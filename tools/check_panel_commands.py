#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CHEQUEO: todo comando que mandan las PANTALLAS NUEVAS existe de los dos lados.
(10/9/2026)

POR QUÉ EXISTE
==============
`tools/check_command_sync.py` compara el celular contra la Cloud Function. Este
compara la tercera pata: **lo que manda el panel**. Es distinta y hace falta,
porque el modo de falla es el peor de todos — **el mudo**:

    el panel manda XYZ  →  la Function lo permite (o no)  →  el celular no lo
    matchea en su `when`  →  no pasa nada  →  el panel muestra el tilde verde.

Ya cazó dos en su primera corrida, sobre código recién escrito:

  · `REAPPLY_RESTRICTIONS` — no existe en ningún lado. Inventado de memoria.
  · `HEAL_VPN` — existe, pero es una acción INTERNA de `KosherVpnService`, no un
    comando FCM. El botón "Reparar el túnel DNS" habría sido un botón que no hace
    nada y contesta que sí.

Los dos habrían pasado un `node --check` sin una queja, y los dos se habrían
descubierto recién con el equipo en la mano. Es exactamente la familia de bugs de
B.28 (`no_apps_control`), B.38, B.40 p.8 y B.57 (`captivePortalCoverImages`).

CÓMO SE USA
===========
    python tools/check_panel_commands.py

Sale 0 si está todo alineado, 1 si algún comando no existe en los dos lados.
Correrlo antes de `deploy_all.ps1`, junto con los otros chequeos.
"""

import json
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FUNCTIONS = os.path.join(RAIZ, "admin-backend", "functions", "index.js")
SERVICE = os.path.join(RAIZ, "app", "src", "main", "java", "com", "ejemplo",
                       "locksuite", "service", "LockSuiteFirebaseService.kt")
POLICY_SPEC = os.path.join(RAIZ, "app", "src", "main", "java", "com", "ejemplo",
                           "locksuite", "mdm", "PolicySpec.kt")
PANTALLAS = [
    os.path.join(RAIZ, "admin-backend", "public", "celular.js"),
    os.path.join(RAIZ, "admin-backend", "public", "dominios.js"),
]
POLICIES_JS = os.path.join(RAIZ, "admin-backend", "public", "policies.js")

# Comandos que resuelve la propia Cloud Function y NUNCA viajan al celular. Va
# declarado con su motivo, no escondido en una lista de excepciones muda.
NO_VIAJAN = {
    "VERIFY_PIN": "la resuelve sendCommandV8, no llega al equipo",
}


def bloque_lista(texto, ancla):
    """Devuelve el texto del array/lista que arranca en `ancla`, balanceando."""
    m = re.search(ancla, texto)
    if not m:
        return ""
    i = texto.index("[", m.start())
    prof = 0
    j = i
    while j < len(texto):
        if texto[j] == "[":
            prof += 1
        elif texto[j] == "]":
            prof -= 1
            if prof == 0:
                break
        j += 1
    return texto[i:j]


def permitidos():
    fn = open(FUNCTIONS, encoding="utf-8").read()
    bloque = bloque_lista(fn, r"ALLOWED_COMMANDS\s*=")
    if not bloque:
        raise SystemExit("No se encontró ALLOWED_COMMANDS en functions/index.js")
    return set(re.findall(r'"([A-Z][A-Z0-9_]+)"', bloque))


def ejecuta_el_celular():
    """Comandos que el celular matchea: el `when` grande más el registro PolicySpec."""
    cmds = set(re.findall(r'"([A-Z][A-Z0-9_]+)"', open(SERVICE, encoding="utf-8").read()))
    if os.path.exists(POLICY_SPEC):
        cmds |= set(re.findall(r'"([A-Z][A-Z0-9_]+)"', open(POLICY_SPEC, encoding="utf-8").read()))
    return cmds


def manda_el_panel():
    """
    Comandos que mandan las pantallas nuevas.

    Se leen las formas en que este código los escribe, y **se ignoran a propósito
    las concatenaciones** (`"SET_DOMAIN_RULE_" + tipo`): no se puede saber por
    lectura estática qué sale de ahí. Para que igual queden cubiertos, las piezas
    se declaran abajo en `CONCATENADOS`, que es un lugar visible donde se ven — en
    vez de un agujero silencioso en el chequeo.
    """
    usados = {}

    def anotar(cmd, donde):
        # Un comando de verdad nunca termina en `_`: si termina así es el PREFIJO de
        # una concatenación (`"SET_DOMAIN_RULE_" + tipo`) y no un comando. Se ignora
        # acá y las piezas completas se declaran abajo en CONCATENADOS.
        if cmd.endswith("_"):
            return
        usados.setdefault(cmd, set()).add(donde)

    for ruta in PANTALLAS:
        if not os.path.exists(ruta):
            continue
        s = open(ruta, encoding="utf-8").read()
        base = os.path.basename(ruta)
        # LS.sendCommand(deviceId, "CMD", …)
        for m in re.finditer(r'sendCommand\(\s*[A-Za-z_$][\w$]*\s*,\s*"([A-Z][A-Z0-9_]+)"', s):
            anotar(m.group(1), base)
        # tablas: ["CMD", "texto", …]  y  { on: "CMD", off: "CMD" }
        for m in re.finditer(r'\[\s*"([A-Z][A-Z0-9_]{3,})"\s*,\s*"', s):
            anotar(m.group(1), base)
        for m in re.finditer(r'\b(?:on|off)\s*:\s*"([A-Z][A-Z0-9_]+)"', s):
            anotar(m.group(1), base)
        # pasos.push("CMD")
        for m in re.finditer(r'push\(\s*"([A-Z][A-Z0-9_]+)"\s*\)', s):
            anotar(m.group(1), base)
        # ternarios: cond ? "CMD_A" : "CMD_B"
        for m in re.finditer(r'\?\s*"([A-Z][A-Z0-9_]+)"\s*:\s*"([A-Z][A-Z0-9_]+)"', s):
            anotar(m.group(1), base)
            anotar(m.group(2), base)

    # Las piezas que el código arma concatenando. Si se agrega un tipo nuevo de
    # regla DNS, va acá también — es UNA línea, y es el precio de que el chequeo
    # no tenga puntos ciegos.
    CONCATENADOS = [
        "SET_DOMAIN_RULE_BLOCK", "SET_DOMAIN_RULE_ALLOW",
        "SET_DOMAIN_RULE_FORCE_BLOCK", "SET_DOMAIN_RULE_FORCE_ALLOW",
        "REMOVE_DOMAIN_RULE",
    ]
    for c in CONCATENADOS:
        anotar(c, "celular.js (concatenado)")

    # Y los pares que salen de policies.js, que es generado.
    if os.path.exists(POLICIES_JS):
        s = open(POLICIES_JS, encoding="utf-8").read()
        datos = json.loads(s[s.index("{"):s.rindex("}") + 1])
        for p in datos.get("policies", []):
            anotar(p["on"], "policies.js")
            anotar(p["off"], "policies.js")

    return usados


def main():
    permitidos_set = permitidos()
    celular_set = ejecuta_el_celular()
    usados = manda_el_panel()

    faltan_function = []
    faltan_celular = []
    for cmd, donde in sorted(usados.items()):
        if cmd not in permitidos_set:
            faltan_function.append((cmd, donde))
        elif cmd not in celular_set and cmd not in NO_VIAJAN:
            faltan_celular.append((cmd, donde))

    print("Comandos que mandan las pantallas nuevas: %d" % len(usados))
    print("  · ALLOWED_COMMANDS (Cloud Function): %d" % len(permitidos_set))
    print("  · matchea el celular: %d" % len(celular_set))

    if not faltan_function and not faltan_celular:
        print("\n✅ Todos los comandos del panel existen en la Function Y en el celular.")
        return 0

    if faltan_function:
        print("\n❌ La Cloud Function los va a RECHAZAR (no están en ALLOWED_COMMANDS):")
        for cmd, donde in faltan_function:
            print("   - %-34s (lo manda: %s)" % (cmd, ", ".join(sorted(donde))))
    if faltan_celular:
        print("\n❌ EL CELULAR NO LOS MATCHEA — este es el fallo MUDO:")
        print("   salen, llegan, no hacen nada, y el panel muestra el tilde verde igual.")
        for cmd, donde in faltan_celular:
            print("   - %-34s (lo manda: %s)" % (cmd, ", ".join(sorted(donde))))
    return 1


if __name__ == "__main__":
    sys.exit(main())
