package com.ejemplo.locksuite.mdm

/**
 * CATÁLOGO DE LA LISTA BLANCA (8/9/2026).
 *
 * Qué es: la tabla "app → dominios" que hace que permitir o prohibir una app sea UN
 * solo toque. Permitir una app significa tres cosas a la vez (se puede descargar de la
 * tienda administrada, no queda oculta ni suspendida, y sus dominios resuelven);
 * prohibirla significa las tres al revés. Esta tabla es la parte de "sus dominios".
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LO PRIMERO QUE HAY QUE ENTENDER ANTES DE TOCAR ESTE ARCHIVO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 1. **Esta lista NO puede estar completa, y no hace falta que lo esté.** Los dominios
 *    de backend de una app cambian sin aviso, varían por país y por versión, y no hay
 *    ninguna fuente pública confiable app por app. Por eso el modo lista blanca arranca
 *    en SIMULACIÓN: registra qué HABRÍA bloqueado sin bloquear nada, el equipo publica
 *    esa lista al panel (`whitelistAudit`), y desde ahí se agrega lo que falte con un
 *    clic. Completar la lista es un trámite de diez minutos con el equipo en la mano;
 *    adivinarla desde acá es imposible. **Ese es el diseño, no una limitación.**
 *
 * 2. **La resolución es "el más específico gana"** (DomainRuleTrie). Por eso se puede
 *    permitir `googleapis.com` entero y bloquear `tenor.googleapis.com` en la misma
 *    tabla: la entrada más profunda gana. Es el mecanismo con el que se cumple el
 *    pedido de "Google Messages SIN los GIF de Tenor".
 *
 * 3. **Los subdominios se heredan solos.** Poner `waze.com` alcanza para
 *    `www.waze.com`, `api.waze.com`, `a.b.waze.com`. NO hace falta enumerarlos, y
 *    enumerarlos de más solo agranda el Trie.
 *
 * 4. **`google.com` NO se permite nunca entero**, y eso es a propósito: sería habilitar
 *    la búsqueda de Google, o sea un navegador. Cada app de Google entra por su host
 *    exacto (`mail.google.com`, `drive.google.com`, …). Mismo criterio para
 *    `translate.google.com`, que se deja AFUERA aunque el Traductor esté permitido:
 *    esa URL traduce páginas web enteras y funciona como proxy de navegación. El
 *    Traductor usa `translate.googleapis.com`, que sí está.
 *
 * 5. **`INFRASTRUCTURE` no es negociable.** Son los dominios sin los cuales el equipo
 *    deja de ser administrable (FCM, o sea los comandos del panel), deja de poder
 *    actualizar apps (Play Store y sus CDN), deja de detectar portales cautivos, o
 *    pierde la hora (y con ella todo TLS). Un equipo sin FCM es un equipo que ya no
 *    se puede rescatar desde el panel. Por eso esta lista se aplica ANTES que
 *    cualquier lista negra y no se puede sacar desde el panel. La única forma de
 *    bloquear uno de estos dominios es un FORCE_BLOCK explícito desde la sección DNS,
 *    que le gana a todo — es la salida de emergencia, y es deliberada.
 *
 * 6. **`SHARED_CDN` es la parte incómoda y está separada a propósito.** Uber, Moovit y
 *    varias más sirven su contenido desde CDN compartidos (CloudFront, Akamai, Fastly).
 *    Permitir `cloudfront.net` entero es permitir una porción enorme de internet, no
 *    solo Uber. Va en su propio grupo, con interruptor propio en el panel
 *    (`whitelist_allow_shared_cdn`, encendido por omisión para que las apps anden de
 *    entrada). Apagarlo endurece mucho el filtro a cambio de tener que agregar a mano
 *    el host exacto de cada CDN que haga falta — que es justo lo que la simulación
 *    deja servido.
 */
object WhitelistCatalog {

    /** Una app del catálogo, con sus dominios permitidos y los que se le bloquean igual. */
    data class Entry(
        val packageName: String,
        val label: String,
        /** Dominios que resuelven si la app está permitida. Los subdominios se heredan. */
        val allow: List<String>,
        /**
         * Dominios que se bloquean SIEMPRE que esta app esté permitida — la parte no
         * kosher de una app por lo demás aceptable (los GIF de Tenor, las ofertas de
         * Mercado Pago, el foro de Waze). Gana sobre `allow` por ser más específico.
         */
        val block: List<String> = emptyList(),
        /** Nota para el panel: por qué esta app necesita revisarse con la simulación. */
        val note: String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    // INFRAESTRUCTURA — NUNCA SE BLOQUEA (salvo FORCE_BLOCK explícito)
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Cada bloque de abajo tiene anotado QUÉ SE ROMPE si falta. Antes de sacar uno,
    // leer esa línea: varios de estos ya fueron causa de un síntoma reportado.
    val INFRASTRUCTURE: List<String> = listOf(
        // ── Canal de comandos (FCM). Sin esto el panel NO controla el equipo. ──
        // Es el caso más grave de todos: un equipo sordo a FCM no se puede destrabar
        // remotamente, hay que tenerlo en la mano. Ver B.52: `mtalk.google.com` cae
        // bajo el sufijo `google.com` de la lista negra global de WebView, que por el
        // problema de atribución de B.10 se aplica a TODO el equipo.
        "mtalk.google.com",
        "mtalk4.google.com",
        "alt1-mtalk.google.com",
        "alt2-mtalk.google.com",
        "alt3-mtalk.google.com",
        "alt4-mtalk.google.com",
        "alt5-mtalk.google.com",
        "alt6-mtalk.google.com",
        "alt7-mtalk.google.com",
        "alt8-mtalk.google.com",
        "device-provisioning.googleapis.com",

        // ── Play Store y sus CDN de descarga. Sin esto NO se actualiza ninguna app. ──
        // `gvt1/gvt2/gvt3.com` y `dl.google.com` son de donde Play Store baja los APK:
        // sin ellos la ficha carga, el botón "Actualizar" se aprieta, y la descarga
        // muere sin explicación — exactamente el síntoma que persigue B.41.
        "play.google.com",
        "android.clients.google.com",
        "play.googleapis.com",
        "playatoms-pa.googleapis.com",
        "dl.google.com",
        "gvt1.com",
        "gvt2.com",
        "gvt3.com",
        "googleusercontent.com",
        "ggpht.com",

        // ── APIs de Google y recursos estáticos. ──
        // Es una superficie ancha y se sabe: prácticamente toda app de Google (y muchas
        // que no lo son) dependen de estos dos. Bloquearlos es dejar el equipo inservible.
        // Lo no kosher que vive adentro se corta por host exacto más abajo (BLOCK_ALWAYS).
        "googleapis.com",
        "gstatic.com",

        // ── Detección de portal cautivo. Sin esto el Wi-Fi de hoteles/aeropuertos ──
        // queda "conectado sin internet" para siempre. Ver B.46 y B.50: ya dejó a un
        // usuario tirado en un aeropuerto una vez.
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients3.google.com",
        "clients4.google.com",

        // ── Hora. Sin hora correcta TODO handshake TLS falla y el equipo queda ──
        // sin internet de una forma que no se parece en nada a un problema de DNS.
        "time.google.com",
        "time.android.com",
        "ntp.org",

        // ── Certificados (OCSP/CRL). Sin esto, TLS estricto falla en varias apps. ──
        "pki.goog",
        "lencr.org",
        "digicert.com",
        "amazontrust.com",
        "sectigo.com",
        "globalsign.com",

        // ── El propio LockSuite: panel, base de datos, OTA, login del panel. ──
        "locksuite-nueva.web.app",
        "locksuite-nueva.firebaseapp.com",
        "firebaseio.com",
        "firebasedatabase.app",
        "cloudfunctions.net",
        "firebaseinstallations.googleapis.com",
        "identitytoolkit.googleapis.com",
        "securetoken.googleapis.com",

        // ── Actualización del sistema operativo (Samsung: gran parte de la flota). ──
        // No incluye `samsungapps.com` a propósito: Galaxy Store es otra tienda de
        // apps, o sea una vía de instalación paralela a la que sí se controla.
        "ospserver.net",
        "samsungdm.com"
    )

    /**
     * Se bloquea SIEMPRE, incluso con la lista blanca apagada y aunque caiga bajo un
     * dominio de infraestructura permitido. Son los rincones no kosher de servicios
     * que por lo demás hacen falta.
     */
    val BLOCK_ALWAYS: List<String> = listOf(
        // GIF y stickers dentro de Mensajes / Chat / Gboard (pedido explícito del dueño).
        "tenor.googleapis.com",
        "tenor.com",
        "giphy.com",
        "gboard-stickers.googleapis.com",
        // YouTube por la puerta de atrás de la API.
        "youtubei.googleapis.com",
        "youtube.googleapis.com",
        // Discovery / feed de contenido de Google (el "Descubre" del launcher).
        "discover.google.com"
    )

    /**
     * CDN compartidos. Grupo aparte con interruptor propio porque permitir uno de
     * estos es permitir una porción enorme de internet, no solo la app que lo pidió.
     * Ver el punto 6 del comentario de cabecera.
     */
    val SHARED_CDN: List<String> = listOf(
        "cloudfront.net",
        "akamaized.net",
        "akamaihd.net",
        "akamai.net",
        "fastly.net",
        "cloudflare.com",
        "cdn77.org",
        "bunnycdn.com",
        "b-cdn.net"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // CATÁLOGO DE APPS
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Orden: como las nombró el dueño. `note` dice qué mirar en la simulación.
    val APPS: List<Entry> = listOf(

        Entry(
            packageName = "com.waze",
            label = "Waze",
            allow = listOf("waze.com", "wazestatic.com", "waze-cdn.com"),
            // Los tres primeros son Zendesk/comunidad, o sea navegación libre dentro de
            // un WebView. Ya venían bloqueados por WebViewPolicy.CORE_DOMAINS y se
            // conservan acá para que la decisión viva en un solo lugar.
            block = listOf(
                "support.waze.com", "help.waze.com", "forum.waze.com",
                "blog.waze.com", "careers.waze.com"
            ),
            note = "Los mapas salen por googleapis.com/gstatic.com (infraestructura)."
        ),

        Entry(
            packageName = "com.didiglobal.passenger",
            label = "DiDi",
            allow = listOf(
                "didiglobal.com", "xiaojukeji.com", "diditaxi.com.cn", "didistatic.com"
            ),
            block = listOf(
                "game.diditaxi.com.cn", "minigame.didiglobal.com", "play.didiglobal.com",
                "drama.didiglobal.com", "webcast.didiglobal.com", "live.didiglobal.com"
            ),
            note = "DiDi mete juegos, streaming y video dentro de la app: por eso los 6 bloqueos."
        ),

        Entry(
            packageName = "com.mercadopago.wallet",
            label = "Mercado Pago",
            // ⚠️ LA MÁS DELICADA DE LA LISTA. Mercado Pago se apoya en la
            // infraestructura de Mercado Libre (api.mercadolibre.com para varias
            // operaciones, mlstatic.com para TODAS las imágenes e íconos de la app).
            // Bloquear mlstatic.com deja la app usable pero visualmente rota; bloquear
            // api.mercadolibre.com puede romper pagos. Se permite lo mínimo de ML y se
            // bloquea el marketplace, que es la parte navegable.
            allow = listOf(
                "mercadopago.com", "mercadopago.com.ar", "mercadolibre.com",
                "mercadolibre.com.ar", "mlstatic.com"
            ),
            block = listOf(
                // El marketplace: catálogo, búsqueda y ofertas. Es la parte "navegable".
                "www.mercadolibre.com", "www.mercadolibre.com.ar",
                "listado.mercadolibre.com", "listado.mercadolibre.com.ar",
                "click1.mercadolibre.com", "click1.mercadolibre.com.ar",
                "mobile.mercadolibre.com", "mobile.mercadolibre.com.ar",
                "snoopy.mercadolibre.com", "snoopy.mercadolibre.com.ar",
                // Ofertas y beneficios dentro de la propia Mercado Pago.
                "ofertas.mercadopago.com", "promociones.mercadopago.com",
                "deals.mercadopago.com", "beneficios.mercadopago.com",
                "descuentos.mercadopago.com", "loyalty.mercadopago.com",
                "matt.mercadopago.com",
                "ofertas.mercadopago.com.ar", "promociones.mercadopago.com.ar",
                "beneficios.mercadopago.com.ar", "descuentos.mercadopago.com.ar"
            ),
            note = "PAGOS: probar una transferencia real en simulación antes de pasar a estricto."
        ),

        Entry(
            packageName = "com.google.android.apps.walletnfcrel",
            label = "Google Wallet / Pay",
            allow = listOf(
                "pay.google.com", "wallet.google.com", "payments.google.com",
                "paymentsresources.googleapis.com"
            ),
            note = "El pago NFC en sí no usa DNS; lo que resuelve es el alta de la tarjeta."
        ),

        Entry(
            packageName = "ar.com.personalpay",
            label = "Personal Pay",
            allow = listOf(
                "personalpay.com.ar", "personal.com.ar", "telecom.com.ar",
                "personalpay.com"
            ),
            note = "Sin fuente pública de sus dominios de backend: ES DE LAS QUE MÁS NECESITA LA SIMULACIÓN."
        ),

        Entry(
            packageName = "com.google.android.gm",
            label = "Gmail",
            allow = listOf("mail.google.com", "gmail.com", "inbox.google.com"),
            note = "Las imágenes remotas de los correos vienen de dominios de terceros: con la lista blanca estricta no van a cargar. Es el comportamiento deseado, pero conviene saberlo."
        ),

        Entry(
            packageName = "com.google.android.apps.dynamite",
            label = "Google Chat",
            allow = listOf("chat.google.com"),
            block = listOf("tenor.googleapis.com", "tenor.com", "giphy.com"),
            note = "Chat tiene selector de GIF propio: por eso los bloqueos aunque esté permitida."
        ),

        Entry(
            packageName = "com.google.android.apps.messaging",
            label = "Google Messages",
            allow = listOf(
                "messages.google.com", "jibe.google.com",
                "instantmessaging-pa.googleapis.com"
            ),
            // Pedido textual del dueño: "google messages (sin gif de tenor)".
            block = listOf(
                "tenor.googleapis.com", "tenor.com", "media.tenor.com",
                "giphy.com", "gboard-stickers.googleapis.com"
            ),
            note = "RCS pasa por jibe.google.com; si los mensajes quedan en 'enviando', mirar ahí primero."
        ),

        Entry(
            packageName = "com.google.android.apps.docs",
            label = "Google Drive",
            allow = listOf("drive.google.com", "docs.google.com", "drive.usercontent.google.com"),
            note = "Los archivos se descargan de googleusercontent.com (infraestructura)."
        ),

        Entry(
            packageName = "com.ubercab",
            label = "Uber",
            allow = listOf("uber.com", "uber-assets.com", "uberinternal.com"),
            note = "Uber sirve buena parte de sus recursos desde CloudFront: si con la lista estricta no carga, es el grupo 'CDN compartidos'."
        ),

        Entry(
            packageName = "com.google.android.apps.adm",
            label = "Localizador de dispositivos",
            allow = listOf("androiddeviceregistration-pa.googleapis.com"),
            note = "A propósito NO se permite google.com/android/find: esa es la interfaz WEB, o sea un navegador. La app usa googleapis.com."
        ),

        Entry(
            packageName = "life.channel.accurate.local.weather.forecast",
            label = "Centro de información / Clima",
            allow = listOf("koshersystem.com", "hm-news.co.il", "lomdaat.co.il"),
            note = "⚠️ El paquete que pasaste es 'מרכז המידע' (Centro de información) de Lomdaat, no una app de clima genérica. Su API es news-api.koshersystem.com. Verificá que sea la que querías."
        ),

        Entry(
            packageName = "com.lionscribe.hebdate",
            label = "HebDate (calendario hebreo)",
            allow = listOf("lionscribe.com"),
            note = "Calendario mayormente offline: es probable que no necesite ningún dominio."
        ),

        Entry(
            packageName = "fm.jewishmusic.application",
            label = "Zing Music",
            allow = listOf("zingmusic.app", "jewishmusic.fm"),
            note = "El streaming del audio puede salir por un CDN: si reproduce cero segundos, mirar la auditoría."
        ),

        Entry(
            packageName = "com.lomdaat.apps.music",
            label = "Jusic",
            allow = listOf("jusic.app", "jusic.co", "lomdaat.co.il", "koshersystem.com"),
            note = "Mismo desarrollador que el Centro de información (Lomdaat): comparten infraestructura."
        ),

        Entry(
            packageName = "com.whatsapp",
            label = "WhatsApp",
            allow = listOf("whatsapp.com", "whatsapp.net", "wa.me", "fbcdn.net"),
            note = "Estados y Canales se bloquean por Capa 3 (accesibilidad), no por DNS: comparten los mismos dominios que los chats."
        ),

        Entry(
            packageName = "com.google.android.apps.tachyon",
            label = "Google Meet",
            allow = listOf("meet.google.com", "tachyon.googleapis.com"),
            note = "El audio/video va por UDP directo a IP de Google, sin DNS: si la llamada conecta y no hay imagen, no es este filtro."
        ),

        Entry(
            packageName = "com.google.android.apps.meetings",
            label = "Google Meet (app aparte)",
            allow = listOf("meet.google.com", "tachyon.googleapis.com"),
            note = "Google tiene dos paquetes distintos según el equipo: se permiten los dos."
        ),

        Entry(
            packageName = "com.beatmobile.ak",
            label = "Ajdut Kosher",
            allow = listOf("kosher.org.ar", "beatmobile.com.ar"),
            note = "Desarrollador Beatmobile; el sitio de la certificación es kosher.org.ar. Confirmar con la simulación."
        ),

        Entry(
            packageName = "com.tranzmate",
            label = "Moovit",
            allow = listOf("moovitapp.com", "moovit.com", "tranzmate.com"),
            note = "Usa CDN compartidos para los mapas de líneas."
        ),

        Entry(
            packageName = "com.google.android.apps.chromecast.app",
            label = "Google Home",
            allow = listOf("home.google.com", "googlecast.com"),
            note = "El control de los dispositivos es local (mDNS), no pasa por este filtro."
        ),

        Entry(
            packageName = "com.tuya.smartlife",
            label = "Smart Life (Tuya)",
            allow = listOf(
                "tuya.com", "tuyaus.com", "tuyaeu.com", "tuyacn.com", "tuyain.com",
                "smart-life.com"
            ),
            note = "Tuya asigna región por cuenta: en Argentina cae en tuyaus.com, pero se permiten las cuatro regiones para no romper cuentas viejas."
        ),

        Entry(
            packageName = "com.google.android.apps.translate",
            label = "Traductor de Google",
            allow = listOf("translate.googleapis.com"),
            // Ver punto 4 del comentario de cabecera: translate.google.com traduce
            // páginas web enteras y funciona como proxy de navegación.
            block = listOf("translate.google.com"),
            note = "translate.google.com queda BLOQUEADO a propósito: es un proxy de navegación. La app usa translate.googleapis.com y funciona igual."
        ),

        Entry(
            packageName = "com.google.android.keep",
            label = "Google Keep",
            allow = listOf("keep.google.com")
        ),

        Entry(
            packageName = "com.google.android.contacts",
            label = "Contactos de Google",
            allow = listOf("contacts.google.com", "people-pa.googleapis.com"),
            note = "El selector de foto de contacto ya tiene su propio bloqueo (B.47)."
        ),

        Entry(
            packageName = "com.google.android.calendar",
            label = "Calendario de Google",
            allow = listOf("calendar.google.com")
        )
    )

    private val BY_PACKAGE: Map<String, Entry> = APPS.associateBy { it.packageName }

    fun entryFor(packageName: String): Entry? = BY_PACKAGE[packageName]

    /** Paquetes que el catálogo de fábrica conoce. */
    fun knownPackages(): Set<String> = BY_PACKAGE.keys

    /**
     * Apps que nunca se prohíben ni se ocultan, pase lo que pase: sin ellas el equipo
     * deja de poder llamar, deja de poder configurarse o deja de poder administrarse.
     * `AppController.isCritical()` cubre lo mismo del lado de las apps; acá se repite
     * el mínimo indispensable porque el modo lista blanca puede correr sobre un
     * inventario que todavía no se sincronizó.
     */
    val NEVER_BLOCK_PACKAGES: Set<String> = setOf(
        "com.ejemplo.locksuite",
        "com.android.vending",          // Play Store: sin esto no hay actualizaciones
        "com.google.android.gms",       // Play Services: sin esto no hay FCM
        "com.google.android.gsf",
        "com.android.settings",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.systemui",
        "com.android.providers.telephony",
        "com.android.captiveportallogin"
    )
}
