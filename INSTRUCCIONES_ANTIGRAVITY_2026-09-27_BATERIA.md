# INSTRUCCIONES PARA ANTIGRAVITY — 27/9/2026
## CPU y batería: la re-aplicación de cada 15 minutos reescribía todo el sistema aunque no cambiara nada (B.89)

**Empezá por acá.** El porqué completo está en `LOCKSUITE_CONTEXTO_PARA_IA.md`, punto **B.89**.
Esto es solo lo operativo.

> ## 🔴 LO PRIMERO
> - **Qué es:** el dueño pidió revisar lo que gasta LockSuite en CPU y batería, en uso y en
>   reposo. Lo más grande que apareció: `reapplyAllRestrictions()` (cada 15 min, en cada arranque
>   y en cada arranque del proceso) **volvía a ordenar TODAS las políticas aunque ya estuvieran
>   puestas**, y en Android 13 cada orden guarda `device_policies.xml` con `fsync` y avisa a todo
>   el sistema. Y en Android ≤ 13 **cada vuelta sumaba un "launcher preferido" duplicado, para
>   siempre**. Ahora compara antes de escribir. Más cinco gastos chicos (preferencias cifradas,
>   accesibilidad, marcador, pantallas de PIN y emergencia).
> - **Estado:** compilado (APK de depuración completo), **45/45 pruebas unitarias** (27 nuevas),
>   **22 controles negativos detectados**, lint sin errores nuevos, simetría de B.38 y los siete
>   chequeos de `tools/` en verde. **Nada probado en un celular.**
> - **Orden:** el commit (§0) → **medir con 0.6.56 todavía instalada** (§1) → chequeos y 0.6.57
>   (§2) → pruebas de regresión en el celular (§3) → medir de nuevo (§4).
> - **Riesgo a vigilar:** que una política quede SIN aplicar. El código está hecho para que eso
>   no pase ("ante la duda, se escribe como antes"), pero es justo lo que §3 tiene que confirmar
>   primero.

---

## 0. EL COMMIT

Es **un solo commit** (código + pruebas + este documento + el contexto). El parche y el mensaje
están en `Claude outputs/`:

- `Claude outputs/2026-09-27_bateria.patch` — el commit entero, en formato `git format-patch`
  (sirve para `git am` y para `git apply`).
- `Claude outputs/2026-09-27_mensaje_commit.txt` — el mensaje, por si lo commiteás a mano.

⚠️ **Si esos dos archivos no están en `Claude outputs/`**, es que el puente al disco se cortó
antes de que Claude pudiera escribirlos: el dueño los tiene en el chat de Claude del 27/9
(archivos adjuntos) y hay que copiarlos ahí primero.

Desde la raíz del repo, en PowerShell:

```powershell
git log -1 --oneline
# Tiene que decir: 80573e5 Actualizacion automatica a version 0.6.56 (Codigo 119)
# Si dice otra cosa, NO sigas: alguien commiteó después; avisale a Claude.
git status --short
```

Después, **uno de los dos caminos**, según lo que muestre `git status --short`:

**A — `git status` NO muestra los archivos del parche** (el caso esperado si el puente se cortó:
en el disco no hay nada de hoy). `git am` aplica y commitea en un paso:

```powershell
git am "Claude outputs/2026-09-27_bateria.patch"
```

**B — `git status` SÍ muestra `PolicyManager.kt`, `PolicyReconciler.kt`, etc.** (Claude llegó a
escribirlos en el disco). Entonces el commit se arma en el índice con el parche, sin tocar ningún
archivo:

```powershell
git diff --cached --stat
# Tiene que salir VACÍO. Si no: `git reset` (saca todo del índice SIN tocar el disco).
git apply --cached "Claude outputs/2026-09-27_bateria.patch"
git commit -F "Claude outputs/2026-09-27_mensaje_commit.txt"
```

**Control (los dos caminos):**

```powershell
git status --short          # ninguna línea con M ni ?? de archivos del proyecto
git log --oneline -2        # arriba: "perf(bateria): comparar antes de escribir ... (B.89)"
git show --stat HEAD        # 13 archivos: los de la lista de "Estado del repo" del contexto
```

Si en el camino B `git status` sigue mostrando alguno de esos archivos como modificado después del
commit, es que el disco y el parche no coinciden: **no descartes nada**, mandale a Claude la salida
de `git diff`.

---

## 1. MEDIR ANTES, CON 0.6.56 TODAVÍA INSTALADA (10 minutos)

Sin esto no hay contra qué comparar. Con el celular del dueño enchufado por USB y ADB andando:

```powershell
mkdir scratch -ErrorAction SilentlyContinue
# 1. Las restricciones que RIGEN hoy (la lista contra la que se compara todo después)
adb shell dumpsys user | Out-File -Encoding utf8 scratch\bat_user_0656.txt
adb shell dumpsys device_policy | Out-File -Encoding utf8 scratch\bat_dp_0656.txt
# 2. Qué hace LockSuite en una vuelta: reiniciar dispara dos re-aplicaciones (arranque del
#    equipo y arranque del proceso). Esperá a que prenda y desbloqueá.
adb reboot
adb wait-for-device
Start-Sleep -Seconds 90
adb logcat -d -v time -s PolicyManager:I AppController:W | Out-File -Encoding utf8 scratch\bat_log_0656.txt
```

Qué mirar en `bat_log_0656.txt` (así se ve el gasto de hoy): si la protección de accesibilidad y
el bloqueo de VPN están encendidos, en CADA re-aplicación aparecen
`setPermittedAccessibilityServices: [...]`, `Always-on VPN activa (lockdown=false)` y
`DNS Privado desactivado a nivel global`, aunque no haya cambiado nada. Esas líneas son
escrituras en el sistema (y no son las únicas: las restricciones no dejan línea en el log). (Las de `AppController` *"Android no aplico ocultamiento"*
son ruido conocido: ver B.89-e.)

**Batería de verdad (opcional, una noche):** `adb shell dumpsys batterystats --reset`,
desenchufar, dejarlo toda la noche, y a la mañana
`adb shell dumpsys batterystats --charged com.ejemplo.locksuite | Out-File -Encoding utf8 scratch\bat_bs_0656.txt`.
Repetir otra noche con 0.6.57 (§4). Las noches no son iguales, así que sirve para ver el orden de
magnitud, no para decimales.

---

## 2. CHEQUEOS Y DESPLIEGUE DE 0.6.57

```powershell
python tools/check_whitelist_sync.py
python tools/check_profile_sync.py
python tools/check_command_sync.py
python tools/check_panel_commands.py
python tools/gen_catalog_js.py --check
python tools/gen_policies_js.py --check
python tools/aa_stubs/check_stubs.py
.\gradlew :app:testDebugUnitTest          # 45 pruebas, 0 fallas (19 PolicyReconcilerTest, 8 DialerCodeScanTest)
.\deploy_all.ps1 -VersionName "0.6.57"
```

**0.6.57 es solo la app Android.** No cambian el panel, ni `functions`, ni las reglas, y no hay
comandos FCM nuevos.

**Si Gradle no compila en la PC** (en el contenedor compiló, así que sería raro): lo único "nuevo"
para el compilador es `androidx.lifecycle.compose.LocalLifecycleOwner` y `repeatOnLifecycle` en
`LoginActivity.kt` y `EmergencyActivity.kt` (vienen en `lifecycle-runtime-compose` 2.10.0 y
`lifecycle-runtime-ktx`, que ya están en el build). Mandale el error a Claude antes de tocar nada.

---

## 3. PRUEBAS EN EL CELULAR, EN ESTE ORDEN

Instalá 0.6.57 en el celular del dueño (por la actualización automática o con `adb install -r`).

### Prueba 1 — QUE NINGUNA POLÍTICA SE PERDIÓ (va primero, es la regresión a vigilar)

Esperá un minuto después de instalar y:

```powershell
adb shell dumpsys user | Out-File -Encoding utf8 scratch\bat_user_0657.txt
Compare-Object (Get-Content scratch\bat_user_0656.txt) (Get-Content scratch\bat_user_0657.txt)
```

En la sección **"Effective restrictions"** (las que rigen) tienen que estar **exactamente las
mismas** `no_...` que antes. Si falta UNA, es un hallazgo grave: mandá los dos archivos a Claude y
**no** despliegues a otros equipos. Hacé lo mismo con `dumpsys device_policy` (van a cambiar horas
y contadores; lo que no puede cambiar es `disableCamera`, `disableScreenCapture`, la lista de
accesibilidad permitida, los paquetes de Lock Task ni las restricciones del administrador).

### Prueba 2 — reiniciar y confirmar lo mismo

```powershell
adb reboot
adb wait-for-device
Start-Sleep -Seconds 90
adb shell dumpsys user | Out-File -Encoding utf8 scratch\bat_user_0657_reinicio.txt
Compare-Object (Get-Content scratch\bat_user_0656.txt) (Get-Content scratch\bat_user_0657_reinicio.txt)
adb logcat -d -v time -s PolicyManager:I AppController:W | Out-File -Encoding utf8 scratch\bat_log_0657.txt
```

Y en `bat_log_0657.txt`: las líneas de §1 (`setPermittedAccessibilityServices`,
`Always-on VPN ...`, `DNS Privado ...`) **ya no tienen que aparecer**, salvo que hubiera algo que
corregir. Si aparece `Always-on VPN reafirmada`, es que el sistema la había perdido y se corrigió:
anotalo.

### Prueba 3 — el launcher kosher (si está encendido en ese equipo)

1. Apretar **Inicio**: tiene que abrir el launcher kosher, como siempre.
2. En el log de la primera re-aplicación con 0.6.57 tiene que aparecer **una vez**
   `Launcher kosher fijado como inicio (una sola entrada).` — es la limpieza de los duplicados que
   0.6.56 y anteriores fueron acumulando (en Android ≤ 13, ~96 por día). Después, a lo sumo una
   vez por día.
3. Apagar el launcher kosher desde el panel → **Inicio** abre el launcher del fabricante.
   Prenderlo de nuevo → **Inicio** vuelve al kosher.
4. **Suspender LockSuite y reanudar** desde el panel: suspendido, Inicio abre el del fabricante;
   reanudado, vuelve el kosher **sin tocar nada**, y `dumpsys user` vuelve a ser igual al de §1.

⚠️ No hay forma de contar los duplicados sin root (viven en
`/data/system/users/0/package-restrictions.xml`). La prueba es funcional: que Inicio haga lo que
tiene que hacer en los cuatro pasos.

### Prueba 4 — códigos del marcador

- Marcar `*#*#1234#*#*` → abre la pantalla de PIN de LockSuite.
- Marcar `*#*#9999#*#*` → abre la pantalla de emergencia (salir SIN purgar).
- Durante una llamada, abrir el teclado de la llamada y marcar `*#*#1234#*#*` → igual que arriba.

La búsqueda cambió de dos recorridos a uno (con banco de pruebas contra la versión vieja), así que
esto es confirmar que en la pantalla real sigue igual.

### Prueba 5 — PIN y bloqueo por intentos

1. Entrar con el PIN correcto.
2. Poner un PIN equivocado las veces que haga falta hasta que aparezca **"entrada bloqueada"** con
   la cuenta regresiva.
3. Apretar **Inicio**, esperar 20 segundos y volver a la pantalla de PIN: la cuenta tiene que
   seguir, **con el tiempo que corresponde** (no congelada en el número de antes).
4. Cuando termine, el PIN correcto tiene que entrar.

### Prueba 6 — lo demás que toca la re-aplicación (solo lo que esté encendido en ese equipo)

- **VPN:** hay internet; un dominio bloqueado sigue bloqueado.
  `adb shell settings get secure always_on_vpn_app` → `com.ejemplo.locksuite`;
  `adb shell settings get secure always_on_vpn_lockdown` → `0`.
- **Kiosco (Lock Task):** encender y apagar desde el panel; igual que antes.
- **Navegadores / WebView suspendidos:** siguen suspendidos después de instalar y de reiniciar;
  apagar el interruptor los libera y prenderlo los vuelve a suspender.
- **Bloqueo de instalación:** Play Store bloqueada o libre según el interruptor, como antes.
- **Bloqueo de imágenes con IA:** igual que antes (solo cambió cuándo se consulta la memoria del
  equipo).

---

## 4. MEDIR DESPUÉS

Lo mismo de §1 con 0.6.57 (los archivos `*_0657*` ya los generaste en §3). Y si hiciste la noche
de batterystats con 0.6.56, repetila:
`adb shell dumpsys batterystats --charged com.ejemplo.locksuite | Out-File -Encoding utf8 scratch\bat_bs_0657.txt`.

Qué esperar:

- **Del lado de LockSuite:** menos CPU en cada vuelta del Watchdog (antes enumeraba todas las apps
  cargando sus nombres) y en uso (accesibilidad, marcador, preferencias cifradas).
- **Del lado del sistema (lo más grande):** en Android 13, de 20-40 escrituras de
  `device_policies.xml` por vuelta a cero cuando no hay nada que corregir. Eso se paga en
  `system_server` (uid 1000), no en LockSuite: en batterystats aparece repartido y no se ve limpio.
  La prueba objetiva es el logcat de §3-2.

Dejá los archivos de `scratch\bat_*` en el disco: Claude los lee en la próxima sesión.

---

## 5. LO QUE NO HAY QUE "SIMPLIFICAR" (está escrito también en el código)

- **`PolicyReconciler.hayQueAplicar` pide DOS lecturas** (la restricción puesta por nosotros Y la
  que rige). No dejar una sola: en Android 14 el sistema tuvo un error en que la política quedaba
  guardada sin regir (b/307481299), y la llamada de siempre es la que la re-sincroniza.
- **Ante una lectura que falla, se escribe.** Todas las lecturas nuevas devuelven `null` al fallar
  y `null` significa "escribir como antes". No cambiarlo a "no escribir".
- **El launcher se re-consolida una vez por día aunque "Inicio" ya abra el nuestro.** No hay API
  para leer las entradas persistentes; sin esto, una perdida por fuera de LockSuite no se vería.
  Y consolidar es borrar + poner, así que no acumula.
- **Liberar (des-suspender) no compara:** solo suspender. Liberar también limpia el registro del
  DevicePolicyManager.
- **`DialerCodeScan` sin `lowercase()`** y con UN recorrido: está probado contra la versión vieja
  en 5.500 árboles al azar. Si se cambia, correr `DialerCodeScanTest`.

---

## 6. SI HAY QUE VOLVER ATRÁS

`git revert` del commit y publicar con `deploy_all.ps1`. No hay nada del panel ni de la base que
revertir. Lo único que queda en el equipo es la preferencia técnica `kosher_home_ppa_ts_v1` (una
marca de tiempo), que la versión vieja ignora. Ojo: volver a 0.6.56 vuelve a sumar un launcher duplicado cada
15 minutos en Android ≤ 13.

---

## 7. PARA EL DUEÑO (decisiones, no tareas)

Detalle y costo de cada una en B.89 (a-e): **Firebase Analytics** incluido y sin usar (¿alguien
mira Analytics en la consola? si no, sacarlo); **la lista de apps** se sube completa y dos veces en
cada sincronización; **el scroll de accesibilidad** llega de todas las apps aunque no haya bloqueo
de imágenes; **Play Store se libera dos veces por vuelta** (en Android 14+ guarda cada vez); y el
**ruido de "no aplicó ocultamiento"** en el logcat.
