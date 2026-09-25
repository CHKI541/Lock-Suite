#!/usr/bin/env python3
"""
Verifica que los stubs de Android Auto del repo sean los que LockSuite espera (B.88).

Qué controla, sin nada instalado más que Python (anda en la PC del dueño):
  1. Que el SHA-256 de cada archivo en tools/aa_stubs/apk/ coincida con
     AndroidAutoStubs.APK_SHA256. Es lo que la Tienda compara para dejarlos instalar
     sin pedirlos: si alguien regenera los stubs (clave nueva) y no actualiza el
     código, esto falla.
  2. Que sigan VACÍOS: ningún classes*.dex adentro, y solo el manifiesto, la tabla de
     recursos y la firma.

La huella del CERTIFICADO (AndroidAutoStubs.CERT_SHA256) la prueba
AndroidAutoStubsTest contra el certificado real del stub.

Uso:  python tools/aa_stubs/check_stubs.py
"""
import hashlib
import pathlib
import re
import sys
import zipfile

RAIZ = pathlib.Path(__file__).resolve().parents[2]
KOTLIN = RAIZ / "app/src/main/java/com/ejemplo/locksuite/mdm/AndroidAutoStubs.kt"
APKS = {
    "PKG_MAPS": RAIZ / "tools/aa_stubs/apk/aa-stub-maps.apk",
    "PKG_GOOGLE_APP": RAIZ / "tools/aa_stubs/apk/aa-stub-google.apk",
}
PERMITIDOS = {"AndroidManifest.xml", "resources.arsc"}


def main() -> int:
    fuente = KOTLIN.read_text(encoding="utf-8")
    errores = []
    for constante, apk in APKS.items():
        m = re.search(constante + r'\s+to\s+"([0-9a-f]{64})"', fuente)
        if not m:
            errores.append(f"{constante}: no encuentro su huella en {KOTLIN.name}")
            continue
        if not apk.exists():
            errores.append(f"{apk.name}: no existe")
            continue
        real = hashlib.sha256(apk.read_bytes()).hexdigest()
        if real != m.group(1):
            errores.append(f"{apk.name}: sha256 {real} != fijado en el código {m.group(1)}")
        with zipfile.ZipFile(apk) as z:
            extras = [n for n in z.namelist() if n not in PERMITIDOS and not n.startswith("META-INF/")]
            if extras:
                errores.append(f"{apk.name}: NO está vacío, trae {extras}")
        print(f"{apk.name}: sha256 {real}")

    if errores:
        print("\nROJO:")
        for e in errores:
            print("  -", e)
        return 1
    print("\nVERDE: los dos stubs coinciden con el código y están vacíos.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
