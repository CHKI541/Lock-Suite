#!/bin/bash
# Arma una copia del panel con Firebase FALSO (en memoria, sin red) y corre las pruebas
# de comportamiento de tools/panel_render/. Ver README.md.
#
#   bash tools/panel_render/run.sh            # copia en una carpeta temporal
#   bash tools/panel_render/run.sh /tmp/pr    # o en la que digas
set -e
AQUI="$(cd "$(dirname "$0")" && pwd)"
RAIZ="$(cd "$AQUI/../.." && pwd)"
DEST="${1:-$(mktemp -d)}"
mkdir -p "$DEST"
PUB="$RAIZ/admin-backend/public"
cp "$PUB"/*.html "$PUB"/*.js "$PUB"/*.css "$DEST"/
cp -r "$PUB/images" "$DEST"/ 2>/dev/null || true
cp "$AQUI"/fake-firebase.js "$AQUI"/fake-function.js "$AQUI"/test_panel.js "$AQUI"/test_ficha.js "$DEST"/
# El firebase-config.js real apunta al proyecto de verdad: acá se reemplaza.
echo "firebase.initializeApp({});" > "$DEST/firebase-config.js"
python3 - "$DEST" <<'PY'
import re, sys, os
d = sys.argv[1]
for f in ("index.html", "celular.html", "dominios.html"):
    p = os.path.join(d, f)
    s = open(p, encoding="utf-8").read()
    # Sin los SDK de gstatic (no hay red); en su lugar, el Firebase falso.
    s = re.sub(r'<script src="https://www\.gstatic\.com/firebasejs/[^"]+"></script>\s*', "", s)
    extra = '<script src="fake-firebase.js"></script>\n  <script src="firebase-config.js"></script>'
    if f != "dominios.html":
        extra += '\n  <script src="fake-function.js"></script>'
    s = s.replace('<script src="firebase-config.js"></script>', extra, 1)
    open(p, "w", encoding="utf-8").write(s)
PY
cd "$DEST"
echo "== Barra lateral (index.html + app.js)"
node test_panel.js
echo "== Ficha completa (celular.html + comun.js + celular.js)"
node test_ficha.js
