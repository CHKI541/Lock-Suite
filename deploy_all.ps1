param(
    [string]$VersionName
)

$ErrorActionPreference = "Stop"

# Rutas de archivos
$projectRoot = Get-Location
$gradleFile = Join-Path $projectRoot "app/build.gradle.kts"
$versionJsonFile = Join-Path $projectRoot "admin-backend/public/version.json"
$serviceAccountKey = "C:\Users\israe\OneDrive\Documentos\Lock Suite segunda version\admin-backend\locksuite-nueva-firebase-adminsdk-fbsvc-dac7996bff.json"

Write-Host "=== Iniciando automatizacion de despliegue para Lock Suite ===" -ForegroundColor Cyan

# 1. Leer version actual
if (-not (Test-Path $gradleFile)) {
    Write-Error "No se encontro app/build.gradle.kts. Asegurate de estar en la raiz del proyecto."
}

$gradleContent = Get-Content $gradleFile -Raw
$versionCodeMatch = [regex]::Match($gradleContent, 'versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($gradleContent, 'versionName\s*=\s*"([^"]+)"')

if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    Write-Error "No se pudo extraer la version actual de build.gradle.kts."
}

$currentCode = [int]$versionCodeMatch.Groups[1].Value
$currentName = $versionNameMatch.Groups[1].Value
$newCode = $currentCode + 1

if ([string]::IsNullOrEmpty($VersionName)) {
    # Autoincrementar patch version (ej: 0.4.8 -> 0.4.9)
    $parts = $currentName.Split('.')
    if ($parts.Length -eq 3) {
        $patch = [int]$parts[2] + 1
        $VersionName = "$($parts[0]).$($parts[1]).$patch"
    } else {
        $VersionName = "$currentName.1"
    }
}

Write-Host "Version actual: $currentName (Codigo: $currentCode)"
Write-Host "Nueva version a aplicar: $VersionName (Codigo: $newCode)" -ForegroundColor Green

# 2. Actualizar build.gradle.kts
$gradleContent = $gradleContent -replace 'versionCode\s*=\s*\d+', "versionCode = $newCode"
$gradleContent = $gradleContent -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$VersionName`""
Set-Content $gradleFile $gradleContent

# 3. Actualizar version.json
if (Test-Path $versionJsonFile) {
    $timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $jsonContent = Get-Content $versionJsonFile -Raw | ConvertFrom-Json
    $jsonContent.versionCode = $newCode
    $jsonContent.versionName = $VersionName
    $jsonContent.updatedAt = $timestamp
    $jsonContent | ConvertTo-Json -Depth 10 | Set-Content $versionJsonFile
    Write-Host "version.json actualizado." -ForegroundColor Green
} else {
    Write-Warning "No se encontro version.json para actualizar."
}

# 4. Compilar APK
Write-Host "Compilando release APK..." -ForegroundColor Yellow
cmd.exe /c .\gradlew.bat assembleRelease --no-daemon

# 5. Copiar APKs
$apkSource = Join-Path $projectRoot "app/build/outputs/apk/release/app-release.apk"
$apkDest1 = Join-Path $projectRoot "admin-backend/public/locksuite-latest.apk"
$apkDest2 = Join-Path $projectRoot "admin-backend/public/LockSuite_MDM.apk"

if (Test-Path $apkSource) {
    Copy-Item $apkSource $apkDest1 -Force
    Copy-Item $apkSource $apkDest2 -Force
    Write-Host "APKs copiadas con exito a public/." -ForegroundColor Green
} else {
    Write-Error "No se encontro el APK compilado en $apkSource"
}

# 6. Desplegar en Firebase
Write-Host "Desplegando en Firebase..." -ForegroundColor Yellow
if (-not (Test-Path $serviceAccountKey)) {
    Write-Error "No se encontro la credencial de Firebase en $serviceAccountKey"
}

$env:GOOGLE_APPLICATION_CREDENTIALS = $serviceAccountKey
Push-Location (Join-Path $projectRoot "admin-backend")
try {
    firebase deploy --only functions,hosting,database
    Write-Host "Firebase desplegado con exito." -ForegroundColor Green
} finally {
    Pop-Location
}

# 7. Subir a GitHub
Write-Host "Subiendo cambios a GitHub..." -ForegroundColor Yellow
git add .
git commit -m "Actualizacion automatica a version $VersionName (Codigo $newCode)"
git push
Write-Host "Despliegue y actualizacion completados con exito a GitHub y Firebase!" -ForegroundColor Green
