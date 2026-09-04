const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const crypto = require("crypto");

const FUNCTION_OPTIONS = {
  region: "us-central1",
  cors: true,
  invoker: "public",
};

admin.initializeApp({
  databaseURL: "https://locksuite-nueva-default-rtdb.firebaseio.com"
});

// Force deploy timestamp: 2026-07-15T00:15:00Z
// CORS headers para todas las respuestas
const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

// Lista blanca de comandos válidos
const ALLOWED_COMMANDS = new Set([
  "LOCK_DEVICE", "BLOCK_INSTALL_APPS", "UNBLOCK_INSTALL_APPS",
  "BLOCK_UNINSTALL_APPS", "UNBLOCK_UNINSTALL_APPS", "BLOCK_FACTORY_RESET",
  "UNBLOCK_FACTORY_RESET", "BLOCK_ADB", "UNBLOCK_ADB", "BLOCK_USER_SWITCH",
  "UNBLOCK_USER_SWITCH", "BLOCK_MODIFY_ACCOUNTS", "UNBLOCK_MODIFY_ACCOUNTS",
  "BLOCK_SAFE_BOOT", "UNBLOCK_SAFE_BOOT", "BLOCK_UNKNOWN_SOURCES",
  "UNBLOCK_UNKNOWN_SOURCES", "BLOCK_VOLUME", "UNBLOCK_VOLUME",
  "BLOCK_APPS_CONTROL", "UNBLOCK_APPS_CONTROL", "BLOCK_BLUETOOTH_SHARING",
  "UNBLOCK_BLUETOOTH_SHARING", "BLOCK_EXTERNAL_MEDIA", "UNBLOCK_EXTERNAL_MEDIA",
  "BLOCK_TETHERING", "UNBLOCK_TETHERING", "BLOCK_WIFI", "UNBLOCK_WIFI",
  "BLOCK_BLUETOOTH", "UNBLOCK_BLUETOOTH", "BLOCK_VPN", "UNBLOCK_VPN",
  "DISABLE_CAMERA", "ENABLE_CAMERA", "BLOCK_SCREEN_CAPTURE",
  "UNBLOCK_SCREEN_CAPTURE", "DISABLE_STATUSBAR", "ENABLE_STATUSBAR",
  "DISABLE_KEYGUARD", "ENABLE_KEYGUARD", "BLOCK_INTERNET", "UNBLOCK_INTERNET",
  "ENABLE_ADBLOCK", "DISABLE_ADBLOCK", "HIDE_APP", "UNHIDE_APP",
  "SUSPEND_APP", "UNSUSPEND_APP", "BLOCK_WEBVIEW", "UNBLOCK_WEBVIEW",
  "UPDATE_ALLOWLIST", "SET_IMAGE_BLOCK_NONE", "SET_IMAGE_BLOCK_LAYER_1",
  "SET_IMAGE_BLOCK_LAYER_2", "SET_IMAGE_BLOCK_BOTH", "ENABLE_AI_MODE",
  "DISABLE_AI_MODE", "ENABLE_MAPS_IMAGE_BLOCKING", "DISABLE_MAPS_IMAGE_BLOCKING",
  "BLOCK_WHATSAPP_STATUS", "UNBLOCK_WHATSAPP_STATUS", "BLOCK_WHATSAPP_CHANNELS",
  "UNBLOCK_WHATSAPP_CHANNELS", "CHANGE_PIN", "ENABLE_STEALTH", "DISABLE_STEALTH",
  "BLOCK_GIFS", "UNBLOCK_GIFS", "UPDATE_APP", "UPDATE_LOCKSUITE", "VERIFY_PIN",
  "BLOCK_APP_INTERNET", "UNBLOCK_APP_INTERNET", "UNSUSPEND_ALL_APPS",
  "SET_HIDE_SUSPENDED_APPS", "APPLY_PRESET_PROFILE",
  "BLOCK_MP_OFFERS_ACCESSIBILITY", "UNBLOCK_MP_OFFERS_ACCESSIBILITY",
  "BLOCK_MP_OFFERS_VPN", "UNBLOCK_MP_OFFERS_VPN",
  "BLOCK_MERCADOPAGO_OFFERS", "UNBLOCK_MERCADOPAGO_OFFERS",
  "BLOCK_ML_IN_MP", "UNBLOCK_ML_IN_MP",
  "BLOCK_FLASHING", "UNBLOCK_FLASHING",
  "ENABLE_KOSHER_LAUNCHER", "DISABLE_KOSHER_LAUNCHER",
  // Cancelar una actualizacion de app en curso (contraparte de UPDATE_APP).
  "CANCEL_UPDATE_APP",
  // Suspension temporal de LockSuite: levanta TODAS las restricciones del
  // equipo y desbloquea todas las apps; al reanudar, PolicyManager reconstruye
  // el estado desde las preferencias guardadas. Exige PIN del dispositivo (no
  // esta en la excepcion de UPDATE_*), porque deja el equipo sin proteccion.
  "SUSPEND_LOCKSUITE", "RESUME_LOCKSUITE",
  "PROTECT_ACCESSIBILITY", "UNPROTECT_ACCESSIBILITY",
  // Sub-interruptores de "Protecciones de Accesibilidad" (17/8/2026). Todos
  // exigen PIN del dispositivo: NO estan en la excepcion de UPDATE_* de mas
  // abajo. Cambian el comportamiento del filtro visual, asi que un operador del
  // panel no deberia poder aflojarlos sin conocer el PIN del equipo.
  // Bloquear el cambio de idioma del sistema (DISALLOW_CONFIG_LOCALE). Es la
  // defensa mas barata contra la evasion por idioma: cualquier filtro que compare
  // texto de pantalla queda mudo si el equipo cambia a un idioma no previsto.
  "BLOCK_LOCALE_CHANGE", "UNBLOCK_LOCALE_CHANGE",
  // Ajustes/actividad de la cuenta de Google (4/9/2026). Cierra el camino
  // Ajustes -> Google -> Datos y privacidad -> Historial de YouTube, que mostraba
  // los videos vistos dentro de Ajustes, sin ningun navegador de por medio.
  "BLOCK_GOOGLE_ACCOUNT_WEB", "UNBLOCK_GOOGLE_ACCOUNT_WEB",
  // Modo del bloqueo de la cuenta: normal (deja administrarla, bloquea el
  // historial por dominio) o estricto (no abre ninguna pantalla de la cuenta).
  "SET_GOOGLE_ACCOUNT_MODE_STRICT", "SET_GOOGLE_ACCOUNT_MODE_NORMAL",
  // Selector de foto de contactos / Google Illustrations (4/9/2026)
  "BLOCK_CONTACT_PHOTO_PICKER", "UNBLOCK_CONTACT_PHOTO_PICKER",
  "ENABLE_ACC_BOUNCE_SETTINGS", "DISABLE_ACC_BOUNCE_SETTINGS",
  "ENABLE_ACC_NAG", "DISABLE_ACC_NAG",
  "ENABLE_ACC_SUSPEND_ALL", "DISABLE_ACC_SUSPEND_ALL",
  "ENABLE_BOOT_GATE_ACCESSIBILITY", "DISABLE_BOOT_GATE_ACCESSIBILITY",
  // Arranque protegido: cierra la red al bootear hasta que el filtro DNS esta
  // realmente funcionando (ver app/util/BootGate.kt).
  "ENABLE_BOOT_GATE", "DISABLE_BOOT_GATE",
  // Bloqueo de imagenes: tapado estricto del contenedor mientras se desplaza.
  "ENABLE_IMAGE_STRICT_SCROLL", "DISABLE_IMAGE_STRICT_SCROLL",
  // Kiosco real del sistema operativo (Lock Task). El equipo SOLO puede abrir los
  // paquetes de la lista blanca del launcher, y lo hace cumplir Android, no LockSuite.
  // OJO: si el marcador telefonico no esta en esa lista, el codigo de recuperacion
  // *#*#9999#*#* no se puede marcar. Ver PolicyManager.applyKioskLockTask().
  "ENABLE_KIOSK_LOCK_TASK", "DISABLE_KIOSK_LOCK_TASK",
  // Modo telefono de teclas (estilo Nokia) y su interruptor de tactil.
  "ENABLE_NOKIA_MODE", "DISABLE_NOKIA_MODE",
  "ENABLE_NOKIA_TOUCH", "DISABLE_NOKIA_TOUCH",
  // ── Restricciones del registro declarativo (app/mdm/PolicySpec.kt) ──
  // Esta lista se genera desde ese archivo: si agregas una restriccion alla, agregala
  // aca tambien o la Cloud Function la va a rechazar con "Comando no reconocido".
  "BLOCK_PRIVATE_DNS", "UNBLOCK_PRIVATE_DNS",
  "BLOCK_SMS", "UNBLOCK_SMS",
  "BLOCK_OUTGOING_CALLS", "UNBLOCK_OUTGOING_CALLS",
  "BLOCK_CONFIG_LOCATION", "UNBLOCK_CONFIG_LOCATION",
  "BLOCK_SHARE_LOCATION", "UNBLOCK_SHARE_LOCATION",
  "BLOCK_AUTOFILL", "UNBLOCK_AUTOFILL",
  "BLOCK_CONTENT_CAPTURE", "UNBLOCK_CONTENT_CAPTURE",
  "BLOCK_PRINTING", "UNBLOCK_PRINTING",
  "BLOCK_USB_FILE_TRANSFER", "UNBLOCK_USB_FILE_TRANSFER",
  "BLOCK_DATA_ROAMING", "UNBLOCK_DATA_ROAMING",
  "BLOCK_AIRPLANE_MODE", "UNBLOCK_AIRPLANE_MODE",
  "BLOCK_AMBIENT_DISPLAY", "UNBLOCK_AMBIENT_DISPLAY",
  "BLOCK_SYSTEM_ERROR_DIALOGS", "UNBLOCK_SYSTEM_ERROR_DIALOGS",
  "BLOCK_SET_WALLPAPER", "UNBLOCK_SET_WALLPAPER",
  "BLOCK_SET_USER_ICON", "UNBLOCK_SET_USER_ICON",
  "BLOCK_CONFIG_CREDENTIALS", "UNBLOCK_CONFIG_CREDENTIALS",
  "BLOCK_CONFIG_CELL_BROADCASTS", "UNBLOCK_CONFIG_CELL_BROADCASTS",
  "BLOCK_OUTGOING_BEAM", "UNBLOCK_OUTGOING_BEAM",
  "BLOCK_UNMUTE_MICROPHONE", "UNBLOCK_UNMUTE_MICROPHONE",
  "BLOCK_REMOVE_MANAGED_PROFILE", "UNBLOCK_REMOVE_MANAGED_PROFILE",
]);

function canonicalCommandPayload(payload) {
  return Object.keys(payload)
    .filter((key) => key !== "signature")
    .sort()
    .map((key) => `${key}=${payload[key] ?? ""}`)
    .join("\n");
}

// Helper para verificar admin por UID (fuente de verdad alineada con database.rules.json)
async function checkAdminByUid(uid) {
  if (!uid) throw { status: 403, message: "Acceso denegado: UID inválido." };
  const snap = await admin.database().ref(`authorizedAdminsUids/${uid}`).once("value");
  if (!snap.exists() || snap.val() !== true) {
    throw { status: 403, message: "Acceso denegado: administrador no autorizado." };
  }
}

// Helper para leer campo con fallback a info.X
function getDeviceField(device, field, fallback = null) {
  if (device && device[field] !== undefined && device[field] !== null) return device[field];
  if (device && device.info && device.info[field] !== undefined && device.info[field] !== null) return device.info[field];
  return fallback;
}

// Detecta PINs triviales, con la MISMA regla que PinManager.isTrivialPin() en la app:
// todos los dígitos iguales, o una secuencia ascendente/descendente de dígitos
// consecutivos ("1234", "9876"). Existe porque la app rechaza estos PINs al
// configurarlos y al cambiarlos desde el Dashboard, pero la vía remota (comando
// CHANGE_PIN) se salteaba esa validación por completo: el celular recibe el hash ya
// calculado, así que no tiene con qué juzgar la fuerza del PIN. Sin esta comprobación
// del lado del servidor, un PIN fuerte se podía debilitar a "1234" desde el panel.
function isTrivialPin(pin) {
  if (!pin || pin.length === 0) return true;
  if ([...pin].every((c) => c === pin[0])) return true;

  let ascending = true;
  for (let i = 0; i < pin.length - 1; i++) {
    if (pin.charCodeAt(i + 1) - pin.charCodeAt(i) !== 1) { ascending = false; break; }
  }
  if (ascending) return true;

  let descending = true;
  for (let i = 0; i < pin.length - 1; i++) {
    if (pin.charCodeAt(i) - pin.charCodeAt(i + 1) !== 1) { descending = false; break; }
  }
  if (descending) return true;

  return false;
}

// Hashear PIN igual que PinManager.kt
function hashPin(pin, saltBase64) {
  const saltBytes = Buffer.from(saltBase64, "base64");
  const hash = crypto.createHash("sha256");
  hash.update(saltBytes);
  hash.update(Buffer.from(pin, "utf8"));
  return hash.digest("base64");
}

// Verificar PIN del dispositivo
async function verifyDevicePin(deviceId, deviceRef, deviceData, adminUid, devicePin, rememberDevice) {
  const trustedAdmins = getDeviceField(deviceData, "trustedAdmins", {});
  if (trustedAdmins[adminUid] === true) return; // ya confiable

  const secretsSnap = await admin.database().ref(`deviceSecrets/${deviceId}`).once("value");
  const secrets = secretsSnap.val() || {};
  let pinHash = secrets.pinHash || getDeviceField(deviceData, "pinHash");
  let pinSalt = secrets.pinSalt || getDeviceField(deviceData, "pinSalt");

  if (!pinHash || !pinSalt) throw { status: 412, message: "DEVICE_PIN_NOT_ENROLLED" };
  if (!devicePin) throw { status: 412, message: "PIN_REQUIRED" };

  const computed = hashPin(devicePin, pinSalt);
  if (computed !== pinHash) throw { status: 403, message: "PIN_INCORRECT" };

  if (rememberDevice) {
    await deviceRef.child(`trustedAdmins/${adminUid}`).set(true);
    await deviceRef.child(`info/trustedAdmins/${adminUid}`).set(true).catch(() => {});
  }
}

exports.sendCommandV8 = onRequest(FUNCTION_OPTIONS, async (req, res) => {
  // CORS headers siempre
  Object.entries(CORS_HEADERS).forEach(([k, v]) => res.set(k, v));

  // Responder preflight inmediatamente
  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }

  if (req.method !== "POST") {
    res.status(405).json({ error: "Método no permitido" });
    return;
  }

  try {
    // Verificar Firebase ID token
    const authHeader = req.headers.authorization || "";
    if (!authHeader.startsWith("Bearer ")) {
      res.status(401).json({ error: "No autorizado: falta token de autenticación." });
      return;
    }
    const idToken = authHeader.slice(7);
    const decoded = await admin.auth().verifyIdToken(idToken);
    const adminUid = decoded.uid;
    const adminEmail = decoded.email;

    // Verificar que es admin autorizado por UID (fuente de verdad alineada con database.rules.json)
    await checkAdminByUid(adminUid);

    const {
      deviceId, command, packages, devicePin, rememberDevice, newPin,
      enabled, presetJson
    } = req.body || {};

    if (!deviceId || typeof deviceId !== "string") {
      res.status(400).json({ error: "Falta deviceId." });
      return;
    }
    if (!ALLOWED_COMMANDS.has(command)) {
      res.status(400).json({ error: `Comando no reconocido: ${command}` });
      return;
    }

    const deviceRef = admin.database().ref(`devices/${deviceId}`);
    const deviceSnap = await deviceRef.once("value");
    const deviceData = deviceSnap.val();
    if (!deviceData) {
      res.status(404).json({ error: "Dispositivo no registrado." });
      return;
    }

    // UPDATE_APP y UPDATE_LOCKSUITE quedan afuera de la exigencia de PIN por
    // dispositivo: son acciones de mantenimiento (actualizar una app puntual,
    // o LockSuite mismo) sin el mismo nivel de riesgo que bloquear el equipo,
    // cambiar el PIN o aplicar restricciones - pedir el PIN ahi era friccion
    // pura para un caso de uso muy frecuente.
    // CANCEL_UPDATE_APP entra en la misma excepcion que UPDATE_APP: es su
    // contraparte, y si para cancelar hiciera falta el PIN, un equipo con una
    // pantalla de actualizacion trabada no se podria destrabar desde el panel
    // — justo el caso en que mas urge poder hacerlo.
    if (command !== "UPDATE_LOCKSUITE" && command !== "UPDATE_APP" &&
        command !== "CANCEL_UPDATE_APP") {
      await verifyDevicePin(deviceId, deviceRef, deviceData, adminUid, devicePin, rememberDevice);
    }

    if (command === "VERIFY_PIN") {
      res.status(200).json({ success: true, verified: true });
      return;
    }

    const token = getDeviceField(deviceData, "fcmToken");
    if (!token) {
      res.status(404).json({ error: "El dispositivo no tiene FCM token." });
      return;
    }

    const commandId = crypto.randomUUID();
    const commandSecret = (await admin.database().ref(`deviceSecrets/${deviceId}/commandSecret`).once("value")).val();
    const hasCommandSecret = typeof commandSecret === "string" && commandSecret.length >= 32;
    // Migración controlada: un cliente anterior a 0.5.0 no puede verificar la
    // firma por dispositivo. Solo se le permite recibir la actualización; no
    // puede ejecutar ninguna política hasta volver a sincronizar ya actualizado.
    if (!hasCommandSecret && command !== "UPDATE_LOCKSUITE") {
      res.status(412).json({ error: "El dispositivo no tiene credencial de comandos. Actualiza y sincroniza LockSuite antes de administrarlo." });
      return;
    }
    const payload = { command, commandId };

    if (command === "CHANGE_PIN") {
      if (!newPin || !/^\d{4,16}$/.test(newPin)) {
        res.status(400).json({ error: "PIN inválido." });
        return;
      }
      if (isTrivialPin(newPin)) {
        res.status(400).json({
          error: "PIN demasiado débil: no uses dígitos repetidos ni secuencias como 1234 o 9876.",
        });
        return;
      }
      const pinSalt = crypto.randomBytes(16).toString("base64");
      const pinHash = hashPin(newPin, pinSalt);
      payload.pinHash = pinHash;
      payload.pinSalt = pinSalt;

      // No reemplazar el nodo completo: contiene la credencial de firma de
      // comandos del dispositivo, que debe sobrevivir a un cambio de PIN.
      await admin.database().ref(`deviceSecrets/${deviceId}`).update({ pinHash, pinSalt });
      await deviceRef.child("hasPinConfigured").set(true);
      await deviceRef.child("info/hasPinConfigured").set(true).catch(() => {});
      await deviceRef.child("pinHash").remove().catch(() => {});
      await deviceRef.child("pinSalt").remove().catch(() => {});
      await deviceRef.child("trustedAdmins").remove().catch(() => {});
      await deviceRef.child("info/trustedAdmins").remove().catch(() => {});
    } else if (command === "UPDATE_ALLOWLIST") {
      if (!Array.isArray(packages)) {
        res.status(400).json({ error: "Falta la lista de paquetes." });
        return;
      }
      const clean = packages.map(p => String(p).trim()).filter(p => /^[a-zA-Z0-9_.]+$/.test(p));
      payload.packages = clean.join(",");
      await deviceRef.child("allowedPackages").set(clean);
      await deviceRef.child("info/allowedPackages").set(clean).catch(() => {});
    } else if (command === "SET_HIDE_SUSPENDED_APPS") {
      if (typeof enabled !== "boolean") {
        res.status(400).json({ error: "Falta el valor enabled para la política." });
        return;
      }
      payload.enabled = String(enabled);
    } else if (command === "APPLY_PRESET_PROFILE") {
      if (typeof presetJson !== "string" || presetJson.length === 0) {
        res.status(400).json({ error: "Falta el perfil a aplicar." });
        return;
      }
      // El payload data de FCM tiene un límite estricto. Evitamos simular un
      // envío que FCM rechazaría; los perfiles grandes requieren la ruta de
      // sincronización dedicada que se revisará en la siguiente etapa.
      if (Buffer.byteLength(presetJson, "utf8") > 3000) {
        res.status(413).json({ error: "El perfil es demasiado grande para enviarlo de forma remota." });
        return;
      }
      payload.presetJson = presetJson;
    } else if (typeof packages === "string" && packages.trim().length > 0) {
      const clean = packages.split(",").map(p => p.trim()).filter(p => /^[a-zA-Z0-9_.]+$/.test(p));
      if (clean.length > 0) payload.packages = clean.join(",");
    } else if (Array.isArray(packages) && packages.length > 0) {
      const clean = packages.map(p => String(p).trim()).filter(p => /^[a-zA-Z0-9_.]+$/.test(p));
      if (clean.length > 0) payload.packages = clean.join(",");
    }

    if (hasCommandSecret) {
      payload.timestamp = String(Date.now());
      payload.signature = crypto
        .createHmac("sha256", commandSecret)
        .update(canonicalCommandPayload(payload), "utf8")
        .digest("base64");
    }

    await admin.messaging().send({
      token,
      data: payload,
      android: { priority: "high" },
    });

    await admin.database().ref(`commandLog/${deviceId}`).push({
      command, commandId,
      packages: payload.packages || null,
      sentBy: adminUid, sentAt: admin.database.ServerValue.TIMESTAMP,
    });

    const ackData = { status: "sent", command, timestamp: admin.database.ServerValue.TIMESTAMP };
    await deviceRef.child(`commandAcks/${commandId}`).set(ackData);
    await deviceRef.child(`info/commandAcks/${commandId}`).set(ackData).catch(() => {});

    res.json({ success: true, commandId });
  } catch (e) {
    console.error("sendCommandV3 error:", e);
    const status = e.status || 500;
    res.status(status).json({ error: e.message || "Error interno del servidor." });
  }
});

// API de Colectivos CABA (Cuando SUBO Proxy)
exports.colectivosApi = onRequest({ region: "us-central1", cors: true, invoker: "public" }, async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.set("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") {
    res.status(204).send("");
    return;
  }

  try {
    const action = req.query.action || req.body.action;
    if (!action) {
      res.status(400).json({ error: "Falta el parámetro action (buscarLinea, obtenerParadas, obtenerArribos)." });
      return;
    }

    if (action === "buscarLinea") {
      const query = req.query.query || req.body.query;
      if (!query) {
        res.status(400).json({ error: "Falta el parámetro query." });
        return;
      }
      const url = `https://cuandosubo.sube.gob.ar/onebusaway-webapp/where/iphone/routes.action?query=${encodeURIComponent(query)}`;
      const response = await fetch(url);
      if (!response.ok) throw new Error("Error consultando el servidor de lineas.");
      const html = await response.text();
      
      const routes = [];
      const liRegex = /<li><a href="[^"]*stops-for-route\.action[^"]*id=([^"&;\s]+)[^"]*"><span[^>]*>([^<]+)<\/span><span[^>]*>([^<]+)<\/span><\/a><\/li>/gi;
      let match;
      while ((match = liRegex.exec(html)) !== null) {
        routes.push({
          id: match[1],
          shortName: match[2].replace("-", "").trim(),
          longName: match[3].trim()
        });
      }
      res.status(200).json({ routes });

    } else if (action === "obtenerParadas") {
      const routeId = req.query.routeId || req.body.routeId;
      if (!routeId) {
        res.status(400).json({ error: "Falta el parámetro routeId." });
        return;
      }
      const url = `https://cuandosubo.sube.gob.ar/onebusaway-webapp/where/iphone/stops-for-route.action?id=${routeId}`;
      const response = await fetch(url);
      if (!response.ok) throw new Error("Error consultando el servidor de paradas.");
      const html = await response.text();
      
      const stops = [];
      const liRegex = /<li><a href="[^"]*stop\.action[^"]*id=([^"&;\s]+)[^"]*">([^<]+)<\/a><\/li>/gi;
      let match;
      while ((match = liRegex.exec(html)) !== null) {
        const name = match[2].trim();
        if (name.includes("Search by line") || name.includes("Nearby stops")) continue;
        stops.push({
          id: match[1],
          name: name
        });
      }
      res.status(200).json({ stops });

    } else if (action === "obtenerArribos") {
      const stopId = req.query.stopId || req.body.stopId;
      if (!stopId) {
        res.status(400).json({ error: "Falta el parámetro stopId." });
        return;
      }
      const url = `https://cuandosubo.sube.gob.ar/onebusaway-webapp/where/iphone/stop.action?id=${stopId}`;
      const response = await fetch(url);
      if (!response.ok) throw new Error("Error consultando el servidor de arribos.");
      const html = await response.text();
      
      const arrivals = [];
      const rowRegex = /<tr class="arrivalsRow">([\s\S]*?)<\/tr>/g;
      let match;
      while ((match = rowRegex.exec(html)) !== null) {
        const rowContent = match[1];
        
        const routeMatch = rowContent.match(/class="arrivalsRouteEntry"[^>]*><a[^>]*>([^<]+)<\/a>/i);
        const route = routeMatch ? routeMatch[1].trim() : "";
        
        const destMatch = rowContent.match(/class="arrivalsDestinationEntry"[^>]*><a[^>]*>([^<]+)<\/a>/i);
        const destination = destMatch ? destMatch[1].trim() : "";
        
        const timeEntryMatch = rowContent.match(/class="arrivalsTimeEntry"[^>]*>([^<]+)<\/span>/i);
        const arrivalTime = timeEntryMatch ? timeEntryMatch[1].trim() : "";
        
        const minutesMatch = rowContent.match(/class="arrivalsStatusEntry[\s\S]*?">([\s\S]*?)<\/td>/i);
        const minutes = minutesMatch ? minutesMatch[1].trim() : "";
        
        const isLive = !rowContent.includes("arrivalStatusNoInfo");
        
        arrivals.push({
          route,
          destination,
          arrivalTime,
          minutes: parseInt(minutes, 10) || 0,
          isLive
        });
      }
      res.status(200).json({ stopId, arrivals });
    } else {
      res.status(400).json({ error: "Acción no reconocida." });
    }
  } catch (err) {
    console.error("colectivosApi error:", err);
    res.status(500).json({ error: err.message || "Error interno." });
  }
});

