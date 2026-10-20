package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de [GoogleVerificationPolicy].
 * Valida que el microvisor permita únicamente pantallas de verificación legítimas
 * y bloquee herméticamente cualquier vía de escape a la web abierta o servicios no kosher.
 */
class GoogleVerificationPolicyTest {

    // ── 1. URLs DE VERIFICACIÓN PERMITIDAS ────────────────────────────────────

    @Test
    fun `permite el QR exacto de la captura reportada por el usuario`() {
        val qrUrl = "https://accounts.google.com/devicephoneverification/begin?flow=browser&plt=AKgnsbt6a7zYT0zvYQQU81JcwlcQhSQZAVEvzREb9WzYhVlsrrpWZZstF0-L2Di2T3Jskytye0eQZZubrZOJr1pdfKa1l5VrnIaUaKc4NxcELz4g6OKEpul3AyLO3MlSuxJy7rb3KZk_SIgtg-yUf3DxX_g1idZnrw&dsh=S-1272006808:1792497735287756&hl=es-419"
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed(qrUrl))
    }

    @Test
    fun `permite rutas canonicas de devicephoneverification`() {
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/initiate"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/verify?token=abc"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/step2"))
    }

    @Test
    fun `permite desafios v3 y v2 de inicio de sesion de Google`() {
        // Desafío IAP v3
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/v3/signin/challenge/iap?TL=ADG-123"))
        // Desafío de contraseña
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/v3/signin/challenge/pwd?TL=ADG-456"))
        // Desafío TOTP / Google Authenticator
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/v3/signin/challenge/totp"))
        // Selección de método
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/v3/signin/challenge/selection"))
        // Desafío v2 clásico
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/signin/challenge/ipp"))
        // Recuperación de cuenta legítima
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/signin/recovery/challenge/phone"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/v3/signin/recovery/challenge/sms"))
    }

    @Test
    fun `permite validaciones de cookies y setup de Play Services`() {
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/CheckCookie?chtml=LoginDoneHtml"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/accounts/CheckCookie"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/embedded/setup/android"))
    }

    @Test
    fun `permite about blank interno`() {
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("about:blank"))
    }

    // ── 2. VECTORES DE EVASIÓN ESTRICTAMENTE BLOQUEADOS ──────────────────────

    @Test
    fun `bloquea otros dominios de Google que no sean accounts`() {
        // Buscador de Google
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://www.google.com/"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://google.com/search?q=algo"))
        // Mi Actividad (YouTube history, searches)
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://myactivity.google.com/"))
        // Gestión de cuenta (YouTube data, security settings)
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://myaccount.google.com/"))
        // Cronología / Maps
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://timeline.google.com/"))
        // Soporte y ayuda (enlaces al pie con artículos abiertos)
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://support.google.com/accounts/answer/123"))
        // Políticas y privacidad (enlaces al pie)
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://policies.google.com/privacy"))
        // YouTube directo
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://www.youtube.com/"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://m.youtube.com/watch?v=123"))
    }

    @Test
    fun `bloquea ataques de subdominio falso o prefijo atacante`() {
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com.evil.com/devicephoneverification/begin"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://evil-accounts.google.com/devicephoneverification/begin"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://fakeaccounts.google.com/v3/signin/challenge"))
    }

    @Test
    fun `bloquea rutas prohibidas dentro de accounts google com`() {
        // Creación de cuenta
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/signup"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/CreateAccount"))
        // Gestión general
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/ManageAccount"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/myaccount"))
        // Switcher general
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/b/0/ManageAccount"))
    }

    @Test
    fun `bloquea esquemas peligrosos y no cifrados`() {
        // http sin cifrar
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("http://accounts.google.com/devicephoneverification/begin"))
        // javascript
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("javascript:alert(document.cookie)"))
        // intent
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("intent://accounts.google.com/#Intent;scheme=https;package=com.android.chrome;end"))
        // file
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("file:///android_asset/something.html"))
        // data
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("data:text/html,<h1>Hacked</h1>"))
        // market
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("market://details?id=com.android.chrome"))
    }

    @Test
    fun `bloquea entradas nulas, vacias o mal formadas`() {
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed(null))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed(""))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("   "))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("not a url"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://"))
    }

    // ── 3. ESQUEMAS SMS Y TELÉFONO NATIVOS ───────────────────────────────────

    @Test
    fun `detecta correctamente acciones de SMS o telefono para verificación`() {
        assertTrue(GoogleVerificationPolicy.isSmsOrTelAction("sms:+12025550199?body=verify"))
        assertTrue(GoogleVerificationPolicy.isSmsOrTelAction("sms:123456"))
        assertTrue(GoogleVerificationPolicy.isSmsOrTelAction("tel:+5491112345678"))
        assertFalse(GoogleVerificationPolicy.isSmsOrTelAction("https://accounts.google.com/devicephoneverification/begin"))
        assertFalse(GoogleVerificationPolicy.isSmsOrTelAction("mailto:test@example.com"))
        assertFalse(GoogleVerificationPolicy.isSmsOrTelAction(null))
    }

    // ── 4. SUBRECURSOS (SCRIPTS, ESTILOS, FUENTES) ───────────────────────────

    @Test
    fun `permite subrecursos indispensables de Google`() {
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://www.gstatic.com/_/mss/boq-identity/_/js/k=boq-identity.DevicePhoneVerificationUi..."))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://ssl.gstatic.com/accounts/ui/avatar.png"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://fonts.gstatic.com/s/googlesans/v14/google-sans.woff2"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://apis.google.com/js/api.js"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/devicephoneverification/data"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("data:image/svg+xml;base64,PHN2Z..."))
    }

    @Test
    fun `bloquea subrecursos no kosher o publicitarios`() {
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://googleads.g.doubleclick.net/pagead/ads"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://myactivity.google.com/page"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://unrelated-domain.com/tracker.js"))
    }

    // ── 5. DETECCIÓN DE ÉXITO DE VERIFICACIÓN ────────────────────────────────

    @Test
    fun `detecta URLs de finalizacion exitosa`() {
        assertTrue(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/devicephoneverification/success"))
        assertTrue(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/v3/signin/challenge/iap/success"))
        assertTrue(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/devicephoneverification/verify?flowComplete=true"))
        assertFalse(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/devicephoneverification/begin"))
        assertFalse(GoogleVerificationPolicy.isVerificationSuccess(null))
    }
}
