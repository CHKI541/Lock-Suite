/**
 * LockSuite WebADB Installer
 * Powered by WebADB Engine (Google Chrome Labs / Tango)
 */

let adbInstance = null;
let webUsbTransport = null;
let connectedDeviceModel = 'Dispositivo Android';
let detectedAccounts = [];
let currentStep = 1;

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

  // QR Modal Bindings
  const btnShowQrHeader = document.getElementById('btnShowQrModal');
  const btnShowQrStep1 = document.getElementById('btnShowQrModalStep1');
  const qrOverlay = document.getElementById('qrModalOverlay');
  const btnCloseQr = document.getElementById('btnCloseQrModal');
  const btnCloseQrBottom = document.getElementById('btnCloseQrModalBottom');
  const qrImage = document.getElementById('qrCodeImage');

  function openQrModal() {
    const qrPayload = JSON.stringify({
      "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.ejemplo.locksuite/.receiver.DeviceAdminReceiver",
      "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://locksuite-nueva.web.app/locksuite-latest.apk",
      "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
    });
    qrImage.src = `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(qrPayload)}`;
    qrOverlay.style.display = 'flex';
  }

  if (btnShowQrHeader) btnShowQrHeader.addEventListener('click', openQrModal);
  if (btnShowQrStep1) btnShowQrStep1.addEventListener('click', openQrModal);
  if (btnCloseQr) btnCloseQr.addEventListener('click', () => { qrOverlay.style.display = 'none'; });
  if (btnCloseQrBottom) btnCloseQrBottom.addEventListener('click', () => { qrOverlay.style.display = 'none'; });
  if (qrOverlay) {
    qrOverlay.addEventListener('click', (e) => {
      if (e.target === qrOverlay) qrOverlay.style.display = 'none';
    });
  }

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

      // Open WebUSB transport via webadb.js engine
      webUsbTransport = await Adb.open("WebUSB");
      adbInstance = await webUsbTransport.connectAdb("host::LockSuite");

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

    const actionsBar = document.querySelector('#step2 .actions-bar');
    if (actionsBar) actionsBar.parentNode.insertBefore(panel, actionsBar);
  }

  // ─── ADB SHELL EXECUTION ──────────────────────────────────────────────────
  async function adbShell(command) {
    if (!adbInstance) throw new Error('Conexión ADB no disponible');
    const stream = await adbInstance.shell(command);
    return await stream.readAll();
  }

  // ─── VERIFY ADB ───────────────────────────────────────────────────────────
  async function verifyAdbAuthorization() {
    const statusText = document.getElementById('statusTextStep3');
    const btnNext = document.getElementById('btnNextStep3');
    try {
      statusText.innerText = 'Verificando autorización ADB...';
      const model = await adbShell('getprop ro.product.model');
      const brand = await adbShell('getprop ro.product.brand');
      connectedDeviceModel = `${brand.trim()} ${model.trim()}`;
      statusText.innerText = `✓ Conectado: ${connectedDeviceModel}`;
      btnNext.disabled = false;
    } catch (err) {
      console.warn('ADB authorization pending:', err);
      statusText.innerText = '⏳ Esperando que aceptes el permiso en tu celular... (si ya lo aceptaste, podés hacer clic en Siguiente)';
      btnNext.disabled = false;
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
      console.warn('Account check failed:', err);
      list.innerHTML = `
        <div style="padding: 14px 16px; background: rgba(245,158,11,0.1); border: 1px solid rgba(245,158,11,0.3); border-radius: 10px; color: #fbbf24; font-size: 0.85rem; line-height: 1.5;">
          ⚠️ No se pudo comprobar automáticamente. Asegúrate manualmente en tu celular de que <b>no haya cuentas de Google registradas</b> (Ajustes &rarr; Cuentas &rarr; Quitar cuenta) y luego presioná Siguiente.
        </div>`;
      countText.innerText = 'Verificación manual';
      dot.className = 'status-dot';
      document.getElementById('btnNextStep4').disabled = false;
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
      log('Iniciando descarga del APK directamente en el dispositivo...');
      setProgress('Descargando APK en el celular...', 15);

      const apkUrl = 'https://locksuite-nueva.web.app/locksuite-latest.apk';
      const targetPath = '/data/local/tmp/locksuite.apk';

      // Download directly on phone using shell curl/wget
      log('Descargando paquete LockSuite desde el servidor...');
      const downloadCmd = `curl -L -s -o ${targetPath} ${apkUrl} || wget -O ${targetPath} ${apkUrl} || toybox wget -O ${targetPath} ${apkUrl} || busybox wget -O ${targetPath} ${apkUrl}`;
      await adbShell(downloadCmd);
      setProgress('Verificando archivo descargado...', 60);

      // Verify file downloaded successfully on device
      const checkResult = await adbShell(`ls -l ${targetPath}`);
      log(`Verificación en el dispositivo: ${checkResult.trim()}`);

      if (!checkResult.includes('locksuite.apk')) {
        throw new Error('No se pudo descargar el APK directamente en el celular. Asegurate de que el celular tenga acceso a internet o WiFi.');
      }

      log('APK verificado OK. Instalando en el sistema con pm install...');
      setProgress('Instalando LockSuite...', 75);

      const installResult = await adbShell(`pm install -r ${targetPath}`);
      log(`pm install: ${installResult.trim()}`);
      await adbShell(`rm -f ${targetPath}`);

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

      // Show error panel with BAT download option
      showUsbError(err.message);
    }
  }

  if (!('usb' in navigator)) {
    console.warn('WebUSB not supported in this browser.');
  }
});
