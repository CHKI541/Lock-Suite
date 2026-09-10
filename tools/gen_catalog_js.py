#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GENERADOR DE `admin-backend/public/catalog.js` (10/9/2026).

POR QUÉ EXISTE
==============
Pedido del dueño, textual: *"necesito que me armes una sección también para decidir
de forma global qué dominios son kosher y cuáles no en cada app (poné lo que ya
hiciste, así puedo corregir si te equivocaste)"*.

Para dibujar esa sección el panel necesita los DOMINIOS del catálogo de fábrica, y
hasta hoy no los tenía: B.53 decidió a propósito no duplicarlos (*"los dominios no
se duplican — el panel manda solo la decisión"*). Esa decisión era correcta cuando
el panel solo tenía que mostrar un botón permitir/prohibir por app. Deja de serlo
cuando el panel tiene que mostrar **qué dominios** tiene cada app para poder
corregirlos: sin la lista, el editor estaría vacío y el dueño no podría ver lo que
va a corregir.

LA ALTERNATIVA QUE NO SE ELIGIÓ, Y POR QUÉ
===========================================
Se podría haber hecho que el celular publique su catálogo a Firebase. No se hizo:
haría falta un equipo en línea y sincronizado para poder abrir el editor, el dato
viajaría por los datos móviles del usuario final, y quedarían tantas copias del
catálogo como equipos en la flota — con la pregunta de cuál gana cuando difieran.
Generarlo en tiempo de despliegue no tiene ninguno de esos problemas: es
determinista, no necesita ningún equipo, y por construcción no puede diferir de lo
que compila la app.

EL KOTLIN SIGUE SIENDO LA FUENTE DE VERDAD. Este archivo no la reemplaza: la copia
en un formato que el navegador pueda leer. Si alguien edita `catalog.js` a mano, el
chequeo de simetría (`tools/check_whitelist_sync.py`) falla y el despliegue se para
— es el mismo patrón de B.38, B.53 y B.57, y está acá por el mismo motivo: este
proyecto ya pagó cuatro veces el bug de "una clave que se escribe en un lenguaje y
se lee en otro, y nadie avisa cuando dejan de coincidir" (B.28 `no_apps_control`,
B.38 `DISALLOW_CONFIG_DATE_TIME`, B.40 p.8, B.57 `captivePortalCoverImages`).

CÓMO SE USA
===========
    python tools/gen_catalog_js.py            # escribe admin-backend/public/catalog.js
    python tools/gen_catalog_js.py --check    # NO escribe; sale 1 si el archivo
                                              # en disco no coincide con el Kotlin

Correrlo antes de `deploy_all.ps1`, junto con los otros tres chequeos.
"""

import json
import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOTLIN = os.path.join(RAIZ, "app", "src", "main", "java", "com", "ejemplo",
                      "locksuite", "mdm", "WhitelistCatalog.kt")
SALIDA = os.path.join(RAIZ, "admin-backend", "public", "catalog.js")


def sin_comentarios(texto):
    """Saca comentarios de línea y de bloque sin tocar el contenido de las cadenas.

    Hacerlo con una expresión regular sola es un error clásico: `"// no es un
    comentario"` vive adentro de una cadena y una regex ingenua se lo come,
    corriendo todo lo que sigue. Se recorre carácter por carácter, que para un
    archivo de 25 KB es instantáneo y no tiene ese problema.
    """
    out = []
    i = 0
    n = len(texto)
    while i < n:
        c = texto[i]
        if c == '"':
            # cadena: copiar tal cual hasta la comilla de cierre no escapada
            out.append(c)
            i += 1
            while i < n:
                if texto[i] == "\\":
                    out.append(texto[i:i + 2])
                    i += 2
                    continue
                out.append(texto[i])
                if texto[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "/" and i + 1 < n and texto[i + 1] == "/":
            while i < n and texto[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and texto[i + 1] == "*":
            j = texto.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def lista_kotlin(texto, nombre):
    """Extrae `val NOMBRE: List<String> = listOf("a", "b", …)` balanceando paréntesis."""
    m = re.search(r"\bval\s+" + re.escape(nombre) + r"\s*:[^=]*=\s*(listOf|setOf)\s*\(", texto)
    if not m:
        raise SystemExit("No se encontró la lista %s en WhitelistCatalog.kt" % nombre)
    i = texto.index("(", m.end() - 1)
    prof = 0
    j = i
    while j < len(texto):
        if texto[j] == "(":
            prof += 1
        elif texto[j] == ")":
            prof -= 1
            if prof == 0:
                break
        j += 1
    return re.findall(r'"([^"]*)"', texto[i:j])


def entradas_kotlin(texto):
    """Extrae las entradas `Entry( packageName = …, label = …, allow = …, block = …, note = … )`.

    Se balancean paréntesis en vez de cortar con una regex hasta `),`: varias
    entradas tienen `listOf(` adentro y un `),` intermedio partiría la entrada al
    medio, dejando dominios afuera sin que nada avise.
    """
    salida = []
    for m in re.finditer(r"\bEntry\s*\(", texto):
        i = texto.index("(", m.end() - 1)
        prof = 0
        j = i
        while j < len(texto):
            if texto[j] == "(":
                prof += 1
            elif texto[j] == ")":
                prof -= 1
                if prof == 0:
                    break
            j += 1
        cuerpo = texto[i + 1:j]

        pkg = re.search(r'packageName\s*=\s*"([^"]+)"', cuerpo)
        label = re.search(r'label\s*=\s*"([^"]*)"', cuerpo)
        if not pkg:
            continue

        def campo(nombre):
            mm = re.search(r"\b" + nombre + r"\s*=\s*listOf\s*\(", cuerpo)
            if not mm:
                return []
            a = cuerpo.index("(", mm.end() - 1)
            p = 0
            b = a
            while b < len(cuerpo):
                if cuerpo[b] == "(":
                    p += 1
                elif cuerpo[b] == ")":
                    p -= 1
                    if p == 0:
                        break
                b += 1
            return re.findall(r'"([^"]*)"', cuerpo[a:b])

        nota = re.search(r'note\s*=\s*"((?:[^"\\]|\\.)*)"', cuerpo)
        salida.append({
            "pkg": pkg.group(1),
            "label": label.group(1) if label else pkg.group(1),
            "allow": campo("allow"),
            "block": campo("block"),
            "note": (nota.group(1).replace('\\"', '"').replace("\\n", " ") if nota else ""),
        })
    return salida


def construir():
    crudo = open(KOTLIN, encoding="utf-8").read()
    texto = sin_comentarios(crudo)

    datos = {
        "apps": entradas_kotlin(texto),
        "infrastructure": lista_kotlin(texto, "INFRASTRUCTURE"),
        "blockAlways": lista_kotlin(texto, "BLOCK_ALWAYS"),
        "sharedCdn": lista_kotlin(texto, "SHARED_CDN"),
        "neverBlock": lista_kotlin(texto, "NEVER_BLOCK_PACKAGES"),
    }

    # Cordura: si el parseo devolvió algo absurdo es mejor fallar ruidosamente que
    # escribir un catálogo vacío que el panel mostraría como "esta app no tiene
    # ningún dominio" — o sea, como si el filtro no existiera.
    if len(datos["apps"]) < 20:
        raise SystemExit("Solo se parsearon %d apps: el parseo está roto, no se escribe nada"
                         % len(datos["apps"]))
    if not datos["infrastructure"] or not datos["blockAlways"]:
        raise SystemExit("INFRASTRUCTURE o BLOCK_ALWAYS vacías: el parseo está roto")
    for a in datos["apps"]:
        if not a["allow"] and not a["block"]:
            raise SystemExit("La app %s quedó sin un solo dominio: el parseo está roto" % a["pkg"])

    cuerpo = json.dumps(datos, ensure_ascii=False, indent=2, sort_keys=False)
    return (
        "// ARCHIVO GENERADO — NO EDITAR A MANO.\n"
        "//\n"
        "// Lo genera `tools/gen_catalog_js.py` desde\n"
        "// `app/src/main/java/com/ejemplo/locksuite/mdm/WhitelistCatalog.kt`, que es la\n"
        "// fuente de verdad: el Kotlin es el que decide qué dominio resuelve en el celular.\n"
        "// Esta copia existe solo para que el panel pueda DIBUJAR la lista y ofrecer\n"
        "// corregirla; las correcciones se guardan en `globalSettings/whitelist/custom`\n"
        "// (campos `allow`, `block` y `unblock`) y NUNCA acá.\n"
        "//\n"
        "// Para regenerarlo:  python tools/gen_catalog_js.py\n"
        "// Para verificarlo:  python tools/gen_catalog_js.py --check\n"
        "window.LOCKSUITE_CATALOG = " + cuerpo + ";\n"
    )


def main():
    nuevo = construir()
    if "--check" in sys.argv:
        if not os.path.exists(SALIDA):
            print("FALTA %s — corré: python tools/gen_catalog_js.py" % SALIDA)
            return 1
        actual = open(SALIDA, encoding="utf-8").read()
        if actual != nuevo:
            print("catalog.js NO coincide con WhitelistCatalog.kt.")
            print("Regeneralo con: python tools/gen_catalog_js.py")
            return 1
        print("OK: catalog.js coincide con WhitelistCatalog.kt")
        return 0

    os.makedirs(os.path.dirname(SALIDA), exist_ok=True)
    with open(SALIDA, "w", encoding="utf-8", newline="\n") as f:
        f.write(nuevo)
    datos = json.loads(nuevo[nuevo.index("{"):-2])
    print("Escrito %s" % SALIDA)
    print("  apps: %d | dominios permitidos: %d | dominios bloqueados: %d"
          % (len(datos["apps"]),
             sum(len(a["allow"]) for a in datos["apps"]),
             sum(len(a["block"]) for a in datos["apps"])))
    print("  infraestructura: %d | bloqueos fijos: %d | CDN: %d"
          % (len(datos["infrastructure"]), len(datos["blockAlways"]), len(datos["sharedCdn"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
