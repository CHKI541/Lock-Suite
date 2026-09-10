#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GENERADOR DE `admin-backend/public/policies.js` (10/9/2026).

POR QUÉ EXISTE
==============
La ficha nueva de un celular (`celular.html`) tiene que dibujar los mismos ~75
interruptores de políticas que la barra lateral del panel viejo, y mandar los mismos
comandos. Esa información hoy vive partida en dos lugares del panel viejo:

  · `admin-backend/public/index.html` — la etiqueta visible, el nombre del campo
    (`data-policy`) y, por la tarjeta en la que está, el grupo al que pertenece.
  · `admin-backend/public/app.js` — el mapa `campo -> [comandoEncender, comandoApagar]`,
    escrito a mano adentro del manejador del clic.

Copiar eso a mano en un tercer archivo sería exactamente el bug que este proyecto ya
pagó cuatro veces: una clave escrita en un lado y leída en otro, que deja de coincidir
sin que nada avise (B.28 `no_apps_control`, B.38 `DISALLOW_CONFIG_DATE_TIME`, B.40 p.8,
B.57 `captivePortalCoverImages`). Y acá el modo de falla es el peor de todos: **un
comando mal escrito sale, la Cloud Function lo acepta si está en `ALLOWED_COMMANDS`, el
celular no lo matchea en su `when`, y el panel muestra el tilde verde igual** — que es
literal el silencio que `tools/check_command_sync.py` busca.

Así que no se copia: se EXTRAE de los dos archivos reales, y `--check` falla si el
generado no coincide con el que está en disco.

LO QUE NO HACE
==============
No toca el panel viejo. `index.html` y `app.js` siguen siendo la fuente de verdad y
siguen funcionando igual; este archivo es una lectura de ellos. Era la condición del
pedido: *"rediseñá todo desde cero sin arruinar el código"*.

CÓMO SE USA
===========
    python tools/gen_policies_js.py            # escribe admin-backend/public/policies.js
    python tools/gen_policies_js.py --check     # sale 1 si el archivo en disco difiere
"""

import json
import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PUBLIC = os.path.join(RAIZ, "admin-backend", "public")
INDEX = os.path.join(PUBLIC, "index.html")
APPJS = os.path.join(PUBLIC, "app.js")
SALIDA = os.path.join(PUBLIC, "policies.js")

# Interruptores que NO son "una política más" y la ficha nueva trata aparte, con su
# propia confirmación y su propio texto. Se excluyen del listado genérico a propósito:
# meterlos ahí los dejaría a un clic de distancia sin el aviso que les corresponde.
APARTE = {
    "locksuiteSuspended",      # levanta TODAS las protecciones (B.11)
    "kioskLockTaskEnabled",    # puede dejar el equipo sin marcar *#*#9999#*#* (B.35)
    "nokiaTouchEnabled",       # puede dejar el equipo sin forma de operarlo (B.36)
}


def sidebar_segment(html):
    i = html.find('<div id="device-sidebar"')
    j = html.find('<div id="group-sidebar"')
    if i < 0 or j < 0:
        raise SystemExit("No se encontró la ficha de dispositivo en index.html")
    return html[i:j]


def limpiar(texto):
    texto = re.sub(r"<[^>]+>", "", texto)
    texto = texto.replace("&amp;", "&").replace("&nbsp;", " ")
    texto = texto.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
    return re.sub(r"\s+", " ", texto).strip()


def grupos_y_switches(seg):
    """Recorre la ficha en orden y va asociando cada switch a la última tarjeta vista."""
    eventos = []
    for m in re.finditer(r"<h3\b[^>]*>(.*?)</h3>", seg, re.S):
        eventos.append((m.start(), "grupo", limpiar(m.group(1))))
    # Cada interruptor vive dentro de un <label class="toggle-row"><span>etiqueta</span>
    # …<input class="policy-switch" data-policy="campo">
    for m in re.finditer(
        r'<label[^>]*class="toggle-row"[^>]*>(.*?)</label>', seg, re.S
    ):
        cuerpo = m.group(1)
        dp = re.search(r'data-policy="([^"]+)"', cuerpo)
        if not dp:
            continue
        if "policy-switch" not in cuerpo:
            continue
        span = re.search(r"<span[^>]*>(.*?)</span>", cuerpo, re.S)
        etiqueta = limpiar(span.group(1)) if span else dp.group(1)
        eventos.append((m.start(), "switch", (dp.group(1), etiqueta)))

    eventos.sort(key=lambda e: e[0])
    grupo_actual = "Otras"
    salida = []
    vistos = set()
    for _, clase, dato in eventos:
        if clase == "grupo":
            grupo_actual = dato
        else:
            campo, etiqueta = dato
            if campo in vistos:
                continue
            vistos.add(campo)
            salida.append({"field": campo, "label": etiqueta, "group": grupo_actual})
    return salida


def mapa_comandos(appjs):
    """Extrae el mapa `campo: ["CMD_ON", "CMD_OFF"]` del manejador de app.js."""
    # Se ancla en la primera entrada conocida y se lee hasta el cierre del objeto,
    # balanceando llaves: así un comentario con `}` adentro no corta la lectura.
    m = re.search(r"factoryResetBlocked\s*:\s*\[", appjs)
    if not m:
        raise SystemExit("No se encontró el mapa de comandos en app.js")
    inicio = appjs.rfind("{", 0, m.start())
    prof = 0
    j = inicio
    while j < len(appjs):
        if appjs[j] == "{":
            prof += 1
        elif appjs[j] == "}":
            prof -= 1
            if prof == 0:
                break
        j += 1
    bloque = appjs[inicio:j + 1]
    pares = {}
    for mm in re.finditer(r'([A-Za-z_][A-Za-z0-9_]*)\s*:\s*\[\s*"([A-Z0-9_]+)"\s*,\s*"([A-Z0-9_]+)"\s*\]', bloque):
        pares[mm.group(1)] = [mm.group(2), mm.group(3)]
    return pares


def construir():
    html = open(INDEX, encoding="utf-8").read()
    appjs = open(APPJS, encoding="utf-8").read()

    switches = grupos_y_switches(sidebar_segment(html))
    cmds = mapa_comandos(appjs)

    policies = []
    sin_comando = []
    for s in switches:
        if s["field"] in APARTE:
            continue
        par = cmds.get(s["field"])
        if not par:
            sin_comando.append(s["field"])
            continue
        policies.append({
            "field": s["field"],
            "label": s["label"],
            "group": s["group"],
            "on": par[0],
            "off": par[1],
        })

    if len(policies) < 50:
        raise SystemExit("Solo se extrajeron %d políticas: el parseo está roto, no se escribe nada"
                         % len(policies))

    # Los campos del mapa de app.js que no tienen interruptor dibujado en la ficha: no
    # son un error (pueden manejarse desde otra pantalla), pero conviene que queden
    # anotados en el archivo para que se vean.
    huerfanos = sorted(set(cmds) - {p["field"] for p in policies} - APARTE)

    datos = {
        "policies": policies,
        "handledSeparately": sorted(APARTE),
        "switchesWithoutCommand": sorted(sin_comando),
        "commandsWithoutSwitch": huerfanos,
    }
    cuerpo = json.dumps(datos, ensure_ascii=False, indent=2)
    return (
        "// ARCHIVO GENERADO — NO EDITAR A MANO.\n"
        "//\n"
        "// Lo genera `tools/gen_policies_js.py` leyendo los DOS archivos que ya eran la\n"
        "// fuente de verdad del panel viejo: las etiquetas y los nombres de campo salen de\n"
        "// `index.html` (la ficha lateral) y los pares de comandos de `app.js`.\n"
        "//\n"
        "// `handledSeparately` son los interruptores que la ficha nueva trata aparte porque\n"
        "// necesitan confirmación propia: suspender LockSuite, el kiosco (puede dejar el\n"
        "// equipo sin poder marcar *#*#9999#*#*) y apagar el táctil.\n"
        "//\n"
        "// Para regenerarlo:  python tools/gen_policies_js.py\n"
        "// Para verificarlo:  python tools/gen_policies_js.py --check\n"
        "window.LOCKSUITE_POLICIES = " + cuerpo + ";\n"
    )


def main():
    nuevo = construir()
    if "--check" in sys.argv:
        if not os.path.exists(SALIDA):
            print("FALTA %s — corré: python tools/gen_policies_js.py" % SALIDA)
            return 1
        if open(SALIDA, encoding="utf-8").read() != nuevo:
            print("policies.js NO coincide con index.html + app.js.")
            print("Regeneralo con: python tools/gen_policies_js.py")
            return 1
        print("OK: policies.js coincide con index.html + app.js")
        return 0

    with open(SALIDA, "w", encoding="utf-8", newline="\n") as f:
        f.write(nuevo)
    datos = json.loads(nuevo[nuevo.index("{"):-2])
    print("Escrito %s" % SALIDA)
    print("  politicas: %d en %d grupos"
          % (len(datos["policies"]), len({p["group"] for p in datos["policies"]})))
    if datos["switchesWithoutCommand"]:
        print("  ⚠ interruptores dibujados SIN par de comandos en app.js: %s"
              % ", ".join(datos["switchesWithoutCommand"]))
    if datos["commandsWithoutSwitch"]:
        print("  · comandos sin interruptor en la ficha (informativo): %s"
              % ", ".join(datos["commandsWithoutSwitch"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
