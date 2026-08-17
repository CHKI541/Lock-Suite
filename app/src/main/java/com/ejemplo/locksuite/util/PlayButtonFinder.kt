package com.ejemplo.locksuite.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Encuentra, en la ficha de una app de Google Play, los botones que le interesan
 * al flujo de actualización — sin depender de que el equipo esté en español.
 *
 * Por qué existe (16/8/2026)
 * ─────────────────────────
 * La versión anterior buscaba el botón comparando el texto contra una lista de
 * palabras. Pasó por los dos extremos y los dos fallaron:
 *
 *   • Con `contains` sobre palabras cortísimas ("ok", "yes", "open", "install")
 *     matcheaba decenas de nodos que no eran botones, y como "installing" contiene
 *     "install", mientras descargaba volvía a apretar sobre la fila de progreso —
 *     que es justo donde Play Store pone el botón de cancelar la descarga.
 *   • Con igualdad exacta dejaba de encontrar el botón real, porque Play Store lo
 *     expone como "Actualizar Waze" en el contentDescription, o como "עדכון" y no
 *     "עדכן", o directamente en un idioma que no está en la lista.
 *
 * La salida de acá NO es "el botón": es una lista de CANDIDATOS ordenada por
 * confianza. Quien llama aprieta el primero, espera, y si no pasó nada (no apareció
 * una sesión de instalación ni cambió el versionCode) prueba el siguiente. Esa
 * verificación es lo que hace que funcione en un idioma que nadie previó.
 *
 * Para que probar candidatos sea seguro, el flujo llama antes a
 * `dpm.setUninstallBlocked(target, true)`: aunque se apretara "Desinstalar" en un
 * idioma desconocido, Android lo rechaza. Sin esa red, este enfoque no va.
 *
 * Tres formas de reconocer un candidato, de más a menos confiable:
 *   1. ID de vista de Play Store — independiente del idioma, pero las versiones
 *      nuevas basadas en Compose ya casi no exponen IDs.
 *   2. Palabra completa contra la tabla multiidioma de abajo.
 *   3. Posición — en TODAS las variantes de la ficha, el botón primario es el que
 *      está más a la derecha de la fila de botones, debajo del encabezado.
 */
object PlayButtonFinder {

    // Topes de seguridad del recorrido, iguales a los del servicio.
    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 2_500

    private const val SCORE_VIEW_ID = 100
    private const val SCORE_WORD = 80
    private const val SCORE_POSITION = 40

    /** Franja donde vive la fila de botones de la ficha, en fracción de pantalla. */
    private const val BAND_TOP = 0.10f
    private const val BAND_BOTTOM = 0.62f

    /** Un botón real ocupa al menos esta fracción del ancho. Descarta íconos. */
    private const val MIN_BUTTON_WIDTH_FRACTION = 0.15f

    private val ACTION_VIEW_IDS = setOf(
        "com.android.vending:id/buy_button",
        "com.android.vending:id/action_button",
        "com.android.vending:id/right_button",
        "com.android.vending:id/positive_button",
        "com.android.vending:id/download_button"
    )

    /**
     * Palabras de "actualizar / instalar". Van sin tildes porque se comparan
     * después de pasar por [fold], que además baja a minúsculas.
     */
    private val ACTION_WORDS = setOf(
        // español / portugués
        "actualizar", "actualizacion", "actualiza", "instalar", "instala",
        "atualizar", "atualizacao",
        // inglés
        "update", "install", "enable", "resume",
        // francés / italiano / alemán
        "actualiser", "installer", "activer",
        "aggiorna", "aggiornare", "installa",
        "aktualisieren", "installieren", "aktualisierung",
        // ruso / ucraniano
        "обновить", "обновление", "установить",
        // hebreo
        "עדכן", "עדכון", "עדכנו", "התקן", "התקנה",
        // árabe
        "تحديث", "تثبيت"
    )

    private val OPEN_WORDS = setOf(
        "abrir", "open", "ouvrir", "aprire", "offnen", "oeffnen",
        "открыть", "פתח", "פתיחה", "فتح", "iniciar", "launch"
    )

    private val DIALOG_WORDS = setOf(
        "continuar", "continue", "aceptar", "ok", "proceder", "descargar",
        "download", "si", "yes", "reintentar", "retry", "entendido",
        "usar datos", "datos moviles", "descargar ahora", "cualquier red",
        "continuer", "accepter", "telecharger",
        "continua", "accetta", "scarica",
        "weiter", "akzeptieren", "herunterladen",
        "продолжить", "принять", "скачать", "да",
        "המשך", "אישור", "הורד", "כן", "נסה שוב",
        "متابعة", "موافق", "تنزيل", "نعم"
    )

    /**
     * Nunca apretar algo que matchee esto. Es una defensa en profundidad, no la
     * principal: la principal es `setUninstallBlocked` durante todo el flujo.
     */
    private val FORBIDDEN_WORDS = setOf(
        "desinstalar", "uninstall", "desinstaller", "disinstalla", "deinstallieren",
        "удалить", "הסר", "הסרה", "الغاء التثبيت",
        "cancelar", "cancel", "annuler", "annulla", "abbrechen", "отмена", "بطل", "בטל",
        "detener", "stop", "arreter", "pausar", "pause", "eliminar", "remove"
    )

    class Candidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        /** Por qué se lo eligió. Va a los logs de diagnóstico. */
        val reason: String,
        val top: Int,
        val left: Int
    )

    class Result(
        /** Candidatos a "Actualizar / Instalar", el mejor primero. */
        val actions: List<Candidate>,
        /** Candidatos a "Abrir" (la app ya quedó lista o ya estaba al día). */
        val opens: List<Candidate>,
        /** Botones de diálogos de confirmación. */
        val dialogs: List<Candidate>,
        val sawProgressBar: Boolean,
        /**
         * Etiquetas de los nodos clickeables de la franja de botones. Sirve para
         * diagnosticar sin ADB cuando el escaneo no reconoce nada: se publican en
         * `updateFlow.debugLabels` y el panel las muestra. Sin esto, cada equipo en
         * un idioma nuevo es una sesión entera de adivinanza.
         */
        val debugLabels: List<String>
    ) {
        val isEmpty: Boolean get() = actions.isEmpty() && opens.isEmpty() && dialogs.isEmpty()
    }

    fun scan(root: AccessibilityNodeInfo?, screenWidth: Int, screenHeight: Int): Result {
        val actions = ArrayList<Candidate>(8)
        val opens = ArrayList<Candidate>(4)
        val dialogs = ArrayList<Candidate>(4)
        val labels = ArrayList<String>(20)
        val state = WalkState(screenWidth, screenHeight)

        if (root != null) {
            try {
                walk(root, 0, state, actions, opens, dialogs, labels)
            } catch (e: Exception) {
                // Un árbol de accesibilidad puede desarmarse mientras se lo recorre.
                // Se devuelve lo que se alcanzó a juntar en vez de perder el ciclo.
            }
        }

        val byConfidence = compareByDescending<Candidate> { it.score }
            .thenBy { it.top }        // filas de más arriba primero
            .thenByDescending { it.left }  // dentro de la fila, el de más a la derecha:
                                           // en toda variante de Play Store el botón
                                           // primario es ese, y el secundario
                                           // ("Desinstalar") queda a su izquierda.

        return Result(
            actions = actions.sortedWith(byConfidence),
            opens = opens.sortedWith(byConfidence),
            dialogs = dialogs.sortedWith(byConfidence),
            sawProgressBar = state.sawProgressBar,
            debugLabels = labels
        )
    }

    private class WalkState(val screenWidth: Int, val screenHeight: Int) {
        var nodes = 0
        var sawProgressBar = false
        val rect = Rect()
        val bandTop = (screenHeight * BAND_TOP).toInt()
        val bandBottom = (screenHeight * BAND_BOTTOM).toInt()
        val minWidth = (screenWidth * MIN_BUTTON_WIDTH_FRACTION).toInt()
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        state: WalkState,
        actions: MutableList<Candidate>,
        opens: MutableList<Candidate>,
        dialogs: MutableList<Candidate>,
        labels: MutableList<String>
    ) {
        if (node == null || depth > MAX_DEPTH || state.nodes >= MAX_NODES) return
        state.nodes++

        val className = node.className?.toString() ?: ""
        if (className.endsWith("ProgressBar")) state.sawProgressBar = true

        val rawText = node.text?.toString()?.trim() ?: ""
        val rawDesc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        node.getBoundsInScreen(state.rect)
        val top = state.rect.top
        val left = state.rect.left
        val width = state.rect.width()
        val onScreen = !state.rect.isEmpty && top >= 0 && top < state.screenHeight
        val inBand = onScreen && top >= state.bandTop && top <= state.bandBottom

        val probe = fold(if (rawText.isNotEmpty()) rawText else rawDesc)
        val forbidden = probe.isNotEmpty() && FORBIDDEN_WORDS.any { containsWord(probe, it) }
        val clickable = node.isClickable || className.endsWith("Button")

        // Diagnóstico: etiquetas cortas de lo clickeable en la franja de botones.
        if (inBand && clickable && probe.isNotEmpty() && probe.length <= 40 && labels.size < 20) {
            val tag = if (viewId.isNotEmpty()) "$probe [${viewId.substringAfterLast('/')}]" else probe
            if (!labels.contains(tag)) labels.add(tag)
        }

        if (!forbidden) {
            // 1. Por ID de vista — lo más confiable donde existe.
            if (viewId in ACTION_VIEW_IDS && onScreen) {
                actions.add(Candidate(node, SCORE_VIEW_ID, "viewId=$viewId", top, left))
            } else if (probe.isNotEmpty() && probe.length <= 40) {
                // 2. Por palabra completa. El tope de 40 caracteres evita que un
                //    párrafo de la descripción de la app entre como candidato.
                when {
                    ACTION_WORDS.any { containsWord(probe, it) } ->
                        actions.add(Candidate(node, SCORE_WORD, "texto='$probe'", top, left))
                    OPEN_WORDS.any { containsWord(probe, it) } ->
                        opens.add(Candidate(node, SCORE_WORD, "texto='$probe'", top, left))
                    DIALOG_WORDS.any { containsWord(probe, it) } ->
                        // Los diálogos y hojas inferiores aparecen abajo, así que
                        // acá NO se exige estar en la franja de botones.
                        if (clickable) dialogs.add(Candidate(node, SCORE_WORD, "dialogo='$probe'", top, left))
                }
            }

            // 3. Por posición — la red que salva un idioma que nadie previó.
            //    Cualquier botón ancho de la franja es candidato; el orden y la
            //    verificación posterior deciden cuál es.
            if (inBand && clickable && width >= state.minWidth &&
                actions.none { it.node == node } &&
                opens.none { it.node == node }
            ) {
                actions.add(Candidate(node, SCORE_POSITION, "posicion top=$top", top, left))
            }
        }

        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, state, actions, opens, dialogs, labels)
        }
    }

    /**
     * Minúsculas + quita tildes latinas. En hebreo, árabe y cirílico es
     * prácticamente un no-op, que es lo correcto.
     */
    fun fold(cs: CharSequence): String {
        val sb = StringBuilder(cs.length)
        for (c in cs) {
            val lc = c.lowercaseChar()
            sb.append(
                when (lc) {
                    'á', 'à', 'ä', 'â', 'ã', 'å' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    'ç' -> 'c'
                    'ñ' -> 'n'
                    else -> lc
                }
            )
        }
        return sb.toString()
    }

    /**
     * ¿[word] aparece en [haystack] como palabra completa?
     *
     * Es lo que distingue "Actualizar Waze" (sí, matchea "actualizar") de
     * "Installing" (no matchea "install", porque después viene una letra). Ese
     * caso puntual es el que hacía que el flujo apretara sobre la barra de
     * progreso mientras descargaba.
     */
    fun containsWord(haystack: String, word: String): Boolean {
        if (word.isEmpty() || haystack.length < word.length) return false
        var from = 0
        while (true) {
            val i = haystack.indexOf(word, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else haystack[i - 1]
            val afterIdx = i + word.length
            val after = if (afterIdx >= haystack.length) ' ' else haystack[afterIdx]
            if (!Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after)) return true
            from = i + 1
        }
    }
}
