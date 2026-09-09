# Instrucciones — Perfiles maestros de alta (B.57) — 9/9/2026

> **✅ LOS NUEVE ARCHIVOS ESTÁN ESCRITOS. NO HACE FALTA APLICAR NINGÚN PARCHE.**
>
> Durante esta sesión, **otra conversación del dueño estaba trabajando sobre el mismo árbol
> al mismo tiempo** (tomó B.54, B.55 y B.56, y el cache-buster `v=32`). Se pisaron en cinco
> archivos. Todo quedó **fusionado**, no reemplazado:
>
> | Archivo | Cómo se resolvió |
> |---|---|
> | `PolicyManager.kt` | Los cambios de esta sesión se aplicaron **encima** de los de la otra (`autoGrantDeclaredPermissions`, su B.55). Conviven: +260 líneas en total. |
> | `admin-backend/public/app.js` | Ídem: la tarjeta "Última actualización" de ellos + los perfiles maestros de esta sesión. |
> | `admin-backend/public/index.html` | Ídem. Cache-buster **`v=33`** (ellos lo habían dejado en `v=32`). |
> | `LOCKSUITE_CONTEXTO_PARA_IA.md` | B.57 insertado después de su B.56, sin tocar nada de lo suyo. |
>
> Esta entrega quedó numerada **B.57** por eso.
>
> **Lo primero que hay que correr, antes de compilar:**
> ```powershell
> cd "E:\Documentos\Lock Suite segunda version"
> python tools\check_profile_sync.py    # tiene que salir ✅ y exit 0
> python tools\check_whitelist_sync.py  # tiene que salir OK y exit 0
> ```
>
> En `scratch\` quedaron `B55_PolicyManager.patch` y `PolicyManager_B55_completo.kt`, que
> eran el plan B por si la fusión no se podía hacer. **Ya no hacen falta: borralos**, y
> acordate de que `scratch\` no debe ir a GitHub (ver B.32 y la advertencia de
> `deploy_all.ps1`).

---

## 1. Qué se hizo y por qué (resumen de una pantalla)

El dueño le pidió a Antigravity que le explicara cómo funcionan los MDM comerciales
(MB Smart) y le llegó una lista de cuatro mejoras. Esta sesión las analizó contra el código
real. **Tres son buenas ideas y una no conviene hoy**; el análisis completo está en §4.

De las tres, se implementó la de mayor impacto y menor riesgo: **perfiles maestros de alta**,
porque ataca directo lo que el dueño describió como el problema ("configurar un equipo desde
cero lleva tiempo y paciencia", "más de 60 switches") y porque **reveló un techo medido que
nadie había visto**.

### El hallazgo que cambia el diseño, y está medido

`sendCommandV8` rechaza con **HTTP 413** cualquier `presetJson` de más de **3.000 bytes**
(el `data` de un mensaje FCM tiene un tope duro de ~4 KB). Se midió el perfil completo:

| | bytes | resultado |
|---|---|---|
| El perfil de políticas de hoy | **2.009** | pasa (67 % del tope) |
| El mismo + lo que B.28 dice que le falta (apps suspendidas, apps ocultas, lista blanca) | **3.580** | **rechazado (413)** |

O sea: **el perfil completo no entra por FCM y nunca pudo entrar.** B.28 tenía razón al
anotar lo que faltaba, pero no sabía que agregarlo era imposible por esa vía. Por eso esta
entrega no es "un botón más": mueve el transporte a `globalSettings/profiles/<id>`, que es
el patrón que estrenó la lista blanca en B.53.

### Dos bugs reales encontrados de paso

1. **`captivePortalCoverImages` viajaba en el perfil del panel y NADIE la leía.** El panel
   la incluye y la firma desde el 8/9 (`app.js` línea ~2004), pero ni
   `exportPolicyPresetJson()` la escribía ni `importPolicyPresetJson()` la leía. Un perfil
   que decía "no tapar las imágenes del portal cautivo" se aplicaba con éxito y no hacía
   nada. **Es exactamente la forma de `no_apps_control` (B.28) y de
   `DISALLOW_CONFIG_DATE_TIME` (B.38): la cuarta vez.** Arreglado en los dos lados.
2. **Los tres interruptores de la lista blanca (B.53) no estaban en el perfil**, ni en la app
   ni en el panel. **Tercera vez que el perfil se queda atrás de las protecciones nuevas**
   (ya lo anotaron B.28 y el punto 8 de B.40). Agregados en los dos lados.

Para que sea la última vez, se escribió **`tools/check_profile_sync.py`**: compara las tres
listas que ESCRIBEN un perfil contra la única que lo LEE, y sale con código 1 si alguna
clave se escribe y no se lee. Es el hermano del chequeo de simetría de B.38 y de
`check_whitelist_sync.py` de B.53. **Correrlo antes de `deploy_all.ps1`.**

---

## 2. Los trece archivos

| Archivo | Qué cambió |
|---|---|
| **`app/mdm/EnrollmentProfiles.kt`** *(nuevo)* | Los tres perfiles como datos puros. Sin `Context`, sin preferencias: se puede correr fuera del equipo. |
| `app/mdm/PolicyManager.kt` ⚠️ *(parche)* | `applyMasterProfile()`, `addDeviceStateToPreset()`, `applyDeviceStateFromPreset()`, las 4 claves que faltaban en export/import, y los dos getters de reporte. |
| `app/util/FirebaseDeviceSync.kt` | `pullAndApplyProfile()` (lee `globalSettings/profiles/<id>`) + `masterProfileId`/`masterProfileAt` al panel. |
| `app/service/LockSuiteFirebaseService.kt` | Comandos `APPLY_MASTER_PROFILE` y `APPLY_PROFILE`. |
| `app/ui/dashboard/DashboardActivity.kt` | Tarjeta "⚡ Configuración rápida" con los tres niveles y confirmación con el aviso adentro. |
| `admin-backend/functions/index.js` | Los dos comandos en `ALLOWED_COMMANDS` + validación de `level` y `profileId` (y un 404 con causa si el perfil no existe). |
| `admin-backend/public/app.js` | Sección de perfiles maestros, ruta de aplicación grande, las 3 claves de lista blanca en el perfil, y el perfil de alta en la barra lateral. |
| `admin-backend/public/index.html` | La sección nueva + la línea del perfil aplicado. **Cache-buster a `v=33`.** |
| **`tools/check_profile_sync.py`** *(nuevo)* | El chequeo de simetría. |
| `PROMPTS_PARA_OTRAS_CONVERSACIONES_2026-09-09.md` *(nuevo)* | Los cuatro prompts para las funciones que quedan. |
| `LOCKSUITE_CONTEXTO_PARA_IA.md` | B.57 nuevo, sección C reemplazada. |
| `scratch/B55_PolicyManager.patch` *(temporal)* | El parche del recuadro de arriba. **Borrar después.** |
| `scratch/PolicyManager_B55_completo.kt` *(temporal)* | Referencia. **Borrar después.** |

### Convivencia con la otra conversación del dueño

Esa sesión tomó el número **B.54** (tarjeta "Última actualización" en el panel) y el
cache-buster **`v=32`**. Esta sesión:

- se corrió a **B.57** y a **`v=33`**;
- **reaplicó sus cambios de `app.js` e `index.html` ENCIMA de los de esa sesión** (se
  bajaron las versiones reales del disco y se parcheó sobre ellas), así que esos dos
  archivos llevan los dos trabajos;
- escribió todo con `expectedMtimeMs`, que es lo único que evitó pisar `PolicyManager.kt`.

**Lección de método para anotar:** dos agentes sobre el mismo árbol se pisan por minutos, y
el `expectedMtimeMs` es barato. Ya había pasado el 31/8 (B.22, "mirar la hora antes de decir
que el reporte del otro no coincide con el disco"); esta vez lo agarró la herramienta.

---

## 3. Cómo probarlo en equipo real (en este orden)

La 1 es la que evita el desastre y va primero.

1. **Con nada aplicado, que no se rompió nada.** Abrir el panel con **Ctrl+F5**, entrar a un
   celular, confirmar que los interruptores se ven y responden como siempre, y que
   `python tools/check_profile_sync.py` sale en verde. La barra lateral tiene que decir
   *"⚡ Sin perfil de alta: se configuró a mano."*
2. **Guardar un perfil desde el panel y volver a aplicarlo al mismo equipo.** Es la
   regresión importante: se le agregaron 4 claves al objeto firmado, así que si la firma
   HMAC se rompió, esto falla con *"archivo alterado"*. Tiene que aplicar bien.
3. **Aplicar el Nivel 3 (Base mínima) en un equipo de descarte.** Es el que menos hace y el
   más seguro para estrenar la ruta. Confirmar que el celular acka `applied` y que la barra
   lateral pasa a decir *"⚡ Configurado con: Nivel 3 — Base mínima"* con la fecha.
4. **Reiniciar ese equipo** y confirmar que las restricciones siguen puestas. Es la prueba
   de que `reapplyAllRestrictions()` las repone — el bug de B.38 / S-3 / P-3. *(Ya se
   verificó contra el código que las 19 restricciones que usan los perfiles están en las
   cuatro listas, pero eso es lectura, no equipo real.)*
5. **Aplicar el Nivel 1 (Kosher estricto) en un equipo de descarte, DESPUÉS de agregarle la
   cuenta de Google.** ⚠️ Bloquea `DISALLOW_MODIFY_ACCOUNTS`: si se aplica antes, el equipo
   no se puede dar de alta. El aviso está en la confirmación de la app y del panel; **esta
   prueba es la que confirma que el aviso alcanza**.
6. **Desde la app del celular**, pestaña Presets → "⚡ Configuración rápida" → aplicar un
   nivel. Tiene que funcionar **sin red** (el perfil está en el APK) y la tarjeta tiene que
   quedar marcada con "✓ aplicado".
7. **La ruta grande.** Guardar un perfil desde el panel en un equipo con muchas apps
   suspendidas, para que pase de 3.000 bytes, y aplicarlo. Antes daba
   *"El perfil es demasiado grande para enviarlo de forma remota"* (413); ahora tiene que
   pasar por `globalSettings/profiles/` y aplicarse. **Si falla, mirá primero las reglas de
   `database.rules.json`** — están OK (`globalSettings` tiene `.read: true` y escritura solo
   para admins autorizados, verificado esta sesión), pero es lo primero que hay que
   descartar.
8. **Suspender LockSuite con un perfil aplicado y reanudar**: tiene que volver todo.

---

## 4. El análisis de las cuatro propuestas de MB Smart (lo pidió el dueño)

**4.1 — Perfiles "1-Click" → SÍ, y es la más importante. HECHO (B.57).**
Era la de mayor impacto y menor riesgo: reusa el motor de presets que ya existe y probado,
no toca la Capa 2 (que es la parte frágil del proyecto), y ataca exactamente el dolor que el
dueño describió. Además destapó el techo de 3.000 bytes, que bloqueaba a B.28 sin que nadie
lo supiera.

**4.2 — Tienda con solicitudes de apps → SÍ, es la segunda. PROMPT A.**
Es la que más se parece a "lo que hace que MB Smart se sienta fácil". Casi todo el motor ya
existe (`SelfUpdater.downloadAndInstallApk()`, el nodo `storeApps`, `allowedPackages`).
**Pero tiene un bloqueante que va primero: B.6 sigue abierto** — la tienda instala apps por
primera vez, y ahí `ApkSignatureVerifier` (B.37) no puede comparar contra nada. Hoy se
descarga y se instala un APK **sin verificar absolutamente nada**, en silencio, como Device
Owner. Eso hay que cerrarlo con `sha256` en `storeApps` antes de invitar al usuario a usar la
tienda. Está todo en el prompt.

**4.3 — Categorización DNS en la nube → NO conviene hoy. PROMPT D, con las objeciones.**
Suena muy bien y tiene tres problemas concretos, verificados contra el código:

1. **Hay un camino que dejaría el equipo sin filtrar, en silencio.**
   `NetworkForwarder` reintenta contra `8.8.8.8` y `1.1.1.1` ante cualquier `IOException`
   (líneas ~184-185 y ~264-265). Eso hoy está bien: es la red de seguridad de B.21/B.24. Pero
   si el resolutor de arriba pasa a ser el que filtra, **cada timeout manda la consulta a un
   resolutor abierto y la deja resolver**. El usuario no ve nada raro; el filtro no está.
2. **`upstream_dns_ip` es configuración muerta y está en el lugar equivocado.** Se lee en un
   solo lugar (línea ~174), no tiene setter, ni comando, ni interruptor — y se usa solo como
   *último recurso*, no como resolutor principal. Invertir esa prioridad choca de frente con
   B.21, cuya corrección del CGNAT depende de `bindSocket()` a la red física y de preferir su
   propio DNS. Ese arreglo costó dos sesiones de diagnóstico forense.
3. **Se pisa con B.53, que es más fuerte y ya está hecho.** Una lista blanca estricta bloquea
   todo lo que no esté permitido; un categorizador permite todo lo que no esté categorizado
   como malo. Y las categorías comerciales (adultos, malware, apuestas) **no son** las
   categorías que le importan a un equipo kosher.

**Recomendación:** dejarlo para después de que la lista blanca de B.53 esté probada en
equipo. Si igual se quiere, el prompt D trae las tres correcciones obligatorias previas.

**4.4 — Invertir la postura de WebViews (lista blanca) → YA ESTÁ DECIDIDO QUE NO.**
El dueño la rechazó textualmente el 4/9 (*"lista blanca no quiero"*, registrado en B.45), y
B.53 cubrió buena parte del mismo territorio por DNS. **Pero la idea técnica que hay detrás
—el "IAB Finder" de MB Smart— es la mejor de las cuatro y NO necesita lista blanca:** es un
detector **estructural** de navegadores embebidos por jerarquía de vistas, que encaja con la
regla que este proyecto ya tiene escrita en B.19 punto 3. Eso cierra B.44 de raíz sin
contradecir al dueño. **PROMPT B.**

---

## 5. Verificación hecha en esta sesión

**No se corrió Gradle y no se probó nada en equipo real.** Lo que sí se hizo:

- **`kotlinc` 2.0.21 contra stubs escritos a mano, con el código EXTRAÍDO de los archivos
  reales y no transcripto**, en tres bancos: `EnrollmentProfiles.kt` completo, los bloques
  nuevos de `PolicyManager.kt` (5 funciones), `pullAndApplyProfile` de `FirebaseDeviceSync`,
  y las dos ramas nuevas del `when` de `LockSuiteFirebaseService`. **0 errores / 0 warnings.**
- **12 controles negativos sobre los type-checks, los 12 detectados** (método inexistente,
  tipo equivocado, enum inexistente, campo inexistente, preferencia mal tipada, rama que no
  devuelve `Boolean`, uso de nullable, `latch` inexistente, retorno mal tipado…).
- **Prueba de comportamiento de verdad: 91 aserciones sobre `EnrollmentProfiles.buildData()`,
  todas verdes**, corriendo el código real contra el `org.json` real. Incluye lo que ningún
  perfil puede encender (kiosco, táctil apagado, suspender todo, internet cortado, modo
  estricto de la cuenta de Google, los tres de la lista blanca), que los tres niveles están
  **anidados** (base ⊆ trabajo ⊆ estricto), que el piso anti-manipulación está en los tres, y
  que las claves de restricción tienen forma de constante real de Android.
  **8 controles negativos sobre ese banco, los 8 detectados.**
- **`tools/check_profile_sync.py` en verde, con 4 controles negativos, los 4 detectados** —
  incluido el que reproduce el bug real de B.40 punto 8 (`kioskLockTaskEnabled` en vez de
  `kioskLockTask`) y el de `captivePortalCoverImages`.
- **Chequeo de simetría de B.38** sobre las cuatro listas de restricciones: las 19 que usan
  los perfiles están en `reapplyAllRestrictions()`, `liftAllForSuspension()` y
  `clearAllRestrictions()`. Ninguna se pierde al reiniciar, al suspender ni al purgar.
- `node --check` en los dos `.js`, con control negativo. `check_whitelist_sync.py` en verde.
- Balance de llaves/paréntesis/corchetes en los cinco `.kt` tocados.

---

## 6. Lo que queda abierto a propósito

- **El perfil sigue sin guardar** bloqueos de WebView, modos de imagen por app y la lista
  blanca del launcher. Se agregaron apps suspendidas, apps ocultas y reglas DNS, que son las
  que se configuran en cada alta; el resto se puede sumar cuando haga falta, ahora sin techo
  de tamaño.
- **`applyDeviceStateFromPreset()` es ADITIVO**: suspende y oculta lo que el perfil nombra y
  **nunca libera nada**. Es fallo-cerrado a propósito, y está explicado en el comentario de
  la función. No lo "simplifiques" a un espejo del equipo de origen: un perfil hecho en un
  equipo donde el navegador nunca se instaló no lo nombra, y espejarlo lo DESBLOQUEARÍA.
- **La firma HMAC de los perfiles sigue siendo la clave pública de B.5.** Un perfil maestro
  firmado con ella es falsificable. No empeora nada (los presets ya estaban así) pero sube de
  prioridad ahora que hay una ruta remota más cómoda para aplicarlos.
- **No hay perfiles por grupo.** Deliberado, misma razón que B.12 y que la lista blanca de
  B.53: aplicar un perfil a un grupo entero con un clic es demasiado fácil de hacer sin
  querer.

---

## 7. Mensaje de commit listo para copiar

```
feat(perfiles): perfiles maestros de alta en un toque, y el perfil deja de topar en FCM

El dueno pidio que configurar un equipo dejara de ser sesenta interruptores. Se
agregan tres perfiles de alta escritos DENTRO del APK (Kosher estricto / Trabajo /
Base minima), aplicables de un toque desde la app y desde el panel, sin red y sin
cuenta de Google configurada — que es justo el momento en que mas falta hacen.

El hallazgo que cambia el diseno esta MEDIDO: sendCommandV8 rechaza con 413 a los
3.000 bytes, el perfil de politicas de hoy pesa 2.009, y sumandole lo que B.28 dejo
anotado como faltante llega a 3.580. O sea que el perfil COMPLETO nunca pudo viajar
por FCM. Se agrega la ruta globalSettings/profiles/<id> (mismo patron que
SYNC_WHITELIST de B.53), con el perfil guardado como CADENA para que Realtime
Database no le cambie la forma y le rompa la firma HMAC — que es literal el bug de
B.28. El envio directo se mantiene para los perfiles que entran, asi los equipos ya
instalados no cambian de comportamiento.

Dos bugs reales encontrados de paso, los dos de la misma forma que
`no_apps_control` (B.28):
  · captivePortalCoverImages viajaba firmada desde el panel desde el 8/9 y NADIE
    la leia: el perfil la traia y no hacia nada.
  · Los tres interruptores de la lista blanca (B.53) no estaban en el perfil, ni en
    la app ni en el panel. Tercera vez que el perfil se queda atras.
Para que sea la ultima, se agrega tools/check_profile_sync.py, que compara las tres
listas que escriben un perfil contra la unica que lo lee y falla si alguna clave se
escribe y no se lee.

Verificacion: kotlinc 2.0.21 contra stubs con el codigo extraido de los archivos
reales, 0 errores / 0 warnings, con 12 controles negativos detectados; 91 aserciones
de comportamiento sobre EnrollmentProfiles.buildData() contra el org.json real, con
8 controles negativos detectados; check_profile_sync.py y check_whitelist_sync.py en
verde con sus propios controles negativos; simetria de B.38 verificada sobre las
cuatro listas de restricciones. Sin Gradle y sin probar en equipo real.

Ver B.57 y INSTRUCCIONES_ANTIGRAVITY_2026-09-09_PERFILES_MAESTROS.md.
```

⚠️ **Antes de correr `deploy_all.ps1`** (que hace `git add .`): sacar del árbol
`scratch\B55_PolicyManager.patch`, `scratch\PolicyManager_B55_completo.kt` y los
`scratch\diag_red_2026-09-06*.txt` que siguen pendientes desde el 6/9 (B.32), o agregar
`scratch/` al `.gitignore` de una vez.
