const auth = firebase.auth();
const functionsRef = firebase.functions();

const loginScreen = document.getElementById("login-screen");
const dashboardScreen = document.getElementById("dashboard-screen");
const emailInput = document.getElementById("email-input");
const passwordInput = document.getElementById("password-input");
const loginButton = document.getElementById("login-button");
const loginError = document.getElementById("login-error");
const logoutButton = document.getElementById("logout-button");
const refreshButton = document.getElementById("refresh-button");
const devicesContainer = document.getElementById("devices-container");
const globalError = document.getElementById("global-error");
const cardTemplate = document.getElementById("device-card-template");

// Elementos del modal de PIN (uno solo, se reutiliza para cualquier tarjeta)
const pinModal = document.getElementById("pin-modal");
const pinModalDeviceName = document.querySelector(".modal-device-name");
const pinModalInput = document.getElementById("pin-modal-input");
const pinModalRemember = document.getElementById("pin-modal-remember");
const pinModalError = document.getElementById("pin-modal-error");
const pinModalCancel = document.getElementById("pin-modal-cancel");
const pinModalConfirm = document.getElementById("pin-modal-confirm");

auth.onAuthStateChanged((user) => {
  if (user) {
    loginScreen.classList.add("hidden");
    dashboardScreen.classList.remove("hidden");
    loadDevices();
  } else {
    dashboardScreen.classList.add("hidden");
    loginScreen.classList.remove("hidden");
  }
});

loginButton.addEventListener("click", async () => {
  loginError.textContent = "";
  try {
    await auth.signInWithEmailAndPassword(emailInput.value.trim(), passwordInput.value);
  } catch (e) {
    loginError.textContent = traducirErrorAuth(e.code);
  }
});

logoutButton.addEventListener("click", () => auth.signOut());
refreshButton.addEventListener("click", loadDevices);

async function loadDevices() {
  globalError.textContent = "";
  devicesContainer.innerHTML = '<p class="loading-text">Cargando dispositivos…</p>';
  try {
    const listDevices = functionsRef.httpsCallable("listDevices");
    const result = await listDevices();
    renderDevices(result.data.devices || []);
  } catch (e) {
    devicesContainer.innerHTML = "";
    globalError.textContent = "No se pudieron cargar los dispositivos: " + e.message;
  }
}

function renderDevices(devices) {
  devicesContainer.innerHTML = "";
  if (devices.length === 0) {
    devicesContainer.innerHTML = '<p class="loading-text">Todavía no hay dispositivos registrados.</p>';
    return;
  }
  devices
    .sort((a, b) => (b.lastSeen || 0) - (a.lastSeen || 0))
    .forEach((device) => devicesContainer.appendChild(buildDeviceCard(device)));
}

function buildDeviceCard(device) {
  const node = cardTemplate.content.cloneNode(true);

  node.querySelector(".device-model").textContent = device.model;
  node.querySelector(".device-id").textContent = device.id;

  const kioskBadge = node.querySelector(".kiosk-badge");
  if (device.kioskEnabled) {
    kioskBadge.textContent = `Kiosco activo (${device.allowedAppCount} apps)`;
    kioskBadge.classList.add("active");
  } else {
    kioskBadge.textContent = "Sin lista blanca";
    kioskBadge.classList.add("inactive");
  }

  node.querySelector(".last-seen").textContent = device.lastSeen
    ? "Última conexión: " + new Date(device.lastSeen).toLocaleString("es-AR")
    : "Todavía no se conectó (sin token FCM)";

  const statusEl = node.querySelector(".card-status");
  const lockBtn = node.querySelector(".lock-btn");
  const allowlistInput = node.querySelector(".allowlist-input");
  const allowlistBtn = node.querySelector(".allowlist-btn");
  const trustStatusEl = node.querySelector(".trust-status");
  const forgetBtn = node.querySelector(".forget-pin-btn");

  // Las referencias a estos elementos siguen siendo válidas después de que
  // el <template> se inserte en el DOM real (solo el DocumentFragment
  // contenedor queda vacío, no los elementos que ya movió adentro).

  function updateTrustUI(trusted) {
    if (!device.hasPinConfigured) {
      trustStatusEl.textContent = "Ese celular todavía no configuró un PIN.";
      forgetBtn.classList.add("hidden");
    } else if (trusted) {
      trustStatusEl.textContent = "🔓 PIN recordado en esta cuenta";
      forgetBtn.classList.remove("hidden");
    } else {
      trustStatusEl.textContent = "🔒 Va a pedir el PIN de este celular";
      forgetBtn.classList.add("hidden");
    }
  }
  updateTrustUI(device.trustedForMe);

  forgetBtn.addEventListener("click", async () => {
    forgetBtn.disabled = true;
    try {
      const forgetDeviceTrust = functionsRef.httpsCallable("forgetDeviceTrust");
      await forgetDeviceTrust({ deviceId: device.id });
      updateTrustUI(false);
      statusEl.textContent = "Listo — la próxima acción va a volver a pedir el PIN.";
    } catch (e) {
      statusEl.textContent = "✗ No se pudo olvidar: " + e.message;
    } finally {
      forgetBtn.disabled = false;
    }
  });

  lockBtn.addEventListener("click", () =>
    runCommand(device, "LOCK_DEVICE", null, lockBtn, statusEl, updateTrustUI)
  );

  bindToggle(node, ".toggle-wifi", device.wifiBlocked, device, "BLOCK_WIFI", "UNBLOCK_WIFI", statusEl, updateTrustUI);
  bindToggle(node, ".toggle-bluetooth", device.bluetoothBlocked, device, "BLOCK_BLUETOOTH", "UNBLOCK_BLUETOOTH", statusEl, updateTrustUI);
  bindToggle(node, ".toggle-vpn", device.vpnBlocked, device, "BLOCK_VPN", "UNBLOCK_VPN", statusEl, updateTrustUI);
  bindToggle(node, ".toggle-install", device.installAppsBlocked, device, "BLOCK_INSTALL_APPS", "UNBLOCK_INSTALL_APPS", statusEl, updateTrustUI);

  allowlistInput.value = "";

  allowlistBtn.addEventListener("click", () => {
    const packages = allowlistInput.value.split(",").map((p) => p.trim()).filter(Boolean);
    if (packages.length === 0) {
      statusEl.textContent = "Ingresá al menos un paquete.";
      return;
    }
    runCommand(device, "UPDATE_ALLOWLIST", packages, allowlistBtn, statusEl, updateTrustUI);
  });

  return node;
}

function bindToggle(node, selector, currentValue, device, onCommand, offCommand, statusEl, updateTrustUI) {
  const checkbox = node.querySelector(selector);
  checkbox.checked = !!currentValue;
  checkbox.addEventListener("change", () => {
    const command = checkbox.checked ? onCommand : offCommand;
    runCommand(device, command, null, checkbox, statusEl, updateTrustUI, () => {
      checkbox.checked = !checkbox.checked; // revierte el toggle si falló/se canceló
    });
  });
}

/**
 * Envía un comando. Si el backend responde "hace falta el PIN de este
 * celular", abre el modal, y si lo confirman reintenta el mismo comando
 * agregando devicePin (y rememberDevice si tildaron "no preguntar más").
 */
async function runCommand(device, command, packages, triggerEl, statusEl, updateTrustUI, onGiveUp) {
  triggerEl.disabled = true;
  statusEl.textContent = "Enviando…";

  let devicePin = null;
  let rememberDevice = false;
  let pinErrorForModal = "";

  while (true) {
    try {
      const sendCommand = functionsRef.httpsCallable("sendCommand");
      await sendCommand({ deviceId: device.id, command, packages, devicePin, rememberDevice });
      statusEl.textContent = "✓ Comando enviado";
      setTimeout(() => {
        if (statusEl.textContent === "✓ Comando enviado") statusEl.textContent = "";
      }, 4000);
      if (rememberDevice) updateTrustUI(true);
      break;
    } catch (e) {
      if (e.message === "PIN_REQUIRED" || e.message === "PIN_INCORRECT") {
        const result = await showPinModal(device.model, pinErrorForModal);
        if (!result) {
          statusEl.textContent = "Cancelado — hace falta el PIN del celular.";
          if (onGiveUp) onGiveUp();
          break;
        }
        devicePin = result.pin;
        rememberDevice = result.remember;
        pinErrorForModal = ""; // se vuelve a completar si esta vuelta también falla
        continue;
      }
      statusEl.textContent = "✗ " + (e.message || "Error al enviar el comando");
      if (onGiveUp) onGiveUp();
      break;
    }
  }
  triggerEl.disabled = false;
}

function showPinModal(deviceLabel, prefillError) {
  return new Promise((resolve) => {
    pinModalDeviceName.textContent = deviceLabel;
    pinModalInput.value = "";
    pinModalRemember.checked = false;
    pinModalError.textContent = prefillError || "";
    pinModal.classList.remove("hidden");
    pinModalInput.focus();

    function cleanup() {
      pinModal.classList.add("hidden");
      pinModalConfirm.removeEventListener("click", onConfirm);
      pinModalCancel.removeEventListener("click", onCancel);
      pinModalInput.removeEventListener("keydown", onKeydown);
    }
    function onConfirm() {
      const pin = pinModalInput.value.trim();
      if (!pin) {
        pinModalError.textContent = "Ingresá el PIN.";
        return;
      }
      cleanup();
      resolve({ pin, remember: pinModalRemember.checked });
    }
    function onCancel() {
      cleanup();
      resolve(null);
    }
    function onKeydown(ev) {
      if (ev.key === "Enter") onConfirm();
      if (ev.key === "Escape") onCancel();
    }

    pinModalConfirm.addEventListener("click", onConfirm);
    pinModalCancel.addEventListener("click", onCancel);
    pinModalInput.addEventListener("keydown", onKeydown);
  });
}

function traducirErrorAuth(code) {
  const mensajes = {
    "auth/invalid-email": "Email inválido.",
    "auth/user-not-found": "No existe una cuenta con ese email.",
    "auth/wrong-password": "Contraseña incorrecta.",
    "auth/invalid-credential": "Email o contraseña incorrectos.",
    "auth/too-many-requests": "Demasiados intentos. Probá de nuevo en unos minutos.",
  };
  return mensajes[code] || "No se pudo iniciar sesión.";
}
