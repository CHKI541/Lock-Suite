package com.ejemplo.locksuite.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * STUBS DE ANDROID AUTO  (25/9/2026 — B.88)
 *
 * QUÉ SON. Al conectar, Android Auto muestra "Descargá apps de Google Play" y NO deja
 * seguir ("Salir" cierra Android Auto) si no están instaladas Google Maps y la App de
 * Google. En un equipo kosher esas dos no están: la App de Google no existe, y Maps
 * suele estar oculta. Un "stub" es una app VACÍA con el mismo nombre de paquete
 * (`tools/aa_stubs/`: sin código, sin activities ni services, versionCode 2100000000).
 * Android Auto ve el paquete y sigue; el stub no hace nada, no tiene ícono y no
 * aparece en la pantalla del auto. La navegación en el auto la hace Waze, que tiene
 * que ser el OFICIAL: Android Auto verifica la firma de Waze y un Waze re-firmado no
 * aparece nunca (B.88).
 *
 * POR QUÉ LOCKSUITE TIENE QUE CONOCERLOS. Sin esto, LockSuite los rompe solo:
 *  · el stub de Google se llama `com.google.android.googlequicksearchbox`, que está
 *    en `DEFAULT_BLOCKED_PACKAGES` (se suspende) y en `PopularNonKosherApps` (se oculta);
 *  · el de Maps hereda la marca `hide_com.google.android.apps.maps` de la Maps real que
 *    reemplaza, y `PackageReceiver` lo oculta apenas se instala;
 *  · con la instalación bloqueada, `PackageReceiver` lo desinstala por "no autorizado".
 * Y un paquete OCULTO es, para Android Auto, un paquete no instalado: vuelve la pantalla
 * de "Descargar". Por eso `AppController.isCritical()` los cuenta como críticos —todos
 * esos caminos lo consultan antes de ocultar, suspender o desinstalar— y
 * `asegurarVisibles()` deshace lo que se les haya colado antes (por ejemplo, si el stub
 * se instaló con una versión de LockSuite que todavía no lo reconocía).
 *
 * SE LOS RECONOCE POR LA FIRMA, NUNCA POR EL NOMBRE. El nombre lo puede copiar
 * cualquiera; la firma no. La clave privada con la que se firmaron se BORRÓ al firmar
 * (ver `tools/aa_stubs/build_stubs.sh`): no existe forma de producir otra app con este
 * certificado, ni siquiera para nosotros. La Maps real (firmada por Google) y
 * cualquier imitación siguen tratándose exactamente igual que antes. Si algún día hace
 * falta otro stub, se arma con otra clave y se AGREGA su huella a `CERT_SHA256`.
 *
 * COSTO. `isStub()` se llama desde `isCritical()`, que corre en bucles sobre TODAS las
 * apps del equipo (la suspensión de emergencia lo hace cada 5 s). Para cualquier
 * paquete que no sea uno de estos dos, la respuesta es una búsqueda en un Set de dos
 * elementos. Solo para estos dos se lee la firma, y el resultado se cachea por
 * `lastUpdateTime`, que cambia si el paquete se reinstala o se actualiza.
 * ══════════════════════════════════════════════════════════════════════════════
 */
object AndroidAutoStubs {

    const val PKG_MAPS = "com.google.android.apps.maps"
    const val PKG_GOOGLE_APP = "com.google.android.googlequicksearchbox"

    val PACKAGES: Set<String> = setOf(PKG_MAPS, PKG_GOOGLE_APP)

    /**
     * SHA-256 del certificado de firma de los stubs: hex en minúsculas, sin separadores,
     * el mismo formato que imprime `apksigner verify --print-certs`. Sale de
     * `tools/aa_stubs/build_stubs.sh` (25/9/2026, `CN=LockSuite Android Auto stub`).
     */
    val CERT_SHA256: Set<String> = setOf(
        "62786d1c3bfaa36cac1d71ec943227c2ace1f622c805c9affc44e39f81bf52de"
    )

    /**
     * SHA-256 de los ARCHIVOS de los stubs (`tools/aa_stubs/apk/`), en el formato en que
     * los guarda el panel en `storeApps/<app>/sha256` (hex en minúsculas).
     *
     * Sirve para que la Tienda los deje instalar a CUALQUIER equipo sin pedirlos (son
     * vacíos: no hay nada que aprobar). Se los reconoce por la huella exacta del archivo
     * y no por el nombre: `downloadAndInstallApk` ya compara el archivo bajado contra
     * esa misma huella y falla cerrado, así que lo que se instala es byte a byte el stub.
     * Una entrada de la Tienda que trajera la Maps o la App de Google REALES bajo ese
     * nombre tendría otra huella, y seguiría necesitando permiso como cualquier app.
     *
     * ⚠️ A propósito NO se agregan estos nombres a `allowedPackages`: esa lista es por
     * NOMBRE y también hace que `PackageReceiver` deje de desinstalar la app, así que
     * permitiría la Maps real si alguien la instalara con la instalación bloqueada.
     */
    val APK_SHA256: Map<String, String> = mapOf(
        PKG_MAPS to "b0d1fc188e88e5a27b366f1d7160488d1b0ebe393ad78e3c69fda96c6692cbd3",
        PKG_GOOGLE_APP to "d81901b6c544df6a2e16cc3e8c96cd83677225c5073eefb64ff2331666bb6152"
    )

    /** ¿Esta entrada de la Tienda es uno de nuestros stubs? (paquete + huella exacta) */
    fun esEntradaDeStub(packageName: String, sha256: String?): Boolean {
        val esperado = APK_SHA256[packageName] ?: return false
        return sha256?.trim()?.lowercase() == esperado
    }

    /**
     * Por qué la Tienda no puede instalar un stub en este equipo. En los dos casos ya existe
     * un paquete con ese nombre firmado por Google, y Android rechaza el stub por firma con
     * un error que no dice nada útil: la Tienda lo explica en vez de intentar y fallar.
     */
    enum class Impedimento {
        /** La app real está instalada como app común (aunque esté oculta): desinstalándola, se puede. */
        APP_REAL_INSTALADA,

        /**
         * La app real viene de fábrica (está en el sistema). En este equipo el stub no se va a
         * poder instalar NUNCA: aunque se desinstalen sus actualizaciones, o se "desinstale"
         * para el usuario, la copia del sistema sigue ahí y su firma manda.
         */
        APP_REAL_DE_FABRICA
    }

    /** null si la Tienda puede instalar el stub `packageName` en este equipo; si no, por qué. */
    fun impedimentoParaInstalar(context: Context, packageName: String): Impedimento? {
        if (packageName !in PACKAGES) return null
        val flags = flagsDelPaquete(context, packageName) ?: return null
        return impedimento(flags, esNuestro = isStub(context, packageName))
    }

    /**
     * Regla pura, separada para poder probarla sin Android. `flags` son los de
     * `ApplicationInfo` del paquete que ya existe con ese nombre (incluido uno de sistema
     * "desinstalado" para el usuario, que sigue bloqueando el nombre); `esNuestro`, si ese
     * paquete es uno de nuestros stubs (entonces no hay nada que impida reinstalarlo).
     */
    fun impedimento(flags: Int, esNuestro: Boolean): Impedimento? = when {
        esNuestro -> null
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 -> Impedimento.APP_REAL_DE_FABRICA
        (flags and ApplicationInfo.FLAG_INSTALLED) != 0 -> Impedimento.APP_REAL_INSTALADA
        else -> null
    }

    private data class Veredicto(val lastUpdateTime: Long, val esStub: Boolean)

    private val cache = ConcurrentHashMap<String, Veredicto>()

    /** ¿Este paquete instalado es uno de NUESTROS stubs (verificado por firma)? */
    fun isStub(context: Context, packageName: String): Boolean {
        if (packageName !in PACKAGES) return false
        val info = paqueteInstalado(context, packageName)
        if (info == null) {
            cache.remove(packageName)
            return false
        }
        cache[packageName]?.let { if (it.lastUpdateTime == info.lastUpdateTime) return it.esStub }
        val esStub = esCertificadoDeStub(certificadosSha256(info))
        cache[packageName] = Veredicto(info.lastUpdateTime, esStub)
        return esStub
    }

    /**
     * Regla pura, separada para poder probarla sin Android: son nuestros si TODOS los
     * firmantes son nuestros. Una app firmada por nosotros Y por otro no cuenta.
     */
    fun esCertificadoDeStub(certificadosSha256: List<String>): Boolean =
        certificadosSha256.isNotEmpty() && certificadosSha256.all { it.lowercase() in CERT_SHA256 }

    /** Hex en minúsculas, sin separadores: el formato de `apksigner --print-certs`. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Deshace cualquier ocultamiento o suspensión que se le haya colado a un stub. La
     * llama el ciclo de re-aplicación de `PolicyManager`. Barata: son dos paquetes y
     * solo toca el sistema si hace falta. Devuelve cuántas correcciones hizo.
     *
     * No borra las marcas `hide_`/`suspend_` de las preferencias a propósito: expresan
     * lo que el administrador decidió para la app REAL. Si algún día se desinstala el
     * stub y vuelve la Maps de Google, tiene que volver oculta.
     */
    fun asegurarVisibles(context: Context, dpm: DevicePolicyManager, admin: ComponentName): Int {
        var corregidos = 0
        for (pkg in PACKAGES) {
            if (!isStub(context, pkg)) continue
            try {
                if (dpm.isApplicationHidden(admin, pkg) && dpm.setApplicationHidden(admin, pkg, false)) {
                    corregidos++
                }
            } catch (e: Exception) {
                android.util.Log.w("AndroidAutoStubs", "No se pudo des-ocultar $pkg: ${e.message}")
            }
            try {
                if (dpm.isPackageSuspended(admin, pkg)) {
                    val sinAplicar = dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                    if (pkg !in sinAplicar) corregidos++
                }
            } catch (e: Exception) {
                android.util.Log.w("AndroidAutoStubs", "No se pudo des-suspender $pkg: ${e.message}")
            }
        }
        return corregidos
    }

    /**
     * El paquete con sus firmas, INCLUIDO si está oculto (`MATCH_UNINSTALLED_PACKAGES`:
     * sin eso, un stub que alguien ocultó sería invisible acá y nunca se repararía).
     * Pero solo si está instalado de verdad para este usuario: un paquete desinstalado
     * que conservó sus datos también aparece con ese flag, y ese no cuenta.
     */
    @Suppress("DEPRECATION")
    private fun paqueteInstalado(context: Context, packageName: String): PackageInfo? = try {
        val pm = context.packageManager
        val firmas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val flags = firmas or PackageManager.MATCH_UNINSTALLED_PACKAGES
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
        }
        val instalado = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_INSTALLED) != 0
        if (instalado) info else null
    } catch (e: Exception) {
        null
    }

    /**
     * Los flags de `ApplicationInfo` de cualquier paquete que exista con ese nombre, aunque
     * no esté instalado para este usuario (`MATCH_UNINSTALLED_PACKAGES`); null si no existe.
     */
    @Suppress("DEPRECATION")
    private fun flagsDelPaquete(context: Context, packageName: String): Int? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
            )
        } else {
            pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        }
        info.flags
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun certificadosSha256(info: PackageInfo): List<String> {
        val firmas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return firmas?.map { sha256Hex(it.toByteArray()) } ?: emptyList()
    }
}
