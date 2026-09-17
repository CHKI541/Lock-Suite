package com.ejemplo.locksuite.mdm

import android.content.Context
import com.ejemplo.locksuite.util.PrefsHelper

/**
 * Layer3Audit — el registro de "qué cerró la Capa 3, cuándo y por qué" (16/9/2026).
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * POR QUÉ EXISTE, Y POR QUÉ ES LA PIEZA QUE FALTABA
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * El servicio de Accesibilidad tiene hoy **nueve** lugares distintos que pueden
 * mandar `GLOBAL_ACTION_BACK` o `GLOBAL_ACTION_HOME`: WebView por app, el detector
 * estructural de navegadores, ofertas de Mercado Pago, Estados/Canales de WhatsApp,
 * el selector de foto, la cuenta de Google, el menú de Accesibilidad, las pantallas
 * legales y el portal cautivo. Para el usuario los nueve se ven **exactamente igual**:
 * la app se cierra sola y no dice nada.
 *
 * Cada uno de esos rebotes escribe algo en `logcat`, con su propio formato, y algunos
 * publican un diagnóstico propio al panel (`debugLabels` en B.41,
 * `googleAccountWebSeenClasses` en B.43, `photoPickerSeenClasses` en B.47). O sea que
 * la solución se redescubrió tres veces **por función**, y nunca hubo una respuesta a
 * la pregunta que en realidad trae el dueño: *"se me cierra esta app, ¿por qué?"*.
 *
 * Hoy, con el reporte de Tefilon, eso costó una sesión entera: hubo que bajar el APK
 * de la Tienda y leer su manifiesto para descubrir que el culpable era el marcador
 * `"artactivity"` matcheando `StartActivity` (ver `PhotoPickerPolicy`). Con este
 * registro, el dueño abría la app una vez, miraba el panel y ahí estaba la línea:
 *
 *     selector-de-foto · tfilon.tfilon · TfilonStartActivity · marcador «artactivity»
 *
 * Y peor: `photoPickerSeenClasses` **no habría servido igual**, porque solo anota las
 * clases de paquetes que ya considera "relevantes" — y Tefilon no lo es. El
 * diagnóstico existía y era ciego justo para el caso que hacía falta. Este registro
 * anota **lo que efectivamente se rebotó**, sin filtro previo, que es el dato que
 * importa cuando algo se cierra y nadie sabe por qué.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * TRES COSAS QUE NO HAY QUE "SIMPLIFICAR"
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * 1. **Persiste en disco, no en memoria.** `EmbeddedBrowserDetector` guarda su
 *    auditoría en un mapa en memoria, y eso está bien para lo suyo; acá no alcanza:
 *    Android recrea los servicios de accesibilidad seguido (B.54 punto 2), y un
 *    registro que se borra solo justo cuando el equipo está inestable no contesta
 *    nada. Son 20 renglones de texto en `SharedPreferences`.
 *
 * 2. **Se anota también lo que NO se bloqueó, cuando estuvo cerca.** Saber que una
 *    pantalla legítima pasó raspando es lo que permite corregir un umbral antes de
 *    que alguien lo reporte. La marca `bloqueado` distingue una cosa de la otra.
 *
 * 3. **Se agrupa por repetición en vez de acumular.** Un rebote que entra en bucle
 *    llenaría el registro con la misma línea veinte veces y taparía todo lo demás —
 *    que es justo el caso en el que uno necesita ver el registro. La cuenta de
 *    repeticiones es además el dato que dice "esto pasa todo el tiempo" contra "pasó
 *    una vez".
 */
object Layer3Audit {

    /** Alcanza para ver un patrón sin convertir la preferencia en un archivo de log. */
    private const val MAX_ENTRADAS = 20

    private const val CLAVE = "layer3_audit"

    /** Separadores. Se limpian de los campos para que una línea no se pueda romper. */
    private const val SEP_CAMPO = "|"
    private const val SEP_LINEA = "\n"

    private val candado = Any()

    /**
     * Una cosa que la Capa 3 hizo (o estuvo a punto de hacer).
     *
     * @param origen     qué mecanismo fue: `selector-de-foto`, `mp-ofertas`, `webview`…
     *                   Es la palabra que le dice al administrador dónde ir a mirar.
     * @param paquete    la app afectada.
     * @param detalle    la clase de la ventana, o lo que identifique la pantalla.
     * @param motivo     por qué se tomó la decisión, en castellano.
     * @param bloqueado  true si se rebotó de verdad; false si solo se registró.
     * @param veces      cuántas veces se repitió exactamente lo mismo.
     * @param ultimaVez  epoch millis de la última vez.
     */
    class Entrada(
        val origen: String,
        val paquete: String,
        val detalle: String,
        val motivo: String,
        val bloqueado: Boolean,
        val veces: Int,
        val ultimaVez: Long
    )

    /**
     * Anota. Barato a propósito: los rebotes son raros (todos tienen antirrebote), así
     * que un `apply()` por rebote no se nota. **No llamar desde el camino caliente de
     * `onAccessibilityEvent`** — solo desde el momento en que se decide actuar.
     */
    fun anotar(
        context: Context,
        origen: String,
        paquete: String?,
        detalle: String?,
        motivo: String,
        bloqueado: Boolean
    ) {
        try {
            synchronized(candado) {
                val prefs = PrefsHelper.getMdmPrefs(context)
                val previas = leerCrudo(prefs.getString(CLAVE, "") ?: "")

                val o = limpiar(origen)
                val p = limpiar(paquete ?: "?")
                val d = limpiar(detalle ?: "")
                val m = limpiar(motivo)
                val ahora = System.currentTimeMillis()

                // Agrupar por repetición: misma decisión sobre la misma pantalla.
                val salida = ArrayList<Entrada>(MAX_ENTRADAS)
                var yaEstaba = false
                for (e in previas) {
                    if (!yaEstaba && e.origen == o && e.paquete == p && e.detalle == d && e.motivo == m) {
                        yaEstaba = true
                        salida.add(
                            Entrada(o, p, d, m, e.bloqueado || bloqueado, e.veces + 1, ahora)
                        )
                    } else {
                        salida.add(e)
                    }
                }
                if (!yaEstaba) salida.add(Entrada(o, p, d, m, bloqueado, 1, ahora))

                // Más reciente primero, y se tira lo más viejo.
                salida.sortByDescending { it.ultimaVez }
                while (salida.size > MAX_ENTRADAS) salida.removeAt(salida.size - 1)

                prefs.edit().putString(CLAVE, serializar(salida)).apply()
            }
        } catch (e: Exception) {
            // Un registro de diagnóstico no puede tumbar un rebote de seguridad.
        }
    }

    /** Lo anotado, más reciente primero. */
    fun instantanea(context: Context): List<Entrada> = try {
        leerCrudo(PrefsHelper.getMdmPrefs(context).getString(CLAVE, "") ?: "")
    } catch (e: Exception) {
        emptyList()
    }

    /** La cadena tal cual viaja al panel. Vacía si no pasó nada. */
    fun crudo(context: Context): String = try {
        PrefsHelper.getMdmPrefs(context).getString(CLAVE, "") ?: ""
    } catch (e: Exception) {
        ""
    }

    fun limpiarTodo(context: Context) {
        try {
            synchronized(candado) {
                PrefsHelper.getMdmPrefs(context).edit().remove(CLAVE).apply()
            }
        } catch (e: Exception) {
            // idem
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Serialización. Texto plano a propósito: se lee de un vistazo en el panel y
    // en `adb shell dumpsys`, y no arrastra ninguna dependencia de JSON.
    // ─────────────────────────────────────────────────────────────────────────

    /** Visible para el banco de pruebas. */
    internal fun serializar(entradas: List<Entrada>): String =
        entradas.joinToString(SEP_LINEA) { e ->
            listOf(
                e.ultimaVez.toString(),
                e.origen,
                e.paquete,
                e.detalle,
                e.motivo,
                if (e.bloqueado) "1" else "0",
                e.veces.toString()
            ).joinToString(SEP_CAMPO)
        }

    /** Visible para el banco de pruebas. Una línea mal formada se ignora, no rompe. */
    internal fun leerCrudo(texto: String): List<Entrada> {
        if (texto.isEmpty()) return emptyList()
        val fuera = ArrayList<Entrada>(MAX_ENTRADAS)
        for (linea in texto.split(SEP_LINEA)) {
            if (linea.isEmpty()) continue
            val c = linea.split(SEP_CAMPO)
            if (c.size < 7) continue
            val ts = c[0].toLongOrNull() ?: continue
            val veces = c[6].toIntOrNull() ?: 1
            fuera.add(Entrada(c[1], c[2], c[3], c[4], c[5] == "1", veces, ts))
        }
        return fuera
    }

    private fun limpiar(s: String): String =
        s.replace(SEP_CAMPO, "/").replace("\n", " ").replace("\r", " ").trim().take(120)
}
