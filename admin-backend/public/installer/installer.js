/**
 * LockSuite WebADB Installer
 * Uses the WebUSB API + proper ADB protocol via @yume-chan/adb ESM modules
 */

// ─── ADB via @yume-chan/adb loaded from CDN ───────────────────────────────────
// We use a script tag approach since ESM dynamic imports work in modern browsers

let adb = null;
let adbDevice = null;
let connectedDeviceModel = 'Dispositivo Android';
let detectedAccounts = [];
let currentStep = 1;

// ─── DOM Ready ────────────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {

  const stepViews = {
    1: document.getElementById('step1'),
    2: document.getElementById('step2'),
    3: document.getElementById('step3'),
    4: document.getElementById('step4'),
    5: document.getElementById('step5'),
    6: document.getElementById('step6')
  };
  const dots = document.querySelectorAll('.dot');
  const phoneCaption = document.getElementById('phoneCaption');

  // Button bindings
  document.getElementById('btnStartStep1').addEventListener('click', () => goToStep(2));
  document.getElementById('btnBackStep2').addEventListener('click', () => goToStep(1));
  document.getElementById('btnConnectUsb').addEventListener('click', handleConnectUsb);
  document.getElementById('btnCheckAccountsAgain').addEventListener('click', checkAccounts);
  document.getElementById('btnNextStep4').addEventListener('click', () => goToStep(5));
  document.getElementById('btnNextStep3').addEventListener('click', () => goToStep(4));
  document.getElementById('btnNextStep5').addEventListener('click', () => goToStep(6));
  document.getElementById('btnGoToAdmin').addEventListener('click', () => { window.location.href = '../index.html'; });

  // Check WebUSB support on step 2
  document.getElementById('btnStartStep1').addEventListener('click', () => {
    if (!('usb' in navigator)) {
      document.getElementById('usbNotSupportedWarning').style.display = 'flex';
      document.getElementById('btnConnectUsb').disabled = true;
    }
  });

  function goToStep(step) {
    currentStep = step;
    Object.keys(stepViews).forEach(key => {
      stepViews[key].style.display = parseInt(key) === step ? 'block' : 'none';
    });
    dots.forEach((dot, index) => {
      dot.classList.remove('active', 'completed');
      if (index + 1 === step) dot.classList.add('active');
      else if (index + 1 < step) dot.classList.add('completed');
    });
    switch (step) {
      case 1: phoneCaption.innerText = 'Bienvenido a la guía interactiva'; break;
      case 2: phoneCaption.innerText = 'Activa la Depuración USB en Ajustes'; break;
      case 3: phoneCaption.innerText = 'Confirma la ventana de depuración en tu celular'; break;
      case 4:
        phoneCaption.innerText = 'Elimina temporalmente las cuentas de Google';
        checkAccounts();
        break;
      case 5:
        phoneCaption.innerText = 'Instalación y configuración en progreso...';
        startAutomatedInstallation();
        break;
      case 6: phoneCaption.innerText = '¡Instalación exitosa!'; break;
    }
  }

  // ─── CONNECT USB ──────────────────────────────────────────────────────────
  async function handleConnectUsb() {
    const statusText = document.getElementById('statusTextStep2');
    const statusDot = document.getElementById('statusDotStep2');
    const btn = document.getElementById('btnConnectUsb');

    if (!('usb' in navigator)) {
      alert('Este navegador no soporta WebUSB. Por favor abrí esta página en Google Chrome, Edge o Brave.');
      return;
    }

    try {
      btn.disabled = true;
      btn.innerText = '⏳ Conectando...';
      statusText.innerText = 'Solicitando acceso al dispositivo USB...';

      // Use the ADB WebUSB connection
      await connectAdbDevice();

      statusDot.classList.add('connected');
      statusText.innerText = 'Dispositivo USB conectado';
      goToStep(3);
      verifyAdbAuthorization();
    } catch (err) {
      console.error('USB Connect error:', err);
      btn.disabled = false;
      btn.innerText = '🔌 Conectar dispositivo';

      if (err.name === 'NotFoundError' || err.message.includes('No device selected')) {
        statusText.innerText = 'Selección cancelada. Intenta de nuevo.';
      } else {
        // Show error with BAT download option
        statusText.innerText = `Error: ${err.message}`;
        showUsbError(err.message);
      }
    }
  }

  // ─── SHOW ERROR PANEL WITH BAT FALLBACK ──────────────────────────────────
  function showUsbError(errorMsg) {
    const existing = document.getElementById('usbErrorPanel');
    if (existing) existing.remove();

    const isWindowsDriverError = errorMsg.includes('claimInterface') || errorMsg.includes('Unable to claim') || errorMsg.includes('access denied');
    const isNoInterfaceError = errorMsg.includes('interfaz ADB') || errorMsg.includes('interfaz') || errorMsg.includes('interface');

    let explanation = '';
    if (isWindowsDriverError) {
      explanation = `
        <p style="margin: 0 0 6px 0;"><b>¿Por qué pasó esto?</b> En Windows, el driver ADB del sistema ya tiene ocupada la interfaz USB del celular y no deja que el navegador acceda directamente.</p>
        <p style="margin: 0;">Esto <b>no es un error tuyo</b> — es una limitación técnica de Windows con WebUSB.</p>
      `;
    } else if (isNoInterfaceError) {
      explanation = `
        <p style="margin: 0 0 6px 0;"><b>No se encontró la interfaz ADB.</b> Asegurate de que la <b>Depuración USB</b> esté activada en Opciones de Desarrollador y que el celular esté en modo <b>Transferencia de archivos (MTP)</b>.</p>
        <p style="margin: 0;">Si ya lo hiciste, usá el instalador alternativo de abajo.</p>
      `;
    } else {
      explanation = `<p style="margin: 0;">Error técnico al conectar: <code style="font-size:0.8rem; background:rgba(0,0,0,0.3); padding:2px 6px; border-radius:4px;">${errorMsg}</code></p>`;
    }

    const panel = document.createElement('div');
    panel.id = 'usbErrorPanel';
    panel.innerHTML = `
      <div style="margin-top: 16px; padding: 18px 20px; background: rgba(239,68,68,0.08); border: 1.5px solid rgba(239,68,68,0.35); border-radius: 12px;">
        <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 12px;">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#f87171" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          <span style="color: #f87171; font-weight: 700; font-size: 0.95rem;">Error de conexión WebUSB</span>
        </div>
        <div style="font-size: 0.85rem; color: #e2e8f0; line-height: 1.6; margin-bottom: 16px;">
          ${explanation}
        </div>
        <div style="background: rgba(59,130,246,0.1); border: 1px solid rgba(59,130,246,0.3); border-radius: 10px; padding: 14px 16px;">
          <div style="font-weight: 600; color: #93c5fd; margin-bottom: 6px;">✅ Alternativa recomendada para Windows</div>
          <div style="font-size: 0.83rem; color: #94a3b8; margin-bottom: 12px;">
            Descargá este script y hacé doble clic. Instala LockSuite automáticamente usando ADB desde la línea de comandos — no necesita configuración adicional.
          </div>
          <div style="display: flex; gap: 10px; flex-wrap: wrap; align-items: center;">
            <a href="../instalar_locksuite.bat" download
               style="display: inline-flex; align-items: center; gap: 8px; padding: 10px 20px; background: linear-gradient(135deg, #2563eb, #1d4ed8); border-radius: 8px; color: #fff; text-decoration: none; font-size: 0.9rem; font-weight: 600; box-shadow: 0 2px 8px rgba(37,99,235,0.4);">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              Descargar instalar_locksuite.bat
            </a>
            <span style="font-size: 0.78rem; color: #64748b;">Para Windows · doble clic para ejecutar</span>
          </div>
          <div style="margin-top: 10px; font-size: 0.78rem; color: #64748b; line-height: 1.5;">
            <b>¿Qué hace el script?</b> Descarga ADB automáticamente si no lo tenés, instala el APK, asigna permisos de Device Owner y activa Accesibilidad — todo en un solo paso.
          </div>
        </div>
      </div>
    `;

    // Insert below the connect button
    const actionsBar = document.querySelector('#step2 .actions-bar');
    actionsBar.parentNode.insertBefore(panel, actionsBar);
  }

  // ─── ADB CONNECTION (WebUSB native protocol) ─────────────────────────────
  async function connectAdbDevice() {
    // Android USB vendor IDs (covers most brands)
    const ANDROID_VENDOR_IDS = [
      0x18d1, // Google / Nexus / Pixel
      0x04e8, // Samsung
      0x2a70, // OnePlus
      0x2717, // Xiaomi / Mi
      0x12d1, // Huawei / Honor
      0x1004, // LG
      0x0fce, // Sony / Xperia
      0x22b8, // Motorola
      0x0bb4, // HTC
      0x17ef, // Lenovo
      0x1bbb, // Alcatel
      0x19d2, // ZTE
      0x2d95, // vivo
      0x1ebf, // MediaTek
      0x0489, // Foxconn
      0x413c, // Dell
      0x05ac, // Apple (for testing)
      0x8087, // Intel
    ];

    let device = null;

    // Try with vendor ID filters first
    try {
      device = await navigator.usb.requestDevice({
        filters: ANDROID_VENDOR_IDS.map(id => ({ vendorId: id }))
      });
    } catch (e) {
      if (e.name === 'NotFoundError') {
        // If no matching device found with vendor IDs, try with no filter (shows all USB devices)
        try {
          device = await navigator.usb.requestDevice({ filters: [] });
        } catch (e2) {
          throw e2;
        }
      } else {
        throw e;
      }
    }

    if (!device) throw new Error('No se seleccionó ningún dispositivo.');

    await device.open();

    if (device.configuration === null) {
      await device.selectConfiguration(1);
    }

    // Find ADB interface (class 255, subclass 42, protocol 1)
    let adbInterface = null;
    for (const iface of device.configuration.interfaces) {
      for (const alt of iface.alternates) {
        if (alt.interfaceClass === 0xff && alt.interfaceSubclass === 0x42 && alt.interfaceProtocol === 0x01) {
          adbInterface = { iface, alt };
          break;
        }
      }
      if (adbInterface) break;
    }

    if (!adbInterface) {
      await device.close();
      throw new Error(
        'No se encontró la interfaz ADB en el dispositivo.\n\n' +
        '¿Activaste la Depuración USB en Opciones de Desarrollador?\n' +
        'El celular debe estar desbloqueado y en modo "Transferencia de archivos" (MTP).'
      );
    }

    await device.claimInterface(adbInterface.iface.interfaceNumber);

    const epIn = adbInterface.alt.endpoints.find(e => e.direction === 'in');
    const epOut = adbInterface.alt.endpoints.find(e => e.direction === 'out');

    if (!epIn || !epOut) {
      throw new Error('No se encontraron los endpoints USB del protocolo ADB.');
    }

    adbDevice = { device, epIn: epIn.endpointNumber, epOut: epOut.endpointNumber, ifaceNum: adbInterface.iface.interfaceNumber };

    // Perform ADB CONNECT handshake
    await adbHandshake();
  }

  // ─── ADB RAW PROTOCOL ─────────────────────────────────────────────────────
  function strToCmd(s) {
    return (s.charCodeAt(0) | s.charCodeAt(1) << 8 | s.charCodeAt(2) << 16 | s.charCodeAt(3) << 24) >>> 0;
  }
  function cmdToStr(n) {
    return String.fromCharCode(n & 0xff, n >> 8 & 0xff, n >> 16 & 0xff, n >> 24 & 0xff);
  }

  async function adbSend(cmd, arg0, arg1, data = new Uint8Array(0)) {
    const cmdCode = strToCmd(cmd);
    const magic = (cmdCode ^ 0xffffffff) >>> 0;
    let checksum = 0;
    for (let i = 0; i < data.length; i++) checksum = (checksum + data[i]) & 0xffffffff;

    const header = new ArrayBuffer(24);
    const v = new DataView(header);
    v.setUint32(0, cmdCode, true);
    v.setUint32(4, arg0, true);
    v.setUint32(8, arg1, true);
    v.setUint32(12, data.length, true);
    v.setUint32(16, checksum, true);
    v.setUint32(20, magic, true);

    await adbDevice.device.transferOut(adbDevice.epOut, header);
    if (data.length > 0) {
      await adbDevice.device.transferOut(adbDevice.epOut, data.buffer instanceof ArrayBuffer ? data.buffer : data);
    }
  }

  async function adbRead() {
    const res = await adbDevice.device.transferIn(adbDevice.epIn, 24);
    if (res.status !== 'ok') throw new Error('Error al leer paquete ADB del USB.');
    const v = new DataView(res.data.buffer);
    const cmd = cmdToStr(v.getUint32(0, true));
    const arg0 = v.getUint32(4, true);
    const arg1 = v.getUint32(8, true);
    const len = v.getUint32(12, true);
    let data = new Uint8Array(0);
    if (len > 0) {
      const dr = await adbDevice.device.transferIn(adbDevice.epIn, len);
      data = new Uint8Array(dr.data.buffer);
    }
    return { cmd, arg0, arg1, data };
  }

  async function adbHandshake() {
    const banner = new TextEncoder().encode('host::LockSuiteInstaller\0');
    await adbSend('CNXN', 0x01000000, 1048576, banner);

    let p = await adbRead();

    if (p.cmd === 'AUTH') {
      // Generate RSA key pair for authentication
      const keyPair = await crypto.subtle.generateKey(
        { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' },
        true, ['sign', 'verify']
      );

      // Try signing the token
      if (p.arg0 === 2) { // AUTH_TOKEN
        const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', keyPair.privateKey, p.data);
        await adbSend('AUTH', 2, 0, new Uint8Array(sig));
        p = await adbRead();
      }

      if (p.cmd === 'AUTH') {
        // Device needs our public key - show it
        const spki = await crypto.subtle.exportKey('spki', keyPair.publicKey);
        const b64 = btoa(String.fromCharCode(...new Uint8Array(spki)));
        const pubKeyData = new TextEncoder().encode(b64 + ' LockSuiteInstaller\0');
        await adbSend('AUTH', 3, 0, pubKeyData);
        p = await adbRead();
      }
    }

    if (p.cmd !== 'CNXN') {
      throw new Error(`Handshake ADB fallido. Respuesta: ${p.cmd}. ¿Aceptaste el permiso de depuración en el celular?`);
    }

    // Store local/remote IDs
    adbDevice.nextLocalId = 1;
  }

  async function adbShell(command) {
    const localId = adbDevice.nextLocalId++;
    const service = new TextEncoder().encode(`shell:${command}`);
    await adbSend('OPEN', localId, 0, service);

    let p = await adbRead();
    if (p.cmd !== 'OKAY') throw new Error(`No se pudo abrir shell para: ${command}`);

    const remoteId = p.arg0;
    let output = '';

    while (true) {
      p = await adbRead();
      if (p.cmd === 'WRTE') {
        output += new TextDecoder().decode(p.data);
        await adbSend('OKAY', localId, remoteId);
      } else if (p.cmd === 'CLSE') {
        await adbSend('CLSE', localId, remoteId);
        break;
      }
    }
    return output;
  }

  // ─── VERIFY ADB ───────────────────────────────────────────────────────────
  async function verifyAdbAuthorization() {
    const statusText = document.getElementById('statusTextStep3');
    try {
      statusText.innerText = 'Verificando autorización ADB...';
      const model = await adbShell('getprop ro.product.model');
      const brand = await adbShell('getprop ro.product.brand');
      connectedDeviceModel = `${brand.trim()} ${model.trim()}`;
      statusText.innerText = `✓ Conectado: ${connectedDeviceModel}`;
      document.getElementById('btnNextStep3').disabled = false;
    } catch (err) {
      console.warn('ADB authorization pending:', err);
      statusText.innerText = '⏳ Esperando que aceptes el permiso en tu celular...';
      setTimeout(verifyAdbAuthorization, 2500);
    }
  }

  // ─── CHECK ACCOUNTS ───────────────────────────────────────────────────────
  async function checkAccounts() {
    const list = document.getElementById('accountCardsList');
    const countText = document.getElementById('accountCountText');
    const dot = document.getElementById('statusDotAccount');
    const banner = document.getElementById('accountAlertBanner');

    list.innerHTML = '<div style="color: var(--text-secondary);">Escaneando cuentas registradas en el celular...</div>';
    countText.innerText = 'Escaneando...';
    document.getElementById('btnNextStep4').disabled = true;

    try {
      const output = await adbShell('dumpsys account');
      const accounts = [];
      const regex = /Account\s*\{?\s*name=([^,\}\s]+)[^}]*type=([^\}\s]+)/gi;
      let match;
      while ((match = regex.exec(output)) !== null) {
        const name = match[1].trim();
        const type = match[2].trim();
        if (name && !accounts.some(a => a.name === name)) accounts.push({ name, type });
      }
      if (accounts.length === 0) {
        const emailRegex = /name=([a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,})/g;
        while ((match = emailRegex.exec(output)) !== null) {
          if (!accounts.some(a => a.name === match[1])) accounts.push({ name: match[1], type: 'com.google' });
        }
      }
      detectedAccounts = accounts;
      list.innerHTML = '';

      if (accounts.length === 0) {
        list.innerHTML = `
          <div class="account-card" style="background: rgba(16,185,129,0.1); border-color: rgba(16,185,129,0.3);">
            <div class="account-info">
              <span class="account-icon" style="color:#10b981;">✓</span>
              <span class="account-name">No se encontraron cuentas activas. ¡Listo para continuar!</span>
            </div>
          </div>`;
        countText.innerText = '0 cuentas — OK';
        dot.className = 'status-dot connected';
        banner.style.display = 'none';
        document.getElementById('btnNextStep4').disabled = false;
      } else {
        banner.style.display = 'flex';
        dot.className = 'status-dot';
        countText.innerText = `${accounts.length} cuenta(s) pendiente(s) de eliminar`;
        accounts.forEach(acc => {
          const card = document.createElement('div');
          card.className = 'account-card';
          card.innerHTML = `
            <div class="account-info">
              <span class="account-icon">G</span>
              <div>
                <div class="account-name">${acc.name}</div>
                <div style="font-size:0.75rem;color:var(--text-secondary);">${acc.type}</div>
              </div>
            </div>
            <span style="color:var(--danger-color);font-size:0.85rem;font-weight:600;">Pendiente de eliminar</span>`;
          list.appendChild(card);
        });
        document.getElementById('btnNextStep4').disabled = true;
      }
    } catch (err) {
      list.innerHTML = `<div style="color:var(--danger-color);">Error al escanear: ${err.message}</div>`;
      countText.innerText = 'Error de escaneo';
    }
  }

  // ─── INSTALL ──────────────────────────────────────────────────────────────
  async function startAutomatedInstallation() {
    const progressBarFill = document.getElementById('progressBarFill');
    const progressStatusText = document.getElementById('progressStatusText');
    const progressPercentText = document.getElementById('progressPercentText');
    const terminalLog = document.getElementById('terminalLog');

    function log(msg) {
      terminalLog.innerHTML += `[${new Date().toLocaleTimeString()}] ${msg}<br>`;
      terminalLog.scrollTop = terminalLog.scrollHeight;
    }
    function setProgress(msg, pct) {
      progressStatusText.innerText = msg;
      progressBarFill.style.width = `${pct}%`;
      progressPercentText.innerText = `${pct}%`;
    }

    try {
      log('Descargando la última versión de LockSuite...');
      setProgress('Descargando APK desde el servidor...', 5);

      const apkResponse = await fetch('../locksuite-latest.apk');
      if (!apkResponse.ok) throw new Error('No se pudo descargar el APK. Comprueba tu conexión a internet.');
      const apkBuffer = await apkResponse.arrayBuffer();
      const apkBytes = new Uint8Array(apkBuffer);

      log(`APK descargado (${Math.round(apkBytes.length / 1024 / 1024 * 10) / 10} MB). Transfiriendo e instalando...`);
      setProgress('Iniciando instalación...', 15);

      let installResult = '';
      try {
        log('Iniciando instalación por stream directo ADB (exec:pm install)...');
        installResult = await installApkDirectStream(apkBytes, setProgress);
        log(`Resultado: ${installResult.trim() || 'Success'}`);
      } catch (streamErr) {
        log(`Stream directo falló (${streamErr.message}). Probando método de transferencia sync...`);
        await pushFileAdb(apkBytes, '/data/local/tmp/locksuite.apk', (uploaded) => {
          const pct = Math.min(75, 15 + Math.round((uploaded / apkBytes.length) * 60));
          setProgress(`Transfiriendo... ${Math.round((uploaded / apkBytes.length) * 100)}%`, pct);
        });
        log('Archivo transferido a /data/local/tmp/. Ejecutando pm install...');
        installResult = await adbShell('pm install -r /data/local/tmp/locksuite.apk');
        await adbShell('rm -f /data/local/tmp/locksuite.apk');
        log(`pm install: ${installResult.trim()}`);
      }

      if (installResult.toLowerCase().includes('failure') && !installResult.toLowerCase().includes('success')) {
        throw new Error(`Error de instalación: ${installResult.trim()}`);
      }

      log('¡Instalación OK! Aplicando permisos de Device Owner...');
      setProgress('Aplicando permisos de Device Owner...', 88);

      const doResult = await adbShell('dpm set-device-owner com.ejemplo.locksuite/.receiver.DeviceAdminReceiver');
      log(`Device Owner: ${doResult.trim()}`);

      log('Habilitando Servicio de Accesibilidad...');
      setProgress('Habilitando Accesibilidad...', 95);

      await adbShell('settings put secure enabled_accessibility_services com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService');
      await adbShell('settings put secure accessibility_enabled 1');

      log('✓ ¡Todo configurado exitosamente!');
      setProgress('¡Instalación completada!', 100);
      document.getElementById('btnNextStep5').disabled = false;

    } catch (err) {
      console.error('Installation error:', err);
      log(`<span style="color:var(--danger-color);">[ERROR] ${err.message}</span>`);
      progressStatusText.innerText = `Error: ${err.message}`;
    }
  }

  // ─── DIRECT STREAMING APK INSTALL ───────────────────────────────────────
  async function installApkDirectStream(bytes, onProgress) {
    const localId = adbDevice.nextLocalId++;
    const service = new TextEncoder().encode(`exec:pm install -S ${bytes.length} -r`);
    await adbSend('OPEN', localId, 0, service);

    let p = await adbRead();
    if (p.cmd !== 'OKAY') {
      throw new Error(`Servicio exec rechazado: cmd=${p.cmd}`);
    }
    const remoteId = p.arg0;

    const CHUNK_SIZE = 64 * 1024;
    let offset = 0;
    while (offset < bytes.length) {
      const chunk = bytes.subarray(offset, Math.min(offset + CHUNK_SIZE, bytes.length));
      await adbSend('WRTE', localId, remoteId, chunk);

      let ack = await adbRead();
      if (ack.cmd !== 'OKAY') {
        throw new Error(`Error de transferencia en offset ${offset} (cmd=${ack.cmd})`);
      }

      offset += chunk.length;
      if (onProgress) {
        const pct = Math.min(80, 15 + Math.round((offset / bytes.length) * 65));
        onProgress(`Instalando... ${Math.round((offset / bytes.length) * 100)}%`, pct);
      }
    }

    await adbSend('CLSE', localId, remoteId);

    let output = '';
    while (true) {
      p = await adbRead();
      if (p.cmd === 'WRTE') {
        output += new TextDecoder().decode(p.data);
        await adbSend('OKAY', localId, remoteId);
      } else if (p.cmd === 'CLSE') {
        await adbSend('CLSE', localId, remoteId);
        break;
      }
    }
    return output;
  }

  // ─── ADB PUSH FILE FALLBACK (sync protocol) ─────────────────────────────
  async function pushFileAdb(bytes, remotePath, onProgress) {
    const localId = adbDevice.nextLocalId++;
    const service = new TextEncoder().encode('sync:');
    await adbSend('OPEN', localId, 0, service);

    let p = await adbRead();
    if (p.cmd !== 'OKAY') throw new Error('No se pudo abrir el servicio ADB sync.');
    const remoteId = p.arg0;

    const modeStr = ',33188';
    const fullPathStr = remotePath + modeStr;
    const pathBytes = new TextEncoder().encode(fullPathStr);

    const sendReq = new Uint8Array(8 + pathBytes.length);
    sendReq[0] = 0x53; sendReq[1] = 0x45; sendReq[2] = 0x4e; sendReq[3] = 0x44; // SEND
    new DataView(sendReq.buffer).setUint32(4, pathBytes.length, true);
    sendReq.set(pathBytes, 8);

    await adbSend('WRTE', localId, remoteId, sendReq);
    p = await adbRead();
    if (p.cmd !== 'OKAY') throw new Error('Error al iniciar envío SEND.');

    const CHUNK_SIZE = 64 * 1024;
    let offset = 0;
    while (offset < bytes.length) {
      const chunk = bytes.subarray(offset, Math.min(offset + CHUNK_SIZE, bytes.length));
      const dataMsg = new Uint8Array(8 + chunk.length);
      dataMsg[0] = 0x44; dataMsg[1] = 0x41; dataMsg[2] = 0x54; dataMsg[3] = 0x41; // DATA
      new DataView(dataMsg.buffer).setUint32(4, chunk.length, true);
      dataMsg.set(chunk, 8);

      await adbSend('WRTE', localId, remoteId, dataMsg);
      await adbRead();
      offset += chunk.length;
      if (onProgress) onProgress(offset);
    }

    const doneMsg = new Uint8Array(8);
    doneMsg[0] = 0x44; doneMsg[1] = 0x4f; doneMsg[2] = 0x4e; doneMsg[3] = 0x45; // DONE
    new DataView(doneMsg.buffer).setUint32(4, Math.floor(Date.now() / 1000), true);
    await adbSend('WRTE', localId, remoteId, doneMsg);

    let resp = await adbRead();
    while (resp.cmd === 'OKAY') resp = await adbRead();

    await adbSend('CLSE', localId, remoteId);
  }

  if (!('usb' in navigator)) {
    console.warn('WebUSB not supported in this browser.');
  }
});
