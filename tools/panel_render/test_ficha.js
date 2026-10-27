// Pruebas de la ficha completa (celular.html + comun.js + celular.js) con Firebase y
// Cloud Function FALSOS (ver README.md). Sin red y sin tocar el Firebase de verdad.
//
// 1. El contrato de LS.sendCommand (applied / pendiente / falla) y el «Canal de comandos».
// 2. (26/10/2026, B.98) La ficha rearmada: que esté TODO lo de la vista rápida y que cada
//    control mande el comando correcto, con el paquete correcto; la sección
//    Actualizaciones (estado en vivo, camino, barra, paso a paso, cancelar, último
//    resultado, elegir el camino); Diagnóstico; archivar; olvidar el PIN; y que en un
//    teléfono (390 px) ninguna sección se salga de la pantalla.
const { chromium } = require('playwright');
const path = require('path');
const now = Date.now();

const APPS_DEV1 = {
  com_whatsapp: { packageName: "com.whatsapp", label: "WhatsApp", appType: "Usuario", isHidden: false, isSuspended: false,
    isWebViewBlocked: false, isInternetBlocked: false, imageBlockingMode: "none", isCritical: false, versionName: "2.24.1", versionCode: 241 },
  com_waze: { packageName: "com.waze", label: "Waze", appType: "Usuario", isHidden: false, isSuspended: true,
    isWebViewBlocked: true, isInternetBlocked: false, imageBlockingMode: "layer1", isCritical: false, versionName: "4.9", versionCode: 1049 },
  com_android_vending: { packageName: "com.android.vending", label: "Play Store", appType: "Sistema", isHidden: false, isSuspended: true,
    isWebViewBlocked: false, isInternetBlocked: false, imageBlockingMode: "none", isCritical: false, versionName: "40.1", versionCode: 84 },
  com_android_phone: { packageName: "com.android.phone", label: "Teléfono", appType: "Sistema", isHidden: false, isSuspended: false,
    isWebViewBlocked: false, isInternetBlocked: false, imageBlockingMode: "none", isCritical: true, versionName: "14", versionCode: 34 }
};

const SEED = {
  authorizedAdminsUids: { admin1: true }, globalSettings: {},
  storeApps: { com_waze: { label: "Waze", packageName: "com.waze", apkUrl: "https://x/waze.apk", sha256: "a".repeat(64) } },
  devices: {
    dev1: { deviceName: "itzi", model: "Cat", lastSeen: now - 30000, fcmToken: "tok1", hasPinConfigured: true,
      trustedAdmins: { admin1: true }, versionName: "0.6.68", versionCode: 131, apps: APPS_DEV1,
      allowedPackages: ["com.whatsapp", "com.android.dialer"], hideSuspendedApps: false, enrollmentPending: true,
      commandAcks: { old1: { status: "applied", command: "BLOCK_WIFI", timestamp: now - 3600000 } },
      updateFlow: { running: true, packageName: "com.waze", stage: "HOSTED_DOWNLOADING", statusText: "Descargando de la Tienda de LockSuite... 45%",
        route: "HOSTED", routePref: "auto", routeWhy: "A esta app no la instaló Google Play.", progress: 45, installer: "com.ejemplo.locksuite",
        versionBefore: "4.9 (1049)", source: "panel", cancelable: true, startedAt: now - 20000, keyguardLocked: false, stepsCount: 3 },
      updateFlowSteps: [
        { t: now - 20000, k: "info", m: "Pedido desde el panel: actualizar Waze (versión 4.9, código 1049)." },
        { t: now - 19000, k: "info", m: "Camino elegido: copia de la Tienda de LockSuite, en silencio." },
        { t: now - 18000, k: "act", m: "Descargando de la Tienda de LockSuite" }
      ] },
    dev2: { deviceName: "R shaul said", model: "Q-Tel Q1", lastSeen: now - 60000, fcmTokenError: "SERVICE_NOT_AVAILABLE",
      pendingCommands: { k1: { data: { command: "BLOCK_WHATSAPP_STATUS", commandId: "k1", timestamp: String(now - 120000), signature: "x" }, queuedAt: now - 120000, expiresAt: now + 86400000 } } },
    dev3: { deviceName: "nuevo", model: "X", lastSeen: now - 10000, mailboxLive: true, mailboxLastCheckAt: now - 5000, mailboxLastResult: "sin pendientes" },
    dev4: { deviceName: "viejo", model: "Y", lastSeen: now - 10000, fcmToken: "tok4", versionName: "0.6.67", versionCode: 130,
      apps: { com_whatsapp: APPS_DEV1.com_whatsapp },
      updateFlow: { running: false, lastResult: "NO_SPACE", lastResultPackage: "com.whatsapp", lastResultReason: "Faltan 180 MB", lastResultAt: now - 60000, freeSpaceMb: 120 } }
  }
};

let fallas = 0; const ok = (c, m) => { console.log((c ? "ok   " : "FALLA") + " " + m); if (!c) fallas++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1300, height: 900 } });
  const errores = []; p.on('pageerror', e => errores.push(e.message)); p.on('console', m => { if (m.type() === 'error') errores.push(m.text()); });
  p.on('dialog', d => d.accept());   // los confirm() de la ficha se aceptan
  await p.addInitScript(s => { window.__SEED__ = s; }, SEED);

  // ══ 1. Contrato de LS.sendCommand y «Canal de comandos» (B.95) ══════════════════════
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

  // ══ 2. La ficha rearmada (B.98) ═════════════════════════════════════════════════
  await p.goto('file://' + path.resolve('celular.html') + '?id=dev1');
  await p.waitForTimeout(1200);

  // Todas las secciones existen y se abren desde el riel.
  const secciones = await p.evaluate(() => [...document.querySelectorAll('#c-rail button')].map(x => x.dataset.sec));
  ok(['resumen', 'rapida', 'celapps', 'apps', 'politicas', 'actualizaciones', 'dns', 'tienda', 'acciones', 'diag']
    .every(s => secciones.includes(s)), 'riel con las 10 secciones: ' + secciones.join(','));
  for (const s of secciones) {
    await p.click('#c-rail button[data-sec="' + s + '"]');
    const visible = await p.evaluate((s) => { const el = document.querySelector('.ls-section[data-sec="' + s + '"]'); return !!el && el.offsetHeight > 0; }, s);
    if (!visible) ok(false, 'la sección ' + s + ' no se ve al tocarla en el riel');
  }
  ok(true, 'cada sección se muestra al tocarla en el riel');

  // Resumen: «Terminar alta» (antes solo en la pestaña de QR del panel).
  await p.click('#c-rail button[data-sec="resumen"]');
  const n0 = await p.evaluate(() => __calls.length);
  await p.evaluate(() => { window.__fnMode = 'fcm_new'; });
  await p.click('#c-alta-btn');
  await sleep(200);
  const altaCmd = await p.evaluate((n0) => __calls.slice(n0).map(c => c.command), n0);
  ok(altaCmd.includes('FINISH_ENROLLMENT'), 'Resumen: «Terminar alta» manda FINISH_ENROLLMENT ' + JSON.stringify(altaCmd));
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  ok(await p.evaluate(() => /Actualizando Waze/.test(document.getElementById('c-upd-resumen').textContent)), 'Resumen: tarjeta con la actualización en curso');

  // ── Apps del celular ──
  await p.click('#c-rail button[data-sec="celapps"]');
  await sleep(150);
  const lista = await p.evaluate(() => [...document.querySelectorAll('#c-inst .ls-irow')].map(x => x.dataset.pkg));
  ok(lista.includes('com.whatsapp') && lista.includes('com.waze') && !lista.includes('com.android.vending') && !lista.includes('com.android.phone'),
    'Apps del celular: por omisión las del usuario, sin Play Store ni las del sistema: ' + JSON.stringify(lista));
  const chipsWaze = await p.evaluate(() => document.querySelector('#c-inst .ls-irow[data-pkg="com.waze"]').textContent);
  ok(/suspendida/.test(chipsWaze) && /sin web/.test(chipsWaze) && /imágenes/.test(chipsWaze) && /v4\.9/.test(chipsWaze),
    'Apps del celular: chips de estado y versión de Waze → ' + chipsWaze.replace(/\s+/g, ' ').slice(0, 160));

  // Ocultar WhatsApp → HIDE_APP con el paquete; ⏳ hasta que el celular reporta.
  let n = await p.evaluate(() => __calls.length);
  await p.click('#c-inst .ls-irow[data-pkg="com.whatsapp"] button[data-acc="oculta"]');
  await sleep(250);
  const hide = await p.evaluate((n) => __calls.slice(n)[0], n);
  ok(hide && hide.command === 'HIDE_APP' && hide.packages === 'com.whatsapp', 'Ocultar → HIDE_APP com.whatsapp ' + JSON.stringify(hide));
  const espera = await p.evaluate(() => document.querySelector('#c-inst .ls-irow[data-pkg="com.whatsapp"] button[data-acc="oculta"]').textContent);
  ok(/⏳/.test(espera), 'mientras el celular no confirma, el botón muestra ⏳: ' + espera);
  await p.evaluate(() => { __deviceApplies('dev1', __lastCid, 'apps/com_whatsapp/isHidden', true); });
  await sleep(300);
  const tras = await p.evaluate(() => document.querySelector('#c-inst .ls-irow[data-pkg="com.whatsapp"] button[data-acc="oculta"]').textContent);
  ok(/Mostrar/.test(tras), 'cuando el celular reporta la app oculta, el botón ofrece «Mostrar»: ' + tras);

  // «Más»: navegador interno, internet, imágenes, actualizar.
  await p.click('#c-inst .ls-irow[data-pkg="com.waze"] button[data-mas]');
  await sleep(100);
  n = await p.evaluate(() => __calls.length);
  await p.click('#c-inst .ls-irow[data-pkg="com.waze"] button[data-acc="internet"]');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  await p.selectOption('#c-inst .ls-irow[data-pkg="com.waze"] select[data-img]', 'both');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, 'apps/com_waze/imageBlockingMode', 'both'));
  const masCmds = await p.evaluate((n) => __calls.slice(n).map(c => c.command + ':' + c.packages), n);
  ok(masCmds.includes('BLOCK_APP_INTERNET:com.waze') && masCmds.includes('SET_IMAGE_BLOCK_BOTH:com.waze'),
    '«Más»: cortar internet e imágenes mandan su comando con el paquete ' + JSON.stringify(masCmds));

  // Filtros de la lista.
  await p.click('#c-inst-filtro button[data-f="sistema"]');
  await sleep(100);
  const sis = await p.evaluate(() => document.querySelector('#c-inst').textContent);
  ok(/Teléfono/.test(sis) && /Crítica para el sistema/.test(sis), 'filtro «Del sistema»: aparece la app crítica, sin controles');
  await p.click('#c-inst-filtro button[data-f="usuario"]');

  // Play Store: suspendida (sacar), ocultar íconos.
  n = await p.evaluate(() => __calls.length);
  await p.click('label[for="play-suspendida"]');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  await p.click('label[for="play-iconos"]');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  const play = await p.evaluate((n) => __calls.slice(n).map(c => ({ c: c.command, p: c.packages, e: c.enabled })), n);
  ok(play.some(x => x.c === 'UNSUSPEND_APP' && x.p === 'com.android.vending') && play.some(x => x.c === 'SET_HIDE_SUSPENDED_APPS' && x.e === true),
    'Play Store: reanudar y «ocultar íconos» mandan lo que manda la vista rápida ' + JSON.stringify(play));

  // Lista de apps permitidas.
  const prell = await p.evaluate(() => document.getElementById('c-allowlist').value);
  ok(/com\.whatsapp/.test(prell) && /com\.android\.dialer/.test(prell), 'lista de permitidas precargada con lo que reporta el equipo: ' + prell);
  n = await p.evaluate(() => __calls.length);
  await p.fill('#c-allowlist', 'com.whatsapp, com.waze');
  await p.click('#c-allowlist-btn');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  const allow = await p.evaluate((n) => __calls.slice(n)[0], n);
  ok(allow && allow.command === 'UPDATE_ALLOWLIST' && allow.packages === 'com.whatsapp,com.waze', 'UPDATE_ALLOWLIST con los paquetes ' + JSON.stringify(allow));

  // ── Actualizaciones ──
  await p.click('#c-rail button[data-sec="actualizaciones"]');
  await sleep(150);
  const vivo = await p.evaluate(() => {
    const c = document.querySelector('#c-upd-vivo .ls-live');
    const bar = c && c.querySelector('.ls-bar span');
    return { txt: c ? c.textContent : '', ancho: bar ? bar.style.width : '' };
  });
  ok(/Tienda de LockSuite/.test(vivo.txt) && /45%/.test(vivo.txt) && vivo.ancho === '45%' && /Por qué este camino/.test(vivo.txt),
    'en curso: camino, etapa, barra al 45 % y el porqué → ' + vivo.txt.replace(/\s+/g, ' ').slice(0, 200));
  const pasos = await p.evaluate(() => document.querySelectorAll('#c-upd-pasos .ls-steps li').length);
  ok(pasos === 3, 'paso a paso: los 3 pasos publicados (' + pasos + ')');
  ok(await p.evaluate(() => !document.getElementById('c-upd-chip').classList.contains('hidden')), 'cabecera: chip «actualizando…» visible');

  // Un paso nuevo llega en vivo.
  await p.evaluate(() => __fakeDb.setAt('devices/dev1/updateFlowSteps/3', { t: Date.now(), k: 'ok', m: 'La copia sirve: versión 4.10' }));
  await sleep(200);
  ok(await p.evaluate(() => document.querySelectorAll('#c-upd-pasos .ls-steps li').length === 4), 'paso a paso: un paso nuevo aparece en vivo');

  n = await p.evaluate(() => __calls.length);
  await p.click('#c-upd-cancelar');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  ok(await p.evaluate((n) => __calls.slice(n).some(c => c.command === 'CANCEL_UPDATE_APP'), n), 'Cancelar → CANCEL_UPDATE_APP');

  // Termina: último resultado con motivo y versiones.
  await p.evaluate(() => __fakeDb.setAt('devices/dev1/updateFlow', { running: false, lastResult: 'UPDATED', lastResultPackage: 'com.waze',
    lastResultAt: Date.now(), route: 'HOSTED', versionBefore: '4.9 (1049)', versionAfter: '4.10 (1050)', freeSpaceMb: 900 }));
  await sleep(250);
  const ult = await p.evaluate(() => document.getElementById('c-upd-ultimo').textContent);
  ok(/Actualizada a la versión 4\.10/.test(ult) && /4\.9 \(1049\) → 4\.10 \(1050\)/.test(ult) && /Tienda de LockSuite/.test(ult),
    'último resultado: versión nueva, de → a y camino → ' + ult.replace(/\s+/g, ' ').slice(0, 200));
  ok(await p.evaluate(() => document.getElementById('c-upd-chip').classList.contains('hidden')), 'cabecera: el chip se va al terminar');

  // Elegir el camino: «Solo Google Play» viaja como route=play; «Automático» no viaja.
  await p.click('#c-upd-ruta button[data-ruta="play"]');
  n = await p.evaluate(() => __calls.length);
  await p.click('#c-upd-lista .ls-updrow[data-pkg="com.whatsapp"] button[data-upd]');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  await sleep(100);
  const updPlay = await p.evaluate((n) => __calls.slice(n)[0], n);
  ok(updPlay && updPlay.command === 'UPDATE_APP' && updPlay.packages === 'com.whatsapp' && updPlay.route === 'play', 'UPDATE_APP con route=play ' + JSON.stringify(updPlay));
  await p.click('#c-upd-ruta button[data-ruta="auto"]');
  n = await p.evaluate(() => __calls.length);
  await p.click('#c-upd-lista .ls-updrow[data-pkg="com.whatsapp"] button[data-upd]');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  await sleep(100);
  const updAuto = await p.evaluate((n) => __calls.slice(n)[0], n);
  ok(updAuto && updAuto.command === 'UPDATE_APP' && !('route' in updAuto), 'UPDATE_APP en automático: sin campo route (idéntico al de siempre) ' + JSON.stringify(updAuto));

  // Tienda: «Actualizar desde la Tienda» fuerza route=hosted sin cambiar la elección.
  await p.click('#c-rail button[data-sec="tienda"]');
  n = await p.evaluate(() => __calls.length);
  await p.click('#c-tienda button[data-tienda-upd="com.waze"]');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  await sleep(100);
  const updT = await p.evaluate((n) => __calls.slice(n)[0], n);
  ok(updT && updT.command === 'UPDATE_APP' && updT.route === 'hosted' && updT.packages === 'com.waze', 'Tienda: «Actualizar desde la Tienda» → route=hosted ' + JSON.stringify(updT));
  ok(await p.evaluate(() => document.querySelector('#c-upd-ruta button.on-allow').dataset.ruta === 'auto'), 'la elección de camino de la sección no cambió');

  // ── Filtro: detector de navegadores embebidos ──
  await p.click('#c-rail button[data-sec="apps"]');
  n = await p.evaluate(() => __calls.length);
  await p.click('#c-iab-on');
  await sleep(250);
  await p.evaluate(() => __deviceApplies('dev1', __lastCid, null));
  ok(await p.evaluate((n) => __calls.slice(n).some(c => c.command === 'ENABLE_IAB_FINDER'), n), 'Filtro: «Encender detector» → ENABLE_IAB_FINDER');

  // ── Diagnóstico ──
  await p.click('#c-rail button[data-sec="diag"]');
  await sleep(150);
  const diag = await p.evaluate(() => ({ campos: document.getElementById('c-diag-campos').textContent, acks: document.getElementById('c-diag-acks').textContent }));
  ok(/model/.test(diag.campos) && /Cat/.test(diag.campos) && /HIDE_APP/.test(diag.acks), 'Diagnóstico: campos del equipo y comandos recientes');
  await p.click('#c-diag-json-box summary');
  await sleep(100);
  ok(await p.evaluate(() => /"deviceName": "itzi"/.test(document.getElementById('c-diag-json').textContent)), 'Diagnóstico: JSON completo al abrirlo');
  ok(await p.evaluate(() => !/tok1/.test(document.getElementById('c-diag-campos').textContent) && !/tok1/.test(document.getElementById('c-diag-json').textContent)),
    'Diagnóstico: el token de FCM no se muestra entero (ni en la tabla ni en el JSON)');

  // ── Acciones: olvidar la confianza del PIN ──
  await p.click('#c-rail button[data-sec="acciones"]');
  await p.click('#c-pin-olvidar');
  await sleep(200);
  ok(await p.evaluate(() => __fakeDb.getAt('devices/dev1/trustedAdmins/admin1') === null), 'Acciones: «Olvidar la confianza del PIN» borra trustedAdmins/<uid>');

  // ── Teléfono: ninguna sección se sale de la pantalla ──
  await p.setViewportSize({ width: 390, height: 844 });
  const anchos = [];
  for (const s of secciones) {
    await p.click('#c-rail button[data-sec="' + s + '"]');
    await sleep(60);
    const w = await p.evaluate(() => document.documentElement.scrollWidth);
    if (w > 392) anchos.push(s + ':' + w);
  }
  ok(anchos.length === 0, 'a 390 px ninguna sección se desborda de costado ' + JSON.stringify(anchos));
  await p.setViewportSize({ width: 1300, height: 900 });

  // ── Archivar: copia a archivedDevices y la ficha lo dice ──
  await p.click('#c-rail button[data-sec="acciones"]');
  await p.click('#c-archivar');
  await sleep(300);
  const arch = await p.evaluate(() => ({ a: !!__fakeDb.getAt('archivedDevices/dev1/deviceName'), d: __fakeDb.getAt('devices/dev1'), t: document.body.textContent }));
  ok(arch.a && arch.d === null && /archivado/.test(arch.t), 'Archivar: copia en archivedDevices, borra de devices y la ficha lo dice');

  // ══ 3. Equipo con LockSuite anterior a B.97 ══════════════════════════════════════
  await p.goto('file://' + path.resolve('celular.html') + '?id=dev4#actualizaciones');
  await p.waitForTimeout(1000);
  const viejo = await p.evaluate(() => document.querySelector('.ls-section[data-sec="actualizaciones"]').textContent);
  ok(/solo por Google Play/.test(viejo) && /No hay espacio/.test(viejo) && /Faltan 180 MB/.test(viejo),
    'equipo viejo: avisa que actualiza solo por Google Play, y muestra el último resultado con su motivo');

  ok(errores.length === 0, 'sin errores de consola/página: ' + JSON.stringify(errores.slice(0, 5)));
  await b.close(); console.log(fallas ? fallas + ' FALLAS' : 'TODO VERDE'); process.exit(fallas ? 1 : 0);
})();
