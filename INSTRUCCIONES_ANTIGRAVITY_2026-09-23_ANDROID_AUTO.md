# INSTRUCCIONES PARA ANTIGRAVITY — 23/9/2026
## Android Auto no arranca con LockSuite ("Error de comunicación 21") — B.87

**Empezá por acá.** El porqué completo está en `LOCKSUITE_CONTEXTO_PARA_IA.md`, punto **B.87**.
Esto es solo lo operativo.

> ## 🔴 LO PRIMERO, EN UNA LÍNEA
> Un solo archivo de código cambiado (`KosherVpnService.kt`): Android Auto sale del túnel de la
> VPN, igual que ya salía LockSuite. **Ya compila y pasa las pruebas** (Gradle real en el
> contenedor: `compileDebugKotlin` OK, 13/13 pruebas unitarias, ningún aviso de lint nuevo).
> Está **escrito en el disco pero sin commitear**, encima de lo del 22/9, que **tampoco** está
> commiteado. Orden: **los dos commits (§0) → desplegar siguiendo las instrucciones del 22/9
> (§1) → pruebas de Android Auto (§2).** **Nada de esto se probó en un celular ni en un auto.**
>
> **Y antes de desplegar:** el respaldo de las reglas publicadas que se intentó el 22/9
> (`scratch/reglas_publicadas_2026-09-22.json`) **está vacío**. Ver el recuadro de §1.

---

## 0. LOS DOS COMMITS, SEPARADOS Y SIN DESCARTAR NADA

**No uses el `git add` de §0 de las instrucciones del 22/9.** Lo del 23/9 toca
`KosherVpnService.kt`, `LOCKSUITE_CONTEXTO_PARA_IA.md` y `tools/ia_contenedor/`, que también
están en esa lista: el commit del 22/9 se llevaría lo de hoy adentro.

La salida: armar cada commit **en el índice** con su parche (`git apply --cached` no toca ni un
archivo del disco) y commitearlo con su mensaje. Desde la raíz del repo, en PowerShell:

```powershell
git log -1 --oneline
# Tiene que decir: fd76ccc docs: actualizar estado de despliegue de 0.6.54 ...
# Si dice otra cosa, NO sigas: mirá la tabla de abajo.
git diff --cached --stat
# Tiene que salir VACÍO (nada preparado a medias). Si no, `git reset` lo saca todo del
# índice SIN tocar ningún archivo del disco, y recién ahí seguí.

# 1) Commit del 22/9
git apply --cached "Claude outputs/2026-09-22_0001_produccion_revision_completa.patch"
git commit -F "Claude outputs/2026-09-22_mensaje_commit.txt"

# 2) Commit del 23/9
git apply --cached "Claude outputs/2026-09-23_0001_android_auto.patch"
git commit -F "Claude outputs/2026-09-23_mensaje_commit.txt"

# 3) Control
git status --short
git log --oneline -3
git show HEAD:app/src/main/java/com/ejemplo/locksuite/service/KosherVpnService.kt | Select-String "projection.gearhead"
```

**Qué tiene que dar el control:**

- `git status --short`: **ninguna línea con `M`**. Como mucho `?? firebase-debug.log`, que ya
  estaba (`scratch/` y `Claude outputs/` están en `.gitignore` y no aparecen). Si aparece un
  archivo con `M`, alguien lo cambió en el disco después de las sesiones: **pará y mirá el
  diff** (`git diff <archivo>`) antes de seguir, no lo descartes.
- `git log`: los dos commits nuevos arriba de `fd76ccc`.
- El `Select-String`: tiene que encontrar la línea de `"com.google.android.projection.gearhead"`.

**Probado en el contenedor:** se armó un repo en `fd76ccc` con el disco simulado (22/9 + 23/9,
en CRLF, con `core.autocrlf` prendido y `filemode = false`, igual que tu PC), se corrieron
exactamente estos comandos, y los dos commits quedaron con el **mismo árbol** que los de la
sesión, con `git status` limpio salvo `?? firebase-debug.log`. Los avisos de `trailing
whitespace` del parche del 22/9 son normales (los mismos que con `git am`) y no frenan nada.

**Si `git log -1` no dice `fd76ccc`:**

| Lo que dice | Qué pasó | Qué hacer |
|---|---|---|
| `fix(produccion): reglas de la base, buzon de comandos...` | Ya se commiteó lo del 22/9 con su `git add` | Saltá el paso 1. Si el paso 2 dice `patch does not apply`, lo de hoy ya entró en ese commit: solo falta `git add INSTRUCCIONES_ANTIGRAVITY_2026-09-23_ANDROID_AUTO.md` y `git commit -m "docs: instrucciones de Android Auto (B.87)"` |
| `Actualizacion automatica a version 0.6.55 ...` | Ya se corrió `deploy_all.ps1`, que hace `git add .` | No hay nada que commitear: la 0.6.55 ya lleva Android Auto. Confirmalo con el `Select-String` de arriba y pasá a §2 |
| Otra cosa | — | Pará y avisale al dueño |

El push lo hacés vos (desde la nube el repo se clona pero no se escribe).

---

## 1. DESPLEGAR

**Todo como dicen las instrucciones del 22/9, desde su §1** (`INSTRUCCIONES_ANTIGRAVITY_2026-09-22_PRODUCCION.md`):
verificaciones → respaldo de las reglas publicadas → `.\deploy_all.ps1 -VersionName "0.6.55"`
→ la hora siguiente al despliegue.

> ### ⚠️ EL RESPALDO DE LAS REGLAS DEL 22/9 QUEDÓ VACÍO: REHACELO ANTES DE DESPLEGAR
> `scratch/reglas_publicadas_2026-09-22.json` pesa **0 bytes**. El 22/9 a las 17:55 se intentó
> con `firebase database:get /.settings/rules --project locksuite-nueva > ...` y el comando se
> cortó por errores de red (`premature close`, reintentos; está todo en `firebase-debug.log`,
> en la raíz). El archivo se creó por la redirección pero nunca recibió nada. **Sin ese
> respaldo no hay plan B** para las reglas nuevas (§6 del 22/9).
>
> Rehacelo como dicen las instrucciones (Firebase Console → Realtime Database → Reglas →
> copiar todo) o reintentá el comando, y **confirmá que no quedó vacío** antes de seguir:
> ```powershell
> (Get-Item scratch\reglas_publicadas_2026-09-22.json).Length     # tiene que ser > 0
> Select-String '"rules"' scratch\reglas_publicadas_2026-09-22.json # tiene que encontrar la línea
> ```
> Después, la comparación contra `fd76ccc` de §2 del 22/9, igual que estaba.

Lo de Android Auto **viaja en la misma 0.6.55**: es solo la app, no cambia nada del panel, de
las reglas ni de las Functions. No hay comando FCM nuevo ni cache-buster que subir.

---

## 2. PRUEBAS DE ANDROID AUTO (después de las del 22/9, en este orden)

### Prueba 0: que no se rompió nada (va primero)

Con la 0.6.55 instalada: hay internet, los dominios resuelven, un dominio bloqueado sigue
fallando al instante, y `check_tun.ps1` (el de las instrucciones del 6/9) da VERDE.

### Prueba 1: Android Auto quedó fuera del túnel (ADB, un minuto, sin auto)

Guardá esto como `check_android_auto.ps1` y correlo con el celular conectado a la PC:

```powershell
$pkg = "com.google.android.projection.gearhead"
$linea = adb shell pm list packages -U | Select-String ('package:' + $pkg + ' uid:') | Select-Object -First 1
if (-not $linea) { Write-Host "Android Auto no esta instalada en este equipo" -Foreground Yellow; return }
$uid = [int]([regex]::Match($linea.Line, 'uid:(\d+)').Groups[1].Value)
$tun = adb shell ip addr show tun0 | Select-Object -First 1
if ($tun -match '^\s*(\d+):\s*tun0') { $tabla = 1000 + [int]($Matches[1]) }
else { Write-Host "La VPN no esta activa (no hay tun0): prendela y volve a correr esto" -Foreground Yellow; return }
# Algunas versiones de Android muestran la tabla por nombre ("lookup tun0") y no por numero.
$patron = 'lookup (' + $tabla + '|tun0)(\s|$)'
$reglas = @(adb shell ip rule show | Select-String $patron)
if ($reglas.Count -eq 0) { Write-Host "No hay reglas apuntando a la tabla $tabla : la VPN esta rota, corre check_tun.ps1" -Foreground Yellow; return }
$cubierto = $false
foreach ($r in $reglas) {
  foreach ($m in [regex]::Matches($r.Line, 'uidrange (\d+)-(\d+)')) {
    $desde = [int]($m.Groups[1].Value); $hasta = [int]($m.Groups[2].Value)
    if ($uid -ge $desde -and $uid -le $hasta) { $cubierto = $true }
  }
}
Write-Host "Android Auto uid=$uid   tabla de la VPN=$tabla   reglas de la VPN=$($reglas.Count)"
if ($cubierto) { Write-Host "ROJO: Android Auto sigue DENTRO de la VPN" -Foreground Red }
else { Write-Host "VERDE: Android Auto quedo FUERA de la VPN" -Foreground Green }
```

- **VERDE** es lo esperado. En `adb shell ip rule show` los rangos `uidrange` de la VPN
  saltean la UID de LockSuite (el 6/9 era la 10223) y ahora también la de Android Auto. En
  Android 13+ también saltean la misma UID + 10000, que es la de su "sandbox" de publicidad:
  es normal.
- **ROJO:** el celular no tiene la versión nueva (mirá la versión en el panel), o el túnel no
  se rehízo desde que se instaló: reiniciá el celular y volvé a correrlo.
- **Amarillo "No hay reglas apuntando a la tabla":** es el bug de B.49 (reglas colgadas de un
  `tun0` viejo). No dice nada de Android Auto: primero `check_tun.ps1` y la auto-reparación.

La lógica del script (las mismas expresiones regulares, en el mismo orden) se probó en el
contenedor contra el formato real de `ip rule` de tu Galaxy A06 (el del 6/9), con CRLF, con
tabla por nombre, con una tabla de número parecido y con la VPN rota o apagada: 9 de 9. Lo que
no se pudo correr es el PowerShell en sí (no hay `pwsh` en el contenedor): si alguna línea da
error de sintaxis, avisá y se corrige.

### Prueba 2: en el auto, con cable

1. LockSuite 0.6.55 activa (la llave de VPN en la barra de estado, como en la captura del dueño).
2. Conectar el celular al auto por USB.
3. **Tiene que arrancar Android Auto sin la pantalla roja del "error 21".**
4. Con Android Auto andando, en el celular abrir un sitio bloqueado: **tiene que seguir bloqueado**.
   El filtro sigue funcionando para todo lo demás.

### Prueba 3: en el auto, inalámbrico (si el auto lo tiene)

Lo mismo que la prueba 2, sin cable. Es el caso que más suele fallar con VPNs, y el que más
depende de este arreglo.

### Si sigue el error 21

1. **Correr `check_android_auto.ps1`.** Si da ROJO, el arreglo no llegó (ver arriba).
2. **Si da VERDE y el auto igual da error 21**, guardá la evidencia **antes de tocar nada**.
   El logcat queda en memoria del celular un rato, así que conectá el celular a la PC en los
   5 minutos siguientes al intento:
   ```powershell
   adb logcat -b all -d > scratch/diag_android_auto_2026-09-XX.txt
   adb shell ip rule show > scratch/diag_android_auto_iprule_2026-09-XX.txt
   adb shell dumpsys connectivity > scratch/diag_android_auto_conn_2026-09-XX.txt
   ```
   y anotá: con cable o inalámbrico, modelo del auto, y si la depuración por USB estaba prendida.
3. **Probá con la depuración por USB apagada** (Ajustes → Opciones para desarrolladores →
   Depuración por USB). Es lo otro que pide la ayuda de Android Auto (la primera captura del
   dueño) y no depende de LockSuite. Si LockSuite tiene "Bloquear depuración" prendido para
   ese equipo, ya está apagada.
4. Con eso, la próxima sesión decide. **No lo resuelvas agregando Google Play Services a la
   exclusión:** ver §3.

---

## 3. LO QUE NO HAY QUE HACER (está escrito también en el código)

- **No agregar `com.google.android.gms` a `ANDROID_AUTO_PACKAGES`**, aunque un foro lo
  recomiende. En ese proceso corre "Gestionar tu cuenta de Google", con el historial de
  YouTube y Mi Actividad: excluirlo reabre **B.43** entero.
- **No agregar `allowBypass()` al túnel:** cualquier app podría salir del filtro atándose a la Wi-Fi.
- **No pasar a `lockdown=true`:** ver B.4. Rompe el internet general con esta VPN de solo DNS.

---

## 4. SI HAY QUE VOLVER ATRÁS

El cambio está aislado en un solo bucle de `buildTunnel()` y una constante. `git revert` del
commit del 23/9 y publicar como 0.6.56 con `deploy_all.ps1`. No hay nada del panel ni de la
base que revertir.
