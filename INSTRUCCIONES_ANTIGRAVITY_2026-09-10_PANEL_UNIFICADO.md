# INSTRUCCIONES — PANEL UNIFICADO, DOMINIOS NO KOSHER Y AUDITORÍA DE LA TIENDA
### 10/9/2026 (noche) · sesión de Claude · **empezá por acá si vas a compilar o desplegar lo de esta tanda**

Cubre **B.62 a B.66** del contexto. Dos commits, ya hechos en el clon:

1. `feat(panel/filtro): ficha completa por celular, editor global de dominios, y los dominios no kosher se bloquean siempre`
2. `feat(app): pestaña Inicio y las mismas secciones que la ficha del panel`

---

## 0. LO PRIMERO, PORQUE SI NO PARECE QUE SE ROMPIÓ TODO

**Hay comandos nuevos en `ALLOWED_COMMANDS`.** Si desplegás solo `hosting`, la ficha nueva va a poder mandar reglas DNS y la Cloud Function las va a rechazar con un error que no explica nada. **Desplegá `functions` también**, y como siempre en pasos separados (`hosting,database` primero y `functions` aparte, por lo que documenta la parte A).

**El cache-buster de `app.js` está en `v=39`.** Abrí el panel con **Ctrl + F5**.

**Los siete chequeos, antes de `deploy_all.ps1`:**

```
python tools/check_whitelist_sync.py
python tools/check_profile_sync.py
python tools/check_command_sync.py
python tools/check_panel_commands.py      # nuevo
python tools/gen_catalog_js.py --check    # nuevo
python tools/gen_policies_js.py --check   # nuevo
```

`admin-backend/public/catalog.js` y `policies.js` son **generados**. Si un `--check` falla, **regeneralos** (`python tools/gen_catalog_js.py`), no los edites a mano — están generados justamente para que no puedan divergir del Kotlin.

---

## 1. QUÉ SE TOCÓ

### Kotlin (6 archivos)

| Archivo | Qué cambió |
|---|---|
| `mdm/WhitelistManager.kt` | El grueso. `alwaysBlockedInAppDomains()`, `unblock`, overrides por equipo, `replaceGlobalDecisions()`, `applyAppSideEffects` con estado previo, `ruleCount()` calculado desde `buildRules()`. |
| `mdm/WhitelistCatalog.kt` | Solo el comentario del campo `block`, que decía "siempre que esta app esté permitida" y ya no es cierto. |
| `util/SelfUpdater.kt` | El bug de la alarma exacta (B.64) y dos de `openDownloadConnection`. |
| `util/FirebaseDeviceSync.kt` | Lee `devices/<id>/appPolicy` y `unblock`; publica `dnsRules`. |
| `service/LockSuiteFirebaseService.kt` | Los 5 comandos de reglas DNS. |
| `ui/dashboard/DashboardActivity.kt` | Pestaña `Inicio`, nombres/orden de pestañas, decisiones a nivel equipo. |

### Panel

**Nuevos, no tocan nada de lo viejo:** `celular.html`, `celular.js`, `dominios.html`, `dominios.js`, `comun.js`, `panel2.css`, `catalog.js` (generado), `policies.js` (generado).

**Tocados, mínimo:** `index.html` (dos enlaces + el botón de la tarjeta + cache-buster), `app.js` (dos handlers, los dos con guard `if (el)` por si se despliegan desfasados), `functions/index.js` (5 comandos).

### Herramientas

`tools/gen_catalog_js.py`, `tools/gen_policies_js.py`, `tools/check_panel_commands.py`.

---

## 2. QUÉ MIRAR SI NO COMPILA

**Compose es lo único que no se pudo type-checkear** (sigue sin haber Gradle en el entorno de la IA). Los 6 `.kt` dan **0 errores de sintaxis** con `kotlinc` 2.0.21 y los 14 símbolos que usa la pantalla nueva se verificaron uno por uno contra el código real, incluida su pertenencia al `companion object` de `KosherVpnService`. Si algo falla, es casi seguro en `HomeTabContent` y va a ser una de estas tres:

1. **`items(destinos) { (indice, titulo, detalle) -> … }`** — destructura un `Triple`. Si tu versión de Compose se queja, cambialo por `items(destinos) { d -> }` y usá `d.first` / `d.second` / `d.third`.
2. **`buildList { }`** — es stdlib desde Kotlin 1.6. Si el proyecto está en una anterior, reemplazalo por `mutableListOf<Triple<Color, String, String>>().apply { … }`.
3. **`MasterProfilesCard(policyManager = …, onApplied = …)`** — verificá la firma; se reusa a propósito y **no hay que copiarla**.

Nada de eso cambia comportamiento: si hay que tocarlo, tocá solo eso.

---

## 3. ORDEN DE PRUEBA EN EQUIPO REAL

**La 0 y la 1 van primero y no son opcionales.**

**0. Que no se rompió el panel viejo.** Se tocaron `index.html` y `app.js`. Abrí el panel, entrá a un celular por la barra lateral de siempre, tocá dos interruptores y confirmá que siguen andando igual. *(Si esto falla, revertí el commit 1 y el resto no importa.)*

**1. ⚠️ LA REGRESIÓN MÁS IMPORTANTE DE TODA LA TANDA: que Mercado Pago siga pagando.** Con el modo lista blanca **APAGADO** y sin tocar ninguna decisión, ahora se bloquean 41 dominios que antes resolvían. Entre ellos hay 9 hosts de Mercado Libre. **Hacé una transferencia real desde Mercado Pago.** Si falla, mirá `adb logcat -s KosherVPN` para ver qué dominio se cortó y sacalo desde **Dominios por app** (la ✕ en la lista roja) — para eso está `unblock`. **No revertir el cambio entero por un host.**

**2. Que el equipo sigue administrable.** Mandale cualquier comando desde el panel y confirmá que llega. Es la prueba de que ningún bloqueo nuevo pisó la infraestructura (se verificó en banco que no hay colisiones, pero es barato confirmarlo).

**3. Que lo no kosher efectivamente se cerró.** En el celular, con el filtro estricto apagado: entrar a las ofertas de Mercado Pago tiene que fallar; `translate.google.com` tiene que fallar; el foro de Waze tiene que fallar. La app en sí tiene que seguir andando.

**4. La ficha nueva.** Abrí un celular con **🗂️ Ficha completa ↗**. Confirmá que los datos son de ESE equipo. Marcá **3 apps** y aplicá: tiene que salir **un solo** `SYNC_WHITELIST` y el celular tiene que aplicar las tres. Después marcá **2 políticas** y aplicá: dos comandos, con la lista de progreso al lado.

**5. Que la decisión es POR EQUIPO.** Permití una app en un celular y confirmá en otro que **no cambió**. Es lo que antes no se podía.

**6. Desde el celular.** Tocá permitir/prohibir en la pestaña *Apps y dominios* del teléfono, mandá `SYNC_WHITELIST` desde el panel y confirmá que **la decisión sobrevive**. Antes se deshacía sola (B.63).

**7. El editor de dominios.** `dominios.html`: sacá un bloqueo de fábrica con la ✕, guardá, y tocá **Enviar a todos los celulares**. Confirmá que ese host vuelve a resolver.

**8. Reglas DNS desde el panel.** Poné un **forzar prohibir** sobre un dominio cualquiera y confirmá que se corta; después **forzar permitir** sobre uno de la lista roja y confirmá que **gana** (es la salida de emergencia y tiene que funcionar).

**9. La Tienda, después del arreglo de la alarma.** Instalá una app desde la Tienda del celular. Si el equipo es Android 12, probá además con "Alarmas y recordatorios" **revocado** para LockSuite: antes eso dejaba la Tienda sin instalar nada.

**10. En `:admin-app`** (el WebView kosher). Abrí la ficha nueva desde el celular administrador. **Deberían cargar sin tocar ninguna lista blanca** porque son del mismo origen que el panel, pero confirmalo con `adb logcat -s LockSuiteAdmin` y agregá el host si aparece alguno bloqueado. **Nunca desactivar el filtro entero "para probar si era eso".**

---

## 4. LO QUE NECESITA AL DUEÑO, NO A VOS

**El APK oficial de Waze.** El que está hoy en la Tienda (`Waze_5.11.5.1.apk`) está firmado por `O=ANDROID-KOSHER, CN=YOLEVI` — o sea reempaquetado por un tercero, no por Google. El dueño decidió reemplazarlo por el oficial. Hasta que aparezca, la entrada sigue instalándose igual (el `sha256` está bien): **el checksum garantiza que es el mismo archivo, no que sea confiable.**

Cuando tengas el oficial: subilo al release, recalculá la huella desde el panel (Ajustes → Tienda → **Calcular huella**) y acordate de que **los equipos que ya tengan el reempaquetado no se van a poder actualizar encima** — hay que desinstalarlo primero, porque la firma es distinta.

**Tres APKs no sirven en toda la flota** y hoy fallan sin explicación: Google Translate y Waze son **solo arm64-v8a**, Gboard es **solo armeabi-v7a**, y Waze exige **Android 10+**. En el CAT S22 Flip varias no van a instalar. Conviene conseguir builds universales o avisarlo en la tarjeta de la Tienda.

---

## 5. SIETE COSAS QUE NO HAY QUE "SIMPLIFICAR"

1. **Los dominios no kosher NO llevan interruptor.** Si alguien propone uno: el panel ya tenía 75 y el dueño lo llamó "mareador", y B.43 dejó escrito que *"un interruptor apagado por defecto solo protege a quien se acuerde de encenderlo"*. La salida de emergencia es `FORCE_ALLOW` por dominio y por equipo, que es **mejor** que un interruptor global.
2. **`unblock` toca solo el catálogo DE FÁBRICA**, no los bloqueos que el panel agrega. Si se "simplifica" a que borre los dos, el panel puede contradecirse a sí mismo y gana el campo que se lea último, en silencio.
3. **`catalog.js` y `policies.js` no se editan a mano.** Son generados y hay `--check`.
4. **La barra lateral vieja se queda.** Es la red por si la ficha nueva falla.
5. **El carrito no marca nada como aplicado sin ACK.** Es B.42 y costó una sesión.
6. **`unset` se guarda explícito en el mapa del equipo.** Borrar la clave no es lo mismo: tiene que poder anular un `allow` global.
7. **Los tres interruptores especiales** (suspender, kiosco, táctil) **no entran en el lote**. Cada uno puede dejar un equipo sin protección, sin poder marcar `*#*#9999#*#*`, o sin forma de operarlo con el dedo.

---

## 6. MENSAJES DE COMMIT

Ya están adentro de los dos commits del clon. Si tenés que rehacerlos, el título del primero es:

```
feat(panel/filtro): ficha completa por celular, editor global de dominios, y los dominios no kosher se bloquean siempre
```

y el del segundo:

```
feat(app): pestaña Inicio y las mismas secciones que la ficha del panel
```
