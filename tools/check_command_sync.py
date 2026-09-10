#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_command_sync.py — CHEQUEO DE SIMETRÍA DE LOS COMANDOS FCM (10/9/2026, B.59).

─────────────────────────────────────────────────────────────────────────────
QUÉ BUG EVITA
─────────────────────────────────────────────────────────────────────────────

Un comando FCM tiene que existir en TRES lugares, y ninguno de los tres avisa
cuando falta en otro:

    manda:    admin-backend/public/app.js         runCommandOnDevice(id, "X", …)
    permite:  admin-backend/functions/index.js    ALLOWED_COMMANDS
    ejecuta:  app/…/service/LockSuiteFirebaseService.kt   el `when (command)`
              app/…/mdm/PolicySpec.kt             blockCommand / unblockCommand

El celular tiene DOS caminos para ejecutar, y hay que mirar los dos o este chequeo
da cuarenta falsos positivos: el `when` explícito y, en su rama `else`, el registro
declarativo `PolicySpec` (B.33, el patrón `FeatureRegistry` copiado de A Bloq). Las
restricciones que viven en `PolicySpec.kt` NO tienen un caso propio en el `when` a
propósito — ese es justamente el punto del registro.

Los tres modos de falla, y por qué el del medio es el peligroso:

  · **El panel manda algo que la Function no permite** → HTTP 400 «Comando no
    reconocido». Se ve en el acto: es el modo de falla ruidoso, y no es el problema.

  · **La Function permite algo que el celular no sabe ejecutar** → el mensaje FCM
    sale, llega, el `when` no matchea ningún caso, y el equipo responde ACK de éxito
    o no responde nada. **El panel muestra el tilde verde y no pasó nada.** Es
    literalmente la forma del bug de `no_apps_control` (B.28), que este proyecto ya
    pagó cuatro veces, y de B.42 («el panel llamaba "✓" al silencio»).

  · **El celular ejecuta algo que la Function no permite** → código muerto: no hay
    forma de disparar ese caso. No rompe nada, pero es una función que alguien
    escribió creyendo que quedaba andando.

Hermano de `check_profile_sync.py` (B.57) y `check_whitelist_sync.py` (B.53).
**Correlo después de agregar cualquier comando, y antes de `deploy_all.ps1`.**

    python tools/check_command_sync.py

─────────────────────────────────────────────────────────────────────────────
LO QUE ESTE SCRIPT NO PUEDE VER, DICHO DE FRENTE
─────────────────────────────────────────────────────────────────────────────

Es una comparación por expresiones regulares sobre el texto, no un análisis del
programa. Un comando armado en tiempo de ejecución (`"BLOCK_" + algo`) es invisible
para esto. Hoy no hay ninguno y no conviene estrenarlos: si aparece uno, el chequeo
deja de servir **en silencio**, que es justo lo que se está tratando de evitar.
Mantener los nombres de comando literales en los tres lados.
"""

import os
import re
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

KT = os.path.join(RAIZ, "app", "src", "main", "java", "com", "ejemplo",
                  "locksuite", "service", "LockSuiteFirebaseService.kt")
SPEC = os.path.join(RAIZ, "app", "src", "main", "java", "com", "ejemplo",
                    "locksuite", "mdm", "PolicySpec.kt")
FUNCS = os.path.join(RAIZ, "admin-backend", "functions", "index.js")
PANEL = os.path.join(RAIZ, "admin-backend", "public", "app.js")

# Comandos que la Cloud Function acepta pero que NO se despachan en el `when` del
# celular porque los resuelve otro camino. Cada excepción va con su motivo escrito:
# una lista de excepciones sin motivos se convierte en el lugar donde se esconden
# los bugs que este script busca.
EXCEPCIONES_SIN_WHEN = {
    # VERIFY_PIN lo resuelve la propia Cloud Function comparando el hash contra
    # deviceSecrets: nunca llega a viajar como mensaje al equipo.
    "VERIFY_PIN": "la resuelve sendCommandV8, no viaja al celular",
}


def leer(p):
    if not os.path.exists(p):
        print(f"❌ No se encontró {p}")
        sys.exit(2)
    with open(p, encoding="utf-8") as f:
        return f.read()


def comandos_del_celular(txt):
    """Los casos del `when (command)`: `"X" ->` y `"X", "Y" ->`."""
    encontrados = set()
    for linea in txt.splitlines():
        s = linea.strip()
        if "->" not in s:
            continue
        izq = s.split("->", 1)[0]
        # Solo el bloque de la izquierda de la flecha, y solo literales en
        # MAYÚSCULAS_CON_GUION: así no entran los `"he" ->` de los textos ni los
        # nombres de clave en minúscula.
        for m in re.finditer(r'"([A-Z][A-Z0-9_]{2,})"', izq):
            encontrados.add(m.group(1))
    return encontrados


def comandos_del_registro(txt):
    """Las restricciones declarativas de PolicySpec.kt (B.33): blockCommand/unblockCommand."""
    txt = re.sub(r"//[^\n]*", "", txt)
    return set(re.findall(r'(?:un)?blockCommand\s*=\s*"([A-Z][A-Z0-9_]{2,})"', txt))


def comandos_permitidos(txt):
    m = re.search(r"const ALLOWED_COMMANDS = new Set\(\[(.*?)\]\)", txt, re.S)
    if not m:
        return None
    cuerpo = re.sub(r"//[^\n]*", "", m.group(1))          # fuera los comentarios
    return set(re.findall(r'"([A-Z][A-Z0-9_]{2,})"', cuerpo))


def comandos_del_panel(txt):
    """Lo que el panel realmente manda: runCommandOnDevice(loQueSea, "X", …)."""
    txt = re.sub(r"//[^\n]*", "", txt)
    return set(re.findall(r'runCommandOnDevice\s*\([^,()]*,\s*"([A-Z][A-Z0-9_]{2,})"', txt))


def main():
    kt = leer(KT)
    spec = leer(SPEC)
    funcs = leer(FUNCS)
    panel = leer(PANEL)

    del_when = comandos_del_celular(kt)
    del_registro = comandos_del_registro(spec)
    ejecuta = del_when | del_registro
    permite = comandos_permitidos(funcs)
    manda = comandos_del_panel(panel)

    if not del_registro:
        print("❌ No se encontró ninguna restricción declarativa en PolicySpec.kt.")
        print("   Si cambió el nombre de los campos blockCommand/unblockCommand hay que")
        print("   actualizar este script: un chequeo que no encuentra lo que compara")
        print("   pasa siempre, y este en particular daría 40 falsos positivos primero.")
        return 1

    if permite is None:
        print("❌ No se encontró ALLOWED_COMMANDS en admin-backend/functions/index.js.")
        print("   Si se renombró la constante, hay que actualizar este script: un chequeo")
        print("   que no encuentra lo que compara es un chequeo que pasa siempre.")
        return 1

    fallas = []
    avisos = []

    # ── 1. El panel manda algo que la Function no permite → HTTP 400 en la cara.
    huerfanos_panel = manda - permite
    if huerfanos_panel:
        fallas.append(
            "❌ El panel manda comandos que la Cloud Function NO permite:\n"
            f"   {sorted(huerfanos_panel)}\n"
            "   sendCommandV8 los rechaza con 400 «Comando no reconocido».\n"
            "   Agregalos a ALLOWED_COMMANDS en admin-backend/functions/index.js.\n"
        )

    # ── 2. La Function permite algo que el celular no ejecuta → EL SILENCIOSO.
    huerfanos_celular = {c for c in (permite - ejecuta) if c not in EXCEPCIONES_SIN_WHEN}
    if huerfanos_celular:
        fallas.append(
            "❌ La Cloud Function permite comandos que el celular NO sabe ejecutar:\n"
            f"   {sorted(huerfanos_celular)}\n"
            "   ⚠️ Este es el modo de falla MUDO: el mensaje sale, llega, el `when` no\n"
            "   matchea, y el panel muestra el tilde verde igual. Es la forma exacta del\n"
            "   bug de `no_apps_control` (B.28) y del «✓ al silencio» de B.42.\n"
            "   Agregá el caso en LockSuiteFirebaseService.kt, o sacá el comando de\n"
            "   ALLOWED_COMMANDS, o anotalo en EXCEPCIONES_SIN_WHEN con su motivo.\n"
        )

    # ── 3. El celular ejecuta algo que nadie puede mandarle → código muerto.
    muertos = ejecuta - permite
    if muertos:
        avisos.append(
            "⚠️  El celular tiene casos que la Cloud Function no permite mandar:\n"
            f"   {sorted(muertos)}\n"
            "   No rompen nada, pero no hay forma de dispararlos desde el panel.\n"
        )

    if fallas:
        print("".join(fallas))
        if avisos:
            print("".join(avisos))
        return 1

    print("✅ Comandos FCM sincronizados.")
    print(f"   {len(ejecuta)} ejecuta el celular ({len(del_when)} en el `when` + "
          f"{len(del_registro)} en PolicySpec) · {len(permite)} permite la Function · "
          f"{len(manda)} manda el panel.")
    if EXCEPCIONES_SIN_WHEN:
        print(f"   Excepciones declaradas: "
              f"{', '.join(f'{k} ({v})' for k, v in sorted(EXCEPCIONES_SIN_WHEN.items()))}")
    if avisos:
        print()
        print("".join(avisos))
    return 0


if __name__ == "__main__":
    sys.exit(main())
