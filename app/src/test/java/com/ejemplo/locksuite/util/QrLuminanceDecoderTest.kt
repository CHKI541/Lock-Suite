package com.ejemplo.locksuite.util

import com.ejemplo.locksuite.mdm.GoogleVerificationPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Banco de [QrLuminanceDecoder] (22/10/2026, B.92).
 *
 * Los QR se generan con el propio ZXing y se convierten en "cuadros de cámara" de
 * luminancia (plano Y), con el relleno de fila (`rowStride`) que entrega CameraX: así
 * se prueba exactamente la forma en que llegan los datos en el equipo.
 */
class QrLuminanceDecoderTest {

    /** Mismo largo y forma que el QR real de la captura del dueño (281 caracteres). Token inventado. */
    private val verificationUrl: String = run {
        val base = "https://accounts.google.com/devicephoneverification/begin?flow=browser&plt="
        val tail = "&dsh=S-1272000000:1792536000000000&hl=es-419"
        base + "AKgnsb" + "Q".repeat(281 - base.length - 6 - tail.length) + tail
    }

    private fun encode(text: String, size: Int): BitMatrix =
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
        )

    /** Un cuadro de cámara: fondo gris claro, el QR en el medio, relleno al final de cada fila. */
    private fun frame(
        matrix: BitMatrix,
        width: Int,
        height: Int,
        rowPadding: Int,
        inverted: Boolean = false,
        rotate90: Boolean = false
    ): Pair<ByteArray, Int> {
        val rowStride = width + rowPadding
        val data = ByteArray(rowStride * height) { 0x55 } // el relleno no es imagen
        val dark: Byte = if (inverted) 0xE0.toByte() else 0x20
        val light: Byte = if (inverted) 0x20 else 0xE0.toByte()
        val ox = (width - matrix.width) / 2
        val oy = (height - matrix.height) / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                val mx = x - ox
                val my = y - oy
                val inside = mx in 0 until matrix.width && my in 0 until matrix.height
                // Girado 90°: el píxel (x, y) del cuadro es el (y, ancho-1-x) del código.
                val on = inside && (if (rotate90) matrix.get(my, matrix.width - 1 - mx) else matrix.get(mx, my))
                data[y * rowStride + x] = if (on) dark else light
            }
        }
        return data to rowStride
    }

    @Test
    fun `lee el QR de verificacion de un cuadro 720p con relleno de fila`() {
        val (data, stride) = frame(encode(verificationUrl, 520), 1280, 720, rowPadding = 64)
        val text = QrLuminanceDecoder.decode(data, stride, 1280, 720)
        assertEquals(verificationUrl, text)
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed(text))
    }

    @Test
    fun `lee el QR girado (el sensor de la camara entrega el cuadro acostado)`() {
        val (data, stride) = frame(encode(verificationUrl, 520), 1280, 720, rowPadding = 0, rotate90 = true)
        assertEquals(verificationUrl, QrLuminanceDecoder.decode(data, stride, 1280, 720))
    }

    @Test
    fun `lee un QR claro sobre fondo oscuro solo con el modo de mas esfuerzo`() {
        val (data, stride) = frame(encode(verificationUrl, 520), 1280, 720, rowPadding = 32, inverted = true)
        assertEquals(verificationUrl, QrLuminanceDecoder.decode(data, stride, 1280, 720, tryHarder = true))
    }

    @Test
    fun `la ultima fila sin relleno alcanza`() {
        val (full, stride) = frame(encode(verificationUrl, 520), 1280, 720, rowPadding = 64)
        val trimmed = full.copyOf(stride * 719 + 1280)
        assertEquals(verificationUrl, QrLuminanceDecoder.decode(trimmed, stride, 1280, 720))
    }

    @Test
    fun `un cuadro sin QR devuelve null y no rompe`() {
        val rnd = Random(7)
        val noise = ByteArray(1280 * 720) { rnd.nextInt(256).toByte() }
        assertNull(QrLuminanceDecoder.decode(noise, 1280, 1280, 720))
        assertNull(QrLuminanceDecoder.decode(noise, 1280, 1280, 720, tryHarder = true))
        assertNull(QrLuminanceDecoder.decode(ByteArray(1280 * 720) { 0x7F }, 1280, 1280, 720))
    }

    @Test
    fun `datos imposibles devuelven null en vez de tirar`() {
        assertNull(QrLuminanceDecoder.decode(ByteArray(10), 10, 0, 10))
        assertNull(QrLuminanceDecoder.decode(ByteArray(10), 5, 10, 1))      // rowStride < ancho
        assertNull(QrLuminanceDecoder.decode(ByteArray(100), 100, 100, 100)) // faltan filas
    }

    @Test
    fun `un QR que no es de Google se lee pero la politica lo rechaza`() {
        val other = "https://www.youtube.com/watch?v=abc"
        val (data, stride) = frame(encode(other, 400), 1280, 720, rowPadding = 0)
        val text = QrLuminanceDecoder.decode(data, stride, 1280, 720)
        assertEquals(other, text)
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed(text))
    }
}
