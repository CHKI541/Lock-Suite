// ARCHIVO GENERADO — NO EDITAR A MANO.
//
// Lo genera `tools/gen_catalog_js.py` desde
// `app/src/main/java/com/ejemplo/locksuite/mdm/WhitelistCatalog.kt`, que es la
// fuente de verdad: el Kotlin es el que decide qué dominio resuelve en el celular.
// Esta copia existe solo para que el panel pueda DIBUJAR la lista y ofrecer
// corregirla; las correcciones se guardan en `globalSettings/whitelist/custom`
// (campos `allow`, `block` y `unblock`) y NUNCA acá.
//
// Para regenerarlo:  python tools/gen_catalog_js.py
// Para verificarlo:  python tools/gen_catalog_js.py --check
window.LOCKSUITE_CATALOG = {
  "apps": [
    {
      "pkg": "com.waze",
      "label": "Waze",
      "allow": [
        "waze.com",
        "wazestatic.com",
        "waze-cdn.com"
      ],
      "block": [
        "support.waze.com",
        "help.waze.com",
        "forum.waze.com",
        "blog.waze.com",
        "careers.waze.com"
      ],
      "note": "Los mapas salen por googleapis.com/gstatic.com (infraestructura)."
    },
    {
      "pkg": "com.didiglobal.passenger",
      "label": "DiDi",
      "allow": [
        "didiglobal.com",
        "xiaojukeji.com",
        "diditaxi.com.cn",
        "didistatic.com"
      ],
      "block": [
        "game.diditaxi.com.cn",
        "minigame.didiglobal.com",
        "play.didiglobal.com",
        "drama.didiglobal.com",
        "webcast.didiglobal.com",
        "live.didiglobal.com"
      ],
      "note": "DiDi mete juegos, streaming y video dentro de la app: por eso los 6 bloqueos."
    },
    {
      "pkg": "com.mercadopago.wallet",
      "label": "Mercado Pago",
      "allow": [
        "mercadopago.com",
        "mercadopago.com.ar",
        "mercadolibre.com",
        "mercadolibre.com.ar",
        "mlstatic.com"
      ],
      "block": [
        "click1.mercadolibre.com",
        "click1.mercadolibre.com.ar",
        "listado.mercadolibre.com",
        "listado.mercadolibre.com.ar",
        "mobile.mercadolibre.com",
        "mobile.mercadolibre.com.ar",
        "snoopy.mercadolibre.com",
        "snoopy.mercadolibre.com.ar",
        "www.mercadolibre.com",
        "www.mercadolibre.com.ar"
      ],
      "note": "PAGOS: probar una transferencia real en simulación antes de pasar a estricto."
    },
    {
      "pkg": "com.google.android.apps.walletnfcrel",
      "label": "Google Wallet / Pay",
      "allow": [
        "pay.google.com",
        "wallet.google.com",
        "payments.google.com",
        "paymentsresources.googleapis.com"
      ],
      "block": [],
      "note": "El pago NFC en sí no usa DNS; lo que resuelve es el alta de la tarjeta."
    },
    {
      "pkg": "ar.com.personalpay",
      "label": "Personal Pay",
      "allow": [
        "personalpay.com.ar",
        "personal.com.ar",
        "telecom.com.ar",
        "personalpay.com"
      ],
      "block": [],
      "note": "Sin fuente pública de sus dominios de backend: ES DE LAS QUE MÁS NECESITA LA SIMULACIÓN."
    },
    {
      "pkg": "com.google.android.gm",
      "label": "Gmail",
      "allow": [
        "mail.google.com",
        "gmail.com",
        "inbox.google.com"
      ],
      "block": [],
      "note": "Las imágenes remotas de los correos vienen de dominios de terceros: con la lista blanca estricta no van a cargar. Es el comportamiento deseado, pero conviene saberlo."
    },
    {
      "pkg": "com.google.android.apps.dynamite",
      "label": "Google Chat",
      "allow": [
        "chat.google.com"
      ],
      "block": [
        "tenor.googleapis.com",
        "tenor.com",
        "giphy.com"
      ],
      "note": "Chat tiene selector de GIF propio: por eso los bloqueos aunque esté permitida."
    },
    {
      "pkg": "com.google.android.apps.messaging",
      "label": "Google Messages",
      "allow": [
        "messages.google.com",
        "jibe.google.com",
        "instantmessaging-pa.googleapis.com"
      ],
      "block": [
        "tenor.googleapis.com",
        "tenor.com",
        "media.tenor.com",
        "giphy.com",
        "gboard-stickers.googleapis.com"
      ],
      "note": "RCS pasa por jibe.google.com; si los mensajes quedan en 'enviando', mirar ahí primero."
    },
    {
      "pkg": "com.google.android.apps.docs",
      "label": "Google Drive",
      "allow": [
        "drive.google.com",
        "docs.google.com",
        "drive.usercontent.google.com"
      ],
      "block": [],
      "note": "Los archivos se descargan de googleusercontent.com (infraestructura)."
    },
    {
      "pkg": "com.ubercab",
      "label": "Uber",
      "allow": [
        "uber.com",
        "uber-assets.com",
        "uberinternal.com"
      ],
      "block": [],
      "note": "Uber sirve buena parte de sus recursos desde CloudFront: si con la lista estricta no carga, es el grupo 'CDN compartidos'."
    },
    {
      "pkg": "com.google.android.apps.adm",
      "label": "Localizador de dispositivos",
      "allow": [
        "androiddeviceregistration-pa.googleapis.com"
      ],
      "block": [],
      "note": "A propósito NO se permite google.com/android/find: esa es la interfaz WEB, o sea un navegador. La app usa googleapis.com."
    },
    {
      "pkg": "life.channel.accurate.local.weather.forecast",
      "label": "Centro de información / Clima",
      "allow": [
        "koshersystem.com",
        "hm-news.co.il",
        "lomdaat.co.il"
      ],
      "block": [],
      "note": "⚠️ El paquete que pasaste es 'מרכז המידע' (Centro de información) de Lomdaat, no una app de clima genérica. Su API es news-api.koshersystem.com. Verificá que sea la que querías."
    },
    {
      "pkg": "com.lionscribe.hebdate",
      "label": "HebDate (calendario hebreo)",
      "allow": [
        "lionscribe.com"
      ],
      "block": [],
      "note": "Calendario mayormente offline: es probable que no necesite ningún dominio."
    },
    {
      "pkg": "fm.jewishmusic.application",
      "label": "Zing Music",
      "allow": [
        "zingmusic.app",
        "jewishmusic.fm"
      ],
      "block": [],
      "note": "El streaming del audio puede salir por un CDN: si reproduce cero segundos, mirar la auditoría."
    },
    {
      "pkg": "com.lomdaat.apps.music",
      "label": "Jusic",
      "allow": [
        "jusic.app",
        "jusic.co",
        "lomdaat.co.il",
        "koshersystem.com"
      ],
      "block": [],
      "note": "Mismo desarrollador que el Centro de información (Lomdaat): comparten infraestructura."
    },
    {
      "pkg": "com.whatsapp",
      "label": "WhatsApp",
      "allow": [
        "whatsapp.com",
        "whatsapp.net",
        "wa.me",
        "fbcdn.net"
      ],
      "block": [],
      "note": "Estados y Canales se bloquean por Capa 3 (accesibilidad), no por DNS: comparten los mismos dominios que los chats."
    },
    {
      "pkg": "com.google.android.apps.tachyon",
      "label": "Google Meet",
      "allow": [
        "meet.google.com",
        "tachyon.googleapis.com"
      ],
      "block": [],
      "note": "El audio/video va por UDP directo a IP de Google, sin DNS: si la llamada conecta y no hay imagen, no es este filtro."
    },
    {
      "pkg": "com.google.android.apps.meetings",
      "label": "Google Meet (app aparte)",
      "allow": [
        "meet.google.com",
        "tachyon.googleapis.com"
      ],
      "block": [],
      "note": "Google tiene dos paquetes distintos según el equipo: se permiten los dos."
    },
    {
      "pkg": "com.beatmobile.ak",
      "label": "Ajdut Kosher",
      "allow": [
        "kosher.org.ar",
        "beatmobile.com.ar"
      ],
      "block": [],
      "note": "Desarrollador Beatmobile; el sitio de la certificación es kosher.org.ar. Confirmar con la simulación."
    },
    {
      "pkg": "com.tranzmate",
      "label": "Moovit",
      "allow": [
        "moovitapp.com",
        "moovit.com",
        "tranzmate.com"
      ],
      "block": [],
      "note": "Usa CDN compartidos para los mapas de líneas."
    },
    {
      "pkg": "com.google.android.apps.chromecast.app",
      "label": "Google Home",
      "allow": [
        "home.google.com",
        "googlecast.com"
      ],
      "block": [],
      "note": "El control de los dispositivos es local (mDNS), no pasa por este filtro."
    },
    {
      "pkg": "com.tuya.smartlife",
      "label": "Smart Life (Tuya)",
      "allow": [
        "tuya.com",
        "tuyaus.com",
        "tuyaeu.com",
        "tuyacn.com",
        "tuyain.com",
        "smart-life.com"
      ],
      "block": [],
      "note": "Tuya asigna región por cuenta: en Argentina cae en tuyaus.com, pero se permiten las cuatro regiones para no romper cuentas viejas."
    },
    {
      "pkg": "com.google.android.apps.translate",
      "label": "Traductor de Google",
      "allow": [
        "translate.googleapis.com"
      ],
      "block": [
        "translate.google.com"
      ],
      "note": "translate.google.com queda BLOQUEADO a propósito: es un proxy de navegación. La app usa translate.googleapis.com y funciona igual."
    },
    {
      "pkg": "com.google.android.keep",
      "label": "Google Keep",
      "allow": [
        "keep.google.com"
      ],
      "block": [],
      "note": ""
    },
    {
      "pkg": "com.google.android.contacts",
      "label": "Contactos de Google",
      "allow": [
        "contacts.google.com",
        "people-pa.googleapis.com"
      ],
      "block": [],
      "note": "El selector de foto de contacto ya tiene su propio bloqueo (B.47)."
    },
    {
      "pkg": "com.google.android.calendar",
      "label": "Calendario de Google",
      "allow": [
        "calendar.google.com"
      ],
      "block": [],
      "note": ""
    }
  ],
  "infrastructure": [
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
    "googleapis.com",
    "gstatic.com",
    "connectivitycheck.gstatic.com",
    "connectivitycheck.android.com",
    "clients3.google.com",
    "clients4.google.com",
    "time.google.com",
    "time.android.com",
    "ntp.org",
    "pki.goog",
    "lencr.org",
    "digicert.com",
    "amazontrust.com",
    "sectigo.com",
    "globalsign.com",
    "locksuite-nueva.web.app",
    "locksuite-nueva.firebaseapp.com",
    "firebaseio.com",
    "firebasedatabase.app",
    "cloudfunctions.net",
    "firebaseinstallations.googleapis.com",
    "identitytoolkit.googleapis.com",
    "securetoken.googleapis.com",
    "ospserver.net",
    "samsungdm.com"
  ],
  "blockAlways": [
    "tenor.googleapis.com",
    "tenor.com",
    "giphy.com",
    "gboard-stickers.googleapis.com",
    "youtubei.googleapis.com",
    "youtube.googleapis.com",
    "discover.google.com"
  ],
  "sharedCdn": [
    "cloudfront.net",
    "akamaized.net",
    "akamaihd.net",
    "akamai.net",
    "fastly.net",
    "cloudflare.com",
    "cdn77.org",
    "bunnycdn.com",
    "b-cdn.net"
  ],
  "neverBlock": [
    "com.ejemplo.locksuite",
    "com.android.vending",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.android.settings",
    "com.android.phone",
    "com.android.dialer",
    "com.google.android.dialer",
    "com.android.systemui",
    "com.android.providers.telephony",
    "com.android.captiveportallogin"
  ]
};
