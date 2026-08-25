@echo off
title Instalador LockSuite MDM
color 0B
cls

echo ╔══════════════════════════════════════════════════════╗
echo ║          INSTALADOR AUTOMATICO - LockSuite MDM       ║
echo ╚══════════════════════════════════════════════════════╝
echo.

:: ─── Verificar que ADB está disponible ───────────────────
where adb >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [!] ADB no encontrado. Descargando Platform Tools de Android...
    powershell -Command "& {Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile '%TEMP%\platform-tools.zip' -UseBasicParsing; Expand-Archive -Force '%TEMP%\platform-tools.zip' -DestinationPath '%TEMP%\'}"
    set "PATH=%PATH%;%TEMP%\platform-tools"
    set "ADB=%TEMP%\platform-tools\adb.exe"
) else (
    set "ADB=adb"
)

echo.
echo [*] Iniciando servidor ADB...
%ADB% start-server >nul 2>&1
timeout /t 2 /nobreak >nul

:: ─── Esperar dispositivo conectado ───────────────────────
echo.
echo [*] Esperando dispositivo conectado...
echo     Asegurate de:
echo       1. Cable USB conectado
echo       2. Depuracion USB activada (Ajustes → Opciones de desarrollador)
echo       3. Celular desbloqueado
echo       4. Tocar "Permitir" en el dialogo de depuracion USB
echo.

:wait_device
%ADB% devices 2>&1 | findstr /C:"device" | findstr /V /C:"List" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    timeout /t 2 /nobreak >nul
    goto wait_device
)

echo [✓] Dispositivo detectado!
echo.

:: ─── Mostrar info del dispositivo ────────────────────────
for /f "tokens=*" %%i in ('%ADB% shell getprop ro.product.brand 2^>nul') do set BRAND=%%i
for /f "tokens=*" %%i in ('%ADB% shell getprop ro.product.model 2^>nul') do set MODEL=%%i
echo [i] Dispositivo: %BRAND% %MODEL%
echo.

:: ─── Descargar APK ───────────────────────────────────────
set "APK_URL=https://locksuite-nueva.web.app/locksuite-latest.apk"
set "APK_FILE=%TEMP%\locksuite-install.apk"

echo [*] Descargando ultima version de LockSuite...
powershell -Command "& {Invoke-WebRequest -Uri '%APK_URL%' -OutFile '%APK_FILE%' -UseBasicParsing}"

if not exist "%APK_FILE%" (
    echo [ERROR] No se pudo descargar el APK. Verifica tu conexion a internet.
    pause
    exit /b 1
)
echo [✓] APK descargado correctamente.
echo.

:: ─── Instalar APK ────────────────────────────────────────
echo [*] Instalando LockSuite en el dispositivo...
%ADB% install -r "%APK_FILE%"
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Fallo la instalacion del APK.
    pause
    exit /b 1
)
echo [✓] LockSuite instalado correctamente.
echo.

:: ─── Verificar cuentas de Google ─────────────────────────
echo [*] Verificando cuentas de Google en el dispositivo...
for /f "tokens=*" %%a in ('%ADB% shell dumpsys account 2^>nul ^| findstr /i "name="') do (
    echo     %%a
)
echo.
echo [!] IMPORTANTE: Si hay cuentas de Google activas arriba, debes eliminarlas
echo     desde Ajustes → Cuentas → Administrar cuentas ANTES de continuar.
echo     Podes volver a agregarlas despues de la instalacion.
echo.
pause

:: ─── Asignar Device Owner ────────────────────────────────
echo [*] Asignando permisos de Device Owner...
%ADB% shell dpm set-device-owner com.ejemplo.locksuite/.receiver.DeviceAdminReceiver
echo.

:: ─── Activar Accesibilidad y Permisos de Sistema ─────────────
echo [*] Habilitando Servicio de Accesibilidad y permisos...
%ADB% shell appops set com.ejemplo.locksuite ACCESS_RESTRICTED_SETTINGS allow
%ADB% shell settings put secure enabled_accessibility_services com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService
%ADB% shell settings put secure accessibility_enabled 1
%ADB% shell pm grant com.ejemplo.locksuite android.permission.WRITE_SECURE_SETTINGS
echo.

:: ─── Limpiar APK temporal ────────────────────────────────
del "%APK_FILE%" >nul 2>&1

echo.
echo ╔══════════════════════════════════════════════════════╗
echo ║        ✓  INSTALACION COMPLETADA CON EXITO!         ║
echo ║                                                      ║
echo ║  LockSuite esta instalado como Device Owner.        ║
echo ║  Ya podes desconectar el cable USB.                 ║
echo ╚══════════════════════════════════════════════════════╝
echo.
pause
