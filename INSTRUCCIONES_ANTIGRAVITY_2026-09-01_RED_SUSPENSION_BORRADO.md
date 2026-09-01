# Instrucciones para Antigravity — 1/9/2026

## Red (VPN), suspensión de LockSuite y borrado de LockSuite

Esta sesión de Claude **no tocó una sola línea de código**. Es una auditoría de lectura: no
hubo `device_bash` (la VM del dispositivo no arrancó), así que no había forma de compilar, ni
de type-checkear contra el proyecto real, ni de commitear. Escribir Kotlin a ciegas en
archivos que después no se pueden volver a leer es peor que no tocarlos, así que lo que hay
acá son **parches exactos para que los apliques vos**, con el porqué de cada uno.

Los hallazgos completos y razonados están en `LOCKSUITE_CONTEXTO_PARA_IA.md`, puntos
**B.24** (red), **B.11** (suspensión) y **B.25** (borrado). Este documento es el "cómo".

**Antes de empezar:** `git status` (debería estar limpio; HEAD = `65bc6da4` del 31/8 18:40 UTC)
y `cat app/build.gradle.kts` (debería decir versionCode 95 / 0.6.32).

---

## Orden recomendado

Está ordenado por "qué descarta el problema más rápido", no por gravedad:

1. **§1 — V-1** (red). Es una sola función, es el arreglo con mejor relación
   impacto/riesgo de toda la lista, y es el candidato más fuerte a ser la causa del
   "se cae internet" que sigue apareciendo. Si podés hacer un solo cambio, hacé este.
2. **§2 — V-2** (red). Complementa a V-1: V-1 hace que el equipo se recupere, V-2 hace
   que no se rompa en primer lugar.
3. **§4 — S-1** (suspensión). Una línea. Falla visible en pantalla, fácil de probar.
4. **§5 — S-2 y S-3** (suspensión). Rompen la promesa central de B.11.
5. **§6 — P-1 a P-4** (borrado). Es lo que deja el equipo peor de lo que estaba.
6. **§3 — V-3 a V-7** (red, menores). Si hay tiempo.

Compilá y probá §1+§2 **solos** antes de meter el resto. Son cambios en el camino caliente
del DNS: si algo sale mal, querés saber que fue eso y no otra cosa.

---

## §1 — V-1: el reintento a DNS público solo se dispara ante TIMEOUT

**Archivo:** `app/src/main/java/com/ejemplo/locksuite/util/NetworkForwarder.kt`
**Función:** `forwardDnsQuery()`

### El problema, en una frase

El reintento a `8.8.8.8` / `1.1.1.1` que se agregó en B.21 está enganchado en
`catch (e: SocketTimeoutException)`, pero cuando el socket quedó vinculado con
`network.bindSocket()` a una red que se cayó o que no tiene ruta hacia ese DNS, **el sistema
no devuelve timeout**: devuelve `ENETUNREACH` / `EHOSTUNREACH` (una `SocketException` desde
`send()`) o `PortUnreachableException` (desde `receive()`). Ninguna de esas hereda de
`SocketTimeoutException`, así que caen al `catch (e: Exception)` general del final, la
consulta se descarta en silencio **y no se prueba ningún resolutor alternativo**.

Resultado para el usuario: cero DNS, o sea "se cayó internet", con la red física perfecta.
Y se destraba apagando y prendiendo la VPN porque eso invalida el cache del resolutor y
vuelve a elegir red. **Es exactamente el síntoma histórico y exactamente cómo el dueño lo
viene destrabando a mano.**

### Por qué es más grave de lo que parece

Toda la red de seguridad de B.21 —el reintento contra Google y Cloudflare, que existe
precisamente "para no dejar sin internet al celular"— queda inactiva justo en el caso más
común de "la red de abajo cambió". Está bien escrita y nunca corre.

### El parche

Reemplazá el bloque que va desde `val responseBuffer = ByteArray(4096)` hasta el cierre del
`catch (e: SocketTimeoutException) { ... }` (todo el bloque de reintentos anidados) por esto:

```kotlin
            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            var responseReceived = false

            // ⚠️ ARREGLO 1/9/2026 (V-1). Antes esta cadena de reintentos colgaba de
            // `catch (e: SocketTimeoutException)`. Ese era el defecto: cuando el socket
            // quedó vinculado (bindSocket) a una red que se cayó, o que no tiene ruta al
            // DNS elegido, el sistema NO devuelve timeout. Devuelve:
            //
            //   • SocketException: sendto failed: ENETUNREACH / EHOSTUNREACH  (desde send)
            //   • PortUnreachableException                                    (desde receive)
            //
            // Ninguna de las dos es SocketTimeoutException, así que la consulta caía al
            // catch general del final y se descartaba SIN probar ningún otro resolutor.
            // O sea: toda la red de seguridad de B.21 no corría justo en el caso más
            // común. Ahora se captura IOException, que es la superclase de las tres
            // (SocketTimeoutException incluida), y los reintentos se hacen en un bucle
            // en vez de tres try/catch anidados — así agregar un resolutor más es
            // agregar un elemento a una lista, no otro nivel de anidamiento.
            //
            // El orden importa: primero el resolutor que corresponde a la red, después
            // Google, después Cloudflare. Y no se repite el mismo dos veces (V-6: antes,
            // si el resolutor inicial YA era 8.8.8.8, no había ningún reintento).
            val resolvers = LinkedHashSet<InetAddress>().apply {
                add(activeResolver)
                add(FALLBACK_DNS_PRIMARY)
                add(FALLBACK_DNS_SECONDARY)
            }

            for ((intento, resolver) in resolvers.withIndex()) {
                try {
                    if (intento > 0) {
                        // Socket nuevo por intento: uno que ya falló con ENETUNREACH
                        // puede quedar asociado a la red muerta.
                        socket.close()
                        socket = DatagramSocket()
                        vpnService.protect(socket)
                        // V-5: volver a vincular a la red física si la tenemos. En un
                        // equipo con la ruta ancha 100.0.0.0/8 del módem, no vincular es
                        // justamente lo que desvía el paquete a la antena.
                        upstreamResult.network?.let { net ->
                            try { net.bindSocket(socket) } catch (ignored: Exception) { }
                        }
                        // Los reintentos van con menos paciencia que el primero: el
                        // objetivo es responderle rápido a la app, no insistir.
                        socket.soTimeout = RETRY_TIMEOUT_MS
                        android.util.Log.w("KosherVPN", "DNS $activeResolver no respondió; reintentando con $resolver")
                    }
                    activeResolver = resolver
                    socket.send(DatagramPacket(packet.payload, packet.payload.size, resolver, UPSTREAM_DNS_PORT))
                    socket.receive(responsePacket)
                    responseReceived = true
                    break
                } catch (e: java.io.IOException) {
                    // Timeout, red inalcanzable, puerto cerrado: los tres se tratan igual
                    // y se pasa al siguiente resolutor. Se registra el motivo porque la
                    // diferencia entre "timeout" y "ENETUNREACH" es la que dice si el
                    // problema es el servidor o la ruta.
                    android.util.Log.w("KosherVPN", "Consulta a $resolver falló (${e.javaClass.simpleName}: ${e.message})")
                }
            }

            if (!responseReceived) return
```

Y agregá la constante, al lado de `TIMEOUT_MS`:

```kotlin
    private const val TIMEOUT_MS = 3500
    /**
     * Paciencia de los reintentos. Más corta que la del primer intento a propósito: si el
     * resolutor de la red no contestó, lo que importa es darle una respuesta a la app
     * antes de que ELLA se dé por vencida, no agotar los 3,5 s con cada alternativa.
     */
    private const val RETRY_TIMEOUT_MS = 2000
```

**Ojo con dos cosas al aplicarlo:**

- `socket` está declarado `var socket: DatagramSocket? = null` y dentro del `try` se usa sin
  `?`. Si el compilador se queja de nulabilidad dentro del bucle, usá una variable local
  no-nula (`var sock = socket!!`) y reasignala; **no** cambies la declaración de arriba,
  porque el `finally { socket?.close() }` depende de ella.
- `InetAddress` ya está importado. `java.io.IOException` va con nombre completo o agregá el
  import; `SocketTimeoutException` puede quedar importado sin uso (es subclase de
  `IOException`) — si el lint molesta, sacá ese import.

### Cómo confirmar en el equipo que era esto

Con la VPN andando y el celular en una Wi-Fi cuyo DNS sea del ISP:

```
adb logcat -c && adb logcat -s KosherVPN
```

Apagá el Wi-Fi de golpe (o poné el equipo en modo avión y sacalo). **Antes del parche** vas
a ver `Fallo reenviando consulta DNS: … ENETUNREACH` **sin** ninguna línea de reintento.
**Después del parche** tenés que ver `Consulta a … falló (SocketException: …)` seguida de
`reintentando con 8.8.8.8` y el navegador tiene que seguir funcionando.

---

## §2 — V-2: se elige "la primera red de la lista", no la que el equipo está usando

**Archivo:** el mismo. **Función:** `resolveUpstreamDns()`

### El problema

La función recorre `cm.allNetworks`, saltea las de transporte VPN, exige
`NET_CAPABILITY_INTERNET` y devuelve **la primera** que tenga un DNS IPv4 usable. Después
hace `bindSocket()` a esa red. Dos cosas:

- **El orden de `allNetworks` no está definido.** Con Wi-Fi y datos móviles arriba a la vez
  —que es exactamente el escenario de B.21— puede devolver la red por la que el tráfico
  **no** está saliendo.
- **`NET_CAPABILITY_INTERNET` es la capacidad *pedida*, no la *validada*.** Una Wi-Fi
  conectada pero sin internet (portal cautivo, router sin WAN, el clásico "conectado, sin
  internet") la cumple igual. La que dice si esa red realmente sale a internet es
  `NET_CAPABILITY_VALIDATED`.

Y como el resultado se cachea 30 segundos, una elección mala se sostiene 30 s mínimo; si la
lista no cambia, se vuelve a elegir igual de mal indefinidamente.

**Aclaración para no confundirlo con B.18:** en B.18 se sacó `cm.activeNetwork` porque podía
devolver la red del propio VPN. Eso era cierto y el arreglo era correcto. Pero la solución
fue tirar el concepto de "red activa" entero, cuando alcanzaba con **descartarla si es VPN**.
Este parche lo recupera con esa guarda puesta.

### El parche

Reemplazá el bloque `for (network in networks) { ... }` (y el `if (ipv6Fallback != null)`
que le sigue) por esto:

```kotlin
                // ⚠️ ARREGLO 1/9/2026 (V-2). Antes esto era un único for sobre
                // cm.allNetworks que devolvía LA PRIMERA red con DNS IPv4 usable. Dos
                // problemas: el orden de allNetworks no está definido (con Wi-Fi y datos
                // arriba a la vez podía elegir la que NO se está usando), y
                // NET_CAPABILITY_INTERNET es la capacidad PEDIDA, no la validada — una
                // Wi-Fi "conectada sin internet" la cumple igual. Ahora hay tres niveles
                // de preferencia, y recién se baja al siguiente si el anterior no dio
                // nada:
                //
                //   1º la red ACTIVA, si no es la nuestra (esto es lo que se había
                //      perdido en B.18: ahí se sacó activeNetwork entero porque podía
                //      devolver el VPN, cuando alcanzaba con descartarlo si lo es);
                //   2º cualquier red VALIDADA (o sea, que el sistema confirmó que sale
                //      a internet de verdad);
                //   3º cualquier red con capacidad de internet — el comportamiento viejo,
                //      que se conserva como último recurso para no empeorar ningún caso
                //      que hoy funcione.
                fun esUsable(net: Network, exigirValidada: Boolean): UpstreamResult? {
                    val caps = cm.getNetworkCapabilities(net) ?: return null
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return null
                    if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
                    if (exigirValidada &&
                        !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ) return null

                    val dnsList = cm.getLinkProperties(net)?.dnsServers ?: return null
                    var v6: InetAddress? = null
                    for (dns in dnsList) {
                        if (!isUsableResolver(dns)) continue
                        // Preferencia a IPv4: es el que existe en prácticamente todas las
                        // redes y el que menos sorpresas da.
                        if (dns is Inet4Address) return UpstreamResult(dns, net)
                        if (v6 == null) v6 = dns
                    }
                    return v6?.let { UpstreamResult(it, net) }   // redes IPv6 puras / DNS64
                }

                // 1º — la red activa.
                cm.activeNetwork?.let { activa ->
                    esUsable(activa, exigirValidada = false)?.let { return it }
                }
                // 2º — cualquier red validada.
                @Suppress("DEPRECATION")
                for (network in cm.allNetworks) {
                    esUsable(network, exigirValidada = true)?.let { return it }
                }
                // 3º — comportamiento anterior, como último recurso.
                @Suppress("DEPRECATION")
                for (network in cm.allNetworks) {
                    esUsable(network, exigirValidada = false)?.let { return it }
                }
```

Con esto desaparecen las variables `ipv6Fallback` / `ipv6FallbackNetwork` y la línea
`val networks = cm.allNetworks` de más arriba: sacalas.

**No saques la guarda de `TRANSPORT_VPN`.** Es la que evita volver a B.18 (el resolutor de
salida terminando siendo `fd00::1`, el DNS virtual del propio túnel, y 3,5 s de timeout por
consulta). `isUsableResolver()` ya descarta las direcciones del túnel por separado; las dos
protecciones son independientes y las dos tienen que quedar.

---

## §3 — V-3 a V-7: los menores de red

**V-3 — `lastNetworkHandle` se actualiza aunque el reestablecimiento no ocurra.**
`KosherVpnService.registerNetworkWatcher()`, dentro de `onAvailable`. Hoy:

```kotlin
                    lastNetworkHandle = handle
                    android.util.Log.i("KosherVPN", "Cambio la red fisica por defecto; reestableciendo tunel VPN.")
                    restartVpn()
```

`restartVpn()` puede salir por el antirrebote de 8 s **sin hacer nada**, y el handle ya quedó
actualizado. Queda el túnel armado para la red vieja y el estado diciendo que es la nueva: el
próximo `onAvailable` de la red buena se descarta por "es la misma red" y el túnel no se rehace
nunca. Cambialo a:

```kotlin
                    android.util.Log.i("KosherVPN", "Cambio la red fisica por defecto; reestableciendo tunel VPN.")
                    // ARREGLO 1/9/2026 (V-3): el handle se anota SOLO si el
                    // reestablecimiento efectivamente ocurrió. Antes se anotaba siempre,
                    // y si el antirrebote de 8 s cancelaba el reinicio quedaba el túnel
                    // de la red vieja marcado como si fuera el de la nueva — con lo cual
                    // el siguiente aviso de la red buena se descartaba por "es la misma".
                    if (restartVpn()) {
                        lastNetworkHandle = handle
                    }
```

y hacé que `restartVpn()` devuelva `Boolean` (`false` en los dos `return` tempranos, `true`
al final).

**V-4 — falta `onCapabilitiesChanged`.** Los DNS de una red cambian sin que la red cambie
(renovación DHCP, alta de IPv6). Agregá al mismo `NetworkCallback`:

```kotlin
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                    // Los servidores DNS de una red cambian sin que la red cambie. No hace
                    // falta rehacer el túnel: alcanza con volver a elegir el resolutor.
                    NetworkForwarder.invalidateUpstreamCache()
                }
```

**V-5** ya quedó incluido en el parche de §1 (el `bindSocket` dentro del bucle de reintentos).
**V-6** también (el `LinkedHashSet` no repite el resolutor inicial, así que si ya era 8.8.8.8
el siguiente intento es 1.1.1.1 en vez de ninguno).

**V-7 — avisarle al arranque protegido cuando el túnel no se pudo establecer.** En
`KosherVpnService.startVpn()`, en la rama `if (establishedInterface == null)`, antes del
`stopSelf()`:

```kotlin
                // ARREGLO 1/9/2026 (V-7): si el túnel no levanta, el arranque protegido se
                // entera recién cuando vence su techo de 120 s — dos minutos sin internet
                // evitables. Acá ya sabemos que no va a haber filtro.
                try {
                    com.ejemplo.locksuite.util.BootGate.release(applicationContext, "el sistema no autorizo el tunel")
                } catch (ignored: Exception) { }
```

---

## §4 — S-1: reiniciar un equipo suspendido devuelve la marca de agua kosher

**Archivo:** `app/src/main/java/com/ejemplo/locksuite/receiver/BootReceiver.kt`

En `onReceive()`, paso 3, hoy dice:

```kotlin
            val policyManager = PolicyManager(context)
            if (policyManager.isKosherLauncherEnabled() && android.provider.Settings.canDrawOverlays(context)) {
```

Cambialo a:

```kotlin
            val policyManager = PolicyManager(context)
            // ARREGLO 1/9/2026 (S-1): faltaba mirar la suspensión. Con LockSuite
            // suspendido el equipo tiene que quedar como si la app no estuviera
            // instalada, y la marca de agua es lo más visible de todo. El mismo chequeo
            // en WatchdogForegroundService.checkRunnable SÍ la miraba: eran dos lugares
            // haciendo lo mismo con criterios distintos. Y como el Watchdog solo ARRANCA
            // el servicio y nunca lo para, una vez que aparecía se quedaba.
            if (!policyManager.isLockSuiteSuspended() &&
                policyManager.isKosherLauncherEnabled() && android.provider.Settings.canDrawOverlays(context)) {
```

Es una línea y hace pasar la prueba "reiniciar el equipo suspendido y confirmar que sigue
suspendido", que hoy falla de forma visible en pantalla.

---

## §5 — S-2 y S-3: lo que la suspensión no devuelve al reanudar

**Archivo:** `app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt`

### S-2 — las apps ocultas no vuelven a ocultarse

`liftAllForSuspension()` des-oculta **todas** las apps. `reapplyAllRestrictions()` **no vuelve
a ocultar ninguna**: la preferencia `hide_<paquete>` no se lee en ningún lado del proyecto
salvo `hide_com.android.vending`. Las suspensiones individuales (`suspend_<paquete>`) sí se
reconstruyen; las ocultaciones no.

Esto rompe la premisa central del diseño de B.11 —"no hace falta guardar un snapshot porque
`reapplyAllRestrictions()` reconstruye todo desde las preferencias"—: hay una preferencia que
no reconstruye. Y el efecto es silencioso: las apps quedan visibles en el celular mientras el
panel y la app siguen mostrándolas como ocultas.

En `reapplyAllRestrictions()`, en el bucle que ya existe para re-aplicar suspensiones
individuales, agregá el ocultamiento. Reemplazá:

```kotlin
        val updatingPkgNow = prefs.getString("updating_package", null)
        val userApps = appController.getUserApps(loadIcon = false)
        for (app in userApps) {
            if (!app.isCritical && app.packageName != "com.android.vending" &&
                app.packageName != updatingPkgNow) {
                val isIndividuallySuspended = prefs.getBoolean("suspend_${app.packageName}", false)
                if (isIndividuallySuspended) {
                    appController.suspendApp(app.packageName, true)
                }
            }
        }
```

por:

```kotlin
        val updatingPkgNow = prefs.getString("updating_package", null)
        val userApps = appController.getUserApps(loadIcon = false)
        for (app in userApps) {
            if (!app.isCritical && app.packageName != "com.android.vending" &&
                app.packageName != updatingPkgNow) {
                // ARREGLO 1/9/2026 (S-2): el ocultamiento va PRIMERO y antes faltaba
                // entero. liftAllForSuspension() des-oculta todas las apps, y al reanudar
                // nadie las volvía a ocultar: `hide_<paquete>` no se leía en ningún lado
                // salvo para Play Store. O sea que suspender y reanudar dejaba las apps
                // ocultas visibles PARA SIEMPRE, con el panel diciendo lo contrario.
                //
                // El orden importa: hideApp(pkg, true) deshabilita el paquete, y
                // suspenderlo después no aporta nada; además hideApp(pkg, false) ya
                // re-suspende solo si corresponde. Por eso: primero ocultar, y suspender
                // solo si NO quedó oculta.
                val debeOcultarse = prefs.getBoolean("hide_${app.packageName}", false)
                if (debeOcultarse) {
                    appController.hideApp(app.packageName, true)
                    continue
                }

                val isIndividuallySuspended = prefs.getBoolean("suspend_${app.packageName}", false)
                if (isIndividuallySuspended) {
                    appController.suspendApp(app.packageName, true)
                }
            }
        }
```

**Probalo con cuidado**, porque este bucle lo corre también el `WatchdogWorker` cada 15
minutos: si `hide_<paquete>` quedó con basura de alguna versión vieja, ahora esa basura se
aplica. Antes de compilar, mirá qué claves `hide_` hay guardadas en un equipo real:

```
adb shell run-as com.ejemplo.locksuite cat /data/data/com.ejemplo.locksuite/shared_prefs/*.xml
```

(no va a funcionar en release porque el paquete no es depurable — usá el panel: la lista de
apps ocultas que muestra sale de la misma preferencia).

### S-3 — `DISALLOW_CONFIG_LOCALE` no se levanta al suspender

En `liftAllForSuspension()`, la lista `allRestrictions` tiene 19 entradas y le falta la del
idioma, que sí está en `reapplyAllRestrictions()`. Agregá al final de la lista, después de
`UserManager.DISALLOW_CONFIG_DATE_TIME`:

```kotlin
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            // ARREGLO 1/9/2026 (S-3): faltaba. Está en reapplyAllRestrictions() desde el
            // 18/8 (B.19) pero no acá, así que un equipo "suspendido" seguía sin poder
            // cambiar de idioma — justo lo contrario de "queda como si LockSuite no
            // estuviera instalado".
            UserManager.DISALLOW_CONFIG_LOCALE
```

### Mientras estés acá: el vencimiento de la suspensión

B.11 lo tiene anotado como riesgo abierto desde el 16/8 y sigue sin resolverse: **un equipo
que quede suspendido por olvido es un equipo sin ninguna protección** —ni FRP, ni Knox, ni
bloqueo de desinstalación—. Ya existe `locksuite_suspended_at` guardado, así que el
vencimiento es barato: en `WatchdogWorker.doWork()`, antes de todo, si
`isLockSuiteSuspended()` y pasaron más de N horas desde `getLockSuiteSuspendedAt()`, llamar a
`setLockSuiteSuspended(false)`. **No lo apliques sin preguntarle al dueño primero** — es un
cambio de comportamiento, no un arreglo, y el valor de N es decisión suya (12 h y 24 h son
los candidatos razonables).

---

## §6 — P-1 a P-4: borrar LockSuite no deja el equipo limpio

**Archivo:** `app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt`
**Función:** `clearAllRestrictions()`

La purga total (`*#*#9999#*#*` → contraseña maestra) hace: des-ocultar apps →
`clearAllRestrictions()` → salir de modo sigiloso → reactivar barra de estado →
`dpm.clearDeviceOwnerApp()`. El problema está en `clearAllRestrictions()`, que **no es
simétrica con `liftAllForSuspension()`** aunque las dos digan "dejar el equipo como si
LockSuite no existiera".

### P-1 — no des-suspende las apps suspendidas una por una

Solo des-suspende `com.android.vending`, los navegadores y el System WebView. Toda app que el
administrador haya suspendido queda **suspendida después de la purga**, y como el último paso
de la función es `prefs.edit().clear()`, se pierde hasta la lista de cuáles eran.

*Matiz honesto, para que no lo descartes ni lo sobreestimes:* desde Android 9 el sistema
des-suspende solo los paquetes cuando **se desinstala** la app que los suspendió. Pero
(a) el `minSdk` de este proyecto es **24**, o sea que Android 7 y 8 no hacen eso —y son
justo el perfil de equipo que se reutiliza como celular kosher—, y (b) la purga **no
desinstala nada**, solo suelta el Device Owner: si el dueño purga y no desinstala, en
cualquier versión las apps quedan suspendidas.

### P-2 — la purga RE-SUSPENDE apps mientras las des-oculta

El paso 1 de `EmergencyActivity.executeFullPurge()` llama a `appController.hideApp(pkg, false)`
para cada app oculta. Y `hideApp()` termina con: si `hide == false` y existe
`suspend_<paquete>`, entonces `setPackagesSuspended(pkg, true)`. O sea que la purga vuelve a
suspender exactamente las apps que estaban ocultas **y** marcadas como suspendidas, y después
nadie las libera. **La purga empeora P-1 en vez de arreglarlo.**

⚠️ **Verificá esto antes de tocarlo.** `EmergencyActivity.kt` es el único archivo de esta
auditoría que no se pudo leer en su versión actual (el blob está empaquetado en git y el
archivo está a 9 carpetas, fuera del tope de `device_stage_files`). Lo de P-2 se verificó
contra la copia de julio de `_to_delete/`, que es 923 bytes más chica que la actual. Abrí
`ui/emergency/EmergencyActivity.kt` y confirmá que `executeFullPurge()` sigue teniendo el
paso 1 tal cual antes de dar P-2 por cierto.

### P-3 — `DISALLOW_CONFIG_LOCALE` tampoco está acá

Si alguna vez se encendió el interruptor de bloqueo de idioma (B.19), después de la purga
**el equipo no puede cambiar de idioma nunca más**, y ya no queda ninguna app con Device
Owner para deshacerlo. Es una línea, y es la que deja un equipo peor que antes de instalar
LockSuite.

### P-4 — no limpia la designación de Always-on VPN

`liftAllForSuspension()` llama a `dpm.setAlwaysOnVpnPackage(admin, null, false)`;
`clearAllRestrictions()` no. Agregalo por simetría, antes de soltar el Device Owner.

### El parche (P-1, P-3 y P-4 juntos)

Agregá `DISALLOW_CONFIG_LOCALE` (y `DISALLOW_CONFIG_DATE_TIME`, que también falta) a la lista
`restrictions` de `clearAllRestrictions()`:

```kotlin
            UserManager.DISALLOW_CONFIG_VPN,
            // ARREGLO 1/9/2026 (P-3): faltaban las dos. Sin la de idioma, un equipo al
            // que se le encendió el interruptor de B.19 queda SIN PODER CAMBIAR DE
            // IDIOMA para siempre después de la purga, y ya no hay ninguna app con
            // Device Owner que pueda deshacerlo.
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_CONFIG_LOCALE
```

Y justo antes de la línea `// Clear local preferences` del final, agregá:

```kotlin
        // ARREGLO 1/9/2026 (P-1 y P-4). Esto faltaba entero: la purga solo des-suspendía
        // Play Store, los navegadores y el WebView, así que toda app suspendida una por
        // una desde el panel quedaba SUSPENDIDA después de que LockSuite ya no estaba —
        // y como abajo se limpian las preferencias, se perdía hasta la lista de cuáles
        // eran. El código es el mismo que ya usa liftAllForSuspension(); la única razón
        // de que no estuviera acá es que las dos funciones se escribieron por separado.
        //
        // Va DESPUÉS de todo lo demás y ANTES de limpiar las preferencias, a propósito:
        // el paso 1 de executeFullPurge() re-suspende apps al des-ocultarlas
        // (hideApp(pkg,false) vuelve a suspender si existe suspend_<paquete>), así que
        // esto tiene que ser lo último que toque la suspensión.
        try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_UNINSTALLED_PACKAGES
            }
            val packages = pm.getInstalledApplications(flags).map { it.packageName }
            // En tandas de 50: setPackagesSuspended con listas enormes puede fallar entera.
            packages.chunked(50).forEach { chunk ->
                try {
                    dpm.setPackagesSuspended(adminComponent, chunk.toTypedArray(), false)
                } catch (e: Exception) {
                    android.util.Log.w("PolicyManager", "Purga: no se pudo liberar una tanda: ${e.message}")
                }
            }
            // Y des-ocultar lo que haya quedado oculto. executeFullPurge() ya lo hace en
            // su paso 1, pero esto es la red de seguridad: si aquel paso falló para
            // alguna app, una app oculta es una app DESHABILITADA, que es lo peor que se
            // le puede dejar a un equipo del que ya no tenemos control.
            packages.forEach { pkg ->
                try {
                    if (dpm.isApplicationHidden(adminComponent, pkg)) {
                        dpm.setApplicationHidden(adminComponent, pkg, false)
                    }
                } catch (e: Exception) {
                    // Paquetes del sistema que no admiten el cambio: se ignoran.
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PolicyManager", "Purga: error liberando apps", e)
        }

        // P-4: soltar la designación de Always-on VPN, igual que hace
        // liftAllForSuspension(). Si no, el sistema sigue teniendo a LockSuite anotada
        // como la VPN permanente de un paquete que ya no aplica ninguna política.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
            }
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "Purga: no se pudo limpiar Always-on VPN: ${e.message}")
        }
```

### P-5 — probá `clearDeviceOwnerApp()` en un equipo de descarte

**Esto no es un parche, es una advertencia, y es importante.** `dpm.clearDeviceOwnerApp()`
está deprecada desde API 26 y su comportamiento cambió entre versiones de Android. **Nunca se
probó en Android 13**, que es la versión del equipo del dueño. Es el único paso irreversible
de todo el proyecto, y si lanza excepción el `catch` solo muestra un Toast: el equipo queda
**sin políticas pero con LockSuite todavía como Device Owner**, o sea sin poder desinstalarse
y sin poder volver a administrarse. Probá la purga completa en un equipo de descarte con
Android 13 **antes** de necesitarla en uno real.

---

## Compilar

```powershell
.\gradlew.bat :app:compileReleaseKotlin
```

y si pasa, el ciclo completo con `deploy_all.ps1`. **Acordate de lo de siempre:**
`deploy_all.ps1` hace `git add .`, así que se lleva puesto todo lo que esté sin commitear —
commiteá lo de abajo vos primero si querés separarlo. Y sube el versionCode solo (96 / 0.6.33
sería lo siguiente).

## Probar en el equipo real, en este orden

Ordenado por "cuál descarta el problema más rápido":

1. **V-1, la prueba clave.** Con la VPN andando y el celular en Wi-Fi, `adb logcat -s KosherVPN`,
   apagar el Wi-Fi de golpe. Tiene que aparecer `Consulta a … falló (SocketException…)` y a
   continuación `reintentando con 8.8.8.8`, y el navegador tiene que seguir andando. Después:
   navegar media hora normal, forzar tres o cuatro handoffs Wi-Fi↔datos seguidos, y abrir de
   golpe cinco apps que consulten mucho. **Ninguna de esas cosas puede dejar el equipo sin
   internet.**
2. **V-2.** Con Wi-Fi y datos móviles los dos encendidos, confirmar que la navegación es
   normal y que en el log el resolutor elegido es el de la red que se está usando. Después:
   conectarse a una Wi-Fi **sin internet** (un router desconectado del módem) y confirmar que
   el equipo sigue resolviendo por datos móviles en vez de quedarse mudo.
3. **Que un dominio bloqueado siga fallando al instante** (no por timeout de 3,5 s). Es la
   prueba de que ninguno de los dos parches aflojó el filtro.
4. **S-1.** Suspender LockSuite desde el panel → reiniciar el equipo → confirmar que **no**
   aparece la marca de agua kosher y que sigue todo desbloqueado.
5. **S-2.** Ocultar dos apps → suspender → confirmar que las dos aparecen → reanudar →
   **confirmar que las dos vuelven a desaparecer**. Esta es la que hoy falla.
6. **S-3.** Encender "bloquear cambio de idioma" → suspender → confirmar que la entrada de
   Idioma de Ajustes vuelve a estar habilitada.
7. **P-1/P-2/P-3, en un equipo de descarte.** Suspender tres apps y ocultar dos → purga total
   (`*#*#9999#*#*`) → confirmar que las cinco quedan **visibles y usables**, que se puede
   cambiar el idioma, y que LockSuite se puede desinstalar.

## Mensaje de commit

```
fix(red/suspension/purga): reintento DNS ante ENETUNREACH, eleccion de red validada, y simetria de suspender/purgar

Red (B.24). El reintento a 8.8.8.8/1.1.1.1 que agrego B.21 colgaba de
catch(SocketTimeoutException), pero cuando el socket queda atado por bindSocket a
una red que se cayo el sistema devuelve ENETUNREACH/PortUnreachableException, que
no son timeout: la consulta se descartaba sin probar ningun resolutor alternativo
y el equipo quedaba sin DNS hasta apagar y prender la VPN a mano. Ahora se captura
IOException y los reintentos van en un bucle sobre una lista sin repetidos, con
bindSocket reaplicado en cada intento. Ademas resolveUpstreamDns dejaba de elegir
"la primera red de allNetworks" (orden no definido, y NET_CAPABILITY_INTERNET es
la capacidad pedida, no la validada): ahora prefiere la red activa si no es VPN,
despues cualquier red VALIDATED, y recien despues el criterio anterior. Se
conserva la guarda de TRANSPORT_VPN que arreglo B.18. Y lastNetworkHandle solo se
anota si el tunel se reestablecio de verdad.

Suspension (B.11). BootReceiver arrancaba WatermarkService sin mirar
isLockSuiteSuspended(), asi que reiniciar un equipo suspendido devolvia la marca
de agua kosher (el Watchdog si lo miraba: eran dos criterios distintos para lo
mismo). reapplyAllRestrictions() no reconstruia hide_<paquete>, asi que suspender
y reanudar dejaba las apps ocultas visibles para siempre mientras el panel decia
lo contrario. Y liftAllForSuspension() no levantaba DISALLOW_CONFIG_LOCALE.

Purga (B.25). clearAllRestrictions() solo des-suspendia Play Store, navegadores y
WebView: las apps suspendidas una por una quedaban suspendidas despues de que
LockSuite ya no estaba, y como el ultimo paso limpia las preferencias se perdia
hasta la lista de cuales eran. Ahora libera el enumerado completo en tandas de 50,
des-oculta como red de seguridad, limpia la designacion Always-on VPN y agrega
DISALLOW_CONFIG_LOCALE y DISALLOW_CONFIG_DATE_TIME a la lista (sin la de idioma,
un equipo purgado no podia volver a cambiar de idioma nunca mas).
```

## Lo que queda abierto después de esto

- **P-5:** probar `clearDeviceOwnerApp()` en Android 13. Es lo único irreversible del
  proyecto y nunca se ejecutó.
- **B.23:** el panel. El cache-buster ya está en `app.js?v=22`, pero los tres últimos commits
  de diseño son posteriores al `_buildtime.txt`: correr `firebase deploy --only hosting,database`
  y confirmar con Ctrl+F5. Y el arreglo de datos (`authorizedAdminsUids/<UID> = true` en la
  consola de Firebase) hay que hacerlo igual, es independiente del despliegue.
- **B.22:** probar `:admin-app` en el celular (ya está compilada y desplegada desde el 31/8).
- Todo lo demás de la sección B que sigue diciendo "sin probar en equipo real": B.9, B.13,
  B.14, B.15, B.16, B.17, B.19.
