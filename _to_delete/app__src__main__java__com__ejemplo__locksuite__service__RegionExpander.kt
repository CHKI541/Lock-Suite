package com.ejemplo.locksuite.service

import android.graphics.Rect

object RegionExpander {

    // --- Rostro: la caja original es SOLO la cara. El cuerpo sigue de largo. ---
    // Basado en proporción anatómica clásica (~8 cabezas de alto): si la cara detectada
    // mide H de alto, el cuerpo completo desde la coronilla hasta los pies mide ~9.7H.
    // Con estos márgenes, el bloqueo total (arriba + caja + abajo) da ~9.7H, o sea que
    // cubre la silueta completa.
    private const val FACE_SIDE_MARGIN_RATIO = 1.5f   // a cada lado, 150% del ancho de la cara
    private const val FACE_TOP_MARGIN_RATIO = 0.7f    // arriba, cubre pelo/cabeza completa
    private const val FACE_BOTTOM_MARGIN_RATIO = 8.0f // abajo, cubre el cuerpo completo

    // --- Cuerpo: el detector de personas YA cubre el cuerpo visible. Solo margen de seguridad. ---
    private const val BODY_MARGIN_RATIO = 0.4f

    fun expand(region: DetectedRegion, screenWidth: Int, screenHeight: Int): Rect {
        val r = region.rect
        val w = r.width()
        val h = r.height()

        val raw = when (region.source) {
            DetectionSource.FACE -> Rect(
                (r.left - w * FACE_SIDE_MARGIN_RATIO).toInt(),
                (r.top - h * FACE_TOP_MARGIN_RATIO).toInt(),
                (r.right + w * FACE_SIDE_MARGIN_RATIO).toInt(),
                (r.bottom + h * FACE_BOTTOM_MARGIN_RATIO).toInt()
            )
            DetectionSource.BODY -> Rect(
                (r.left - w * BODY_MARGIN_RATIO).toInt(),
                (r.top - h * BODY_MARGIN_RATIO).toInt(),
                (r.right + w * BODY_MARGIN_RATIO).toInt(),
                (r.bottom + h * BODY_MARGIN_RATIO).toInt()
            )
        }

        // Clip a los bordes reales de la PANTALLA
        return Rect(
            raw.left.coerceIn(0, screenWidth),
            raw.top.coerceIn(0, screenHeight),
            raw.right.coerceIn(0, screenWidth),
            raw.bottom.coerceIn(0, screenHeight)
        )
    }
}
