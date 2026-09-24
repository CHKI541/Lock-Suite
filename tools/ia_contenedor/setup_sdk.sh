#!/bin/bash
# Instala el Android SDK mínimo para compilar :app en el contenedor Linux de una sesión
# de IA. NO se usa en la PC del dueño (ahí está Android Studio). Ver README.md.
set -e
# La carpeta del script se resuelve ANTES del `cd` de abajo: `$0` suele ser relativo
# (`bash tools/ia_contenedor/setup_sdk.sh`) y después del `cd` ya no apunta a nada.
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK=${ANDROID_SDK_ROOT:-/opt/android-sdk}
mkdir -p "$SDK" && cd "$SDK"
if [ ! -x cmdline-tools/latest/bin/sdkmanager ]; then
  curl -sSL -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
  mkdir -p cmdline-tools && unzip -q -o cmdtools.zip -d cmdline-tools
  mv cmdline-tools/cmdline-tools cmdline-tools/latest 2>/dev/null || true
  rm -f cmdtools.zip
fi
yes | cmdline-tools/latest/bin/sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
cmdline-tools/latest/bin/sdkmanager --sdk_root="$SDK" "platforms;android-36" "build-tools;36.0.0" | tail -3
# Maven Central devuelve 429 desde la IP de salida del contenedor: espejo de Google.
mkdir -p ~/.gradle/init.d
cp "$HERE/central-mirror.gradle" ~/.gradle/init.d/
echo "SDK listo en $SDK"
