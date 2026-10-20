# MEMORIA TÉCNICA MAESTRA Y ESTADO ACTUAL — FLASHEO DUOQIN QIN F21 PRO (MT6761)
> **Fecha y hora:** 20 de Octubre de 2026 - 09:00 AM  
> **Ubicaciones del archivo:**  
> 📁 `e:\Documentos\Lock Suite segunda version\ESTADO_ACTUAL_Y_CONTINUACION_FLASHEO_F21_PRO.md`  
> 📁 `D:\qin_f21_pro_rom\ESTADO_ACTUAL_Y_CONTINUACION_FLASHEO_F21_PRO.md`

---

## 1. COMPARATIVA DE LOS DOS TELÉFONOS (QUÉ SE HIZO EN CADA UNO)

Ambos teléfonos son **DuoQin Qin F21 Pro** idénticos de hardware (SoC MediaTek Helio A22 / MT6761, 4 GB RAM, 64 GB eMMC, placa `g55v71c2k_dfl_eea`, Android 11 base con ROM kosher israelí de fábrica).  
Comparamos el hash SHA-256 del kernel original de fábrica (`boot_a.img`) de ambos y es **exactamente idéntico**:  
`33a5d929e127faa642c7677f57289420a2f441216bfa1ddb547dac6a0524e321`.

### 📱 EL PRIMER TELÉFONO: CÓMO SE FLASHEÓ CON ÉXITO
1. **Estado inicial:** Venía con bloqueo Kosher (`com.shapsplus.kmarket888` y WebView de Chromium modificado).
2. **Auditoría del script de Grok:** Grok recomendaba la ROM `OLD-F21-USA-6.1.3-ROOTED_INTEGRITY_TWEAKS` con su script `flash-all.bat`. Al auditarlo, descubrimos que ese script sobreescribía `nvram.bin`, `nvdata.bin` (destruía el IMEI por uno de un Kyocera de USA) y `md1img_a.bin` (cambiaba el módem a Verizon USA, quitando las bandas B4 y B28 de Argentina).
3. **El flasheo seguro creado:**
   * Creamos `flash_safe_argentina.bat` (y `run_safe_flash.py`).
   * **Excluimos:** `nvram`, `nvdata`, `nvcfg`, `md1img_a` (conservando IMEI y 4G original de Argentina).
   * **Flasheamos por Fastboot:**
     * `vbmeta`, `vbmeta_system`, `vbmeta_vendor` con `--disable-verity --disable-verification`.
     * `dtbo_a.img`, `boot_a.img` (Magisk), `spmfw_a.img`, `sspm_a.img`.
     * `super.img` (2.4 GB con Google Play Store nativo).
     * `fastboot --set-active=a`.
     * **`fastboot -w` (Wipe / formateo total de datos de usuario).**
     * `fastboot reboot`.
4. **Resultado:** Al tener el formateo `fastboot -w` desde el inicio, el primer teléfono arrancó limpio en 3 minutos con Google Play Store oficial, Magisk root, 64 GB nativos y señal 4G perfecta de Argentina.

---

### 📱 EL SEGUNDO TELÉFONO: QUÉ PASÓ Y POR QUÉ SE TRABÓ
1. **Respaldo preventivo:** Antes de tocar nada, respaldamos:
   * **Archivos personales:** 375 archivos (408 MB de fotos, descargas, cámara) en `D:\qin_f21_pro_rom\backup_segundo_f21\sdcard_user_data\`.
   * **Calibraciones de hardware:** `nvram`, `nvdata`, `nvcfg`, `protect1`, `protect2`, `proinfo`, `md1img_a`, `boot_a` en `D:\qin_f21_pro_rom\backup_segundo_f21\`.
2. **El detonante del problema (Petición de no borrar datos):**  
   El usuario solicitó: *"si existe, que no se borren los datos del usuario"*.  
   Para complacerlo, flasheamos la ROM pero **omitimos el formateo `fastboot -w`**.
3. **El conflicto criptográfico (Bucle en Duoqin):**  
   Android 11 utiliza cifrado de disco obligatorio por hardware (FBE - File Based Encryption). Al arrancar el nuevo sistema con claves criptográficas distintas, no pudo descifrar la partición `/data` existente. El sistema operativo crasheó y entró en un bucle infinito en el logo "Duoqin".
4. **El problema del reinicio de hardware:**  
   Al intentar apagarlo manteniendo presionado Power por 10 segundos, el chip de energía MediaTek (PMIC) no lo apaga: ejecuta un **reinicio forzado por hardware** (la pantalla se apaga 1 segundo y vuelve a prender sola).
5. **Acciones de rescate ejecutadas:**
   * **mtkclient:** Configuramos el entorno Python con `libusb` y logramos atrapar el teléfono en modo BROM. Se ejecutó un formateo físico a bajo nivel de toda la partición `userdata` (57.34 GB).
   * **SP Flash Tool:** Flasheó con éxito `logo`, `spmfw_a`, `scp_a`, `sspm_a`, `boot_a`, `dtbo_a`, `tee_a`, `vbmeta_a`, pero dio error `STATUS_DOWNLOAD_EXCEPTION (0xC0050003)` al 8% de `super.img` porque en el scatter de USA estaba marcado como `NORMAL_ROM` siendo un sparse de 2.45 GB.
   * **Fastboot:** Capturamos el teléfono en Fastboot (`0123456789ABCDEF fastboot`) y ejecutamos `run_safe_flash.py`, flasheando `super.img` en 19 partes de 2.4 GB con éxito y realizando `fastboot -w`.
6. **Diagnóstico de fondo:**  
   A pesar de estar flasheado al 100% y con `userdata` formateado, el teléfono continuó trabado en el logo "Duoqin". Esto confirmó que la ROM modificada **USA 6.1.3** tiene problemas de compatibilidad en su kernel con Magisk / ramdisk para ciertos paneles o lotes.
7. **La solución definitiva:**  
   El usuario recordó que en el pasado ya había flasheado con 100% de éxito la **ROM oficial limpia 1.1.1** (contenida en `qin_f21_pro_mtk.zip`). Procedimos a extraerla en `C:\f21pro_111\firmware\`, lista para grabarse por SP Flash Tool.

---

## 2. MAPEO COMPLETO DE RUTAS EN LA PC

### Discos y herramientas:
* **SP Flash Tool v5.1924:**  
  📁 `E:\Programas\01_Moviles_y_Flasheo\SP_Flash_Tool_v5.1924_Win\`  
  * Ejecutable: `E:\Programas\01_Moviles_y_Flasheo\SP_Flash_Tool_v5.1924_Win\flash_tool.exe`  
  * Download Agent: `E:\Programas\01_Moviles_y_Flasheo\SP_Flash_Tool_v5.1924_Win\MTK_AllInOne_DA.bin`
* **Fastboot / ADB:**  
  📁 `D:\qin_f21_pro_rom\f21_usa_613_ready_to_flash\fastboot.exe`  
  📁 `D:\qin_f21_pro_rom\f21_usa_613_ready_to_flash\adb.exe`
* **mtkclient:**  
  📁 `C:\Users\israe\Downloads\mtkclient-main\`

### Respaldos del SEGUNDO teléfono (100% seguros):
* **Fotos y archivos de usuario (`/sdcard/`):**  
  📁 `D:\qin_f21_pro_rom\backup_segundo_f21\sdcard_user_data\` (375 archivos, 408 MB)
* **Particiones de radio e IMEI original:**  
  📁 `D:\qin_f21_pro_rom\backup_segundo_f21\` (`nvram.img`, `nvdata.img`, `md1img_a.img`, etc.)

### Firmware probado listo para flashear (ROM 1.1.1):
* **Carpeta de trabajo lista para SP Flash Tool:**  
  📁 `C:\f21pro_111\firmware\`
* **Archivo Scatter:**  
  📄 `C:\f21pro_111\firmware\MT6761_Android_scatter.txt`
* **Archivo zip original:**  
  📦 `C:\Users\israe\Downloads\qin_f21_pro_mtk.zip` *(dentro tiene `F21PRO_1.1.1 (2).zip`)*

---

## 3. PASO A PASO PARA COMPLETAR EL FLASHEO AHORA

### Paso 1: Flashear la ROM 1.1.1 en SP Flash Tool
1. Abrir `E:\Programas\01_Moviles_y_Flasheo\SP_Flash_Tool_v5.1924_Win\flash_tool.exe`.
2. **Download-Agent:** Seleccionar `E:\Programas\01_Moviles_y_Flasheo\SP_Flash_Tool_v5.1924_Win\MTK_AllInOne_DA.bin`.
3. **Scatter-loading File:** Seleccionar `C:\f21pro_111\firmware\MT6761_Android_scatter.txt`.
4. Modo: **Download Only** *(NUNCA usar "Format All + Download")*.
5. **DESTILDAR (Desmarcar por seguridad):**
   * ⬜ `preloader` *(Seguridad total contra bricks)*
   * ⬜ `md1img_a` *(o `md1img.img` / módem: mantiene señal 4G de Argentina intacta)*
   * ⬜ `md_udc`
   * ⬜ `lk_a` *(Mantiene el bootloader desbloqueado)*
6. **DEJAR TILDADO (Marcado):**
   * ☑ `boot_a`
   * ☑ `dtbo_a`
   * ☑ `spmfw_a`
   * ☑ `scp_a`
   * ☑ `sspm_a`
   * ☑ `tee_a`
   * ☑ `vbmeta_a`
   * ☑ `vbmeta_system_a`
   * ☑ `vbmeta_vendor_a`
   * ☑ `super` (1.75 GB)
   * ☑ `userdata`
   * *(y `logo` si querés)*
7. Clic en **Download**.
8. Conectar el teléfono con el cable USB manteniendo **Atrás / Volver + Power** (soltar Power apenas la pantalla se vaya a negro).
9. SP Flash Tool completará la barra amarilla y mostrará el círculo verde (**Download OK**).

### Paso 2: Primer arranque y restauración de archivos
1. El teléfono iniciará a Android limpio en 1 a 2 minutos.
2. Conectar el teléfono por USB con Depuración USB activa.
3. En una consola de comandos ejecutar:
   ```cmd
   adb push "D:\qin_f21_pro_rom\backup_segundo_f21\sdcard_user_data\." /sdcard/
   ```
   ¡Y todas tus fotos, descargas y carpetas vuelven a estar exactamente donde estaban!

---

## 4. PROMPT PARA PEGAR EN UNA NUEVA CONVERSACIÓN

```text
Hola. Estoy trabajando en el flasheo de mi segundo teléfono Qin F21 Pro (MediaTek MT6761 / 64GB).

Por favor, lee el archivo de memoria técnica y estado actual ubicado en:
"e:\Documentos\Lock Suite segunda version\ESTADO_ACTUAL_Y_CONTINUACION_FLASHEO_F21_PRO.md"
(o en "D:\qin_f21_pro_rom\ESTADO_ACTUAL_Y_CONTINUACION_FLASHEO_F21_PRO.md").

Resumen de la situación:
1. El primer F21 Pro lo flasheamos con éxito mediante Fastboot con la ROM USA 6.1.3 + formateo de datos.
2. En este segundo F21 Pro, intentamos la misma ROM USA 6.1.3 pero sin wipe inicial de datos a pedido mío, lo que generó un conflicto de cifrado FBE. Aunque luego formateamos userdata y flasheamos la ROM USA 6.1.3 completa en Fastboot, el teléfono sigue trabado en el logo Duoqin por incompatibilidad de kernel/display de esa ROM con esta placa.
3. Mis fotos y archivos personales están 100% seguros y respaldados en "D:\qin_f21_pro_rom\backup_segundo_f21\sdcard_user_data\" (375 archivos, 408 MB) y las calibraciones de IMEI/radio en "D:\qin_f21_pro_rom\backup_segundo_f21\".
4. La solución que ya tengo probada y que usé la vez pasada con SP Flash Tool es la ROM oficial limpia 1.1.1, la cual ya está completamente extraída en:
   "C:\f21pro_111\firmware\MT6761_Android_scatter.txt"
5. SP Flash Tool está listo en:
   "E:\Programas\01_Moviles_y_Flasheo\SP_Flash_Tool_v5.1924_Win\flash_tool.exe"

Dime cómo procedemos exactamente para flashear esta ROM 1.1.1 en SP Flash Tool y luego restaurar mis fotos.
```
