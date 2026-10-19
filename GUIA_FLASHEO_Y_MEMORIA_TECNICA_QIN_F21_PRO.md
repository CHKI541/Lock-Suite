# GUÍA MAESTRA DE FLASHEO Y MEMORIA TÉCNICA — DUOQIN QIN F21 PRO (MT6761)
> **Fecha de consolidación:** Octubre 2026  
> **Ubicación del paquete listo para flashear:** `D:\qin_f21_pro_rom\f21_usa_613_ready_to_flash\`  
> **Script ejecutable protegido:** `D:\qin_f21_pro_rom\f21_usa_613_ready_to_flash\flash_safe_argentina.bat`

---

## 1. FICHA TÉCNICA DEL DISPOSITIVO
- **Modelo:** Duoqin Qin F21 Pro (F21 Pro estándar con teclado físico numérico).
- **SoC / Procesador:** MediaTek Helio A22 (MT6761).
- **Variante de hardware:** "OLD" (pre-v3, con pantalla/audio original y puerto USB-C estándar, sin borde rojo).
- **Memoria RAM y Almacenamiento:** 4 GB RAM / 64 GB eMMC (partición `userdata` nativa de ~52 a 56 GB libres).
- **Sistema base:** Android 11 (arquitectura `arm64-v8a`).
- **Esquema de particiones:** Dispositivo A/B con partición dinámica `super` (4.0 GB) que aloja `system`, `vendor` y `product`.

---

## 2. HISTORIAL COMPLETO DE LO QUE SE HIZO EN ESTA SESIÓN

### A. Diagnóstico y extracción inicial (Filtro Kosher)
1. El teléfono llegó con una ROM personalizada kosher bloqueada por el sistema:
   - Administrador de bloqueo en `/system/priv-app/com.shapsplus.kmarket888/`.
   - WebView modificado de Chromium 83 en `/product/app/webview/webview.apk` con filtrado por lista blanca dura.
   - Base de datos ObjectBox embebida (`data.mdb`) con las entidades `WebViewWL`, `WebWords`, `ChromeFilterItem`, `ScreenBlock` y `TelegramFilterItem`.
2. Se realizó un volcado forense completo de todas las particiones del teléfono, aplicaciones del sistema, WebView y base de datos antes de intervenir el equipo.

### B. El problema de intentar instalar Google Play sobre la ROM china
- Al intentar instalar los APKs de GApps (`GoogleServicesFramework`, `Phonesky`) sobre el sistema Duoqin de fábrica mediante ADB:
  - `GoogleServicesFramework.apk` falló por conflicto de proveedores: `[INSTALL_FAILED_CONFLICTING_PROVIDER: provider com.android.car.ui.CarUiInstaller already used by com.android.managedprovisioning]`.
  - `Phonesky.apk` falló por verificación de firmas v2: `[INSTALL_PARSE_FAILED_NO_CERTIFICATES]`.
- **Conclusión técnica:** Para tener Google Play Store oficial y funcional no alcanza con instalar APKs sueltos; se requiere un `super.img` que tenga los servicios de Google y sus permisos integrados a nivel de sistema (`priv-app`).

### C. La propuesta de Grok y el peligro oculto de la ROM USA 6.1.3
Grok recomendó descargar la ROM `OLD-F21-USA-6.1.3-ROOTED_INTEGRITY_TWEAKS.rar` (desarrollada por `@triple613` y `@ars18` en JTech / XDA) y correr su instalador `flash-all.bat`.

**Al auditar el archivo `flash_all.bat` original, descubrimos un riesgo crítico:**
- En las líneas 43 a 46 ejecutaba:
  ```bat
  fastboot.exe flash nvram nvram.bin      <-- ¡SOBREESCRIBÍA EL IMEI POR EL DE UN KYOCERA DE USA!
  fastboot.exe flash nvdata nvdata.bin     <-- ¡SOBREESCRIBÍA CALIBRACIONES DE RADIO!
  fastboot.exe flash md1img_a md1img_a.bin <-- ¡CAMBIABA EL MÓDEM A VERIZON USA!
  ```
- **Por qué Grok obligaba a usar SN Write Tool y Bands Swap:** Como esa ROM fue armada para burlar el bloqueo de Verizon en EE.UU., le metieron un IMEI genérico estadounidense. Si la flasheabas completa, te destruía tu IMEI real y te dejaba sin señal 4G en Argentina (bandas B4 y B28). Por eso Grok requería reprogramar el IMEI con *SN Write Tool* y cambiar las bandas con una app externa.

### D. La solución segura implementada (Cero riesgos)
Creamos una versión segura del flasheador: `flash_safe_argentina.bat` (y `run_safe_flash.py`).
- **Qué flasheamos:**
  1. `vbmeta_a.img`, `vbmeta_system_a.img`, `vbmeta_vendor_a.img` con flags `--disable-verity --disable-verification` (desactiva Android Verified Boot para permitir arrancar con modificaciones).
  2. `dtbo_a.img`
  3. `boot_a.img` (Kernel parcheado con Magisk 30.2 root integrado).
  4. `spmfw_a.img` y `sspm_a.img` (Firmware del SoC).
  5. `super.img` (2.4 GB con Google Play Store nativo, GSF, Play Integrity Fix y tweaks de rendimiento).
  6. Activación del slot: `fastboot --set-active=a`.
  7. Formateo de datos: `fastboot -w` (crea el sistema de archivos limpio aprovechando los 64 GB nativos).
- **Qué EXCLUIMOS estrictamente:**
  - `nvram.bin`, `nvdata.bin`, `nvcfg.bin` ➔ **Tu IMEI original de fábrica quedó 100% intacto.**
  - `md1img_a.bin` ➔ **Tus bandas de radio 4G para Argentina quedaron 100% intactas.**
  - `preloader.bin` y `lk_a.bin` ➔ **Cero riesgo de brick en el gestor de arranque.**

### E. Solución del controlador USB Fastboot en Windows
Al reiniciar el teléfono a Fastboot (`adb reboot bootloader`), Windows lo detectó con error (Código 28, `USB\VID_0E8D&PID_201C`).
- Descargamos el controlador oficial Google USB Driver y parcheamos `android_winusb.inf` para asociar el Hardware ID de MediaTek Fastboot.
- Se seleccionó en el Administrador de Dispositivos: **Android Bootloader Interface**.
- Fastboot detectó el equipo al instante (`F21PD2409003915 fastboot`) y el flasheo seguro se completó en 91 segundos con éxito total (código de salida 0).

---

## 3. LIMPIEZA DE DISCO REALIZADA (RECUPERACIÓN DE ESPACIO)

Para solucionar el problema de falta de espacio en la unidad `C:`:
1. **Se eliminó completamente `C:\f21pro_clean_rom`:**  
   Liberó **~3.2 GB** de archivos temporales de descarga (paquete de MediaFire, APKs sueltos de OpenGApps y descompresiones temporales).
2. **Se limpió `C:\rom_f21pro_dump`:**  
   Se eliminaron más de **4.4 GB** de imágenes completas duplicadas del sistema (`super.img.gz`, volcados crudos redundantes).
3. **Se preservaron todos los archivos valiosos:**  
   Se trasladaron de forma segura al Disco `D:` (base de datos de LockSuite, APK del filtro, WebView modificado y respaldos de calibración de hardware).
4. **Resultado en Disco C:**  
   El espacio libre en `C:` subió de **9.65 GB** a **17.29 GB** (se recuperaron más de **7.6 GB** netos).

---

## 4. ESTRUCTURA DE ARCHIVOS EN EL DISCO D: (`D:\qin_f21_pro_rom\`)

Todo el entorno quedó centralizado y respaldado en `D:\qin_f21_pro_rom\`:

```text
D:\qin_f21_pro_rom\
├── f21_usa_613_ready_to_flash\          <-- CARPETA LISTA PARA FLASHEAR CUALQUIER F21 PRO
│   ├── flash_safe_argentina.bat         <-- Doble clic para flashear de forma segura
│   ├── run_safe_flash.py                <-- Automatizador seguro en Python
│   ├── super.img                        <-- Sistema con Google Play Store nativo (2.4 GB)
│   ├── boot_a.img                       <-- Kernel + Magisk root
│   ├── vbmeta_a.img                     <-- Desactivador de firmas AVB
│   ├── vbmeta_system_a.img
│   ├── vbmeta_vendor_a.img
│   ├── dtbo_a.img
│   ├── spmfw_a.img
│   ├── sspm_a.img
│   ├── fastboot.exe                     <-- Binario fastboot versión 36.0.0
│   ├── adb.exe
│   ├── AdbWinApi.dll
│   └── AdbWinUsbApi.dll
│
├── drivers_fastboot_adb\                <-- CONTROLADORES PARA WINDOWS
│   └── usb_driver\
│       ├── android_winusb.inf           <-- Driver parcheado con soporte MT6761 Fastboot
│       └── ...
│
├── backup_kosher_locksuite\             <-- DATOS DE INVESTIGACIÓN Y HARDWARE
│   ├── kmarket\                         <-- APK de com.shapsplus.kmarket888
│   ├── kmarket_data\                    <-- Base de datos ObjectBox (data.mdb con listas blancas)
│   ├── webview\                         <-- WebView Chromium 83 modificado
│   ├── configs\                         <-- Tabla de particiones y getprops originales
│   └── hardware_calibration_backup\     <-- Respaldos de IMEI / RF de este equipo puntual
│       ├── nvram.img
│       ├── nvdata.img
│       ├── nvcfg.img
│       ├── protect1.img
│       ├── protect2.img
│       ├── proinfo.img
│       ├── boot_a.img
│       ├── logo.img
│       └── md1img_a.img                 <-- Módem original de Argentina (bandas stock)
│
└── GUIA_FLASHEO_Y_MEMORIA_TECNICA_QIN_F21_PRO.md <-- Este documento de referencia
```

---

## 5. INSTRUCCIONES PASO A PASO PARA FLASHEAR OTRO QIN F21 PRO EN EL FUTURO

Cuando tengas otro Qin F21 Pro (modelo MT6761 / 64GB) y quieras dejarlo con Google Play Store y sin filtros:

### Paso 1: Verificar que el bootloader esté desbloqueado
1. Al encender el equipo, debe aparecer el texto en letras blancas pequeñas arriba a la izquierda que dice **"Orange State - Your device has been unlocked..."**.
2. Si no lo tiene desbloqueado, se desbloquea con `mtkclient` en modo BROM (`python mtk da seccfg unlock`).

### Paso 2: Poner el teléfono en modo Fastboot
- **Método A (Con el teléfono encendido en Android):**
  1. Conectá el teléfono por USB con Depuración USB activa.
  2. En una consola ejecutá:  
     `adb reboot bootloader`
  3. El teléfono se reiniciará a una pantalla negra que dice abajo en letras chicas: `=> FASTBOOT mode...`.
- **Método B (Con el teléfono apagado):**
  1. Con el teléfono apagado y desenchufado, mantené presionados: **Tecla superior izquierda (Corazón)** + **Power** + **Tecla `*`**.
  2. Al encender la pantalla, soltá **Power** pero mantené apretados **Corazón** y **`*`** hasta entrar al Recovery.
  3. Con el joystick seleccioná **Reboot to bootloader** y pulsá Power.
  4. Enchufá el cable USB a la PC.

### Paso 3: Verificar el controlador en Windows
1. Abrí el Administrador de Dispositivos (`Win + X` ➔ *Administrador de dispositivos*).
2. Si bajo "Otros dispositivos" aparece **Android** con signo de exclamación:
   - Clic derecho en `Android` ➔ *Actualizar controlador* ➔ *Examinar mi PC en busca de controladores* ➔ *Elegir en una lista de controladores disponibles en el equipo* ➔ Elegir **Android Device** ➔ Seleccionar **Android Bootloader Interface** (o pulsar *Usar disco...* y buscar `D:\qin_f21_pro_rom\drivers_fastboot_adb\usb_driver\android_winusb.inf`).

### Paso 4: Ejecutar el flasheo seguro
Tenés dos opciones idénticas:
- **Opción simple:** Hacé doble clic en:  
  `D:\qin_f21_pro_rom\f21_usa_613_ready_to_flash\flash_safe_argentina.bat`  
  Escribí `SI` cuando el script te lo pida y presioná Enter.
- **Opción con IA:** Abrí una conversación con el asistente, compartile este archivo y decile:  
  *"Tengo conectado un Qin F21 Pro en modo fastboot, ejecutame el flasheo seguro ubicado en D:\qin_f21_pro_rom\f21_usa_613_ready_to_flash"*.

### Paso 5: Primer encendido
1. El teléfono se reiniciará solo. El primer inicio tarda entre **2 y 4 minutos**.
2. Al iniciar, el asistente de Android estará limpio.
3. Esperá unos 30 segundos a que el script interno termine de otorgar permisos de Play Integrity.
4. Abrí **Google Play Store**, iniciá sesión con tu cuenta de Google y disfrutá del teléfono como un Android normal sin restricciones y con 64 GB nativos.
