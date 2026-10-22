package com.ejemplo.locksuite.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ── 5. REVISIÓN DEL 22/10/2026 ───────────────────────────────────────────

    @Test
    fun `acepta el QR real con su forma exacta (token sintetico del mismo largo)`() {
        // Forma medida del QR de la captura del dueño: /devicephoneverification/begin con
        // flow, plt (base64url), dsh (con ':') y hl. El token es inventado.
        val plt = "AKgnsb" + "x".repeat(200)
        val url = "https://accounts.google.com/devicephoneverification/begin?flow=browser&plt=$plt&dsh=S-1272000000:1792536000000000&hl=es-419"
        assertTrue(GoogleVerificationPolicy.isNavigationAllowed(url))
        assertTrue(GoogleVerificationPolicy.isSmsHandoffContext(url))
    }

    @Test
    fun `permite el RPC de las apps de Google como subrecurso (v3 y la del QR)`() {
        // Sin esto el desafío v3 se dibujaba pero no podía responder (403 al batchexecute).
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/v3/signin/_/AccountsSignInUi/data/batchexecute?rpcids=abc"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/devicephoneverification/_/DevicePhoneVerificationUi/data/batchexecute?rpcids=x"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/_/signin/challenge?hl=es"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/_/mss/boq-identity/_/js/k=x"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/generate_204"))
        assertTrue(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/devicephoneverification"))
    }

    @Test
    fun `no deja como subrecurso los redirectores ni lo que no es del microvisor`() {
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/CheckCookie?continue=https://www.youtube.com/"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/ServiceLogin?continue=https://www.google.com/"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/signup/v2/webcreateaccount"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/b/0/ManageAccount"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com/devicephoneverificationotra/x"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("https://accounts.google.com:8443/_/js/x"))
        assertFalse(GoogleVerificationPolicy.isResourceAllowed("http://accounts.google.com/_/js/x"))
    }

    @Test
    fun `el SMS de Google se acepta y conserva numero y codigo exactos`() {
        val sms = GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567?body=G-123456%20verifica")
        assertNotNull(sms)
        assertEquals("+15551234567", sms!!.recipient)
        assertEquals("G-123456 verifica", sms.body)

        val smsto = GoogleVerificationPolicy.parseSmsHandoff("SMSTO:+442071234567?body=abc")
        assertEquals("+442071234567", smsto!!.recipient)
        assertEquals("abc", smsto.body)

        // Estilo iOS y con barras.
        assertEquals("ABC", GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567;?&body=ABC")!!.body)
        assertEquals("ABC", GoogleVerificationPolicy.parseSmsHandoff("sms://+15551234567?body=ABC")!!.body)
        // sms_body también.
        assertEquals("X1", GoogleVerificationPolicy.parseSmsHandoff("sms:24444?sms_body=X1")!!.body)
        // Número corto de servicio y separadores visuales.
        assertEquals("24444", GoogleVerificationPolicy.parseSmsHandoff("sms:24444?body=1")!!.recipient)
        assertEquals("+5491112345678", GoogleVerificationPolicy.parseSmsHandoff("sms:+54%209%2011-1234-5678?body=1")!!.recipient)
        // Sin texto: se acepta (borrador vacío) y no inventa nada.
        assertEquals("", GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567")!!.body)
    }

    @Test
    fun `acepta las otras formas conocidas de abrir un SMS (iOS e intent de Android)`() {
        // iOS 8+: '&body=' sin '?'.
        val ios = GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567&body=COD%2B1")
        assertEquals("+15551234567", ios!!.recipient)
        assertEquals("COD+1", ios.body)
        // intent: de SMS. Se toman SOLO número y texto: el paquete y los otros extras no importan.
        val intent = GoogleVerificationPolicy.parseSmsHandoff(
            "intent://+15551234567#Intent;scheme=smsto;package=com.evil.app;S.extra=x;S.sms_body=G-1%20ok;end"
        )
        assertEquals("+15551234567", intent!!.recipient)
        assertEquals("G-1 ok", intent.body)
        assertEquals("ABC", GoogleVerificationPolicy.parseSmsHandoff("intent:24444#Intent;scheme=sms;S.sms_body=ABC;end")!!.body)
        // Un intent que NO es de SMS sigue sin servir (y en el microvisor se bloquea como antes).
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("intent://www.youtube.com/#Intent;scheme=https;package=com.android.chrome;end"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("intent://+15551234567#Intent;scheme=tel;end"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("intent://+15551234567#Intent;scheme=smsto"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("intent://+15551234567,+15557654321#Intent;scheme=smsto;S.sms_body=x;end"))
    }

    @Test
    fun `el codigo del SMS no pierde el signo mas (URLDecoder lo convertiria en espacio)`() {
        val sms = GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567?body=a+b%2Bc")
        assertEquals("a+b+c", sms!!.body)
        // UTF-8 de verdad, también en hebreo.
        assertEquals("קוד 12", GoogleVerificationPolicy.parseSmsHandoff("sms:+97221234567?body=%D7%A7%D7%95%D7%93%2012")!!.body)
    }

    @Test
    fun `rechaza SMS armados para otra cosa`() {
        // Varios destinatarios.
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567,+15557654321?body=x"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567%2C+15557654321?body=x"))
        // Letras o largo imposible.
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:GOOGLE?body=x"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:12?body=x"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+1234567890123456?body=x"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:?body=x"))
        // Texto demasiado largo o con caracteres de control.
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567?body=" + "a".repeat(321)))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567?body=a%0Ab"))
        // Escapes rotos o UTF-8 inválido.
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567?body=%ZZ"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567?body=%E2%28"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+15551234567?body=%4"))
        // Dígitos que no son ASCII.
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("sms:+١٥٥٥١٢٣٤٥٦٧?body=x"))
        // Otros esquemas.
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("tel:+15551234567"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("mms:+15551234567?body=x"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("intent://x#Intent;scheme=sms;end"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff("https://accounts.google.com/devicephoneverification/begin"))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff(null))
        assertNull(GoogleVerificationPolicy.parseSmsHandoff(""))
    }

    @Test
    fun `el SMS solo se acepta desde la verificacion por QR`() {
        assertTrue(GoogleVerificationPolicy.isSmsHandoffContext("https://accounts.google.com/devicephoneverification/consent?x=1"))
        assertTrue(GoogleVerificationPolicy.isSmsHandoffContext("https://accounts.google.com/devicephoneverification/verify"))
        assertTrue(GoogleVerificationPolicy.isSmsHandoffContext("https://accounts.google.com/devicephoneverification"))
        // Otras pantallas del propio microvisor, no.
        assertFalse(GoogleVerificationPolicy.isSmsHandoffContext("https://accounts.google.com/v3/signin/challenge/ipp"))
        assertFalse(GoogleVerificationPolicy.isSmsHandoffContext("https://accounts.google.com/CheckCookie"))
        assertFalse(GoogleVerificationPolicy.isSmsHandoffContext("about:blank"))
        assertFalse(GoogleVerificationPolicy.isSmsHandoffContext("https://accounts.google.com/devicephoneverificationotra"))
        // Afuera, nunca.
        assertFalse(GoogleVerificationPolicy.isSmsHandoffContext("https://evil.example/devicephoneverification/consent"))
        assertFalse(GoogleVerificationPolicy.isSmsHandoffContext("https://accounts.google.com.evil.com/devicephoneverification/consent"))
        assertFalse(GoogleVerificationPolicy.isSmsHandoffContext(null))
        // Y sms: sigue sin ser una navegación del WebView.
        assertFalse(GoogleVerificationPolicy.isNavigationAllowed("sms:+15551234567?body=x"))
    }

    @Test
    fun `el registro nunca guarda la query ni el contenido del SMS`() {
        val log = GoogleVerificationPolicy.describeForLog("https://accounts.google.com/devicephoneverification/begin?flow=browser&plt=SECRETO")
        assertEquals("https://accounts.google.com/devicephoneverification/begin", log)
        assertFalse(GoogleVerificationPolicy.describeForLog("https://www.youtube.com/watch?v=SECRETO#t=1").contains("SECRETO"))
        assertEquals("sms:(oculto)", GoogleVerificationPolicy.describeForLog("sms:+15551234567?body=SECRETO"))
        assertEquals("data:(oculto)", GoogleVerificationPolicy.describeForLog("data:text/html,SECRETO"))
        assertTrue(GoogleVerificationPolicy.describeForLog("https://accounts.google.com/" + "a".repeat(500)).length <= 120)
        // La forma de un SMS rechazado sirve para diagnosticar sin anotar el código.
        val shape = GoogleVerificationPolicy.describeSmsShapeForLog("sms:+16505550123?body=G-123%20OK")
        assertEquals("sms:+99999999999?xxxx=x-999%99xx", shape)
        assertFalse(shape.contains("650"))
        assertFalse(shape.contains("G-123"))
    }

    @Test
    fun `el User-Agent es de Chrome con la version real del WebView y sin marcas de WebView`() {
        val webViewUa = "Mozilla/5.0 (Linux; Android 13; SM-A065M Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        val ua = GoogleVerificationPolicy.chromeLikeUserAgent(webViewUa)
        assertTrue(ua.contains("Chrome/141.0.0.0"))
        assertFalse(ua.contains("; wv"))
        assertFalse(ua.contains("Version/4.0"))
        assertFalse(ua.contains("LockSuite"))
        assertTrue(ua.endsWith("Mobile Safari/537.36"))
        // Sin dato o con basura: una versión fija razonable, nunca vacío.
        assertTrue(GoogleVerificationPolicy.chromeLikeUserAgent(null).contains("Chrome/"))
        assertTrue(GoogleVerificationPolicy.chromeLikeUserAgent("algo raro").contains("Chrome/"))
    }
}
