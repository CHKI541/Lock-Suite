# Instrucciones para Antigravity — compilar y desplegar todo lo pendiente

**Fecha:** 16 de agosto de 2026
**Para:** Antigravity (o quien tenga terminal real, Android SDK y Gradle)
**Estado de partida:** HEAD contiene ~2.400 líneas nuevas y una Capa 3 reescrita. **Nada de eso pasó por un compilador.**

---

## 0. La trampa que hay que ver antes de empezar

`app/build.gradle.kts` dice **versionCode 80 / versionName 0.6.18**. Ese número miente.

El APK 0.6.18 que está publicado en Firebase Hosting se compiló **antes** de los commits que traen todo esto. O sea: el número de versión ya está "gastado" en un APK que no tiene ninguno de los cambios nuevos.

> **Si compilás y publicás sin subir el número, los equipos que ya tienen la 0.6.18 instalada nunca van a ver la actualización.** El `SelfUpdater` compara `versionCode`, y 80 no es mayor que 80.

Lo próximo que se publique tiene que ser **0.6.19 / versionCode 81**. `deploy_all.ps1` sube el número solo; si compilás a mano, subilo a mano en `app/build.gradle.kts` **y** en `admin-backend/public/version.json` (los dos, o el manifiesto queda desincronizado del APK).

---

## 1. Qué entra en este build

Tres bloques de trabajo, de tres sesiones distintas, todos ya commiteados:

| Punto | Qué es | Archivos principales |
|---|---|---|
| **B.8** | Fix del `intent-filter` de Accesibilidad en Android 13 | `AndroidManifest.xml` (línea 97) |
| **B.9** | Reescritura completa del flujo de actualización por Play Store (7 bugs) | `util/UpdateFlowManager.kt` (nuevo, 551 líneas), `receiver/PackageReceiver.kt`, `service/LockSuiteFirebaseService.kt` |
| **B.11** | Suspensión temporal de LockSuite (app + panel) | `mdm/PolicyManager.kt`, `ui/dashboard/DashboardActivity.kt`, `admin-backend/` |
| **B.13** | Optimización de Capa 3 + corrección del sobre-bloqueo de Mercado Pago | `service/LockSuiteAccessibilityService.kt`, `service/BlockOverlayManager.kt` |

Más: sección "Actualizar apps" antes del PIN en `ui/auth/LoginActivity.kt`, y cambios de panel en `admin-backend/functions/index.js`, `public/app.js`, `public/index.html`.

Los detalles de cada uno están en `LOCKSUITE_CONTEXTO_PARA_IA.md` sección B. Para B.13 hay además un informe aparte, `INFORME_OPTIMIZACION_ACCESIBILIDAD_2026-08-16.md`.

---

## 2. Compilar

```powershell
cd "C:\Users\israe\OneDrive\Documentos\Lock Suite segunda version"

# 1. Confirmar de dónde partís (no confíes en este documento, verificalo)
git log -1 --oneline
git status --short

# 2. Compilación rápida solo de Kotlin: es la que da los errores útiles y tarda mucho menos
.\gradlew.bat compileDebugKotlin

# 3. Si eso pasa limpio, recién entonces el build completo
.\gradlew.bat assembleRelease
```

Si `compileDebugKotlin` falla, **no sigas a `assembleRelease`**: los errores de Kotlin salen mucho más legibles en el primero.

### Dónde es más probable que falle

Ordenado por probabilidad. Nada de esto es seguro que pase — es dónde mirar primero si algo revienta:

1. **`util/UpdateFlowManager.kt`** — es el archivo más nuevo y más grande (551 líneas), escrito sin compilador. Es el candidato número uno.
2. **`mdm/PolicyManager.kt`** — creció 284 líneas para la suspensión, y toca `dpm.*` directo. Ojo con métodos de `DevicePolicyManager` que necesitan un `Build.VERSION.SDK_INT` mínimo: el proyecto es `minSdk 24` y hay APIs de suspensión que son API 28+.
3. **`service/LockSuiteFirebaseService.kt`** — creció 201 líneas con los comandos nuevos. Revisar que todos los `when` sobre el nombre de comando estén completos.
4. **`ui/auth/LoginActivity.kt`** y **`ui/dashboard/DashboardActivity.kt`** — son Compose; los errores de Compose suelen ser confusos, buscá el `@Composable` mal anidado.

Sobre **B.13** (los dos archivos de Capa 3): ya pasaron un type-check con kotlinc 2.0.21 contra stubs de la API de Android, con 0 errores y 0 warnings. No es garantía —los stubs no son el SDK real— pero es el bloque con menos probabilidad de dar problemas de compilación.

### Si hay que tocar algo

- **No "simplifiques" el snapshot de flags** de `LockSuiteAccessibilityService.kt` volviendo a leer `SharedPreferences` dentro de `onAccessibilityEvent`. Ese es exactamente el problema que se acaba de arreglar. Si necesitás un dato nuevo en el evento, agregalo a la clase `Flags`.
- **No vuelvas a poner `recycle()`** en el recorrido `scanNodes` de `handlePlayStoreAutoUpdate`: reciclar ahí rompe el clic en Android 11/12 con `IllegalStateException` (bug ya vivido, ver B.9).
- **No conviertas el listener de preferencias en una lambda local.** `SharedPreferences` retiene los listeners con referencia débil; tiene que quedar en un campo o deja de funcionar en silencio.

---

## 3. Instalar y desbloquear Android 13

En el equipo de prueba (QEMAY-QM01, Android 13 / API 33). Android 13 marca como "Ajustes restringidos" cualquier app instalada por sideload, y eso impide activar el interruptor de Accesibilidad desde la pantalla de Ajustes:

```bash
adb install -r app\build\outputs\apk\release\app-release.apk

adb shell appops set com.ejemplo.locksuite ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set com.ejemplo.locksuite GET_USAGE_STATS allow
adb shell settings put secure enabled_accessibility_services com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

---

## 4. Probar — el orden importa

### Paso 0 (bloqueante): confirmar que la Accesibilidad levanta

**Sin esto, ninguna de las otras pruebas significa nada**: B.9, B.11 y B.13 dependen todas de que el servicio reciba eventos.

```bash
adb shell cmd package query-services -a android.accessibilityservice.AccessibilityService
adb shell dumpsys accessibility | Select-String "installedServiceCount|Bound services"
```

Tiene que aparecer `com.ejemplo.locksuite.service.LockSuiteAccessibilityService`, con `installedServiceCount` distinto de 0 y `Bound services` no vacío.

Si `installedServiceCount` sigue en 0 → el fix de B.8 no funcionó, parar acá y avisar. Todo lo demás queda sin poder probarse.

### Paso 1 — B.13, Capa 3 (empezar por acá: es lo que se nota en el uso diario)

Los dos primeros son los importantes, porque son un cambio de **comportamiento**, no solo de velocidad:

1. **Mercado Pago no debe rebotar de más.** Abrir MP y quedarse en el **inicio** → no tiene que pasar nada. Entrar a un **flujo de pago o escanear un QR** y navegarlo → no tiene que pasar nada. *Antes de este cambio las dos cosas rebotaban al usuario fuera de la app.*
2. **Mercado Pago sí debe bloquear ofertas.** Entrar a Ofertas / Promociones / Mercado Puntos → tiene que salir con un solo "atrás", sin cerrar la app entera y sin quedar rebotando en bucle. Si alguna pantalla de ofertas se escapa: agregar su título a `MP_OFFERS_STRONG` o su id a `MP_OFFERS_VIEW_ID_HINTS` en `LockSuiteAccessibilityService.kt` — **no volver a la lista plana de palabras sueltas**, que es lo que causaba el problema del punto 1.
3. **Alineación de los recuadros negros.** Con Capa 1 activa en alguna app, mirar que los recuadros queden **exactamente** encima de las imágenes. Si aparecen corridos verticalmente (típicamente el alto de la barra de estado), sacar `FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS` de `blockRegion()` en `BlockOverlayManager.kt` y avisar. El resto de los cambios no depende de eso.
4. **Scroll.** Desplazar una lista larga con imágenes: los recuadros tienen que acompañar sin parpadear y sin dejar la imagen destapada. Es el cambio que más debería notarse.
5. **WhatsApp:** Estados y Canales siguen bloqueándose, y el rebote a la pestaña Chats sigue andando.
6. **WebView:** el bloqueo de navegador interno sigue funcionando en una app con WebView bloqueado.
7. **Ajustes:** ir a Ajustes → Apps → LockSuite; al aparecer "Desinstalar" / "Forzar detención" tiene que rebotar y abrir el login.
8. **Fluidez general.** Usar el equipo un rato normal. La corrección del `queryIntentActivities` por evento debería notarse en la fluidez del sistema entero, no solo de la app.

Checklist completo con el porqué de cada punto: `INFORME_OPTIMIZACION_ACCESIBILIDAD_2026-08-16.md` §6.

### Paso 2 — B.9, actualización de apps por Play Store

1. Actualizar desde el panel una app que tenga actualización pendiente: tiene que instalarse sola, el texto de la pantalla negra tiene que ir cambiando, y al terminar Play Store debe cerrarse y quedar re-bloqueada.
2. Lo mismo desde el botón "Actualizar apps" del celular, antes del PIN.
3. Apretar Cancelar a mitad de la descarga: tiene que volver a la pantalla común con todo re-bloqueado.
4. Mandar una actualización de una app que ya está al día: tiene que salir sola a los ~8 segundos.
5. Con el servicio de Accesibilidad apagado, el flujo tiene que **negarse a arrancar**, en vez de dejar Play Store abierta.

### Paso 3 — B.11, suspensión

1. Suspender: confirmar que todas las apps se desbloquean y que Ajustes / WiFi / cámara vuelven a funcionar.
2. Reiniciar el equipo suspendido: tiene que seguir suspendido.
3. Reanudar: tiene que volver exactamente la configuración anterior, incluidas suspensiones individuales de apps, launcher kosher y VPN.

> ⚠️ Mientras está suspendido, el equipo **no tiene ninguna protección** — se puede desinstalar LockSuite o formatear. Probalo con el equipo a la vista y reanudá al terminar.

---

## 5. Desplegar

Si las pruebas pasan:

```powershell
.\deploy_all.ps1 -VersionName "0.6.19"
```

Eso sube versionCode/versionName en los dos archivos, compila, copia el APK a `admin-backend/public/`, despliega y commitea.

**Ojo:** `deploy_all.ps1` hace `git add .`, así que se lleva junto todo lo que esté sin commitear en ese momento. Si tenés trabajo a medias de otra sesión, commiteálo aparte antes de correrlo.

### Si desplegás a mano

El orden importa:

```powershell
cd admin-backend
firebase deploy --only hosting,database   # primero
firebase deploy --only functions          # después, en llamada aparte
```

Si Cloud Functions falla dentro del mismo llamado que `hosting`, Firebase **no confirma la versión de Hosting** y los celulares siguen viendo el manifiesto viejo.

Y hace falta desplegar `functions` sí o sí: hay comandos nuevos en `ALLOWED_COMMANDS` (`SUSPEND_LOCKSUITE`, `RESUME_LOCKSUITE`, `CANCEL_UPDATE_APP`) sin los cuales los botones nuevos del panel devuelven "Comando no reconocido".

---

## 6. Al terminar, actualizar el contexto

En `LOCKSUITE_CONTEXTO_PARA_IA.md`:

- Marcar **[RESUELTO 2026-08-XX]** en B.8, B.9, B.11 y B.13 **solo lo que hayas confirmado andando en el equipo real**. La regla del archivo es explícita: no alcanza con que compile.
- Actualizar la línea de "Estado al 16/8" en la sección A con el HEAD y la versión nuevos.
- Reemplazar la sección C (bitácora) por lo que pasó en tu sesión — se reemplaza, no se acumula.

Si algo falla y no lo podés arreglar, dejalo escrito en la sección B con lo que probaste y qué pasó. Un pendiente bien documentado vale más que un `[RESUELTO]` optimista: ya pasó una vez en este proyecto que un documento diera algo por cerrado sin estarlo (ver B.3).
