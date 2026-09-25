package com.ejemplo.locksuite.mdm

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * B.88 — stubs de Android Auto. Lo que se prueba es lo que decide si LockSuite protege un
 * paquete o lo trata como a cualquier otro: la huella del certificado y la huella del
 * archivo. Lo que toca Android (leer la firma instalada, ocultar/des-ocultar) no corre en
 * la JVM; eso va en las pruebas en equipo de las instrucciones.
 */
class AndroidAutoStubsTest {

    /** Certificado REAL de los stubs, sacado de META-INF/STUB.RSA de aa-stub-maps.apk. */
    private val certNuestro: ByteArray = Base64.getDecoder().decode(
            "MIIEGTCCAoGgAwIBAgIIE1uSFFAeFNMwDQYJKoZIhvcNAQEMBQAwOjESMBAGA1UEChMJTG9ja1N1aXRlMSQwIgYDVQQDExtM" +
            "b2NrU3VpdGUgQW5kcm9pZCBBdXRvIHN0dWIwIBcNMjYwOTI1MTkzNjQxWhgPMjEyNjA5MDExOTM2NDFaMDoxEjAQBgNVBAoT" +
            "CUxvY2tTdWl0ZTEkMCIGA1UEAxMbTG9ja1N1aXRlIEFuZHJvaWQgQXV0byBzdHViMIIBojANBgkqhkiG9w0BAQEFAAOCAY8A" +
            "MIIBigKCAYEAsM0ubrIG8xsmlmtgNXYpE6w7biN1Sbr7HJlRKPwmssHKzvY2xVYYGHuzN9Us/h0Hu/a7wEGm+HVvY0X0PmZq" +
            "i/Dye1NqK79bD8nnZqSLG0YLDxj1U4NV+LHIksLLeO+glTGnJfbxJRlO8QbMu+oFJ3Xo8qSFJJis7KlQxBQryrLnEYWQLW6Y" +
            "VzGYdHpLA389P4Gw1Pn6gGCQ20TKloD48H2riJ4CJWLhOH5wfDMBgYAGujcGW+uZqf8xT3umEMtag0MV9DfAx0sQ40uiBNAd" +
            "C9f7VJWaCiGvP0N+bN7QiEsndWka+XKX1ClbHILvg2MeWsAnYiYeSGnGCgavFuw4XVV8zGryPkSFPaXWW8/rekCnXU3toK0W" +
            "W83Wrw7uRKROcw47btc/VCMPxeaLpu0XLhjSi7zfCt7FUbyTgaKz+475iFQGvznZSogaP1NuBfs99jEP8OzybyuUDsRCFy5H" +
            "WavJ3T8i29+sTqUn4BI7IzY/NsvekAA/jdk3p4u4PNA/AgMBAAGjITAfMB0GA1UdDgQWBBT03T8b+0pNztr3JbBzbvXC0n/X" +
            "KTANBgkqhkiG9w0BAQwFAAOCAYEARMIbPn9hUyV7cAU1vYCajTA3X6vXUUrOI40MI4Rslvhg/5ltMn5Y9Tahsk26MakKdztS" +
            "slauLUN1c489lM0ns2JvYZLtHsek7AyagM+hwfSr9TFgpOsvA89uGctBVtYSJH01T8gEvJWTCcxn1fS/Qe4jP0Ynif0eHObg" +
            "iC8nXJnaUnFwOwbrMCG5vPjbnhanK8wK5VVXi1xWkz/WBQrBfuXCmljQDX0FWeskM3XHKYRbKWIJpP8tWrWzLCs69wATPZR2" +
            "I6KNHietgdDPGtPaZ53jqI5pkmVGQxHEg7X6BGHYMiuoEcbAkobcqZ2czNy4XGA09q7aOdkv5QjfUuW6PlqeCqN1L8uVQgOD" +
            "0CjLjMLB8IG1gNCrBbxlXrX5cHJcphSHca1XLFj0M7frxQeMl6N/kR39x4pU+i4Xq9z1VMnFRNmb/DTTiNU3gGYCtdPOHIHg" +
            "TOtMK0+qcb+SOEJ0KiIGdaBvqpUIU0YNQvFdY9PFC+D6FpXo41B3JZ1IyU1k"
    )

    /**
     * Control negativo: la clave de PRUEBA pública de Android (CN=Android,
     * android@android.com), con la que está firmado el stub de Maps de la comunidad
     * (aa4mg / rik-shaw/aa-stubs). Cualquiera la tiene: nunca tiene que contar como nuestra.
     */
    private val certPruebaAndroid: ByteArray = Base64.getDecoder().decode(
            "MIIEqDCCA5CgAwIBAgIJAJNurL4H8gHfMA0GCSqGSIb3DQEBBQUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZv" +
            "cm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UE" +
            "AxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODAyMjkwMTMzNDZaFw0zNTA3MTcw" +
            "MTMzNDZaMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4G" +
            "A1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9p" +
            "ZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANaTGQTexgskse3HYuDZ2CU+Ps1s6x3i/waM" +
            "qOi8qM1r03hupwqnbOYOuw+ZNVn/2T53qUPn6D1LZLjk/qLT5lbx4meoG7+yMLV4wgRDvkxyGLhG9SEVhvA4oU6Jwr44f46+" +
            "z4/Kw9oe4zDJ6pPQp8PcSvNQIg1QCAcy4ICXF+5qBTNZ5qaU7Cyz8oSgpGbIepTYOzEJOmc3Li9kEsBubULxWBjf/gOBzAzU" +
            "RNps3cO4JFgZSAGzJWQTT7/emMkod0jb9WdqVA2BVMi7yge54kdVMxHEa5r3b97szI5p58ii0I54JiCUP5lyfTwE/nKZHZnf" +
            "m644oLIXf6MdW2r+6R8CAQOjgfwwgfkwHQYDVR0OBBYEFEhZAFY9JyxGrhGGBaR0GawJyowRMIHJBgNVHSMEgcEwgb6AFEhZ" +
            "AFY9JyxGrhGGBaR0GawJyowRoYGapIGXMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMN" +
            "TW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAG" +
            "CSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJAJNurL4H8gHfMAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEFBQAD" +
            "ggEBAHqvlozrUMRBBVEY0NqrrwFbinZaJ6cVosK0TyIUFf/azgMJWr+kLfcHCHJsIGnlw27drgQAvilFLAhLwn62oX6snb4Y" +
            "LCBOsVMR9FXYJLZW2+TcIkCRLXWG/oiVHQGo/rWuWkJgU134NDEFJCJGjDbiLCpe+ZTWHdcwauTJ9pUbo8EvHRkU3cYfGmLa" +
            "Lfgn9gP+pWA7LFQNvXwBnDa6sppCccEX31I828XzgXpJ4O+mDL1/dBd+ek8ZPUP0IgdyZm5MTYPhvVqGCHzzTy3sIeJFymwr" +
            "sBbmg2OAUNLEMO6nwmocSdN2ClirfxqCzJOLSDE4QyS9BAH6EhY6UFcOaE0="
    )

    @Test
    fun laHuellaFijadaEsLaDelCertificadoReal_yConElFormatoDeApksigner() {
        // Si alguien regenera los stubs (clave nueva) y no actualiza CERT_SHA256, esto
        // no lo detecta — para eso está tools/aa_stubs/check_stubs.py —, pero sí prueba
        // que sha256Hex() produce EXACTAMENTE lo que imprime `apksigner --print-certs`
        // sobre el certificado de verdad, que es de donde sale el valor fijado.
        val huella = AndroidAutoStubs.sha256Hex(certNuestro)
        assertEquals("62786d1c3bfaa36cac1d71ec943227c2ace1f622c805c9affc44e39f81bf52de", huella)
        assertTrue(huella in AndroidAutoStubs.CERT_SHA256)
    }

    @Test
    fun reconoceSoloNuestraFirma() {
        val nuestra = AndroidAutoStubs.sha256Hex(certNuestro)
        val prueba = AndroidAutoStubs.sha256Hex(certPruebaAndroid)
        assertEquals("a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc", prueba)

        assertTrue(AndroidAutoStubs.esCertificadoDeStub(listOf(nuestra)))
        assertTrue(AndroidAutoStubs.esCertificadoDeStub(listOf(nuestra.uppercase())))
        // El stub de la comunidad NO es nuestro, aunque se llame igual.
        assertFalse(AndroidAutoStubs.esCertificadoDeStub(listOf(prueba)))
        // Firmado por nosotros Y por otro: no cuenta (todos los firmantes tienen que ser nuestros).
        assertFalse(AndroidAutoStubs.esCertificadoDeStub(listOf(nuestra, prueba)))
        // Sin firmas (no se pudo leer): no cuenta.
        assertFalse(AndroidAutoStubs.esCertificadoDeStub(emptyList()))
    }

    @Test
    fun laTiendaReconoceLosStubsPorPaqueteYHuellaExacta() {
        val maps = AndroidAutoStubs.APK_SHA256.getValue(AndroidAutoStubs.PKG_MAPS)
        val google = AndroidAutoStubs.APK_SHA256.getValue(AndroidAutoStubs.PKG_GOOGLE_APP)

        assertTrue(AndroidAutoStubs.esEntradaDeStub(AndroidAutoStubs.PKG_MAPS, maps))
        assertTrue(AndroidAutoStubs.esEntradaDeStub(AndroidAutoStubs.PKG_GOOGLE_APP, google))
        // Como la guarda el panel a mano: con espacios y en mayúsculas.
        assertTrue(AndroidAutoStubs.esEntradaDeStub(AndroidAutoStubs.PKG_MAPS, "  " + maps.uppercase() + " "))

        // Huellas cruzadas: el archivo de un stub bajo el nombre del otro no vale.
        assertFalse(AndroidAutoStubs.esEntradaDeStub(AndroidAutoStubs.PKG_MAPS, google))
        assertFalse(AndroidAutoStubs.esEntradaDeStub(AndroidAutoStubs.PKG_GOOGLE_APP, maps))
        // La Maps REAL bajo su nombre (cualquier otra huella): necesita permiso como siempre.
        assertFalse(AndroidAutoStubs.esEntradaDeStub(AndroidAutoStubs.PKG_MAPS, "0".repeat(64)))
        // Sin huella cargada: no.
        assertFalse(AndroidAutoStubs.esEntradaDeStub(AndroidAutoStubs.PKG_MAPS, null))
        assertFalse(AndroidAutoStubs.esEntradaDeStub(AndroidAutoStubs.PKG_MAPS, ""))
        // Otro paquete con la huella de un stub: no.
        assertFalse(AndroidAutoStubs.esEntradaDeStub("com.waze", maps))
    }

    @Test
    fun laTiendaDiceSiLaAppRealImpideElStub() {
        val instalada = ApplicationInfo.FLAG_INSTALLED
        val sistema = ApplicationInfo.FLAG_SYSTEM
        val actualizada = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP

        // Nuestro stub ya instalado: nada lo impide (reinstalarlo es inofensivo).
        assertNull(AndroidAutoStubs.impedimento(instalada, esNuestro = true))
        // La Maps real como app común, oculta o no (el caso del celular del dueño).
        assertEquals(
            AndroidAutoStubs.Impedimento.APP_REAL_INSTALADA,
            AndroidAutoStubs.impedimento(instalada, esNuestro = false)
        )
        // De fábrica: tal cual, actualizada desde Play Store, o "desinstalada" para el
        // usuario (sin FLAG_INSTALLED, pero la copia del sistema sigue ahí).
        for (flags in listOf(sistema or instalada, sistema or actualizada or instalada, sistema)) {
            assertEquals(
                AndroidAutoStubs.Impedimento.APP_REAL_DE_FABRICA,
                AndroidAutoStubs.impedimento(flags, esNuestro = false)
            )
        }
        // Una app común desinstalada que conservó sus datos: ya no ocupa el nombre.
        assertNull(AndroidAutoStubs.impedimento(0, esNuestro = false))
    }

    @Test
    fun soloDosPaquetes_yNingunoEsWazeNiLockSuite() {
        assertEquals(
            setOf("com.google.android.apps.maps", "com.google.android.googlequicksearchbox"),
            AndroidAutoStubs.PACKAGES
        )
        assertEquals(AndroidAutoStubs.PACKAGES, AndroidAutoStubs.APK_SHA256.keys)
    }
}
