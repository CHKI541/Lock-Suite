$env:SETUPTOOLS_USE_DISTUTILS = "stdlib"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

Write-Host "Limpiando compilaciones anteriores..." -ForegroundColor Cyan
if (Test-Path "build") { Remove-Item -Recurse -Force "build" }
if (Test-Path "dist") { Remove-Item -Recurse -Force "dist" }

Write-Host "Compilando LockSuite_Instalador_Portable.exe con PyInstaller..." -ForegroundColor Yellow
pyinstaller --noconfirm `
    --onefile `
    --windowed `
    --add-data "bin;bin" `
    --collect-all customtkinter `
    --name "LockSuite_Instalador_Portable" `
    main.py

if (Test-Path "dist\LockSuite_Instalador_Portable.exe") {
    Write-Host "Compilacion exitosa!" -ForegroundColor Green
    Get-Item "dist\LockSuite_Instalador_Portable.exe" | Select-Object FullName, Length, LastWriteTime
} else {
    Write-Host "Error: No se encontro el archivo compilado." -ForegroundColor Red
}
