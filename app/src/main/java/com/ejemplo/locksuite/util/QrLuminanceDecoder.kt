package com.ejemplo.locksuite.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * QrLuminanceDecoder — lee un código QR de un cuadro de cámara (22/10/2026, B.92).
 *
 * Función PURA sobre la luminancia (el plano Y de YUV_420_888, que es lo que entrega
 * CameraX en `ImageAnalysis`): no toca la cámara ni Android, así que se prueba en el
 * banco con códigos generados por el propio ZXing (`QrLuminanceDecoderTest`).
 *
 * Por qué existe: en Android 12+ la cámara del sistema NO le pasa a LockSuite el link
 * del QR de Google. Un link web va al navegador predeterminado salvo que la app esté
 * aprobada para ese dominio, y LockSuite no puede verificar `accounts.google.com`. Con
 * el navegador suspendido, el usuario veía "app pausada por el administrador" y no
 * había forma de llegar al microvisor. Escanear desde LockSuite esquiva todo eso, anda
 * sin servicios de Google y sin red, y en equipos sin app de cámara con lector de QR.
 *
 * Solo QR (nada de códigos de barras), y la versión "más esfuerzo" solo cuando se pide,
 * porque corre varias veces por segundo en equipos de 2 GB.
 */
object QrLuminanceDecoder {

    private val HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    private val HINTS_HARDER: Map<DecodeHintType, Any> = HINTS + (DecodeHintType.TRY_HARDER to true)

    /**
     * @param data      luminancia fila por fila: la fila `y` empieza en `y * rowStride`.
     * @param rowStride bytes por fila (en CameraX suele ser mayor que el ancho).
     * @param width     ancho útil de la imagen, en píxeles.
     * @param height    alto de la imagen, en píxeles.
     * @param tryHarder si además se busca con más esfuerzo y con los colores invertidos.
     * @return el texto del QR, o `null` si en este cuadro no hay uno legible.
     */
    fun decode(
        data: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
        tryHarder: Boolean = false
    ): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        // La última fila puede venir sin el relleno del final: alcanza con que entre entera.
        val needed = rowStride.toLong() * (height - 1) + width
        if (data.size < needed) return null
        val dataHeight = maxOf(height, data.size / rowStride)

        val source = try {
            PlanarYUVLuminanceSource(data, rowStride, dataHeight, 0, 0, width, height, false)
        } catch (_: IllegalArgumentException) {
            return null
        }

        val hints = if (tryHarder) HINTS_HARDER else HINTS
        decodeSource(source, hints)?.let { return it }
        if (tryHarder) {
            // QR claro sobre fondo oscuro (modo oscuro de algunas pantallas).
            decodeSource(source.invert(), hints)?.let { return it }
        }
        return null
    }

    private fun decodeSource(source: LuminanceSource, hints: Map<DecodeHintType, Any>): String? =
        try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)?.text
        } catch (_: ReaderException) {
            null
        } catch (_: RuntimeException) {
            // ZXing tira IllegalArgument/ArrayIndex con cuadros degenerados: es "no hay QR".
            null
        }
}
