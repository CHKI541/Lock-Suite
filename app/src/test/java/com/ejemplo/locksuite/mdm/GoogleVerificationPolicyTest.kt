package com.ejemplo.locksuite.mdm

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
    fun `permite el formato canonico del QR de verificacion IAP con token sintetico`() {
        // Token sintético de prueba (P1-06: sin secretos reales)
        val mockQrUrl = "https://accounts.google.com/devicephoneverification/begin?flow=browser&plt=MOCK_TEST_TOKEN_12345&dsh=S-MOCK:123456789&hl=es-419"
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed(mockQrUrl))
    }

    @Test
    fun `permite rutas canonicas de devicephoneverification con limites de segmento`() {
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/initiate"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/verify?token=abc"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/step2"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification?flow=browser"))
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
    fun `permite about blank interno pero bloquea otros esquemas about`() {
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("about:blank"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("about:config"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("about:flags"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("about:"))
    }

    @Test
    fun `valida estrictamente el puerto HTTPS`() {
        // Puerto implícito (-1) y estándar (443) permitidos
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/begin"))
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com:443/devicephoneverification/begin"))
        // Puertos no estándar rechazados
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com:8443/devicephoneverification/begin"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com:8080/devicephoneverification/begin"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com:80/devicephoneverification/begin"))
    }

    // ── 2. BLINDAJE CONTRA VECTORES DE EVASIÓN Y CONFUSIÓN DE AUTORIDAD (P1-01) ──

    @Test
    fun `bloquea confusion de autoridad y userinfo en URL`() {
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com:443@evil.example/devicephoneverification/begin?x=a b"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com@evil.example/devicephoneverification/begin"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://user:pass@accounts.google.com/devicephoneverification/begin"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://evil.com?user=accounts.google.com"))
    }

    @Test
    fun `bloquea path traversal y prefijos sin delimitador de segmento (P1-03)`() {
        // Traversal a signup
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/../signup"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/%2e%2e/signup"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification/..%2fmanageaccount"))
        // Prefijos pegados sin barra ni signo de interrogación
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverificationotra"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/signin/challenge-admin"))
        // Barras invertidas
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/devicephoneverification\\test"))
    }

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
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://accounts.google.com/b"))
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
        // sms y tel bloqueados para no salir a apps externas (P1-05)
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("sms:+12025550199?body=verify"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("tel:+5491112345678"))
    }

    @Test
    fun `bloquea entradas nulas, vacias o mal formadas`() {
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed(null))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed(""))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("   "))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("not a url"))
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("https://"))
    }

    // ── 3. SUBRECURSOS (SCRIPTS, ESTILOS, FUENTES) ───────────────────────────

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
    fun `bloquea subrecursos no kosher, wss o publicitarios (P2-01)`() {
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("wss://accounts.google.com/ws"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("data:text/html,<h1>Test</h1>"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://googleads.g.doubleclick.net/pagead/ads"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://myactivity.google.com/page"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://unrelated-domain.com/tracker.js"))
    }

    // ── 4. DETECCIÓN DE ÉXITO DE VERIFICACIÓN (P1-02) ────────────────────────

    @Test
    fun `detecta URLs de finalizacion exitosa SOLO en URLs autorizadas`() {
        assertTrue(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/devicephoneverification/success"))
        assertTrue(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/v3/signin/challenge/iap/success"))
        assertTrue(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/devicephoneverification/verify?flowComplete=true"))
        assertFalse(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/devicephoneverification/begin"))

        // Falsos positivos en dominios externos estrictamente bloqueados (P1-02)
        assertFalse(GoogleVerificationPolicy.isVerificationSuccess("https://evil.example/devicephoneverification/success"))
        assertFalse(GoogleVerificationPolicy.isVerificationSuccess("https://evil.example/?status=success"))
        assertFalse(GoogleVerificationPolicy.isVerificationSuccess("https://accounts.google.com/signup?status=success"))
        assertFalse(GoogleVerificationPolicy.isVerificationSuccess(null))
    }
}
