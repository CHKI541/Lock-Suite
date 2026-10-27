package com.ejemplo.locksuite.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner.Firma
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner.HostedVerdict
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner.Plan
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner.StoreEntry
import com.ejemplo.locksuite.mdm.UpdateStepLog
import com.ejemplo.locksuite.service.LockSuiteAccessibilityService
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * La ruta SILENCIOSA de "actualizar una app" (26/10/2026, B.97): la copia de la Tienda de
 * LockSuite, bajada e instalada como Device Owner, sin abrir Google Play y sin tocar la
 * pantalla. También es quien ELIGE el camino cuando el pedido es "automático" o "Tienda":
 * consulta la Tienda (es red, por eso vive acá y en segundo plano) y le pregunta a
 * `UpdateRoutePlanner` (puro, con pruebas) por dónde ir.
 *
 * Reglas:
 *  · Todo lo que decide queda como PASO en el registro (UpdateFlowManager.step): qué
 *    encontró en la Tienda, por qué eligió ese camino, qué versión trae la copia, si la
 *    firma coincide, qué contestó el sistema.
 *  · Nada de esto toca la pantalla: el usuario sigue usando el teléfono, con todos los
 *    filtros andando, y ve el progreso en una notificación.
 *  · Se trabaja SIEMPRE para un flujo identificado (paquete + hora de arranque, ver
 *    UpdateFlowManager.isSameFlow). Si el flujo se cerró (cancelado, watchdog) o empezó
 *    otro, esto se calla y se va: el cierre ya lo hizo quien lo cerró.
 *  · Si la copia de la Tienda no sirve y el pedido era "automático", se sigue por Google
 *    Play (UpdateFlowManager.fallbackToPlayStore) — salvo falta de espacio, que Google
 *    Play tampoco puede arreglar.
 *  · La decisión de instalar se toma con el APK YA BAJADO y con su sha256 YA VERIFICADO
 *    (SelfUpdater.preInstallCheck), y ANTES de levantar ninguna restricción.
 */
object HostedAppUpdater {

    private const val TAG = "LockSuite_Update"

    /** Cache "huella → versionCode" de las copias de la Tienda ya inspeccionadas. */
    private const val KEY_VC_CACHE = "update_flow_hosted_vc_cache"

    private const val STORE_LOOKUP_TIMEOUT_MS = 10_000L
    private const val INSTALL_POLL_MS = 2_000L
    /** Un poco más que el watchdog del flujo: el que cierra por tiempo es el watchdog. */
    private const val INSTALL_WATCH_MAX_MS = 11 * 60_000L
    private const val INSTALL_SLOW_NOTICE_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * El flujo (su token, ver UpdateFlowManager.flowToken) cuyo trabajo corre AHORA en este
     * proceso; 0 = ninguno. Con esto UpdateFlowManager.closeOrphanedAfterRestart() sabe si
     * un flujo de la Tienda abierto tiene a alguien trabajando o quedó huérfano.
     */
    @Volatile
    private var liveToken = 0L

    fun isWorkingOn(token: Long): Boolean = token != 0L && liveToken == token

    /**
     * Elige el camino y lo recorre. Lo llama UpdateFlowManager.start() con el flujo ya
     * abierto (paquete, etapa CHOOSING_ROUTE, watchdog armado). Vuelve enseguida.
     */
    fun chooseAndRun(context: Context, packageName: String, pref: String) {
        val ctx = context.applicationContext
        val token = UpdateFlowManager.flowToken(ctx)
        liveToken = token
        scope.launch {
            try {
                run(ctx, packageName, UpdateRoutePlanner.normalizePref(pref), token)
            } catch (e: Exception) {
                Log.e(TAG, "Ruta de la Tienda: error inesperado para $packageName", e)
                if (UpdateFlowManager.isSameFlow(ctx, packageName, token)) {
                    val msg = "Error inesperado preparando la actualización: ${e.message ?: e.javaClass.simpleName}"
                    UpdateFlowManager.step(ctx, UpdateStepLog.ERROR, msg)
                    UpdateFlowManager.finish(ctx, UpdateRoutePlanner.RESULT_ERROR, msg, msg)
                }
            } finally {
                if (liveToken == token) liveToken = 0L
            }
        }
    }

    private suspend fun run(ctx: Context, pkg: String, pref: String, token: Long) {
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val label = UpdateFlowManager.appLabel(ctx, pkg)

        val lookup = lookupStoreEntry(pkg)
        if (!UpdateFlowManager.isSameFlow(ctx, pkg, token)) return
        val entry = lookup.entry
        when {
            lookup.error != null ->
                UpdateFlowManager.step(ctx, UpdateStepLog.WARN, "No se pudo consultar la Tienda de LockSuite: ${lookup.error}.")
            entry == null ->
                UpdateFlowManager.step(ctx, UpdateStepLog.INFO, "La app no está en la Tienda de LockSuite.")
            else -> {
                val problema = UpdateRoutePlanner.hostedProblem(entry, pkg)
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.INFO,
                    if (problema == null) "La app está en la Tienda de LockSuite, con huella sha256."
                    else "La app está en la Tienda de LockSuite, pero $problema."
                )
            }
        }

        if (pref == UpdateRoutePlanner.PREF_HOSTED && lookup.error != null) {
            val msg = "Se pidió actualizar desde la Tienda de LockSuite, pero no se la pudo consultar (${lookup.error})."
            UpdateFlowManager.step(ctx, UpdateStepLog.ERROR, msg)
            UpdateFlowManager.finish(ctx, UpdateRoutePlanner.RESULT_ERROR, msg, msg)
            return
        }

        val installer = prefs.getString(UpdateFlowManager.KEY_INSTALLER, "") ?: ""
        val installedVc = UpdateFlowManager.versionCodeOf(ctx, pkg)
        val cachedVc = UpdateRoutePlanner.vcCacheGet(prefs.getString(KEY_VC_CACHE, null), entry?.sha256)
        val plan = UpdateRoutePlanner.plan(
            pref, pkg, entry, installedVc, cachedVc,
            LockSuiteAccessibilityService.instance != null,
            UpdateFlowManager.playStoreProblem(ctx),
            UpdateFlowManager.isPlayManaged(ctx, pkg, installer)
        )
        Log.i(TAG, "Plan para $pkg (pedido=$pref): $plan")

        when (plan) {
            is Plan.Fail -> {
                UpdateFlowManager.step(ctx, UpdateStepLog.ERROR, plan.message)
                UpdateFlowManager.finish(ctx, plan.result, plan.message, plan.message)
            }
            is Plan.PlayStore -> {
                val why = if (lookup.error != null) {
                    "No se pudo consultar la Tienda de LockSuite (${lookup.error})."
                } else {
                    plan.why
                }
                // beginPlayStoreRoute abre Play Store y registra el SessionCallback: en el
                // hilo principal, como siempre lo corrió start().
                UpdateFlowManager.runOnMain {
                    if (UpdateFlowManager.isSameFlow(ctx, pkg, token)) {
                        UpdateFlowManager.beginPlayStoreRoute(ctx, pkg, why)
                    }
                }
            }
            is Plan.Hosted -> runHosted(ctx, pkg, label, plan.entry, plan.why, pref, token)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // La copia de la Tienda
    // ─────────────────────────────────────────────────────────────────────────────

    /** Lo que se averiguó del APK bajado, para decidir qué hacer si no sirve. */
    private class Juicio(val rechazo: HostedVerdict.Reject?, val porVersion: Boolean)

    private suspend fun runHosted(
        ctx: Context,
        pkg: String,
        label: String,
        entry: StoreEntry,
        why: String,
        pref: String,
        token: Long
    ) {
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        prefs.edit()
            .putString(UpdateFlowManager.KEY_ROUTE, UpdateFlowManager.ROUTE_HOSTED)
            .putString(UpdateFlowManager.KEY_ROUTE_WHY, why)
            .apply()
        UpdateFlowManager.step(
            ctx, UpdateStepLog.INFO,
            "Camino elegido: copia de la Tienda de LockSuite, en silencio (sin abrir Google Play ni tapar la pantalla). $why"
        )
        UpdateFlowManager.setStage(ctx, UpdateRoutePlanner.STAGE_HOSTED_DOWNLOADING, null, progress = 0)
        UpdateFlowManager.notifyUser(ctx, "Actualizando $label", "Descargando de la Tienda de LockSuite…", -1, ongoing = true)

        var juicio: Juicio? = null
        var ultimoPctAviso = -10
        val error = SelfUpdater.downloadAndInstallApk(
            context = ctx,
            apkUrl = entry.apkUrl,
            packageName = pkg,
            label = label,
            sha256 = entry.sha256,
            onProgress = { pct ->
                if (UpdateFlowManager.isSameFlow(ctx, pkg, token)) {
                    UpdateFlowManager.setStage(
                        ctx, UpdateRoutePlanner.STAGE_HOSTED_DOWNLOADING,
                        "Descargando de la Tienda de LockSuite... $pct%", progress = pct
                    )
                    if (pct - ultimoPctAviso >= 5 || pct >= 100) {
                        ultimoPctAviso = pct
                        UpdateFlowManager.notifyUser(
                            ctx, "Actualizando $label", "Descargando de la Tienda de LockSuite… $pct%", pct, ongoing = true
                        )
                    }
                }
            },
            quiet = true,
            isCancelled = { !UpdateFlowManager.isSameFlow(ctx, pkg, token) },
            preInstallCheck = { file ->
                if (!UpdateFlowManager.isSameFlow(ctx, pkg, token)) {
                    "Actualización cancelada"
                } else {
                    val j = juzgar(ctx, pkg, label, file, entry)
                    juicio = j
                    j.rechazo?.reason
                }
            }
        )

        // ¿El flujo se cerró mientras tanto (cancelado, watchdog, otro pedido)? Ya lo
        // cerró quien lo cerró; acá no queda nada que decir.
        if (!UpdateFlowManager.isSameFlow(ctx, pkg, token)) return

        val j = juicio
        val rechazo = j?.rechazo
        val porVersion = j?.porVersion == true
        when {
            error == null -> {
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.ACT,
                    "Le pasé el APK al instalador del sistema (instalación silenciosa como Device Owner). Esperando su confirmación…"
                )
                UpdateFlowManager.setStage(ctx, UpdateRoutePlanner.STAGE_HOSTED_INSTALLING)
                watchInstall(ctx, pkg, token)
            }
            rechazo != null -> {
                val motivo = "La copia de la Tienda no sirve para este equipo: ${rechazo.reason}."
                if (rechazo.fallbackToPlay && pref == UpdateRoutePlanner.PREF_AUTO) {
                    UpdateFlowManager.fallbackToPlayStore(ctx, pkg, motivo)
                } else if (porVersion) {
                    UpdateFlowManager.step(ctx, UpdateStepLog.OK, "No hay nada que instalar desde la Tienda: ${rechazo.reason}.")
                    UpdateFlowManager.finish(
                        ctx, UpdateRoutePlanner.RESULT_UP_TO_DATE,
                        "$label ya tiene la versión de la Tienda de LockSuite (o una más nueva).", motivo
                    )
                } else {
                    UpdateFlowManager.step(ctx, UpdateStepLog.ERROR, motivo)
                    UpdateFlowManager.finish(
                        ctx, UpdateRoutePlanner.RESULT_ERROR,
                        "No se pudo actualizar $label desde la Tienda: ${rechazo.reason}.", motivo
                    )
                }
            }
            else -> {
                val motivo = "No se pudo usar la copia de la Tienda: $error"
                when {
                    UpdateRoutePlanner.isNoSpaceMessage(error) -> {
                        UpdateFlowManager.step(ctx, UpdateStepLog.ERROR, "$motivo (sin espacio: Google Play tampoco podría).")
                        UpdateFlowManager.finish(
                            ctx, UpdateRoutePlanner.RESULT_NO_SPACE,
                            "No hay espacio para bajar la actualización de $label. Liberá espacio y volvé a intentar.", motivo
                        )
                    }
                    pref == UpdateRoutePlanner.PREF_AUTO ->
                        UpdateFlowManager.fallbackToPlayStore(ctx, pkg, conPunto(motivo))
                    else -> {
                        UpdateFlowManager.step(ctx, UpdateStepLog.ERROR, motivo)
                        UpdateFlowManager.finish(
                            ctx, UpdateRoutePlanner.RESULT_ERROR,
                            "No se pudo actualizar $label desde la Tienda: $error", motivo
                        )
                    }
                }
            }
        }
    }

    /**
     * El juicio sobre el APK ya bajado y con la huella ya verificada. Corre adentro de
     * SelfUpdater, ANTES de levantar ninguna restricción. Si la copia sirve, deja el flujo
     * en HOSTED_INSTALLING: desde ahí, "Cancelar" dice la verdad (ya no se puede frenar).
     */
    private fun juzgar(ctx: Context, pkg: String, label: String, file: File, entry: StoreEntry): Juicio {
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val mb = file.length() / (1024L * 1024L)
        UpdateFlowManager.step(ctx, UpdateStepLog.OK, "Descarga completa ($mb MB): la huella sha256 coincide con la publicada.")
        UpdateFlowManager.setStage(ctx, UpdateRoutePlanner.STAGE_HOSTED_VERIFYING)

        val info = try {
            ctx.packageManager.getPackageArchiveInfo(file.path, 0)
        } catch (e: Exception) {
            null
        }
        val archivePkg = info?.packageName
        val archiveVc = when {
            info == null -> -1L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        val archiveVn = info?.versionName ?: "?"
        val minSdk = info?.applicationInfo?.minSdkVersion ?: 0
        val abis = try {
            ZipFile(file).use { z -> UpdateRoutePlanner.abisFromZipEntries(z.entries().asSequence().map { it.name }.toList()) }
        } catch (e: Exception) {
            emptySet()
        }
        val deviceAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val firma = when (ApkSignatureVerifier.verify(ctx, file.path, pkg)) {
            ApkSignatureVerifier.Result.Match -> Firma.COINCIDE
            ApkSignatureVerifier.Result.NotInstalled -> Firma.NO_INSTALADA
            is ApkSignatureVerifier.Result.Mismatch -> Firma.DISTINTA
            is ApkSignatureVerifier.Result.Unknown -> Firma.DESCONOCIDA
        }

        // La versión de esta copia queda anotada SIEMPRE (sirva o no): la próxima vez que se
        // pida actualizar esta app, si la copia sigue siendo la misma y no es más nueva, se va
        // directo por Google Play sin volver a bajarla.
        val sha = entry.sha256
        if (archiveVc >= 0L && archivePkg == pkg && !sha.isNullOrBlank()) {
            prefs.edit()
                .putString(KEY_VC_CACHE, UpdateRoutePlanner.vcCachePut(prefs.getString(KEY_VC_CACHE, null), sha, archiveVc))
                .apply()
        }

        val installedVc = UpdateFlowManager.versionCodeOf(ctx, pkg)
        val veredicto = UpdateRoutePlanner.judgeHostedApk(
            pkg, archivePkg, archiveVc, installedVc, minSdk, Build.VERSION.SDK_INT, abis, deviceAbis, firma
        )
        return when (veredicto) {
            HostedVerdict.Install -> {
                val firmaTxt = if (firma == Firma.COINCIDE) "misma firma que la instalada"
                else "la firma no se pudo comparar (la verifica Android al instalar)"
                val abiTxt = if (abis.isEmpty()) "sin código nativo" else "arquitectura compatible (${abis.sorted().joinToString(", ")})"
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.OK,
                    "La copia sirve: versión $archiveVn (código $archiveVc) contra código $installedVc instalado; $firmaTxt; $abiTxt."
                )
                UpdateFlowManager.setStage(ctx, UpdateRoutePlanner.STAGE_HOSTED_INSTALLING)
                UpdateFlowManager.notifyUser(ctx, "Actualizando $label", "Instalando en silencio…", -1, ongoing = true)
                Juicio(null, false)
            }
            is HostedVerdict.Reject -> {
                val porVersion = archivePkg == pkg && archiveVc in 0L..installedVc
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.WARN,
                    "La copia de la Tienda (versión $archiveVn, código $archiveVc) no se instala: ${veredicto.reason}."
                )
                Juicio(veredicto, porVersion)
            }
        }
    }

    /**
     * Después del commit: la confirmación llega por PackageInstallStatusReceiver o por
     * PackageReceiver (PACKAGE_REPLACED). Esto es la tercera vía, la que no depende de
     * ningún aviso: si el versionCode cambió, se instaló. Hay fabricantes que se comen
     * alguno de los dos avisos, y esa era una de las formas de quedar "Instalando…" hasta
     * el watchdog.
     */
    private suspend fun watchInstall(ctx: Context, pkg: String, token: Long) {
        val t0 = SystemClock.elapsedRealtime()
        var avisoLento = false
        while (SystemClock.elapsedRealtime() - t0 < INSTALL_WATCH_MAX_MS) {
            delay(INSTALL_POLL_MS)
            if (!UpdateFlowManager.isSameFlow(ctx, pkg, token)) return
            if (UpdateFlowManager.currentRoute(ctx) != UpdateFlowManager.ROUTE_HOSTED) return
            if (UpdateFlowManager.targetAlreadyUpdated(ctx)) {
                val vc = UpdateFlowManager.versionCodeOf(ctx, pkg)
                UpdateFlowManager.step(ctx, UpdateStepLog.OK, "La versión instalada cambió (ahora código $vc).")
                UpdateFlowManager.finish(ctx, UpdateRoutePlanner.RESULT_UPDATED, null)
                return
            }
            if (!avisoLento && SystemClock.elapsedRealtime() - t0 > INSTALL_SLOW_NOTICE_MS) {
                avisoLento = true
                UpdateFlowManager.step(
                    ctx, UpdateStepLog.INFO,
                    "El sistema sigue instalando: en un equipo lento, una app grande puede tardar un par de minutos."
                )
            }
        }
    }

    private fun conPunto(s: String): String = if (s.trimEnd().endsWith(".")) s.trimEnd() else "${s.trimEnd()}."

    // ─────────────────────────────────────────────────────────────────────────────
    // La consulta a la Tienda
    // ─────────────────────────────────────────────────────────────────────────────

    private class Lookup(val entry: StoreEntry?, val error: String?)

    /**
     * Lee `storeApps` (lectura pública, ver database.rules.json) y se queda con la entrada
     * de este paquete. Se lee el nodo entero —son unas pocas entradas chicas— y no la clave
     * `<paquete con _>` sola, porque las entradas viejas pueden tener otra clave.
     *
     * `addListenerForSingleValueEvent` sin conexión no contesta nunca: el tope de 10 s es
     * lo que evita que el flujo quede "Buscando la mejor forma de actualizar…" para siempre.
     */
    private fun lookupStoreEntry(pkg: String): Lookup {
        val latch = CountDownLatch(1)
        val out = AtomicReference<Lookup>()
        val ref = try {
            FirebaseDatabase.getInstance().getReference("storeApps")
        } catch (e: Exception) {
            return Lookup(null, e.message ?: "Firebase no disponible")
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                out.set(
                    try {
                        Lookup(pickEntry(snapshot, pkg), null)
                    } catch (e: Exception) {
                        Lookup(null, "respuesta ilegible (${e.message})")
                    }
                )
                latch.countDown()
            }

            override fun onCancelled(error: DatabaseError) {
                out.set(Lookup(null, error.message))
                latch.countDown()
            }
        }
        try {
            ref.addListenerForSingleValueEvent(listener)
        } catch (e: Exception) {
            return Lookup(null, e.message ?: "error de Firebase")
        }
        val llego = try {
            latch.await(STORE_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!llego) {
            try { ref.removeEventListener(listener) } catch (_: Exception) { }
            return Lookup(null, "no contestó en ${STORE_LOOKUP_TIMEOUT_MS / 1000} s (¿sin conexión?)")
        }
        return out.get() ?: Lookup(null, "respuesta vacía")
    }

    private fun DataSnapshot.texto(campo: String): String? = try {
        child(campo).getValue(String::class.java)
    } catch (e: Exception) {
        child(campo).value?.toString()
    }

    private fun pickEntry(snapshot: DataSnapshot, pkg: String): StoreEntry? {
        val claveEsperada = pkg.replace('.', '_')
        val candidatas = ArrayList<Pair<String?, StoreEntry>>()
        for (c in snapshot.children) {
            val p = c.texto("packageName")?.trim() ?: continue
            if (p != pkg) continue
            candidatas.add(
                c.key to StoreEntry(
                    packageName = p,
                    apkUrl = c.texto("apkUrl")?.trim().orEmpty(),
                    sha256 = c.texto("sha256")?.trim(),
                    label = c.texto("label")
                )
            )
        }
        if (candidatas.isEmpty()) return null
        fun usable(e: StoreEntry) = UpdateRoutePlanner.hostedProblem(e, pkg) == null
        return candidatas.firstOrNull { it.first == claveEsperada && usable(it.second) }?.second
            ?: candidatas.firstOrNull { usable(it.second) }?.second
            ?: candidatas.first().second
    }
}
