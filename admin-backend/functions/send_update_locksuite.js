const admin = require("firebase-admin");
const crypto = require("crypto");
const path = require("path");

const serviceAccountPath = path.resolve(__dirname, "../locksuite-nueva-firebase-adminsdk-fbsvc-dac7996bff.json");
const serviceAccount = require(serviceAccountPath);

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: "https://locksuite-nueva-default-rtdb.firebaseio.com"
});

const deviceId = "e1939eb56647c24f";

function canonicalCommandPayload(payload) {
  return Object.keys(payload)
    .filter((key) => key !== "signature")
    .sort()
    .map((key) => `${key}=${payload[key] ?? ""}`)
    .join("\n");
}

async function run() {
  console.log(`Consultando estado del dispositivo ${deviceId}...`);
  const deviceSnap = await admin.database().ref(`devices/${deviceId}`).once("value");
  const device = deviceSnap.val();
  if (!device) {
    console.error("Dispositivo no encontrado en la base de datos.");
    process.exit(1);
  }

  const info = device.info || {};
  const lastSeen = info.lastSeen || device.lastSeen;
  const now = Date.now();
  const diffSec = lastSeen ? Math.round((now - lastSeen) / 1000) : null;

  console.log("=== Estado del Dispositivo ===");
  console.log("Modelo:", info.model || device.model);
  console.log("Versión actual:", `${info.versionName || device.versionName} (código ${info.versionCode || device.versionCode})`);
  console.log("Última conexión:", lastSeen ? `${new Date(lastSeen).toISOString()} (hace ${diffSec} seg / ${Math.round(diffSec / 60)} min)` : "desconocida");
  console.log("Salud del túnel DNS:", info.dnsTunnelHealth || device.dnsTunnelHealth);
  console.log("Arranque protegido (bootGateLastResult):", info.bootGateLastResult || device.bootGateLastResult);
  if (info.lastExitReason || device.lastExitReason) {
    console.log("Último motivo de salida del proceso:", info.lastExitReason || device.lastExitReason);
    console.log("Detalle:", info.lastExitDetail || device.lastExitDetail);
  }

  const token = info.fcmToken || device.fcmToken;
  if (!token) {
    console.error("El dispositivo no tiene token FCM registrado.");
    process.exit(1);
  }

  const secretsSnap = await admin.database().ref(`deviceSecrets/${deviceId}`).once("value");
  const secrets = secretsSnap.val() || {};
  const commandSecret = secrets.commandSecret;
  const hasCommandSecret = typeof commandSecret === "string" && commandSecret.length >= 32;

  console.log("Token FCM presente:", !!token);
  console.log("Credencial de comandos (commandSecret) presente:", hasCommandSecret);

  const commandId = crypto.randomUUID();
  const command = "UPDATE_LOCKSUITE";
  const payload = { command, commandId };

  if (hasCommandSecret) {
    payload.timestamp = String(Date.now());
    payload.signature = crypto
      .createHmac("sha256", commandSecret)
      .update(canonicalCommandPayload(payload), "utf8")
      .digest("base64");
  }

  console.log(`\nEncolando y enviando comando ${command} (ID: ${commandId})...`);

  // 1. Escribir ack inicial
  await admin.database().ref(`devices/${deviceId}/commandAcks/${commandId}`).set({
    status: "sent",
    command,
    timestamp: admin.database.ServerValue.TIMESTAMP
  });

  // 2. Encolar en pendingCommands (buzón)
  if (hasCommandSecret) {
    await admin.database().ref(`devices/${deviceId}/pendingCommands/${commandId}`).set({
      data: payload,
      family: "UPDATE_LOCKSUITE",
      queuedAt: Date.now(),
      expiresAt: Date.now() + 24 * 60 * 60 * 1000
    });
    console.log("Comando guardado en el buzón (pendingCommands).");
  }

  // 3. Enviar mensaje FCM de alta prioridad
  try {
    const fcmResult = await admin.messaging().send({
      token,
      data: payload,
      android: { priority: "high" }
    });
    console.log("Mensaje FCM enviado exitosamente. MessageId:", fcmResult);
  } catch (err) {
    console.warn("Fallo el envío por FCM (quedó en el buzón):", err.message);
  }

  console.log("Esperando confirmación del dispositivo (hasta 25 segundos)...");
  const ackRef = admin.database().ref(`devices/${deviceId}/commandAcks/${commandId}`);

  const timeoutPromise = new Promise((resolve) => setTimeout(() => resolve(null), 25000));
  const ackPromise = new Promise((resolve) => {
    ackRef.on("value", (snap) => {
      const val = snap.val();
      if (val && val.status !== "sent") {
        resolve(val);
      }
    });
  });

  const res = await Promise.race([ackPromise, timeoutPromise]);
  if (res) {
    console.log("\nConfirmación recibida del celular:", JSON.stringify(res, null, 2));
  } else {
    console.log("\nEl comando quedó enviado y encolado en el buzón del dispositivo.");
    console.log("Se ejecutará automáticamente en la próxima sincronización o cuando FCM despierte el equipo.");
  }

  process.exit(0);
}

run().catch((err) => {
  console.error("Error ejecutando el script:", err);
  process.exit(1);
});
