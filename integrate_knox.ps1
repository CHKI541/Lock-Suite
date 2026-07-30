# Script para automatizar la integración de Samsung Knox SDK en LockSuite MDM
# Creado por Antigravity

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   INTEGRACIÓN AUTOMÁTICA DE SAMSUNG KNOX EN LOCKSUITE    " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Definir rutas
$projectRoot = Get-Location
$libsDir = Join-Path $projectRoot "app\libs"
$hardeningFile = Join-Path $projectRoot "app\src\main\java\com\ejemplo\locksuite\mdm\KnoxHardening.kt"

# 2. Verificar archivos JAR
$knoxJarName = "knoxsdk.jar"
$supportJarName = "supportlib.jar"

$knoxJarLocalPath = Join-Path $libsDir $knoxJarName
$supportJarLocalPath = Join-Path $libsDir $supportJarName

if (-not (Test-Path $libsDir)) {
    New-Item -ItemType Directory -Path $libsDir | Out-Null
    Write-Host "[*] Creado directorio app/libs/" -ForegroundColor Gray
}

# Buscar si los JARs ya están en libs/
$hasJars = (Test-Path $knoxJarLocalPath) -and (Test-Path $supportJarLocalPath)

if (-not $hasJars) {
    Write-Host "[!] No se encontraron $knoxJarName y/o $supportJarName en app/libs/." -ForegroundColor Yellow
    Write-Host "Por favor, especifica dónde descargaste los archivos .jar de Samsung Knox."
    Write-Host "(Puedes arrastrar y soltar la carpeta o el archivo aquí, o presionar ENTER para buscarlos en la carpeta actual)"
    
    $searchPath = Read-Host "Ruta de búsqueda"
    $searchPath = $searchPath.Trim().Trim('"').Trim("'")
    
    if ([string]::IsNullOrWhiteSpace($searchPath)) {
        $searchPath = $projectRoot.Path
    }
    
    # Intentar buscar en la ruta especificada de forma recursiva
    Write-Host "[*] Buscando archivos JAR en: $searchPath ..." -ForegroundColor Gray
    
    $foundKnox = Get-ChildItem -Path $searchPath -Filter $knoxJarName -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    $foundSupport = Get-ChildItem -Path $searchPath -Filter $supportJarName -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    
    if ($foundKnox -and $foundSupport) {
        Write-Host "[+] Archivos encontrados!" -ForegroundColor Green
        Write-Host "    Knox SDK: $($foundKnox.FullName)" -ForegroundColor Gray
        Write-Host "    Support Lib: $($foundSupport.FullName)" -ForegroundColor Gray
        
        Copy-Item -Path $foundKnox.FullName -Destination $knoxJarLocalPath -Force
        Copy-Item -Path $foundSupport.FullName -Destination $supportJarLocalPath -Force
        Write-Host "[+] Archivos copiados exitosamente a app/libs/" -ForegroundColor Green
        $hasJars = $true
    } else {
        Write-Host "[-] ERROR: No se pudieron encontrar los archivos '$knoxJarName' y '$supportJarName'." -ForegroundColor Red
        Write-Host "Sigue estos pasos antes de volver a ejecutar este script:" -ForegroundColor Yellow
        Write-Host "  1. Registrate gratis en https://developer.samsungknox.com/"
        Write-Host "  2. Entra al Knox Partner Program."
        Write-Host "  3. Descarga el 'Knox SDK' (nivel Standard/Enterprise) y copia 'knoxsdk.jar' y 'supportlib.jar' a la carpeta 'app/libs/' de este proyecto."
        Write-Host ""
        Exit
    }
} else {
    Write-Host "[+] Los archivos JAR ya se encuentran en app/libs/." -ForegroundColor Green
}

# 3. Descomentar código en KnoxHardening.kt
if (Test-Path $hardeningFile) {
    Write-Host "[*] Modificando KnoxHardening.kt para activar las llamadas a Knox..." -ForegroundColor Gray
    
    $content = [System.IO.File]::ReadAllText($hardeningFile, [System.Text.Encoding]::UTF8)
    
    # Reemplazo 1: activateLicense
    $target1 = @"
        if (!isSamsung()) return
        Log.i(TAG, "Knox SDK todavia no integrado (ver KnoxHardening.kt) — omitiendo activacion de licencia.")

        // -- Descomentar una vez agregado app/libs/knoxsdk.jar + supportlib.jar --
        // try {
        //     val licenseKey = context.getString(com.ejemplo.locksuite.R.string.knox_license_key)
        //     if (licenseKey.isBlank() || licenseKey == "TU_LICENCIA_KPE_STANDARD_ACA") {
        //         Log.w(TAG, "Falta configurar knox_license_key en strings.xml.")
        //         return
        //     }
        //     com.samsung.android.knox.license.KnoxEnterpriseLicenseManager
        //         .getInstance(context)
        //         .activateLicense(licenseKey)
        // } catch (e: Exception) {
        //     Log.w(TAG, "No se pudo activar la licencia Knox.", e)
        // }
"@

    $replacement1 = @"
        if (!isSamsung()) return
        try {
            val licenseKey = context.getString(com.ejemplo.locksuite.R.string.knox_license_key)
            if (licenseKey.isBlank() || licenseKey == "TU_LICENCIA_KPE_STANDARD_ACA") {
                Log.w(TAG, "Falta configurar knox_license_key en strings.xml.")
                return
            }
            com.samsung.android.knox.license.KnoxEnterpriseLicenseManager
                .getInstance(context)
                .activateLicense(licenseKey)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo activar la licencia Knox.", e)
        }
"@

    # Reemplazo 2: setFactoryResetBlocked
    $target2 = @"
    fun setFactoryResetBlocked(context: Context, block: Boolean): Boolean {
        if (!isSamsung()) return false
        Log.i(TAG, "Knox SDK todavia no integrado — setFactoryResetBlocked(`$block) no tiene efecto todavia.")
        return false

        // -- Descomentar junto con lo anterior; borrar el "return false" de arriba --
        // return try {
        //     com.samsung.android.knox.EnterpriseDeviceManager.getInstance(context)
        //         .restrictionPolicy
        //         .allowFactoryReset(!block)
        //     true
        // } catch (e: Exception) {
        //     Log.w(TAG, "Knox allowFactoryReset fallo (¿licencia no activada o falta Device Owner?)", e)
        //     false
        // }
    }
"@

    $replacement2 = @"
    fun setFactoryResetBlocked(context: Context, block: Boolean): Boolean {
        if (!isSamsung()) return false
        return try {
            com.samsung.android.knox.EnterpriseDeviceManager.getInstance(context)
                .restrictionPolicy
                .allowFactoryReset(!block)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Knox allowFactoryReset fallo (¿licencia no activada o falta Device Owner?)", e)
            false
        }
    }
"@

    # Reemplazo 3: setFlashingBlocked
    $target3 = @"
    fun setFlashingBlocked(context: Context, block: Boolean): Boolean {
        if (!isSamsung()) return false
        Log.i(TAG, "Knox SDK todavia no integrado — setFlashingBlocked(`$block) no tiene efecto todavia.")
        return false

        // -- Descomentar junto con lo anterior; borrar el "return false" de arriba --
        // return try {
        //     com.samsung.android.knox.EnterpriseDeviceManager.getInstance(context)
        //         .restrictionPolicy
        //         .allowFirmwareRecovery(!block)
        //     true
        // } catch (e: Exception) {
        //     Log.w(TAG, "Knox allowFirmwareRecovery fallo (¿licencia no activada o falta Device Owner?)", e)
        //     false
        // }
    }
"@

    $replacement3 = @"
    fun setFlashingBlocked(context: Context, block: Boolean): Boolean {
        if (!isSamsung()) return false
        return try {
            com.samsung.android.knox.EnterpriseDeviceManager.getInstance(context)
                .restrictionPolicy
                .allowFirmwareRecovery(!block)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Knox allowFirmwareRecovery fallo (¿licencia no activada o falta Device Owner?)", e)
            false
        }
    }
"@

    # Normalizar retornos de carro a CRLF para Windows
    $content = $content -replace "`r`n", "`n" -replace "`n", "`r`n"
    $target1 = $target1 -replace "`r`n", "`n" -replace "`n", "`r`n"
    $replacement1 = $replacement1 -replace "`r`n", "`n" -replace "`n", "`r`n"
    $target2 = $target2 -replace "`r`n", "`n" -replace "`n", "`r`n"
    $replacement2 = $replacement2 -replace "`r`n", "`n" -replace "`n", "`r`n"
    $target3 = $target3 -replace "`r`n", "`n" -replace "`n", "`r`n"
    $replacement3 = $replacement3 -replace "`r`n", "`n" -replace "`n", "`r`n"

    $modified = $false

    if ($content.Contains($target1)) {
        $content = $content.Replace($target1, $replacement1)
        $modified = $true
    }
    if ($content.Contains($target2)) {
        $content = $content.Replace($target2, $replacement2)
        $modified = $true
    }
    if ($content.Contains($target3)) {
        $content = $content.Replace($target3, $replacement3)
        $modified = $true
    }

    if ($modified) {
        [System.IO.File]::WriteAllText($hardeningFile, $content, [System.Text.Encoding]::UTF8)
        Write-Host "[+] KnoxHardening.kt actualizado exitosamente." -ForegroundColor Green
    } else {
        Write-Host "[!] Advertencia: No se encontraron los bloques comentados en KnoxHardening.kt." -ForegroundColor Yellow
        Write-Host "Es posible que el archivo ya esté descomentado o haya sido modificado manualmente." -ForegroundColor Yellow
    }
} else {
    Write-Host "[-] ERROR: No se encontró el archivo $hardeningFile." -ForegroundColor Red
    Exit
}

# 4. Instrucciones finales
Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "         ¡INTEGRACIÓN DE CÓDIGO COMPLETADA!               " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Siguientes pasos obligatorios para que funcione:" -ForegroundColor Yellow
Write-Host "1. Configurar la Licencia Knox:"
Write-Host "   - Ve a: app/src/main/res/values/strings.xml"
Write-Host "   - Reemplaza 'TU_LICENCIA_KPE_STANDARD_ACA' con tu clave de licencia KPE Standard gratuita."
Write-Host "     (Obtenla en: https://partner.samsungknox.com/ en el dashboard de KPE)"
Write-Host ""
Write-Host "2. Probar la compilación de la APK:"
Write-Host "   - Abre el proyecto en Android Studio."
Write-Host "   - Ejecuta 'Sync Project with Gradle Files' (Sincronizar Gradle)."
Write-Host "   - Compila la aplicación. Gradle detectará automáticamente los .jar en app/libs y compilará la versión activa."
Write-Host ""
Write-Host "3. Desplegar y Verificar en un Dispositivo Samsung Físico:"
Write-Host "   - Instala la APK compilada en un equipo Samsung real que tenga soporte para Knox."
Write-Host "   - La app debe estar configurada como Device Owner."
Write-Host "   - Activa los switches desde el panel web de Firebase o en el Dashboard de la app:"
Write-Host "     * Bloqueo de restablecimiento por recovery."
Write-Host "     * Bloqueo de flasheo por Odin."
Write-Host "   - Reinicia el equipo y comprueba en el menú recovery y modo descarga (Odin) si los bloqueos se aplican correctamente."
Write-Host ""
Write-Host "¡Listo! Ya tienes todo configurado de tu lado en el código." -ForegroundColor Green
Write-Host "Presiona cualquier tecla para finalizar..."
$null = [System.Console]::ReadKey($true)
