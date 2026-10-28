# Instrucciones para Antigravity — 28/10/2026

## El bucle "Google Play no estaba al frente" (B.99) y borrar LockSuite / quitar restricciones desde el panel (B.100)

Escrito por Claude (Opus) desde un contenedor en la nube. Ese contenedor **no tiene Gradle
ni el SDK de Android ni acceso a Maven/Google** (el proxy solo deja npm/pypi/crates/go/
GitHub), así que **todo está escrito en el disco pero SIN compilar con Gradle, SIN
desplegar y SIN probar en un celular.** Lo que sigue es exactamente lo que falta, en orden.
El porqué completo está en `LOCKSUITE_CONTEXTO_PARA_IA.md`, puntos **B.99** y **B.100**.

Lo que SÍ se verificó en la nube: la lógica pura (`UpdateRoutePlanner`) se compiló con
`kotlinc` y pasó un arnés de pruebas (ALL-PASS); el resto del Kotlin se revisó firma por
firma contra el código real; `node --check` y los dos chequeos de comandos
(`check_command_sync.py`, `check_panel_commands.py`) quedaron en verde. Falta lo que solo
se puede hacer en la PC: Gradle, el despliegue y el celular.

---

## 0. En una línea

1. **B.99:** actualizar una app por Google Play entraba en un bucle "Google Play no estaba
   al frente" (hasta 81 reintentos y se rendía a los 75 s) en todo equipo con la Tienda
   **oculta** o **suspendida**: el propio flujo se la re-ocultaba al destaparla. Ahora hay
   una sola fuente de verdad que mantiene la Tienda abierta durante la actualización, y si
   algo la re-bloquea se diagnostica, se cura solo unas veces y, si no, cierra diciendo el
   motivo.
2. **B.100:** la ficha completa del panel tiene ahora, en la Zona de peligro, **«Quitar
   todas las restricciones del celular»** (reversible) y **«Borrar LockSuite del celular»**
   (libera el equipo, saca Device Owner y desinstala; irreversible). Se aclaró que
   «Archivar» y «Eliminar del panel» no tocan el teléfono.

---

## 1. Traer el código a tu PC

El contenedor de la nube **no pudo escribir al disco esta vez** (el puente al dispositivo no
montó la carpeta, y sus herramientas de archivo no llegan a los `.kt`: están 8 carpetas
adentro y el puente soporta 7) **ni subir a GitHub** desde ahí. Así que los cambios van como
un **parche** y un **zip** de respaldo, adjuntos en el chat. Los dos traen lo mismo (14
archivos). El parche es un commit con su mensaje (B.99/B.100).

**Opción A — aplicar el parche (recomendada):**

```powershell
cd "E:\Documentos\Lock Suite segunda version"
git am "RUTA\0001-fix-update-panel-....patch"
# Si git am se queja por contexto, sin crear commit:
#   git apply --3way "RUTA\0001-fix-update-panel-....patch"
```

**Opción B — el zip:** extraé `cambios_locksuite_2026-10-28.zip` en la raíz del repo
(`E:\Documentos\Lock Suite segunda version`) respetando las carpetas: pisa los 12 archivos
con su versión nueva y agrega `app\...\mdm\DeviceRelease.kt` y este instructivo.

Verificá que quedó todo con:

```powershell
git status
python tools/check_command_sync.py
python tools/check_panel_commands.py
```

Después seguí con los pasos 3 a 6.

---

## 2. Qué archivos cambian

El parche/zip toca estos archivos:

**App (Kotlin):**
- `app/src/main/java/com/ejemplo/locksuite/mdm/UpdateRoutePlanner.kt` — nuevas funciones puras `storeRelaunchGapMs`, `playStoreBlockReason`, const `MAX_STORE_REBLOCKS`.
- `app/src/test/java/com/ejemplo/locksuite/mdm/UpdateRoutePlannerTest.kt` — dos tests nuevos de esas funciones.
- `app/src/main/java/com/ejemplo/locksuite/util/UpdateFlowManager.kt` — `mustKeepPlayStoreOpen`, `playStoreState`, `PlayStoreState`, `refreshKioskForUpdate`; refresco del kiosco al empezar/cerrar la ruta de Google Play.
- `app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt` — `lockTaskAllowedPackages` suma `com.android.vending` mientras dura la ruta; `restoreInstallRestrictions` no toca nada si el flujo está abierto.
- `app/src/main/java/com/ejemplo/locksuite/mdm/AppController.kt` — `hideApp`/`suspendApp` difieren el ocultar/suspender de la Tienda mientras dura el flujo.
- `app/src/main/java/com/ejemplo/locksuite/receiver/PackageReceiver.kt` — no re-bloquea `com.android.vending` si el flujo está abierto.
- `app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt` — diagnóstico/auto-cura/espera creciente cuando la Tienda no aparece; y el servicio se apaga solo (`disableSelf`) cuando `locksuite_purged` está puesto.
- `app/src/main/java/com/ejemplo/locksuite/service/CommandProcessor.kt` — nuevo caso `UNINSTALL_LOCKSUITE` (ack por corrutina + `DeviceRelease`); el comando está en `allowedWhileSuspended`.
- `app/src/main/java/com/ejemplo/locksuite/mdm/DeviceRelease.kt` — **NUEVO**: liberación completa sin Activity (misma secuencia que `EmergencyActivity.executeFullPurge`).

**Backend / panel:**
- `admin-backend/functions/index.js` — `UNINSTALL_LOCKSUITE` en `ALLOWED_COMMANDS` y `ACTION_COMMANDS`.
- `admin-backend/public/celular.js` — Zona de peligro rearmada (teléfono vs panel), funciones `quitarRestricciones` y `borrarDelCelular`, textos de «Eliminar del panel» aclarados.
- `admin-backend/public/celular.html` — aviso de la Zona de peligro reescrito.

> Si cambiaste `celular.js` / `celular.html`, acordate de subir la versión del query-string
> (`celular.js?v=…`) como se hizo en B.98, para que el navegador no sirva la copia vieja.

---

## 3. Compilar y correr las pruebas con Gradle (en la PC)

```powershell
cd "E:\Documentos\Lock Suite segunda version"
# Chequeos de sincronización (deberían estar ya en verde, pero confirmalos):
python tools/check_command_sync.py
python tools/check_panel_commands.py
# Unit tests + compilación:
./gradlew :app:testDebugUnitTest
```

Esperá los dos tests nuevos de `UpdateRoutePlannerTest`
(`reabrirGooglePlay_laPrimeraEsRapidaYDespuesEspera`,
`porQueNoApareceGooglePlay_soloLoMedible`) en verde, además de los conocidos. Las 2 de
entorno de `NetworkForwarderTest` pueden fallar como siempre.

El type-check completo contra el SDK de Android lo hace Gradle acá: en la nube solo se
pudo revisar firma por firma. Si algo de los archivos Android no compila, es acá donde va
a aparecer.

---

## 4. Desplegar — HOSTING **Y** FUNCTIONS (imprescindible)

`UNINSTALL_LOCKSUITE` es un comando nuevo: la Cloud Function `sendCommandV8` tiene que
conocerlo o lo **rechaza con 400 «Comando no reconocido»** y el botón «Borrar LockSuite del
celular» no haría nada. **No alcanza con desplegar hosting.**

```powershell
cd "E:\Documentos\Lock Suite segunda version"
./deploy_all.ps1 -VersionName "0.6.69"
```

`deploy_all.ps1` sube versionCode (→ 132) y versionName, compila release, copia los APK,
despliega **hosting + reglas RTDB + functions**, y hace el commit y push a GitHub. Si usás
otra vía de despliegue, asegurate de incluir `firebase deploy --only functions` además del
hosting.

Verificá después que `sendCommandV8` quedó en una revisión nueva (que ya conoce
`UNINSTALL_LOCKSUITE`), igual que se hizo con `route` en B.97.

---

## 5. Probar en el celular del dueño

**B.99 — Google Play no aparece:**
1. ★ En un equipo con **Play Store oculta** (o suspendida con "ocultar íconos de lo
   suspendido"), mandá actualizar una app por Google Play desde el panel. Antes entraba en
   el bucle "no estaba al frente"; ahora la Tienda tiene que quedar destapada durante el
   flujo, abrir la ficha de la app, y re-ocultarse sola al terminar.
2. En un equipo en **kiosco (Lock Task)**, lo mismo: la Tienda tiene que poder pasar al
   frente durante la ruta de Google Play.
3. Caso feo a propósito: si en algún equipo algo vuelve a bloquear la Tienda apenas se la
   destapa, el flujo tiene que **cerrar a las 3 veces diciendo el motivo** ("algo del
   equipo la vuelve a bloquear… probá con «Solo Tienda»"), no quedarse reintentando.

**B.100 — borrar / quitar restricciones:**
4. Ficha completa → **Zona de peligro** → «🧹 Quitar todas las restricciones del celular»:
   pide el PIN, el equipo queda **sin restricciones** y las apps bloqueadas **abren**. Con
   «Reanudar LockSuite» tiene que volver todo.
5. Ficha → «📵 Borrar LockSuite del celular»: doble confirmación + PIN. En el teléfono tiene
   que aparecer el pedido de **desinstalación**; al confirmarlo, LockSuite ya no está y el
   equipo queda **usable** (sin apps ocultas, táctil y barra de estado OK, se puede cambiar
   de idioma). **Ojo:** esto suelta Device Owner — es irreversible y el equipo deja de poder
   administrarse remotamente. Probalo en un equipo de descarte, no en uno en producción.
6. Confirmá que «📦 Archivar (solo saca del panel)» y «🗑️ Eliminar del panel» **no tocan el
   teléfono** (LockSuite sigue andando en el equipo después de usarlos).

---

## 6. Al terminar

Cuando lo pruebes en equipo, pasá en `LOCKSUITE_CONTEXTO_PARA_IA.md` lo que confirmes de
**B.99** y **B.100** a `[RESUELTO / CONFIRMADO EN EQUIPO]`, y actualizá la bitácora
(sección C) con la versión desplegada. Hasta entonces quedan como "escrito, sin equipo
real".
