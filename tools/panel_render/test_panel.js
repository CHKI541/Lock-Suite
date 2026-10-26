const { chromium } = require('playwright');
const path = require('path');
const now = Date.now();
const SEED = {
  authorizedAdminsUids: { admin1: true },
  globalSettings: {},
  devices: {
    dev1: { deviceName: "itzi", model: "Cat S22 FLIP", lastSeen: now - 30000, fcmToken: "tok1", hasPinConfigured: true, trustedAdmins: { admin1: true }, wifiBlocked: false, versionName: "0.6.66", versionCode: 129 },
    dev2: { deviceName: "R shaul said", model: "Q-Tel Q1", lastSeen: now - 60000, hasPinConfigured: true, trustedAdmins: { admin1: true }, fcmTokenError: "SERVICE_NOT_AVAILABLE", whatsappBlockStatus: false, wifiBlocked: false,
      pendingCommands: { k1: { data: { command: "BLOCK_WHATSAPP_STATUS", commandId: "k1", timestamp: String(now - 120000), signature: "x" }, family: "BLOCK_WHATSAPP_STATUS", queuedAt: now - 120000, expiresAt: now + 86400000 } },
      versionName: "0.6.66", versionCode: 129 },
    dev3: { deviceName: "nuevo sin FCM", model: "X", lastSeen: now - 10000, hasPinConfigured: true, trustedAdmins: { admin1: true }, mailboxLive: true, mailboxLastCheckAt: now - 5000, mailboxLastResult: "sin pendientes", wifiBlocked: false, versionName: "0.6.67", versionCode: 130 }
  }
};
let fallas = 0;
const ok = (c, m) => { console.log((c ? "ok   " : "FALLA") + " " + m); if (!c) fallas++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
  const errores = [];
  page.on('pageerror', e => errores.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errores.push('console: ' + m.text()); });
  await page.addInitScript(s => { window.__SEED__ = s; }, SEED);
  await page.goto('file://' + path.resolve('index.html'));
  await page.waitForSelector('.device-card', { timeout: 10000 });
  await sleep(300);
  // 1) badge Sin FCM con motivo
  const badge = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('.device-card')];
    const c = cards.find(x => x.textContent.includes('R shaul said'));
    const b = c && c.querySelector('.device-header .badge');
    return b ? { t: b.textContent, title: b.title } : null;
  });
  ok(badge && badge.t === 'Sin FCM' && /SERVICE_NOT_AVAILABLE/.test(badge.title), 'badge «Sin FCM» con el motivo en el título: ' + JSON.stringify(badge));
  // 2) abrir dev2 → tarjeta canal + interruptor pendiente
  await page.evaluate(() => openDeviceSidebar('dev2', currentDevicesData['dev2']));
  await sleep(300);
  const canal = await page.evaluate(() => { const el = document.getElementById('device-command-channel'); return { vis: el && el.style.display, txt: el && el.textContent }; });
  ok(canal.vis === 'block' && /no tiene FCM — SERVICE_NOT_AVAILABLE/.test(canal.txt) && /1 comando\(s\) esperando/.test(canal.txt), 'tarjeta «Canal de comandos» visible para el equipo sin FCM');
  console.log('      texto: ' + canal.txt);
  const pend = await page.evaluate(() => { const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="whatsappBlockStatus"]'); const row = sw.closest('.toggle-row'); const m = row.querySelector('.ls-pendiente'); return { checked: sw.checked, marca: m && m.textContent }; });
  ok(pend.checked === true && pend.marca === '⏳ pendiente', 'interruptor con comando en el buzón: muestra lo pedido con «⏳ pendiente» ' + JSON.stringify(pend));
  // 3) A: Function VIEJA, equipo con FCM → espera el ack (no "encolado")
  await page.evaluate(() => openDeviceSidebar('dev1', currentDevicesData['dev1']));
  await sleep(200);
  const canal1 = await page.evaluate(() => document.getElementById('device-command-channel').style.display);
  ok(canal1 === 'none', 'equipo con FCM y buzón vacío: la tarjeta del canal se calla');
  await page.evaluate(() => { window.__fnMode = 'fcm_old'; window.__revert = 0; const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); sw.checked = true; runCommandOnDevice('dev1', 'BLOCK_WIFI', null, sw, () => { window.__revert++; sw.checked = false; }); });
  await sleep(400);
  let st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/Esperando respuesta del celular/.test(st) && !/encolado/i.test(st), 'A) FCM salió (Function vieja): espera el ack, NO dice "encolado": ' + st);
  const marcaA = await page.evaluate(() => { const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); const m = sw.closest('.toggle-row').querySelector('.ls-pendiente'); return { c: sw.checked, m: m && m.textContent }; });
  ok(marcaA.c === true && marcaA.m === '⏳ pendiente', 'A) mientras tanto el interruptor queda en lo pedido con «⏳ pendiente» (no vuelve solo): ' + JSON.stringify(marcaA));
  await page.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(st === '✓ Comando aplicado con éxito en el celular', 'A) el ack "applied" se muestra: ' + st);
  const marcaA2 = await page.evaluate(() => { const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); const m = sw.closest('.toggle-row').querySelector('.ls-pendiente'); return { c: sw.checked, m: m && m.textContent }; });
  ok(marcaA2.c === true && marcaA2.m === '⏳ aplicando', 'A) aplicado pero sin reporte nuevo: «⏳ aplicando» ' + JSON.stringify(marcaA2));
  await page.evaluate(() => __fakeDb.setAt('devices/dev1/wifiBlocked', true));
  await sleep(300);
  const marcaA3 = await page.evaluate(() => { const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); const m = sw.closest('.toggle-row').querySelector('.ls-pendiente'); return { c: sw.checked, m: m && m.textContent, rev: window.__revert }; });
  ok(marcaA3.c === true && !marcaA3.m && marcaA3.rev === 0, 'A) cuando el equipo reporta, la marca se va y nunca se deshizo el interruptor ' + JSON.stringify(marcaA3));
  // 4) B: Function vieja, equipo SIN FCM y sin escucha en vivo (dev2) → 1,5 s y segundo plano
  await page.evaluate(() => openDeviceSidebar('dev2', currentDevicesData['dev2']));
  await sleep(200);
  await page.evaluate(() => { window.__fnMode = 'notoken_old'; window.__revert = 0; const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); sw.checked = true; runCommandOnDevice('dev2', 'BLOCK_WIFI', null, sw, () => { window.__revert++; }); });
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/no recibe FCM \(el equipo nunca registró FCM\)/.test(st), 'B) sin FCM: lo dice de entrada: ' + st);
  await sleep(1600);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/En el buzón del celular/.test(st) && /anterior a 0\.6\.67/.test(st), 'B) sin escucha en vivo: avisa que tarda hasta 15 min: ' + st);
  let rev = await page.evaluate(() => window.__revert);
  ok(rev === 0, 'B) "en cola" NO deshace el interruptor');
  await page.evaluate(() => __deviceApplies('dev2', __lastCid, 'wifiBlocked', true));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/BLOCK_WIFI: el celular lo aplicó/.test(st), 'B) cuando el celular lo levanta (minutos después), el panel lo dice: ' + st);
  // 5) C: Function nueva delivered:false, equipo con escucha en vivo (dev3)
  await page.evaluate(() => openDeviceSidebar('dev3', currentDevicesData['dev3']));
  await sleep(200);
  await page.evaluate(() => { window.__fnMode = 'notoken_new'; window.__revert = 0; const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); sw.checked = true; runCommandOnDevice('dev3', 'BLOCK_WIFI', null, sw, () => { window.__revert++; }); });
  await sleep(1800);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/Esperando que lo levante/.test(st), 'C) en vivo: sigue esperando (no corta a los 1,5 s): ' + st);
  await page.evaluate(() => __deviceApplies('dev3', __lastCid, 'wifiBlocked', true));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(st === '✓ Comando aplicado en el celular (llegó por el buzón)', 'C) aplicado por el buzón en vivo: ' + st);
  // 6) D: rechazo real → deshace
  await page.evaluate(() => { window.__fnMode = 'fcm_new'; window.__revert = 0; const sw = document.querySelector('#device-sidebar .policy-switch[data-policy="wifiBlocked"]'); runCommandOnDevice('dev3', 'UNBLOCK_WIFI', null, sw, () => { window.__revert++; }); });
  await sleep(300);
  await page.evaluate(() => __deviceApplies('dev3', __lastCid, null, null, 'rejected'));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  rev = await page.evaluate(() => window.__revert);
  ok(/rechazado por el celular \(BAD_SIGNATURE\)/.test(st) && rev === 1, 'D) un rechazo real sí deshace el interruptor: ' + st);
  // 7) E: FCM salió pero no contesta en 10 s → pendiente, sin deshacer; después llega
  await page.evaluate(() => { window.__fnMode = 'fcm_new'; window.__revert = 0; runCommandOnDevice('dev3', 'BLOCK_SMS', null, null, () => { window.__revert++; }); });
  await sleep(10500);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  rev = await page.evaluate(() => window.__revert);
  ok(/no confirmó en 10 s/.test(st) && /NO se perdió/.test(st) && rev === 0, 'E) sin respuesta en 10 s: pendiente, sin deshacer: ' + st.slice(0, 90));
  await page.evaluate(() => __deviceApplies('dev3', __lastCid, null));
  await sleep(300);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/BLOCK_SMS: el celular lo aplicó/.test(st), 'E) el ack tardío se muestra: ' + st);
  // 8) F: UPDATE_LOCKSUITE a un equipo sin FCM ni escucha en vivo: no dice "actualizando"
  await page.evaluate(() => openDeviceSidebar('dev2', currentDevicesData['dev2']));
  await sleep(200);
  await page.evaluate(() => { window.__fnMode = 'notoken_old'; runCommandOnDevice('dev2', 'UPDATE_LOCKSUITE', null, null, null); });
  await sleep(1800);
  st = await page.evaluate(() => document.getElementById('sidebar-status-msg').textContent);
  ok(/En el buzón del celular/.test(st) && !/actualizando en segundo plano/.test(st), 'F) autoactualización a un equipo sin FCM: no promete lo que no sabe: ' + st.slice(0, 80));
  await page.screenshot({ path: 'panel_sidebar.png' });
  await page.evaluate(() => openDeviceSidebar('dev2', currentDevicesData['dev2']));
  await sleep(300);
  await page.screenshot({ path: 'panel_sin_fcm.png' });
  ok(errores.length === 0, 'sin errores de consola/página: ' + JSON.stringify(errores.slice(0, 5)));
  await browser.close();
  console.log(fallas ? fallas + ' FALLAS' : 'TODO VERDE');
  process.exit(fallas ? 1 : 0);
})();
