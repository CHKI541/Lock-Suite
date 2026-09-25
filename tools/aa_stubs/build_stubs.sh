#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Arma los dos "stubs" que la pantalla de Android Auto "Descargá apps de Google
# Play" pide para dejar seguir: Google Maps y la App de Google. Ver
# LOCKSUITE_CONTEXTO_PARA_IA.md, B.88.
#
# Un stub es una app VACÍA que solo lleva el nombre de paquete:
#   · android:hasCode="false" y sin classes.dex → no puede ejecutar nada.
#   · Sin activities, services, receivers ni providers → no aparece en ningún
#     lanzador, no aparece en la pantalla del auto, no se conecta a nada.
#   · versionCode 2100000000 → Android Auto nunca pide "actualizar", y Play Store
#     nunca intenta reemplazarla (además la firma no coincide).
#   · Etiqueta honesta ("Stub de ... (LockSuite)"), para que nadie la confunda con
#     la app real en Ajustes ni en el panel.
#
# Android Auto solo verifica que el paquete EXISTA: los stubs de la comunidad
# (aa4mg, SolidEva/android-auto-stub, rik-shaw/aa-stubs) son exactamente esto —
# un <application/> vacío — y se midió el 25/9 con aapt2.
#
# LA CLAVE PRIVADA NO SE GUARDA: se genera en una carpeta temporal y se borra al
# salir. Nadie —ni nosotros— puede firmar nunca una "actualización" de estos
# paquetes. (El stub de Maps que circula en la comunidad está firmado con la
# clave de PRUEBA pública de Android: cualquiera podría reemplazarlo.)
#
# ⚠️ Volver a correr este script genera una clave NUEVA: hay que copiar la huella
# que imprime a AndroidAutoStubs.CERT_SHA256 (app/.../mdm/AndroidAutoStubs.kt) y
# reinstalar los stubs en los equipos (desinstalar los viejos primero: la firma
# cambia). LockSuite solo protege los stubs cuya firma reconoce.
#
# Uso (contenedor de IA, con el SDK de tools/ia_contenedor/setup_sdk.sh):
#   bash tools/aa_stubs/build_stubs.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK=${ANDROID_SDK_ROOT:-/opt/android-sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM_JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
OUT="$HERE/apk"
VERSION_CODE=2100000000
VERSION_NAME="stub-locksuite-1"
MIN_SDK=24
TARGET_SDK=36

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT          # se lleva la clave privada con él
mkdir -p "$OUT"

PASS=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
keytool -genkeypair -keystore "$WORK/stub.p12" -storetype PKCS12 \
    -storepass "$PASS" -keypass "$PASS" -alias stub \
    -keyalg RSA -keysize 3072 -validity 36500 \
    -dname "CN=LockSuite Android Auto stub, O=LockSuite" >/dev/null 2>&1

armar() {  # $1 paquete  $2 etiqueta  $3 archivo de salida
    local dir="$WORK/$3"
    mkdir -p "$dir"
    cat > "$dir/AndroidManifest.xml" <<MANIFEST
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$1">
    <application
        android:label="$2"
        android:hasCode="false"
        android:allowBackup="false" />
</manifest>
MANIFEST
    "$BT/aapt2" link -o "$dir/sin_alinear.apk" -I "$PLATFORM_JAR" \
        --manifest "$dir/AndroidManifest.xml" \
        --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
        --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
    "$BT/zipalign" -p -f 4 "$dir/sin_alinear.apk" "$dir/alineado.apk"
    "$BT/apksigner" sign --ks "$WORK/stub.p12" --ks-pass "pass:$PASS" \
        --ks-key-alias stub --out "$OUT/$3" "$dir/alineado.apk"
    "$BT/apksigner" verify "$OUT/$3"
}

armar com.google.android.apps.maps          "Stub de Maps para Android Auto (LockSuite)"  aa-stub-maps.apk
armar com.google.android.googlequicksearchbox "Stub de Google para Android Auto (LockSuite)" aa-stub-google.apk
rm -f "$OUT"/*.idsig

echo "── Huella del certificado (va en AndroidAutoStubs.CERT_SHA256) ──"
"$BT/apksigner" verify --print-certs "$OUT/aa-stub-maps.apk" | grep "SHA-256 digest" | sed 's/.*: //'
echo "── SHA-256 de cada APK (para la Tienda) ──"
( cd "$OUT" && sha256sum aa-stub-maps.apk aa-stub-google.apk )
