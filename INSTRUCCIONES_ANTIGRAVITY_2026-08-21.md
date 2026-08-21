# Instrucciones para Antigravity — 21/8/2026

**Tema único de esta sesión: *"a veces se cae el internet cuando tengo LockSuite"*.**
Escrito por Claude vía Cowork, **sin terminal**: no se compiló, no se corrió `git` y no se
probó en equipo. Lo que sí se hizo: leer el diagnóstico forense que sacaste vos del
celular, encontrar la causa, arreglarla en cuatro archivos y type-checkear el resultado.

Contexto completo en `LOCKSUITE_CONTEXTO_PARA_IA.md` → **B.20** (ahí está la tabla de
mediciones y el porqué de cada cambio). Esto de acá es lo operativo.

---

## 1. Qué pasaba, en tres frases

El proxy global `127.0.0.1:9999` —el que usan el interruptor "bloquear internet" y el
arranque protegido de `BootGate`— **se quedaba clavado** en la configuración del sistema.
Con la red física perfecta debajo: tu propio volcado mostró `ping` a WhatsApp con 0 % de
pérdida y `nc … 443` conectando, mientras las apps decían "sin conexión". Por eso apagar y
prender la VPN lo destrababa: `runFilterLoop()` volvía a llamar a `BootGate.onFilterReady()`,
que sacaba el proxy.

**Tu diagnóstico estaba bien y los dos puntos que marcaste eran los correctos.** Lo que la
lectura del código agregó fueron dos cosas más que también hacían falta, y un detalle que
habría dejado el arreglo sin efecto (está en la sección 4, leelo).

---

## 2. Qué se tocó — cuatro archivos

### 2.1 `LockSuiteApplication.kt` — la causa de fondo

`BootReceiver` es `directBootAware="true"` y escucha `LOCKED_BOOT_COMPLETED`, así que
Android arranca el proceso **antes del primer desbloqueo**, con el almacenamiento cifrado
por credencial sin montar. En ese estado `domainRuleManager.loadRules()` —el **único**
bloque de `onCreate()` que no estaba en try/catch— lanzaba, y una excepción que sale de
`Application.onCreate()` **mata el proceso entero**. Con él se caían el Watchdog, la VPN y
la sincronización con el panel. `LocaleManager.init()` era la segunda bomba, dos líneas
más abajo, por el mismo motivo.

Cómo quedó:

- Los tres objetos del motor DNS (`domainRuleEngine`, `dnsActivityBuffer`,
  `domainRuleManager`) **se construyen siempre**. Son `lateinit` y medio proyecto
  los consulta —`BootReceiver.shouldVpnBeRunning()` entre otros—; si no se asignan,
  cualquier acceso posterior revienta con `UninitializedPropertyAccessException`.
  Construirlos no toca disco.
- Todo lo que necesita preferencias se movió a `initializeUnlocked()`, que corre desde
  `onCreate()` si el equipo ya está desbloqueado, o desde un receptor de
  `ACTION_USER_UNLOCKED` si arrancó bloqueado.
- **`loadRules()` se difiere, no se saltea.** Si no, un proceso que arrancara bloqueado y
  sobreviviera al desbloqueo se quedaba con el motor en CERO reglas y el filtro DNS corría
  vacío en silencio toda su vida. Era una regresión de seguridad muda que nadie habría
  notado. `BootReceiver` además llama a `ensureDomainRulesLoaded()` como segunda red.

### 2.2 `BootGate.kt` — que el proxy no pueda quedar clavado

Tres defectos propios, además del crash:

1. **`release()` salía antes de tocar el proxy si `KEY_ACTIVE` ya estaba en false.** Hay
   varias formas de llegar a "marca apagada + proxy puesto": el proceso muere entre el
   `apply()` y la llamada al DPM, el DPM lanza, la ventana viene de otro arranque. En
   cualquiera de ellas **ningún camino del código volvía a mirar el proxy nunca**.
2. **`KEY_SINCE` guarda `SystemClock.elapsedRealtime()`, que vuelve a cero al reiniciar.**
   Una ventana que sobrevivía a un reinicio daba `elapsed` **negativo**, y `elapsed > techo`
   es falso con un negativo: **el techo de 120 s no vencía jamás**.
3. **`WatchdogWorker` no llamaba al `tick()`** — o sea que el único mecanismo que
   sobrevive a que muera el proceso estaba fuera del circuito.

Cómo quedó: función nueva **`healStuckProxy()`** que **le pregunta al sistema**
(`Settings.Global`) si el proxy está puesto, en vez de deducirlo de una preferencia
nuestra, y lo saca si no hay razón legítima. Se la llama desde el arranque del proceso,
desde `engage()`, desde `tick()` **incluso con la ventana ya cerrada**, desde
`onFilterReady()` y desde el Worker de 15 min. Más: `release()` ya no sale sin mirar el
proxy, y `tick()` trata `elapsed < 0` como vencido.

Es la misma regla que salió de la sesión del 18/8 —**reconciliar, no recordar**— aplicada
donde no estaba.

### 2.3 `WatchdogWorker.kt`

Entra al circuito: `BootGate.tick()` + `BootGate.healStuckProxy()`, en try/catch separados.
Con esto **el peor caso posible pasa de "sin internet hasta que alguien abra la app" a "sin
internet 15 minutos como mucho, y se arregla solo"**.

### 2.4 `BootReceiver.kt`

Una llamada: `LockSuiteApplication.ensureDomainRulesLoaded(context)`, justo después de
`BootGate.engage()`.

---

## 3. Qué hacer, en orden

### Paso 0 — Estado del repo

```powershell
git status
git log -1
git diff --stat
```

Esta sesión no pudo correr `git`. Los cuatro archivos están escritos en el working tree y
**sin commitear**, junto con `LOCKSUITE_CONTEXTO_PARA_IA.md` y este documento. Puede haber
además trabajo de otra sesión sin commitear.

### Paso 1 — Compilar

```powershell
.\gradlew.bat compileDebugKotlin
```

Ya está type-checkeado con `kotlinc` 2.0.21 contra stubs de la API de Android y de las
clases del proyecto: **0 errores / 0 warnings**, con control negativo. Eso no reemplaza a
Gradle, pero acota bastante. Si salta algo, lo más probable es:

- `Context.RECEIVER_NOT_EXPORTED` en `registerUnlockReceiver()` — existe desde API 33 y el
  `compileSdk` es 36, así que debería estar. Si tu configuración no lo resuelve, la
  alternativa es `ContextCompat.registerReceiver(...)` de `androidx.core`.
- `android.provider.Settings.Global.getString(cr, "global_http_proxy_host")` — se leen las
  claves por nombre literal a propósito (ver 4.1). No requiere permiso.

### Paso 2 — Commitear ANTES de probar

Commiteá antes de tocar nada más, así queda un punto de recuperación limpio. Mensaje listo:

```
fix(red): el proxy del arranque protegido ya no puede quedar clavado

Sintoma reportado por el dueno: "a veces se cae el internet cuando tengo
LockSuite", y volvia apagando y prendiendo la VPN. Diagnostico forense por
ADB sobre el equipo real (Android 13, versionCode 88): el proxy global
127.0.0.1:9999 quedaba puesto en Settings.Global con la red fisica
perfecta debajo (ping 0% de perdida, socket TCP al 443 exitoso). Solo
fallaba el trafico HTTP/HTTPS de las apps. Ver B.20.

Causa de fondo: LockSuiteApplication.onCreate() crasheaba en Arranque
Directo. BootReceiver es directBootAware y escucha LOCKED_BOOT_COMPLETED,
asi que Android arranca el proceso antes del primer desbloqueo, cuando el
almacenamiento cifrado por credencial todavia no esta montado.
domainRuleManager.loadRules() era el unico bloque de onCreate() sin
try/catch y lanzaba IllegalStateException; una excepcion que sale de
Application.onCreate() mata el proceso entero, y con el el Watchdog, la
VPN y la sincronizacion. Sin Watchdog, nadie corria el tick() que libera
el proxy. LocaleManager.init() era la segunda bomba, por lo mismo.

Y tres defectos del propio BootGate que convertian ese tropiezo en un
equipo sin internet indefinidamente:
- release() salia antes de tocar el proxy si KEY_ACTIVE ya estaba en
  false, asi que con la marca perdida ningun camino volvia a mirarlo.
- KEY_SINCE guarda elapsedRealtime(), que vuelve a cero al reiniciar: una
  ventana que sobrevivia a un reinicio daba elapsed negativo y el techo de
  120 s no vencia nunca.
- WatchdogWorker (15 min) no llamaba al tick(), siendo el unico mecanismo
  que sobrevive a que muera el proceso.

Cambios:
- LockSuiteApplication.kt: los objetos del motor DNS se construyen
  siempre; leer preferencias se difiere a ACTION_USER_UNLOCKED. loadRules()
  se difiere, no se saltea (si no, el filtro corria con cero reglas en
  silencio). LocaleManager.init() en try/catch.
- BootGate.kt: healStuckProxy() reconcilia contra Settings.Global en vez
  de confiar en una preferencia propia, y libera el proxy huerfano. Se
  llama desde el arranque del proceso, engage(), tick() (incluso con la
  ventana cerrada), onFilterReady() y el Worker de 15 min. release() ya no
  sale sin mirar el proxy. tick() trata elapsed < 0 como vencido.
- WatchdogWorker.kt: entra al circuito del arranque protegido.
- BootReceiver.kt: ensureDomainRulesLoaded() en el broadcast.

Peor caso: de "sin internet hasta que alguien abra la app y toque la VPN"
a "sin internet 15 minutos como mucho, y se arregla solo".

Type-checkeado con kotlinc 2.0.21 contra stubs (0 errores, 0 warnings, con
control negativo). NO compilado con Gradle ni probado en equipo en la
sesion que lo escribio.
```

### Paso 3 — Probar en el equipo real, en este orden

El orden está elegido por **cuál descarta el problema más rápido**, no por importancia.

**A. El crash de Arranque Directo — dos minutos, y es lo que más importa.**

```powershell
adb logcat -c
adb reboot
# NO desbloquear todavia. Esperar ~20 s y mirar:
adb logcat -d | Select-String "AndroidRuntime|LockSuiteApplication|BootGate"
```

- ❌ **No tiene que aparecer ningún `FATAL EXCEPTION`** ni
  `Unable to create application`.
- ✅ Tiene que aparecer `Arranque Directo: el almacenamiento cifrado todavía no está montado`.
- Después desbloqueá con el PIN y volvé a mirar: `Usuario desbloqueado: completando la
  inicialización diferida` y `Reglas DNS cargadas`.

**B. Que las reglas DNS quedaron cargadas de verdad.**
Con una regla de bloqueo puesta desde el panel, reiniciar, desbloquear, y **sin abrir la
app** confirmar que el dominio sigue bloqueado. (Antes, un proceso que arrancaba bloqueado
y sobrevivía se quedaba con el motor vacío.)

**C. La recuperación automática del proxy.** Con LockSuite andando normal:

```powershell
adb shell settings put global global_http_proxy_host 127.0.0.1
adb shell settings put global global_http_proxy_port 9999
# esperar 20-30 s sin tocar nada
adb shell settings get global global_http_proxy_host
adb logcat -d | Select-String "BootGate"
```

Tiene que aparecer en el log `Proxy huérfano detectado ... Liberando la red (ciclo del
Watchdog)`, y `global_http_proxy_host` volver a vacío.

⚠️ **Cómo leer este test si sale a medias.** Acá el proxy se pone escribiendo el ajuste a
mano, no con `dpm.setRecommendedGlobalProxy()`, que es como se pone en la vida real. Si ves
la línea `Proxy huérfano detectado` **pero el ajuste no se limpia**, eso significa que la
**detección funciona** y que lo único que no aplica es el borrado sobre un valor escrito por
fuera del DPM — que en el caso real no ocurre, porque ahí lo puso el DPM. Con la línea en el
log alcanza para dar el test por bueno. Si **no aparece ni la línea**, ahí sí hay un
problema: mirá 4.1.

También en el panel: `bootGateLastResult` tiene que decir
`proxy huerfano liberado (...)`.

**D. Que NO limpia de más — igual de importante que C.**
Encendé "Bloquear internet" desde el panel o desde la app y dejá pasar **dos o tres minutos**
(varios ciclos del Watchdog). El equipo tiene que **seguir sin internet**. Si vuelve el
internet solo, `healStuckProxy()` le está peleando al administrador y eso es peor que el bug
original — avisá antes de publicar.

**E. El ciclo normal del arranque protegido, para confirmar que nada de esto lo rompió.**
Reiniciar con reglas DNS activas: unos segundos sin internet y vuelve solo, con
`bootGateLastResult` = `filtro listo`. Esto ya lo confirmaste el 21/8 antes del cambio, así
que es una regresión-check.

**F. Uso normal.** Media hora de uso real y un par de reinicios, mirando que no reaparezca
el corte. Es el único test que cubre el caso "a veces".

### Paso 4 — Publicar

versionCode **estrictamente mayor que 88** (es lo que hay instalado; verificalo con
`adb shell dumpsys package com.ejemplo.locksuite | Select-String versionCode`, no lo
asumas).

```powershell
.\deploy_all.ps1 -VersionName "0.6.26"
```

Panel: `database` → `functions` → `hosting`, con `functions` en su propio try/catch.

---

## 4. Cosas que NO hay que "simplificar"

### 4.1 `isGateProxyPresent()` mira DOS claves, y no es redundancia

Tu propio volcado del equipo mostró esto:

```
http_proxy = null
global_http_proxy_host = 127.0.0.1
global_http_proxy_port = 9999
```

O sea que en este equipo **`setRecommendedGlobalProxy` no escribe
`Settings.Global.HTTP_PROXY`**, que es la constante pública y la que parece "la buena". Si
la función mirara solo esa, daría `false` justo en el caso real que hay que detectar y todo
el arreglo sería decorativo. Mira el par host/puerto **y** la cadena combinada a propósito,
porque cambia según versión y fabricante.

### 4.2 Los objetos del motor DNS se construyen aunque el equipo esté bloqueado

Son `lateinit`. Si no se asignan, `shouldVpnBeRunning()` y compañía revientan con
`UninitializedPropertyAccessException`. Lo que se difiere es **leer del disco**, no
construirlos.

### 4.3 `loadRules()` se difiere, no se saltea

Si el proceso arranca bloqueado y sobrevive al desbloqueo, sin el receptor de
`ACTION_USER_UNLOCKED` el motor de reglas se queda **vacío** durante toda la vida del
proceso. El filtro seguiría "activo" y no bloquearía nada. Es un fallo silencioso: no hay
error, no hay log, simplemente no filtra.

### 4.4 `healStuckProxy()` respeta `isInternetBlocked()`

Si el administrador tiene "bloquear internet" encendido a propósito, no toca nada. Sacar esa
guarda convierte el arreglo en un agujero: el usuario final recuperaría internet solo.

### 4.5 Y lo de siempre, de sesiones anteriores

`setNetworkGateBlocked()` no escribe la preferencia `internet_blocked`;
`AppController.setEmergencySuspendAll()` no escribe las `suspend_<paquete>`; el pool DNS usa
`DiscardPolicy`; el resolutor de salida se busca en una red sin `TRANSPORT_VPN`; el
antirrebote de 1,2 s y la gracia de 800 ms de `AccessibilityEnforcer`;
`dpm.setUninstallBlocked()` durante el flujo de actualización. La lista completa está en
`INSTRUCCIONES_ANTIGRAVITY_2026-08-17.md` §3.

---

## 5. Si un equipo queda sin internet igual

Rescate manual, por orden de menos a más invasivo:

```powershell
# 1) Ver si es esto:
adb shell settings get global global_http_proxy_host

# 2) Sacarlo a mano:
adb shell settings put global global_http_proxy_host ""
adb shell settings put global global_http_proxy_port 0

# 3) Desde la app: apagar y prender la VPN (dispara onFilterReady -> healStuckProxy).
```

Y después **mirá por qué no se liberó solo**: con este cambio tendrían que haberlo hecho el
`tick()` de 20 s, el Worker de 15 min o el arranque del proceso. Si ninguno lo hizo, el log
de `BootGate` es el lugar donde mirar.

---

## 6. Lo que sigue pendiente, sin cambios

**Ya no está bloqueado por B.8**: el servicio de Accesibilidad **está corriendo** en el
Android 13 del dueño (lo confirmó tu propio `dumpsys activity services`: PID 4190, junto a
la VPN y el Watchdog). Falta solo confirmar el comportamiento en pantalla.

Orden sugerido para lo que quedó de las sesiones anteriores, todo sin probar en equipo:
**B.17** (imágenes: scroll largo, que los recuadros vayan pegados y no parpadeen), **B.13**
(Mercado Pago que NO rebote en inicio ni en pagos), **B.15** (las cuatro protecciones de
accesibilidad, una por una — ojo con el canal de notificación `_nag_v2`: si venís de una
instalación vieja, borrá datos o reinstalá antes de dar el aviso por fallido), **B.19**
(bloqueo de cambio de idioma), **B.9/B.14** (actualización por Play Store) y **B.11**
(suspensión).

Y **B.1 y B.2** siguen siendo los de mayor impacto y menor esfuerzo de toda la lista: el
repo de GitHub sigue público, con la clave HMAC de los presets adentro.
