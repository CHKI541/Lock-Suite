const { chromium } = require('playwright');
const path = require('path');
const now = Date.now();
const SEED = {
  authorizedAdminsUids: { admin1: true }, globalSettings: {},
  devices: {
    dev1: { deviceName: "itzi", model: "Cat", lastSeen: now - 30000, fcmToken: "tok1", hasPinConfigured: true, trustedAdmins: { admin1: true } },
    dev2: { deviceName: "R shaul said", model: "Q-Tel Q1", lastSeen: now - 60000, fcmTokenError: "SERVICE_NOT_AVAILABLE",
      pendingCommands: { k1: { data: { command: "BLOCK_WHATSAPP_STATUS", commandId: "k1", timestamp: String(now - 120000), signature: "x" }, queuedAt: now - 120000, expiresAt: now + 86400000 } } },
    dev3: { deviceName: "nuevo", model: "X", lastSeen: now - 10000, mailboxLive: true, mailboxLastCheckAt: now - 5000, mailboxLastResult: "sin pendientes" }
  }
};
let fallas = 0; const ok = (c, m) => { console.log((c ? "ok   " : "FALLA") + " " + m); if (!c) fallas++; };
(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  const errores = []; p.on('pageerror', e => errores.push(e.message)); p.on('console', m => { if (m.type() === 'error') errores.push(m.text()); });
  await p.addInitScript(s => { window.__SEED__ = s; }, SEED);
  await p.goto('file://' + path.resolve('celular.html') + '?id=dev2');
  await p.waitForTimeout(1500);
  const resumen = await p.evaluate(() => (document.getElementById('c-salud') || {}).textContent || '');
  ok(/Canal de comandos/.test(resumen) && /sin FCM/.test(resumen) && /1 en su buzón/.test(resumen), 'Resumen: «Canal de comandos» sin FCM y 1 en su buzón → ' + resumen.replace(/\s+/g, ' ').slice(0, 220));
  const avisos = await p.evaluate(() => document.body.textContent.includes('no está escuchando su buzón en vivo'));
  ok(avisos, 'Resumen: aviso de equipo sin FCM y sin escucha en vivo');
  const r1 = await p.evaluate(async () => { window.__fnMode = 'fcm_old'; setTimeout(() => __deviceApplies('dev1', __lastCid, null), 500); return await LS.sendCommand('dev1', 'BLOCK_WIFI', null, null, {}); });
  ok(r1.ok && r1.status === 'applied', '1) FCM salió (Function vieja): espera y devuelve applied ' + JSON.stringify(r1));
  const t0 = Date.now();
  const r2 = await p.evaluate(async () => { window.__fnMode = 'notoken_old'; return await LS.sendCommand('dev2', 'BLOCK_WIFI', null, null, {}); });
  const dt = Date.now() - t0;
  ok(r2.ok && r2.pendiente && dt < 4000, '2) sin FCM ni escucha en vivo: pendiente en ~1,5 s (' + dt + ' ms), sin trabar ' + JSON.stringify(r2).slice(0, 160));
  const r3 = await p.evaluate(async () => { window.__fnMode = 'notoken_new'; setTimeout(() => __deviceApplies('dev3', __lastCid, null), 1000); return await LS.sendCommand('dev3', 'BLOCK_WIFI', null, null, {}); });
  ok(r3.ok && r3.status === 'applied' && /buzón/.test(r3.reason), '3) sin FCM con escucha en vivo: espera y llega applied ' + JSON.stringify(r3));
  const r4 = await p.evaluate(async () => { window.__fnMode = 'fcm_new'; setTimeout(() => __deviceApplies('dev1', __lastCid, null, null, 'rejected'), 300); return await LS.sendCommand('dev1', 'BLOCK_WIFI', null, null, {}); });
  ok(!r4.ok && r4.status === 'rejected', '4) rechazo real: ok=false ' + JSON.stringify(r4));
  const txt = await p.evaluate(() => LS.resultado({ ok: true, pendiente: true, reason: 'x' }, 'Listo.'));
  ok(txt[1] === 'warn' && /^⏳/.test(txt[0]), '5) LS.resultado: pendiente se avisa con ⏳ y tipo warn, nunca ✓ ' + JSON.stringify(txt));
  ok(errores.length === 0, 'sin errores de consola/página: ' + JSON.stringify(errores.slice(0, 5)));
  await b.close(); console.log(fallas ? fallas + ' FALLAS' : 'TODO VERDE'); process.exit(fallas ? 1 : 0);
})();
