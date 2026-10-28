package com.ejemplo.locksuite.mdm

/**
 * Decisiones PURAS del flujo "actualizar una app" (26/10/2026, B.97).
 *
 * Sin `Context`, sin Android, sin preferencias: todo lo que decide POR QUÉ CAMINO se
 * actualiza una app, si la copia de la Tienda sirve para ESTE equipo y qué explicación
 * se le da al administrador vive acá, para poder probarlo en la JVM
 * (`UpdateRoutePlannerTest`). Mismo reparto que `PolicyReconciler`, `AppRequestManager` o
 * `MercadoPagoOffersPolicy`: la parte Android arma los datos, esta parte decide.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ HAY DOS CAMINOS, DICHO SIN ADORNOS
 * ─────────────────────────────────────────────────────────────────────────────
 * Android **no tiene** una API para que una app de MDM le diga a Google Play "actualizá
 * la app X ahora". El único camino oficial es Managed Google Play (Android Enterprise),
 * que exige inscribir la flota en un EMM registrado con Google: es un cambio de
 * plataforma, no un parche. Así que actualizar "desde Play Store" es, sí o sí, manejar la
 * pantalla de Play Store con la Accesibilidad — y eso depende del idioma, de la versión
 * de Play Store, de la velocidad del equipo, del tamaño de la pantalla y de que el
 * celular no esté bloqueado. Se puede hacer muy robusto (eso hace el resto del flujo),
 * pero no infalible.
 *
 * La única forma de actualizar que funciona SIEMPRE, en cualquier celular, es la que no
 * toca la pantalla de nadie: bajar el APK y dárselo a `PackageInstaller` como Device
 * Owner. Para las apps que el administrador tiene cargadas en la Tienda de LockSuite
 * (`storeApps`, con huella sha256 desde B.58) eso ya existe. Por eso:
 *
 *   · AUTOMÁTICO (lo de siempre desde el panel):
 *       - Si a la app la instaló GOOGLE PLAY (o vino de fábrica), va por Google Play: es
 *         lo que pidió el dueño ("que actualice desde Play Store") y Google Play tiene
 *         siempre la última versión. Solo si Google Play no se puede usar en el equipo
 *         (sin Accesibilidad, Play Store deshabilitada) se recurre a la Tienda.
 *       - Si NO la instaló Google Play (vino de la Tienda de LockSuite, de un APK o de
 *         ADB) y está en la Tienda con huella, se usa la copia de la Tienda, en
 *         silencio: Google Play solo puede reemplazar una app firmada como la suya, y una
 *         que vino de otro lado puede no estarlo (B.66: el Waze de la Tienda lo firma un
 *         tercero). Si la copia resulta no servir para este equipo (no más nueva, otra
 *         firma, otra arquitectura, Android muy viejo), se sigue por Google Play SIN
 *         haber abierto nada: la decisión se toma con el APK ya bajado y antes de
 *         levantar ninguna restricción.
 *   · GOOGLE PLAY: solo Google Play.
 *   · TIENDA: solo la copia de la Tienda (para cuando Google Play falla con una app).
 *
 * El panel tiene el selector de las tres en la ficha (sección Actualizaciones).
 *
 * ⚠️ La copia de la Tienda NUNCA se instala si no es estrictamente más nueva: instalar la
 * misma versión no actualiza nada, y una más vieja Android la rechaza (o, peor, en un
 * equipo con `adb` mal configurado la aceptaría como downgrade). Tampoco si la firma no
 * coincide con la de la app instalada: Android la rechaza igual al final, pero acá se
 * dice el motivo ANTES, en vez de un "falló la instalación" mudo.
 */
object UpdateRoutePlanner {

    // ── Preferencia de ruta (campo `route` del comando UPDATE_APP) ──────────────────
    const val PREF_AUTO = "auto"
    const val PREF_PLAY = "play"
    const val PREF_HOSTED = "hosted"

    // ── Ruta efectiva (lo que se publica en `updateFlow.route`) ─────────────────────
    const val ROUTE_PLAY_STORE = "PLAY_STORE"
    const val ROUTE_HOSTED = "HOSTED"
    /** Todavía no se decidió (se está consultando la Tienda). */
    const val ROUTE_UNDECIDED = ""

    // ── Resultados. Son LOS MISMOS textos que UpdateFlowManager.RESULT_* (que ahora los
    //    toma de acá) y que el panel traduce en `updateResultText()`: no inventar uno
    //    nuevo sin agregarlo también allá, o el panel muestra "Actualización finalizada"
    //    a secas. ──────────────────────────────────────────────────────────────────
    const val RESULT_UPDATED = "UPDATED"
    const val RESULT_UP_TO_DATE = "UP_TO_DATE"
    const val RESULT_CANCELLED = "CANCELLED"
    const val RESULT_TIMEOUT = "TIMEOUT"
    const val RESULT_ERROR = "ERROR"
    const val RESULT_NO_SPACE = "NO_SPACE"
    const val RESULT_NEEDS_ACCOUNT = "NEEDS_ACCOUNT"
    const val RESULT_NOT_AVAILABLE = "NOT_AVAILABLE"
    const val RESULT_STORE_ERROR = "STORE_ERROR"

    // ── Etapas. UpdateFlowManager.STAGE_* las toma de acá. ──────────────────────────
    const val STAGE_IDLE = "IDLE"
    const val STAGE_PREPARING = "PREPARING"
    /** Consultando la Tienda de LockSuite para elegir el camino. */
    const val STAGE_CHOOSING_ROUTE = "CHOOSING_ROUTE"
    const val STAGE_OPENING_STORE = "OPENING_STORE"
    const val STAGE_WAITING_STORE = "WAITING_STORE"
    /**
     * El celular está BLOQUEADO (pantalla de bloqueo). Nuevo el 26/10/2026: antes el
     * flujo tapaba la pantalla de bloqueo con el overlay negro —que es una ventana de
     * accesibilidad y queda POR ENCIMA del keyguard y de la barra de navegación— y el
     * usuario no podía desbloquear; Google Play, abierta detrás del keyguard, nunca se
     * veía, y a los 75 s el flujo cerraba con "Google Play no se abrió". Ver B.97.
     */
    const val STAGE_WAITING_UNLOCK = "WAITING_UNLOCK"
    const val STAGE_LOOKING_BUTTON = "LOOKING_BUTTON"
    const val STAGE_CONFIRMING = "CONFIRMING"
    /** Google Play tiene la descarga en cola / preparándose, sin sesión de instalación todavía. */
    const val STAGE_QUEUED = "QUEUED"
    const val STAGE_DOWNLOADING = "DOWNLOADING"
    const val STAGE_INSTALLING = "INSTALLING"
    const val STAGE_HOSTED_DOWNLOADING = "HOSTED_DOWNLOADING"
    const val STAGE_HOSTED_VERIFYING = "HOSTED_VERIFYING"
    const val STAGE_HOSTED_INSTALLING = "HOSTED_INSTALLING"
    const val STAGE_FINISHING = "FINISHING"

    fun normalizePref(raw: String?): String = when (raw?.trim()?.lowercase()) {
        PREF_PLAY, "playstore", "play_store", "google_play" -> PREF_PLAY
        PREF_HOSTED, "tienda", "store" -> PREF_HOSTED
        else -> PREF_AUTO
    }

    /** Una entrada de `storeApps`, ya leída de Firebase. */
    data class StoreEntry(
        val packageName: String,
        val apkUrl: String,
        val sha256: String?,
        val label: String?
    )

    private val SHA256_RE = Regex("^[0-9a-fA-F]{64}$")

    /**
     * ¿Por qué esta entrada de la Tienda NO sirve para actualizar `targetPkg`?
     * `null` = sirve (todavía sin bajar el APK: eso lo juzga [judgeHostedApk]).
     */
    fun hostedProblem(entry: StoreEntry?, targetPkg: String): String? = when {
        entry == null -> "la app no está cargada en la Tienda de LockSuite"
        entry.packageName != targetPkg -> "la entrada de la Tienda es de otro paquete (${entry.packageName})"
        !entry.apkUrl.trim().startsWith("https://") -> "la entrada de la Tienda no tiene un enlace https al APK"
        entry.sha256.isNullOrBlank() || !SHA256_RE.matches(entry.sha256.trim()) ->
            "la entrada de la Tienda no tiene huella sha256 (sin huella no se instala nada, ver B.58)"
        else -> null
    }

    /**
     * `why` es el MOTIVO, sin el nombre del camino (que lo pone quien lo muestra: "Camino
     * elegido: Google Play. <why>"). Empieza con mayúscula y termina en punto.
     */
    sealed class Plan {
        /** Bajar la copia de la Tienda e instalarla en silencio. */
        data class Hosted(val entry: StoreEntry, val why: String) : Plan()
        /** Abrir Google Play y manejarla con la Accesibilidad. */
        data class PlayStore(val why: String) : Plan()
        /** No hay forma de actualizar: se cierra el flujo con este resultado y motivo. */
        data class Fail(val result: String, val message: String) : Plan()
    }

    /**
     * El camino inicial.
     *
     * @param installedVc versionCode instalado ahora.
     * @param cachedHostedVc versionCode de la copia de la Tienda SI ya se inspeccionó un APK
     *   con esa misma huella (cache local). Sirve para no bajar 60 MB por datos móviles
     *   solo para descubrir otra vez que la copia es vieja. `null` = no se sabe todavía.
     * @param accessibilityOn ¿el servicio de Accesibilidad de LockSuite está corriendo?
     * @param playStoreProblem `null` si Google Play se puede usar; si no, por qué no.
     * @param playManaged ¿a la app la instaló Google Play (o vino de fábrica)? Decide, en
     *   automático, cuál camino va primero. Ver la cabecera.
     */
    fun plan(
        pref: String,
        targetPkg: String,
        entry: StoreEntry?,
        installedVc: Long,
        cachedHostedVc: Long?,
        accessibilityOn: Boolean,
        playStoreProblem: String?,
        playManaged: Boolean = true
    ): Plan {
        val p = normalizePref(pref)
        val problem = hostedProblem(entry, targetPkg)
        val playWhyNot: String? = when {
            playStoreProblem != null -> playStoreProblem
            !accessibilityOn -> "el servicio de Accesibilidad de LockSuite no está activo, y sin él " +
                "abrir Google Play dejaría la tienda abierta sin protección"
            else -> null
        }

        when (p) {
            PREF_HOSTED -> {
                return if (problem == null) {
                    Plan.Hosted(entry!!, "Elegido por el administrador.")
                } else {
                    Plan.Fail(RESULT_NOT_AVAILABLE, "Se pidió actualizar desde la Tienda de LockSuite, pero $problem.")
                }
            }
            PREF_PLAY -> {
                return if (playWhyNot == null) {
                    Plan.PlayStore("Elegido por el administrador.")
                } else {
                    Plan.Fail(RESULT_ERROR, "No se puede actualizar por Google Play: $playWhyNot.")
                }
            }
        }

        // ── AUTOMÁTICO ──
        if (problem == null) {
            val vcTienda = cachedHostedVc
            val copiaSirve = vcTienda == null || vcTienda > installedVc
            if (copiaSirve && (!playManaged || playWhyNot != null)) {
                val detalle = if (vcTienda != null) " (versión $vcTienda contra $installedVc instalada)" else ""
                val porQue = if (!playManaged) {
                    "A esta app no la instaló Google Play, y Google Play solo puede reemplazar una app firmada como la suya"
                } else {
                    "Google Play no se puede usar en este equipo ($playWhyNot)"
                }
                return Plan.Hosted(entry!!, "$porQue$detalle.")
            }
            if (playWhyNot == null) {
                return Plan.PlayStore(
                    if (!copiaSirve) {
                        // La copia de la Tienda ya se miró y no es más nueva: no se la vuelve a bajar.
                        "La copia de la Tienda (versión $vcTienda) no es más nueva que la instalada ($installedVc)."
                    } else {
                        "La instaló Google Play, que tiene siempre la última versión. (La app también está en la " +
                            "Tienda de LockSuite: si Google Play falla, se puede reintentar eligiendo la Tienda.)"
                    }
                )
            }
            val porQue = "la copia de la Tienda (versión $vcTienda) no es más nueva que la instalada ($installedVc)"
            return Plan.Fail(RESULT_ERROR, "No hay forma de actualizar: $porQue, y además $playWhyNot.")
        }
        return if (playWhyNot == null) {
            Plan.PlayStore(
                if (entry == null) "La app no está en la Tienda de LockSuite."
                else "En la Tienda de LockSuite no sirve: ${problem}."
            )
        } else {
            Plan.Fail(
                RESULT_ERROR,
                "No hay forma segura de actualizar esta app: $playWhyNot; y por la Tienda tampoco: $problem."
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ¿La copia de la Tienda YA BAJADA sirve para este equipo?
    // ─────────────────────────────────────────────────────────────────────────────

    /** Resultado de comparar la firma del APK bajado contra la de la app instalada. */
    enum class Firma { COINCIDE, DISTINTA, DESCONOCIDA, NO_INSTALADA }

    sealed class HostedVerdict {
        object Install : HostedVerdict()
        /**
         * No se instala. `fallbackToPlay` = tiene sentido probar por Google Play (casi
         * siempre: el problema es de ESA copia, no de la app).
         */
        data class Reject(val reason: String, val fallbackToPlay: Boolean) : HostedVerdict()
    }

    /**
     * El juicio sobre el APK de la Tienda, con el archivo ya bajado y su sha256 ya
     * verificado (eso lo hace `ApkChecksum`, antes que esto). El orden de las preguntas va
     * de lo más barato y concluyente a lo menos.
     *
     * @param archivePkg paquete que declara el APK (`null` = no se pudo leer el archivo).
     * @param apkAbis carpetas `lib/<abi>/` del APK (vacío = no trae código nativo).
     * @param deviceAbis `Build.SUPPORTED_ABIS` del equipo.
     */
    fun judgeHostedApk(
        targetPkg: String,
        archivePkg: String?,
        archiveVc: Long,
        installedVc: Long,
        archiveMinSdk: Int,
        deviceSdk: Int,
        apkAbis: Set<String>,
        deviceAbis: List<String>,
        firma: Firma
    ): HostedVerdict {
        if (archivePkg.isNullOrBlank()) {
            return HostedVerdict.Reject("no se pudo leer el APK de la Tienda (archivo dañado o incompleto)", true)
        }
        if (archivePkg != targetPkg) {
            return HostedVerdict.Reject("el APK de la Tienda es de otra app ($archivePkg), no de $targetPkg", true)
        }
        if (archiveVc <= installedVc) {
            val cual = if (archiveVc == installedVc) "es la misma versión que la instalada ($installedVc)"
            else "es más vieja (versión $archiveVc) que la instalada ($installedVc)"
            return HostedVerdict.Reject("la copia de la Tienda $cual", true)
        }
        if (archiveMinSdk > deviceSdk) {
            return HostedVerdict.Reject(
                "la copia de la Tienda necesita Android con API $archiveMinSdk y este equipo tiene API $deviceSdk", true
            )
        }
        if (!abiCompatible(apkAbis, deviceAbis)) {
            return HostedVerdict.Reject(
                "la copia de la Tienda trae código solo para ${apkAbis.sorted().joinToString(", ")} y este " +
                    "equipo es ${deviceAbis.joinToString(", ").ifBlank { "desconocido" }}", true
            )
        }
        return when (firma) {
            Firma.COINCIDE -> HostedVerdict.Install
            Firma.DISTINTA -> HostedVerdict.Reject(
                "la copia de la Tienda está firmada por otro desarrollador que la app instalada: Android no " +
                    "deja reemplazar una con la otra", true
            )
            // No se pudo leer alguna de las dos firmas (hay versiones de Android que no
            // devuelven la del archivo). No es motivo para no intentar: la huella sha256 ya
            // garantizó que el archivo es el que publicó el administrador, y Android mismo
            // NUNCA deja reemplazar una app por otra firmada distinto. Si no coincide, la
            // instalación falla con su motivo y, en automático, se sigue por Google Play.
            // Rechazar acá dejaba la ruta silenciosa inservible justo en esos equipos.
            Firma.DESCONOCIDA -> HostedVerdict.Install
            Firma.NO_INSTALADA -> HostedVerdict.Reject("la app ya no está instalada en el equipo", false)
        }
    }

    /**
     * ¿El código nativo del APK corre en este equipo? Un APK sin `lib/` corre en
     * cualquiera. B.66 midió que en la Tienda hay APKs solo `arm64-v8a` (Traductor, Waze)
     * y solo `armeabi-v7a` (Gboard): en el equipo equivocado Android los rechaza con
     * INSTALL_FAILED_NO_MATCHING_ABIS, y antes eso era un error sin explicación.
     */
    fun abiCompatible(apkAbis: Set<String>, deviceAbis: List<String>): Boolean {
        if (apkAbis.isEmpty()) return true
        if (deviceAbis.isEmpty()) return true   // no se sabe: que decida Android
        return apkAbis.any { it in deviceAbis }
    }

    /** Las ABI de un APK a partir de los nombres de sus entradas zip (`lib/<abi>/x.so`). */
    fun abisFromZipEntries(names: Iterable<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (n in names) {
            if (!n.startsWith("lib/")) continue
            val resto = n.substring(4)
            val barra = resto.indexOf('/')
            if (barra <= 0) continue
            // Solo cuenta si adentro hay una biblioteca de verdad.
            if (!resto.endsWith(".so")) continue
            out.add(resto.substring(0, barra))
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Fallas de la instalación silenciosa (PackageInstaller.EXTRA_STATUS)
    // ─────────────────────────────────────────────────────────────────────────────

    // Los valores de PackageInstaller.STATUS_*, escritos acá para no importar Android.
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7

    data class InstallFailure(val result: String, val reason: String, val fallbackToPlay: Boolean)

    /**
     * Qué hacer cuando el sistema rechazó la instalación de la copia de la Tienda.
     * Sin espacio no tiene sentido ir a Google Play (le va a faltar lo mismo); en el
     * resto, el problema es de esa copia y Google Play todavía puede servir.
     */
    fun hostedInstallFailure(status: Int, message: String?): InstallFailure {
        val msg = message?.trim().orEmpty()
        val sinEspacio = status == STATUS_FAILURE_STORAGE || msg.contains("INSUFFICIENT_STORAGE", ignoreCase = true)
        if (sinEspacio) {
            return InstallFailure(RESULT_NO_SPACE, "el sistema no tiene espacio para instalar la copia de la Tienda" +
                (if (msg.isNotEmpty()) " ($msg)" else ""), false)
        }
        val porQue = when (status) {
            STATUS_FAILURE_INCOMPATIBLE -> "la copia de la Tienda no es compatible con este equipo"
            STATUS_FAILURE_CONFLICT -> "la copia de la Tienda choca con la app instalada (firma u otra versión)"
            STATUS_FAILURE_INVALID -> "el sistema dice que la copia de la Tienda no es un APK válido"
            STATUS_FAILURE_BLOCKED -> "una política del equipo bloqueó la instalación"
            STATUS_FAILURE_ABORTED -> "la instalación se abortó"
            STATUS_PENDING_USER_ACTION -> "el sistema pidió confirmación a mano (no debería pasar siendo Device Owner)"
            else -> "el sistema rechazó la instalación"
        }
        return InstallFailure(RESULT_ERROR, porQue + (if (msg.isNotEmpty()) " ($msg)" else ""), true)
    }

    /** ¿El error de una descarga/instalación es falta de espacio? (Ir a Google Play no lo arregla.) */
    fun isNoSpaceMessage(msg: String?): Boolean {
        if (msg.isNullOrBlank()) return false
        val m = msg.lowercase()
        return m.contains("enospc") || m.contains("no space left") || m.contains("insufficient_storage") ||
            m.contains("insufficient storage")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Cache de "qué versión trae la copia de la Tienda con tal huella"
    //
    // Para no bajar 60 MB por datos móviles cada vez que se pide actualizar una app cuya
    // copia de la Tienda ya se sabe que es vieja. Se indexa por los primeros 16
    // caracteres de la huella: si el administrador reemplaza el APK, la huella cambia y
    // la entrada vieja deja de aplicar sola. Formato `huella16=versionCode;…`, con tope.
    // ─────────────────────────────────────────────────────────────────────────────

    const val VC_CACHE_MAX = 30

    private fun vcKey(sha: String) = sha.trim().lowercase().take(16)

    fun vcCacheGet(raw: String?, sha: String?): Long? {
        if (raw.isNullOrBlank() || sha.isNullOrBlank()) return null
        val k = vcKey(sha)
        for (part in raw.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            if (part.substring(0, i) == k) return part.substring(i + 1).toLongOrNull()
        }
        return null
    }

    fun vcCachePut(raw: String?, sha: String, vc: Long, max: Int = VC_CACHE_MAX): String {
        val k = vcKey(sha)
        val items = (raw ?: "").split(';').filter { it.isNotBlank() && !it.startsWith("$k=") }.toMutableList()
        if (vc >= 0L) items.add("$k=$vc")
        while (items.size > max) items.removeAt(0)
        return items.joinToString(";")
    }

    /**
     * El motivo de un cierre por TIEMPO según la etapa en que quedó el flujo. Antes el
     * watchdog cerraba siempre con "La actualización tardó demasiado", que no dice dónde.
     */
    fun timeoutReasonForStage(stage: String?): String = when (stage) {
        STAGE_WAITING_UNLOCK -> "El celular estuvo bloqueado y nadie lo desbloqueó a tiempo para que Google Play pudiera abrirse."
        STAGE_CHOOSING_ROUTE -> "No se pudo consultar la Tienda de LockSuite a tiempo (¿sin red?)."
        STAGE_PREPARING, STAGE_OPENING_STORE, STAGE_WAITING_STORE -> "Google Play nunca mostró la ficha de la app."
        STAGE_LOOKING_BUTTON, STAGE_CONFIRMING -> "No se pudo encontrar o apretar el botón de actualizar a tiempo."
        STAGE_QUEUED -> "Google Play dejó la descarga en espera y nunca empezó (otra descarga en curso, sin Wi-Fi o pausada)."
        STAGE_DOWNLOADING -> "La descarga desde Google Play no terminó a tiempo."
        STAGE_INSTALLING -> "La instalación no terminó a tiempo."
        STAGE_HOSTED_DOWNLOADING -> "La descarga desde la Tienda de LockSuite no terminó a tiempo."
        STAGE_HOSTED_VERIFYING -> "La verificación de la copia de la Tienda no terminó a tiempo."
        STAGE_HOSTED_INSTALLING -> "El sistema no confirmó la instalación de la copia de la Tienda."
        else -> "La actualización tardó demasiado y se canceló."
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Un flujo que quedó HUÉRFANO porque el proceso de LockSuite se reinició
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * ¿Hay que cerrar, al arrancar el proceso, el flujo que quedó abierto? `null` = no.
     *
     * Elegir el camino, bajar la copia de la Tienda y verificarla corre en una corrutina
     * de ESTE proceso (HostedAppUpdater). Si el proceso muere a mitad —el sistema lo mata
     * por memoria, un cierre inesperado, LockSuite que se actualiza—, ese trabajo muere
     * con él y nadie lo termina: el flujo quedaba "en curso" hasta el watchdog de 10
     * minutos, el panel mostraba una descarga que ya no existía y un pedido nuevo se
     * rechazaba con "ya hay una actualización en curso". El mismo silencio que B.54
     * arregló para Google Play.
     *
     * NO se tocan:
     *  · la ruta de Google Play: la retoma la Accesibilidad al reconectarse (B.54);
     *  · la instalación silenciosa ya entregada al sistema (HOSTED_INSTALLING): Android
     *    la termina aunque LockSuite se reinicie, y la confirman PackageInstallStatusReceiver
     *    o PackageReceiver (con el watchdog de respaldo).
     *
     * @param route la ruta guardada; ausente se lee como Google Play (UpdateFlowManager.currentRoute).
     * @return el motivo, listo para el registro y el panel.
     */
    fun orphanReasonAfterRestart(route: String?, stage: String?): String? {
        if (route == ROUTE_PLAY_STORE) return null
        if (stage == STAGE_HOSTED_INSTALLING) return null
        val donde = when {
            route != ROUTE_HOSTED -> "mientras elegía por dónde actualizar (consultando la Tienda de LockSuite)"
            stage == STAGE_HOSTED_DOWNLOADING -> "mientras bajaba la copia de la Tienda"
            stage == STAGE_HOSTED_VERIFYING -> "mientras verificaba la copia de la Tienda"
            else -> "a mitad de la actualización desde la Tienda"
        }
        return "LockSuite se reinició $donde, y ese trabajo se perdió con el reinicio"
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ¿La descarga de Google Play se mueve aunque su barra no diga cuánto?
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Huella de los NÚMEROS que hay en la pantalla de Google Play.
     *
     * Mientras baja, Play Store muestra "45 %" o "12 MB de 30 MB" y eso cambia; lo fijo de
     * la ficha (la calificación, "10 M+", "3+") no. Si la huella cambia, la descarga se
     * movió. Hace falta para las versiones de Play Store que dibujan la barra SIN valor
     * para la Accesibilidad (indeterminada) pero con el texto avanzando: sin esto, a los
     * 4 minutos (QUEUED_STALL_MS) se la daba por "en cola" y se cortaba una descarga que
     * andaba. Si algo de la pantalla cambia por otro motivo, el efecto es esperar más
     * —hasta el watchdog, como antes de B.97—: el lado seguro.
     *
     * @param texts los textos cortos de la pantalla, en el orden del árbol.
     */
    fun progressSignature(texts: List<String>): String {
        val sb = StringBuilder()
        for (t in texts) {
            if (t.length > 40 || t.none { it.isDigit() }) continue
            if (sb.isNotEmpty()) sb.append('|')
            sb.append(t)
            if (sb.length > 400) break
        }
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Google Play no aparece (28/10/2026, B.99)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Cuánto esperar antes de volver a abrir Google Play, según cuántas veces SEGUIDAS se
     * la abrió sin que llegara a verse.
     *
     * Antes era siempre 1,5 s. Medido el 27/10 en el QEMAY-QM01: cuando algo mata a Play
     * Store apenas arranca, abrirla cada 1,5 s es un bucle de cierres ("Google Play Store
     * dejó de funcionar") que no arregla nada y tapa la causa. La primera vuelta sigue
     * siendo rápida —es el caso normal: el usuario tocó Inicio y hay que traerla de
     * vuelta—; las siguientes, sin haberla visto ni una vez, esperan cada vez más.
     */
    fun storeRelaunchGapMs(seguidasSinVerla: Int): Long = when {
        seguidasSinVerla <= 0 -> 1_500L
        seguidasSinVerla == 1 -> 3_000L
        seguidasSinVerla == 2 -> 5_000L
        seguidasSinVerla == 3 -> 8_000L
        else -> 12_000L
    }

    /** Cuántas veces se tolera que algo vuelva a bloquear Google Play antes de cerrar diciéndolo. */
    const val MAX_STORE_REBLOCKS = 3

    /**
     * Qué tiene a Google Play fuera de la pantalla, en palabras, a partir de lo que se puede
     * MEDIR (no de lo que se ve). `null` = nada medible: Play Store está habilitada y la
     * causa es otra (no terminó de abrir, el usuario se fue, otra app adelante).
     *
     * @param hidden `DevicePolicyManager.isApplicationHidden`: oculta = para Android "no
     *   está instalada"; al abrirla se cierra sola ("is package not installed?").
     * @param suspended `isPackageSuspended`: en vez de la ficha aparece "app en pausa".
     * @param lockTaskBlocks el modo kiosco (Lock Task) está activo y Play Store no está en
     *   su lista: Android no la deja pasar al frente.
     */
    fun playStoreBlockReason(hidden: Boolean, suspended: Boolean, lockTaskBlocks: Boolean): String? {
        val partes = ArrayList<String>(3)
        if (hidden) partes.add("está oculta")
        if (suspended) partes.add("está suspendida")
        if (lockTaskBlocks) partes.add("el modo kiosco no la deja abrir")
        return if (partes.isEmpty()) null else partes.joinToString(" y ")
    }
}
