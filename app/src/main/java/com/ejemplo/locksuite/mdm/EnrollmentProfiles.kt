package com.ejemplo.locksuite.mdm

import android.os.UserManager
import org.json.JSONObject

/**
 * EnrollmentProfiles — PERFILES MAESTROS DE ALTA (9/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ PROBLEMA RESUELVE, Y DE DÓNDE SALIÓ
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * El dueño comparó LockSuite con MB Smart y con los MDM comerciales, y la diferencia
 * que sintió no es de potencia sino de PUESTA EN MARCHA: allá el instalador enrola el
 * equipo en un perfil pre-armado y el celular queda listo; acá hay que ir interruptor
 * por interruptor —más de sesenta— y acordarse de todos, en cada equipo.
 *
 * Esto es esa mitad: tres perfiles escritos **dentro del APK**, aplicables de un toque,
 * sin panel, sin red y sin cuenta configurada. Es a propósito que estén en el binario y
 * no en la nube: el momento en que más falta hacen es el alta, que es exactamente cuando
 * el equipo todavía no tiene ni cuenta de Google ni Wi-Fi configurado. Un perfil que
 * necesita internet para aplicarse no sirve para dar de alta un equipo.
 *
 * Los perfiles guardados por el administrador (los `.locksuite` y los de `presets/`)
 * siguen existiendo y no cambian: esto NO los reemplaza, les pone un piso.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ ESTE ARCHIVO NO TOCA NADA (Y POR QUÉ ESO IMPORTA)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Acá no hay `Context`, ni `SharedPreferences`, ni `DevicePolicyManager`: solo datos y
 * funciones puras que devuelven un `JSONObject`. Quien aplica es
 * `PolicyManager.applyMasterProfile()`, que arma este objeto, lo firma y se lo pasa a
 * `importPolicyPresetJson()` — **el mismo y único camino de aplicación que ya usaban los
 * presets**.
 *
 * Las dos razones:
 *
 * 1. **Un solo camino de aplicación.** Si este archivo aplicara por su cuenta, habría
 *    dos lugares que saben "cómo se aplica un perfil" y se irían separando solos. Ese
 *    es, literalmente, el modo de falla que este proyecto ya pagó cuatro veces (la clave
 *    `no_apps_control` de B.28, `DISALLOW_CONFIG_DATE_TIME` de B.38, los nombres de
 *    clave del perfil en B.40 punto 8, y `captivePortalCoverImages` de B.57).
 * 2. **Se puede probar afuera del equipo.** Al ser puro, `buildData()` se ejercita en el
 *    contenedor con aserciones reales, que es lo que un type-check no puede dar. Es la
 *    misma decisión que se tomó con `WhitelistManager.buildRules()` en B.53, y es donde
 *    se agarran los errores que importan.
 *
 * ⚠️ **Las claves de abajo tienen que existir en `PolicyManager.importPolicyPresetJson()`.**
 * Una clave que el importador no lee se acepta en silencio y no hace nada: el perfil se
 * aplica "con éxito" y la protección nunca se pone. Para que eso no se pueda volver a
 * escapar, `tools/check_profile_sync.py` compara este archivo contra el importador y
 * falla si alguna clave de acá no se lee allá. **Correrlo después de tocar cualquiera de
 * los dos.**
 */
object EnrollmentProfiles {

    const val LEVEL_STRICT = "kosher_estricto"
    const val LEVEL_WORK = "trabajo"
    const val LEVEL_BASE = "base_minima"

    /**
     * Período de gracia con cierre automático (10/9/2026). Pedido del dueño: bloquear lo
     * claramente no kosher, dejar lo dudoso a criterio del usuario por un tiempo, con la
     * tienda abierta, y que después se cierre solo. Ver `mdm/GracePeriodManager.kt`.
     */
    const val LEVEL_GRACE = "gracia"

    /**
     * @param id            Clave estable. Viaja por FCM y se guarda en preferencias.
     * @param label         Nombre visible en la app y en el panel.
     * @param summary       Una línea: qué deja hacer y qué no.
     * @param warning       Lo que hay que saber ANTES de aplicarlo. Se muestra en la
     *                      confirmación, no escondido en la documentación. `null` si no hay.
     * @param restrictions  Restricciones `DISALLOW_*` (constantes reales de `UserManager`).
     * @param switches      El resto de los interruptores, con las claves EXACTAS que lee
     *                      `PolicyManager.importPolicyPresetJson()`.
     */
    data class MasterProfile(
        val id: String,
        val label: String,
        val summary: String,
        val warning: String?,
        val restrictions: Map<String, Boolean>,
        val switches: Map<String, Boolean>
    ) {
        /**
         * Las restricciones de este perfil que NO se pueden aplicar durante el
         * aprovisionamiento por QR (B.61). Se calcula, no se declara: así una
         * restricción nueva que caiga en [POST_ALTA] queda cubierta sola.
         */
        val postAlta: Set<String> get() = restrictions.keys.intersect(POST_ALTA)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LO QUE NO SE PUEDE APLICAR DURANTE EL ALTA (10/9/2026, B.61)
    // ─────────────────────────────────────────────────────────────────────────
    //
    // El QR de aprovisionamiento aplica el perfil apenas termina de instalarse
    // LockSuite, o sea ANTES de que el instalador haya agregado la cuenta de Google
    // y ANTES de haber dejado el equipo en su idioma definitivo. Estas dos, aplicadas
    // en ese momento, **dejan un equipo que no se puede terminar de dar de alta**:
    //
    //  · `DISALLOW_MODIFY_ACCOUNTS` — no se puede agregar la cuenta de Google. Es el
    //    mismo error de razonamiento que ya costó B.41 punto 3 y B.43: LockSuite se
    //    instala con el equipo SIN cuenta, así que "todavía no hay cuenta" es el
    //    ESTADO DE FÁBRICA del procedimiento, no un caso raro.
    //  · `DISALLOW_CONFIG_LOCALE` — no se puede dejar el equipo en el idioma
    //    definitivo, y el aviso de los perfiles pide justamente hacerlo antes.
    //
    // Las dos se aplican después, con el botón "Terminar alta" del panel (comando
    // `FINISH_ENROLLMENT`). Mientras tanto el equipo YA está protegido por todo el
    // resto del perfil, incluido el piso anti-manipulación entero: el hueco es
    // exactamente "puede agregar cuentas y cambiar el idioma", que es lo que hay que
    // poder hacer para terminar el alta.
    //
    // ⚠️ Si mañana se agrega una restricción que también bloquee un paso del alta,
    // va acá. `check_profile_sync.py` verifica que todo lo que está en esta lista
    // exista de verdad en algún perfil, para que no quede una entrada muerta.
    val POST_ALTA: Set<String> = setOf(
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        UserManager.DISALLOW_CONFIG_LOCALE
    )

    // ─────────────────────────────────────────────────────────────────────────
    // LO QUE NINGÚN PERFIL DE ALTA ENCIENDE, Y POR QUÉ
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Estas cuatro NO están en ningún perfil de abajo. No es olvido: cada una puede
    // dejar un equipo inservible o irrecuperable, y ninguna se puede deshacer desde el
    // propio equipo si sale mal. Van a mano, con el equipo a la vista.
    //
    //  · `kioskLockTask` (B.35) — si el marcador telefónico no está en la lista blanca
    //    del launcher, en ese equipo NO se puede marcar `*#*#9999#*#*`, que es LA vía de
    //    recuperación. Un perfil de alta que lo encienda puede dejar un equipo sin
    //    salida antes de que nadie lo pruebe.
    //  · `nokiaTouchEnabled=false` (B.36) — apagar el táctil en un equipo sin teclas
    //    físicas lo deja manejable solo desde el panel.
    //  · `accSuspendAll` (B.15) — costo "alto" por decisión explícita del dueño: con la
    //    accesibilidad caída el equipo no sirve para nada hasta reactivarla.
    //  · Los tres interruptores de la lista blanca (B.53) — el modo estricto tiene un
    //    despliegue por etapas (simulación → bloquear de verdad) que depende de haber
    //    completado el catálogo con el equipo en la mano. Un perfil de alta que mueva un
    //    equipo entre esas etapas en silencio es exactamente lo que B.53 pidió evitar.
    //    Al no nombrarlas, un perfil no las toca: clave ausente = no se toca.
    //
    // El perfil que el administrador guarda desde un equipo bien configurado SÍ las
    // lleva (`exportPolicyPresetJson`), y está bien que sea así: ahí hubo una persona
    // mirando el equipo. La diferencia entre los dos casos es deliberada.

    /** Restricciones anti-manipulación. Es el piso de los tres perfiles. */
    private val ANTI_TAMPER: Map<String, Boolean> = mapOf(
        UserManager.DISALLOW_FACTORY_RESET to true,
        UserManager.DISALLOW_SAFE_BOOT to true,
        UserManager.DISALLOW_DEBUGGING_FEATURES to true,
        UserManager.DISALLOW_UNINSTALL_APPS to true,
        UserManager.DISALLOW_USER_SWITCH to true,
        UserManager.DISALLOW_CONFIG_VPN to true
    )

    private val STRICT_RESTRICTIONS: Map<String, Boolean> = ANTI_TAMPER + mapOf(
        UserManager.DISALLOW_INSTALL_APPS to true,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES to true,
        UserManager.DISALLOW_APPS_CONTROL to true,
        UserManager.DISALLOW_CONFIG_TETHERING to true,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA to true,
        UserManager.DISALLOW_BLUETOOTH_SHARING to true,
        UserManager.DISALLOW_NETWORK_RESET to true,
        // B.19 — la defensa más barata contra la evasión por idioma: cualquier filtro
        // que compare texto de pantalla queda mudo si el equipo cambia a un idioma no
        // previsto. Cuesta que el instalador tenga que dejar el idioma bien ANTES.
        UserManager.DISALLOW_CONFIG_LOCALE to true,
        // B.33 — sin esto el Watchdog tiene que reimponer "DNS privado apagado" cada 60 s,
        // y en esa ventana el filtro DNS no ve nada. Es la más valiosa de las 20 nuevas.
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS to true,
        UserManager.DISALLOW_CONFIG_CREDENTIALS to true,
        UserManager.DISALLOW_SET_WALLPAPER to true,
        UserManager.DISALLOW_CONFIG_DATE_TIME to true,
        // ⚠️ Bloquea agregar y quitar cuentas de Google. Va en el aviso del perfil: si se
        // aplica ANTES de terminar el alta de la cuenta, el equipo no se puede dar de
        // alta. Es el mismo error de razonamiento que costó B.41 punto 3 y B.43: LockSuite
        // se instala con el equipo SIN cuenta, así que "todavía no hay cuenta" es el
        // estado de fábrica del procedimiento, no un caso raro.
        UserManager.DISALLOW_MODIFY_ACCOUNTS to true
    )

    private val STRICT_SWITCHES: Map<String, Boolean> = mapOf(
        "adBlockingEnabled" to true,
        "gifsBlocked" to true,
        "whatsappBlockStatus" to true,
        "whatsappBlockChannels" to true,
        "mercadoPagoBlockOffersAccessibility" to true,
        "mercadoPagoBlockOffersVpn" to true,
        "blockMlInMp" to true,
        "blockPopularNonKosher" to true,
        "googleAccountWebBlocked" to true,
        // B.43 — NORMAL, no estricto. El modo estricto cierra la administración entera de
        // la cuenta de Google (datos, seguridad, dispositivos, pagos) porque todo eso vive
        // detrás de UNA sola Activity. El dueño ya reportó eso como bloqueo de más el 4/9.
        "googleAccountBlockStrict" to false,
        "captivePortalGuard" to true,
        "captivePortalCoverImages" to true,
        "contactPhotoPickerBlocked" to true,
        "kosherLauncherEnabled" to true,
        "hideSuspendedApps" to true,
        "flashingBlocked" to true,
        "accessibilityProtection" to true,
        "accBounceSettings" to true,
        "accNag" to true,
        "bootGateEnabled" to true,
        "bootGateWaitAccessibility" to true,
        // Explícitamente en false: son las que se decidió no encender de fábrica (ver el
        // bloque de arriba). Nombrarlas con su valor seguro es mejor que omitirlas,
        // porque deja el resultado del perfil sin ambigüedad.
        "accSuspendAll" to false,
        "kioskLockTask" to false,
        "nokiaKeypadMode" to false,
        "nokiaTouchEnabled" to true,
        "imageBlockStrictScroll" to false,
        "internetBlocked" to false,
        "cameraDisabled" to false,
        "screenCaptureBlocked" to false,
        "statusBarDisabled" to false,
        "keyguardDisabled" to false
    )

    private val WORK_RESTRICTIONS: Map<String, Boolean> = ANTI_TAMPER + mapOf(
        UserManager.DISALLOW_INSTALL_APPS to true,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES to true,
        UserManager.DISALLOW_CONFIG_TETHERING to true,
        UserManager.DISALLOW_CONFIG_LOCALE to true,
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS to true,
        UserManager.DISALLOW_SET_WALLPAPER to true,
        // Las tres diferencias con el estricto, y son las que hacen que el equipo sirva
        // para trabajar: se pueden agregar cuentas (correo de la empresa), controlar apps
        // y usar medios físicos.
        UserManager.DISALLOW_MODIFY_ACCOUNTS to false,
        UserManager.DISALLOW_APPS_CONTROL to false,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA to false
    )

    private val WORK_SWITCHES: Map<String, Boolean> = mapOf(
        "adBlockingEnabled" to true,
        "gifsBlocked" to true,
        "whatsappBlockStatus" to true,
        "whatsappBlockChannels" to true,
        "mercadoPagoBlockOffersAccessibility" to true,
        "mercadoPagoBlockOffersVpn" to true,
        "blockMlInMp" to true,
        "blockPopularNonKosher" to true,
        "googleAccountWebBlocked" to true,
        "googleAccountBlockStrict" to false,
        "captivePortalGuard" to true,
        "captivePortalCoverImages" to true,
        "contactPhotoPickerBlocked" to true,
        // El launcher kosher queda APAGADO: un equipo de trabajo necesita su lanzador
        // normal. Es la diferencia más visible entre este nivel y el estricto.
        "kosherLauncherEnabled" to false,
        "hideSuspendedApps" to true,
        "flashingBlocked" to true,
        "accessibilityProtection" to true,
        "accBounceSettings" to true,
        "accNag" to true,
        "bootGateEnabled" to true,
        // Costo medio (B.15): esperar a que una persona vaya a Ajustes puede tardar. En un
        // equipo de trabajo eso es tiempo sin red, así que acá va apagado.
        "bootGateWaitAccessibility" to false,
        "accSuspendAll" to false,
        "kioskLockTask" to false,
        "nokiaKeypadMode" to false,
        "nokiaTouchEnabled" to true,
        "imageBlockStrictScroll" to false,
        "internetBlocked" to false,
        "cameraDisabled" to false,
        "screenCaptureBlocked" to false,
        "statusBarDisabled" to false,
        "keyguardDisabled" to false
    )

    // ── NIVEL 4 — PERÍODO DE GRACIA (auto-personalización) ────────────────────
    //
    // La idea, en una línea: **lo claramente no kosher se cierra ya; lo dudoso se deja
    // abierto un tiempo y se observa.**
    //
    // Lo que hace distinto a este perfil de los otros tres no es lo que bloquea, sino lo
    // que DELIBERADAMENTE deja abierto:
    //
    //  · `DISALLOW_INSTALL_APPS` en false → la tienda queda abierta, que es lo que pidió
    //    el dueño. Es la única de las cuatro entradas de alta donde eso pasa.
    //  · `kosherLauncherEnabled` en false → el usuario usa su lanzador normal y organiza
    //    el equipo como quiere. Es "lo que no es tan claro queda a decisión del usuario".
    //  · `DISALLOW_APPS_CONTROL` en false → puede desinstalar y reordenar sus apps.
    //
    // Y lo que NO se negocia ni siquiera durante la gracia:
    //
    //  · El piso anti-manipulación entero (no se puede formatear, ni modo seguro, ni
    //    desinstalar LockSuite). Un período de gracia no es un equipo sin MDM.
    //  · `DISALLOW_CONFIG_DATE_TIME` → **es la defensa del propio vencimiento.** Sin esto,
    //    atrasar el reloj alcanza para que el cierre no llegue nunca. Es la única
    //    restricción de este perfil que está por un motivo mecánico y no de contenido, y
    //    por eso está anotada acá: si alguien la saca "porque molesta", rompe la función
    //    entera y no va a ser evidente.
    //  · Todos los filtros de contenido claros: apps populares no kosher, estados y
    //    canales de WhatsApp, ofertas de Mercado Pago, historial de la cuenta de Google,
    //    GIFs, anuncios, portal cautivo, selector de fotos de contacto.
    private val GRACE_RESTRICTIONS: Map<String, Boolean> = ANTI_TAMPER + mapOf(
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES to true,
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS to true,
        UserManager.DISALLOW_CONFIG_LOCALE to true,
        // La que sostiene el vencimiento. Ver el comentario de arriba.
        UserManager.DISALLOW_CONFIG_DATE_TIME to true,
        UserManager.DISALLOW_CONFIG_TETHERING to true,
        // Explícitamente abiertas durante la gracia:
        UserManager.DISALLOW_INSTALL_APPS to false,
        UserManager.DISALLOW_APPS_CONTROL to false,
        UserManager.DISALLOW_MODIFY_ACCOUNTS to false,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA to false
    )

    private val GRACE_SWITCHES: Map<String, Boolean> = mapOf(
        // Lo claramente no kosher: cerrado desde el minuto cero.
        "adBlockingEnabled" to true,
        "gifsBlocked" to true,
        "whatsappBlockStatus" to true,
        "whatsappBlockChannels" to true,
        "mercadoPagoBlockOffersAccessibility" to true,
        "mercadoPagoBlockOffersVpn" to true,
        "blockMlInMp" to true,
        "blockPopularNonKosher" to true,
        "googleAccountWebBlocked" to true,
        "googleAccountBlockStrict" to false,
        "captivePortalGuard" to true,
        "captivePortalCoverImages" to true,
        "contactPhotoPickerBlocked" to true,
        "flashingBlocked" to true,
        "accessibilityProtection" to true,
        "accBounceSettings" to true,
        "accNag" to true,
        "bootGateEnabled" to true,
        // Lo dudoso: abierto, a criterio del usuario, hasta que venza.
        "kosherLauncherEnabled" to false,
        "hideSuspendedApps" to false,
        "bootGateWaitAccessibility" to false,
        // Nunca, en ningún perfil de alta:
        "accSuspendAll" to false,
        "kioskLockTask" to false,
        "nokiaKeypadMode" to false,
        "nokiaTouchEnabled" to true,
        "imageBlockStrictScroll" to false,
        "internetBlocked" to false,
        "cameraDisabled" to false,
        "screenCaptureBlocked" to false,
        "statusBarDisabled" to false,
        "keyguardDisabled" to false
    )

    private val BASE_RESTRICTIONS: Map<String, Boolean> = ANTI_TAMPER

    private val BASE_SWITCHES: Map<String, Boolean> = mapOf(
        "accessibilityProtection" to true,
        "flashingBlocked" to true,
        "bootGateEnabled" to true,
        "captivePortalGuard" to true,
        "captivePortalCoverImages" to true,
        "internetBlocked" to false,
        "accSuspendAll" to false,
        "kioskLockTask" to false,
        "nokiaKeypadMode" to false,
        "nokiaTouchEnabled" to true,
        "statusBarDisabled" to false,
        "keyguardDisabled" to false
    )

    /**
     * Los tres perfiles, en el orden en que se muestran.
     *
     * El orden es de más cerrado a más abierto a propósito: el caso frecuente —dar de
     * alta un equipo kosher— es el primero, y el que hay que pensar dos veces es el
     * último.
     */
    val ALL: List<MasterProfile> = listOf(
        MasterProfile(
            id = LEVEL_STRICT,
            label = "Nivel 1 — Kosher estricto",
            summary = "Llamadas, mensajes, bancos y las apps permitidas. Sin navegador, " +
                "sin tienda, sin estados de WhatsApp, sin ofertas, con launcher kosher.",
            // El aviso es la parte más importante de esta entrada. Un perfil de alta que
            // se aplica en el momento equivocado deja un equipo que no se puede dar de
            // alta, y el instalador no tendría cómo saber por qué.
            warning = "Bloquea agregar y quitar cuentas de Google, y bloquea el cambio de " +
                "idioma. Aplicalo DESPUÉS de haber agregado la cuenta de Google y de haber " +
                "dejado el equipo en el idioma definitivo.",
            restrictions = STRICT_RESTRICTIONS,
            switches = STRICT_SWITCHES
        ),
        MasterProfile(
            id = LEVEL_WORK,
            label = "Nivel 2 — Trabajo",
            summary = "Todo lo del Nivel 1 salvo el launcher kosher, y deja agregar " +
                "cuentas de correo y manejar las apps del equipo.",
            warning = "Bloquea el cambio de idioma del sistema: dejá el equipo en el " +
                "idioma definitivo antes de aplicarlo.",
            restrictions = WORK_RESTRICTIONS,
            switches = WORK_SWITCHES
        ),
        MasterProfile(
            id = LEVEL_GRACE,
            label = "Nivel 4 — Período de gracia",
            summary = "Bloquea todo lo claramente no kosher y deja lo dudoso a criterio " +
                "del usuario, con la tienda abierta. Al vencer el plazo se cierra solo " +
                "aplicando el Nivel 1.",
            // Los dos avisos que importan: que el equipo queda MÁS abierto de lo normal
            // mientras dure, y qué se gana a cambio — porque si el administrador no sabe
            // que la auditoría se está llenando, no la va a mirar y se pierde la mitad del
            // valor de esperar dos días.
            warning = "Mientras dure, el equipo queda más abierto: puede instalar apps y " +
                "usar su lanzador normal. A cambio, LockSuite va anotando qué dominios usa " +
                "de verdad — miralos en la pestaña Lista blanca antes de cerrar. " +
                "El plazo se elige al aplicarlo.",
            restrictions = GRACE_RESTRICTIONS,
            switches = GRACE_SWITCHES
        ),
        MasterProfile(
            id = LEVEL_BASE,
            label = "Nivel 3 — Base mínima",
            summary = "Solo el piso anti-manipulación: no se puede formatear, ni entrar " +
                "en modo seguro, ni desinstalar LockSuite. Ningún filtro de contenido.",
            // Este es el único de los tres que AFLOJA. Decirlo en el aviso y no en la
            // descripción: la confirmación es lo único que la persona lee seguro.
            warning = "Este perfil APAGA los filtros de contenido (anuncios, GIFs, estados " +
                "de WhatsApp, ofertas, cuenta de Google, launcher kosher). Usalo para " +
                "diagnosticar, no para entregar un equipo.",
            restrictions = BASE_RESTRICTIONS,
            switches = BASE_SWITCHES
        )
    )

    fun byId(id: String?): MasterProfile? = ALL.firstOrNull { it.id == id }

    /**
     * Arma el objeto `data` del perfil, con la misma forma que consume
     * `PolicyManager.importPolicyPresetJson()`.
     *
     * Función pura: no toca preferencias, ni el sistema, ni la red. Se puede ejercitar
     * fuera del equipo, que es donde se agarran los errores de esta clase (una clave mal
     * escrita no da error en ningún lado: se acepta y no hace nada).
     *
     * @return el objeto `data`, o `null` si el id no existe.
     */
    @JvmOverloads
    fun buildData(profileId: String?, incluirPostAlta: Boolean = true): JSONObject? {
        val profile = byId(profileId) ?: return null
        val data = JSONObject()

        val restrictionsObj = JSONObject()
        for ((key, value) in profile.restrictions) {
            // B.61 — durante el aprovisionamiento por QR se omiten las de [POST_ALTA].
            // Se OMITEN (la clave no aparece), no se ponen en `false`: el importador
            // trata "clave ausente" como "no se toca", y ponerlas en false las
            // levantaría explícitamente en un equipo que ya las tuviera puestas.
            if (!incluirPostAlta && key in POST_ALTA) continue
            restrictionsObj.put(key, value)
        }
        data.put("restrictions", restrictionsObj)

        for ((key, value) in profile.switches) {
            data.put(key, value)
        }

        // Nunca se manda un array vacío: Realtime Database los descarta y la clave
        // desaparece, que fue exactamente el bug de B.28 (el perfil firmado y el perfil
        // guardado dejaban de ser el mismo objeto y la firma no coincidía nunca). Acá el
        // perfil no pasa por la base, pero la regla se respeta igual para que las dos
        // rutas produzcan objetos con la misma forma y una sola firma sirva para las dos.
        return data
    }

    /**
     * Nombre de perfil que queda guardado en el equipo. Lleva la fecha para que en el
     * panel se pueda distinguir un equipo dado de alta hoy de uno de hace tres meses.
     */
    /**
     * Lo que quedó pendiente de aplicar tras un alta por QR. Vacío si el perfil no
     * toca ninguna de las de [POST_ALTA] — en ese caso el alta ya quedó completa y el
     * panel no tiene por qué mostrar el botón "Terminar alta".
     */
    fun pendientesDeAlta(profileId: String?): Set<String> =
        byId(profileId)?.postAlta ?: emptySet()

    fun presetNameFor(profileId: String?): String {
        val profile = byId(profileId) ?: return "Perfil LockSuite"
        return profile.label
    }
}
