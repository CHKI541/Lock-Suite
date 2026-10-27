package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.UpdateRoutePlanner.Firma
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner.HostedVerdict
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner.Plan
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner.StoreEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26/10/2026 (B.97) — por qué camino se actualiza una app y cuándo la copia de la Tienda
 * NO se instala. Lo caro de equivocarse acá: instalar una copia vieja o ajena en silencio
 * (o, al revés, decir "no se puede" cuando Google Play sí podía).
 */
class UpdateRoutePlannerTest {

    private val pkg = "com.whatsapp"
    private val sha = "a".repeat(64)
    private val buena = StoreEntry(pkg, "https://locksuite-nueva.web.app/apks/wa.apk", sha, "WhatsApp")

    // ── Preferencia ─────────────────────────────────────────────────────────────

    @Test
    fun normalizePref_desconocidoEsAutomatico() {
        assertEquals(UpdateRoutePlanner.PREF_AUTO, UpdateRoutePlanner.normalizePref(null))
        assertEquals(UpdateRoutePlanner.PREF_AUTO, UpdateRoutePlanner.normalizePref(""))
        assertEquals(UpdateRoutePlanner.PREF_AUTO, UpdateRoutePlanner.normalizePref("cualquiera"))
        assertEquals(UpdateRoutePlanner.PREF_PLAY, UpdateRoutePlanner.normalizePref(" PLAY "))
        assertEquals(UpdateRoutePlanner.PREF_HOSTED, UpdateRoutePlanner.normalizePref("hosted"))
        assertEquals(UpdateRoutePlanner.PREF_HOSTED, UpdateRoutePlanner.normalizePref("tienda"))
    }

    // ── ¿La entrada de la Tienda sirve? ────────────────────────────────────────

    @Test
    fun hostedProblem_cadaMotivo() {
        assertNull(UpdateRoutePlanner.hostedProblem(buena, pkg))
        assertNotNull(UpdateRoutePlanner.hostedProblem(null, pkg))
        assertNotNull(UpdateRoutePlanner.hostedProblem(buena.copy(packageName = "com.otra"), pkg))
        assertNotNull(UpdateRoutePlanner.hostedProblem(buena.copy(apkUrl = "http://x/y.apk"), pkg))
        assertNotNull(UpdateRoutePlanner.hostedProblem(buena.copy(sha256 = null), pkg))
        assertNotNull(UpdateRoutePlanner.hostedProblem(buena.copy(sha256 = ""), pkg))
        assertNotNull(UpdateRoutePlanner.hostedProblem(buena.copy(sha256 = "abc"), pkg))
        // Mayúsculas: el panel guarda en minúscula, pero una pegada a mano puede venir así.
        assertNull(UpdateRoutePlanner.hostedProblem(buena.copy(sha256 = "A".repeat(64)), pkg))
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    private fun plan(
        pref: String = UpdateRoutePlanner.PREF_AUTO,
        entry: StoreEntry? = buena,
        installed: Long = 100,
        cached: Long? = null,
        acc: Boolean = true,
        playProblem: String? = null,
        playManaged: Boolean = false
    ) = UpdateRoutePlanner.plan(pref, pkg, entry, installed, cached, acc, playProblem, playManaged)

    @Test
    fun automatico_appQueNoInstaloPlay_conCopiaEnLaTienda_vaSilencioso() {
        assertTrue(plan() is Plan.Hosted)
        // Sin accesibilidad igual se puede: la ruta silenciosa no la necesita.
        assertTrue(plan(acc = false) is Plan.Hosted)
        // Sin Google Play también.
        assertTrue(plan(playProblem = "deshabilitada") is Plan.Hosted)
    }

    @Test
    fun automatico_appQueInstaloPlay_vaPorPlay_aunqueEsteEnLaTienda() {
        // Lo que pidió el dueño: "que actualice desde Play Store". La copia de la Tienda
        // puede ser más vieja que la de Google Play; no se la usa si Play se puede usar.
        val p = plan(playManaged = true)
        assertTrue(p is Plan.PlayStore)
        assertTrue((p as Plan.PlayStore).why.contains("reintentar eligiendo la Tienda"))
        // …salvo que Google Play no se pueda usar: ahí la Tienda es el único camino.
        assertTrue(plan(playManaged = true, acc = false) is Plan.Hosted)
        assertTrue(plan(playManaged = true, playProblem = "deshabilitada") is Plan.Hosted)
    }

    @Test
    fun automatico_copiaYaMiradaYNoMasNueva_vaPorPlay_sinBajarla() {
        val p = plan(cached = 100, installed = 100)
        assertTrue(p is Plan.PlayStore)
        assertTrue((p as Plan.PlayStore).why.contains("no es más nueva"))
        assertTrue(plan(cached = 90, installed = 100) is Plan.PlayStore)
        // Si la copia mirada SÍ es más nueva, se usa.
        assertTrue(plan(cached = 101, installed = 100) is Plan.Hosted)
    }

    @Test
    fun automatico_copiaViejaYSinPlay_fallaConLasDosRazones() {
        val p = plan(cached = 50, installed = 100, acc = false)
        assertTrue(p is Plan.Fail)
        val m = (p as Plan.Fail).message
        assertTrue(m, m.contains("no es más nueva"))
        assertTrue(m, m.contains("Accesibilidad"))
    }

    @Test
    fun automatico_sinCopia_vaPorPlay() {
        val p = plan(entry = null)
        assertTrue(p is Plan.PlayStore)
        assertTrue((p as Plan.PlayStore).why.contains("no está en la Tienda"))
        // Una entrada sin huella cuenta como "no hay copia usable".
        assertTrue(plan(entry = buena.copy(sha256 = null)) is Plan.PlayStore)
    }

    @Test
    fun automatico_sinCopiaYSinAccesibilidad_fallaDiciendoPorQue() {
        val p = plan(entry = null, acc = false)
        assertTrue(p is Plan.Fail)
        assertEquals(UpdateRoutePlanner.RESULT_ERROR, (p as Plan.Fail).result)
        assertTrue(p.message.contains("Accesibilidad"))
        assertTrue(p.message.contains("no está cargada en la Tienda"))
    }

    @Test
    fun forzadoPlay_nuncaUsaLaTienda() {
        assertTrue(plan(pref = UpdateRoutePlanner.PREF_PLAY) is Plan.PlayStore)
        val sinAcc = plan(pref = UpdateRoutePlanner.PREF_PLAY, acc = false)
        assertTrue(sinAcc is Plan.Fail)
        val sinPlay = plan(pref = UpdateRoutePlanner.PREF_PLAY, playProblem = "Google Play está deshabilitada")
        assertTrue(sinPlay is Plan.Fail)
        assertTrue((sinPlay as Plan.Fail).message.contains("deshabilitada"))
    }

    @Test
    fun forzadoTienda_nuncaUsaPlay() {
        assertTrue(plan(pref = UpdateRoutePlanner.PREF_HOSTED) is Plan.Hosted)
        val sin = plan(pref = UpdateRoutePlanner.PREF_HOSTED, entry = null)
        assertTrue(sin is Plan.Fail)
        assertEquals(UpdateRoutePlanner.RESULT_NOT_AVAILABLE, (sin as Plan.Fail).result)
        // Forzado a la Tienda, NO mira el cache de versión: el administrador lo pidió.
        assertTrue(plan(pref = UpdateRoutePlanner.PREF_HOSTED, cached = 1, installed = 100) is Plan.Hosted)
    }

    // ── Juicio sobre el APK bajado ─────────────────────────────────────────────

    private fun juzgar(
        archivePkg: String? = pkg,
        archiveVc: Long = 101,
        installed: Long = 100,
        minSdk: Int = 24,
        sdk: Int = 30,
        apkAbis: Set<String> = emptySet(),
        devAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        firma: Firma = Firma.COINCIDE
    ) = UpdateRoutePlanner.judgeHostedApk(pkg, archivePkg, archiveVc, installed, minSdk, sdk, apkAbis, devAbis, firma)

    @Test
    fun juicio_casoBueno_instala() {
        assertTrue(juzgar() === HostedVerdict.Install)
    }

    @Test
    fun juicio_nuncaInstalaLaMismaNiUnaMasVieja() {
        val igual = juzgar(archiveVc = 100)
        assertTrue(igual is HostedVerdict.Reject)
        assertTrue((igual as HostedVerdict.Reject).reason.contains("misma versión"))
        assertTrue(igual.fallbackToPlay)
        val vieja = juzgar(archiveVc = 99)
        assertTrue(vieja is HostedVerdict.Reject)
        assertTrue((vieja as HostedVerdict.Reject).reason.contains("más vieja"))
    }

    @Test
    fun juicio_otraApp_archivoIlegible_firmaDistinta_rechaza() {
        assertTrue(juzgar(archivePkg = null) is HostedVerdict.Reject)
        assertTrue(juzgar(archivePkg = "com.otra") is HostedVerdict.Reject)
        val distinta = juzgar(firma = Firma.DISTINTA)
        assertTrue(distinta is HostedVerdict.Reject)
        assertTrue((distinta as HostedVerdict.Reject).reason.contains("firmada por otro"))
        // Firma ilegible: decide Android (que nunca acepta otra firma); la huella ya
        // garantizó que el archivo es el publicado.
        assertTrue(juzgar(firma = Firma.DESCONOCIDA) === HostedVerdict.Install)
        val noInst = juzgar(firma = Firma.NO_INSTALADA)
        assertTrue(noInst is HostedVerdict.Reject)
        assertFalse((noInst as HostedVerdict.Reject).fallbackToPlay)
    }

    @Test
    fun juicio_androidDemasiadoViejo_rechaza() {
        val r = juzgar(minSdk = 29, sdk = 28)
        assertTrue(r is HostedVerdict.Reject)
        assertTrue((r as HostedVerdict.Reject).reason.contains("API 29"))
        assertTrue(juzgar(minSdk = 29, sdk = 29) === HostedVerdict.Install)
    }

    @Test
    fun juicio_arquitectura_casosMedidosEnB66() {
        // Waze/Traductor de la Tienda: solo arm64. En un equipo de 32 bits no instala.
        val r = juzgar(apkAbis = setOf("arm64-v8a"), devAbis = listOf("armeabi-v7a", "armeabi"))
        assertTrue(r is HostedVerdict.Reject)
        assertTrue((r as HostedVerdict.Reject).reason.contains("arm64-v8a"))
        // Gboard de la Tienda: solo armeabi-v7a. En un arm64 que también corre 32 bits, sí.
        assertTrue(juzgar(apkAbis = setOf("armeabi-v7a")) === HostedVerdict.Install)
        // Sin código nativo, siempre.
        assertTrue(juzgar(apkAbis = emptySet(), devAbis = listOf("x86")) === HostedVerdict.Install)
    }

    @Test
    fun abisFromZipEntries_soloCarpetasConBibliotecas() {
        val abis = UpdateRoutePlanner.abisFromZipEntries(
            listOf(
                "AndroidManifest.xml", "classes.dex",
                "lib/arm64-v8a/libfoo.so", "lib/arm64-v8a/libbar.so",
                "lib/armeabi-v7a/libfoo.so",
                "lib/x86/README.txt",      // sin .so: no cuenta
                "lib/", "lib/libsuelta.so"  // mal formadas: no cuentan
            )
        )
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), abis)
        assertTrue(UpdateRoutePlanner.abisFromZipEntries(listOf("classes.dex")).isEmpty())
    }

    // ── Fallas de instalación ──────────────────────────────────────────────────

    @Test
    fun falloDeInstalacion_sinEspacioNoVaAPlay_elRestoSi() {
        val sinEsp = UpdateRoutePlanner.hostedInstallFailure(UpdateRoutePlanner.STATUS_FAILURE_STORAGE, null)
        assertEquals(UpdateRoutePlanner.RESULT_NO_SPACE, sinEsp.result)
        assertFalse(sinEsp.fallbackToPlay)
        // Algunos fabricantes devuelven FAILURE genérico con el texto de Android adentro.
        val porTexto = UpdateRoutePlanner.hostedInstallFailure(
            UpdateRoutePlanner.STATUS_FAILURE, "INSTALL_FAILED_INSUFFICIENT_STORAGE: ..."
        )
        assertEquals(UpdateRoutePlanner.RESULT_NO_SPACE, porTexto.result)
        val conflicto = UpdateRoutePlanner.hostedInstallFailure(UpdateRoutePlanner.STATUS_FAILURE_CONFLICT, "x")
        assertEquals(UpdateRoutePlanner.RESULT_ERROR, conflicto.result)
        assertTrue(conflicto.fallbackToPlay)
        assertTrue(conflicto.reason.contains("(x)"))
    }

    @Test
    fun sinEspacio_seReconoceEnLosTextosDeAndroid() {
        assertTrue(UpdateRoutePlanner.isNoSpaceMessage("write failed: ENOSPC (No space left on device)"))
        assertTrue(UpdateRoutePlanner.isNoSpaceMessage("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        assertFalse(UpdateRoutePlanner.isNoSpaceMessage("HTTP 404"))
        assertFalse(UpdateRoutePlanner.isNoSpaceMessage(null))
    }

    @Test
    fun cacheDeVersion_idaYVuelta_reemplazo_yTope() {
        val a = "ab".repeat(32)
        val b = "cd".repeat(32)
        var raw: String? = null
        assertNull(UpdateRoutePlanner.vcCacheGet(raw, a))
        raw = UpdateRoutePlanner.vcCachePut(raw, a, 100)
        raw = UpdateRoutePlanner.vcCachePut(raw, b, 7)
        assertEquals(100L, UpdateRoutePlanner.vcCacheGet(raw, a))
        assertEquals(7L, UpdateRoutePlanner.vcCacheGet(raw, b))
        // La misma huella con otra versión reemplaza, no duplica.
        raw = UpdateRoutePlanner.vcCachePut(raw, a, 120)
        assertEquals(120L, UpdateRoutePlanner.vcCacheGet(raw, a))
        assertEquals(2, raw.split(';').size)
        // Mayúsculas en la huella: misma clave.
        assertEquals(120L, UpdateRoutePlanner.vcCacheGet(raw, a.uppercase()))
        // Tope: lo más viejo se va. (La clave son los primeros 16 caracteres de la huella:
        // las huellas de prueba tienen que diferir AL PRINCIPIO.)
        var r2: String? = null
        for (i in 0 until 40) r2 = UpdateRoutePlanner.vcCachePut(r2, i.toString().padEnd(64, 'f'), i.toLong(), max = 30)
        assertEquals(30, r2!!.split(';').size)
        assertNull(UpdateRoutePlanner.vcCacheGet(r2, "0".padEnd(64, 'f')))
        assertEquals(39L, UpdateRoutePlanner.vcCacheGet(r2, "39".padEnd(64, 'f')))
    }

    @Test
    fun timeout_cadaEtapaTieneSuMotivo() {
        val etapas = listOf(
            UpdateRoutePlanner.STAGE_WAITING_UNLOCK, UpdateRoutePlanner.STAGE_CHOOSING_ROUTE,
            UpdateRoutePlanner.STAGE_WAITING_STORE, UpdateRoutePlanner.STAGE_LOOKING_BUTTON,
            UpdateRoutePlanner.STAGE_QUEUED, UpdateRoutePlanner.STAGE_DOWNLOADING,
            UpdateRoutePlanner.STAGE_HOSTED_DOWNLOADING, UpdateRoutePlanner.STAGE_HOSTED_INSTALLING
        )
        val generico = UpdateRoutePlanner.timeoutReasonForStage("NADA")
        for (e in etapas) {
            val m = UpdateRoutePlanner.timeoutReasonForStage(e)
            assertTrue("etapa $e sin motivo propio", m != generico && m.isNotBlank())
        }
        assertTrue(UpdateRoutePlanner.timeoutReasonForStage(UpdateRoutePlanner.STAGE_WAITING_UNLOCK).contains("bloqueado"))
    }

    // ── Flujo huérfano después de un reinicio del proceso ─────────────────────────

    @Test
    fun huerfano_googlePlayNoSeToca_laRetomaLaAccesibilidad() {
        for (e in listOf(
            UpdateRoutePlanner.STAGE_WAITING_STORE, UpdateRoutePlanner.STAGE_DOWNLOADING,
            UpdateRoutePlanner.STAGE_WAITING_UNLOCK, UpdateRoutePlanner.STAGE_QUEUED
        )) {
            assertNull(UpdateRoutePlanner.orphanReasonAfterRestart(UpdateRoutePlanner.ROUTE_PLAY_STORE, e))
        }
    }

    @Test
    fun huerfano_instalacionYaEntregadaAlSistemaNoSeToca() {
        // Android la termina aunque LockSuite se reinicie; la confirman los receptores.
        assertNull(
            UpdateRoutePlanner.orphanReasonAfterRestart(
                UpdateRoutePlanner.ROUTE_HOSTED, UpdateRoutePlanner.STAGE_HOSTED_INSTALLING
            )
        )
    }

    @Test
    fun huerfano_trabajoDeEsteProcesoSeCierraDiciendoDonde() {
        val eligiendo = UpdateRoutePlanner.orphanReasonAfterRestart(
            UpdateRoutePlanner.ROUTE_UNDECIDED, UpdateRoutePlanner.STAGE_CHOOSING_ROUTE
        )
        assertNotNull(eligiendo)
        assertTrue(eligiendo!!.contains("elegía"))
        val bajando = UpdateRoutePlanner.orphanReasonAfterRestart(
            UpdateRoutePlanner.ROUTE_HOSTED, UpdateRoutePlanner.STAGE_HOSTED_DOWNLOADING
        )
        assertTrue(bajando!!.contains("bajaba"))
        val verificando = UpdateRoutePlanner.orphanReasonAfterRestart(
            UpdateRoutePlanner.ROUTE_HOSTED, UpdateRoutePlanner.STAGE_HOSTED_VERIFYING
        )
        assertTrue(verificando!!.contains("verificaba"))
        // Una ruta desconocida (dato roto) también es trabajo de este proceso: se cierra.
        assertNotNull(UpdateRoutePlanner.orphanReasonAfterRestart("RARO", UpdateRoutePlanner.STAGE_PREPARING))
    }

    // ── Huella de los números de la pantalla de Google Play ──────────────────────

    @Test
    fun huellaDeNumeros_cambiaSoloSiCambianLosNumeros() {
        val fijo = listOf("whatsapp messenger", "4,3", "10 m+", "3+", "actualizar", "acerca de esta app")
        val a = UpdateRoutePlanner.progressSignature(fijo + listOf("12 mb de 30 mb"))
        val b = UpdateRoutePlanner.progressSignature(fijo + listOf("13 mb de 30 mb"))
        val c = UpdateRoutePlanner.progressSignature(fijo + listOf("13 mb de 30 mb"))
        assertTrue(a != b)
        assertEquals(b, c)
        // Los textos sin números no cuentan: que cambie un título no es "avanzar".
        assertEquals(
            UpdateRoutePlanner.progressSignature(listOf("pendiente", "4,3")),
            UpdateRoutePlanner.progressSignature(listOf("esperando", "4,3"))
        )
        // Ni los textos largos (la descripción de la app puede tener números).
        assertEquals("", UpdateRoutePlanner.progressSignature(listOf("la version 2 trae 15 mejoras para chatear con tus amigos y familia")))
        assertEquals("", UpdateRoutePlanner.progressSignature(emptyList()))
    }

    @Test
    fun huellaDeNumeros_tieneTope() {
        val muchos = (1..200).map { "$it %" }
        assertTrue(UpdateRoutePlanner.progressSignature(muchos).length <= 420)
    }
}
