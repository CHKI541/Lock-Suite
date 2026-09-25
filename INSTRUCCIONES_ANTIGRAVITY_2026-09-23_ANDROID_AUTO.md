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

**Qué esperar, según lo revisado el 23/9 (B.87):** Android Auto **con cable** funciona con VPN
(va por USB, no usa la red). El que falla es el **inalámbrico**: usa el Bluetooth para
encontrar la Wi-Fi del auto y después todo pasa por esa Wi-Fi, y la VPN de LockSuite no lo
dejaba usarla. En la captura del dueño se ve el ícono de Bluetooth: lo más probable es que sea
inalámbrico. **La prueba que importa es la 3.**

### Prueba 0: que no se rompió nada (va primero)

Con la 0.6.55 instalada: hay internet, los dominios resuelven, un dominio bloqueado sigue
fallando al instante, y `check_tun.ps1` (el de las instrucciones del 6/9) da VERDE.

### Prueba 1: el celular está listo (ADB, un minuto, sin auto)

Guardá esto como `check_android_auto.ps1` y correlo con el celular conectado a la PC. Revisa
cuatro cosas, en este orden: que Android Auto **no comparta UID** con Play Services (si la
compartiera, excluirla excluiría también Play Services y reabriría B.43), que la VPN esté
**sin lockdown** (con lockdown, Android deja sin red a las apps excluidas), que el
**Bluetooth** no esté bloqueado (sin él, el inalámbrico no puede andar), y que Android Auto
haya quedado **fuera** de los rangos de la VPN.

```powershell
$pkg = "com.google.android.projection.gearhead"
$todos = adb shell pm list packages -U
$linea = $todos | Select-String ('package:' + $pkg + ' uid:') | Select-Object -First 1
if (-not $linea) { Write-Host "Android Auto no esta instalada en este equipo" -Foreground Yellow; return }
$uid = [int]([regex]::Match($linea.Line, 'uid:(\d+)').Groups[1].Value)
# 1) Android Auto NO puede compartir UID con Play Services: excluirla excluiria tambien GMS (B.43)
$gms = $todos | Select-String 'package:com.google.android.gms uid:' | Select-Object -First 1
if ($gms -and ([int]([regex]::Match($gms.Line, 'uid:(\d+)').Groups[1].Value) -eq $uid)) {
  Write-Host "ROJO GRAVE: Android Auto comparte UID con Play Services (reabre B.43). Avisar antes de seguir." -Foreground Red; return }
# 2) La VPN tiene que estar SIN lockdown: con lockdown, las apps excluidas se quedan sin red
$lockdown = (adb shell settings get secure always_on_vpn_lockdown | Out-String).Trim()
# 3) El inalambrico necesita Bluetooth
$sinBluetooth = [bool](adb shell dumpsys user | Select-String 'no_bluetooth(\s|$)')
# 4) Android Auto fuera de los rangos de UID de la VPN
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
Write-Host "Android Auto uid=$uid   tabla de la VPN=$tabla   reglas de la VPN=$($reglas.Count)   lockdown=$lockdown"
if ($cubierto) { Write-Host "ROJO: Android Auto sigue DENTRO de la VPN" -Foreground Red }
elseif ($lockdown -eq "1") { Write-Host "ROJO: Android Auto esta fuera de la VPN, pero la VPN esta en LOCKDOWN (ver B.87)" -Foreground Red }
else { Write-Host "VERDE: Android Auto quedo FUERA de la VPN y sin lockdown" -Foreground Green }
if ($sinBluetooth) { Write-Host "AMARILLO: el Bluetooth esta bloqueado en este equipo: el Android Auto inalambrico no puede andar (el de cable si)" -Foreground Yellow }
```

- **VERDE** es lo esperado. En `adb shell ip rule show` los rangos `uidrange` de la VPN
  saltean la UID de LockSuite (el 6/9 era la 10223) y ahora también la de Android Auto. En
  Android 13+ también saltean la misma UID + 10000, que es la de su "sandbox" de publicidad:
  es normal.
- **ROJO "sigue DENTRO":** el celular no tiene la versión nueva (mirá la versión en el panel),
  o el túnel no se rehízo desde que se instaló: reiniciá el celular y volvé a correrlo.
- **ROJO "LOCKDOWN":** no debería pasar nunca (LockSuite lo pone en `false` y
  `DISALLOW_CONFIG_VPN` impide cambiarlo). Si aparece, **pará y avisá**: es lo que en los
  reportes hacía fallar la exclusión.
- **ROJO GRAVE "comparte UID":** no debería pasar (Android no deja cambiar eso en una
  actualización). Si aparece, **no desplegar** y avisar.
- **AMARILLO Bluetooth:** alguien prendió "Bloquear Bluetooth" para ese equipo en el panel.
  Con eso el inalámbrico no puede funcionar, con o sin este arreglo. Es decisión del dueño.
- **Amarillo "No hay reglas apuntando a la tabla":** es el bug de B.49 (reglas colgadas de un
  `tun0` viejo). No dice nada de Android Auto: primero `check_tun.ps1` y la auto-reparación.

La lógica del script (las mismas expresiones regulares y decisiones, en el mismo orden) se
probó en el contenedor contra el formato real de `ip rule` de tu Galaxy A06 (el del 6/9) y
variantes: CRLF, tabla por nombre, tabla de número parecido, VPN rota o apagada, lockdown en
`1`/`0`/`null`, Bluetooth bloqueado, solo "compartir por Bluetooth" bloqueado, y UID
compartida: 12 de 12. Lo que no se pudo correr es el PowerShell en sí (no hay `pwsh` en el
contenedor): si alguna línea da error de sintaxis, avisá y se corrige.

### Prueba 2: en el auto, inalámbrico — LA IMPORTANTE

1. LockSuite 0.6.55 activa (la llave de VPN en la barra de estado, como en la captura del
   dueño), Bluetooth y Wi-Fi prendidos.
2. Subir al auto y dejar que conecte solo, sin cable.
3. **Tiene que arrancar Android Auto en la pantalla del auto, sin la pantalla roja del "error 21".**
4. Con Android Auto andando, en el celular abrir un sitio bloqueado: **tiene que seguir
   bloqueado**. El filtro sigue funcionando para todo lo demás, incluidas las apps que se ven
   en el auto (Maps, Waze, Spotify, WhatsApp).

### Prueba 3: en el auto, con cable

Lo mismo con cable. Según los reportes ya andaba con VPN, así que es un control: tiene que
seguir andando igual.

### Si sigue fallando

1. **Correr `check_android_auto.ps1`.** Si da ROJO o AMARILLO, lo que dice es la causa.
2. **Si da VERDE, la VPN de LockSuite ya no está en el camino de Android Auto**, aunque el
   mensaje de error la siga nombrando: el "error 21" es una falla genérica de comunicación con
   el auto, y la frase de la VPN es solo la sospecha que agrega Android Auto. Buscá otra causa:
   - **con cable:** el cable o el puerto (es la causa más común del error 21 con cable). Probá
     otro cable, corto y de datos.
   - **inalámbrico:** Wi-Fi o Bluetooth apagados en el celular, o el auto necesita volver a
     emparejarse (olvidar el auto en el Bluetooth del celular y emparejar de nuevo).
3. **Guardá la evidencia antes de tocar nada más.** El logcat queda en memoria del celular un
   rato, así que conectá el celular a la PC en los 5 minutos siguientes al intento:
   ```powershell
   adb logcat -b all -d > scratch/diag_android_auto_2026-09-XX.txt
   adb shell ip rule show > scratch/diag_android_auto_iprule_2026-09-XX.txt
   adb shell dumpsys connectivity > scratch/diag_android_auto_conn_2026-09-XX.txt
   ```
   y anotá: con cable o inalámbrico, modelo del auto, y si el mensaje nombraba la VPN.
4. **Probá con la depuración por USB apagada** (Ajustes → Opciones para desarrolladores →
   Depuración por USB). Es lo otro que pide la ayuda de Android Auto (la primera captura del
   dueño) y no depende de LockSuite. En los equipos dados de alta con cualquiera de los tres
   perfiles ya está apagada (`DISALLOW_DEBUGGING_FEATURES` es parte de la base); en el
   celular de pruebas del dueño probablemente no, porque se usa ADB.
5. Con eso, la próxima sesión decide. **No lo resuelvas agregando Google Play Services a la
   exclusión:** ver §3.

---

## 3. LO QUE NO HAY QUE HACER (está escrito también en el código)

- **No agregar `com.google.android.gms` a `ANDROID_AUTO_PACKAGES`**, aunque un foro lo
  recomiende. En ese proceso corre "Gestionar tu cuenta de Google", con el historial de
  YouTube y Mi Actividad: excluirlo reabre **B.43** entero.
- **No agregar `allowBypass()` al túnel:** cualquier app podría salir del filtro atándose a la Wi-Fi.
- **No pasar a `lockdown=true`:** ver B.4. Rompe el internet general con esta VPN de solo DNS, y
  además vuelve a romper Android Auto: con lockdown, Android deja sin red a las apps excluidas
  (B.87).

---

## 4. SI HAY QUE VOLVER ATRÁS

El cambio está aislado en un solo bucle de `buildTunnel()` y una constante. `git revert` del
commit del 23/9 y publicar como 0.6.56 con `deploy_all.ps1`. No hay nada del panel ni de la
base que revertir.
