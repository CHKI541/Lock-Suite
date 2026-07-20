/**
 * LockSuite WebADB Installer Logic
 */

document.addEventListener('DOMContentLoaded', () => {
  let currentStep = 1;
  const adb = new WebADB();
  let connectedDeviceModel = 'Dispositivo Android';
  let detectedAccounts = [];

  // DOM Elements
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

  // Step 1 Controls
  document.getElementById('btnStartStep1').addEventListener('click', () => goToStep(2));

  // Step 2 Controls
  document.getElementById('btnBackStep2').addEventListener('click', () => goToStep(1));
  document.getElementById('btnConnectUsb').addEventListener('click', handleConnectUsb);

  // Step 3 Controls
  const btnNextStep3 = document.getElementById('btnNextStep3');
  btnNextStep3.addEventListener('click', () => goToStep(4));

  // Step 4 Controls
  const btnCheckAccountsAgain = document.getElementById('btnCheckAccountsAgain');
  const btnNextStep4 = document.getElementById('btnNextStep4');
  btnCheckAccountsAgain.addEventListener('click', checkAccounts);
  btnNextStep4.addEventListener('click', () => goToStep(5));

  // Step 5 Controls
  const btnNextStep5 = document.getElementById('btnNextStep5');
  btnNextStep5.addEventListener('click', () => goToStep(6));

  // Step 6 Controls
  document.getElementById('btnGoToAdmin').addEventListener('click', () => {
    window.location.href = '../index.html';
  });

  function goToStep(step) {
    currentStep = step;

    // Toggle views
    Object.keys(stepViews).forEach(key => {
      stepViews[key].style.display = parseInt(key) === step ? 'block' : 'none';
    });

    // Update dots
    dots.forEach((dot, index) => {
      const stepNum = index + 1;
      dot.classList.remove('active', 'completed');
      if (stepNum === step) {
        dot.classList.add('active');
      } else if (stepNum < step) {
        dot.classList.add('completed');
      }
    });

    // Update Phone Captions & Illustrations
    switch (step) {
      case 1:
        phoneCaption.innerText = 'Bienvenido a la guía interactiva';
        break;
      case 2:
        phoneCaption.innerText = 'Activa la Depuración USB en Ajustes';
        break;
      case 3:
        phoneCaption.innerText = 'Confirma la ventana de depuración en tu celular';
        break;
      case 4:
        phoneCaption.innerText = 'Elimina temporalmente las cuentas de Google';
        if (adb.device) checkAccounts();
        break;
      case 5:
        phoneCaption.innerText = 'Instalación y configuración en progreso...';
        startAutomatedInstallation();
        break;
      case 6:
        phoneCaption.innerText = '¡Instalación exitosa!';
        break;
    }
  }

  async function handleConnectUsb() {
    const statusTextStep2 = document.getElementById('statusTextStep2');
    const statusDotStep2 = document.getElementById('statusDotStep2');
    const btnConnectUsb = document.getElementById('btnConnectUsb');

    try {
      statusTextStep2.innerText = 'Solicitando acceso USB...';
      btnConnectUsb.disabled = true;

      await adb.requestDevice();
      statusTextStep2.innerText = 'Estableciendo protocolo ADB...';

      await adb.connect();

      statusDotStep2.classList.add('connected');
      statusTextStep2.innerText = 'Dispositivo USB Detectado';

      // Move to Step 3 for authorization check
      goToStep(3);
      verifyAdbAuthorization();
    } catch (err) {
      console.error('USB Connect error:', err);
      statusTextStep2.innerText = `Error: ${err.message}`;
      btnConnectUsb.disabled = false;
      alert(`No se pudo conectar: ${err.message}`);
    }
  }

  async function verifyAdbAuthorization() {
    const statusTextStep3 = document.getElementById('statusTextStep3');
    try {
      statusTextStep3.innerText = 'Verificando autorización ADB...';
      
      const model = await adb.shell('getprop ro.product.model');
      const brand = await adb.shell('getprop ro.product.brand');
      connectedDeviceModel = `${brand.trim()} ${model.trim()}`;

      statusTextStep3.innerText = `Conectado: ${connectedDeviceModel}`;
      btnNextStep3.disabled = false;
    } catch (err) {
      console.warn('ADB Authorization pending:', err);
      statusTextStep3.innerText = 'Esperando que aceptes la casilla en tu celular...';
      setTimeout(verifyAdbAuthorization, 2000);
    }
  }

  async function checkAccounts() {
    const accountCardsList = document.getElementById('accountCardsList');
    const accountCountText = document.getElementById('accountCountText');
    const statusDotAccount = document.getElementById('statusDotAccount');
    const accountAlertBanner = document.getElementById('accountAlertBanner');

    accountCardsList.innerHTML = '<div style="color: var(--text-secondary);">Escaneando cuentas registradas en el celular...</div>';
    accountCountText.innerText = 'Escaneando...';
    btnNextStep4.disabled = true;

    try {
      detectedAccounts = await adb.getDeviceAccounts();
      accountCardsList.innerHTML = '';

      if (detectedAccounts.length === 0) {
        accountCardsList.innerHTML = `
          <div class="account-card" style="background: rgba(16, 185, 129, 0.1); border-color: rgba(16, 185, 129, 0.3);">
            <div class="account-info">
              <span class="account-icon" style="color: #10b981;">✓</span>
              <span class="account-name">No se encontraron cuentas activas. ¡Listo para continuar!</span>
            </div>
          </div>
        `;
        accountCountText.innerText = '0 cuentas encontradas (OK)';
        statusDotAccount.className = 'status-dot connected';
        accountAlertBanner.style.display = 'none';
        btnNextStep4.disabled = false;
      } else {
        accountAlertBanner.style.display = 'flex';
        statusDotAccount.className = 'status-dot';
        accountCountText.innerText = `Se encontraron ${detectedAccounts.length} cuenta(s)`;

        detectedAccounts.forEach(acc => {
          const card = document.createElement('div');
          card.className = 'account-card';
          card.innerHTML = `
            <div class="account-info">
              <span class="account-icon">G</span>
              <div>
                <div class="account-name">${acc.name}</div>
                <div style="font-size: 0.75rem; color: var(--text-secondary);">${acc.type}</div>
              </div>
            </div>
            <span style="color: var(--danger-color); font-size: 0.85rem; font-weight: 600;">Pendiente de eliminar</span>
          `;
          accountCardsList.appendChild(card);
        });

        btnNextStep4.disabled = true;
      }
    } catch (err) {
      console.error('Error checking accounts:', err);
      accountCardsList.innerHTML = `<div style="color: var(--danger-color);">Error al escanear cuentas: ${err.message}</div>`;
      accountCountText.innerText = 'Error de escaneo';
    }
  }

  async function startAutomatedInstallation() {
    const progressBarFill = document.getElementById('progressBarFill');
    const progressStatusText = document.getElementById('progressStatusText');
    const progressPercentText = document.getElementById('progressPercentText');
    const terminalLog = document.getElementById('terminalLog');

    function logTerminal(msg) {
      terminalLog.innerHTML += `[${new Date().toLocaleTimeString()}] ${msg}<br>`;
      terminalLog.scrollTop = terminalLog.scrollHeight;
    }

    try {
      logTerminal('Descargando la última versión de LockSuite (locksuite-latest.apk)...');
      progressStatusText.innerText = 'Descargando paquete de instalación...';
      progressBarFill.style.width = '5%';
      progressPercentText.innerText = '5%';

      const apkResponse = await fetch('../locksuite-latest.apk');
      if (!apkResponse.ok) {
        throw new Error('No se pudo descargar el archivo APK desde el servidor web.');
      }
      const apkBlob = await apkResponse.blob();

      logTerminal(`APK descargado (${Math.round(apkBlob.size / 1024 / 1024)} MB). Transfiriendo vía ADB WebUSB...`);

      await adb.installApk(apkBlob, (statusMsg, percent) => {
        progressStatusText.innerText = statusMsg;
        progressBarFill.style.width = `${percent}%`;
        progressPercentText.innerText = `${percent}%`;
        logTerminal(statusMsg);
      });

      logTerminal('¡Proceso completado exitosamente! Permisos asignados.');
      btnNextStep5.disabled = false;
    } catch (err) {
      console.error('Installation error:', err);
      logTerminal(`<span style="color: var(--danger-color);">[ERROR] ${err.message}</span>`);
      progressStatusText.innerText = `Falló: ${err.message}`;
    }
  }
});
