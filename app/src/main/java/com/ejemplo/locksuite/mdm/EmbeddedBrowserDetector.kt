package com.ejemplo.locksuite.mdm

/**
 * DETECTOR ESTRUCTURAL DE NAVEGADORES EMBEBIDOS (10/9/2026, B.60)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * El "IAB Finder" de MB Smart, traído a LockSuite. Cierra B.44 de raíz.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EL PROBLEMA, TAL COMO LO DEJÓ ESCRITO B.44
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * La Capa 3 es una lista de apps CONOCIDAS y el bloqueo de WebView es **opt-in por
 * app**: `handleWebViewBlocking()` arranca con
 * `if (!WebViewBlockManager.isBlocked(this, packageName)) return`. O sea que la
 * postura por omisión del equipo es **permitir**, y cada agujero nuevo es "una app
 * en la que no pensamos". B.44 los enumeró —la app de Google, "Ayuda y comentarios",
 * el selector de fondos, Gboard, Instant Apps, el salvapantallas, Takeout, Maps, los
 * de fabricante— y van a seguir apareciendo indefinidamente.
 *
 * ⚠️ **El dueño YA RECHAZÓ la lista blanca global de WebView** que proponía B.44
 * (textual: "lista blanca no quiero"; registrado en B.45). Esto NO la necesita: en
 * vez de una lista de nombres, usa una señal ESTRUCTURAL — que es exactamente la
 * regla que el proyecto ya tiene escrita en B.19 punto 3: *"si hay una señal
 * estructural —clase de actividad, view-id, componente resuelto— usarla antes que
 * una palabra"*.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ SE CONSIDERA UN NAVEGADOR EMBEBIDO, Y POR QUÉ ESA Y NO OTRA DEFINICIÓN
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * **Un WebView no es el problema. Un WebView por el que el usuario puede ir a
 * cualquier lado, sí.** Media app de este catálogo tiene WebViews adentro: la
 * pantalla de ayuda de Waze, los términos y condiciones de Mercado Pago, la ficha de
 * una app en Play Store. Bloquear "todo lo que tenga un WebView" rompería el equipo
 * entero y sería la lista blanca por la puerta de atrás.
 *
 * Lo que distingue a un navegador embebido es que **le da al usuario una forma de
 * elegir a dónde ir**: una barra de direcciones donde escribir, o el juego completo
 * de controles de navegación (atrás / adelante / recargar / abrir en el navegador).
 * Eso es una señal de estructura, no una palabra ni un nombre de paquete, y por eso
 * sirve para apps en las que nadie pensó.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ ES UNA FUNCIÓN PURA
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Sin un solo import de Android. Entra un RETRATO del árbol de vistas —que el
 * servicio arma recorriéndolo una vez— y sale un veredicto. Así se le pueden correr
 * aserciones de comportamiento de verdad fuera del equipo, que es lo único que
 * agarra los errores que importan sin Gradle: es lo que se hizo con
 * `WhitelistManager.buildRules()` (B.53), `EnrollmentProfiles.buildData()` (B.57),
 * `ApkChecksum` (B.58) y `AppRequestManager` (B.59).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ EL FALSO POSITIVO ES CARO. ESTE PROYECTO YA LO PAGÓ TRES VECES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Si el detector confunde una app legítima con un navegador, esa app deja de
 * funcionar y **nadie sabe por qué**:
 *
 *   · B.43 — bloqueó la administración entera de la cuenta de Google.
 *   · B.50 — dejó a un usuario sin poder conectarse al Wi-Fi de un aeropuerto y de
 *            un avión, porque le pintaba de negro la página de inicio de sesión.
 *   · B.15 punto 1 — casi no se podía abrir Ajustes.
 *
 * Por eso, tres decisiones de diseño que **no hay que "simplificar"**:
 *
 * 1. **Interruptor propio, APAGADO POR DEFECTO** ([KEY_ENABLED]).
 * 2. **MODO SIMULACIÓN primero** ([KEY_SIMULATION]): no bloquea, publica al panel
 *    qué HABRÍA bloqueado. Es la parte que hizo usable el modo lista blanca entero
 *    en B.53, y acá importa más todavía porque el universo de apps es abierto.
 * 3. **Las exclusiones se evalúan ANTES que cualquier otra cosa** y viven en la
 *    función pura, no en el servicio, para que el banco de pruebas pueda afirmar que
 *    siguen pasando de largo. Son las tres que romperían el equipo si fallaran:
 *      · **el portal cautivo** — su contenido ENTERO es un WebView, y taparlo dejó a
 *        alguien sin poder conectarse (B.46/B.50);
 *      · **el alta de cuenta de Google** (`auth.uiflows.minutemaid`) — LockSuite se
 *        instala con el equipo SIN cuenta, así que ese flujo es **el estado de
 *        fábrica del procedimiento**, no un caso raro (B.43, y el mismo error de
 *        razonamiento que costó B.41 punto 3);
 *      · **`:admin-app`** — es a propósito un WebView del panel; bloquearlo sería
 *        que el administrador no pueda administrar.
 *
 * ⚠️ Y OJO CON B.45 PUNTO 4: `googlequicksearchbox` ya está en
 * `KNOWN_BROWSER_PACKAGES`, y en `handleWebViewBlocking` "ser navegador" significa
 * otra cosa de lo que parece. Este detector corre **después** de esas dos ramas y
 * solo sobre apps que NO están marcadas en `WebViewBlockManager`: no las pisa.
 */
object EmbeddedBrowserDetector {

    /** Interruptor del detector. Ausente = apagado. Ver punto 1 de arriba. */
    const val KEY_ENABLED = "iab_finder_enabled"

    /** Modo simulación: no bloquea, solo anota. Ausente = **simulación**. */
    const val KEY_SIMULATION = "iab_finder_simulation"

    /**
     * Fracción mínima de la pantalla que tiene que ocupar el WebView. Por debajo de
     * esto es un banner, un mapa embebido, un anuncio o una tarjeta: no es una
     * pantalla de navegación y no se mira.
     */
    const val FRACCION_MINIMA = 0.45f

    enum class Veredicto {
        /** No hay WebView, o está excluido, o es demasiado chico. */
        NO_ES_NAVEGADOR,

        /** Hay un WebView grande, pero el usuario no puede elegir a dónde ir. */
        WEBVIEW_DE_CONTENIDO,

        /** Hay un WebView y una forma de navegar libremente. */
        NAVEGADOR_EMBEBIDO
    }

    /**
     * Retrato del árbol de vistas. Lo arma el servicio en UN recorrido, con el tope
     * de profundidad y el presupuesto de nodos que ya usan todos los recorridos del
     * archivo (`MAX_TREE_DEPTH = 40`, `MAX_NODES_PER_SCAN = 2500`).
     *
     * Todo lo que entra acá es texto ya extraído: esta clase nunca toca un
     * `AccessibilityNodeInfo`, que es lo que la mantiene pura y probable.
     */
    data class Retrato(
        val packageName: String,
        /** Clase de la actividad resuelta, si se conoce. Señal estructural (B.19 p.3). */
        val activityClass: String = "",
        /** ¿La ventana actual es la del portal cautivo? La decide el servicio. */
        val esPortalCautivo: Boolean = false,
        val tieneWebView: Boolean = false,
        /** Fracción de la pantalla que ocupa el WebView más grande, 0..1. */
        val fraccionWebView: Float = 0f,
        /** `viewIdResourceName` de los nodos, ya en minúsculas. */
        val idsDeVista: List<String> = emptyList(),
        /** Texto de los campos EDITABLES visibles. */
        val textosEditables: List<String> = emptyList(),
        /** `contentDescription` de los botones/imágenes, ya en minúsculas. */
        val descripciones: List<String> = emptyList()
    )

    /**
     * Paquetes que nunca se miran, con el motivo al lado. Una lista de excepciones
     * sin motivos escritos se convierte en el lugar donde se esconden los bugs.
     */
    private val PAQUETES_EXENTOS = mapOf(
        // Es a propósito un WebView del panel: bloquearlo es que el administrador no
        // pueda administrar. Ver la sección ":admin-app" del contexto.
        "com.ejemplo.locksuite.admin" to "es el panel de administración",
        "com.ejemplo.locksuite" to "es LockSuite",
        // El flujo de actualización de Play Store lo maneja la Capa 3 con su propia
        // automatización (UpdateFlowManager, B.14/B.41/B.54). Meter otro detector en
        // esa pantalla es pelearse con el flujo propio.
        "com.android.vending" to "su flujo lo maneja UpdateFlowManager"
    )

    /**
     * Fragmentos de clase de actividad que marcan un flujo del sistema que **no se
     * toca**. Se comparan en minúsculas y por contenido, porque el nombre completo
     * cambia entre versiones de Android y de Play Services.
     */
    private val ACTIVIDADES_EXENTAS = listOf(
        // Alta de cuenta de Google. B.43: el equipo se instala SIN cuenta, así que
        // este flujo es el estado de fábrica del procedimiento.
        "minutemaid",
        "auth.uiflows",
        // Asistente de configuración inicial del equipo.
        "setupwizard",
        "com.android.settings.suggested",
        // La ventana del portal cautivo, además del flag de [Retrato.esPortalCautivo]:
        // dos caminos para lo mismo porque es la exclusión que ya rompió el equipo una
        // vez (B.50) y no conviene que dependa de una sola señal.
        "captiveportal"
    )

    /**
     * `viewIdResourceName` que solo aparecen en una barra de direcciones de verdad.
     *
     * ⚠️ Acá NO va `search_box` ni `search_bar`, y es deliberado: media app de
     * compras tiene un WebView y un buscador propio, y ese buscador **no** deja ir a
     * cualquier lado. Meterlo sería estrenar el cuarto falso positivo caro del
     * proyecto.
     */
    private val IDS_BARRA_URL = listOf(
        "url_bar", "urlbar", "url_field", "url_input", "edit_url",
        "omnibox", "address_bar", "addressbar", "location_bar", "toolbar_url"
    )

    /**
     * Controles de navegación, **agrupados por FUNCIÓN**. Se piden dos funciones
     * DISTINTAS, no dos palabras.
     *
     * ⚠️ Que estén agrupados no es cosmético: el banco de pruebas encontró que una
     * lista plana daba un falso positivo carísimo. `SwipeRefreshLayout` es el widget
     * de "deslizar para actualizar" que tiene media app de Android, y su view-id
     * (`swipe_refresh_layout`) contiene `refresh`; si además la pantalla tiene un
     * botón cuya descripción dice `recargar`, una lista plana cuenta DOS y bloquea la
     * app. Pero `refresh` y `recargar` **son el mismo control en dos idiomas**. Con
     * las funciones agrupadas, eso cuenta uno y no alcanza — que es lo correcto.
     *
     * Por el mismo motivo `atrás`/`back` no figura en ninguna lista: está en
     * absolutamente todas las pantallas de Android.
     */
    private val CONTROLES_NAVEGACION = mapOf(
        "adelante" to listOf("forward", "adelante"),
        "recargar" to listOf("reload", "refresh", "recargar", "actualizar"),
        "abrir_afuera" to listOf("open_in_browser", "abrir en el navegador", "open in browser",
                                 "open_in_new", "abrir en chrome"),
        "detener" to listOf("stop_loading", "detener carga"),
        "favorito" to listOf("bookmark", "favorito"),
        "compartir_enlace" to listOf("copy_link", "copiar enlace", "copy_url", "copiar dirección")
    )

    /**
     * ¿Este texto parece una dirección web? Pura y separada para poder afirmarla
     * sola: es la mitad de la señal más fuerte del detector.
     *
     * A propósito NO alcanza con "tiene un punto": `Hola. Qué tal` tiene un punto, y
     * `3.14` también. Se exige forma de host —sin espacios, con una etiqueta final
     * alfabética de 2 a 24 letras— o un esquema explícito.
     */
    fun pareceUrl(texto: String?): Boolean {
        val t = texto?.trim()?.lowercase() ?: return false
        if (t.isEmpty() || t.length > 2048) return false
        if (t.startsWith("http://") || t.startsWith("https://")) return true
        if (t.contains(' ') || t.contains('\n')) return false
        val host = t.substringBefore('/').substringBefore('?').substringBefore(':')
        if (!host.contains('.')) return false
        val etiquetas = host.split('.')
        if (etiquetas.size < 2) return false
        if (etiquetas.any { it.isEmpty() }) return false
        val tld = etiquetas.last()
        if (tld.length < 2 || tld.length > 24) return false
        if (!tld.all { it in 'a'..'z' }) return false
        // El resto de las etiquetas: letras, dígitos o guiones. Así `3.14` y `1.2.3.4`
        // quedan afuera por el TLD, y `mi_carpeta.txt` por el guión bajo.
        return etiquetas.dropLast(1).all { e ->
            e.isNotEmpty() && e.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
    }

    /** ¿Alguno de estos textos, o de los ids, contiene alguno de esos fragmentos? */
    private fun contieneAlguno(donde: List<String>, que: List<String>): Boolean =
        donde.any { d -> que.any { q -> d.contains(q) } }

    /**
     * Cuántas FUNCIONES distintas de navegación aparecen en la pantalla. Una función
     * cuenta una sola vez por más sinónimos suyos que haya: ver el comentario de
     * [CONTROLES_NAVEGACION].
     */
    private fun contarFuncionesDeNavegacion(r: Retrato): Int =
        CONTROLES_NAVEGACION.count { (_, sinonimos) ->
            sinonimos.any { s ->
                r.idsDeVista.any { it.contains(s) } || r.descripciones.any { it.contains(s) }
            }
        }

    /**
     * **LA FUNCIÓN PURA CENTRAL.**
     *
     * El orden de las comprobaciones no es arbitrario: las exclusiones van primero y
     * cortan del todo, porque un falso positivo en cualquiera de ellas deja el equipo
     * inusable (B.43, B.50). Recién después se mira si hay WebView, si es grande, y
     * al final si hay señales de navegación.
     */
    fun evaluar(r: Retrato): Veredicto {
        // ── 1. Exclusiones duras. Siempre primero. ──
        if (r.esPortalCautivo) return Veredicto.NO_ES_NAVEGADOR
        if (PAQUETES_EXENTOS.containsKey(r.packageName)) return Veredicto.NO_ES_NAVEGADOR
        val actividad = r.activityClass.lowercase()
        if (ACTIVIDADES_EXENTAS.any { actividad.contains(it) }) return Veredicto.NO_ES_NAVEGADOR

        // ── 2. Sin WebView no hay nada que mirar. ──
        if (!r.tieneWebView) return Veredicto.NO_ES_NAVEGADOR

        // ── 3. Un WebView chico es un banner, un mapa o una tarjeta. ──
        if (r.fraccionWebView < FRACCION_MINIMA) return Veredicto.NO_ES_NAVEGADOR

        // ── 4. ¿Le da al usuario una forma de elegir a dónde ir? ──
        //
        // Señal A: una barra de direcciones identificada por su view-id. Es
        // estructural y muy específica, así que alcanza sola.
        if (contieneAlguno(r.idsDeVista, IDS_BARRA_URL)) return Veredicto.NAVEGADOR_EMBEBIDO

        // Señal B: un campo EDITABLE que hoy contiene algo con forma de dirección. Que
        // sea editable es lo que importa: un texto de solo lectura con la URL es un
        // subtítulo informativo (los WebViews de contenido lo muestran seguido), y
        // bloquear por eso sería el falso positivo caro.
        if (r.textosEditables.any { pareceUrl(it) }) return Veredicto.NAVEGADOR_EMBEBIDO

        // Señal C: el juego de controles de un navegador. Se piden DOS FUNCIONES
        // distintas (ver el comentario de CONTROLES_NAVEGACION: contar palabras en vez
        // de funciones bloqueaba cualquier app con `SwipeRefreshLayout` y un botón que
        // dijera "recargar"). Con una función sola no alcanza: "recargar" hay en
        // cualquier pantalla con lista, y "atrás" está en todas.
        if (contarFuncionesDeNavegacion(r) >= 2) return Veredicto.NAVEGADOR_EMBEBIDO

        // ── 5. Hay un WebView grande, pero el usuario no puede ir a otro lado. ──
        return Veredicto.WEBVIEW_DE_CONTENIDO
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AUDITORÍA — la mitad que hace usable el modo entero
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Sin esto, encender el detector es una apuesta a ciegas sobre un universo de
    // apps abierto: el administrador no tiene forma de saber qué se rompería antes
    // de romperlo. Con esto, se enciende en simulación, se usa el equipo unos días,
    // y el panel muestra la lista exacta de lo que HABRÍA bloqueado. Es la misma
    // pieza que hizo usable el modo lista blanca en B.53 — y acá pesa más todavía,
    // porque allá la lista de apps era cerrada y acá no.
    //
    // Vive en este archivo, y no en WhitelistManager, porque el dato es de otra
    // forma (app + motivo, no dominio + golpes) y mezclarlos ensucia las dos cosas.
    // Sigue sin depender de Android: es un mapa sincronizado y nada más.

    /** Tope de apps distintas anotadas. Más que esto no aporta y ocupa memoria. */
    const val MAX_AUDITORIA = 60

    private val auditLock = Any()
    private val auditoria = LinkedHashMap<String, Anotacion>()
    @Volatile private var auditDescartadas = 0

    data class Anotacion(
        val packageName: String,
        val motivo: String,
        /** false = se vio en simulación y NO se bloqueó. */
        val bloqueado: Boolean,
        val veces: Int
    )

    /** Anota una detección. La llama el servicio de accesibilidad. */
    fun anotar(packageName: String, motivo: String, bloqueado: Boolean) {
        synchronized(auditLock) {
            val previa = auditoria[packageName]
            if (previa == null && auditoria.size >= MAX_AUDITORIA) {
                auditDescartadas++
                return
            }
            auditoria[packageName] = Anotacion(
                packageName = packageName,
                motivo = motivo,
                // Si alguna vez se bloqueó de verdad, la anotación queda marcada como
                // bloqueada aunque después se pase a simulación: al administrador le
                // importa saber que a esa app le pasó algo, no cuál fue la última vez.
                bloqueado = bloqueado || (previa?.bloqueado ?: false),
                veces = (previa?.veces ?: 0) + 1
            )
        }
    }

    /** Lo anotado, de más visto a menos. Orden estable para que la prueba sea fiable. */
    fun instantanea(): List<Anotacion> = synchronized(auditLock) {
        auditoria.values.sortedWith(compareByDescending<Anotacion> { it.veces }.thenBy { it.packageName })
    }

    /** Cuántas apps distintas no entraron por el tope. */
    fun descartadas(): Int = auditDescartadas

    fun limpiarAuditoria() {
        synchronized(auditLock) {
            auditoria.clear()
            auditDescartadas = 0
        }
    }

    /** El motivo del veredicto, para el registro y para la auditoría del panel. */
    fun motivo(r: Retrato): String {
        if (r.esPortalCautivo) return "portal cautivo"
        PAQUETES_EXENTOS[r.packageName]?.let { return "exento: $it" }
        val actividad = r.activityClass.lowercase()
        ACTIVIDADES_EXENTAS.firstOrNull { actividad.contains(it) }?.let { return "exento: actividad $it" }
        if (!r.tieneWebView) return "sin WebView"
        if (r.fraccionWebView < FRACCION_MINIMA) return "WebView chico (${(r.fraccionWebView * 100).toInt()}%)"
        if (contieneAlguno(r.idsDeVista, IDS_BARRA_URL)) return "barra de direcciones por view-id"
        if (r.textosEditables.any { pareceUrl(it) }) return "campo editable con una dirección"
        val controles = contarFuncionesDeNavegacion(r)
        if (controles >= 2) return "$controles funciones de navegación"
        return "WebView de contenido"
    }
}
