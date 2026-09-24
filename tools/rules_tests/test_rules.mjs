// Pruebas de admin-backend/database.rules.json contra el EMULADOR REAL de Realtime
// Database (22/9/2026). Cómo correrlas: ver README.md de esta carpeta.
//
// Cada `check` dice qué TIENE que pasar. Si alguien cambia las reglas y se rompe algo
// que el celular necesita (o se abre algo que un atacante no debería poder), sale en
// rojo y el proceso termina con código 1.
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { readFileSync } from "fs";
import { fileURLToPath } from "url";
import { ref, get, set, update, remove, serverTimestamp } from "firebase/database";

const rulesPath = process.env.RULES ||
  fileURLToPath(new URL("../../admin-backend/database.rules.json", import.meta.url));
const rules = readFileSync(rulesPath, "utf8");
// `firebase emulators:exec` deja la dirección del emulador en esta variable.
const [emuHost, emuPort] = (process.env.FIREBASE_DATABASE_EMULATOR_HOST || "127.0.0.1:9000").split(":");
const env = await initializeTestEnvironment({
  projectId: "demo-locksuite",
  database: { rules, host: emuHost, port: Number(emuPort) }
});
await env.clearDatabase();
await env.withSecurityRulesDisabled(async (ctx) => {
  const db = ctx.database();
  await set(ref(db, "authorizedAdminsUids/admin1"), true);
  await set(ref(db, "devices/D1"), { ownerUid: "devA", deviceName: "Celu de Eli", fcmToken: "tokA",
    appPolicy: { com_whatsapp: "block" }, appRequests: { com_waze: { status: "approved" } }, allowedPackages: ["com.waze"],
    pendingCommands: { c1: { command: "BLOCK_WIFI", commandId: "c1", timestamp: "1", signature: "x" } } });
  await set(ref(db, "deviceSecrets/D1"), { ownerUid: "devA", commandSecret: "S".repeat(44), pinHash: "h", pinSalt: "s" });
  await set(ref(db, "globalSettings/whitelist/decisions/com_waze"), "allow");
});
const results = []; let rojos = 0;
async function check(name, p, expectOk) {
  let got;
  try { await p; got = true; } catch (e) { got = false; }
  const ok = got === expectOk; if (!ok) rojos++;
  results.push([name, got ? "PERMITIDO" : "DENEGADO", ok ? "ok" : "✗ ROJO"]);
}
const dev = env.authenticatedContext("devA").database();
const devNew = env.authenticatedContext("devNew").database();
const evil = env.authenticatedContext("evil").database();
const adm = env.authenticatedContext("admin1").database();
const anon = env.unauthenticatedContext().database();

// ── lo que el celular legítimo TIENE que poder hacer ──
await check("cel: lee su appPolicy (B.63)", get(ref(dev, "devices/D1/appPolicy")), true);
await check("cel: lee su deviceName (B.27)", get(ref(dev, "devices/D1/deviceName")), true);
await check("cel: lee sus appRequests (B.59)", get(ref(dev, "devices/D1/appRequests")), true);
await check("cel: lee su allowedPackages (tienda)", get(ref(dev, "devices/D1/allowedPackages")), true);
await check("cel: lee su buzón pendingCommands", get(ref(dev, "devices/D1/pendingCommands")), true);
await check("cel: borra una entrada del buzón", remove(ref(dev, "devices/D1/pendingCommands/c1")), true);
await check("cel: latido (update con ownerUid)", update(ref(dev, "devices/D1"), { lastSeen: serverTimestamp(), ownerUid: "devA", "info/ownerUid": "devA" }), true);
await check("cel: escribe un ack (ruta hija)", set(ref(dev, "devices/D1/commandAcks/abc"), { status: "applied", command: "X", timestamp: serverTimestamp() }), true);
await check("cel: publica auditoría (ruta hija)", set(ref(dev, "devices/D1/whitelistAudit"), { a_com: { domain: "a.com", hits: 3 } }), true);
await check("cel: escribe pinHash/pinSalt", update(ref(dev, "deviceSecrets/D1"), { pinHash: "h2", pinSalt: "s2", ownerUid: "devA" }), true);
await check("cel: reescribe su commandSecret (mismo valor)", set(ref(dev, "deviceSecrets/D1/commandSecret"), "S".repeat(44)), true);
await check("cel: lee el catálogo global", get(ref(dev, "globalSettings/whitelist")), true);
await check("cel: NO lee deviceSecrets", get(ref(dev, "deviceSecrets/D1")), false);
await check("cel: NO puede cederse el nodo a otro uid", update(ref(dev, "devices/D1"), { ownerUid: "otro" }), false);
await check("cel: NO puede borrar su nodo", remove(ref(dev, "devices/D1")), false);

// ── alta de un equipo NUEVO ──
await check("alta: crea devices/D2 con su uid", update(ref(devNew, "devices/D2"), { ownerUid: "devNew", lastSeen: serverTimestamp() }), true);
await check("alta: escribe deviceSecrets/D2", update(ref(devNew, "deviceSecrets/D2"), { ownerUid: "devNew", pinHash: "h", pinSalt: "s" }), true);
await check("alta: publica commandSecret D2", set(ref(devNew, "deviceSecrets/D2/commandSecret"), "N".repeat(44)), true);
await check("alta: secretos ANTES que el nodo (D3)", update(ref(devNew, "deviceSecrets/D3"), { ownerUid: "devNew", pinHash: "h", pinSalt: "s" }), true);
await check("alta: después crea devices/D3", update(ref(devNew, "devices/D3"), { ownerUid: "devNew" }), true);

// ── ataques que TIENEN que fallar ──
await check("ATAQUE: secuestrar devices/D1 (fcmToken)", update(ref(evil, "devices/D1"), { ownerUid: "evil", fcmToken: "robado" }), false);
await check("ATAQUE: escribir pinHash de D1", update(ref(evil, "deviceSecrets/D1"), { ownerUid: "evil", pinHash: "x" }), false);
await check("ATAQUE: pisar commandSecret de D1", set(ref(evil, "deviceSecrets/D1/commandSecret"), "X".repeat(44)), false);
await check("ATAQUE: leer devices/D1", get(ref(evil, "devices/D1")), false);
await check("ATAQUE: leer lista devices", get(ref(evil, "devices")), false);
await check("ATAQUE: inyectar comando en buzón de D1", set(ref(evil, "devices/D1/pendingCommands/zz"), { command: "UNBLOCK_WIFI" }), false);
await check("ATAQUE: trustedAdmins en D1", set(ref(evil, "devices/D1/trustedAdmins/evil"), true), false);
await check("ATAQUE: sin sesión, escribir D1", update(ref(anon, "devices/D1"), { ownerUid: null }), false);
await check("ATAQUE: secretos de equipo EXISTENTE ajeno", update(ref(evil, "deviceSecrets/D2"), { ownerUid: "evil", pinHash: "x" }), false);

// ── re-vinculación tras reinstalar (uid nuevo) ──
const devReinst = env.authenticatedContext("devA2").database();
await check("reinstalado: NO escribe con uid nuevo", update(ref(devReinst, "devices/D1"), { ownerUid: "devA2", lastSeen: 1 }), false);
await check("reinstalado: pide re-vincular (deviceClaims)", set(ref(devReinst, "deviceClaims/D1/devA2"), { at: serverTimestamp(), model: "SM-A065M", versionName: "0.6.55" }), true);
await check("claim: no deja claves extra", set(ref(devReinst, "deviceClaims/D1/devA2"), { at: 1, evil: "x" }), false);
await check("claim: no a nombre de otro uid", set(ref(evil, "deviceClaims/D1/devA2"), { at: 1 }), false);
await check("claim: no para equipo inexistente", set(ref(evil, "deviceClaims/NOPE/evil"), { at: 1 }), false);
await check("claim: el celular no lee los pedidos", get(ref(devReinst, "deviceClaims")), false);
await check("admin: lee pedidos de re-vinculación", get(ref(adm, "deviceClaims")), true);
await check("admin: aprueba (ownerUid → uid nuevo)", update(ref(adm, "/"), {
  "devices/D1/ownerUid": "devA2", "devices/D1/info/ownerUid": "devA2", "deviceSecrets/D1/ownerUid": "devA2",
  "deviceSecrets/D1/commandSecret": null, "deviceClaims/D1": null }), true);
await check("reinstalado: ya escribe", update(ref(devReinst, "devices/D1"), { ownerUid: "devA2", lastSeen: 2 }), true);
await check("reinstalado: publica commandSecret nuevo", set(ref(devReinst, "deviceSecrets/D1/commandSecret"), "Z".repeat(44)), true);
await check("uid viejo: ya no escribe", update(ref(dev, "devices/D1"), { ownerUid: "devA", lastSeen: 3 }), false);

// ── panel (admin) ──
await check("admin: escribe appPolicy", set(ref(adm, "devices/D1/appPolicy/com_waze"), "allow"), true);
await check("admin: borra un equipo", remove(ref(adm, "devices/D3")), true);
await check("admin: escribe catálogo global", set(ref(adm, "globalSettings/whitelist/decisions/com_x"), "block"), true);
await check("no-admin: NO escribe catálogo global", set(ref(evil, "globalSettings/whitelist/decisions/com_x"), "allow"), false);

console.table(results);
console.log(rojos === 0 ? "TODAS VERDES (" + results.length + ")" : "ROJAS: " + rojos);
await env.cleanup();
process.exit(rojos === 0 ? 0 : 1);
