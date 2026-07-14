const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp({
  databaseURL: "https://locksuite-nueva-default-rtdb.firebaseio.com"
});

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
]);

// Helper para verificar admin por email
async function checkAdminByEmail(email) {
  if (!email) throw { status: 403, message: "Acceso denegado: correo inválido." };
  const emailKey = email.toLowerCase().replace(/[.@]/g, "_");
  const snap = await admin.database().ref(`authorizedAdmins/${emailKey}`).once("value");
  if (!snap.exists() || snap.val() !== true) {
    throw { status: 403, message: `Acceso denegado: ${email} no está autorizado.` };
  }
}

// Helper para leer campo con fallback a info.X
function getDeviceField(device, field, fallback = null) {
  if (device && device[field] !== undefined && device[field] !== null) return device[field];
  if (device && device.info && device.info[field] !== undefined && device.info[field] !== null) return device.info[field];
  return fallback;
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

const FUNCTION_OPTIONS = {
  region: "us-central1",
  invoker: "public",
};

/**
 * sendCommandV3 — Enviar comando a un dispositivo
 * HTTP POST { deviceId, command, packages?, devicePin?, rememberDevice?, newPin? }
 */
exports.sendCommandV4 = onRequest(FUNCTION_OPTIONS, async (req, res) => {
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

    // Verificar que es admin autorizado
    await checkAdminByEmail(adminEmail);

    const { deviceId, command, packages, devicePin, rememberDevice, newPin } = req.body || {};

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

    await verifyDevicePin(deviceId, deviceRef, deviceData, adminUid, devicePin, rememberDevice);

    const token = getDeviceField(deviceData, "fcmToken");
    if (!token) {
      res.status(404).json({ error: "El dispositivo no tiene FCM token." });
      return;
    }

    const commandId = crypto.randomUUID();
    const payload = { command, commandId };

    if (command === "CHANGE_PIN") {
      if (!newPin || !/^\d{4,16}$/.test(newPin)) {
        res.status(400).json({ error: "PIN inválido." });
        return;
      }
      const pinSalt = crypto.randomBytes(16).toString("base64");
      const pinHash = hashPin(newPin, pinSalt);
      payload.pinHash = pinHash;
      payload.pinSalt = pinSalt;

      await admin.database().ref(`deviceSecrets/${deviceId}`).set({ pinHash, pinSalt });
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
    } else if (Array.isArray(packages) && packages.length > 0) {
      const clean = packages.map(p => String(p).trim()).filter(p => /^[a-zA-Z0-9_.]+$/.test(p));
      if (clean.length > 0) payload.packages = clean.join(",");
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
