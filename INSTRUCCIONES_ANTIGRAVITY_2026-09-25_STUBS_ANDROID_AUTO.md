# INSTRUCCIONES PARA ANTIGRAVITY — 25/9/2026
## Android Auto: la pantalla "Descargá apps de Google Play" — stubs vacíos (B.88)

**Empezá por acá.** El porqué completo está en `LOCKSUITE_CONTEXTO_PARA_IA.md`, puntos **B.87**
y **B.88**. Esto es solo lo operativo.

> ## 🔴 LO PRIMERO
> - **El error 21 (B.87) está resuelto:** salió en 0.6.55 y el dueño lo confirmó en el auto.
> - **Ahora Android Auto conecta, pero pide "Google Maps" y "App de Google"** y no deja seguir
>   ("Salir" cierra Android Auto). La solución son **dos apps vacías ("stubs")** con esos nombres
>   de paquete, y que LockSuite las reconozca y no las oculte. **La navegación en el auto la hace
>   Waze**, el oficial de Play Store que el dueño ya tiene.
> - **Escrito, compilado y probado en el contenedor** (Gradle real: 18/18 pruebas unitarias, 5
>   nuevas; ningún aviso de lint en las líneas tocadas). Está **en el disco, sin commitear**.
> - **Orden:** los dos commits (§0) → chequeos y 0.6.56 (§1) → los stubs a la Tienda (§2) → el
>   celular del dueño (§3) → el auto (§4). **0.6.56 tiene que estar en el celular ANTES que los
>   stubs.**
> - **Nada de esto se probó en un celular ni en un auto.**

---

## 0. LOS DOS COMMITS, SEPARADOS Y SIN DESCARTAR NADA

En el disco hay trabajo de dos momentos distintos, y los dos tocan `LOCKSUITE_CONTEXTO_PARA_IA.md`:

- **A — la revisión del 23/9 a la noche** (B.87; solo comentarios y documentación). Se escribió
  a las 23:26, después de tu commit y del despliegue de 0.6.55 (21:30 y 21:49), así que quedó
  afuera. Son tres archivos: `KosherVpnService.kt`, el contexto e
  `INSTRUCCIONES_ANTIGRAVITY_2026-09-23_ANDROID_AUTO.md`.
- **B — los stubs de Android Auto** (25/9, B.88).

Un `git add` los mezclaría. Igual que el 23/9: cada commit se arma **en el índice** con su parche
(`git apply --cached` no toca ni un archivo del disco) y se commitea con su mensaje. Desde la
raíz del repo, en PowerShell:

```powershell
git log -1 --oneline
# Tiene que decir: e8f1d01 Actualizacion automatica a version 0.6.55 (Codigo 118)
# Si dice otra cosa, NO sigas: mirá la tabla de abajo.
git diff --cached --stat
# Tiene que salir VACÍO. Si no, `git reset` lo saca todo del índice SIN tocar ningún
# archivo del disco, y recién ahí seguí.

# 1) Commit A: revisión del 23/9
git apply --cached "Claude outputs/2026-09-25_A_revision_android_auto.patch"
git commit -F "Claude outputs/2026-09-25_A_mensaje_commit.txt"

# 2) Commit B: stubs de Android Auto
git apply --cached "Claude outputs/2026-09-25_B_stubs_android_auto.patch"
git commit -F "Claude outputs/2026-09-25_B_mensaje_commit.txt"

# 3) Control
git status --short
git log --oneline -3
python tools/aa_stubs/check_stubs.py
```

**Qué tiene que dar el control:**

- `git status --short`: **ninguna línea con `M`** ni con `??` de archivos del proyecto
  (`scratch/` y `Claude outputs/` están en `.gitignore`). Si aparece un archivo con `M`, alguien
  lo cambió en el disco después de la sesión: **pará y mirá el diff** (`git diff <archivo>`)
  antes de seguir; no lo descartes.
- `git log`: los dos commits nuevos arriba de `e8f1d01`.
- `check_stubs.py`: **VERDE** (los dos APK que quedaron commiteados son byte a byte los que el
  código reconoce, y están vacíos).

**Probado en el contenedor:** se armó un repo en `e8f1d01` con el disco imitado (los archivos
con los mismos finales de línea que en tu disco, `core.autocrlf` prendido y
`filemode = false`, como tu PC), se corrieron exactamente estos comandos, y los dos commits
quedaron con el **mismo árbol** que los de la sesión, con `git status` limpio.

**Si `git log -1` no dice `e8f1d01`:**

| Lo que dice | Qué pasó | Qué hacer |
|---|---|---|
| `docs(vpn): revision de B.87 ...` | El commit A ya está | Saltá el paso 1 |
| `feat(android-auto): stubs vacios ...` | Ya están los dos | Pasá al control y a §1 |
| `Actualizacion automatica a version 0.6.56 ...` | Ya se corrió `deploy_all.ps1`, que hace `git add .` y se llevó todo | Confirmá que entró: `git show --stat HEAD \| Select-String AndroidAutoStubs`. Si aparece, pasá a §2 |
| Otra cosa | — | Pará y avisale al dueño |

---

## 1. CHEQUEOS Y DESPLIEGUE DE 0.6.56

```powershell
python tools/check_whitelist_sync.py
python tools/check_profile_sync.py
python tools/check_command_sync.py
python tools/check_panel_commands.py
python tools/gen_catalog_js.py --check
python tools/gen_policies_js.py --check
python tools/aa_stubs/check_stubs.py
.\gradlew :app:testDebugUnitTest          # 18 pruebas, 0 fallas (5 son de AndroidAutoStubsTest)
.\deploy_all.ps1 -VersionName "0.6.56"
```

**0.6.56 es solo la app Android.** No cambian el panel, ni `functions`, ni las reglas, y no hay
comandos FCM nuevos. `deploy_all.ps1` igual vuelve a publicar todo (idéntico a 0.6.55) y hace el
push.

**Los stubs NO van dentro del APK de LockSuite**: se suben aparte a la Tienda (§2).

---

## 2. SUBIR LOS STUBS A LA TIENDA

Son dos archivos del repo, de 8,5 KB cada uno:

| Archivo | Paquete | SHA-256 |
|---|---|---|
| `tools/aa_stubs/apk/aa-stub-maps.apk` | `com.google.android.apps.maps` | `b0d1fc188e88e5a27b366f1d7160488d1b0ebe393ad78e3c69fda96c6692cbd3` |
| `tools/aa_stubs/apk/aa-stub-google.apk` | `com.google.android.googlequicksearchbox` | `d81901b6c544df6a2e16cc3e8c96cd83677225c5073eefb64ff2331666bb6152` |

**a) Subirlos al release `store-apks-v1`**, donde están las otras 16 apps de la Tienda:

```powershell
gh release upload store-apks-v1 tools\aa_stubs\apk\aa-stub-maps.apk tools\aa_stubs\apk\aa-stub-google.apk --repo CHKI541/Lock-Suite
```

Sin `gh`: GitHub → Releases → `store-apks-v1` → Edit → arrastrar los dos archivos → Update release.

**b) Confirmar que lo que se baja es exactamente eso:**

```powershell
$base = "https://github.com/CHKI541/Lock-Suite/releases/download/store-apks-v1"
Invoke-WebRequest "$base/aa-stub-maps.apk" -OutFile "$env:TEMP\m.apk"
(Get-FileHash "$env:TEMP\m.apk" -Algorithm SHA256).Hash.ToLower() -eq "b0d1fc188e88e5a27b366f1d7160488d1b0ebe393ad78e3c69fda96c6692cbd3"
Invoke-WebRequest "$base/aa-stub-google.apk" -OutFile "$env:TEMP\g.apk"
(Get-FileHash "$env:TEMP\g.apk" -Algorithm SHA256).Hash.ToLower() -eq "d81901b6c544df6a2e16cc3e8c96cd83677225c5073eefb64ff2331666bb6152"
# Las dos líneas tienen que decir True
```

**c) Cargarlos en la Tienda.** Panel → Ajustes → **Tienda de Apps** → "➕ Agregar a la tienda":

| Nombre | Paquete | URL directa del APK |
|---|---|---|
| `Android Auto: requisito Maps (app vacía)` | `com.google.android.apps.maps` | `https://github.com/CHKI541/Lock-Suite/releases/download/store-apks-v1/aa-stub-maps.apk` |
| `Android Auto: requisito Google (app vacía)` | `com.google.android.googlequicksearchbox` | `https://github.com/CHKI541/Lock-Suite/releases/download/store-apks-v1/aa-stub-google.apk` |

El panel va a intentar calcular la huella y, con GitHub, lo más probable es que no pueda (CORS):
en ese caso te la pide. **Pegá la de la tabla de arriba, completa.** También se puede escribir
directo con el Admin SDK, como el 10/9: `storeApps/com_google_android_apps_maps` y
`storeApps/com_google_android_googlequicksearchbox`, con `label`, `packageName`, `apkUrl`,
`sha256` (en minúsculas) y `sha256At`.

Al terminar, las dos tarjetas del panel tienen que decir **"✓ Verificable"**.

> ⚠️ **La huella tiene que ser EXACTAMENTE esa.** Es lo que hace que 0.6.56 deje instalar el stub
> a cualquier equipo sin pedirlo (`AndroidAutoStubs.APK_SHA256`). Con otra huella, la tarjeta
> pide permiso como cualquier app.
>
> ⚠️ **No agregues estos dos paquetes a las apps permitidas de ningún equipo ni grupo, ni a
> "Permitir paquetes para todos los usuarios", y no apruebes un pedido de ellos.** Esa lista es por NOMBRE: permitiría también la Maps y la App de
> Google reales. Si llega un pedido desde un equipo con 0.6.55, la respuesta es actualizarlo a
> 0.6.56, no aprobarlo.

---

## 3. EN EL CELULAR DEL DUEÑO (en este orden)

**Qué hay hoy en ese celular** (ADB, 25/9): la App de Google **no existe**; la Maps real está
instalada como app común (`/data/app`, sin copia de sistema) y **oculta**; Waze viene de fábrica,
con una actualización de Play Store encima.

**1. Confirmar que ya tiene 0.6.56.** En el panel (versión del equipo), o:

```powershell
adb shell dumpsys package com.ejemplo.locksuite | findstr versionName     # versionName=0.6.56
```

Con 0.6.55 la Tienda no deja instalar los stubs sin pedirlos, así que el orden se respeta solo.
Pero **no los instales por ADB antes de 0.6.56**: 0.6.55 no los conoce y los oculta, suspende o
desinstala. Si igual pasó, no es grave: con 0.6.56 se reparan solos en la reaplicación siguiente
(hasta 15 minutos), salvo que se hayan desinstalado, que hay que volver a instalarlos.

**2. Desinstalar la Maps real.** El stub no se puede instalar encima: la firma es otra. Como es
una app común, se va entera.

- En el celular: LockSuite (PIN) → **Políticas** → apagar **"Bloquear Desinstalación de Apps"**.
- LockSuite → **Aplicaciones** → buscar **Maps** (`com.google.android.apps.maps`) → el tacho →
  Desinstalar.
- Volver a prender **"Bloquear Desinstalación de Apps"** enseguida.

Por ADB es lo mismo, también con ese interruptor apagado:
`adb uninstall com.google.android.apps.maps` → `Success`. Si dice
`DELETE_FAILED_USER_RESTRICTED`, el interruptor sigue prendido.

**3. Instalar los dos stubs desde la Tienda de LockSuite.** Las dos tarjetas nuevas tienen que
tener el botón de instalar habilitado, sin pedir nada.

- Si una tarjeta dice **"⚠ Está instalada la app real — primero desinstalala"**, el paso 2 no se
  hizo.
- Si dice **"⚠ En este equipo la app real viene de fábrica"**, en ese celular el stub no se puede
  instalar. En el del dueño no debería pasar: pará y avisale.
- **Play Protect** puede avisar, porque es un paquete con nombre de Google firmado por otro. Si
  ofrece "Instalar de todas formas", aceptalo. Si lo bloquea sin opción, sacá una captura y
  pará: no lo esquives de otra forma sin decidirlo con el dueño.

**4. Verificar (ADB, un minuto):**

```powershell
foreach ($p in "com.google.android.apps.maps","com.google.android.googlequicksearchbox") {
  adb shell dumpsys package $p | findstr /C:"versionCode" /C:"User 0:"
}
```

Esperado, en los dos:

- `versionCode=2100000000`: es el stub, no la app real.
- En la línea `User 0:`: `installed=true hidden=false suspended=false`.

**Repetilo pasados 15 minutos** (una vuelta de la reaplicación de políticas): tiene que seguir
igual. Si aparece `hidden=true` o `suspended=true`, LockSuite no reconoce el stub. Mirá primero
la versión (tiene que ser 0.6.56), y después la huella del APK instalado:

```powershell
adb shell pm path com.google.android.apps.maps          # package:/data/app/.../base.apk
adb pull <esa ruta> "$env:TEMP\instalado.apk"
(Get-FileHash "$env:TEMP\instalado.apk" -Algorithm SHA256).Hash.ToLower()   # b0d1fc18...
```

---

## 4. EN EL AUTO

1. **Conectar**, inalámbrico o con cable. La pantalla de "Descargá apps de Google Play" ya no
   tiene que aparecer, o tiene que mostrar las dos como instaladas y dejar seguir.
2. En la pantalla del auto **aparece Waze**. Maps y Google **no aparecen**: los stubs no tienen
   nada que mostrar.
3. Si Android Auto pregunta qué app de navegación usar, o intenta abrir Maps, **elegir Waze**.
   En el celular también se configura en los ajustes de Android Auto.
4. **Una navegación de prueba con Waze** en la pantalla del auto.
5. **Qué NO va a andar, y está bien:** el botón del asistente de voz de Google, porque la App de
   Google no está. Es lo esperado en un equipo kosher.

**Regresión, después:** internet y filtro iguales que antes; Play Store sigue bloqueada; en el
panel, la ficha del equipo muestra los dos stubs sin "oculta" ni "suspendida".

### Si sigue pidiendo "Descargar"

1. Correr la verificación del paso 4 de §3. Si un stub está oculto o no está, eso es lo que hay
   que arreglar primero.
2. Si los dos están instalados y visibles, y Android Auto igual los pide, puede que esta versión
   de Android Auto verifique algo más que la presencia del paquete (hoy la comunidad reporta que
   no). **Guardá la evidencia** antes de tocar nada, en los 5 minutos siguientes al intento:
   ```powershell
   adb logcat -b all -d > scratch/diag_aa_stubs_2026-09-XX.txt
   adb shell dumpsys package com.google.android.projection.gearhead | findstr versionName
   ```
   y anotá qué dice la pantalla (captura). Con eso decide la próxima sesión.
3. **No instales la Maps ni la App de Google reales "para probar".**

---

## 5. LO QUE NO HAY QUE HACER (está escrito también en el código)

- **No agregar estos paquetes a `allowedPackages`** (ni aprobar pedidos): es por nombre y
  dejaría pasar las apps reales. La Tienda ya los reconoce por la huella del archivo.
- **No sumarlos a ninguna lista por nombre de `AppController`** (`systemEssential`, etc.): LockSuite
  los reconoce **por la firma**, justamente para que la Maps real no quede protegida por
  llamarse igual.
- **No volver a correr `tools/aa_stubs/build_stubs.sh` "para actualizarlos".** Genera una clave
  nueva, y los stubs de hoy dejarían de reconocerse. Si alguna vez hace falta, el README de esa
  carpeta dice todo lo que hay que cambiar.
- **No re-firmar Waze ni usar el Waze de la Tienda para el auto.** Android Auto verifica la firma
  de Waze: uno re-firmado no aparece nunca (B.88). El del dueño es el oficial, y no hay que
  tocarlo.
- **Lo de B.87 sigue valiendo:** no excluir `com.google.android.gms` de la VPN, no `allowBypass()`,
  no `lockdown=true`.

---

## 6. SI HAY QUE VOLVER ATRÁS

- **Sacar los stubs de un celular:** ADB (`adb uninstall <paquete>`) o Ajustes → Apps, con
  "Bloquear Desinstalación de Apps" apagado. LockSuite **no** los desinstala, ni desde su propio
  panel: cuentan como críticos, a propósito.
- **Sacarlos de la Tienda:** borrar las dos tarjetas en el panel.
- **Volver atrás el código:** primero sacar los stubs de los celulares (una versión sin
  `AndroidAutoStubs` los ocultaría o suspendería), después `git revert` del commit B y publicar
  con `deploy_all.ps1`. No hay nada del panel ni de la base que revertir.

---

## 7. DE PASO

- **El respaldo de las reglas ya no está vacío.** Lo rehiciste el 23/9 a las 21:32: 3.082 bytes,
  JSON válido, idéntico a las reglas del repo en `fd76ccc`. Es el plan B de B.73.
- **Falta confirmar que las reglas nuevas (B.73) quedaron publicadas con 0.6.55.** Firebase
  Console → Realtime Database → Reglas: tiene que aparecer `deviceClaims`. Si no aparece, el
  paso de `database` de `deploy_all.ps1` falló en silencio, y el de 0.6.56 las va a publicar.
  Conviene saberlo antes, por el orden que pide §1 de las instrucciones del 22/9.
- **Hebreo:** la pestaña "Aplicaciones" y los interruptores de instalar/desinstalar apps decían
  `אפליקציες`, con dos letras **griegas** al final (desde el primer commit). Corregido en
  `LocaleManager.kt` (commit B). Si alguien usa LockSuite en hebreo, que lo mire.
