#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Chequeo de simetría del modo lista blanca (8/9/2026).

POR QUÉ EXISTE
==============
El catálogo de la lista blanca vive en DOS lenguajes:

  · `app/src/main/java/com/ejemplo/locksuite/mdm/WhitelistCatalog.kt` — la fuente
    de verdad: es la que decide qué dominio resuelve en el celular.
  · `admin-backend/public/app.js` (`WHITELIST_BUILTIN`) — la copia que el panel
    necesita para dibujar las tarjetas de permitir/prohibir.

Los DOMINIOS no se duplican a propósito (el panel manda solo la decisión por app;
los dominios los pone el equipo). Pero el nombre, el paquete y la cantidad sí, y
eso se desincroniza solo en cuanto alguien agregue una app en un lado y se olvide
del otro. El modo de falla es feo y silencioso: **un paquete mal escrito en el
panel escribe una decisión que ningún equipo va a aplicar nunca**, y el panel
muestra la app en verde igual.

Es el mismo tipo de defecto que B.28 (el panel mandaba `no_apps_control`, que no
existe en Android, y Android lo aceptaba sin hacer nada) y el mismo tipo de
chequeo que en B.38 encontró un bug real de verdad.

CÓMO SE USA
===========
    python tools/check_whitelist_sync.py

Sale con código 0 si están alineados y 1 si no, así se puede meter en el flujo de
despliegue antes de `deploy_all.ps1`.
"""

import io
import json
import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(RAIZ, "app", "src", "main", "java", "com", "ejemplo",
                  "locksuite", "mdm", "WhitelistCatalog.kt")
JS = os.path.join(RAIZ, "admin-backend", "public", "app.js")


def sin_comentarios(texto):
    """Saca los // ... para no contar dominios que están comentados."""
    return re.sub(r"//[^\n]*", "", texto)


def lista_kotlin(cuerpo, campo):
    """Cuenta las cadenas de `campo = listOf( … )` respetando paréntesis anidados."""
    i = cuerpo.find(campo + " = listOf(")
    if i < 0:
        return []
    i = cuerpo.index("(", i + len(campo))
    profundidad, j = 0, i
    while j < len(cuerpo):
        if cuerpo[j] == "(":
            profundidad += 1
        elif cuerpo[j] == ")":
            profundidad -= 1
            if profundidad == 0:
                break
        j += 1
    return re.findall(r'"([^"]+)"', sin_comentarios(cuerpo[i:j]))


def leer_catalogo_kotlin():
    src = io.open(KT, encoding="utf-8").read()
    cuerpo = src[src.index("val APPS: List<Entry> = listOf("):src.index("private val BY_PACKAGE")]
    apps = {}
    for parte in cuerpo.split("Entry(")[1:]:
        pkg = re.search(r'packageName = "([^"]+)"', parte).group(1)
        label = re.search(r'label = "([^"]*)"', parte).group(1)
        apps[pkg] = {
            "label": label,
            "allow": lista_kotlin(parte, "allow"),
            "block": lista_kotlin(parte, "block"),
        }
    return apps


def leer_catalogo_panel():
    src = io.open(JS, encoding="utf-8").read()
    ini = src.index("const WHITELIST_BUILTIN = [")
    bloque = src[ini:src.index("];", ini)]
    apps = {}
    for m in re.finditer(
        r'\{\s*pkg:\s*"([^"]+)",\s*label:\s*"([^"]*)",\s*allow:\s*(\d+),\s*block:\s*(\d+)\s*\}',
        bloque,
    ):
        apps[m.group(1)] = {
            "label": m.group(2),
            "allow": int(m.group(3)),
            "block": int(m.group(4)),
        }
    return apps


def main():
    kt = leer_catalogo_kotlin()
    js = leer_catalogo_panel()
    problemas = []

    for pkg, datos in kt.items():
        if pkg not in js:
            problemas.append(
                "FALTA en el panel: %s (%s). El panel no lo va a poder marcar." % (pkg, datos["label"])
            )
            continue
        p = js[pkg]
        if p["label"] != datos["label"]:
            problemas.append(
                'ETIQUETA distinta en %s: kotlin="%s" panel="%s"' % (pkg, datos["label"], p["label"])
            )
        if p["allow"] != len(datos["allow"]) or p["block"] != len(datos["block"]):
            problemas.append(
                "CANTIDADES distintas en %s: kotlin allow=%d block=%d | panel allow=%d block=%d"
                % (pkg, len(datos["allow"]), len(datos["block"]), p["allow"], p["block"])
            )

    for pkg in js:
        if pkg not in kt:
            problemas.append(
                "SOBRA en el panel: %s. El panel lo ofrece y ningún equipo lo conoce." % pkg
            )

    # Un dominio permitido y prohibido a la vez dentro de la MISMA app es casi
    # siempre un dedazo: el Trie lo resuelve (gana el bloqueo, que se escribe
    # después) pero el resultado no es el que quien lo escribió esperaba.
    for pkg, datos in kt.items():
        repetidos = set(datos["allow"]) & set(datos["block"])
        if repetidos:
            problemas.append(
                "%s tiene el mismo dominio en allow y en block: %s" % (pkg, ", ".join(sorted(repetidos)))
            )

    # Un dominio de la lista de infraestructura repetido dentro de una app no
    # rompe nada, pero es ruido: la infraestructura ya lo permite para todos.
    src = io.open(KT, encoding="utf-8").read()
    infra = set(lista_kotlin(src[src.index("val INFRASTRUCTURE"):src.index("val BLOCK_ALWAYS")],
                             "INFRASTRUCTURE: List<String>"))
    for pkg, datos in kt.items():
        redundantes = set(datos["allow"]) & infra
        if redundantes:
            problemas.append(
                "AVISO (no rompe): %s repite dominios que ya están en infraestructura: %s"
                % (pkg, ", ".join(sorted(redundantes)))
            )

    print("Catálogo Kotlin: %d apps · panel: %d apps" % (len(kt), len(js)))
    if not problemas:
        print("OK — los dos catálogos dicen lo mismo.")
        return 0
    for p in problemas:
        print("  " + p)
    graves = [p for p in problemas if not p.startswith("AVISO")]
    return 1 if graves else 0


if __name__ == "__main__":
    sys.exit(main())
