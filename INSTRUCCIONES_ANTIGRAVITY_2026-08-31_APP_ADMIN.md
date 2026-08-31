# Instrucciones para Antigravity — App de administración para celular (`:admin-app`)

**Fecha:** 31/8/2026
**Escrito por:** sesión de Claude vía el puente al dispositivo (sin terminal: **no compiló, no desplegó y no commiteó nada**).
**Qué te toca a vos:** commitear, compilar, desplegar y probar en el celular.

Antes de tocar código leé `LOCKSUITE_CONTEXTO_PARA_IA.md` completo, y en particular
**B.22** (la lista razonada de los doce defectos) y la sección "App de administración
para celular" de la parte A (el mapa del módulo y cómo funcionan las listas blancas).

---

## 0-BIS. Actualización del 31/8 (tarde) — LEER ANTES QUE NADA

Se probó el APK en el celular y **no se puede iniciar sesión**. Dos síntomas, dos causas
distintas, y ninguna de las dos es un bug de la app.

### (A) El error de la pantalla NO es de la app: es la base de datos

```
Error al verificar permisos: permission_denied at /authorizedAdminsUids/dklXHRAY3tdjmnwzX0CxJMoGb…
Client doesn't have permission to access the desired data.
```

Ese texto aparece **después** de que Firebase Auth aceptó el email y la contraseña — el UID
en el mensaje lo prueba: la cuenta existe y la contraseña es correcta. Lo que falla es el
paso siguiente, cuando `app.js` pregunta si esa cuenta está autorizada a administrar.

La regla de `database.rules.json` es **auto-referencial**:

```json
"authorizedAdminsUids": {
  ".read": "auth != null && root.child('authorizedAdminsUids').child(auth.uid).exists()"
}
```

Para leer si sos admin, tenés que **ya estar** en la lista. O sea que una cuenta no
autorizada no recibe "vacío": recibe `permission_denied`. El mensaje suena a falla del
sistema y en realidad significa, simplemente, **"esta cuenta no está en la lista de
administradores"**.

De yapa: el plan B por email (`authorizedAdmins/<email>`) tampoco puede funcionar nunca,
porque su regla de lectura exige lo mismo. Es código muerto.

**Prueba que lo confirma en 30 segundos, sin compilar nada:** abrí
`https://locksuite-nueva.web.app` en Chrome **en la PC** y entrá con el mismo email y
contraseña. Si aparece el mismo error, queda descartada la app por completo.

**Arreglo inmediato — 2 minutos, sin compilar ni desplegar nada:**

1. Firebase Console → proyecto `locksuite-nueva` → **Authentication → Users**.
2. Buscá la cuenta con la que querés entrar y copiá su **User UID** completo.
3. **Realtime Database → Data**, nodo raíz → `authorizedAdminsUids`.
4. Agregá un hijo con **nombre = ese UID** y **valor = `true`** (booleano, no el texto `"true"`).

Con eso el login por email y contraseña funciona **ya**, incluso con el APK que ya está
instalado en el celular.

**Arreglo de fondo (ya escrito, falta desplegar):**

- `admin-backend/database.rules.json` — se agregó que cada usuario pueda leer **su propia**
  entrada (tres líneas). Así una cuenta no autorizada recibe "no existe" en vez de
  `permission_denied`.
- `admin-backend/public/app.js` — la verificación ahora distingue "no autorizado" de "error
  de verdad", y cuando la cuenta no está autorizada **muestra el UID en pantalla**, que es
  justo el dato que hay que copiar a la consola. Nunca más un mensaje de error críptico por
  esto.
- `admin-backend/public/index.html` — cache-buster a `app.js?v=19`.

```powershell
cd admin-backend
firebase deploy --only hosting,database
```

### (B) El login con Google no puede funcionar con el APK que está instalado

`signInWithPopup` necesita `window.open`, y en un WebView de Android eso devuelve `null`
salvo que se habiliten ventanas múltiples. Firebase entonces tira `auth/popup-blocked`. La
pantalla "titila" porque el popup se abre y se cierra en el acto.

**El arreglo ya está escrito** (`app.js` usa `signInWithRedirect` cuando detecta el token
`LockSuiteAdminApp` en el User-Agent), pero **necesita las dos cosas juntas**: el APK
recompilado *y* el panel desplegado. Con uno solo de los dos no alcanza.

⚠️ **No lo "arregles" habilitando `setSupportMultipleWindows(true)`.** Es tentador y es
justo lo que abre el agujero: una ventana nueva del WebView **no pasa por
`shouldOverrideUrlLoading`**, o sea que se saltea la lista blanca entera y el celular deja
de ser kosher. El camino correcto es la redirección, que navega en la misma ventana y sí
pasa por el filtro.

### (C) Los cambios del reporte anterior no están en esta carpeta

Esto hay que decirlo porque cambia qué falta hacer. Al revisar el disco el 31/8 a la tarde:

| Qué se buscó | Resultado |
|---|---|
| `ALLOWED_NAV_HOSTS`, `ALLOWED_SUBRESOURCE_HOSTS`, `LockSuiteBridge`, `@JavascriptInterface`, `setSupportMultipleWindows(true)`, `accounts.youtube.com` en `MainActivity.kt` | **0 coincidencias de cada uno** |
| Lo que sí está en `MainActivity.kt` | `NAV_ALLOWED_HOSTS` (×5) y `setSupportMultipleWindows(false)` (×2) — la versión escrita el 31/8 a la mañana, sin tocar |
| `release-apk/LockSuite_Admin.apk` | **4.830.930 bytes (4,6 MB), del 17/8** — no 1,95 MB, no recompilado |
| `admin-backend/public/LockSuite_Admin.apk` | idéntico: 4.830.930 bytes, del 17/8 |
| `mipmap-mdpi` … `mipmap-xxxhdpi` | no existen; los íconos legacy son los `mipmap/ic_launcher.xml` del 31/8 |
| `EXPLICACION_APK_ADMIN_PARA_OTRAS_IA.md` | sigue sin aparecer |

O sea: **el APK que está instalado en el celular es el original del 17/8**, el que tiene
`shouldOverrideUrlLoading` devolviendo `false` — sin ningún confinamiento kosher. Y el panel
desplegado es el viejo también.

Puede haber pasado algo tan simple como haber trabajado sobre otra copia del proyecto, o que
un guardado no llegara al disco. No importa el porqué: **lo importante es que los pasos 1 a 4
de más abajo siguen todos pendientes**, y que mientras tanto el celular está corriendo una
app sin filtro. Si tenés esos cambios en otro lado, **no los pises encima de estos**:
compará primero, porque la versión que está en el repo pasó type-check y la otra no la vio
nadie.

**Orden correcto para salir de esto:**

1. Agregar el UID en Firebase Console *(2 minutos, desbloquea el login hoy)*.
2. Commit (§1) y compilar (§2).
3. Desplegar `hosting,database` (§3, ahora incluye el arreglo de reglas).
4. Instalar el APK nuevo y correr las pruebas (§4).

---

## 0. Estado del repo cuando arrancás

Hay **12 archivos modificados sin commitear**:

```
admin-app/build.gradle.kts
admin-app/proguard-rules.pro                                   (nuevo)
admin-app/src/main/AndroidManifest.xml
admin-app/src/main/java/com/ejemplo/locksuite/admin/MainActivity.kt   (reescrito)
admin-app/src/main/res/layout/activity_main.xml
admin-app/src/main/res/values/strings.xml
admin-app/src/main/res/xml/network_security_config.xml         (nuevo)
admin-app/src/main/res/mipmap/ic_launcher.xml                  (nuevo)
admin-app/src/main/res/mipmap/ic_launcher_round.xml            (nuevo)
admin-backend/database.rules.json                              (arreglo del login, ver 0-BIS)
admin-backend/public/app.js
admin-backend/public/index.html
```

⚠️ **`deploy_all.ps1` hace `git add .`**, así que si lo corrés sin commitear primero se
va a llevar estos archivos junto con cualquier otra cosa que haya suelta en el working
tree. **Commiteá esto solo, primero.**

Verificá antes de empezar, como siempre:

```powershell
git log -1
git status
cat app/build.gradle.kts        # el MDM sigue en 95 / 0.6.32, esta sesión no lo tocó
cat admin-app/build.gradle.kts  # la app admin pasa de 1/1.0.0 a 2/1.1.0
```

---

## 1. Commit (copiar tal cual)

```powershell
git add admin-app admin-backend/database.rules.json admin-backend/public/app.js admin-backend/public/index.html LOCKSUITE_CONTEXTO_PARA_IA.md INSTRUCCIONES_ANTIGRAVITY_2026-08-31_APP_ADMIN.md
git commit -F- <<'MSG'
admin-app: confinar el WebView a una lista blanca y arreglar 12 defectos de la app de administración

La app de administración para celular era, de hecho, un navegador completo sin barra de
direcciones: shouldOverrideUrlLoading devolvía false en todos los casos, así que un solo
enlace externo, un redirect o un iframe alcanzaba para que el celular dejara de ser kosher.
Ese es el motivo principal de este cambio.

Confinamiento (lo central):
- Dos listas blancas en MainActivity: NAV_ALLOWED_HOSTS (a dónde puede NAVEGAR, por host
  exacto: el panel y accounts.google.com para el login) y RESOURCE_ALLOWED_* (de dónde
  puede bajar subrecursos: Firebase, gstatic, la Cloud Function, los íconos). Un host de
  la segunda lista no se puede visitar.
- Se rechaza todo esquema que no sea https, lo que de paso cierra intent:// — el vector
  clásico para saltar desde un WebView a Chrome.
- shouldInterceptRequest como segunda barrera (scripts, XHR, iframes).
- setSupportMultipleWindows(false) + onCreateWindow=false: window.open y target=_blank
  caen dentro del mismo WebView y pasan por el filtro en vez de esquivarlo.
- Descargas bloqueadas con aviso; geolocalización, cámara y micrófono denegados.
- Todo lo bloqueado se loguea con el tag LockSuiteAdmin para poder diagnosticar.

Defectos que dejaban la app inservible:
- restoreState() podía devolver null (Bundle perdido o truncado) y nadie llamaba a
  loadUrl(): la app quedaba en negro para siempre al rotar o al volver de segundo plano.
- onRenderProcessGone no estaba implementado: si Android mataba el renderer por falta de
  memoria —el perfil típico de un celular económico— se llevaba puesto el proceso entero.
- Deslizar para recargar se armaba con webView.scrollY == 0, pero el panel dibuja el
  detalle del dispositivo dentro de un contenedor con overflow-y propio que no mueve ese
  valor: con el panel abierto, cualquier arrastre hacia abajo recargaba la página. Ahora
  el gesto solo se arma si el dedo bajó en los primeros 64 dp.
- El ícono adaptativo vivía solo en mipmap-anydpi-v26 y minSdk es 24: en Android 7.0/7.1
  no existía @mipmap/ic_launcher. Se agregaron los dos íconos sin calificador de versión.

Seguridad:
- allowBackup pasa a false: la sesión de administrador (cookies del WebView) se copiaba al
  backup de Google y se podía extraer con adb backup.
- network_security_config nuevo: sin cleartext y solo CA del sistema.
- R8 activado en release con reglas conservadoras.
- onReceivedSslError explícito y cancelando (nunca proceed()).

Compatibilidad:
- configChanges completo + launchMode singleTask: cambiar modo oscuro, tamaño de fuente o
  idioma ya no destruye la Activity ni recarga el panel.
- onPause/onResume del WebView y CookieManager.flush() para no perder la sesión.
- onShowFileChooser implementado (el botón de importar preset era un botón muerto).
- La pantalla de "sin conexión" va dentro de un ScrollView: en horizontal el botón
  Reintentar quedaba fuera de pantalla.
- Se avisa con un cartel si Android System WebView está desactivado, en vez de cerrarse.

Panel:
- El botón de login con Google usaba signInWithPopup, que no funciona en un WebView.
  Dentro de la app (User-Agent con el token LockSuiteAdminApp) usa signInWithRedirect;
  en la PC sigue con popup. Se agregó getRedirectResult().catch() para que un error de
  Google se vea en pantalla. Cache-buster de app.js a v=19.

Login: el "permission_denied" no era un error, era una cuenta sin autorizar:
- La regla de authorizedAdminsUids es auto-referencial (para leer si sos admin tenés que ya
  estar en la lista), así que una cuenta no autorizada recibía permission_denied en vez de
  "no existe", y el panel lo mostraba como "Error al verificar permisos". Parecía una falla
  del sistema o de la app Android y no lo era.
- database.rules.json: cada usuario puede leer ahora su PROPIA entrada de
  authorizedAdminsUids. La autorización real no cambia: sigue siendo estar en esa lista.
- app.js: la verificación distingue "no autorizado" de un error de verdad, y cuando la
  cuenta no está autorizada muestra el UID en pantalla, que es el dato exacto que hay que
  copiar a la consola de Firebase.

Verificación: type-check con kotlinc 2.0.21 contra stubs de la API de Android, 0 errores y
0 warnings, con tres controles negativos. NO se compiló con Gradle ni se probó en equipo.
MSG
```

---

## 2. Compilar

```powershell
.\gradlew :admin-app:assembleRelease
```

El APK sale en `admin-app/build/outputs/apk/release/`. Copialo a
`admin-backend/public/LockSuite_Admin.apk`.

**Si el build falla:**

| Síntoma | Qué hacer |
|---|---|
| Falla en la tarea de R8 / `minifyReleaseWithR8`, o compila pero la app arranca en negro | En `admin-app/build.gradle.kts` poné `isMinifyEnabled = false` y `isShrinkResources = false`, recompilá y **avisá**. Es un cambio aislado de seguridad, no de comportamiento: se puede posponer sin tocar nada más. |
| `RELEASE_STORE_FILE debe configurarse en local.properties` | Falta el keystore; es el mismo que usa `:app`. |
| Error de recursos con `@mipmap/ic_launcher` duplicado | No debería: `res/mipmap/` (sin calificador) y `res/mipmap-anydpi-v26/` conviven a propósito, el segundo gana desde Android 8. Si aaptr se queja, pegame el error textual. |

---

## 3. Desplegar el panel

Los cambios de `app.js`/`index.html` son necesarios para que el login con Google funcione
dentro de la app. Como siempre: **`hosting,database` primero, `functions` aparte**.

```powershell
cd admin-backend
firebase deploy --only hosting,database
firebase deploy --only functions   # en su propio intento
```

---

## 4. Pruebas en el celular, en este orden

Están ordenadas por cuál descarta el riesgo más grande más rápido.

### 4.1 — Login (RIESGO NÚMERO UNO)

Esto es lo primero porque es lo único que no se pudo razonar con certeza: el flujo de
Google toca varios dominios y la lista blanca de subrecursos puede haber quedado corta.

1. Dejá corriendo, en otra ventana:
   ```powershell
   adb logcat -c ; adb logcat -s LockSuiteAdmin
   ```
2. Abrí la app y entrá **con email y contraseña**. Tiene que entrar.
3. Cerrá sesión y entrá **con Google**.
4. Mirá el logcat. Cada línea `BLOQUEADO (recurso): https://host/...` es un dominio que la
   lista blanca dejó afuera.

**Si el login con Google no completa:** agregá el host que aparezca en el log a
`RESOURCE_ALLOWED_HOSTS` (host exacto) o a `RESOURCE_ALLOWED_SUFFIXES` (sufijo de dominio)
en `MainActivity.kt`, recompilá y repetí. **No agregues nada a `NAV_ALLOWED_HOSTS`** salvo
que la línea diga `BLOQUEADO (navegación)` y sea claramente parte del flujo de OAuth.

🛑 **Lo que NO hay que hacer** es "sacar el filtro un rato para ver si era eso". Ese filtro
es la razón de ser de todo este cambio; si queda apagado en una compilación que después se
instala, el celular deja de ser kosher y nadie se entera.

*Si aun agregando hosts el redirect no cierra:* el remedio documentado por Firebase es
poner `authDomain: "locksuite-nueva.web.app"` en `admin-backend/public/firebase-config.js`
(hoy es `locksuite-nueva.firebaseapp.com`), para que el flujo quede en un solo origen.
No se hizo desde acá para no cambiar el login de la PC sin poder probarlo. **Probalo en la
PC también antes de dejarlo.**

### 4.2 — Confinamiento kosher

1. Entrá al panel y probá el botón **"🔌 Instalador Web"** (es un `target="_blank"` a
   `installer/index.html`): tiene que abrir **dentro** de la app, sin ventana nueva.
2. Desde el login con Google, en la pantalla de cuentas de Google, tocá los enlaces del pie
   (**Ayuda / Privacidad / Condiciones**). Tienen que **no hacer nada** y mostrar el cartel
   *"Esta app solo abre el panel de LockSuite."*. En el logcat aparece
   `BLOQUEADO (navegación)`.
3. Confirmá que en ningún momento se abre Chrome ni ninguna otra app.

### 4.3 — El gesto que antes rompía todo

1. Abrí el panel de un dispositivo (la barra lateral).
2. Deslizá hacia abajo **en el medio del contenido** para subir en la lista.
3. **No tiene que recargarse la página.** Antes se recargaba siempre.
4. Deslizá hacia abajo **empezando desde la cabecera de la barra lateral** (los primeros
   ~64 dp de la pantalla): ahí sí tiene que aparecer el indicador de recarga.

### 4.4 — Robustez

1. **Rotar** el celular con el panel abierto y con un dispositivo seleccionado: no se tiene
   que recargar ni quedar en negro.
2. **Cambiar el modo oscuro y el tamaño de fuente** del sistema con la app abierta: no se
   tiene que recargar.
3. **Segundo plano largo:** abrí varias apps pesadas para que el sistema mate el proceso,
   volvé a la app. Tiene que volver donde estaba, o recargar el panel — **nunca quedar en
   negro** y nunca pedir login de nuevo.
4. **Modo avión:** aparece la pantalla de "Sin conexión"; el botón **Reintentar** tiene que
   estar visible y alcanzable **también con el celular en horizontal**.
5. **Importar preset:** el botón tiene que abrir el selector de archivos del sistema.
6. **Exportar preset:** tiene que aparecer el cartel *"Las descargas están deshabilitadas"*.
   Es el comportamiento elegido a propósito, no un bug (ver B.22).

### 4.5 — Compatibilidad

1. Si hay a mano un equipo **Android 7.0 o 7.1**, instalá y confirmá que **el ícono aparece
   en el lanzador** (era el bug 7 de B.22).
2. Probá en la pantalla más chica que tengas. Los cortes del CSS del panel están en 768 px,
   480 px y 320 px.

---

## 5. Ocho cosas que parecen rulos innecesarios y NO hay que "simplificar"

1. **Las dos listas blancas separadas.** Parecen redundantes y no lo son: navegar a un
   dominio y bajarle una imagen son permisos distintos. Fundirlas en una sola abre la web.
2. **La comparación de `NAV_ALLOWED_HOSTS` es por host EXACTO.** Cambiarla a "termina en
   `.google.com`" abre `support.google.com`, `news.google.com`, `translate.google.com` y
   con eso media internet.
3. **`shouldInterceptRequest` además de `shouldOverrideUrlLoading`.** El primero ve toda
   petición; el segundo solo las navegaciones. Sacar cualquiera de los dos deja un hueco.
4. **`setSupportMultipleWindows(false)`.** Parece una limitación estética. Es lo que fuerza
   a que `window.open` y `target="_blank"` pasen por el filtro en vez de esquivarlo.
5. **El rechazo de todo esquema que no sea `https`.** Ahí adentro está el bloqueo de
   `intent://`, que es como se salta de un WebView a Chrome en un renglón.
6. **La zona de 64 dp del gesto de recargar.** No es un capricho: es lo único que separa
   "deslizar en el panel" de "recargar la página" sin meter JavaScript en el medio.
7. **El chequeo de `null` en `restoreState`.** Se ve como una guarda paranoica. Es el
   arreglo de la pantalla en negro permanente.
8. **`onRenderProcessGone` devolviendo `true`.** Si alguien lo saca "porque nunca pasa",
   vuelve el cierre inesperado sin explicación en los equipos con poca memoria — que son
   justamente los que se usan como celular kosher.

---

## 6. Lo que quedó pendiente a propósito

- **Exportar presets desde el celular.** Requiere leer una URL `blob:` desde código nativo,
  y eso necesita un puente de JavaScript. Se eligió bloquear y avisar en vez de abrir esa
  superficie sin poder probarla. Si el dueño lo pide, la vía limpia es
  `WebViewCompat.addWebMessageListener` de `androidx.webkit` con la lista de orígenes
  acotada al panel — el framework verifica el origen, así que es más seguro que
  `addJavascriptInterface`.
- **`EXPLICACION_APK_ADMIN_PARA_OTRAS_IA.md` no está en el repo.** El dueño dice que lo
  creaste; no aparece ni en la raíz ni dentro de `admin-app/`. Si lo tenés, guardalo; si
  no, el mapa del módulo quedó escrito en la parte A del contexto y en los comentarios de
  `MainActivity.kt`.
- **Detalles menores del panel** (íconos de apps servidos desde `upload.wikimedia.org`,
  elementos táctiles por debajo de 48 dp, `viewport-fit=cover` sin `env(safe-area-inset-*)`):
  anotados al final de **B.22**, ninguno urgente.

---

## 7. Al cerrar

Si las pruebas salen bien, tachá en **B.22** lo que quedó confirmado **en equipo real**
(no alcanza con que compile) y anotá lo que aparezca nuevo. Reemplazá la sección **C** por
el resumen de tu sesión.
