const { onCall, HttpsError } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();

// Lista blanca de comandos válidos — cualquier otro valor se rechaza.
const ALLOWED_COMMANDS = new Set([
  "LOCK_DEVICE",
  "BLOCK_INSTALL_APPS",
  "UNBLOCK_INSTALL_APPS",
  "BLOCK_WIFI",
  "UNBLOCK_WIFI",
  "BLOCK_BLUETOOTH",
  "UNBLOCK_BLUETOOTH",
  "BLOCK_VPN",
  "UNBLOCK_VPN",
  "UPDATE_ALLOWLIST",
]);

/**
 * Replica exactamente el esquema de PinManager.kt en Android:
 * SHA-256(salt_bytes + pin_utf8_bytes), codificado en Base64 estándar
 * (equivalente a Base64.NO_WRAP de Android — sin saltos de línea).
 */
function hashPinServerSide(pin, saltBase64) {
  const saltBytes = Buffer.from(saltBase64, "base64");
  const hash = crypto.createHash("sha256");
  hash.update(saltBytes);
  hash.update(Buffer.from(pin, "utf8"));
  return hash.digest("base64");
}

/**
 * Verifica que quien pide administrar ESTE dispositivo puntual conozca su
 * PIN de administrador (el mismo que se configuró en el celular), a menos
 * que ya haya quedado marcado como "de confianza" para este admin en una
 * verificación anterior (ver `rememberDevice`).
 *
 * Lanza HttpsError con mensaje "PIN_REQUIRED" o "PIN_INCORRECT" — el panel
 * web reconoce esos dos mensajes exactos para mostrar el modal de PIN.
 */
async function verifyDevicePinOrThrow(deviceRef, deviceData, adminUid, devicePin, rememberDevice) {
  const trustedAdmins = deviceData.trustedAdmins || {};
  if (trustedAdmins[adminUid] === true) {
    return; // Ya verificado antes para este admin, no se vuelve a pedir.
  }

  if (!deviceData.pinHash || !deviceData.pinSalt) {
    // El celular todavía no configuró un PIN local (recién instalado) —
    // no hay nada contra qué verificar, se deja pasar.
    return;
  }

  if (!devicePin) {
    throw new HttpsError("failed-precondition", "PIN_REQUIRED");
  }

  const computedHash = hashPinServerSide(devicePin, deviceData.pinSalt);
  if (computedHash !== deviceData.pinHash) {
    throw new HttpsError("permission-denied", "PIN_INCORRECT");
  }

  if (rememberDevice) {
    await deviceRef.child(`trustedAdmins/${adminUid}`).set(true);
  }
}

/**
 * Envía un comando a un dispositivo puntual. Requiere estar autenticado
 * (ver README) y, la primera vez por dispositivo, el PIN de administrador
 * configurado en ESE celular (a menos que se haya marcado "no preguntar de
 * nuevo" en una vez anterior).
 */
exports.sendCommand = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Necesitás iniciar sesión.");
  }

  const { deviceId, command, packages, devicePin, rememberDevice } = request.data || {};

  if (!deviceId || typeof deviceId !== "string") {
    throw new HttpsError("invalid-argument", "Falta deviceId.");
  }
  if (!ALLOWED_COMMANDS.has(command)) {
    throw new HttpsError("invalid-argument", `Comando no reconocido: ${command}`);
  }

  const deviceRef = admin.database().ref(`devices/${deviceId}`);
  const deviceSnap = await deviceRef.once("value");
  const deviceData = deviceSnap.val();

  if (!deviceData) {
    throw new HttpsError("not-found", "Ese dispositivo no está registrado.");
  }

  await verifyDevicePinOrThrow(deviceRef, deviceData, request.auth.uid, devicePin, rememberDevice);

  const token = deviceData.fcmToken;
  if (!token) {
    throw new HttpsError(
      "not-found",
      "Ese dispositivo no tiene un token FCM registrado todavía (¿tiene la app abierta al menos una vez con internet?)."
    );
  }

  const data = { command };

  if (command === "UPDATE_ALLOWLIST") {
    if (!Array.isArray(packages) || packages.length === 0) {
      throw new HttpsError("invalid-argument", "Falta la lista de paquetes permitidos.");
    }
    const cleanPackages = packages
      .map((p) => String(p).trim())
      .filter((p) => /^[a-zA-Z0-9_.]+$/.test(p));
    if (cleanPackages.length === 0) {
      throw new HttpsError("invalid-argument", "Ningún paquete tiene un formato válido.");
    }
    data.packages = cleanPackages.join(",");
  }

  await admin.messaging().send({
    token,
    data,
    android: { priority: "high" },
  });

  await admin.database().ref(`commandLog/${deviceId}`).push({
    command,
    packages: data.packages || null,
    sentBy: request.auth.uid,
    sentAt: admin.database.ServerValue.TIMESTAMP,
  });

  return { success: true };
});

/**
 * Le "hace olvidar" a UN administrador (el que llama) que ya había
 * verificado el PIN de este dispositivo. La próxima acción sobre ese
 * dispositivo, desde esa cuenta, va a volver a pedirlo.
 */
exports.forgetDeviceTrust = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Necesitás iniciar sesión.");
  }
  const { deviceId } = request.data || {};
  if (!deviceId || typeof deviceId !== "string") {
    throw new HttpsError("invalid-argument", "Falta deviceId.");
  }
  await admin.database().ref(`devices/${deviceId}/trustedAdmins/${request.auth.uid}`).remove();
  return { success: true };
});

/**
 * Devuelve metadata de todos los dispositivos registrados.
 * A propósito NO incluye el token FCM ni el hash/salt del PIN — el panel
 * no los necesita para nada, solo sendCommand los usa internamente.
 */
exports.listDevices = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Necesitás iniciar sesión.");
  }

  const snap = await admin.database().ref("devices").once("value");
  const devices = snap.val() || {};
  const adminUid = request.auth.uid;

  return {
    devices: Object.entries(devices).map(([id, info]) => ({
      id,
      model: info.model || "Desconocido",
      isDeviceOwner: !!info.isDeviceOwner,
      kioskEnabled: !!info.kioskEnabled,
      allowedAppCount: info.allowedAppCount || 0,
      wifiBlocked: !!info.wifiBlocked,
      bluetoothBlocked: !!info.bluetoothBlocked,
      vpnBlocked: !!info.vpnBlocked,
      installAppsBlocked: !!info.installAppsBlocked,
      lastSeen: info.lastSeen || null,
      hasToken: !!info.fcmToken,
      hasPinConfigured: !!(info.pinHash && info.pinSalt),
      trustedForMe: !!(info.trustedAdmins && info.trustedAdmins[adminUid]),
    })),
  };
});
