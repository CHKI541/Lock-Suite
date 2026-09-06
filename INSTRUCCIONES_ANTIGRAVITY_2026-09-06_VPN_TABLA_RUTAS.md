# Instrucciones para Antigravity — 6/9/2026
# "SE CAE EL INTERNET": causa raíz encontrada y medida. Sexta causa, y la que explica el resto.

**Estado:** cinco archivos escritos y type-checkeados en el disco del dueño. **Sin compilar, sin
probar en equipo.** Falta: `./gradlew compileDebugKotlin`, las pruebas de abajo en ese orden, y el
commit (el mensaje está listo al final).

---

## 1. Lo que se midió, y por qué invalida el diagnóstico anterior

El 6/9 se corrieron dos volcados de ADB sobre el equipo del dueño (Samsung Galaxy A06 `SM-A065M`,
Android 16 / SDK 36, LockSuite 0.6.44 / código 107). Están guardados en
`scratch/diag_red_2026-09-06.txt` y `scratch/diag_red_2026-09-06_despues.txt`.

### 1.1. El estado ROTO (primer volcado, con el equipo sin internet)

```
58: tun0: <POINTOPOINT,UP,LOWER_UP> mtu 4000     ← la interfaz viva tiene ÍNDICE 58
    inet 10.0.0.2/32 scope global tun0

13000: from all fwmark 0x0/0x20000 iif lo uidrange 0-10222     lookup 1057
13000: from all fwmark 0x0/0x20000 iif lo uidrange 10224-20222 lookup 1057
16000: from all fwmark 0x1006e/0x1ffff iif lo uidrange 0-10222 lookup 1057
17000: from all iif lo oif tun0 uidrange 0-10222               lookup 1057
                                                                      ↑ TODAS a la 1057

fd00::2 dev tun0 table 1058 proto kernel metric 256    ← las rutas reales de tun0, en la 1058
```

Y en `ip route show table all` **la tabla 1057 no aparece ni una sola vez**: está vacía.

Android numera la tabla de rutas de cada red como **`1000 + índice de la interfaz`**. `tun0` tiene
índice 58 → su tabla es la **1058**. Pero todas las reglas de la red VPN (netId `0x6e` = 110)
apuntan a la **1057**, la tabla de un `tun0` **anterior** que ya no existe.

**Consecuencia exacta:** toda app del equipo queda en la red VPN, busca ruta en una tabla vacía,
cae por descarte a la Wi-Fi, y la consulta al DNS virtual sale por `wlan0` con origen
`192.168.0.7` hacia el router doméstico, que la descarta por ser una IP inexistente en su LAN.
Doce a dieciocho segundos de timeout, todas las consultas, todas las apps. `ping` y TCP por IP
directa siguen perfectos porque la ruta por omisión no se toca. LockSuite tampoco se entera:
es la UID **10223**, justo el hueco entre los rangos `0-10222` y `10224-20222`.

### 1.2. El estado SANO (segundo volcado, después de reiniciar el equipo)

```
1.0.0.1          dev tun0 table 1056 proto static scope link
1.1.1.1          dev tun0 table 1056 proto static scope link
8.8.4.4          dev tun0 table 1056 proto static scope link
8.8.8.8          dev tun0 table 1056 proto static scope link
9.9.9.9          dev tun0 table 1056 proto static scope link
10.0.0.1         dev tun0 table 1056 proto static scope link      ← ESTÁ
10.0.0.2         dev tun0 table 1056 proto static scope link
149.112.112.112  dev tun0 table 1056 proto static scope link
208.67.220.220   dev tun0 table 1056 proto static scope link
208.67.222.222   dev tun0 table 1056 proto static scope link
```

Tras un arranque limpio —un solo `establish()`, sin reestablecimiento de por medio— **las diez
rutas IPv4 están instaladas**, `10.0.0.1/32` incluida, y el número de tabla coincide con el índice
de la interfaz.

### 1.3. Qué queda descartado, con la evidencia al lado

| Hipótesis del informe anterior | Veredicto |
|---|---|
| "La máscara `/32` hace que Netd descarte las rutas on-link IPv4" | **FALSO.** El §1.2 muestra las diez rutas instaladas con `addAddress("10.0.0.2", 32)` sin tocar. Android no exige que el destino pertenezca a la subred de la interfaz. |
| "En IPv4 la tabla del túnel tiene CERO rutas" | **FALSO en el archivo que se generó para probarlo.** Las tiene. |
| "Hay que pasar a `/24`" | **NO HACERLO.** No arregla nada y **rompe equipos**: `10.0.0.0/24` es uno de los rangos LAN domésticos más comunes que existen (varios ISP usan `10.0.0.1` de gateway). Enrutar el `/24` entero a un túnel que descarta todo lo que no sea UDP/53 deja sin internet a los equipos de esas redes. |
| "`rp_filter` descarta la respuesta inyectada" | **Mecanismo al revés.** `rp_filter` valida la dirección de **origen** del paquete entrante, no la de destino. Y no llega a importar: la consulta nunca entra al túnel, así que no hay respuesta que inyectar. |
| "Always-On VPN sin túnel completo" | **Descriptivo, no causal.** Que todas las apps queden en la VPN con `10.0.0.1` de DNS es el diseño funcionando. |
| "Es LockSuite, es DNS, el transporte IP está sano" | **CORRECTO.** Esa parte del informe estaba bien. |

> **Lección de método, la tercera vez que aparece en este proyecto** (ya está en B.40 punto 13 y en
> B.22): un informe se escribió describiendo un archivo que decía lo contrario. **Reabrir el
> archivo antes de concluir**, siempre. Acá alcanzaba con un `grep "10.0.0.1" diag_..._despues.txt`.

---

## 2. La causa raíz: LockSuite se pisaba a sí misma al reestablecer el túnel

En tres lugares se hacía `stopVpn(); startVpn()` **pegados, sin un milisegundo en el medio**:

1. `restartVpn()` — callback de cambio de red.
2. `onRevoke()` — cuando el sistema revoca la VPN.
3. `onStartCommand("RESTART_VPN")` — que **`DomainRuleManager` disparaba cada vez que se tocaba
   una regla DNS**.

`stopVpn()` cierra el descriptor y a partir de ahí **el sistema destruye `tun0` de forma
asincrónica**. `startVpn()` llamaba a `establish()` en la línea siguiente, o sea que el túnel nuevo
se creaba mientras el viejo todavía se estaba muriendo. El sistema calcula el número de tabla en un
momento y engancha la interfaz en otro → las reglas quedan apuntando a la tabla del túnel viejo y
las rutas se instalan en la del nuevo.

**Y esto explica, por fin, el detalle que estuvo en el reporte del dueño desde el primer día:**
*"vuelve apagando y prendiendo la VPN"*. A mano pasan segundos entre las dos cosas: la interfaz
vieja termina de irse, la nueva se crea sola, y los dos números coinciden. Por eso el apagar-prender
siempre funcionó, por eso nunca se pudo reproducir a pedido, y por eso el síntoma volvió después de
cada uno de los cinco arreglos anteriores (B.18 ×2, B.20, B.21, B.24): **todos eran bugs reales,
pero ninguno era éste.**

---

## 3. Qué se cambió (cinco archivos, ya escritos en el disco)

### 3.1. `service/KosherVpnService.kt` — el arreglo de la causa raíz

- **`scheduleRestart(settleMs, reason)` nueva.** Baja el túnel, deja que el sistema termine de
  soltar la interfaz (**2 s**, o **3,5 s** en una auto-reparación) y recién ahí levanta el nuevo.
  Reemplaza los tres `stopVpn(); startVpn()`.
- **`stopVpn(keepForeground: Boolean = false)`.** Durante la espera no se suelta la notificación de
  primer plano: si no, el proceso queda unos segundos como servicio común y el sistema lo puede
  degradar justo ahí. Todos los llamadores viejos siguen funcionando por el valor por omisión.
- **`restartPendingUntilMs`.** Sin esto la espera se saltea sola: durante esos segundos
  `isTunnelRunning` vale `false`, así que si el ciclo de 20 s del Watchdog cae en la ventana,
  `ensureVpnRunning()` arranca el túnel antes de tiempo y se reintroduce el bug. Con la marca,
  `startVpn()` se niega a arrancar mientras la espera esté corriendo.
- **Acción `RELOAD_RULES` nueva.** Recarga el Trie de reglas en caliente, sin rehacer el túnel.
- **Acción `HEAL_VPN` nueva.** La usa la auto-reparación.
- **Arreglo de un doble cierre de descriptor** en el `finally` de `runFilterLoop()`. `input`/`output`
  envuelven el descriptor crudo **sin duplicarlo**. Si el hilo es el de un túnel viejo —justo lo que
  pasa en un reestablecimiento— la `ParcelFileDescriptor` ya la cerró `stopVpn()`, y el sistema
  puede haberle reasignado ese mismo número al túnel **nuevo**: cerrarlo ahí cerraba el túnel bueno.
  Ahora solo se cierra si seguimos siendo el túnel actual.
- **Constantes `TUNNEL_DNS_V4/ADDR_V4/DNS_V6/ADDR_V6`**, fuente única de verdad. Estaban duplicadas
  a mano acá y en `NetworkForwarder`, y **ese duplicado fue la causa 1 de B.18** (faltaba `fd00::1`
  en la lista de exclusión). Ahora no se pueden desincronizar.

### 3.2. `service/KosherVpnService.kt` — la medición que faltaba (bloque "SALUD DEL TÚNEL")

Hasta hoy, "¿el filtro está funcionando?" se contestaba con `isTunnelRunning`, que solo dice si el
**hilo** está vivo. Y el hilo puede estar perfectamente vivo, bloqueado en `read()`, sobre un túnel
al que el sistema no le enruta ni un paquete: eso es exactamente lo que pasó. Para el usuario, "se
cayó internet"; para LockSuite y para el panel, todo verde.

- `tunnelPacketsIn` / `tunnelResponsesOut` (dos `AtomicLong`) y `tunnelReadyAtMs`.
- `tunnelHealth(context)` → `OK` / `APAGADO` / `SIN_DATOS` / `SIN_CAPTURA` / `SIN_SALIDA`.
  Devuelve `SIN_DATOS` siempre que la pregunta no se pueda contestar con honestidad: túnel recién
  levantado (150 s de gracia), pantalla apagada (en Doze es normal no resolver nada) o sin ninguna
  red física validada. **Es a propósito:** un falso positivo cuesta un reinicio de túnel, y un
  reinicio de túnel son un par de segundos sin resolver para todo el equipo.
- `healIfBroken(context)` → si está roto, hace el mismo apagar-y-prender que el dueño viene haciendo
  a mano, con la espera que hace falta. Con un enfriamiento de 5 minutos entre intentos.

**Costo:** dos incrementos atómicos por paquete y una comparación dentro del ciclo de 20 s que el
Watchdog ya hacía. **Cero hilos nuevos, cero despertares nuevos, cero llamadas al sistema nuevas.**

### 3.3. Los otros cuatro archivos

| Archivo | Cambio |
|---|---|
| `util/NetworkForwarder.kt` | `TUNNEL_ADDRESSES` deriva de las constantes nuevas; se llama a `noteTunnelResponse()` después de cada escritura al túnel (las dos: respuesta reenviada y respuesta de bloqueo). |
| `dns/DomainRuleManager.kt` | `RESTART_VPN` → `RELOAD_RULES`. Una línea, y saca **el disparador más frecuente** de los tres. |
| `service/WatchdogForegroundService.kt` | Una llamada a `KosherVpnService.healIfBroken()` dentro del ciclo de 20 s que ya existía. |
| `util/FirebaseDeviceSync.kt` | Reporta `dnsTunnelHealth`, `dnsTunnelPacketsIn` y `dnsTunnelHeals` al panel. |

### 3.4. Verificación ya hecha

`kotlinc` 2.0.21 contra stubs de la API de Android escritos para esto, con el código **extraído del
archivo real** (no transcripto): **0 errores / 0 warnings**. **Ocho controles negativos** (campo
inexistente, tipo equivocado ×2, aridad ×2, método inexistente, constante inexistente, parámetro
mal escrito): los ocho detectados, o sea que el chequeo estaba vivo. Balance de llaves y paréntesis
verde en los cinco archivos. **No se corrió Gradle y no se probó nada en equipo.**

---

## 4. Pruebas en equipo real, en este orden

### Prueba 0 — que no se rompió nada (va primero, es la regresión a vigilar)

Instalar, esperar un minuto y confirmar: hay internet, los dominios resuelven, y un dominio
bloqueado sigue fallando al instante. Si esto falla, nada de lo demás importa.

### Prueba 1 — LA PRUEBA DE LA CAUSA RAÍZ (la más valiosa)

Guardá esto como `check_tun.ps1` y correlo cuando quieras:

```powershell
$linea = (adb shell ip addr show tun0 | Select-Object -First 1)
$idx   = [int]($linea -split ':')[0]
$regla = (adb shell ip rule show | Select-String "uidrange 0-10222" | Select-Object -First 1)
$tabla = [int](($regla -split "lookup ")[1].Trim())
Write-Host "tun0 indice=$idx  ->  tabla esperada=$(1000+$idx)   reglas apuntan a=$tabla"
if ($tabla -eq (1000+$idx)) { Write-Host "VERDE: coinciden" -Foreground Green }
else { Write-Host "ROJO: NO coinciden -> el equipo se va a quedar sin DNS" -Foreground Red }
```

Ahora forzá los tres disparadores y confirmá **VERDE después de cada uno**:

1. **Cambiar una regla DNS** desde la app o el panel (agregar y sacar un dominio bloqueado).
   Antes esto tiraba el túnel abajo; ahora ni lo toca. Confirmá además que **el internet no se corta
   ni un segundo** durante el cambio — eso solo ya es un cambio visible para el dueño.
2. **Handoff de red**, cinco veces seguidas: apagar Wi-Fi → esperar a que agarre datos → prender
   Wi-Fi → esperar. `check_tun.ps1` entre cada una.
3. **Reiniciar el equipo** y correr `check_tun.ps1` a los dos minutos.

Si en algún punto sale ROJO, mandá la salida de `adb shell ip rule show` y
`adb shell ip route show table all` completas: significa que 2 s de espera no alcanzan en ese equipo
y hay que subir `RESTART_SETTLE_MS`.

### Prueba 2 — la auto-reparación

Con el equipo en el estado ROJO (si lográs reproducirlo), dejarlo **con la pantalla encendida** y
esperar. A los ~150 s el Watchdog tiene que detectarlo y repararlo solo. En `adb logcat -s KosherVPN`
tiene que aparecer:

```
Tunel en mal estado (SIN_CAPTURA): paquetes=0 respuestas=0. Auto-reparacion #1.
Reestableciendo tunel (auto-reparacion por tunel sin trafico). Espera de 3500 ms ...
```

y después `check_tun.ps1` tiene que dar VERDE y el internet volver **solo, sin tocar nada**.

### Prueba 3 — que no haya falsos positivos (importante)

Dejar el equipo un rato con la pantalla apagada y otro rato en modo avión, y confirmar en el logcat
que **NO** aparece ninguna "Auto-reparacion". Si aparecen reparaciones sin que haya un problema, los
tres filtros de `tunnelHealth()` (gracia, pantalla, red validada) no están alcanzando y hay que
subir `CAPTURE_GRACE_MS`.

### Prueba 4 — el panel

En Firebase, el nodo del dispositivo tiene que traer `dnsTunnelHealth` = `OK` (o `SIN_DATOS` con la
pantalla apagada), `dnsTunnelPacketsIn` creciendo, y `dnsTunnelHeals` = 0.

---

## 5. Pendiente que NO se hizo, y por qué

- **Mostrar `dnsTunnelHealth` en el panel.** Los campos ya viajan; falta dibujarlos en
  `admin-backend/public/`. No se hizo porque esta sesión no puede desplegar y porque el arreglo no
  lo necesita. Cuando se agregue, va como aviso rojo si el valor es `SIN_CAPTURA` o `SIN_SALIDA`, y
  hay que subir el cache-buster de `app.js` en `index.html`.
- **Cambiar el direccionamiento del túnel de `10.0.0.x` a un rango que no colisione.** Hay un
  problema latente real: `10.0.0.1` es el gateway por omisión de varios ISP, y en esas redes
  LockSuite enruta el router del usuario al túnel. **No se tocó a propósito**: no es la falla de
  hoy, y cambiar el direccionamiento toca el 100 % de la flota. Ahora las direcciones están en
  cuatro constantes en un solo lugar, así que el día que se decida es un cambio de cuatro líneas
  — y va con la misma red de seguridad que el MTU (si `establish()` falla, reintentar con las
  direcciones viejas).
- **`DISALLOW_CONFIG_PRIVATE_DNS`** (B.30/B.33) sigue siendo la mejora de batería más grande que
  queda pendiente.

---

## 6. Commit

```
fix(vpn): arreglar la causa raiz de "se cae el internet" — el tunel se reestablecia
encima de la interfaz anterior y la red VPN quedaba apuntando a una tabla de rutas vacia

Medido por ADB sobre el equipo del dueno (Galaxy A06, Android 16 / SDK 36) el 6/9/2026:
la interfaz viva era tun0 indice 58 (tabla 1058) pero TODAS las reglas de la red VPN
apuntaban a la tabla 1057 — la del tun0 anterior, ya inexistente y vacia. Con esa tabla
vacia toda app cae por descarte a la Wi-Fi y la consulta al DNS virtual sale por wlan0
hacia el router, que la descarta: 12-18 s de timeout en todas las consultas del equipo.
Ping y TCP por IP directa seguian perfectos, y LockSuite tampoco se enteraba porque su
UID esta excluida del tunel.

Lo producia la propia app: restartVpn(), onRevoke() y RESTART_VPN hacian
stopVpn(); startVpn() pegados, o sea que creaban el tunel nuevo mientras el sistema
todavia estaba destruyendo el viejo de forma asincronica. Explica por que apagar y
prender la VPN a mano siempre lo arreglaba (segundos entre una cosa y la otra) y por
que el sintoma volvia despues de cada uno de los cinco arreglos anteriores.

- KosherVpnService: scheduleRestart() con espera de 2 s (3,5 s al auto-repararse) en
  lugar de los tres stopVpn();startVpn(); marca restartPendingUntilMs para que el
  Watchdog no adelante el arranque durante la espera; stopVpn(keepForeground) para no
  soltar el primer plano en la ventana; acciones RELOAD_RULES y HEAL_VPN nuevas.
- KosherVpnService: bloque SALUD DEL TUNEL — contadores de paquetes que entran y
  respuestas que salen, tunnelHealth() y healIfBroken(). Detecta y repara solo el caso
  "el hilo esta vivo pero el sistema no le enruta nada", que era invisible hasta hoy.
- KosherVpnService: arreglo de un doble cierre de descriptor en el finally del bucle de
  lectura (podia cerrar el tunel NUEVO si el fd se reasignaba).
- KosherVpnService/NetworkForwarder: las direcciones del tunel pasan a ser constantes
  unicas; ese duplicado fue la causa 1 de B.18.
- DomainRuleManager: cambiar una regla DNS ya no rehace el tunel (RELOAD_RULES).
- WatchdogForegroundService: llama a healIfBroken() en el ciclo de 20 s que ya existia.
- FirebaseDeviceSync: reporta dnsTunnelHealth/PacketsIn/Heals al panel.

NO se cambio la mascara /32 del tunel: se midio que con /32 el sistema instala las diez
rutas IPv4 pedidas (10.0.0.1 dev tun0 table 1056 proto static scope link). Pasarla a /24
no arregla nada y romperia los equipos conectados a redes LAN 10.0.0.0/24.

Type-check con kotlinc 2.0.21 contra stubs, 0 errores / 0 warnings, con 8 controles
negativos, los 8 detectados. Sin compilar con Gradle y sin probar en equipo.
```

Ojo con `deploy_all.ps1`: hace `git add .`, así que commiteá aparte lo que no quieras que entre
(los `.txt` de `scratch/` no deberían subir — ver B.32).
