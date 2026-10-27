package com.ejemplo.locksuite.mdm

/**
 * Registro PASO A PASO de una actualización de app (26/10/2026, B.97).
 *
 * El pedido del dueño fue literal: *"que muestre exactamente el proceso y qué está
 * pasando"*. Hasta hoy el flujo publicaba UNA línea —la etapa actual— y al terminar un
 * resultado con su motivo. Entre medio no quedaba nada: si la actualización fallaba, no
 * había forma de saber si llegó a abrir Google Play, qué botón apretó, si vio una
 * descarga, cuánto esperó ni por qué decidió rendirse. Cada falla era una sesión de
 * diagnóstico a ciegas — la historia de B.9, B.14, B.41 y B.54.
 *
 * Ahora cada DECISIÓN del flujo deja una línea con su hora: qué camino eligió y por
 * qué, qué vio en la pantalla, qué apretó, qué contestó el sistema. Se ve en vivo en la
 * pantalla negra del celular (las últimas líneas) y entero en la ficha del panel, y
 * queda guardado después de terminar, hasta la próxima actualización.
 *
 * Formato de almacenamiento: texto plano, una línea por paso, `ms|tipo|mensaje`. Mismo
 * criterio que `Layer3Audit`: se lee de un vistazo por ADB, no depende de org.json (que
 * en la JVM de las pruebas no existe) y no se puede romper con un caracter raro porque
 * los separadores se limpian del mensaje al guardar.
 *
 * Funciones puras: las prueba `UpdateStepLogTest`.
 */
object UpdateStepLog {

    /** Tope de pasos guardados. Una actualización normal deja entre 8 y 20. */
    const val MAX_STEPS = 40
    /** Tope de largo de cada mensaje, para que el nodo de Firebase no crezca sin control. */
    const val MAX_MSG = 180

    // Tipos de paso. El panel los dibuja con colores distintos.
    const val INFO = "info"
    /** Algo que hizo LockSuite sola en el equipo (apretar un botón, abrir Play Store). */
    const val ACT = "act"
    const val OK = "ok"
    const val WARN = "warn"
    const val ERROR = "error"

    data class Step(val at: Long, val kind: String, val msg: String)

    private fun clean(s: String, max: Int): String =
        s.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim().take(max)

    private fun cleanKind(k: String): String = when (k) {
        INFO, ACT, OK, WARN, ERROR -> k
        else -> INFO
    }

    fun encode(steps: List<Step>): String =
        steps.joinToString("\n") { "${it.at}|${cleanKind(it.kind)}|${clean(it.msg, MAX_MSG)}" }

    /** Las líneas que no se pueden leer se ignoran: el celular puede estar en otra versión. */
    fun decode(raw: String?): List<Step> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Step>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val parts = line.split('|', limit = 3)
            if (parts.size < 3) continue
            val t = parts[0].toLongOrNull() ?: continue
            out.add(Step(t, cleanKind(parts[1]), parts[2]))
        }
        return out
    }

    /**
     * Agrega un paso. Un paso IGUAL al anterior (mismo tipo y mensaje) no se repite: el
     * ciclo del flujo corre cada 700 ms y sin esto "Esperando a Google Play…" llenaría
     * el registro solo.
     *
     * Al pasar el tope se descarta el MÁS VIEJO, pero nunca el primero: el primero dice
     * qué se pidió y por qué camino, y sin él el resto no se entiende.
     */
    fun append(raw: String?, step: Step): String {
        val list = decode(raw).toMutableList()
        val msg = clean(step.msg, MAX_MSG)
        if (msg.isEmpty()) return encode(list)
        val kind = cleanKind(step.kind)
        val last = list.lastOrNull()
        if (last != null && last.kind == kind && last.msg == msg) return encode(list)
        list.add(Step(step.at, kind, msg))
        while (list.size > MAX_STEPS) list.removeAt(if (list.size > 1) 1 else 0)
        return encode(list)
    }

    /**
     * Las últimas `n` líneas para la pantalla negra del celular, con el tiempo relativo
     * al primer paso ("+12 s"). En el celular no se muestra la hora del reloj: lo que
     * le sirve a quien mira es cuánto lleva.
     */
    fun tailForOverlay(raw: String?, n: Int): String {
        val list = decode(raw)
        if (list.isEmpty() || n <= 0) return ""
        val base = list.first().at
        return list.takeLast(n).joinToString("\n") { s ->
            val seg = ((s.at - base).coerceAtLeast(0L) / 1000L)
            val marca = when (s.kind) {
                OK -> "✓"
                ERROR -> "✗"
                WARN -> "!"
                ACT -> "›"
                else -> "·"
            }
            "+${seg}s $marca ${s.msg}"
        }
    }
}
