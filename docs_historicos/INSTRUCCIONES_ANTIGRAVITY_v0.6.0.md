> **ARCHIVADO (16/8/2026) — ver `LOCKSUITE_CONTEXTO_PARA_IA.md`.** Este documento quedó consolidado ahí; se conserva acá como referencia histórica.

# Instrucciones para Antigravity — Subir LockSuite a v0.6.0

Este documento lo escribió Claude tras una sesión trabajando sobre `C:\Users\israe\OneDrive\Documentos\Lock Suite segunda version` vía el puente al dispositivo. Ese entorno **no tiene Android SDK, Gradle ni acceso a Firebase**, así que nada de lo de abajo se compiló ni se probó en un equipo real todavía — esa parte queda para vos.

## 1. Qué se cambió (7 archivos)

| Archivo | Qué se tocó |
|---|---|
| `admin-backend/functions/index.js` | `UPDATE_APP` ya no exige el PIN del dispositivo (ver §2). |
| `app/src/main/java/com/ejemplo/locksuite/dns/DomainRuleTrie.kt` | `RuleType` pasa de 2 a 4 valores (`BLOCK`, `ALLOW`, `FORCE_BLOCK`, `FORCE_ALLOW`). |
| `app/src/main/java/com/ejemplo/locksuite/dns/DomainRuleManager.kt` | Reescrito para los 4 tipos de regla; ahora arranca la VPN al fijar una regla. |
| `app/src/main/java/com/ejemplo/locksuite/receiver/BootReceiver.kt` | `shouldVpnBeRunning()` ahora también chequea si hay reglas DNS personalizadas. |
| `app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt` | Dos lugares que apagaban la VPN "para ahorrar batería" ahora respetan reglas DNS activas. |
| `app/src/main/java/com/ejemplo/locksuite/service/KosherVpnService.kt` | Lógica de prioridad forzar/normal en `handleDnsQuery()`. |
| `app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt` | Sección DNS del dashboard: lista persistente de reglas + alta manual + botones de forzar. |

Revisá el diff de estos 7 (`git diff -- <archivo>`) antes de compilar — es la primera vez que este código pasa por un compilador real.

## 2. Actualizar apps sin pedir el PIN del celular

`sendCommandV8` en `index.js` exigía el PIN del dispositivo para **todos** los comandos salvo `UPDATE_LOCKSUITE`. Se agregó `UPDATE_APP` a esa excepción (una línea: `if (command !== "UPDATE_LOCKSUITE" && command !== "UPDATE_APP")`). Del lado del celular no cambió nada — `UPDATE_APP` sigue haciendo lo mismo de siempre (suspende momentáneamente la Play Store, abre la ficha de la app vía `market://`, y hay una alarma de seguridad a los 10 min).

**Nota de seguridad, para que la tengas presente:** esto significa que cualquier admin de la lista `authorizedAdmins` en Firebase puede mandar "Actualizar" a cualquier dispositivo sin probar que conoce su PIN — antes esa acción pedía el PIN igual que bloquear el equipo entero. Si sos el único admin no cambia nada en la práctica; si en algún momento sumás más gente a `authorizedAdmins`, tenelo en cuenta.

## 3. DNS: el bug real y la prioridad forzar/normal

**La causa de "no funciona":** la VPN (Capa 2) solo se mantenía corriendo si había WebView bloqueado, AdBlocker, GIFs bloqueados, bloqueo de Internet por app, etc. — nunca se chequeaba si había reglas DNS personalizadas cargadas. Si lo único que tenías activo era una regla de dominio, la VPN podía no estar corriendo (o se apagaba sola al desactivar otra cosa), y la regla quedaba guardada en el teléfono sin aplicarse nunca. Se corrigió en tres puntos: `BootReceiver.shouldVpnBeRunning()`, los dos "apagar VPN para ahorrar batería" de `PolicyManager.kt`, y `DomainRuleManager.setRule()` ahora llama a `ensureVpnRunning()` apenas guardás una regla.

**La causa de "no aparecen los bloqueados / no puedo desbloquear después de una hora":** la sección DNS del dashboard solo mostraba **actividad reciente** (`DnsActivityBuffer`, tope duro de 1 hora), y esa lista de actividad era la ÚNICA forma de tocar una regla para editarla o borrarla. Un dominio que no se volvía a consultar simplemente desaparecía de la vista — la regla seguía viva en `SharedPreferences`, pero no había ningún botón para llegar a ella. Se agregó una sección nueva "Reglas DNS personalizadas", separada de la actividad reciente, que lista TODAS las reglas guardadas sin importar cuándo se consultó el dominio, con su propio botón de "Quitar". También se puede cargar un dominio a mano ahí (sin esperar a que aparezca en la actividad).

**Forzar vs. normal (lo que pediste):** antes cualquier regla de dominio le ganaba a todo automáticamente, sin que eso fuera una opción explícita. Ahora:
- `FORCE_BLOCK` / `FORCE_ALLOW`: le gana a cualquier otra configuración (bloqueo de WebView, AdBlocker, GIFs, Mercado Pago). Se resuelve primero, sin mirar nada más.
- `BLOCK` / `ALLOW` (normal): solo se aplica si ninguna otra política ya decidió algo para ese dominio puntual. No pisa un bloqueo de WebView activo, por ejemplo.

En la fila de "Actividad reciente" los botones rápidos "Bloquear"/"Permitir" siguen mandando a modo forzado (para no cambiarte el comportamiento que ya conocías); el modo normal se elige desde la sección nueva de reglas.

## 4. Cómo compilar y desplegar como v0.6.0

El proyecto ya tiene `deploy_all.ps1` en la raíz, que hace todo el pipeline en un solo paso (sube versionCode/versionName, compila `assembleRelease`, copia el APK a `admin-backend/public/`, despliega a Firebase, commitea y pushea a GitHub). Versión actual: **0.5.5 (versionCode 61)**.

```powershell
cd "C:\Users\israe\OneDrive\Documentos\Lock Suite segunda version"
.\deploy_all.ps1 -VersionName "0.6.0"
```

Esto deja `versionCode = 62` automáticamente (currentCode + 1). Si el versionCode cambió mientras tanto por otra sesión, fijate que 62 siga libre.

**Antes de correrlo:** `git status` va a mostrar, además de los 7 archivos de arriba, otros cambios sin commitear de sesiones anteriores (los informes de auditoría, `_to_delete/`, `BlockAccessibilityActivity.kt`, y `app/build.gradle.kts`/`admin-backend/public/version.json`, que ya estaban modificados antes de que yo empezara). El script hace `git add .`, así que va a commitear TODO eso junto en un solo commit. Si querés separarlo, hacé el commit de esos otros cambios vos antes de correr el script.

**Si algo no compila:** `assembleRelease` corriendo por primera vez sobre este código es, en los hechos, la primera revisión real de sintaxis que recibe — yo verifiqué manualmente balance de llaves/paréntesis y revisé cada diff línea por línea, pero no reemplaza un compilador. Si tira error, decime el mensaje y lo reviso.

### Alternativa: pasos a mano (si no querés usar el script)

1. `app/build.gradle.kts`: `versionCode = 62`, `versionName = "0.6.0"`.
2. `admin-backend/public/version.json`: mismo versionCode/versionName + `updatedAt` con la fecha/hora UTC actual.
3. `./gradlew assembleRelease` (o `gradlew.bat` en Windows).
4. Copiar `app/build/outputs/apk/release/app-release.apk` a `admin-backend/public/locksuite-latest.apk` y a `admin-backend/public/LockSuite_MDM.apk`.
5. `cd admin-backend && firebase deploy --only functions,hosting,database`.
6. `git add`, `git commit`, `git push` a `origin` (`https://github.com/CHKI541/Lock-Suite`).

## 5. Qué probar en un equipo real antes de confiar en la build

- Agregar una regla `BLOCK` (normal, no forzada) para un dominio, sin tener ninguna otra política de VPN activa (WebView/AdBlock/GIFs apagados) → confirmar que la VPN arranca sola y el dominio se corta.
- Con esa regla puesta, esperar más de una hora sin volver a consultar el dominio → confirmar que sigue apareciendo en "Reglas DNS personalizadas" y que "Quitar" funciona.
- Activar bloqueo de WebView para una app + poner una regla `ALLOW` normal para un dominio que esa app tiene bloqueado por WebView → confirmar que sigue bloqueado (la regla normal no debe pisar el bloqueo de WebView). Repetir con `FORCE_ALLOW` → confirmar que ahí sí se permite.
- Desde el panel web, tocar "Actualizar" en una app de un dispositivo que no tenga el PIN "recordado" en el navegador → confirmar que ya no pide el PIN.
