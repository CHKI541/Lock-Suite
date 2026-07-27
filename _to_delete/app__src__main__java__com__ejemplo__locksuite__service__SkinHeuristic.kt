package com.ejemplo.locksuite.service

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SkinHeuristic {
    // Regla RGB clásica (Peer et al.) + rango YCbCr (BT.601) — heurística, no modelo:
    // tiene falsos positivos (madera, arena) y negativos (iluminación, tono de piel) —
    // por eso es solo un FILTRO PREVIO para decidir qué vale la pena mirar con el modelo.
    fun isSkinTonePixel(r: Int, g: Int, b: Int): Boolean {
        val rgbRule = r > 95 && g > 40 && b > 20 &&
            (max(r, max(g, b)) - min(r, min(g, b))) > 15 &&
            abs(r - g) > 15 && r > g && r > b

        val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        val ycbcrRule = cb in 77.0..127.0 && cr in 133.0..173.0

        return rgbRule || ycbcrRule
    }
}
