#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_profile_sync.py — CHEQUEO DE SIMETRÍA DE LOS PERFILES (9/9/2026).

─────────────────────────────────────────────────────────────────────────────
QUÉ BUG EVITA, Y POR QUÉ VALE LA PENA UN SCRIPT ENTERO PARA ESTO
─────────────────────────────────────────────────────────────────────────────

Un perfil es un objeto JSON con ~50 claves que escriben TRES lugares distintos y
lee UNO:

    escriben:  PolicyManager.exportPolicyPresetJson()   (el .locksuite del celular)
               EnrollmentProfiles.kt                    (los tres perfiles del APK)
               admin-backend/public/app.js              (el perfil hecho en el panel)
    lee:       PolicyManager.importPolicyPresetJson()

Y el modo de falla de una clave desalineada es EL PEOR POSIBLE: **no da error en
ningún lado**. `JSONObject.optBoolean("clave_que_nadie_lee", …)` simplemente nunca
se llama; el perfil se aplica "con éxito", el panel muestra el tilde verde, y la
protección no se puso nunca. Se descubre meses después, cuando alguien nota que un
equipo no estaba filtrando.

Este proyecto ya lo pagó CUATRO veces:

  · `no_apps_control` (B.28)                — el panel mandaba una clave que no existe
                                               en Android; "Bloquear control de apps"
                                               viajaba en TODOS los perfiles y no se
                                               aplicó nunca.
  · `DISALLOW_CONFIG_DATE_TIME` (B.38)      — estaba en tres de las cuatro listas de
                                               restricciones y faltaba en la cuarta.
  · Los nombres de clave del perfil (B.40 p.8) — la auditoría propuso guardar
                                               `kioskLockTaskEnabled` cuando el
                                               importador lee `kioskLockTask`.
  · `captivePortalCoverImages` (B.57)       — el panel la firmaba y la mandaba desde
                                               el 8/9; el importador no la leía.

Las cuatro son la misma forma. Este script las hace imposibles: compara las cuatro
listas y sale con código 1 si alguna clave se escribe y no se lee.

Es el hermano del chequeo de simetría de restricciones de B.38 (que compara
aplicar / suspender / purgar / perfil) y de `check_whitelist_sync.py` de B.53.
**Correlo después de tocar cualquiera de los cuatro archivos, y antes de
`deploy_all.ps1`.**

    python tools/check_profile_sync.py

─────────────────────────────────────────────────────────────────────────────
LO QUE ESTE SCRIPT NO PUEDE VER, DICHO DE FRENTE
─────────────────────────────────────────────────────────────────────────────

Es una comparación por expresiones regulares sobre el texto de los archivos, no un
análisis del programa. No detecta una clave armada en tiempo de ejecución
(`"acc_" + sufijo`), ni una que se lea desde otro archivo. Hoy no hay ninguna de
esas y no conviene agregarlas: si aparece una, este chequeo deja de servir en
silencio, que es justo lo que se está tratando de evitar. Mantener las claves
literales.
"""

import re
import sys
from pathlib import Path

if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

RAIZ = Path(__file__).resolve().parent.parent
POLICY_MANAGER = RAIZ / "app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt"
ENROLLMENT = RAIZ / "app/src/main/java/com/ejemplo/locksuite/mdm/EnrollmentProfiles.kt"
PANEL_JS = RAIZ / "admin-backend/public/app.js"
FUNCTIONS_JS = RAIZ / "admin-backend/functions/index.js"

# Claves que el importador maneja de forma especial (no con optBoolean) y que por lo
# tanto no aparecen en el patrón de abajo. Se listan a mano para que el chequeo no dé
# un falso positivo, y con el nombre de la función que las lee, para poder verificarlo.
CLAVES_NO_BOOLEANAS = {
    "restrictions",            # importPolicyPresetJson: getJSONObject + setRestriction
    "perAppInternetBlocked",   # importPolicyPresetJson: optJSONArray
    "suspendedPackages",       # applyDeviceStateFromPreset: optJSONArray
    "hiddenPackages",          # applyDeviceStateFromPreset: optJSONArray
    "dnsRules",                # applyDeviceStateFromPreset: optJSONObject
}

# Los tres niveles tienen que existir con el mismo id en el Kotlin y en la Cloud
# Function: la Function valida contra una lista escrita a mano porque no puede leer
# Kotlin, y un nivel que ella acepte y el celular no reconozca se descarta en silencio
# del otro lado.
RE_NIVEL_KOTLIN = re.compile(r'const val LEVEL_[A-Z_]+\s*=\s*"([a-z_]+)"')
RE_NIVEL_JS = re.compile(r'const LEVELS = new Set\(\[([^\]]*)\]\)')


# B.61 — el receptor de aprovisionamiento, para comparar la clave del nivel del QR.
RECEIVER = RAIZ / "app/src/main/java/com/ejemplo/locksuite/receiver/DeviceAdminReceiver.kt"


def leer(path: Path) -> str:
    if not path.exists():
        print(f"❌ No se encontró {path.relative_to(RAIZ)}")
        sys.exit(2)
    return path.read_text(encoding="utf-8")


def bloque(texto: str, inicio: str, fin: str) -> str:
    """Devuelve el texto entre dos marcas. Sirve para no leer todo el archivo."""
    i = texto.find(inicio)
    if i < 0:
        print(f"❌ No se encontró el bloque que empieza con: {inicio!r}")
        sys.exit(2)
    j = texto.find(fin, i + len(inicio))
    return texto[i:j if j > 0 else len(texto)]


def main() -> int:
    pm = leer(POLICY_MANAGER)
    enroll = leer(ENROLLMENT)
    panel = leer(PANEL_JS)
    funcs = leer(FUNCTIONS_JS)

    export_src = bloque(pm, "fun exportPolicyPresetJson(", "private fun addDeviceStateToPreset")
    import_src = bloque(pm, "fun importPolicyPresetJson(", "private fun normalizeRestrictionKey")
    estado_src = bloque(pm, "private fun applyDeviceStateFromPreset", "// PERFILES MAESTROS DE ALTA")

    # ── Qué claves escribe cada lado ──────────────────────────────────────────
    escribe_export = set(re.findall(r'dataObj\.put\("([A-Za-z0-9_]+)"', export_src))
    escribe_export |= set(re.findall(r'dataObj\.put\("([A-Za-z0-9_]+)"', estado_src))

    # En EnrollmentProfiles las claves están en los mapas `switches`; las restricciones
    # van por constantes de UserManager y las cubre el chequeo de B.38, no este.
    escribe_enroll = set(re.findall(r'^\s*"([A-Za-z0-9_]+)" to (?:true|false),?\s*$',
                                    enroll, re.MULTILINE))

    # En el panel, el objeto del perfil. Se acota al bloque para no barrer app.js entero.
    #
    # Y se le saca el sub-objeto `restrictions: { … }`: sus claves son constantes
    # `DISALLOW_*` de Android, no claves de perfil — el importador las recorre en bloque
    # (`getJSONObject("restrictions")` + `setRestriction`) y no las nombra una por una.
    # Compararlas contra el importador daría 40 falsos positivos. La simetría de ESAS
    # cuatro listas la cubre el chequeo de B.38, que es el que corresponde.
    panel_src = bloque(panel, "const dataObj = {", "const dataStr = canonicalizeJson(dataObj)")
    panel_sin_restr = re.sub(r'restrictions:\s*\{.*?\n(\s*)\},\n', '', panel_src, flags=re.DOTALL)
    if panel_sin_restr == panel_src:
        print("❌ No se pudo recortar el sub-objeto `restrictions` del perfil del panel.")
        print("   Sin ese recorte el chequeo da 40 falsos positivos. Revisar app.js.")
        return 2
    escribe_panel = set(re.findall(r'^\s*([A-Za-z0-9_]+):', panel_sin_restr, re.MULTILINE))

    # ── Qué claves lee el importador ──────────────────────────────────────────
    lee_import = set(re.findall(r'dataObj\.opt[A-Za-z]+\("([A-Za-z0-9_]+)"', import_src))
    lee_import |= set(re.findall(r'dataObj\.opt[A-Za-z]+\("([A-Za-z0-9_]+)"', estado_src))
    lee_import |= CLAVES_NO_BOOLEANAS

    fallas = []

    for etiqueta, escritas in (
        ("PolicyManager.exportPolicyPresetJson()", escribe_export),
        ("EnrollmentProfiles.kt (perfiles del APK)", escribe_enroll),
        ("admin-backend/public/app.js (perfil del panel)", escribe_panel),
    ):
        huerfanas = sorted(escritas - lee_import)
        if huerfanas:
            fallas.append(
                f"❌ {etiqueta} escribe claves que importPolicyPresetJson() NO lee.\n"
                f"   Se aceptan en silencio y NO HACEN NADA (es el bug de `no_apps_control`, B.28):\n"
                + "".join(f"     · {k}\n" for k in huerfanas)
            )

    # Al revés no es un error: el importador puede leer claves que un origen concreto no
    # manda (ahí el valor por omisión es el actual, que es la regla del proyecto). Pero
    # que el EXPORTADOR de la app no escriba algo que el importador lee sí es raro y vale
    # avisarlo, porque significa que un .locksuite no reproduce ese ajuste.
    solo_lee = sorted(lee_import - escribe_export - CLAVES_NO_BOOLEANAS)
    if solo_lee:
        print("⚠️  Avisos (no rompen el chequeo): el importador lee claves que el .locksuite "
              "del celular NO exporta, así que un respaldo hecho desde la app no las reproduce:")
        for k in solo_lee:
            print(f"     · {k}")
        print()

    # ── Los tres niveles, Kotlin contra Cloud Function ────────────────────────
    niveles_kt = set(RE_NIVEL_KOTLIN.findall(enroll))
    m = RE_NIVEL_JS.search(funcs)
    niveles_js = set(re.findall(r'"([a-z_]+)"', m.group(1))) if m else set()
    if not m:
        fallas.append("❌ No se encontró la lista LEVELS en admin-backend/functions/index.js.\n")
    elif niveles_kt != niveles_js:
        fallas.append(
            "❌ Los niveles de perfil no coinciden entre el APK y la Cloud Function.\n"
            f"   Solo en EnrollmentProfiles.kt: {sorted(niveles_kt - niveles_js) or '—'}\n"
            f"   Solo en functions/index.js:    {sorted(niveles_js - niveles_kt) or '—'}\n"
            "   Un nivel que la Function acepta y el celular no reconoce se descarta allá sin avisar.\n"
        )

    # ── B.61 — las dos simetrías del alta por QR ──
    #
    # (a) Todo lo que está en POST_ALTA tiene que existir DE VERDAD en algún perfil.
    #     Una entrada muerta ahí no da error: simplemente `postAlta` sale vacío, el
    #     alta se marca como completa y el equipo queda sin esa restricción PARA
    #     SIEMPRE, sin que nadie lo note. Es la forma del bug de B.28.
    # (b) La clave del nivel dentro de ADMIN_EXTRAS_BUNDLE tiene que decir lo mismo en
    #     el Kotlin y en el panel. Si no coinciden, el equipo se enrola, no encuentra
    #     el nivel, y queda enrolado sin configurar — otra vez la misma forma.
    kt_enroll = leer(ENROLLMENT)
    m_post = re.search(r"val POST_ALTA: Set<String> = setOf\((.*?)\)", kt_enroll, re.S)
    if not m_post:
        fallas.append("❌ No se encontró POST_ALTA en EnrollmentProfiles.kt.\n")
    else:
        post = set(re.findall(r"UserManager\.(\w+)", m_post.group(1)))
        usadas = set(re.findall(r"UserManager\.(\w+) to ", kt_enroll))
        muertas = post - usadas
        if muertas:
            fallas.append(
                "❌ POST_ALTA nombra restricciones que ningún perfil usa: "
                f"{sorted(muertas)}\n"
                "   Una entrada muerta ahí hace que el alta por QR se dé por completa sin\n"
                "   haber aplicado esa restricción, y no avisa nadie.\n"
            )

    kt_recv = leer(RECEIVER)
    m_kt = re.search(r'EXTRA_NIVEL_PERFIL = "([^"]+)"', kt_recv)
    m_js = re.search(r'QR_EXTRA_NIVEL = "([^"]+)"', leer(PANEL_JS))
    if not m_kt or not m_js:
        fallas.append("❌ No se encontró la clave del nivel del QR en el Kotlin o en el panel.\n")
    elif m_kt.group(1) != m_js.group(1):
        fallas.append(
            "❌ La clave del nivel dentro de ADMIN_EXTRAS_BUNDLE no coincide.\n"
            f"   DeviceAdminReceiver.kt: {m_kt.group(1)}\n"
            f"   admin-backend/public/app.js: {m_js.group(1)}\n"
            "   El equipo se enrola, no encuentra el nivel y queda SIN CONFIGURAR, en silencio.\n"
        )

    if fallas:
        print("".join(fallas))
        return 1

    print("✅ Perfiles sincronizados.")
    print(f"   {len(escribe_export)} claves exporta la app · "
          f"{len(escribe_enroll)} usan los perfiles del APK · "
          f"{len(escribe_panel)} manda el panel · "
          f"{len(lee_import)} lee el importador.")
    print(f"   Niveles: {', '.join(sorted(niveles_kt))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
