package com.ejemplo.locksuite.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Encuentra, en la ficha de una app de Google Play, los botones que le interesan
 * al flujo de actualización — sin depender de que el equipo esté en español — y
 * además DIAGNOSTICA las pantallas donde Play Store no va a poder actualizar nada
 * (sin espacio, sin cuenta, app incompatible, ficha inexistente, error de red).
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
 *
 * ── CORRECCIÓN DEL 3/9/2026 (equipo CAT S22 Flip, Android 11) ──
 *
 * La franja de botones estaba expresada en FRACCIÓN de la pantalla (10 % a 62 %).
 * Eso asume una pantalla de teléfono normal (~800 dp de alto). En un CAT S22 Flip
 * —480×640 px, 2,8", ~286 ppi, o sea entre 320 y 366 dp de alto— la fila de
 * botones de Play Store cae alrededor del **70-80 %**, porque el encabezado de la
 * ficha (barra de búsqueda + ícono + título + fila de métricas) mide lo mismo en
 * dp en cualquier equipo: unos 250 dp. O sea que en ese equipo el corte del 62 %
 * descartaba justo al botón bueno, y de paso dejaba **vacías las etiquetas de
 * diagnóstico** que el panel muestra — el único dato con el que se podía haber
 * diagnosticado el equipo a distancia.
 *
 * El arreglo es dejar de medir en fracciones y medir en **dp desde arriba**, que es
 * la unidad en la que el encabezado es constante. De paso queda MÁS estricto que
 * antes en un teléfono normal (340 dp de 800 dp son 42 %, contra el 62 % viejo),
 * así que también achica el riesgo del bug 4 de B.9 (agarrar el "Instalar" de un
 * carrusel de "apps similares" que está más abajo).
 *
 * El documento de diseño del 16/8 (§1.3) ya había anticipado esto: la franja tenía
 * que ser criterio de ORDEN y no un corte duro. Quedó implementado como corte duro.
 * Ahora es las dos cosas: hay una franja preferida (más puntaje) y una franja
 * tolerada (menos puntaje), y el corte duro quedó solo en los bordes.
 */
object PlayButtonFinder {

    // Topes de seguridad del recorrido, iguales a los del servicio.
    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 2_500

    private const val SCORE_VIEW_ID = 100
    private const val SCORE_WORD = 80
    private const val SCORE_POSITION = 40
    /** Candidato por posición fuera de la franja preferida pero dentro de la tolerada. */
    private const val SCORE_POSITION_WIDE = 25

    /**
     * Alto del encabezado de la ficha de Play Store, en dp. Es constante entre
     * equipos: barra de estado + barra de búsqueda + bloque de ícono/título +
     * fila de métricas. La fila de botones arranca justo debajo.
     *
     * PREFERRED es donde está en la enorme mayoría de los casos; BAND es hasta
     * dónde se la sigue aceptando (con menos puntaje) antes de darla por perdida.
     */
    private const val PREFERRED_BOTTOM_DP = 260f
    private const val BAND_BOTTOM_DP = 340f

    /** Nunca se considera botón lo que está pegado al borde de arriba (toolbar). */
    private const val BAND_TOP_FRACTION = 0.05f
    /** Ni lo que está pegado al borde de abajo (barra de navegación / gestos). */
    private const val BAND_BOTTOM_MAX_FRACTION = 0.95f

    /** Un botón real ocupa al menos esta fracción del ancho. Descarta íconos. */
    private const val MIN_BUTTON_WIDTH_FRACTION = 0.15f

    /**
     * Un texto más largo que esto es prosa (la descripción de la app, las novedades
     * de la versión), no una etiqueta de botón ni un mensaje de error de la tienda.
     * Se usa para no dejar que la descripción de una app dispare un diagnóstico.
     */
    private const val MAX_LABEL_LEN = 40
    private const val MAX_NOTICE_LEN = 90

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

    /**
     * Palabras de "abrir". Que aparezca una de estas SIN un candidato de acción es
     * la señal de "la app ya está al día".
     *
     * ⚠️ Acá NO puede entrar ninguna palabra que también aparezca en la pantalla de
     * iniciar sesión de Play Store. "iniciar" estaba en esta lista hasta el 3/9/2026
     * y hacía que "Iniciar sesión" —la pantalla que ve un equipo SIN cuenta de
     * Google, que es exactamente cómo se instala LockSuite— se leyera como el botón
     * "Abrir", con lo cual el flujo cerraba a los 1,5 s informando "la app ya está
     * actualizada". Un falso éxito, en el equipo donde justamente nunca se iba a
     * poder actualizar nada. Si hay que agregar un idioma, agregá el verbo "abrir",
     * no el verbo "entrar/iniciar/acceder".
     */
    private val OPEN_WORDS = setOf(
        "abrir", "open", "ouvrir", "aprire", "offnen", "oeffnen",
        "открыть", "פתח", "פתיחה", "فتح", "launch", "jugar", "play"
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
        "detener", "stop", "arreter", "pausar", "pause", "eliminar", "remove",
        // 3/9/2026: en la pantalla de "sin espacio", el botón que ofrece Play Store
        // abre el administrador de almacenamiento del sistema. Apretarlo saca al
        // usuario de la tienda y lo deja en Ajustes con la pantalla tapada.
        "liberar espacio", "liberar espaco", "free up space", "manage storage",
        "administrar almacenamiento", "gestionar almacenamiento"
    )

    // ──────────────────────────────────────────────
    // Diagnóstico de pantallas donde no se va a poder actualizar
    //
    // Todo esto es NUEVO el 3/9/2026. Antes, cualquiera de estas pantallas caía en
    // el mismo saco de "no reconocí ningún botón" y el flujo cerraba diciendo "la
    // app ya está actualizada" — mentira, y encima la mentira que hace que el dueño
    // busque el problema en el lugar equivocado.
    //
    // Los marcadores se comparan por PALABRA/FRASE COMPLETA (containsWord) contra
    // texto ya normalizado por fold(), y SOLO sobre nodos cortos (<= MAX_NOTICE_LEN):
    // la descripción de una app puede contener cualquier cosa, y un párrafo de
    // prosa no debe poder disparar un diagnóstico.
    // ──────────────────────────────────────────────

    /** Por qué Play Store no va a poder actualizar. NONE = nada anormal detectado. */
    enum class Diagnosis {
        NONE,
        /** No hay cuenta de Google en el equipo (o la sesión venció). */
        NEED_SIGN_IN,
        /** No hay espacio suficiente en el almacenamiento. */
        NO_SPACE,
        /** Play Store dice que la app no es compatible con este equipo. */
        NOT_COMPATIBLE,
        /** La ficha no existe: la app no está publicada / no está en este país. */
        NOT_FOUND,
        /** Error de red o del servidor de Play Store. */
        NETWORK_ERROR,
        /** La descarga quedó esperando Wi-Fi (o una condición de red). */
        WAITING_NETWORK,
        /** Error genérico de la tienda (DF-DFERH-01 y familia). */
        STORE_ERROR
    }

    private val SIGN_IN_MARKERS = setOf(
        "iniciar sesion", "inicia sesion", "iniciar sesao", "iniciar sessao",
        "sign in", "signin", "sign-in",
        "agregar cuenta", "anadir cuenta", "add account", "adicionar conta",
        "necesitas una cuenta", "you need a google account",
        "התחבר", "התחברות", "войти", "تسجيل الدخول"
    )

    private val NO_SPACE_MARKERS = setOf(
        "espacio insuficiente", "almacenamiento insuficiente",
        "no hay suficiente espacio", "no hay espacio suficiente",
        "sin espacio suficiente", "espaco insuficiente",
        "insufficient storage", "not enough space", "not enough storage",
        "no hay espacio", "necesitas mas espacio", "need more space",
        "libera espacio", "liberar espacio", "free up space",
        "нехватка места", "недостаточно места",
        "אין מספיק מקום", "מקום אחסון", "مساحة غير كافية"
    )

    private val NOT_COMPATIBLE_MARKERS = setOf(
        "no es compatible", "no compatible", "not compatible", "incompatible",
        "incompativel", "nao e compativel",
        "tu dispositivo no es compatible", "your device isnt compatible",
        "no disponible para tu dispositivo", "not available for your device",
        "no disponible en tu pais", "not available in your country",
        "لا يتوافق", "לא תואם", "несовместимо", "не поддерживается"
    )

    private val NOT_FOUND_MARKERS = setOf(
        "no se encontro", "no encontramos", "elemento no encontrado",
        "no se encontro la pagina", "pagina no encontrada",
        "not found", "item not found", "the requested url was not found",
        "nao encontrado", "nao foi encontrado",
        "לא נמצא", "не найдено", "غير موجود"
    )

    private val NETWORK_MARKERS = setOf(
        "sin conexion", "no hay conexion", "comprueba tu conexion",
        "verifica tu conexion", "revisa tu conexion",
        "no connection", "no internet connection", "check your connection",
        "sem conexao", "verifique sua conexao",
        "error de red", "network error",
        "нет подключения", "אין חיבור", "لا يوجد اتصال"
    )

    private val WAITING_NETWORK_MARKERS = setOf(
        "esperando wi-fi", "esperando wifi", "en espera de wi-fi",
        "waiting for wi-fi", "waiting for wifi", "waiting for network",
        "aguardando wi-fi", "esperando red", "descarga en espera",
        "ממתין לwi-fi", "ожидание wi-fi"
    )

    private val STORE_ERROR_MARKERS = setOf(
        "se ha producido un error", "se produjo un error", "algo salio mal",
        "something went wrong", "an error occurred",
        "vuelve a intentarlo mas tarde", "try again later",
        "error al recuperar informacion del servidor",
        "error retrieving information from server",
        "df-dferh-01", "rh-01", "df-dla-15", "rpc:s-5:aec-0",
        "ocorreu um erro", "произошла ошибка", "אירעה שגיאה"
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
         *
         * ⚠️ 3/9/2026: hasta hoy estas etiquetas se juntaban SOLO dentro de la
         * franja de botones, o sea que en el equipo donde la franja fallaba —el
         * único donde hacían falta— llegaban vacías al panel. Ahora se juntan de
         * toda la pantalla, con la posición anotada.
         */
        val debugLabels: List<String>,
        /**
         * Cuántos nodos con texto propio tiene la pantalla. Es cómo se distingue
         * "la ficha todavía no se dibujó" (equipo lento: el root existe pero está
         * vacío) de "la ficha se dibujó y no tiene botón de actualizar". Antes no
         * se distinguía, y en un equipo lento el flujo daba por "ya actualizada"
         * una ficha que todavía era una pantalla en blanco.
         */
        val renderedNodes: Int,
        /** Qué pantalla de error de Play Store se reconoció, si alguna. */
        val diagnosis: Diagnosis,
        /** El texto exacto que disparó el diagnóstico. Va al panel y al log. */
        val diagnosisEvidence: String?,
        /** Un nodo desplazable de la ficha, para bajar si el botón quedó fuera. */
        val scrollable: AccessibilityNodeInfo?
    ) {
        val isEmpty: Boolean get() = actions.isEmpty() && opens.isEmpty() && dialogs.isEmpty()
        /** true si la ficha ya tiene contenido dibujado (no es una pantalla en blanco). */
        val rendered: Boolean get() = renderedNodes >= PlayButtonFinder.MIN_RENDERED_NODES
    }

    /**
     * Debajo de esto la ficha se considera "todavía cargando". Una ficha de Play
     * Store dibujada tiene decenas de nodos con texto; una pantalla de carga tiene
     * la barra de búsqueda y poco más.
     */
    const val MIN_RENDERED_NODES = 6

    /**
     * @param density píxeles por dp (`resources.displayMetrics.density`). Es lo que
     *   permite medir el encabezado de la ficha en dp en vez de en fracción de
     *   pantalla — ver el comentario de cabecera.
     */
    fun scan(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        density: Float
    ): Result {
        val actions = ArrayList<Candidate>(8)
        val opens = ArrayList<Candidate>(4)
        val dialogs = ArrayList<Candidate>(4)
        val labels = ArrayList<String>(24)
        val notices = ArrayList<String>(24)
        val state = WalkState(screenWidth, screenHeight, density)

        if (root != null) {
            try {
                walk(root, 0, state, actions, opens, dialogs, labels, notices)
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

        val (diagnosis, evidence) = diagnose(notices)

        return Result(
            actions = actions.sortedWith(byConfidence),
            opens = opens.sortedWith(byConfidence),
            dialogs = dialogs.sortedWith(byConfidence),
            sawProgressBar = state.sawProgressBar,
            debugLabels = labels,
            renderedNodes = state.textNodes,
            diagnosis = diagnosis,
            diagnosisEvidence = evidence,
            scrollable = state.scrollable
        )
    }

    /**
     * Reconoce la pantalla de error de Play Store a partir de los textos cortos.
     * El orden importa: se devuelve la causa más específica y más accionable
     * primero. "Sin espacio" va antes que "error genérico" porque las dos pueden
     * estar en pantalla a la vez y la primera es la que el dueño puede resolver.
     */
    private fun diagnose(notices: List<String>): Pair<Diagnosis, String?> {
        if (notices.isEmpty()) return Diagnosis.NONE to null
        val tables = listOf(
            Diagnosis.NO_SPACE to NO_SPACE_MARKERS,
            Diagnosis.NEED_SIGN_IN to SIGN_IN_MARKERS,
            Diagnosis.NOT_COMPATIBLE to NOT_COMPATIBLE_MARKERS,
            Diagnosis.NOT_FOUND to NOT_FOUND_MARKERS,
            Diagnosis.WAITING_NETWORK to WAITING_NETWORK_MARKERS,
            Diagnosis.NETWORK_ERROR to NETWORK_MARKERS,
            Diagnosis.STORE_ERROR to STORE_ERROR_MARKERS
        )
        for ((diag, markers) in tables) {
            for (notice in notices) {
                for (m in markers) {
                    if (containsWord(notice, m)) return diag to notice
                }
            }
        }
        return Diagnosis.NONE to null
    }

    private class WalkState(val screenWidth: Int, val screenHeight: Int, density: Float) {
        var nodes = 0
        /** Nodos con texto o descripción propios: mide si la ficha ya se dibujó. */
        var textNodes = 0
        var sawProgressBar = false
        var scrollable: AccessibilityNodeInfo? = null
        val rect = Rect()

        private val dp = if (density > 0f) density else 1f

        /** Borde superior: se descarta la barra de estado y la de búsqueda. */
        val bandTop = (screenHeight * BAND_TOP_FRACTION).toInt()

        /**
         * Franja preferida y franja tolerada, medidas en dp desde arriba y topeadas
         * para que nunca se coman la pantalla entera. En un teléfono normal esto es
         * MÁS estricto que la fracción vieja del 62 %; en una pantalla chica (un
         * flip de 2,8") es mucho más permisivo, que es justo lo que hacía falta.
         */
        val preferredBottom = minOf(
            (PREFERRED_BOTTOM_DP * dp).toInt(),
            (screenHeight * BAND_BOTTOM_MAX_FRACTION).toInt()
        )
        val bandBottom = minOf(
            maxOf((BAND_BOTTOM_DP * dp).toInt(), preferredBottom),
            (screenHeight * BAND_BOTTOM_MAX_FRACTION).toInt()
        )

        val minWidth = (screenWidth * MIN_BUTTON_WIDTH_FRACTION).toInt()
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        state: WalkState,
        actions: MutableList<Candidate>,
        opens: MutableList<Candidate>,
        dialogs: MutableList<Candidate>,
        labels: MutableList<String>,
        notices: MutableList<String>
    ) {
        if (node == null || depth > MAX_DEPTH || state.nodes >= MAX_NODES) return
        state.nodes++

        val className = node.className?.toString() ?: ""
        if (className.endsWith("ProgressBar")) state.sawProgressBar = true
        if (state.scrollable == null && node.isScrollable) state.scrollable = node

        val rawText = node.text?.toString()?.trim() ?: ""
        val rawDesc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        node.getBoundsInScreen(state.rect)
        val top = state.rect.top
        val left = state.rect.left
        val width = state.rect.width()
        val onScreen = !state.rect.isEmpty && top >= 0 && top < state.screenHeight
        val inPreferred = onScreen && top >= state.bandTop && top <= state.preferredBottom
        val inBand = onScreen && top >= state.bandTop && top <= state.bandBottom

        val probe = fold(if (rawText.isNotEmpty()) rawText else rawDesc)
        if (probe.isNotEmpty()) state.textNodes++

        // Texto candidato a mensaje de la tienda. Solo textos cortos: un párrafo de
        // la descripción de la app no puede disparar un diagnóstico.
        if (probe.isNotEmpty() && probe.length <= MAX_NOTICE_LEN && notices.size < 40) {
            notices.add(probe)
        }
        // El contentDescription y el texto pueden decir cosas distintas (Compose
        // suele poner el mensaje en uno y el título en el otro): se miran los dos.
        if (rawText.isNotEmpty() && rawDesc.isNotEmpty() && notices.size < 40) {
            val alt = fold(rawDesc)
            if (alt.length <= MAX_NOTICE_LEN && alt != probe) notices.add(alt)
        }

        val forbidden = probe.isNotEmpty() && FORBIDDEN_WORDS.any { containsWord(probe, it) }
        val clickable = node.isClickable || className.endsWith("Button")

        // Diagnóstico: etiquetas de lo clickeable, de TODA la pantalla.
        //
        // 3/9/2026: antes se exigía `inBand`. En el equipo donde la franja fallaba
        // —el único donde estas etiquetas hacen falta— llegaban vacías al panel.
        // Ahora se anota además la posición en dp desde arriba, que es el dato que
        // permite ver desde el panel si la franja está bien calibrada.
        if (clickable && probe.isNotEmpty() && probe.length <= MAX_LABEL_LEN && labels.size < 20) {
            val where = if (inPreferred) "" else if (inBand) " @${top}px" else " @${top}px!"
            val tag = if (viewId.isNotEmpty())
                "$probe [${viewId.substringAfterLast('/')}]$where"
            else "$probe$where"
            if (!labels.contains(tag)) labels.add(tag)
        }

        if (!forbidden) {
            // 1. Por ID de vista — lo más confiable donde existe.
            if (viewId in ACTION_VIEW_IDS && onScreen) {
                actions.add(Candidate(node, SCORE_VIEW_ID, "viewId=$viewId", top, left))
            } else if (probe.isNotEmpty() && probe.length <= MAX_LABEL_LEN) {
                // 2. Por palabra completa. El tope de caracteres evita que un
                //    párrafo de la descripción de la app entre como candidato.
                //
                //    Las palabras de acción se exigen DENTRO de la franja tolerada:
                //    es lo que impide agarrar el "Instalar" de un carrusel de "apps
                //    similares" que está más abajo (bug 4 de B.9). Con la franja
                //    ahora medida en dp, eso sigue protegiendo en un teléfono
                //    normal sin descartar el botón bueno en una pantalla chica.
                when {
                    ACTION_WORDS.any { containsWord(probe, it) } ->
                        if (inBand || !onScreen) {
                            actions.add(
                                Candidate(
                                    node,
                                    if (inPreferred || !onScreen) SCORE_WORD else SCORE_WORD - 10,
                                    "texto='$probe'", top, left
                                )
                            )
                        }
                    OPEN_WORDS.any { containsWord(probe, it) } ->
                        if (inBand) opens.add(Candidate(node, SCORE_WORD, "texto='$probe'", top, left))
                    DIALOG_WORDS.any { containsWord(probe, it) } ->
                        // Los diálogos y hojas inferiores aparecen abajo, así que
                        // acá NO se exige estar en la franja de botones.
                        if (clickable) dialogs.add(Candidate(node, SCORE_WORD, "dialogo='$probe'", top, left))
                }
            }

            // 3. Por posición — la red que salva un idioma que nadie previó.
            //    Cualquier botón ancho de la franja es candidato; el orden y la
            //    verificación posterior deciden cuál es. Fuera de la franja
            //    preferida entra igual pero con menos puntaje, así que solo se
            //    prueba cuando no hay nada mejor.
            if (inBand && clickable && width >= state.minWidth &&
                actions.none { it.node == node } &&
                opens.none { it.node == node }
            ) {
                val score = if (inPreferred) SCORE_POSITION else SCORE_POSITION_WIDE
                actions.add(Candidate(node, score, "posicion top=$top", top, left))
            }
        }

        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, state, actions, opens, dialogs, labels, notices)
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
     *
     * Sirve igual para frases ("espacio insuficiente"): el chequeo de borde se hace
     * en el primer y el último carácter de la frase entera.
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
