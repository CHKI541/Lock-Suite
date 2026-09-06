package com.ejemplo.locksuite.mdm

/**
 * PopularNonKosherApps — Catálogo maestro de aplicaciones populares no kosher y bloatware OEM.
 *
 * Clasifica paquetes comunes agrupados por categorías:
 *  1. Navegadores Web
 *  2. Tiendas de Apps Alternativas (vías de evasión)
 *  3. Redes Sociales y Comunicación Abierta
 *  4. Streaming y Entretenimiento
 *  5. Asistentes de IA con Navegación Abierta
 *  6. Feeds y Bloatware OEM (Samsung, Xiaomi, Motorola, Tecno/Infinix)
 *  7. Juegos con navegación/publicidad abierta
 */
object PopularNonKosherApps {

    val PACKAGES: Set<String> = setOf(
        // ── 1. Navegadores Web ──
        "com.android.chrome",                          // Google Chrome
        "com.sec.android.app.sbrowser",                // Samsung Internet
        "com.mi.globalbrowser",                        // Xiaomi Mi Browser
        "com.android.browser",                         // Navegador AOSP / Mi Browser alternativo
        "org.mozilla.firefox",                         // Firefox
        "org.mozilla.focus",                           // Firefox Focus
        "com.microsoft.emmx",                          // Microsoft Edge
        "com.opera.browser",                           // Opera
        "com.opera.mini.native",                       // Opera Mini
        "com.opera.gx",                                // Opera GX
        "com.brave.browser",                           // Brave
        "com.duckduckgo.mobile.android",               // DuckDuckGo
        "com.kiwibrowser.browser",                     // Kiwi Browser
        "com.UCMobile.intl",                           // UC Browser
        "com.huawei.browser",                          // Huawei Browser
        "com.vivo.browser",                            // Vivo Browser
        "com.heytap.browser",                          // Oppo / Realme Heytap Browser
        "com.coloros.browser",                         // ColorOS Browser
        "com.google.android.googlequicksearchbox",     // Google App / Asistente / Lens
        "com.google.android.apps.searchlite",          // Google Go

        // ── 2. Tiendas de Apps Alternativas (Vías de evasión) ──
        "com.sec.android.app.samsungapps",             // Galaxy Store
        "com.xiaomi.mipicks",                          // Xiaomi GetApps
        "com.huawei.appmarket",                        // Huawei AppGallery
        "com.heytap.market",                           // Oppo / Realme Market
        "com.oppo.market",                             // Oppo Market alternativo
        "com.vivo.appstore",                           // Vivo V-Appstore
        "com.transsion.palmsore",                      // Palm Store (Tecno / Infinix)
        "com.amazon.venezia",                          // Amazon Appstore
        "com.aurora.store",                            // Aurora Store
        "org.fdroid.fdroid",                           // F-Droid
        "com.apkpure.aegon",                           // APKPure
        "cm.aptoide.pt",                               // Aptoide
        "com.uptodown",                                // Uptodown

        // ── 3. Redes Sociales y Comunicación Abierta ──
        "com.facebook.katana",                         // Facebook
        "com.facebook.lite",                           // Facebook Lite
        "com.facebook.orca",                           // Facebook Messenger
        "com.facebook.mlite",                          // Messenger Lite
        "com.instagram.android",                       // Instagram
        "com.instagram.barcelona",                     // Threads
        "com.zhiliaoapp.musically",                    // TikTok
        "com.zhiliaoapp.musically.go",                 // TikTok Lite
        "com.twitter.android",                         // X (Twitter)
        "com.twitter.android.lite",                    // Twitter Lite
        "com.snapchat.android",                        // Snapchat
        "com.reddit.frontpage",                        // Reddit
        "com.discord",                                 // Discord
        "org.telegram.messenger",                      // Telegram
        "org.telegram.plus",                           // Telegram Plus
        "com.tinder",                                  // Tinder
        "com.badoo.mobile",                            // Badoo
        "com.bumble.app",                              // Bumble
        "com.linkedin.android",                        // LinkedIn

        // ── 4. Streaming y Entretenimiento ──
        "com.google.android.youtube",                  // YouTube
        "com.google.android.apps.youtube.kids",        // YouTube Kids
        "com.google.android.apps.youtube.music",       // YouTube Music
        "com.spotify.music",                           // Spotify
        "com.google.android.videos",                   // Google TV / Películas
        "com.netflix.mediaclient",                     // Netflix
        "com.disney.disneyplus",                       // Disney+
        "com.amazon.avod.thirdpartyclient",            // Amazon Prime Video
        "com.wbd.stream",                              // Max (HBO Max)
        "tv.twitch.android.app",                       // Twitch
        "com.samsung.android.tvplus",                  // Samsung TV Plus

        // ── 5. Asistentes de IA con Conexión Abierta ──
        "com.openai.chatgpt",                          // ChatGPT
        "com.microsoft.copilot",                       // Microsoft Copilot
        "com.google.android.apps.bard",                // Google Gemini
        "ai.perplexity.app.android",                   // Perplexity AI

        // ── 6. Feeds y Bloatware por Marca ──
        // Samsung:
        "com.samsung.android.game.gamehome",           // Samsung Gaming Hub
        "com.samsung.android.app.spage",               // Samsung Free / O Daily
        "com.samsung.sree",                            // Samsung Global Goals (publicidad en bloqueo)
        "com.sec.android.app.kidshome",                // Samsung Kids (navegador interno)
        "com.samsung.android.kidsinstaller",           // Samsung Kids Installer
        "com.samsung.android.voc",                     // Samsung Members (comunidad y foros)
        "com.samsung.android.oneconnect",              // SmartThings
        "com.sec.android.app.shealth",                 // Samsung Health
        "com.sec.android.easyMover",                   // Samsung Smart Switch
        "com.samsung.android.smartswitchassistant",    // Smart Switch Asistente
        "com.rsupport.rs.activity.rsupport.aas2",      // Smart Tutor / Asistencia remota
        "com.samsung.android.app.find",                // Samsung Find
        "com.samsung.android.app.notes",               // Samsung Notes
        "com.samsung.android.app.watchmanager",        // Galaxy Wearable
        "com.samsung.android.app.watchmanagerstub",    // Galaxy Wearable stub
        "com.samsung.accessory.budsman",               // Galaxy Buds Manager
        "com.samsung.accessory.neobeanmgr",            // Galaxy Buds Live Manager
        "com.samsung.accessory.berrymgr",              // Galaxy Buds Pro Manager
        "com.sec.android.daemonapp",                   // Samsung Weather / Clima
        "com.samsung.android.scloud",                  // Samsung Cloud
        "com.samsung.android.app.routines",            // Modos y Rutinas
        "com.samsung.android.forest",                  // Samsung Digital Wellbeing
        "com.sec.android.app.fm",                      // Radio FM
        "com.samsung.android.game.gos",                // Game Optimizing Service (GOS)
        "com.samsung.android.stickercenter",           // Sticker Center
        "com.samsung.android.dynamiclock",             // Dynamic Lock
        "com.sec.android.app.billing",                 // Samsung Checkout
        "com.samsung.android.rubin.app",               // Customization Service (Rubin)
        "com.facebook.appmanager",                     // Meta App Manager
        "com.facebook.system",                         // Meta System
        "com.facebook.services",                       // Meta Services
        // Xiaomi / Redmi / POCO:
        "com.miui.videoplayer",                        // Mi Video con feed online
        "com.mi.globalminusscreen",                    // Bóveda de apps y noticias
        "com.miui.android.fashiongallery",             // Glance / Carrusel de fondos con noticias
        "com.xiaomi.glgm",                             // Game Center
        // Motorola:
        "com.glance.internet",                         // Noticias en pantalla de bloqueo
        "com.motorola.gamemode",                       // Moto Game Time
        // Tecno / Infinix (Transsion):
        "com.transsion.ahagames",                      // AHA Games
        "com.transsion.news",                          // Scooper News
        "com.afmobi.boomplayer",                       // Boomplay Music

        // ── 7. Juegos con Publicidad / Navegación Web Abierta ──
        "com.bitmango.go.bubblepop"                    // Bubble Pop
    )

    /**
     * Devuelve true si el paquete pertenece al catálogo de aplicaciones populares no kosher.
     */
    fun isPopularNonKosher(packageName: String): Boolean {
        return PACKAGES.contains(packageName)
    }
}
