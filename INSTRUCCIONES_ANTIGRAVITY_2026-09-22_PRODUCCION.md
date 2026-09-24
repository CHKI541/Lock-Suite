# INSTRUCCIONES PARA ANTIGRAVITY — 22/9/2026
## Revisión completa para producción con varios usuarios (B.73 a B.86)

**Empezá por acá.** El detalle de cada arreglo, con el porqué, está en
`LOCKSUITE_CONTEXTO_PARA_IA.md`, puntos **B.73 a B.86**. Esto es solo lo operativo.

> ## 🔴 LO PRIMERO, EN UNA LÍNEA
> Todo está **escrito en el disco pero sin commitear** (la sesión de IA no tiene `git`
> sobre el disco: `device_bash` no montó). **Ya compila y pasa las pruebas** (Gradle
> real en el contenedor: `compileDebugKotlin`, 13/13 pruebas unitarias, lint sin
> errores nuevos; reglas 44/44 contra el emulador). Falta: **commitear (§0) → respaldar
> las reglas publicadas (§2) → compilar y desplegar (§3) → probar en equipo (§5).**
> **Nada de esto se probó todavía en un celular real.**

---

## 0. EL COMMIT (antes de `deploy_all.ps1`, que hace `git add .` de todo)

Un solo commit con todo lo de hoy. Desde la raíz del repo, en PowerShell:

```powershell
git add admin-backend/database.rules.json admin-backend/functions/index.js `
        admin-backend/public/app.js admin-backend/public/comun.js admin-backend/public/celular.js `
        admin-backend/public/index.html admin-backend/public/celular.html admin-backend/public/dominios.html `
        app/src/main/java/com/ejemplo/locksuite/LockSuiteApplication.kt `
        app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt `
        app/src/main/java/com/ejemplo/locksuite/receiver/PackageReceiver.kt `
        app/src/main/java/com/ejemplo/locksuite/receiver/UninstallReceiver.kt `
        app/src/main/java/com/ejemplo/locksuite/service/CommandProcessor.kt `
        app/src/main/java/com/ejemplo/locksuite/service/KosherVpnService.kt `
        app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt `
        app/src/main/java/com/ejemplo/locksuite/service/LockSuiteFirebaseService.kt `
        app/src/main/java/com/ejemplo/locksuite/service/WatchdogForegroundService.kt `
        app/src/main/java/com/ejemplo/locksuite/util/CommandMailbox.kt `
        app/src/main/java/com/ejemplo/locksuite/util/DnsPacketParser.kt `
        app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt `
        app/src/main/java/com/ejemplo/locksuite/util/SelfUpdater.kt `
        app/src/main/java/com/ejemplo/locksuite/util/UpdateFlowManager.kt `
        app/src/main/java/com/ejemplo/locksuite/worker/WatchdogWorker.kt `
        app/src/test/java/com/ejemplo/locksuite/util/CommandMailboxTest.kt `
        app/src/test/java/com/ejemplo/locksuite/util/DnsPacketParserTest.kt `
        tools/check_command_sync.py tools/check_panel_commands.py `
        tools/rules_tests tools/ia_contenedor `
        LOCKSUITE_CONTEXTO_PARA_IA.md INSTRUCCIONES_ANTIGRAVITY_2026-09-22_PRODUCCION.md
git status      # confirmá que no quedó nada de hoy afuera y que no entró nada ajeno
git commit -F "Claude outputs/2026-09-22_mensaje_commit.txt"
```

El mensaje está en `Claude outputs/2026-09-22_mensaje_commit.txt` (el mismo del commit
que quedó hecho en el clon de la sesión). **Alternativa:** el commit entero como parche,
`Claude outputs/2026-09-22_0001_produccion_revision_completa.patch`, para `git am`. Pero
el contenido ya está en el disco, así que antes habría que descartar esos cambios del
árbol de trabajo. **Elegí una de las dos vías, no las dos.** Con `git add` + `git commit`
no hace falta descartar nada.

El push lo hacés vos: desde la nube el repo se puede clonar pero no escribir (403).

---

## 1. ANTES DE COMPILAR: LAS VERIFICACIONES

```powershell
python tools/check_whitelist_sync.py
python tools/check_profile_sync.py
python tools/check_command_sync.py
python tools/check_panel_commands.py
python tools/gen_catalog_js.py --check
python tools/gen_policies_js.py --check
.\gradlew.bat :app:testDebugUnitTest
cd tools/rules_tests; npm install; npm test; cd ../..
```

- Los seis de `tools/` tienen que dar verde. `check_command_sync` y `check_panel_commands`
  ahora leen `CommandProcessor.kt` (ahí se mudó el `when` de comandos): si alguno dice
  que el celular "no sabe ejecutar" 148 comandos, estás corriendo una versión vieja del
  script.
- `npm test` tiene que terminar con **`TODAS VERDES (44)`**. Necesita Java (el emulador de
  Firebase es un `.jar`). Si no hay Java en la PC, no bloquea: ya se corrió en la nube.
  Anotalo y seguí.

---

## 2. RESPALDO DE LAS REGLAS PUBLICADAS (2 minutos, no te lo saltees)

Antes de desplegar reglas nuevas, guardá las que están publicadas hoy: Firebase Console →
Realtime Database → **Reglas** → copiar todo a `scratch/reglas_publicadas_2026-09-22.json`.

Compará ese archivo con las reglas del commit anterior:

```powershell
git show fd76ccc:admin-backend/database.rules.json > scratch/reglas_repo_fd76ccc.json
```

**Si difieren, pará y avisale al dueño:** quiere decir que alguien las editó a mano en la
consola, y el diagnóstico de B.73 ("el celular no puede leer su `appPolicy`") está hecho
sobre las del repo. Si son iguales, seguí.

Ese respaldo es también el **plan B** (§6): si algo sale mal con las reglas nuevas, se
vuelve a él en un minuto.

---

## 3. COMPILAR Y DESPLEGAR

```powershell
.\deploy_all.ps1 -VersionName "0.6.55"
```

(Sube a código 118, compila, copia el APK, despliega `hosting,database` y después
`functions`, y hace `git add .` + commit + push. Por eso el commit de §0 va antes.)

### ¿Importa el orden entre las reglas nuevas y el APK nuevo?

Se analizó y **no hace falta separarlos**, por esto:

- Con las reglas viejas, cada equipo reescribe `devices/<id>/ownerUid` con su uid en cada
  latido. O sea que **todo equipo activo ya tiene su `ownerUid` correcto**, y las reglas
  nuevas no lo afectan.
- Solo queda afuera un equipo cuyo uid cambie DESPUÉS de desplegar (reinstalar LockSuite,
  borrarle los datos) o uno que hoy tenga un `ownerUid` ajeno. Con el APK 0.6.55 ese
  equipo pide re-vinculación sola; con el viejo, no, pero igual se actualiza por OTA
  (`version.json` es público y no depende de la base) y ahí la pide.
- La Function nueva es compatible con el APK viejo (el viejo ignora el buzón), y el panel
  nuevo es compatible con las reglas viejas (si no puede leer `deviceClaims`, no muestra
  el cartel y sigue).

Si `firebase deploy --only functions` falla (el 409 de siempre), el resto queda
publicado igual; reintentá solo las funciones:
`cd admin-backend; firebase deploy --only functions`.

---

## 4. EN LA HORA SIGUIENTE AL DESPLIEGUE

1. **Panel → la lista de equipos:** tienen que seguir "en línea" como antes. Si varios
   pasan a "desconectado" y no vuelven en 15-20 min, es el caso de §6.
2. **Cartel "Este celular pide re-vincularse"** (arriba del panel): aprobá solo los que
   reconozcas por modelo y versión. Cada pedido muestra el uid nuevo; **Descartar** lo
   borra sin cambiar nada.
3. Mandá un comando inofensivo a un equipo (por ejemplo **Sincronizar apps y dominios**) y
   confirmá el tilde verde.

---

## 5. PRUEBAS EN EQUIPO REAL (en este orden)

Con un celular de prueba, no con el de un usuario. `adb logcat` con los tags indicados.

| # | Qué | Cómo | Qué tiene que pasar | Punto |
|---|---|---|---|---|
| 1 | Ficha de apps por equipo | Panel → ficha del celular → **Apps** → prohibir una app SOLO en este equipo → aplicar | Se bloquea en ese celular y en ningún otro. **Antes no se aplicaba nunca.** | B.73 / B.63 |
| 2 | Comando con el equipo sin conexión | Modo avión → mandar **bloquear Wi-Fi** → sacar el modo avión | El panel dice "quedó en cola"; al volver se aplica (al instante o ≤ 15 min) | B.74 |
| 3 | Dos comandos opuestos sin conexión | Modo avión → bloquear y después desbloquear algo → volver | Queda el ÚLTIMO | B.74 |
| 4 | Tienda con instalación bloqueada | Bloquear instalación → instalar una app DESDE LA TIENDA | Queda instalada (antes, al arreglar B.75 sin esto, se desinstalaba sola) | B.75 |
| 5 | Re-suspensión al actualizar | Una app suspendida (o Play Store) se actualiza | Vuelve a quedar suspendida sola. `adb logcat -s PackageReceiver` muestra "Acción de paquete recibida" | B.75 |
| 6 | Re-vinculación | Borrar los datos de LockSuite (o reinstalarla) → esperar unos minutos | Aparece el cartel en el panel → Aprobar → el equipo vuelve a "en línea" y recibe comandos | B.73 |
| 7 | Actualizar una app | `UPDATE_APP` de una app con actualización pendiente | Termina como antes (pantalla negra → actualizada → se va sola) | B.81 |
| 8 | Reinicio en medio de una actualización | Igual que 7, y reiniciar con la pantalla negra puesta | A más tardar ~45 min después: Play Store suspendida otra vez y la instalación bloqueada | B.81 |
| 9 | Arranque del túnel | `adb logcat -s KosherVPN` al reiniciar el servicio | Sin reestablecimiento inmediato del túnel al arrancar | B.79 |
| 10 | Internet en uso normal | Un día de uso | Sin cortes; «Auto-reparaciones» en la ficha no sube sin motivo | B.79 |
| 11 | Portal cautivo largo | Wi-Fi con portal (avión, hotel), desplazar la página un rato | No se cierra mientras se desplaza | B.84 |
| 12 | Panel con nombres raros | Instalar una app cuyo nombre tenga `&`, comillas o `<` | La lista de apps del panel la muestra como texto, sin romperse | B.82 |

**Si algo falla, anotá el número de prueba y el logcat** (`scratch/diag_*.txt`, como
siempre) antes de tocar nada: la próxima sesión resuelve en minutos con eso.

---

## 6. SI ALGO SALE MAL (vuelta atrás)

- **Reglas:** Firebase Console → Reglas → pegar el respaldo de §2 → Publicar. La app
  0.6.55 funciona con las reglas viejas: sin re-vinculación, sin buzón (lo lee y le
  niegan) y sin ficha por equipo, igual que hasta hoy.
- **Function:** `git checkout fd76ccc -- admin-backend/functions/index.js`, desplegar
  solo `functions`, y volver a dejar el archivo como estaba con `git checkout HEAD --`.
- **App:** por OTA no se puede bajar de versión (el código tiene que subir). Un arreglo
  urgente sale como 0.6.56 con `deploy_all.ps1`.

---

## 7. DECISIONES QUE SON DEL DUEÑO (no las tomes vos)

1. **Varios administradores (B.85.1):** hoy cualquier cuenta autorizada ve y controla
   TODOS los equipos. Antes de dar de alta a un segundo administrador de otra familia o
   cliente, hay que agregar dueño por equipo en reglas y Function.
2. **Repo público (B.2)** y **clave de presets pública (B.5):** siguen abiertos.
3. **`UPDATE_*` sin PIN (B.7):** sigue abierto; pesa más con varios administradores.
4. **Vencimiento de `UPDATE_APP` en el buzón (B.81):** 24 h. Si se prefiere menos
   (por ejemplo 6 h), es una constante en `functions/index.js`.
5. **Recomendadas para producción (B.85):** Crashlytics, integración continua con los
   chequeos, despliegue escalonado (1-2 equipos primero), respaldos diarios de la base.
