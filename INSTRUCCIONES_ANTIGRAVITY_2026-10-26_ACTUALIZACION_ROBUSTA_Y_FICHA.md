# Instrucciones para Antigravity — 26/10/2026 (cerrado el 27/10)

## Actualización de apps robusta (B.97) y ficha completa rearmada (B.98)

Escrito por Claude, trabajando desde la nube por el puente al dispositivo (ese puente solo
deja leer y escribir archivos: no compila, no corre `git`). **Todo está escrito en el
disco, pero SIN commit, SIN compilar con Gradle, SIN desplegar y SIN probar en un
celular.** Lo que sigue es exactamente lo que falta, en orden. El porqué completo está en
`LOCKSUITE_CONTEXTO_PARA_IA.md`, puntos **B.97** y **B.98**.

---

## 0. En una línea

Actualizar una app "no siempre andaba, dependía de la app y del celular" por cuatro
causas: con el **celular bloqueado** la pantalla negra tapaba la pantalla de bloqueo y no
se podía desbloquear; **cualquier barra de progreso** de la ficha (el video de la app
cargando) se leía como "ya está descargando" y no se apretaba nada; una descarga **en
cola** no tenía tope; y las apps que **no instaló Google Play** pueden estar firmadas por
otro y Play nunca las actualiza. Ahora hay dos caminos (Google Play, o la copia de la
Tienda instalada en silencio como Device Owner), elegidos solos y con el porqué a la
vista, y cada paso queda anotado en el celular y en el panel. Aparte, la ficha completa
del panel tiene ahora todo lo que antes solo estaba en la vista rápida.

---

## 1. ANTES QUE NADA: borrar dos archivos de un primer intento

En esta misma sesión, antes de que se reiniciara el contenedor de la nube, se había
escrito al disco un primer intento de lo mismo (sin verificar). Se reemplazó todo por la
versión verificada, pero **estos dos archivos no se pudieron borrar desde la nube** y
**no los usa nada**:

```powershell
cd "E:\Documentos\Lock Suite segunda version"
Remove-Item "app\src\main\java\com\ejemplo\locksuite\util\UpdatePlan.kt"
Remove-Item "app\src\test\java\com\ejemplo\locksuite\util\UpdatePlanTest.kt"
```

Si quedan, compilan (son Kotlin puro) pero son código muerto que describe un diseño que
no existe, y `deploy_all.ps1` (que hace `git add .`) los metería en el commit. El primer
intento entero quedó respaldado en
`Claude outputs\2026-10-26_B_respaldo_primer_intento_NO_APLICAR.patch` — **no aplicarlo**;
es solo para consultar.

---

## 2. Commit — ANTES de `deploy_all.ps1`

```powershell
git status
```

Tiene que mostrar **modificados** estos 17 archivos:

```
admin-backend/functions/index.js
admin-backend/public/celular.html
admin-backend/public/celular.js
admin-backend/public/panel2.css
app/src/main/java/com/ejemplo/locksuite/LockSuiteApplication.kt
app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt
app/src/main/java/com/ejemplo/locksuite/receiver/PackageInstallStatusReceiver.kt
app/src/main/java/com/ejemplo/locksuite/service/BlockOverlayManager.kt
app/src/main/java/com/ejemplo/locksuite/service/CommandProcessor.kt
app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt
app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt
app/src/main/java/com/ejemplo/locksuite/util/PlayButtonFinder.kt
app/src/main/java/com/ejemplo/locksuite/util/SelfUpdater.kt
app/src/main/java/com/ejemplo/locksuite/util/UpdateFlowManager.kt
LOCKSUITE_CONTEXTO_PARA_IA.md
tools/panel_render/README.md
tools/panel_render/test_ficha.js
```

y **sin seguimiento** estos 6:

```
app/src/main/java/com/ejemplo/locksuite/mdm/UpdateRoutePlanner.kt
app/src/main/java/com/ejemplo/locksuite/mdm/UpdateStepLog.kt
app/src/main/java/com/ejemplo/locksuite/util/HostedAppUpdater.kt
app/src/test/java/com/ejemplo/locksuite/mdm/UpdateRoutePlannerTest.kt
app/src/test/java/com/ejemplo/locksuite/mdm/UpdateStepLogTest.kt
INSTRUCCIONES_ANTIGRAVITY_2026-10-26_ACTUALIZACION_ROBUSTA_Y_FICHA.md
```

- `ui/auth/LoginActivity.kt` y `ui/update/AppUpdateActivity.kt` **no** tienen que
  aparecer: el primer intento los había tocado y se dejaron otra vez como en `HEAD`. Si
  aparecen, `git diff` de cada uno tiene que salir vacío (solo fin de línea); en ese caso
  `git checkout -- <archivo>`.
- Si aparece `admin-backend/public/styles.css`: es el cambio de 1 byte que está sin
  commitear desde el 10/9 (ver B.95, "Hallazgos"); **no es de esta sesión**, no meterlo.
- Si aparecen `UpdatePlan.kt` / `UpdatePlanTest.kt`: falta el paso 1.

```powershell
git add admin-backend/functions/index.js admin-backend/public/celular.html admin-backend/public/celular.js admin-backend/public/panel2.css
git add app/src/main/java/com/ejemplo/locksuite/LockSuiteApplication.kt app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt app/src/main/java/com/ejemplo/locksuite/mdm/UpdateRoutePlanner.kt app/src/main/java/com/ejemplo/locksuite/mdm/UpdateStepLog.kt
git add app/src/main/java/com/ejemplo/locksuite/receiver/PackageInstallStatusReceiver.kt app/src/main/java/com/ejemplo/locksuite/service/BlockOverlayManager.kt app/src/main/java/com/ejemplo/locksuite/service/CommandProcessor.kt app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt
git add app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt app/src/main/java/com/ejemplo/locksuite/util/HostedAppUpdater.kt app/src/main/java/com/ejemplo/locksuite/util/PlayButtonFinder.kt app/src/main/java/com/ejemplo/locksuite/util/SelfUpdater.kt app/src/main/java/com/ejemplo/locksuite/util/UpdateFlowManager.kt
git add app/src/test/java/com/ejemplo/locksuite/mdm/UpdateRoutePlannerTest.kt app/src/test/java/com/ejemplo/locksuite/mdm/UpdateStepLogTest.kt
git add tools/panel_render LOCKSUITE_CONTEXTO_PARA_IA.md INSTRUCCIONES_ANTIGRAVITY_2026-10-26_ACTUALIZACION_ROBUSTA_Y_FICHA.md
git commit -F "Claude outputs/2026-10-26_B_mensaje_commit.txt"
```

**Para comparar:** `Claude outputs\2026-10-26_B_actualizacion_robusta_y_ficha.patch` es el
mismo cambio hecho como commit sobre `174e849` en el clon de la sesión (en LF; el disco
conserva el fin de línea que tenía cada archivo). `git show --stat HEAD` tiene que listar
los mismos 23 archivos que `git apply --stat` de ese parche.

**Plan B, si el árbol no coincide con la lista** (otra sesión tocó algo en el medio):
guardar lo que haya y aplicar el parche sobre `HEAD` limpio, que crea el commit solo:

```powershell
git stash push -u -m "antes de B.97"
git am "Claude outputs\2026-10-26_B_actualizacion_robusta_y_ficha.patch"
```

(`git am --abort` deja todo como estaba si algo sale mal; lo guardado queda en
`git stash list`.)

---

## 3. Compilar y probar en la PC

```powershell
.\gradlew :app:testDebugUnitTest
.\gradlew :app:assembleRelease
python tools/check_command_sync.py
python tools/check_panel_commands.py
python tools/check_whitelist_sync.py
python tools/check_profile_sync.py
python tools/gen_catalog_js.py --check
python tools/gen_policies_js.py --check
```

En la sesión los seis chequeos de `tools/` dieron verde, y las pruebas nuevas
(`UpdateRoutePlannerTest`, `UpdateStepLogTest`) también. El Kotlin se chequeó **con
`kotlinc` 2.0.21 y stubs, no con Gradle**: los 76 archivos que no son de Compose enteros,
0 errores. El proyecto compila con **Kotlin 2.3.20**: si Gradle marca algo que 2.0.21 no
marcaba, el lugar va a estar en los archivos de la lista y el arreglo no tiene que cambiar
comportamiento.

⚠️ **Lo que ese chequeo NO cubre son los archivos de Compose** (`LoginActivity`,
`AppUpdateActivity`, …). De ahí salió el único error encontrado tarde: haber cambiado el
orden de los parámetros de `SelfUpdater.downloadAndInstallApk` rompía la lambda final con
la que la Tienda del celular (`LoginActivity`) pasa el progreso. Ya está corregido
(`onProgress` volvió a ser el último parámetro, con un aviso en el KDoc) y se probó con un
archivo que reproduce esas llamadas con la misma forma. Si Gradle se queja en
`LoginActivity.kt` línea ~933 o en `start(` de `LoginActivity`/`AppUpdateActivity`, es
por ahí: **no cambiar esas llamadas**, la firma tiene que aceptarlas como están.

**Opcional, si hay `node` y `playwright`:** `bash tools/panel_render/run.sh` corre el panel
con Firebase falso (barra 20/20 y ficha 41/41 en la sesión).

---

## 4. Desplegar

1. **Antes de desplegar**, si la versión NO va a ser 0.6.68 / código **131**, abrir
   `admin-backend/public/celular.js`, buscar `const VERSION_CON_B97 = 131;` y poner el
   versionCode que salga. Es solo para AVISAR en la ficha que un celular más viejo
   actualiza como antes.
2. ```powershell
   .\deploy_all.ps1 -VersionName "0.6.68"
   ```
   Sube a **0.6.68 / 131** y publica hosting + database + functions. Las reglas
   (`database.rules.json`) **no cambiaron**.
3. **`functions` es necesario esta vez:** el camino que se elige en la ficha («Solo Google
   Play» / «Solo Tienda») viaja como `route` dentro del comando firmado, y lo firma la
   Function. Con la Function vieja el campo se ignora y todo va en automático, **sin
   avisar**. Si el despliegue de functions falla, el resto sirve igual, pero hay que
   reintentarlo.
4. Abrir el panel con **Ctrl+F5** (cache-busters: `celular.js?v=6`, `panel2.css?v=3`).

Los celulares con LockSuite anterior a 0.6.68 siguen actualizando como siempre (solo por
Google Play): el comando automático es idéntico al de antes, y un `route` que no conocen
lo ignoran (la firma lo cubre igual de los dos lados).

---

## 5. Pruebas en el celular, en este orden

En cada una, mirar en la ficha del celular → **Actualizaciones**: el recuadro en vivo
(camino, porqué, etapa, barra), el **paso a paso** y, al terminar, el **último resultado**
con su motivo. Todo lo que el celular decide queda escrito ahí. En la PC:
`adb logcat -s LockSuite_Update LockSuite_Session SelfUpdater`.

1. **★ Celular BLOQUEADO** (lo más importante). Con la pantalla de bloqueo puesta, mandar a
   actualizar desde el panel una app que vino de Google Play y tenga actualización.
   - Esperado: **la pantalla de bloqueo queda libre** (antes la tapaba la pantalla negra y
     no se podía desbloquear); aparece una notificación «Desbloqueá el celular…»; el paso a
     paso dice "El celular está bloqueado: espero…"; al desbloquear, se abre Google Play,
     se tapa, aprieta Actualizar y termina "Actualizada a la versión …".
2. **Una app con video en la ficha** (YouTube, por ejemplo) con actualización pendiente.
   - Esperado: aprieta «Actualizar» (paso "Apreté «Actualizar»…"), no se queda en
     "Descargando…" sin haber apretado nada.
3. **Una app instalada DESDE la Tienda de LockSuite**, con una copia más nueva cargada en la
   Tienda (con sha256).
   - Esperado: camino «Tienda de LockSuite (en silencio)»; **no** aparece pantalla negra ni
     se abre Google Play; notificación con el progreso; termina "Actualizada a la versión
     …" con la versión de → a. El usuario puede seguir usando el teléfono mientras tanto.
4. **La misma app, con una copia en la Tienda que NO es más nueva.**
   - Esperado: paso "La copia de la Tienda … no se instala: es la misma versión…" y sigue
     sola por Google Play. Un segundo pedido ya no baja la copia (la recuerda por su
     huella).
5. **Play Store configurada para bajar solo por Wi-Fi**, con el celular en datos móviles.
   - Esperado: a los ~10 s de mostrar "Esperando Wi-Fi", cierra con ese motivo (antes:
     10 min de "Descargando…").
6. **Desde la ficha, «Solo Google Play» y «Solo Tienda»** con una app de cada tipo: hace lo
   que dice, y si no se puede, lo explica (por ejemplo "la app no está cargada en la
   Tienda").
7. **Un celular con LockSuite viejo** (anterior a 0.6.68): la ficha avisa que actualiza solo
   por Google Play; actualizar funciona como siempre.
8. **Regresiones:** la Tienda del celular (bajar una app nueva), la autoactualización de
   LockSuite, el botón «Actualizar» de las apps en el celular, y Cancelar desde el panel
   (en la ruta de la Tienda, si ya se está instalando, dice que no se puede frenar y la
   deja terminar).
9. **La ficha nueva en un teléfono** (el panel desde un celular): las diez secciones, que
   nada se salga de costado, y las acciones de «Apps del celular» (ocultar, suspender,
   internet, imágenes) con su ⏳ hasta que el celular confirma.

---

## 6. Si algo falla

- **El paso a paso de la ficha es lo primero que hay que mirar**: dice qué camino eligió y
  por qué, qué vio, qué apretó y qué contestó el sistema. Copiarlo entero en el reporte.
- "**No pude apretar «…»**" repetido → Play Store cambió algo en ese equipo; mandar también
  la línea «Botones que ve en Google Play» del recuadro en vivo (es `updateFlow.debugLabels`,
  también en Diagnóstico → JSON completo).
- "**Google Play dejó la descarga en espera**" → Play tiene otra descarga en curso, o está
  pausada/sin Wi-Fi: no es un error de LockSuite, el motivo lo dice.
- "**La copia de la Tienda está firmada por otro desarrollador**" → la app instalada vino
  de otro lado que la copia de la Tienda (ver B.66): por la Tienda no se va a poder; por
  Google Play solo si la instalada es la de Google Play.
- "**LockSuite se reinició mientras bajaba la copia de la Tienda**" → el proceso murió a
  mitad (memoria, un cierre); el pedido se cerró solo al volver. Pedirlo de nuevo.

---

## 7. Lo que NO se hizo, y por qué

- **No hay "siempre" por Google Play:** Android no tiene API para que un MDM le pida a Google
  Play "actualizá X". La única vía oficial es Managed Google Play (Android Enterprise con
  un EMM registrado): cambio de plataforma, decisión de producto. Ver B.97.
- **Waze (B.66):** sigue siendo decisión del dueño qué copia va en la Tienda.
- **No se commiteó ni se compiló desde la sesión:** el shell del puente no pudo montar la
  carpeta del proyecto ("failed to mount"), así que no hubo `git` ni Gradle; solo se
  pudieron leer y escribir archivos.
