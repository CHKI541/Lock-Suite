# Portal cautivo y selector de foto de contacto

**Sesión de Claude, 5/9/2026.** Escrito y verificado; **sin compilar con Gradle y sin probar en equipo real.**

Va con `LOCKSUITE_CONTEXTO_PARA_IA.md` § **B.46** (portal cautivo) y § **B.47** (selector de foto).

---

## 1. Portal cautivo: el hallazgo que invalida el enfoque anterior

Pedido del dueño: *"¿hay manera de que me averigües y bloquees de la mejor manera navegación
por portal cautivo, sin bloquear al usuario a que se conecte a la red?"*.

### 1.1 — Lo primero, porque cambia todo lo demás

`CaptivePortalLoginActivity` —el WebView de "Iniciar sesión en la red Wi-Fi"— llama a
**`ConnectivityManager.bindProcessToNetwork(mNetwork)`** dentro de `initializeWebView()`, y
cuando usa Custom Tabs llama además a `bypassVpnForCustomTabsProvider()` (que usa
`setDelegateUid()` por reflexión). **Verificado leyendo el fuente de AOSP, no inferido.**

O sea: **esa ventana fija su tráfico a la red física del hotel y esquiva cualquier VPN, por
diseño de Android.** Tiene que hacerlo — si su tráfico saliera por la VPN no podría hablar
con el portal, que solo existe dentro de esa red todavía sin validar.

> **Consecuencia directa: la Capa 2 de LockSuite no ve ni una consulta DNS de esa ventana.
> Ninguna lista de dominios puede protegerla, por más correcta que sea la lista.**

El filtro que había (`isCaptivePortalEscapeDomain`, B.45 punto 3) tenía además un **segundo**
motivo independiente para no ejecutarse nunca: vivía detrás de `logPackage != "desconocido"`,
y las consultas DNS de Android salen por `netd`, no por la app (B.10). Se quitó del código,
con un comentario en el lugar exacto para que no vuelva.

### 1.2 — Lo que sí se puede hacer: interruptor `captive_portal_guard` (encendido por defecto)

Archivo nuevo **`mdm/CaptivePortalPolicy.kt`**. Comandos `ENABLE/DISABLE_CAPTIVE_PORTAL_GUARD`.
Tres palancas, todas de Capa 3 y todas estructurales — **no se lee un solo texto de pantalla**,
así que cambiar el idioma no evade nada (regla de B.19):

| # | Qué hace | Por qué no rompe el login |
|---|---|---|
| 1 | **Tapa las imágenes** de esa ventana mientras está al frente (reusa el tapado de Capa 1). | Quedan el texto y los formularios: escribir usuario/contraseña y aceptar términos sigue andando. Deja de ser un visor de contenido. |
| 2 | **La cierra apenas la red valida** (elección explícita del dueño). | La validación ocurre *después* de un login exitoso: en ese momento la ventana ya no tiene razón de existir. |
| 3 | **Tope duro de 3 minutos** (constante, no configurable — decisión del dueño). | Un login legítimo tarda menos de un minuto. |

**Cómo se detecta la validación, y por qué así:** se recorren las redes descartando las de
transporte VPN, y se busca una **Wi-Fi** con `NET_CAPABILITY_VALIDATED` puesta y
`NET_CAPABILITY_CAPTIVE_PORTAL` sacada. Son dos banderas del sistema, no una heurística.
⚠️ **No se usa `cm.activeNetwork` a secas, a propósito:** con la VPN levantada eso devuelve la
red del propio túnel, que es exactamente la trampa que causó la causa 1 de B.18.

Hay **1,5 s de gracia** antes de cerrar por validación. Sin eso, abrir la ventana sobre una red
ya validada la cerraría en el mismo frame y el usuario no llegaría a leer el aviso. 1,5 s no
alcanzan para navegar.

### 1.3 — Lo que NO cubre, dicho de frente

- **Antes de validar, el dominio del propio portal es alcanzable** — tiene que serlo. Un portal
  que sirva contenido en su propia página se ve igual. No hay forma de distinguirlo sin leer la
  URL, y la ventana no la publica en ningún lado que la accesibilidad pueda ver.
- **Reabrir la ventana** sobre una red ya validada da ventanas de ~1,5 s cada vez. Molesto de
  abusar, pero no imposible.
- Por eso la cuarta pata es **visibilidad**: se reportan al panel `captivePortalOpens`,
  `captivePortalTotalMs` y `captivePortalLastOpenAt`. Lo que no se puede impedir del todo, al
  menos que se vea — misma filosofía que B.34.

### 1.4 — ⚠️ El riesgo mayor asociado, que conviene tener presente

Si hay **un navegador instalado y habilitado**, Android puede abrir el portal cautivo en una
**Custom Tab de ese navegador**, y ahí el bypass de VPN es explícito (`setDelegateUid()`). Con
los navegadores suspendidos —como ya los deja LockSuite— cae al WebView propio, que es el
camino que cubre esto. **Mantener los navegadores suspendidos importa más de lo que parece.**

### 1.5 — El límite general que este hallazgo deja anotado

*Cualquier* app que llame a `bindProcessToNetwork()` esquiva el túnel DNS. Hoy la única que lo
hace es la del portal cautivo, pero **es el techo real de la Capa 2** y conviene tenerlo escrito
para no volver a proponer una lista de dominios donde no puede haberla.

---

## 2. Selector de foto de contacto: por qué "a veces no rebotaba"

Reporte del dueño: *"me di cuenta que el bloqueo a veces no rebota, no sé por qué"*.
Archivo nuevo **`mdm/PhotoPickerPolicy.kt`**.

### 2.1 — Cuatro causas. Las dos primeras explican solas el "a veces"

1. **La sesión de administrador lo apagaba en silencio durante 5 minutos.** El rebote salía por
   `SessionManager.isActive()`, y esa sesión dura 5 minutos desde que se ingresa el PIN — que es
   exactamente lo que hay que hacer para encender el interruptor y después ir a probarlo.
   **Es, textual, el mismo bug que B.15 primera corrección puntos 3 y 4: *"no estaban rotas:
   estaban calladas"*.** Acá la excepción no tenía ninguna razón de ser: un administrador no
   necesita abrir un selector de fotos. **Se sacó, con el porqué escrito arriba de la función.**
2. **El selector de fotos DEL SISTEMA no estaba contemplado.** En Android 13+ (y en el backport
   por Play services) vive en `com.google.android.providers.media.module`, clase
   `com.android.providers.media.photopicker.PhotoPickerActivity`. La versión anterior buscaba
   `photopickerintentactivity` —que no es ese nombre— y su única regla que decía `photopicker`
   estaba condicionada a que el paquete fuera de Contactos, y el del sistema no lo es.
3. **Google Fotos como selector externo tampoco.** Su clase es
   `...apps.photos.picker.external.ExternalPickerActivity`: contiene "picker" pero **no**
   "photopicker" (hay un punto en el medio).
4. **El antirrebote de 2 s** hacía que reabrir el selector enseguida no rebotara. Bajado a 1,2 s.

### 2.2 — Esto está MEDIDO, no leído

Se extrajo la función vieja del commit y se corrió contra los cinco casos reales:

```
  NO DETECTA  <-- selector de fotos del sistema (Android 13+)
  NO DETECTA  <-- Google Fotos como selector externo
  NO DETECTA  <-- selector del sistema, clase vacia
  DETECTA         catalogo de ilustraciones
  DETECTA         avatar picker dedicado

La version vieja NO detectaba 3 de 5 casos.
```

**Vale la pena conservar el método:** extraer la función vieja y correrla es más rápido y más
confiable que discutir si el `contains` matchea. Es la misma idea que el chequeo de simetría de
B.38.

### 2.3 — ⚠️ LA DECISIÓN DE ALCANCE, QUE NO HAY QUE "SIMPLIFICAR"

**El selector de fotos del sistema es el mismo que usa WhatsApp para adjuntar una foto.**
Rebotarlo siempre dejaría al equipo sin poder mandar fotos: una regresión grande, silenciosa y
que nadie pidió. Por eso hay dos niveles:

- **SIEMPRE se rebota** el selector de *avatar / ilustraciones / foto de perfil*
  (`avatarpicker`, `artpicker`, `illustration`, `user.profile.photopicker`). Esas pantallas
  existen solo para elegir foto de perfil y nunca se usan para adjuntar: **ahí vive el catálogo
  de ilustraciones que reportó el dueño**.
- **El selector genérico de fotos y Google Fotos se rebotan SOLO si se llegó desde una app de
  contactos** (Contactos, Teléfono/Dialer, People), mirando los tres últimos paquetes del stack
  que el servicio ya mantiene. Con eso el agujero queda cerrado y adjuntar en WhatsApp sigue
  funcionando.

Si algún día se quiere cerrar también el adjuntar fotos, **eso es otra decisión y otro
interruptor** — no hay que hacerlo ampliando este.

### 2.4 — Diagnóstico incorporado

El servicio publica al panel **`photoPickerSeenClasses`**: las clases vistas en paquetes de
selector/contactos que NO se clasificaron. Si en un equipo el rebote no dispara, ahí está el
nombre exacto para agregar, en vez de adivinar. Misma idea que `debugLabels` (B.41) y
`googleAccountWebSeenClasses` (B.43). **Sin eso, "a veces no rebota" es una sesión entera de
adivinanza — que es exactamente lo que pasó.**

---

## 3. Archivos tocados

| Archivo | Qué cambió |
|---|---|
| `mdm/CaptivePortalPolicy.kt` | **NUEVO.** Detección de la ventana, tope de tiempo, gracia. Lógica pura. |
| `mdm/PhotoPickerPolicy.kt` | **NUEVO.** Los dos niveles de alcance del selector. Lógica pura. |
| `service/LockSuiteAccessibilityService.kt` | Rebote del selector reescrito (sin excepción de administrador), diagnóstico, ciclo del portal cautivo, tapado de imágenes forzado en esa ventana, limpieza en `onDestroy`. |
| `service/KosherVpnService.kt` | **Se QUITÓ** la rama de portal cautivo y `isCaptivePortalEscapeDomain()`. Queda un comentario explicando por qué no puede volver. |
| `mdm/PolicyManager.kt` | Interruptor `captive_portal_guard`, contadores del portal, `getPhotoPickerSeenClasses()`, perfil exportable. |
| `service/LockSuiteFirebaseService.kt` | Comandos FCM. |
| `util/FirebaseDeviceSync.kt` | `captivePortalGuard`, `captivePortalOpens`, `captivePortalTotalMs`, `captivePortalLastOpenAt`, `photoPickerSeenClasses`. |
| `ui/dashboard/DashboardActivity.kt` | Interruptor en la app. |
| `admin-backend/public/index.html` | Interruptor por dispositivo y por grupo + explicación. Cache-buster a **`v=30`**. |
| `admin-backend/public/app.js` | Mapeo a comandos y clave del perfil. |
| `admin-backend/functions/index.js` | `ALLOWED_COMMANDS`. |

---

## 4. Verificación hecha

- **`kotlinc` 2.0.21** contra stubs de la API de Android, con el código **extraído del archivo
  real** (no transcripto): **0 errores / 0 warnings**.
- **6 controles negativos, los 6 detectados**: método inexistente, tipo equivocado, constante
  inexistente, aridad equivocada, `NetworkCapabilities` inexistente, asignación de tipo
  equivocado.
- **Prueba de comportamiento ejecutada**: 48 casos, todos verdes, **con control negativo**.
  Incluye explícitamente los casos que NO se pueden romper: **adjuntar una foto en WhatsApp**,
  Google Fotos abierta como app normal, editar un contacto, recortar una foto propia, la cámara.
- **La función vieja del selector corrida contra los casos reales** (§2.2): 3 de 5 sin detectar.
- Chequeo de parseo antes/después en los 7 archivos Kotlin (0 y 0), con control negativo.
- Chequeo de simetría de restricciones (B.38): verde.
- `node --check` sobre `app.js` y `functions/index.js`.
- **NO se corrió Gradle. NO se generó APK. NO se probó nada en equipo real.**

---

## 5. Prueba en equipo real, en este orden

**Selector de foto** (las dos primeras son las que prueban el diagnóstico):

1. **Ingresar el PIN en la app y, en los 5 minutos siguientes**, ir a Contactos → editar un
   contacto → tocar la foto. **Tiene que rebotar.** Antes no rebotaba justamente acá.
2. **⚠️ Adjuntar una foto en WhatsApp tiene que seguir funcionando.** Es la regresión a vigilar.
3. El catálogo de ilustraciones tiene que rebotar venga de donde venga.
4. Sacar una foto con la cámara y recortarla para un contacto: tiene que seguir andando.
5. Si algo no rebota, mirar **`photoPickerSeenClasses`** en el panel: ahí está el nombre exacto.

**Portal cautivo** (necesita un Wi-Fi con portal de verdad — un bar, un hotel):

6. Conectarse y **completar el login con las imágenes tapadas**. Si el botón de aceptar fuera
   una imagen y quedara tapado, apagar el interruptor desde el panel y anotarlo.
7. Al validar la red, la ventana **tiene que cerrarse sola** con el aviso.
8. Volver a abrirla desde Ajustes → Wi-Fi → "Iniciar sesión en la red" sobre la red ya
   conectada: tiene que cerrarse a los ~1,5 s.
9. Mirar en el panel el contador de aperturas y el tiempo total.

---

## 6. Mensaje de commit

```
feat(portal-cautivo/selector-fotos): cerrar lo que se puede y medir lo que no

PORTAL CAUTIVO. Hallazgo que invalida el enfoque anterior:
CaptivePortalLoginActivity llama a bindProcessToNetwork() en
initializeWebView() (y a setDelegateUid() en modo Custom Tabs), o sea que fija
su trafico a la red fisica y esquiva cualquier VPN por diseno de Android.
Verificado leyendo el fuente de AOSP. Consecuencia: la capa 2 no ve ni una
consulta de esa ventana y ninguna lista de dominios puede protegerla.

Se quito la rama de com.android.captiveportallogin de KosherVpnService y la
funcion isCaptivePortalEscapeDomain: no estaban mal escritas, no podian
funcionar (y encima vivian detras de logPackage != "desconocido", con las
consultas saliendo por netd - B.10). Queda un comentario en el lugar exacto
para que no vuelvan.

Reemplazado por captive_portal_guard (encendido por defecto,
ENABLE/DISABLE_CAPTIVE_PORTAL_GUARD), todo capa 3 y estructural: tapa las
imagenes de esa ventana (queda el texto y los formularios, asi que el login
sigue andando), la cierra apenas la red valida (NET_CAPABILITY_VALIDATED
puesta y CAPTIVE_PORTAL sacada sobre la Wi-Fi, recorriendo redes y descartando
las de transporte VPN - nunca cm.activeNetwork a secas, que es la trampa de
B.18) y le pone un tope duro de 3 minutos. Ademas reporta aperturas y tiempo
total al panel: lo que no se puede impedir del todo, al menos que se vea.

SELECTOR DE FOTO DE CONTACTO. El dueno reporto que "a veces no rebota".
Cuatro causas, las dos primeras explican solas el "a veces":
1. SessionManager.isActive() lo apagaba 5 minutos, o sea justo despues de
   ingresar el PIN para encender el interruptor. Es el mismo bug que B.15
   primera correccion puntos 3 y 4. Sacado.
2. El selector de fotos DEL SISTEMA (Android 13+) no estaba contemplado:
   com.google.android.providers.media.module /
   com.android.providers.media.photopicker.PhotoPickerActivity.
3. Google Fotos como selector externo tampoco (picker.external, no photopicker).
4. Antirrebote de 2 s bajado a 1,2 s.

Las causas 2 y 3 estan MEDIDAS: se extrajo la funcion vieja y se corrio contra
los cinco casos reales - no detectaba 3 de 5.

Decision de alcance que no hay que simplificar: el selector del sistema es el
mismo que usa WhatsApp para adjuntar fotos. Se rebota SIEMPRE el de avatar,
ilustraciones y foto de perfil (donde vive el catalogo que reporto el dueno y
que nunca sirve para adjuntar), y el generico SOLO si se llego desde una app de
contactos. Asi el agujero queda cerrado y mandar fotos sigue funcionando.

Diagnostico nuevo photoPickerSeenClasses al panel, para calibrar en un equipo
donde no dispare en vez de adivinar.

Cache-buster de app.js a v=30.

Verificado: kotlinc 2.0.21 con stubs y el codigo extraido del archivo real,
0 errores / 0 warnings, con 6 controles negativos todos detectados; prueba de
comportamiento ejecutada (48 casos) con control negativo, incluyendo los casos
que no se pueden romper (adjuntar en WhatsApp, Google Fotos como app normal,
recortar una foto propia); chequeo de parseo antes/despues en los 7 archivos
con su control negativo; simetria de restricciones (B.38) verde; node --check.
Sin Gradle y sin probar en equipo.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XWJG4LXCD3KKSUbcvXZV7p
```

Después: `firebase deploy --only hosting,functions` y abrir el panel con **Ctrl+F5**.
