package com.ejemplo.locksuite.ui.launcher

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * NokiaIconSet — juego de íconos estilo teléfono de teclas (2/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ ES Y POR QUÉ NO ES UNA COPIA
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * El pedido del dueño fue: *"que las apps que se pueda tenga iconos como de nokia
 * viejos (no como este launcher que deja los mismos, cambia los contactos, llamadas
 * etc, todo lo que existe en nokia)"*, tomando como referencia KeyLauncher.
 *
 * **Esto no copia los íconos de KeyLauncher ni de Nokia.** Copiar los dibujos de otra
 * app sería usar material ajeno, y encima no haría falta: lo que da el aire de teléfono
 * de teclas no es el dibujo exacto sino tres decisiones de estilo, y esas sí se pueden
 * reproducir:
 *
 *   1. **Una silueta plana, gruesa y blanca** sobre un fondo de color liso. Nada de
 *      degradados, sombras ni volumen: los Series 40 no tenían con qué dibujarlos.
 *   2. **Una paleta corta y saturada** — verde teléfono, azul mensajes, ámbar,
 *      rojo — repetida en todo el menú, en vez de un color por app.
 *   3. **Un ícono por FUNCIÓN, no por app.** Es el punto exacto del reclamo: da igual
 *      qué app de contactos tenga el equipo, en el menú tiene que aparecer "Contactos"
 *      con la silueta de la agenda. El launcher de referencia deja el ícono original de
 *      cada app y por eso no parece un Nokia.
 *
 * Los dibujos salen de Material Icons, que ya está en el proyecto: son siluetas planas
 * de un solo trazo, que es justo la forma correcta. Lo que hace el estilo es cómo se
 * presentan (`NokiaKeypadScreen` los pinta en blanco sobre un cuadrado de color liso con
 * esquinas apenas redondeadas), no de dónde salen.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CÓMO AGREGAR UNA APP
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Sumá una entrada a `REGLAS`. El orden importa: se devuelve la PRIMERA que coincida,
 * así que las reglas específicas van antes que las genéricas. Las palabras se comparan
 * en minúscula contra el nombre del paquete y contra la etiqueta visible de la app, así
 * que conviene poner variantes en español, inglés y hebreo — el equipo puede estar en
 * cualquiera de los tres.
 */
data class NokiaIcon(
    val label: String,
    val icon: ImageVector,
    val tile: Color
)

object NokiaIconSet {

    // Paleta corta y saturada, estilo Series 40. Se repite a propósito.
    private val VERDE = Color(0xFF2E9E4F)     // llamadas, teléfono
    private val AZUL = Color(0xFF1F6FB2)      // mensajes, contactos
    private val AMBAR = Color(0xFFE0952A)     // multimedia, notas
    private val ROJO = Color(0xFFC0392B)      // cámara, alarma
    private val VIOLETA = Color(0xFF7A4FA3)   // galería, música
    private val GRIS = Color(0xFF5B6770)      // herramientas, genérico
    private val TEAL = Color(0xFF17807A)      // calendario, reloj

    private data class Regla(
        val claves: List<String>,
        val icono: NokiaIcon
    )

    /**
     * Reglas en orden de prioridad. Lo primero son las funciones que un Nokia tenía en el
     * menú principal, que es lo que el dueño pidió explícitamente que cambiara.
     */
    private val REGLAS: List<Regla> = listOf(
        // ── Las del menú clásico ──
        Regla(
            listOf("dialer", "incallui", "telecom", "phone", "telefono", "teléfono", "llamada", "marcador", "טלפון", "חיוג"),
            NokiaIcon("Llamadas", Icons.Filled.Call, VERDE)
        ),
        Regla(
            listOf("contacts", "contactos", "people", "agenda", "אנשי קשר", "contact"),
            NokiaIcon("Contactos", Icons.Filled.Contacts, AZUL)
        ),
        Regla(
            listOf("mms", "sms", "messaging", "messages", "mensaje", "mensajes", "הודעות"),
            NokiaIcon("Mensajes", Icons.Filled.Email, AZUL)
        ),
        Regla(
            listOf("alarm", "clock", "reloj", "alarma", "deskclock", "שעון"),
            NokiaIcon("Reloj", Icons.Filled.AccessAlarm, TEAL)
        ),
        Regla(
            listOf("calendar", "calendario", "agenda", "לוח שנה"),
            NokiaIcon("Calendario", Icons.Filled.CalendarMonth, TEAL)
        ),
        Regla(
            listOf("calculator", "calculadora", "calc", "מחשבון"),
            NokiaIcon("Calculadora", Icons.Filled.Calculate, GRIS)
        ),
        Regla(
            listOf("camera", "camara", "cámara", "מצלמה"),
            NokiaIcon("Cámara", Icons.Filled.PhotoCamera, ROJO)
        ),
        Regla(
            listOf("gallery", "galeria", "galería", "photos", "fotos", "image", "גלריה"),
            NokiaIcon("Galería", Icons.Filled.Image, VIOLETA)
        ),
        Regla(
            listOf("music", "musica", "música", "mp3", "player", "reproductor", "נגן"),
            NokiaIcon("Música", Icons.Filled.MusicNote, VIOLETA)
        ),
        Regla(
            listOf("record", "grabadora", "voice", "recorder", "grabación", "הקלטה"),
            NokiaIcon("Grabadora", Icons.Filled.Mic, AMBAR)
        ),
        Regla(
            listOf("note", "notas", "memo", "keep", "פתקים"),
            NokiaIcon("Notas", Icons.Filled.Description, AMBAR)
        ),
        Regla(
            listOf("radio", "fm"),
            NokiaIcon("Radio", Icons.Filled.Radio, AMBAR)
        ),
        Regla(
            listOf("flash", "linterna", "torch", "פנס"),
            NokiaIcon("Linterna", Icons.Filled.FlashlightOn, AMBAR)
        ),
        Regla(
            listOf("file", "archivo", "explorer", "documents", "קבצים"),
            NokiaIcon("Archivos", Icons.Filled.Folder, GRIS)
        ),
        Regla(
            listOf("setting", "ajustes", "config", "הגדרות"),
            NokiaIcon("Ajustes", Icons.Filled.Settings, GRIS)
        ),
        // ── Las que no existían en un Nokia pero sí en un equipo kosher ──
        Regla(
            listOf("whatsapp"),
            NokiaIcon("WhatsApp", Icons.Filled.Chat, VERDE)
        ),
        Regla(
            listOf("map", "waze", "gps", "navegacion", "navegación"),
            NokiaIcon("Mapas", Icons.Filled.Map, VERDE)
        ),
        Regla(
            listOf("sidur", "siddur", "tefila", "סדור", "תפילה"),
            NokiaIcon("Sidur", Icons.Filled.MenuBook, AZUL)
        ),
        Regla(
            listOf("bank", "banco", "pago", "mercadopago", "wallet"),
            NokiaIcon("Pagos", Icons.Filled.AccountBalanceWallet, VERDE)
        ),
        Regla(
            listOf("bus", "colectivo", "transporte", "subte", "train"),
            NokiaIcon("Transporte", Icons.Filled.DirectionsBus, AZUL)
        )
    )

    private val GENERICO = NokiaIcon("Aplicación", Icons.Filled.Apps, GRIS)

    /**
     * Ícono de teclas para un paquete.
     *
     * @param conservarEtiqueta si es `true`, se conserva el nombre real de la app en vez
     *   del nombre genérico de la función. Para las funciones clásicas (Llamadas,
     *   Contactos, Mensajes) conviene `false`, que es justo lo que el dueño pidió: que
     *   diga "Contactos" y no el nombre de la app de contactos que tenga el equipo.
     */
    fun para(packageName: String, label: String, conservarEtiqueta: Boolean = false): NokiaIcon {
        val pkg = packageName.lowercase()
        val lbl = label.lowercase()
        val encontrada = REGLAS.firstOrNull { regla ->
            regla.claves.any { clave -> pkg.contains(clave) || lbl.contains(clave) }
        }?.icono
        return when {
            encontrada == null -> GENERICO.copy(label = label)
            conservarEtiqueta -> encontrada.copy(label = label)
            else -> encontrada
        }
    }
}
