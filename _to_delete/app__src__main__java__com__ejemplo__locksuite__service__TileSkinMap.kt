package com.ejemplo.locksuite.service

import android.graphics.Bitmap

object TileSkinMap {
    /**
     * Reduce la pantalla completa a una miniatura de (cols*samplesPerTile) x (rows*samplesPerTile)
     * UNA sola vez, y devuelve el % de piel de cada celda. Mucho más barato que recortar
     * y escalar un bitmap por cada una de las N celdas por separado.
     */
    fun computeSkinRatioPerTile(
        fullScreenBitmap: Bitmap,
        cols: Int,
        rows: Int,
        samplesPerTile: Int = 6
    ): Array<FloatArray> {
        val smallW = cols * samplesPerTile
        val smallH = rows * samplesPerTile
        val small = Bitmap.createScaledBitmap(fullScreenBitmap, smallW, smallH, false)
        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
        small.recycle()

        val ratios = Array(rows) { FloatArray(cols) }
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var skinCount = 0
                val total = samplesPerTile * samplesPerTile
                for (dy in 0 until samplesPerTile) {
                    for (dx in 0 until samplesPerTile) {
                        val x = col * samplesPerTile + dx
                        val y = row * samplesPerTile + dy
                        val p = pixels[y * smallW + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        if (SkinHeuristic.isSkinTonePixel(r, g, b)) skinCount++
                    }
                }
                ratios[row][col] = skinCount.toFloat() / total
            }
        }
        return ratios
    }
}
