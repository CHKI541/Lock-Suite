const auth = firebase.auth(),
    database = firebase.database(),
    functionsRef = firebase.functions(),
    loginScreen = document.getElementById("login-screen"),
    dashboardScreen = document.getElementById("dashboard-screen"),
    emailInput = document.getElementById("email-input"),
    passwordInput = document.getElementById("password-input"),
    loginButton = document.getElementById("login-button"),
    googleLoginButton = document.getElementById("google-login-button"),
    loginError = document.getElementById("login-error"),
    logoutButton = document.getElementById("logout-button"),
    refreshButton = document.getElementById("refresh-button"),
    devicesContainer = document.getElementById("devices-container"),
    globalError = document.getElementById("global-error"),
    cardTemplate = document.getElementById("device-card-template"),
    sidebar = document.getElementById("device-sidebar"),
    sidebarCloseBtn = document.getElementById("sidebar-close-btn"),
    sidebarDeviceName = document.getElementById("sidebar-device-name"),
    sidebarDeviceId = document.getElementById("sidebar-device-id"),
    sidebarDeviceVersion = document.getElementById("sidebar-device-version"),
    sidebarTabButtons = document.querySelectorAll(".sidebar-tabs .tab-btn"),
    sidebarTabPanels = document.querySelectorAll(".tab-panel"),
    deviceNameInput = document.getElementById("device-name-input"),
    saveNameBtn = document.getElementById("save-name-btn"),
    sidebarAppsList = document.getElementById("sidebar-apps-list"),
    appSearchInput = document.getElementById("app-search-input"),
    sidebarAllowlistInput = document.getElementById("sidebar-allowlist-input"),
    sidebarAllowlistBtn = document.getElementById("sidebar-allowlist-btn"),
    sidebarLockBtn = document.getElementById("sidebar-lock-btn"),
    sidebarUnsuspendAllBtn = document.getElementById("sidebar-unsuspend-all-btn"),
    sidebarTrustStatus = document.getElementById("sidebar-trust-status"),
    sidebarForgetPinBtn = document.getElementById("sidebar-forget-pin-btn"),
    sidebarArchiveBtn = document.getElementById("sidebar-archive-btn"),
    sidebarDeleteBtn = document.getElementById("sidebar-delete-btn"),
    sidebarStatusMsg = document.getElementById("sidebar-status-msg"),
    playstoreHidden = document.getElementById("playstore-hidden"),
    playstoreSuspended = document.getElementById("playstore-suspended"),
    pinModal = document.getElementById("pin-modal"),
    pinModalDeviceName = document.querySelector(".modal-device-name"),
    pinModalInput = document.getElementById("pin-modal-input"),
    pinModalRemember = document.getElementById("pin-modal-remember"),
    pinModalError = document.getElementById("pin-modal-error"),
    pinModalCancel = document.getElementById("pin-modal-cancel"),
    pinModalConfirm = document.getElementById("pin-modal-confirm"),
    sidebarNewPinInput = document.getElementById("sidebar-new-pin-input"),
    sidebarChangePinBtn = document.getElementById("sidebar-change-pin-btn"),
    sidebarUpdateLocksuiteBtn = document.getElementById("sidebar-update-locksuite-btn"),
    globalUpdateLocksuiteBtn = document.getElementById("global-update-locksuite-btn");
let selectedDeviceId = null,
    currentDevicesData = {},
    activeTabId = "panel-policies",
    devicesListener = null,
    verifiedDevicePins = {},
    verifiedDevicePinHashes = {};

function field(device, key, fallback = null) {
    if (device && device[key] !== undefined && device[key] !== null) return device[key];
    if (device && device.info && device.info[key] !== undefined && device.info[key] !== null) {
        return device.info[key];
    }
    return fallback;
}

function appsOf(device) {
    return field(device, "apps", {});
}

function arrayOrCsv(value) {
    if (Array.isArray(value)) return value.join(", ");
    return value || "";
}

let groupsListener = null;

function startRealtimeSync() {
    devicesListener && database.ref("devices").off("value", devicesListener), devicesListener = database.ref("devices").on("value", e => {
        const t = e.val() || {};
        currentDevicesData = t, renderDevicesList(t), selectedDeviceId && t[selectedDeviceId] && updateSidebarUI(selectedDeviceId, t[selectedDeviceId])
        // Si hay un grupo abierto, refrescar la lista de dispositivos seleccionables por si cambiaron de nombre o estado
        if (selectedGroupId && currentGroupsData[selectedGroupId]) {
            renderGroupDevicesSelector(currentGroupsData[selectedGroupId]);
        }
    }, e => {
        globalError.textContent = "Error de sincronización en tiempo real: " + e.message
    });

    groupsListener && database.ref("groups").off("value", groupsListener), groupsListener = database.ref("groups").on("value", e => {
        currentGroupsData = e.val() || {};
        renderGroupsList(currentGroupsData);
        if (selectedGroupId && currentGroupsData[selectedGroupId]) {
            updateGroupSidebarUI(selectedGroupId, currentGroupsData[selectedGroupId]);
        }
    }, e => {
        console.error("Error de sync de grupos:", e);
    });

    archivedListener && database.ref("archivedDevices").off("value", archivedListener), archivedListener = database.ref("archivedDevices").on("value", e => {
        currentArchivedDevicesData = e.val() || {};
        renderArchivedDevicesList(currentArchivedDevicesData);
    }, e => {
        console.error("Error de sync de archivados:", e);
    });
}

function stopRealtimeSync() {
    devicesListener && (database.ref("devices").off("value", devicesListener), devicesListener = null)
    groupsListener && (database.ref("groups").off("value", groupsListener), groupsListener = null)
    archivedListener && (database.ref("archivedDevices").off("value", archivedListener), archivedListener = null)
}

function renderDevicesList(e) {
    devicesContainer.innerHTML = "";
    const t = Object.entries(e);
    0 !== t.length ? (t.sort((e, t) => {
        const n = field(e[1], "lastSeen", 0);
        return field(t[1], "lastSeen", 0) - n
    }), t.forEach(([e, t]) => {
        const n = cardTemplate.content.cloneNode(!0),
            a = n.querySelector(".device-card"),
            i = field(t, "deviceName", "Dispositivo sin nombre"),
            d = field(t, "model", "Modelo desconocido"),
            s = field(t, "lastSeen", null),
            fcm = field(t, "fcmToken", ""),
            o = s && Date.now() - s < 3e5;
        n.querySelector(".device-name").textContent = i, n.querySelector(".device-model-label").textContent = d, n.querySelector(".device-id").textContent = e;
        const c = n.querySelector(".status-badge");
        if (o && fcm) {
            c.textContent = "En línea";
            c.className = "badge active";
        } else if (o && !fcm) {
            c.textContent = "Sin FCM";
            c.className = "badge warning";
        } else {
            c.textContent = "Desconectado";
            c.className = "badge inactive";
        }
        n.querySelector(".last-seen").textContent = s ? "Última conexión: " + new Date(s).toLocaleString("es-AR") : "Sin conexión registrada";
        const r = () => openDeviceSidebar(e, t);
        a.addEventListener("click", r), n.querySelector(".view-details-btn").addEventListener("click", e => {
            e.stopPropagation(), r()
        }), devicesContainer.appendChild(n)
    })) : devicesContainer.innerHTML = '<p class="loading-text">Todavía no hay dispositivos registrados.</p>'
}

async function hashPinLocal(pin, saltBase64) {
    const binaryString = window.atob(saltBase64);
    const saltBytes = new Uint8Array(binaryString.length);
    for (let i = 0; i < binaryString.length; i++) {
        saltBytes[i] = binaryString.charCodeAt(i);
    }
    const encoder = new TextEncoder();
    const pinBytes = encoder.encode(pin);
    const combinedBytes = new Uint8Array(saltBytes.length + pinBytes.length);
    combinedBytes.set(saltBytes, 0);
    combinedBytes.set(pinBytes, saltBytes.length);
    
    const hashBuffer = await window.crypto.subtle.digest("SHA-256", combinedBytes);
    const hashBytes = new Uint8Array(hashBuffer);
    let binaryHash = "";
    for (let i = 0; i < hashBytes.byteLength; i++) {
        binaryHash += String.fromCharCode(hashBytes[i]);
    }
    return window.btoa(binaryHash);
}

async function verifyPinBackend(deviceId, pin, remember) {
    const secretsSnap = await database.ref(`deviceSecrets/${deviceId}`).once("value");
    const secrets = secretsSnap.val();
    if (!secrets || !secrets.pinHash || !secrets.pinSalt) {
        throw new Error("DEVICE_PIN_NOT_ENROLLED");
    }
    
    const localHash = await hashPinLocal(pin, secrets.pinSalt);
    if (localHash !== secrets.pinHash) {
        throw new Error("PIN_INCORRECT");
    }
    
    const adminUid = auth.currentUser ? auth.currentUser.uid : null;
    if (adminUid) {
        if (remember) {
            await database.ref(`devices/${deviceId}/trustedAdmins/${adminUid}`).set(true);
            await database.ref(`devices/${deviceId}/info/trustedAdmins/${adminUid}`).set(true);
        }
    }
    return { success: true };
}

async function openDeviceSidebar(e, t) {
    selectedDeviceId = e;
    
    const trustedAdmins = t.trustedAdmins || (t.info && t.info.trustedAdmins) || {};
    const adminUid = auth.currentUser ? auth.currentUser.uid : null;
    const isTrusted = adminUid && trustedAdmins[adminUid] === true;
    
    // Verificamos si tiene PIN configurado según la bandera pública hasPinConfigured
    const hasPinConfigured = t.hasPinConfigured || (t.info && t.info.hasPinConfigured) === true;
    
    if (hasPinConfigured && !isTrusted && !verifiedDevicePins[e]) {
        const r = t.deviceName || (t.info && t.info.deviceName) || t.model || (t.info && t.info.model) || "Celular";
        let isPinCorrect = false;
        let errorMessage = "";
        
        while (!isPinCorrect) {
            const pinData = await showPinModal(r, errorMessage);
            if (!pinData) {
                closeDeviceSidebar();
                return;
            }
            
            try {
                pinModalError.textContent = "Verificando PIN...";
                pinModalError.style.color = "var(--accent-orange)";
                
                await verifyPinBackend(e, pinData.pin, pinData.remember);
                
                isPinCorrect = true;
                verifiedDevicePins[e] = pinData.pin;
            } catch (err) {
                console.error("Error al verificar PIN:", err);
                if (err.message === "PIN_INCORRECT") {
                    errorMessage = "PIN incorrecto. Intentá de nuevo.";
                } else if (err.message === "DEVICE_PIN_NOT_ENROLLED") {
                    alert("Este dispositivo no tiene un PIN de administrador configurado.");
                    closeDeviceSidebar();
                    return;
                } else {
                    errorMessage = "Error al verificar PIN: " + err.message;
                }
            }
        }
    } else if (!hasPinConfigured && !isTrusted) {
        alert("Este dispositivo no tiene un PIN de administrador configurado.");
        closeDeviceSidebar();
        return;
    }
    
    sidebar.classList.remove("hidden");
    updateSidebarUI(e, t);
}

function closeDeviceSidebar() {
    selectedDeviceId = null, sidebar.classList.add("hidden")
}

function updateSidebarUI(e, t) {
    const n = field(t, "deviceName", ""),
        a = field(t, "model", "Celular");
    const vName = field(t, "versionName", "");
    const vCode = field(t, "versionCode", "");
    let vText = "";
    if (vName) {
        vText = `Versión: ${vName} (${vCode})`;
    } else {
        vText = "Versión: Desconocida (actualizá la app)";
    }
    const progress = field(t, "updateProgress", null);
    if (progress !== null && progress !== undefined) {
        vText += ` [Descargando: ${progress}%]`;
        sidebarUpdateLocksuiteBtn.textContent = `🔄 Descargando: ${progress}%`;
        sidebarUpdateLocksuiteBtn.disabled = true;
    } else {
        sidebarUpdateLocksuiteBtn.textContent = `🔄 Actualizar LockSuite (Sistema)`;
        sidebarUpdateLocksuiteBtn.disabled = false;
    }
    sidebarDeviceName.textContent = n || a || "Celular sin nombre", sidebarDeviceId.textContent = e, sidebarDeviceVersion.textContent = vText, document.activeElement !== deviceNameInput && (deviceNameInput.value = n);
    sidebar.querySelectorAll(".policy-switch").forEach(e => {
        const n = e.getAttribute("data-policy");
        e.checked = field(t, n, false) === true;
    });
    const apps = appsOf(t);
    const i = apps.com_android_vending || null,
        d = field(t, "installAppsBlocked", false) === true;
    playstoreHidden.checked = i ? i.isHidden === true : d, playstoreSuspended.checked = i ? i.isSuspended === true : d;
    renderAppsList(apps), document.activeElement !== sidebarAllowlistInput && (sidebarAllowlistInput.value = arrayOrCsv(field(t, "allowedPackages", "")));
    const s = !!(field(t, "hasPinConfigured") || field(t, "pinHash") && field(t, "pinSalt")),
        o = auth.currentUser ? auth.currentUser.uid : "",
        c = !!field(t, "trustedAdmins", {})[o];
    s ? (sidebarForgetPinBtn.classList.remove("hidden"), sidebarTrustStatus.textContent = c ? "🔓 PIN de administrador recordado en esta sesión." : "🔒 Se requerirá el PIN para realizar acciones críticas.") : (sidebarForgetPinBtn.classList.add("hidden"), sidebarTrustStatus.textContent = "Ese celular todavía no configuró un PIN de administrador.")
}
sidebarCloseBtn.addEventListener("click", closeDeviceSidebar), sidebarTabButtons.forEach(e => {
    e.addEventListener("click", () => {
        sidebarTabButtons.forEach(e => e.classList.remove("active")), sidebarTabPanels.forEach(e => e.classList.remove("active")), e.classList.add("active"), activeTabId = e.getAttribute("data-tab"), document.getElementById(activeTabId).classList.add("active")
    })
});
let currentSearchQuery = "", currentAppFilter = "all";

function renderAppsList(e) {
    sidebarAppsList.innerHTML = "";
    const t = Object.values(e).filter(e => "com.android.vending" !== e.packageName);
    if (0 === t.length) return void(sidebarAppsList.innerHTML = '<p class="loading-text">No hay aplicaciones reportadas por la app.</p>');
    
    // Update blocked count display
    const blockedCountEl = document.getElementById("blocked-apps-count");
    if (blockedCountEl) {
        const totalBlocked = t.filter(app => app.isHidden || app.isSuspended || app.isWebViewBlocked || (app.imageBlockingMode && app.imageBlockingMode !== "none")).length;
        blockedCountEl.textContent = totalBlocked;
    }

    // Filter by type
    let filtered = t;
    if (currentAppFilter === "blocked") {
        filtered = t.filter(app => app.isHidden || app.isSuspended || app.isWebViewBlocked || (app.imageBlockingMode && app.imageBlockingMode !== "none"));
    } else if (currentAppFilter === "user") {
        filtered = t.filter(app => app.appType === "Usuario");
    } else if (currentAppFilter === "system") {
        filtered = t.filter(app => app.appType === "Sistema" || app.appType === "Preinstalada");
    }

    const n = filtered.filter(e => e.label.toLowerCase().includes(currentSearchQuery) || e.packageName.toLowerCase().includes(currentSearchQuery));
    0 !== n.length ? n.forEach(e => {
        const t = document.createElement("div");
        t.className = "app-item", e.isCritical && (t.style.borderLeft = "3px solid #7F8C8D");
        
        t.style.flexDirection = "column";
        t.style.alignItems = "stretch";
        t.style.gap = "8px";

        const mainRow = document.createElement("div");
        mainRow.className = "app-main-row";
        mainRow.style.display = "flex";
        mainRow.style.alignItems = "center";
        mainRow.style.justifyContent = "space-between";
        mainRow.style.width = "100%";
        mainRow.style.gap = "10px";
        t.appendChild(mainRow);

        const n = document.createElement("div");
        n.className = "app-meta";
        const a = document.createElement("p");
        a.className = "app-title", a.textContent = e.label;
        const i = document.createElement("p");
        i.className = "app-pkg", i.textContent = e.packageName, n.appendChild(a), n.appendChild(i), mainRow.appendChild(n);
        
        const d = document.createElement("div");
        d.className = "app-toggles";
        if (e.isCritical) {
            const e = document.createElement("span");
            e.className = "badge inactive", e.textContent = "Crítica", d.appendChild(e);
            mainRow.appendChild(d);
        } else {
            const tBtn = document.createElement("button");
            tBtn.className = "app-toggle-btn" + (e.isHidden ? " active-block" : ""), tBtn.textContent = e.isHidden ? "Bloqueada" : "Bloquear", tBtn.addEventListener("click", () => {
                const n = e.isHidden ? "UNHIDE_APP" : "HIDE_APP";
                runCommandOnDevice(selectedDeviceId, n, [e.packageName], tBtn)
            });
            const nBtn = document.createElement("button");
            nBtn.className = "app-toggle-btn" + (e.isSuspended ? " active-suspend" : ""), nBtn.textContent = e.isSuspended ? "Suspendida" : "Suspender", nBtn.addEventListener("click", () => {
                const t = e.isSuspended ? "UNSUSPEND_APP" : "SUSPEND_APP";
                runCommandOnDevice(selectedDeviceId, t, [e.packageName], nBtn)
            });
            const aBtn = document.createElement("button");
            aBtn.className = "app-toggle-btn" + (e.isWebViewBlocked ? " active-webview" : ""), aBtn.textContent = e.isWebViewBlocked ? "Sin Web" : "Bloquear Web", aBtn.addEventListener("click", () => {
                const t = e.isWebViewBlocked ? "UNBLOCK_WEBVIEW" : "BLOCK_WEBVIEW";
                runCommandOnDevice(selectedDeviceId, t, [e.packageName], aBtn)
            });
            
            const gearBtn = document.createElement("button");
            gearBtn.className = "app-toggle-btn gear-btn";
            gearBtn.textContent = "⚙️";
            gearBtn.style.padding = "4px 6px";
            gearBtn.style.fontSize = "12px";
            if (e.imageBlockingMode && e.imageBlockingMode !== "none") {
                gearBtn.style.borderColor = "var(--accent-orange)";
                gearBtn.style.color = "var(--accent-orange)";
                gearBtn.style.boxShadow = "0 0 5px rgba(241, 196, 15, 0.4)";
            }

            d.appendChild(tBtn), d.appendChild(nBtn), d.appendChild(aBtn), d.appendChild(gearBtn);
            mainRow.appendChild(d);

            const advancedPanel = document.createElement("div");
            advancedPanel.className = "advanced-settings hidden";
            advancedPanel.style.marginTop = "6px";
            advancedPanel.style.padding = "8px 12px";
            advancedPanel.style.backgroundColor = "rgba(0, 0, 0, 0.25)";
            advancedPanel.style.borderRadius = "8px";
            advancedPanel.style.border = "1px solid rgba(255, 255, 255, 0.05)";
            advancedPanel.style.width = "100%";
            advancedPanel.style.display = "flex";
            advancedPanel.style.flexDirection = "column";
            advancedPanel.style.gap = "8px";

            // Fila 1: Bloqueo de Imagen
            const imgRow = document.createElement("div");
            imgRow.style.display = "flex";
            imgRow.style.alignItems = "center";
            imgRow.style.justifyContent = "space-between";
            imgRow.style.width = "100%";

            const label = document.createElement("span");
            label.textContent = "Bloqueo de imágenes:";
            label.style.fontSize = "11px";
            label.style.color = "var(--text-gray)";

            const select = document.createElement("select");
            select.style.padding = "3px 6px";
            select.style.borderRadius = "6px";
            select.style.backgroundColor = "var(--navy-dark)";
            select.style.color = "var(--text-light)";
            select.style.border = "1px solid var(--navy-light)";
            select.style.fontSize = "11px";
            select.style.cursor = "pointer";

            const optionsList = [
                { value: "none", text: "Desactivado" },
                { value: "layer1", text: "Capa 1: Nodos" },
                { value: "layer2", text: "Capa 2: IA (Siluetas)" },
                { value: "both", text: "Ambas Capas (Nodos + IA)" }
            ];

            optionsList.forEach(opt => {
                const o = document.createElement("option");
                o.value = opt.value;
                o.textContent = opt.text;
                if ((e.imageBlockingMode || "none") === opt.value) {
                    o.selected = true;
                }
                select.appendChild(o);
            });

            select.addEventListener("change", () => {
                let cmd = "SET_IMAGE_BLOCK_NONE";
                if (select.value === "layer1") cmd = "SET_IMAGE_BLOCK_LAYER_1";
                else if (select.value === "layer2") cmd = "SET_IMAGE_BLOCK_LAYER_2";
                else if (select.value === "both") cmd = "SET_IMAGE_BLOCK_BOTH";
                
                select.disabled = true;
                runCommandOnDevice(selectedDeviceId, cmd, [e.packageName], select);
            });

            imgRow.appendChild(label);
            imgRow.appendChild(select);
            advancedPanel.appendChild(imgRow);

            // Fila 2: Bloqueo Total de Internet por App
            const netRow = document.createElement("div");
            netRow.style.display = "flex";
            netRow.style.alignItems = "center";
            netRow.style.justifyContent = "space-between";
            netRow.style.width = "100%";
            netRow.style.marginTop = "4px";

            const netLabel = document.createElement("span");
            netLabel.textContent = "Bloqueo Total de Internet:";
            netLabel.style.fontSize = "11px";
            netLabel.style.color = "var(--text-gray)";

            const netBtn = document.createElement("button");
            netBtn.className = "app-toggle-btn" + (e.isInternetBlocked ? " active-block" : "");
            netBtn.style.fontSize = "11px";
            netBtn.style.padding = "4px 8px";
            netBtn.textContent = e.isInternetBlocked ? "🚫 Sin Internet (Bloqueado)" : "🌐 Internet Permitido";
            netBtn.addEventListener("click", () => {
                const cmd = e.isInternetBlocked ? "UNBLOCK_APP_INTERNET" : "BLOCK_APP_INTERNET";
                runCommandOnDevice(selectedDeviceId, cmd, [e.packageName], netBtn);
            });

            netRow.appendChild(netLabel);
            netRow.appendChild(netBtn);
            advancedPanel.appendChild(netRow);

            imgRow.appendChild(label);
            imgRow.appendChild(select);
            advancedPanel.appendChild(imgRow);

            // Fila 2: Actualizar App
            const updateRow = document.createElement("div");
            updateRow.style.display = "flex";
            updateRow.style.alignItems = "center";
            updateRow.style.justifyContent = "space-between";
            updateRow.style.width = "100%";
            updateRow.style.borderTop = "1px solid rgba(255, 255, 255, 0.05)";
            updateRow.style.paddingTop = "6px";

            const updateLabel = document.createElement("span");
            updateLabel.textContent = "Actualizar Aplicación:";
            updateLabel.style.fontSize = "11px";
            updateLabel.style.color = "var(--text-gray)";

            const updateBtn = document.createElement("button");
            updateBtn.className = "app-toggle-btn";
            updateBtn.textContent = "Actualizar desde Play Store";
            updateBtn.style.padding = "4px 8px";
            updateBtn.style.fontSize = "10px";
            updateBtn.style.backgroundColor = "#16a085";
            updateBtn.style.color = "white";
            updateBtn.style.border = "none";
            updateBtn.style.borderRadius = "4px";
            updateBtn.style.cursor = "pointer";

            updateBtn.addEventListener("click", (evt) => {
                evt.stopPropagation();
                if (confirm(`¿Enviar comando para actualizar "${e.label}"? El celular abrirá temporalmente la Play Store y se cerrará automáticamente al finalizar la instalación.`)) {
                    runCommandOnDevice(selectedDeviceId, "UPDATE_APP", [e.packageName], updateBtn);
                }
            });

            updateRow.appendChild(updateLabel);
            updateRow.appendChild(updateBtn);
            advancedPanel.appendChild(updateRow);

            gearBtn.addEventListener("click", (evt) => {
                evt.stopPropagation();
                advancedPanel.classList.toggle("hidden");
            });

            t.appendChild(advancedPanel);
        }
        sidebarAppsList.appendChild(t);
    }) : sidebarAppsList.innerHTML = '<p class="loading-text">Ninguna aplicación coincide con la búsqueda.</p>';
}
async function runCommandOnDevice(e, t, n = null, a = null, i = null, extraParams = null) {
    a && (a.disabled = !0), sidebarStatusMsg.textContent = "Enviando comando al celular...";
    let d = verifiedDevicePins[e] || null,
        s = !1,
        o = "";
    const c = currentDevicesData[e],
        r = c && (c.model || c.info && c.info.model) || "Celular";
    for (;;) try {
        const idToken = await auth.currentUser.getIdToken();
        const payload = {
            deviceId: e,
            command: t,
            packages: n ? (Array.isArray(n) ? n.join(",") : String(n)) : null,
            devicePin: d,
            rememberDevice: s
        };
        if (extraParams) {
            Object.assign(payload, extraParams);
        }
        
        const response = await fetch("https://sendcommandv8-687828714595.us-central1.run.app", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Authorization": `Bearer ${idToken}`
            },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            const errData = await response.json().catch(() => ({}));
            const errMsg = errData.error || `HTTP_${response.status}`;
            throw new Error(errMsg);
        }

        const resData = await response.json();
        const commandId = resData.commandId;
        if (commandId) {
            const isUpdateCmd = (t === "UPDATE_LOCKSUITE");
            const timeoutMs = isUpdateCmd ? 120000 : 10000;
            sidebarStatusMsg.textContent = isUpdateCmd ? "Esperando descarga y actualización del celular..." : "Comando enviado. Esperando respuesta del celular...";
            const ackRef = database.ref(`devices/${e}/commandAcks/${commandId}`);
            const timeoutId = setTimeout(() => {
                ackRef.off();
                sidebarStatusMsg.textContent = isUpdateCmd ? "✓ Comando enviado. El celular se está actualizando en segundo plano." : "✓ Comando enviado (sin confirmación del celular)";
                if (a) a.disabled = false;
                if (i) i();
            }, timeoutMs);
            
            ackRef.on("value", (snap) => {
                if (snap.exists()) {
                    const status = snap.val().status;
                    if (status === "applied") {
                        clearTimeout(timeoutId);
                        ackRef.off();
                        sidebarStatusMsg.textContent = "✓ Comando aplicado con éxito en el celular";
                        if (a) a.disabled = false;
                    } else if (status === "failed") {
                        clearTimeout(timeoutId);
                        ackRef.off();
                        const reason = snap.val().reason ? ` (${snap.val().reason})` : "";
                        sidebarStatusMsg.textContent = "✗ El comando falló en el celular" + reason;
                        if (a) a.disabled = false;
                        if (i) i();
                    }
                }
            });
        } else {
            sidebarStatusMsg.textContent = "✓ Comando enviado";
            setTimeout(() => {
                "✓ Comando enviado" === sidebarStatusMsg.textContent && (sidebarStatusMsg.textContent = "");
                if (a) a.disabled = false;
                if (i) i();
            }, 4000);
        }
        break;
    } catch (e) {
        if ("PIN_REQUIRED" === e.message || "PIN_INCORRECT" === e.message) {
            if ("PIN_INCORRECT" === e.message) {
                delete verifiedDevicePins[selectedDeviceId];
            }
            o = "PIN_INCORRECT" === e.message ? "PIN incorrecto. Intentá de nuevo." : "";
            const t = await showPinModal(r, o);
            if (!t) {
                sidebarStatusMsg.textContent = "Cancelado — PIN requerido.", i && i();
                break;
            }
            d = t.pin, s = t.remember;
            verifiedDevicePins[selectedDeviceId] = t.pin;
            const dHash = c.pinHash || (c.info && c.info.pinHash);
            if (dHash) {
                verifiedDevicePinHashes[selectedDeviceId] = dHash;
            }
            continue;
        }
        sidebarStatusMsg.textContent = "✗ Error: " + (e.message || "desconocido"), i && i();
        break;
    }
    a && (a.disabled = !1);
}

function showPinModal(e, t) {
    return new Promise(n => {
        function a() {
            pinModalConfirm.removeEventListener("click", i), pinModalCancel.removeEventListener("click", d), pinModalInput.removeEventListener("keydown", s), pinModal.classList.add("hidden")
        }

        function i() {
            const e = pinModalInput.value.trim();
            e ? (a(), n({
                pin: e,
                remember: pinModalRemember.checked
            })) : pinModalError.textContent = "Ingresá el PIN."
        }

        function d() {
            a(), n(null)
        }

        function s(e) {
            "Enter" === e.key && i(), "Escape" === e.key && d()
        }
        pinModalDeviceName.textContent = e, pinModalInput.value = "", pinModalRemember.checked = !1, pinModalError.textContent = t || "", pinModal.classList.remove("hidden"), pinModalInput.focus(), pinModalConfirm.addEventListener("click", i), pinModalCancel.addEventListener("click", d), pinModalInput.addEventListener("keydown", s)
    })
}

function traducirErrorAuth(e) {
    return {
        "auth/invalid-email": "Email inválido.",
        "auth/user-not-found": "No existe una cuenta con ese email.",
        "auth/wrong-password": "Contraseña incorrecta.",
        "auth/invalid-credential": "Email o contraseña incorrectos.",
        "auth/too-many-requests": "Demasiados intentos. Probá de nuevo en unos minutos."
    } [e] || "No se pudo iniciar sesión."
}
appSearchInput.addEventListener("input", e => {
    currentSearchQuery = e.target.value.toLowerCase(), selectedDeviceId && currentDevicesData[selectedDeviceId] && renderAppsList(currentDevicesData[selectedDeviceId].apps || {})
});
document.querySelectorAll(".apps-filters .filter-chip").forEach(btn => {
    btn.addEventListener("click", () => {
        document.querySelectorAll(".apps-filters .filter-chip").forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        currentAppFilter = btn.getAttribute("data-filter");
        if (selectedDeviceId && currentDevicesData[selectedDeviceId]) {
            renderAppsList(currentDevicesData[selectedDeviceId].apps || {});
        }
    });
});
saveNameBtn.addEventListener("click", async () => {
    if (!selectedDeviceId) return;
    const e = deviceNameInput.value.trim();
    saveNameBtn.disabled = !0, sidebarStatusMsg.textContent = "Guardando nombre...";
    try {
        await database.ref(`devices/${selectedDeviceId}`).update({
            deviceName: e
        }), await database.ref(`devices/${selectedDeviceId}/info`).update({
            deviceName: e
        }).catch(() => {}), sidebarStatusMsg.textContent = "✓ Nombre guardado con éxito.", setTimeout(() => {
            sidebarStatusMsg.textContent.includes("✓") && (sidebarStatusMsg.textContent = "")
        }, 4e3)
    } catch (e) {
        sidebarStatusMsg.textContent = "✗ Error al guardar: " + e.message
    } finally {
        saveNameBtn.disabled = !1
    }
}), sidebar.addEventListener("change", e => {
    if (!e.target.classList.contains("policy-switch")) return;
    const t = e.target,
        n = t.getAttribute("data-policy"),
        a = t.checked,
        i = {
            factoryResetBlocked: ["BLOCK_FACTORY_RESET", "UNBLOCK_FACTORY_RESET"],
            installAppsBlocked: ["BLOCK_INSTALL_APPS", "UNBLOCK_INSTALL_APPS"],
            uninstallAppsBlocked: ["BLOCK_UNINSTALL_APPS", "UNBLOCK_UNINSTALL_APPS"],
            adbBlocked: ["BLOCK_ADB", "UNBLOCK_ADB"],
            userSwitchBlocked: ["BLOCK_USER_SWITCH", "UNBLOCK_USER_SWITCH"],
            modifyAccountsBlocked: ["BLOCK_MODIFY_ACCOUNTS", "UNBLOCK_MODIFY_ACCOUNTS"],
            safeBootBlocked: ["BLOCK_SAFE_BOOT", "UNBLOCK_SAFE_BOOT"],
            unknownSourcesBlocked: ["BLOCK_UNKNOWN_SOURCES", "UNBLOCK_UNKNOWN_SOURCES"],
            wifiBlocked: ["BLOCK_WIFI", "UNBLOCK_WIFI"],
            vpnBlocked: ["BLOCK_VPN", "UNBLOCK_VPN"],
            appsControlBlocked: ["BLOCK_APPS_CONTROL", "UNBLOCK_APPS_CONTROL"],
            adjustVolumeBlocked: ["BLOCK_VOLUME", "UNBLOCK_VOLUME"],
            cameraDisabled: ["DISABLE_CAMERA", "ENABLE_CAMERA"],
            screenCaptureBlocked: ["BLOCK_SCREEN_CAPTURE", "UNBLOCK_SCREEN_CAPTURE"],
            statusBarDisabled: ["DISABLE_STATUSBAR", "ENABLE_STATUSBAR"],
            keyguardDisabled: ["DISABLE_KEYGUARD", "ENABLE_KEYGUARD"],
            internetBlocked: ["BLOCK_INTERNET", "UNBLOCK_INTERNET"],
            bluetoothBlocked: ["BLOCK_BLUETOOTH", "UNBLOCK_BLUETOOTH"],
            bluetoothSharingBlocked: ["BLOCK_BLUETOOTH_SHARING", "UNBLOCK_BLUETOOTH_SHARING"],
            externalMediaBlocked: ["BLOCK_EXTERNAL_MEDIA", "UNBLOCK_EXTERNAL_MEDIA"],
            tetheringBlocked: ["BLOCK_TETHERING", "UNBLOCK_TETHERING"],
            adBlockingEnabled: ["ENABLE_ADBLOCK", "DISABLE_ADBLOCK"],
            gifsBlocked: ["BLOCK_GIFS", "UNBLOCK_GIFS"],
            aiModeEnabled: ["ENABLE_AI_MODE", "DISABLE_AI_MODE"],
            mapsImageBlockingEnabled: ["ENABLE_MAPS_IMAGE_BLOCKING", "DISABLE_MAPS_IMAGE_BLOCKING"],
            whatsappBlockStatus: ["BLOCK_WHATSAPP_STATUS", "UNBLOCK_WHATSAPP_STATUS"],
            whatsappBlockChannels: ["BLOCK_WHATSAPP_CHANNELS", "UNBLOCK_WHATSAPP_CHANNELS"],
            mercadoPagoBlockOffers: ["BLOCK_MERCADOPAGO_OFFERS", "UNBLOCK_MERCADOPAGO_OFFERS"],
            mercadoPagoBlockOffersAccessibility: ["BLOCK_MP_OFFERS_ACCESSIBILITY", "UNBLOCK_MP_OFFERS_ACCESSIBILITY"],
            mercadoPagoBlockOffersVpn: ["BLOCK_MP_OFFERS_VPN", "UNBLOCK_MP_OFFERS_VPN"],
            stealthModeEnabled: ["ENABLE_STEALTH", "DISABLE_STEALTH"]
        } [n];
    if (!i) return;
    const d = a ? i[0] : i[1];
    t.disabled = !0, runCommandOnDevice(selectedDeviceId, d, null, t, () => {
        t.checked = !a
    })
}), playstoreHidden.addEventListener("change", () => {
    const e = playstoreHidden.checked,
        t = e ? "HIDE_APP" : "UNHIDE_APP";
    playstoreHidden.disabled = !0, runCommandOnDevice(selectedDeviceId, t, ["com.android.vending"], playstoreHidden, () => {
        playstoreHidden.checked = !e
    })
}), playstoreSuspended.addEventListener("change", () => {
    const e = playstoreSuspended.checked,
        t = e ? "SUSPEND_APP" : "UNSUSPEND_APP";
    playstoreSuspended.disabled = !0, runCommandOnDevice(selectedDeviceId, t, ["com.android.vending"], playstoreSuspended, () => {
        playstoreSuspended.checked = !e
    })
});
const hideSuspendedAppsToggle = document.getElementById("hide-suspended-apps-toggle");
if (hideSuspendedAppsToggle) {
    hideSuspendedAppsToggle.addEventListener("change", () => {
        const e = hideSuspendedAppsToggle.checked;
        hideSuspendedAppsToggle.disabled = !0;
        runCommandOnDevice(selectedDeviceId, "SET_HIDE_SUSPENDED_APPS", null, hideSuspendedAppsToggle, () => {
            hideSuspendedAppsToggle.checked = !e;
        }, { enabled: e });
    });
}
sidebarAllowlistBtn.addEventListener("click", () => {
    const e = sidebarAllowlistInput.value.split(",").map(e => e.trim()).filter(Boolean);
    if (e.length === 0) {
        if (!confirm("¿Seguro que querés vaciar la lista de aplicaciones permitidas? Esto bloqueará el acceso a todas las aplicaciones no esenciales.")) {
            return;
        }
    }
    sidebarAllowlistBtn.disabled = !0, runCommandOnDevice(selectedDeviceId, "UPDATE_ALLOWLIST", e, sidebarAllowlistBtn);
}), sidebarLockBtn.addEventListener("click", () => {
    sidebarLockBtn.disabled = !0, runCommandOnDevice(selectedDeviceId, "LOCK_DEVICE", null, sidebarLockBtn)
}), sidebarUnsuspendAllBtn.addEventListener("click", () => {
    if (confirm("¿Seguro que querés desbloquear todas las aplicaciones de este celular?")) {
        sidebarUnsuspendAllBtn.disabled = !0, runCommandOnDevice(selectedDeviceId, "UNSUSPEND_ALL_APPS", null, sidebarUnsuspendAllBtn)
    }
}), sidebarForgetPinBtn.addEventListener("click", async () => {
    if (selectedDeviceId) {
        sidebarForgetPinBtn.disabled = !0, sidebarStatusMsg.textContent = "Olvidando confianza del PIN...";
        try {
            // Borrado directo en la base de datos (no requiere Cloud Function)
            const adminUid = auth.currentUser && auth.currentUser.uid;
            if (adminUid) {
                await database.ref(`devices/${selectedDeviceId}/trustedAdmins/${adminUid}`).remove();
                await database.ref(`devices/${selectedDeviceId}/info/trustedAdmins/${adminUid}`).remove();
            }
            sidebarStatusMsg.textContent = "Confianza del PIN olvidada.";
        } catch (e) {
            sidebarStatusMsg.textContent = "Error al olvidar confianza: " + e.message
        } finally {
            sidebarForgetPinBtn.disabled = !1
        }
    }
}), sidebarUpdateLocksuiteBtn.addEventListener("click", () => {
    if (selectedDeviceId) {
        if (confirm("¿Actualizar la aplicación LockSuite (sistema) en este celular?")) {
            sidebarUpdateLocksuiteBtn.disabled = !0;
            runCommandOnDevice(selectedDeviceId, "UPDATE_LOCKSUITE", null, sidebarUpdateLocksuiteBtn);
        }
    }
}), sidebarChangePinBtn.addEventListener("click", () => {
    const pin = sidebarNewPinInput.value.trim();
    if (!pin || pin.length < 4 || pin.length > 16 || !/^\d+$/.test(pin)) {
        alert("El PIN debe ser puramente numérico y tener entre 4 y 16 dígitos.");
        return;
    }
    if (!confirm("¿Seguro que querés cambiar el PIN de administrador de este celular?")) {
        return;
    }
    runCommandOnDevice(selectedDeviceId, "CHANGE_PIN", null, sidebarChangePinBtn, null, { newPin: pin });
    sidebarNewPinInput.value = "";
}), auth.onAuthStateChanged(async e => {
    if (e) {
        loginError.textContent = "Verificando permisos...";
        try {
            const t = await database.ref(`authorizedAdminsUids/${e.uid}`).once("value");
            if (!t.exists() || !0 !== t.val()) {
                const t = e.email.toLowerCase().replace(/[.@]/g, "_"),
                    n = await database.ref(`authorizedAdmins/${t}`).once("value");
                if (!n.exists() || !0 !== n.val()) return loginError.textContent = `Acceso denegado: El correo ${e.email} no está autorizado.`, void auth.signOut()
            }
            loginError.textContent = "", loginScreen.classList.add("hidden"), dashboardScreen.classList.remove("hidden"), startRealtimeSync()
        } catch (e) {
            loginError.textContent = "Error al verificar permisos: " + e.message, auth.signOut()
        }
    } else stopRealtimeSync(), closeDeviceSidebar(), dashboardScreen.classList.add("hidden"), loginScreen.classList.remove("hidden")
}), loginButton.addEventListener("click", async () => {
    loginError.textContent = "";
    try {
        await auth.signInWithEmailAndPassword(emailInput.value.trim(), passwordInput.value)
    } catch (e) {
        loginError.textContent = traducirErrorAuth(e.code)
    }
}), googleLoginButton.addEventListener("click", async () => {
    loginError.textContent = "";
    try {
        const e = new firebase.auth.GoogleAuthProvider;
        await auth.signInWithPopup(e)
    } catch (e) {
        loginError.textContent = "Error al iniciar sesión con Google: " + e.message
    }
}), logoutButton.addEventListener("click", () => auth.signOut()), refreshButton.addEventListener("click", () => {
    devicesListener && database.ref("devices").once("value").then(e => {
        currentDevicesData = e.val() || {}, renderDevicesList(currentDevicesData)
    })
}), globalUpdateLocksuiteBtn.addEventListener("click", () => {
    const deviceIds = Object.keys(currentDevicesData);
    if (deviceIds.length === 0) {
        alert("No hay ningún dispositivo registrado.");
        return;
    }
    if (confirm(`¿Enviar orden de actualización de LockSuite (sistema) a los ${deviceIds.length} celulares registrados?`)) {
        globalUpdateLocksuiteBtn.disabled = true;
        let successCount = 0;
        let failCount = 0;
        const promises = deviceIds.map(deviceId => {
            return runCommandOnDevice(deviceId, "UPDATE_LOCKSUITE")
                .then(() => { successCount++; })
                .catch(() => { failCount++; });
        });
        Promise.all(promises).finally(() => {
            globalUpdateLocksuiteBtn.disabled = false;
            alert(`Orden de actualización enviada. Éxito: ${successCount}, Fallidos: ${failCount}`);
        });
    }
});

// --- Archivar dispositivo ---
if (sidebarArchiveBtn) {
    sidebarArchiveBtn.addEventListener("click", async () => {
        if (!selectedDeviceId) return;
        const deviceData = currentDevicesData[selectedDeviceId] || {};
        const name = field(deviceData, "deviceName") || field(deviceData, "model") || selectedDeviceId;
        const confirmed = window.confirm(`¿Archivar el dispositivo "${name}"?\n\nEl dispositivo quedará oculto del panel pero sus datos se conservarán en la base de datos (en la sección "archivedDevices"). Podrás restaurarlo manualmente desde Firebase Console.`);
        if (!confirmed) return;
        sidebarArchiveBtn.disabled = true;
        sidebarStatusMsg.textContent = "Archivando...";
        try {
            // Copiar datos a archivedDevices y borrar de devices
            const snapshot = await database.ref(`devices/${selectedDeviceId}`).once("value");
            const data = snapshot.val();
            if (data) {
                data._archivedAt = new Date().toISOString();
                await database.ref(`archivedDevices/${selectedDeviceId}`).set(data);
            }
            await database.ref(`devices/${selectedDeviceId}`).remove();
            sidebarStatusMsg.textContent = "✔ Dispositivo archivado.";
            closeDeviceSidebar();
        } catch (e) {
            sidebarStatusMsg.textContent = "✘ Error al archivar: " + e.message;
        } finally {
            sidebarArchiveBtn.disabled = false;
        }
    });
}

// --- Eliminar dispositivo definitivamente ---
if (sidebarDeleteBtn) {
    sidebarDeleteBtn.addEventListener("click", async () => {
        if (!selectedDeviceId) return;
        const deviceData = currentDevicesData[selectedDeviceId] || {};
        const name = field(deviceData, "deviceName") || field(deviceData, "model") || selectedDeviceId;
        const confirmed1 = window.confirm(`⚠️ ¿Eliminar permanentemente el dispositivo "${name}"?\n\nEsta acción NO se puede deshacer. Todos los datos del dispositivo serán borrados para siempre.`);
        if (!confirmed1) return;
        const confirmed2 = window.confirm(`¿Confirmás que querés eliminar "${name}" de forma irreversible?`);
        if (!confirmed2) return;
        sidebarDeleteBtn.disabled = true;
        sidebarStatusMsg.textContent = "Eliminando...";
        try {
            await database.ref(`devices/${selectedDeviceId}`).remove();
            await database.ref(`deviceSecrets/${selectedDeviceId}`).remove().catch(() => {});
            sidebarStatusMsg.textContent = "✔ Dispositivo eliminado.";
            closeDeviceSidebar();
        } catch (e) {
            sidebarStatusMsg.textContent = "✘ Error al eliminar: " + e.message;
        } finally {
            sidebarDeleteBtn.disabled = false;
        }
    });
}

// ==========================================
// LÓGICA DE GRUPOS
// ==========================================
const mainTabDevices = document.getElementById("main-tab-devices");
const mainTabGroups = document.getElementById("main-tab-groups");
const groupsContainer = document.getElementById("groups-container");
const groupsList = document.getElementById("groups-list");
const createGroupBtn = document.getElementById("create-group-btn");

const groupSidebar = document.getElementById("group-sidebar");
const groupSidebarName = document.getElementById("group-sidebar-name");
const groupSidebarId = document.getElementById("group-sidebar-id");
const groupSidebarCloseBtn = document.getElementById("group-sidebar-close-btn");
const groupNameInput = document.getElementById("group-name-input");
const saveGroupNameBtn = document.getElementById("save-group-name-btn");
const groupDevicesSelectorList = document.getElementById("group-devices-selector-list");
const groupSidebarStatusMsg = document.getElementById("group-sidebar-status-msg");

// Toggles de políticas grupales
const groupPolicyPlaystore = document.getElementById("group-policy-playstore");
const groupPolicyCamera = document.getElementById("group-policy-camera");
const groupPolicyAdblock = document.getElementById("group-policy-adblock");
const groupPolicyGifs = document.getElementById("group-policy-gifs");
const groupPolicyWebview = document.getElementById("group-policy-webview");

// Acciones de mantenimiento de grupo
const groupActionUnsuspendAll = document.getElementById("group-action-unsuspend-all");
const groupUpdatePackageInput = document.getElementById("group-update-package-input");
const groupActionUpdateApp = document.getElementById("group-action-update-app");
const groupActionUpdateLocksuite = document.getElementById("group-action-update-locksuite");
const groupDeleteBtn = document.getElementById("group-delete-btn");

let selectedGroupId = null;
let currentGroupsData = {};

// 1. Alternancia de Pestañas Principales (Celulares / Grupos / Archivados)
const mainTabArchived = document.getElementById("main-tab-archived");
const archivedContainer = document.getElementById("archived-container");
const archivedList = document.getElementById("archived-list");

let currentArchivedDevicesData = {};
let archivedListener = null;

const mainTabGlobalSettings = document.getElementById("main-tab-global-settings");
const globalSettingsContainer = document.getElementById("global-settings-container");

const globalAllowedPackagesInput = document.getElementById("global-allowed-packages-input");
const saveGlobalAllowedPackagesBtn = document.getElementById("save-global-allowed-packages-btn");
const globalAllowedStatusMsg = document.getElementById("global-allowed-status-msg");

const storeAppLabel = document.getElementById("store-app-label");
const storeAppPackage = document.getElementById("store-app-package");
const storeAppUrl = document.getElementById("store-app-url");
const addStoreAppBtn = document.getElementById("add-store-app-btn");
const storeAppsList = document.getElementById("store-apps-list");

if (mainTabDevices && mainTabGroups && mainTabArchived && mainTabGlobalSettings) {
    mainTabDevices.addEventListener("click", () => {
        mainTabDevices.classList.add("active");
        mainTabGroups.classList.remove("active");
        mainTabArchived.classList.remove("active");
        mainTabGlobalSettings.classList.remove("active");
        
        mainTabDevices.style.background = "var(--navy-light)";
        mainTabDevices.style.color = "var(--text-light)";
        mainTabGroups.style.background = "none";
        mainTabGroups.style.color = "var(--text-gray)";
        mainTabArchived.style.background = "none";
        mainTabArchived.style.color = "var(--text-gray)";
        mainTabGlobalSettings.style.background = "none";
        mainTabGlobalSettings.style.color = "var(--text-gray)";
        
        devicesContainer.classList.remove("hidden");
        groupsContainer.classList.add("hidden");
        archivedContainer.classList.add("hidden");
        globalSettingsContainer.classList.add("hidden");
    });

    mainTabGroups.addEventListener("click", () => {
        mainTabGroups.classList.add("active");
        mainTabDevices.classList.remove("active");
        mainTabArchived.classList.remove("active");
        mainTabGlobalSettings.classList.remove("active");
        
        mainTabGroups.style.background = "var(--navy-light)";
        mainTabGroups.style.color = "var(--text-light)";
        mainTabDevices.style.background = "none";
        mainTabDevices.style.color = "var(--text-gray)";
        mainTabArchived.style.background = "none";
        mainTabArchived.style.color = "var(--text-gray)";
        mainTabGlobalSettings.style.background = "none";
        mainTabGlobalSettings.style.color = "var(--text-gray)";
        
        groupsContainer.classList.remove("hidden");
        devicesContainer.classList.add("hidden");
        archivedContainer.classList.add("hidden");
        globalSettingsContainer.classList.add("hidden");
        closeDeviceSidebar();
    });

    mainTabArchived.addEventListener("click", () => {
        mainTabArchived.classList.add("active");
        mainTabDevices.classList.remove("active");
        mainTabGroups.classList.remove("active");
        mainTabGlobalSettings.classList.remove("active");
        
        mainTabArchived.style.background = "var(--navy-light)";
        mainTabArchived.style.color = "var(--text-light)";
        mainTabDevices.style.background = "none";
        mainTabDevices.style.color = "var(--text-gray)";
        mainTabGroups.style.background = "none";
        mainTabGroups.style.color = "var(--text-gray)";
        mainTabGlobalSettings.style.background = "none";
        mainTabGlobalSettings.style.color = "var(--text-gray)";
        
        archivedContainer.classList.remove("hidden");
        devicesContainer.classList.add("hidden");
        groupsContainer.classList.add("hidden");
        globalSettingsContainer.classList.add("hidden");
        closeDeviceSidebar();
        closeGroupSidebar();
    });

        closeGroupSidebar();
    });

    const mainTabPresets = document.getElementById("main-tab-presets");
    const presetsContainer = document.getElementById("presets-container");
    const presetsList = document.getElementById("presets-list");

    if (mainTabPresets) {
        mainTabPresets.addEventListener("click", () => {
            mainTabPresets.classList.add("active");
            mainTabDevices.classList.remove("active");
            mainTabGroups.classList.remove("active");
            mainTabArchived.classList.remove("active");
            mainTabGlobalSettings.classList.remove("active");
            
            mainTabPresets.style.background = "var(--navy-light)";
            mainTabPresets.style.color = "var(--text-light)";
            mainTabDevices.style.background = "none";
            mainTabDevices.style.color = "var(--text-gray)";
            mainTabGroups.style.background = "none";
            mainTabGroups.style.color = "var(--text-gray)";
            mainTabArchived.style.background = "none";
            mainTabArchived.style.color = "var(--text-gray)";
            mainTabGlobalSettings.style.background = "none";
            mainTabGlobalSettings.style.color = "var(--text-gray)";
            
            presetsContainer.classList.remove("hidden");
            devicesContainer.classList.add("hidden");
            groupsContainer.classList.add("hidden");
            archivedContainer.classList.add("hidden");
            globalSettingsContainer.classList.add("hidden");
            closeDeviceSidebar();
            closeGroupSidebar();

            loadPresetsList();
        });
    }

    mainTabGlobalSettings.addEventListener("click", () => {
        mainTabGlobalSettings.classList.add("active");
        mainTabDevices.classList.remove("active");
        mainTabGroups.classList.remove("active");
        mainTabArchived.classList.remove("active");
        if (mainTabPresets) mainTabPresets.classList.remove("active");
        
        mainTabGlobalSettings.style.background = "var(--navy-light)";
        mainTabGlobalSettings.style.color = "var(--text-light)";
        mainTabDevices.style.background = "none";
        mainTabDevices.style.color = "var(--text-gray)";
        mainTabGroups.style.background = "none";
        mainTabGroups.style.color = "var(--text-gray)";
        mainTabArchived.style.background = "none";
        mainTabArchived.style.color = "var(--text-gray)";
        if (mainTabPresets) {
            mainTabPresets.style.background = "none";
            mainTabPresets.style.color = "var(--text-gray)";
        }
        
        globalSettingsContainer.classList.remove("hidden");
        devicesContainer.classList.add("hidden");
        groupsContainer.classList.add("hidden");
        archivedContainer.classList.add("hidden");
        presetsContainer.classList.add("hidden");
        closeDeviceSidebar();
        closeGroupSidebar();
    });
}

// ─────────────────────────────────────────────
// GESTIÓN DE PERFILES GUARDADOS (PRESETS)
// ─────────────────────────────────────────────

async function computeHmacSha256(dataStr) {
    try {
        const encoder = new TextEncoder();
        const keyData = encoder.encode("LockSuiteMDM_Preset_HMAC_SecretKey_2026");
        const msgData = encoder.encode(dataStr);

        const cryptoKey = await crypto.subtle.importKey(
            "raw",
            keyData,
            { name: "HMAC", hash: "SHA-256" },
            false,
            ["sign"]
        );

        const signatureBuffer = await crypto.subtle.sign("HMAC", cryptoKey, msgData);
        const hashArray = Array.from(new Uint8Array(signatureBuffer));
        return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
    } catch (e) {
        console.error("Error al calcular HMAC:", e);
        return "";
    }
}

function loadPresetsList() {
    const presetsList = document.getElementById("presets-list");
    if (!presetsList) return;
    presetsList.innerHTML = '<p class="loading-text">Cargando perfiles guardados…</p>';

    database.ref("presets").once("value", snapshot => {
        const presets = snapshot.val() || {};
        renderPresetsList(presets);
    }).catch(err => {
        presetsList.innerHTML = `<p class="error-text">Error al cargar perfiles: ${err.message}</p>`;
    });
}

function renderPresetsList(presets) {
    const presetsList = document.getElementById("presets-list");
    if (!presetsList) return;
    presetsList.innerHTML = "";

    const entries = Object.entries(presets);
    if (entries.length === 0) {
        presetsList.innerHTML = '<p class="loading-text" style="grid-column: 1/-1;">No hay ningún perfil guardado aún. Hace clic en "+ Guardar Perfil Actual" o importa un respaldo .locksuite.</p>';
        return;
    }

    entries.forEach(([presetId, preset]) => {
        const card = document.createElement("div");
        card.className = "card";
        card.style.background = "var(--navy-medium)";
        card.style.borderRadius = "16px";
        card.style.padding = "20px";
        card.style.display = "flex";
        card.style.flexDirection = "column";
        card.style.justifyContent = "space-between";
        card.style.gap = "12px";

        const title = document.createElement("h3");
        title.style.margin = "0";
        title.style.fontSize = "16px";
        title.style.color = "var(--accent)";
        title.textContent = preset.presetName || "Perfil Sin Nombre";

        const dateStr = preset.createdAt ? new Date(preset.createdAt).toLocaleString() : "Fecha desconocida";
        const dateEl = document.createElement("p");
        dateEl.style.margin = "2px 0 8px 0";
        dateEl.style.fontSize = "11px";
        dateEl.style.color = "var(--text-gray)";
        dateEl.textContent = `Creado: ${dateStr}`;

        const details = document.createElement("div");
        details.style.fontSize = "12px";
        details.style.color = "var(--text-light)";
        details.style.display = "flex";
        details.style.flexDirection = "column";
        details.style.gap = "4px";

        const d = preset.data || {};
        const restr = d.restrictions || {};
        const restrCount = Object.values(restr).filter(Boolean).length;
        const perAppCount = (d.perAppInternetBlocked || []).length;

        details.innerHTML = `
            <span>🔒 Restricciones de Sistema: <strong>${restrCount} activas</strong></span>
            <span>🌐 Anuncios: <strong>${d.adBlockingEnabled ? "Bloqueados" : "Permitidos"}</strong></span>
            <span>📱 Bloqueo Total Internet Apps: <strong>${perAppCount} apps</strong></span>
            <span>🛒 Mercado Pago (Acc/VPN): <strong>${d.mercadoPagoBlockOffersAccessibility ? "Sí" : "No"} / ${d.mercadoPagoBlockOffersVpn ? "Sí" : "No"}</strong></span>
        `;

        const actions = document.createElement("div");
        actions.style.display = "flex";
        actions.style.gap = "8px";
        actions.style.marginTop = "8px";

        const applyBtn = document.createElement("button");
        applyBtn.className = "action-btn";
        applyBtn.style.background = "var(--success-green)";
        applyBtn.style.color = "white";
        applyBtn.style.fontWeight = "bold";
        applyBtn.style.fontSize = "12px";
        applyBtn.style.padding = "8px 12px";
        applyBtn.style.flex = "1";
        applyBtn.textContent = "⚡ Aplicar a Dispositivo";
        applyBtn.addEventListener("click", () => applyPresetToDeviceModal(preset));

        const exportBtn = document.createElement("button");
        exportBtn.className = "action-btn";
        exportBtn.style.background = "var(--navy-light)";
        exportBtn.style.color = "var(--text-light)";
        exportBtn.style.fontSize = "12px";
        exportBtn.style.padding = "8px 12px";
        exportBtn.textContent = "💾 Exportar";
        exportBtn.addEventListener("click", () => exportPresetToFile(preset));

        const deleteBtn = document.createElement("button");
        deleteBtn.className = "action-btn";
        deleteBtn.style.background = "#e74c3c";
        deleteBtn.style.color = "white";
        deleteBtn.style.fontSize = "12px";
        deleteBtn.style.padding = "8px 12px";
        deleteBtn.textContent = "🗑️";
        deleteBtn.addEventListener("click", () => {
            if (confirm(`¿Eliminar el perfil "${preset.presetName}"?`)) {
                database.ref(`presets/${presetId}`).remove().then(() => loadPresetsList());
            }
        });

        actions.appendChild(applyBtn);
        actions.appendChild(exportBtn);
        actions.appendChild(deleteBtn);

        card.appendChild(title);
        card.appendChild(dateEl);
        card.appendChild(details);
        card.appendChild(actions);

        presetsList.appendChild(card);
    });
}

// 📌 Guardar Estado Actual como Nuevo Perfil
const savePresetBtn = document.getElementById("save-current-preset-btn");
if (savePresetBtn) {
    savePresetBtn.addEventListener("click", async () => {
        if (!selectedDeviceId || !currentDevicesData[selectedDeviceId]) {
            alert("Seleccioná primero un celular en la pestaña 'Celulares' para usar su configuración como plantilla.");
            return;
        }

        const name = prompt("Escribí el nombre para este perfil de bloqueo (ej. 'Bloqueo Fuerte A'):");
        if (!name || !name.trim()) return;

        const dev = currentDevicesData[selectedDeviceId];
        const dataObj = {
            restrictions: {
                no_factory_reset: !!dev.factoryResetBlocked,
                no_install_apps: !!dev.installAppsBlocked,
                no_uninstall_apps: !!dev.uninstallAppsBlocked,
                no_debugging_features: !!dev.adbBlocked,
                no_user_switch: !!dev.userSwitchBlocked,
                no_modify_accounts: !!dev.modifyAccountsBlocked,
                no_safe_boot: !!dev.safeBootBlocked,
                no_install_unknown_sources: !!dev.unknownSourcesBlocked,
                no_config_wifi: !!dev.wifiBlocked,
                no_config_vpn: !!dev.vpnBlocked,
                no_adjust_volume: !!dev.adjustVolumeBlocked,
                no_apps_control: !!dev.appsControlBlocked,
                no_bluetooth_sharing: !!dev.bluetoothSharingBlocked,
                no_physical_media: !!dev.externalMediaBlocked,
                no_config_tethering: !!dev.tetheringBlocked
            },
            cameraDisabled: !!dev.cameraDisabled,
            screenCaptureBlocked: !!dev.screenCaptureBlocked,
            statusBarDisabled: !!dev.statusBarDisabled,
            keyguardDisabled: !!dev.keyguardDisabled,
            internetBlocked: !!dev.internetBlocked,
            adBlockingEnabled: !!dev.adBlockingEnabled,
            gifsBlocked: !!dev.gifsBlocked,
            whatsappBlockStatus: !!dev.whatsappBlockStatus,
            whatsappBlockChannels: !!dev.whatsappBlockChannels,
            mercadoPagoBlockOffersAccessibility: !!dev.mercadoPagoBlockOffersAccessibility,
            mercadoPagoBlockOffersVpn: !!dev.mercadoPagoBlockOffersVpn,
            perAppInternetBlocked: Object.values(dev.apps || {}).filter(a => a.isInternetBlocked).map(a => a.packageName)
        };

        const dataStr = JSON.stringify(dataObj);
        const signature = await computeHmacSha256(dataStr);

        const presetPayload = {
            presetName: name.trim(),
            createdAt: Date.now(),
            version: 1,
            data: dataObj,
            signature: signature
        };

        database.ref("presets").push(presetPayload).then(() => {
            alert(`✅ Perfil "${name.trim()}" guardado con éxito.`);
            loadPresetsList();
        }).catch(err => alert("Error al guardar perfil: " + err.message));
    });
}

// 📌 Exportar Perfil a Archivo .locksuite
function exportPresetToFile(preset) {
    const jsonStr = JSON.stringify(preset, null, 2);
    const blob = new Blob([jsonStr], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${(preset.presetName || "perfil").replace(/\s+/g, "_")}.locksuite`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

// 📌 Importar Archivo de Respaldo .locksuite
const importFileInput = document.getElementById("import-preset-file-input");
if (importFileInput) {
    importFileInput.addEventListener("change", async e => {
        const file = e.target.files[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = async event => {
            try {
                const preset = JSON.parse(event.target.result);
                if (!preset.data || !preset.signature) {
                    alert("🚨 Archivo inválido o corrupto.");
                    return;
                }

                // Verificar Firma HMAC
                const computed = await computeHmacSha256(JSON.stringify(preset.data));
                if (computed.toLowerCase() !== preset.signature.toLowerCase()) {
                    alert("🚨 FIRMA INVÁLIDA: El archivo de respaldo ha sido modificado o alterado de forma no autorizada.");
                    return;
                }

                await database.ref("presets").push(preset);
                alert(`✅ Perfil "${preset.presetName || 'Importado'}" verificado e importado con éxito.`);
                loadPresetsList();
            } catch (err) {
                alert("Error al leer el archivo: " + err.message);
            }
        };
        reader.readAsText(file);
    });
}

// 📌 Aplicar Perfil a un Dispositivo en 1-Clic
function applyPresetToDeviceModal(preset) {
    const deviceId = prompt("Ingresá el ID del dispositivo al que querés aplicar este perfil (o dejalo como está si querés aplicarlo al celular seleccionado actualmente):", selectedDeviceId || "");
    if (!deviceId || !deviceId.trim()) return;

    const targetId = deviceId.trim();
    const presetJsonStr = JSON.stringify(preset);

    runCommandOnDevice(targetId, "APPLY_PRESET_PROFILE", { presetJson: presetJsonStr });
}

// 2. Renderizar Lista de Tarjetas de Grupos
function renderGroupsList(groups) {
    if (!groupsList) return;
    groupsList.innerHTML = "";
    
    const groupEntries = Object.entries(groups);
    if (groupEntries.length === 0) {
        groupsList.innerHTML = '<p class="loading-text" style="grid-column: 1/-1;">Todavía no hay ningún grupo creado. Hacé clic en "+ Crear Nuevo Grupo".</p>';
        return;
    }
    
    groupEntries.forEach(([groupId, group]) => {
        const deviceIds = Object.keys(group.devices || {});
        const deviceCount = deviceIds.length;
        
        const card = document.createElement("div");
        card.className = "card";
        card.style.background = "var(--navy-medium)";
        card.style.borderRadius = "16px";
        card.style.padding = "20px";
        card.style.display = "flex";
        card.style.flexDirection = "column";
        card.style.justifyContent = "space-between";
        card.style.gap = "12px";
        
        const header = document.createElement("div");
        header.style.display = "flex";
        header.style.justifyContent = "space-between";
        header.style.alignItems = "flex-start";
        
        const info = document.createElement("div");
        const title = document.createElement("h2");
        title.style.margin = "0";
        title.style.fontSize = "16px";
        title.textContent = group.name || "Grupo sin nombre";
        
        const sub = document.createElement("p");
        sub.style.margin = "4px 0 0 0";
        sub.style.fontSize = "12px";
        sub.style.color = "var(--text-gray)";
        sub.textContent = `${deviceCount} celular${deviceCount === 1 ? "" : "es"} asignado${deviceCount === 1 ? "" : "s"}`;
        
        info.appendChild(title);
        info.appendChild(sub);
        
        const badge = document.createElement("span");
        badge.className = "badge";
        badge.style.backgroundColor = deviceCount > 0 ? "var(--navy-light)" : "rgba(255,255,255,0.05)";
        badge.style.color = deviceCount > 0 ? "var(--text-light)" : "var(--text-gray)";
        badge.textContent = deviceCount > 0 ? "Activo" : "Vacío";
        
        header.appendChild(info);
        header.appendChild(badge);
        card.appendChild(header);
        
        const actionBar = document.createElement("div");
        const adminBtn = document.createElement("button");
        adminBtn.className = "action-btn";
        adminBtn.style.background = "var(--accent-orange)";
        adminBtn.style.color = "var(--navy-dark)";
        adminBtn.style.fontWeight = "bold";
        adminBtn.style.fontSize = "12px";
        adminBtn.style.padding = "8px 16px";
        adminBtn.style.borderRadius = "8px";
        adminBtn.style.cursor = "pointer";
        adminBtn.style.width = "100%";
        adminBtn.textContent = "👥 Configurar Grupo";
        
        adminBtn.addEventListener("click", () => openGroupSidebar(groupId, group));
        actionBar.appendChild(adminBtn);
        card.appendChild(actionBar);
        
        groupsList.appendChild(card);
    });
}

// 3. Crear Nuevo Grupo
if (createGroupBtn) {
    createGroupBtn.addEventListener("click", () => {
        const groupName = prompt("Escribí el nombre para el nuevo grupo:");
        if (!groupName || groupName.trim() === "") return;
        
        database.ref("groups").push({
            name: groupName.trim(),
            devices: {},
            policies: {
                playstoreBlocked: false,
                cameraDisabled: false,
                adBlockingEnabled: false,
                gifsBlocked: false,
                webviewBlocked: false
            }
        }).then(() => {
            console.log("Grupo creado con éxito");
        }).catch(err => {
            alert("Error al crear grupo: " + err.message);
        });
    });
}

// 4. Sidebar del Grupo (Abrir / Cerrar)
function openGroupSidebar(groupId, group) {
    selectedGroupId = groupId;
    if (groupSidebar) {
        groupSidebar.classList.remove("hidden");
        updateGroupSidebarUI(groupId, group);
    }
}

function closeGroupSidebar() {
    selectedGroupId = null;
    if (groupSidebar) {
        groupSidebar.classList.add("hidden");
    }
}

if (groupSidebarCloseBtn) {
    groupSidebarCloseBtn.addEventListener("click", closeGroupSidebar);
}

// 5. Alternancia de Pestañas internas del Sidebar del Grupo
const groupTabButtons = document.querySelectorAll(".group-tab-btn");
const groupTabPanels = document.querySelectorAll(".group-tab-panel");
groupTabButtons.forEach(btn => {
    btn.addEventListener("click", () => {
        groupTabButtons.forEach(b => {
            b.classList.remove("active");
            b.style.color = "var(--text-gray)";
            b.style.borderBottomColor = "transparent";
        });
        groupTabPanels.forEach(p => p.style.display = "none");
        
        btn.classList.add("active");
        btn.style.color = "var(--accent-orange)";
        btn.style.borderBottomColor = "var(--accent-orange)";
        
        const tabId = btn.getAttribute("data-tab");
        const panel = document.getElementById(tabId);
        if (panel) {
            panel.style.display = "flex";
        }
    });
});

// 6. Actualizar Interfaz del Sidebar del Grupo
function updateGroupSidebarUI(groupId, group) {
    if (!groupSidebar) return;
    
    groupSidebarName.textContent = group.name || "Grupo sin nombre";
    groupSidebarId.textContent = `ID: ${groupId}`;
    groupNameInput.value = group.name || "";
    
    const policies = group.policies || {};
    groupSidebar.querySelectorAll(".group-policy-switch").forEach(el => {
        const policyKey = el.getAttribute("data-policy");
        el.checked = !!policies[policyKey];
    });
    
    // Renderizar selector de dispositivos
    renderGroupDevicesSelector(group);
}

// 7. Renderizar Lista de Selección de Celulares
function renderGroupDevicesSelector(group) {
    if (!groupDevicesSelectorList) return;
    groupDevicesSelectorList.innerHTML = "";
    
    const deviceEntries = Object.entries(currentDevicesData);
    if (deviceEntries.length === 0) {
        groupDevicesSelectorList.innerHTML = '<p class="hint-text">No hay celulares registrados para asociar.</p>';
        return;
    }
    
    deviceEntries.forEach(([deviceId, device]) => {
        const name = field(device, "deviceName", "Dispositivo sin nombre");
        const model = field(device, "model", "Modelo desconocido");
        const isChecked = group.devices && group.devices[deviceId] === true;
        
        const label = document.createElement("label");
        label.style.display = "flex";
        label.style.justifyContent = "space-between";
        label.style.alignItems = "center";
        label.style.fontSize = "13px";
        label.style.padding = "8px";
        label.style.background = "var(--navy-dark)";
        label.style.borderRadius = "8px";
        label.style.cursor = "pointer";
        
        const textSpan = document.createElement("span");
        textSpan.style.display = "flex";
        textSpan.style.flexDirection = "column";
        
        const nameSpan = document.createElement("span");
        nameSpan.style.fontWeight = "bold";
        nameSpan.textContent = name;
        
        const modelSpan = document.createElement("span");
        modelSpan.style.fontSize = "10px";
        modelSpan.style.color = "var(--text-gray)";
        modelSpan.textContent = `${model} (ID: ${deviceId.substring(0, 8)}...)`;
        
        textSpan.appendChild(nameSpan);
        textSpan.appendChild(modelSpan);
        
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.checked = isChecked;
        checkbox.style.width = "auto";
        checkbox.style.margin = "0";
        
        checkbox.addEventListener("change", () => {
            if (checkbox.checked) {
                database.ref(`groups/${selectedGroupId}/devices/${deviceId}`).set(true).then(() => {
                    // Sincronizar políticas del grupo actuales a este nuevo celular de inmediato
                    applyGroupPoliciesToSingleDevice(selectedGroupId, deviceId);
                });
            } else {
                database.ref(`groups/${selectedGroupId}/devices/${deviceId}`).remove();
            }
        });
        
        label.appendChild(textSpan);
        label.appendChild(checkbox);
        groupDevicesSelectorList.appendChild(label);
    });
}

// Guardar cambio de nombre de grupo
if (saveGroupNameBtn && groupNameInput) {
    saveGroupNameBtn.addEventListener("click", () => {
        if (!selectedGroupId) return;
        const newName = groupNameInput.value.trim();
        if (newName === "") return;
        saveGroupNameBtn.disabled = true;
        
        database.ref(`groups/${selectedGroupId}/name`).set(newName).then(() => {
            groupSidebarName.textContent = newName;
            groupSidebarStatusMsg.textContent = "✔ Nombre guardado.";
            setTimeout(() => groupSidebarStatusMsg.textContent = "", 3000);
        }).catch(err => {
            groupSidebarStatusMsg.textContent = "✘ Error: " + err.message;
        }).finally(() => {
            saveGroupNameBtn.disabled = false;
        });
    });
}

// 8. Enviar Comando a Todos los Dispositivos del Grupo
async function runCommandOnGroup(groupId, command, packages = null, buttonEl = null, extraParams = null) {
    const groupSnap = await database.ref(`groups/${groupId}`).once("value");
    if (!groupSnap.exists()) return;
    const group = groupSnap.val();
    const deviceIds = Object.keys(group.devices || {});
    if (deviceIds.length === 0) {
        groupSidebarStatusMsg.textContent = "El grupo no tiene celulares asociados.";
        setTimeout(() => groupSidebarStatusMsg.textContent = "", 4000);
        return;
    }
    
    if (buttonEl) buttonEl.disabled = true;
    groupSidebarStatusMsg.textContent = `Enviando comando a ${deviceIds.length} celulares...`;
    
    let successCount = 0;
    let failCount = 0;
    
    for (const deviceId of deviceIds) {
        try {
            const idToken = await auth.currentUser.getIdToken();
            const payload = {
                deviceId: deviceId,
                command: command,
                packages: packages
            };
            if (extraParams) {
                Object.assign(payload, extraParams);
            }
            
            const response = await fetch("https://sendcommandv8-687828714595.us-central1.run.app", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Authorization": `Bearer ${idToken}`
                },
                body: JSON.stringify(payload)
            });
            if (response.ok) {
                successCount++;
            } else {
                failCount++;
            }
        } catch (err) {
            failCount++;
        }
    }
    
    groupSidebarStatusMsg.textContent = `Enviado: ${successCount} exitosos, ${failCount} fallidos.`;
    if (buttonEl) buttonEl.disabled = false;
    setTimeout(() => {
        if (groupSidebarStatusMsg.textContent.startsWith("Enviado:")) {
            groupSidebarStatusMsg.textContent = "";
        }
    }, 5000);
}

// 9. Aplicar Políticas del Grupo al Asociar un Nuevo Celular
async function applyGroupPoliciesToSingleDevice(groupId, deviceId) {
    try {
        const groupSnap = await database.ref(`groups/${groupId}`).once("value");
        if (!groupSnap.exists()) return;
        const group = groupSnap.val();
        const policies = group.policies || {};
        
        const idToken = await auth.currentUser.getIdToken();
        const sendPayload = async (cmd, pkgs = null) => {
            await fetch("https://sendcommandv8-687828714595.us-central1.run.app", {
                method: "POST",
                headers: { "Content-Type": "application/json", "Authorization": `Bearer ${idToken}` },
                body: JSON.stringify({ deviceId: deviceId, command: cmd, packages: pkgs })
            }).catch(() => {});
        };

        const mapping = {
            factoryResetBlocked: ["BLOCK_FACTORY_RESET", "UNBLOCK_FACTORY_RESET"],
            installAppsBlocked: ["BLOCK_INSTALL_APPS", "UNBLOCK_INSTALL_APPS"],
            uninstallAppsBlocked: ["BLOCK_UNINSTALL_APPS", "UNBLOCK_UNINSTALL_APPS"],
            adbBlocked: ["BLOCK_ADB", "UNBLOCK_ADB"],
            userSwitchBlocked: ["BLOCK_USER_SWITCH", "UNBLOCK_USER_SWITCH"],
            modifyAccountsBlocked: ["BLOCK_MODIFY_ACCOUNTS", "UNBLOCK_MODIFY_ACCOUNTS"],
            safeBootBlocked: ["BLOCK_SAFE_BOOT", "UNBLOCK_SAFE_BOOT"],
            unknownSourcesBlocked: ["BLOCK_UNKNOWN_SOURCES", "UNBLOCK_UNKNOWN_SOURCES"],
            wifiBlocked: ["BLOCK_WIFI", "UNBLOCK_WIFI"],
            vpnBlocked: ["BLOCK_VPN", "UNBLOCK_VPN"],
            appsControlBlocked: ["BLOCK_APPS_CONTROL", "UNBLOCK_APPS_CONTROL"],
            adjustVolumeBlocked: ["BLOCK_VOLUME", "UNBLOCK_VOLUME"],
            cameraDisabled: ["DISABLE_CAMERA", "ENABLE_CAMERA"],
            screenCaptureBlocked: ["BLOCK_SCREEN_CAPTURE", "UNBLOCK_SCREEN_CAPTURE"],
            statusBarDisabled: ["DISABLE_STATUSBAR", "ENABLE_STATUSBAR"],
            keyguardDisabled: ["DISABLE_KEYGUARD", "ENABLE_KEYGUARD"],
            internetBlocked: ["BLOCK_INTERNET", "UNBLOCK_INTERNET"],
            bluetoothBlocked: ["BLOCK_BLUETOOTH", "UNBLOCK_BLUETOOTH"],
            bluetoothSharingBlocked: ["BLOCK_BLUETOOTH_SHARING", "UNBLOCK_BLUETOOTH_SHARING"],
            externalMediaBlocked: ["BLOCK_EXTERNAL_MEDIA", "UNBLOCK_EXTERNAL_MEDIA"],
            tetheringBlocked: ["BLOCK_TETHERING", "UNBLOCK_TETHERING"],
            adBlockingEnabled: ["ENABLE_ADBLOCK", "DISABLE_ADBLOCK"],
            gifsBlocked: ["BLOCK_GIFS", "UNBLOCK_GIFS"],
            aiModeEnabled: ["ENABLE_AI_MODE", "DISABLE_AI_MODE"],
            mapsImageBlockingEnabled: ["ENABLE_MAPS_IMAGE_BLOCKING", "DISABLE_MAPS_IMAGE_BLOCKING"],
            whatsappBlockStatus: ["BLOCK_WHATSAPP_STATUS", "UNBLOCK_WHATSAPP_STATUS"],
            whatsappBlockChannels: ["BLOCK_WHATSAPP_CHANNELS", "UNBLOCK_WHATSAPP_CHANNELS"],
            mercadoPagoBlockOffers: ["BLOCK_MERCADOPAGO_OFFERS", "UNBLOCK_MERCADOPAGO_OFFERS"],
            mercadoPagoBlockOffersAccessibility: ["BLOCK_MP_OFFERS_ACCESSIBILITY", "UNBLOCK_MP_OFFERS_ACCESSIBILITY"],
            mercadoPagoBlockOffersVpn: ["BLOCK_MP_OFFERS_VPN", "UNBLOCK_MP_OFFERS_VPN"],
            stealthModeEnabled: ["ENABLE_STEALTH", "DISABLE_STEALTH"],
            webviewBlocked: ["BLOCK_WEBVIEW", "UNBLOCK_WEBVIEW"]
        };

        for (const [policyKey, mapCmds] of Object.entries(mapping)) {
            const active = policies[policyKey] === true;
            const cmd = active ? mapCmds[0] : mapCmds[1];
            const pkgs = (policyKey === "webviewBlocked") ? ["com.android.chrome"] : null;
            await sendPayload(cmd, pkgs);
        }
    } catch (err) {
        console.error("Error al sincronizar políticas iniciales:", err);
    }
}

// 10. Listeners para Toggles de Políticas Grupales
if (groupSidebar) {
    groupSidebar.addEventListener("change", (e) => {
        if (!e.target.classList.contains("group-policy-switch")) return;
        if (!selectedGroupId) return;
        
        const el = e.target;
        const policyKey = el.getAttribute("data-policy");
        const active = el.checked;
        
        const mapping = {
            factoryResetBlocked: ["BLOCK_FACTORY_RESET", "UNBLOCK_FACTORY_RESET"],
            installAppsBlocked: ["BLOCK_INSTALL_APPS", "UNBLOCK_INSTALL_APPS"],
            uninstallAppsBlocked: ["BLOCK_UNINSTALL_APPS", "UNBLOCK_UNINSTALL_APPS"],
            adbBlocked: ["BLOCK_ADB", "UNBLOCK_ADB"],
            userSwitchBlocked: ["BLOCK_USER_SWITCH", "UNBLOCK_USER_SWITCH"],
            modifyAccountsBlocked: ["BLOCK_MODIFY_ACCOUNTS", "UNBLOCK_MODIFY_ACCOUNTS"],
            safeBootBlocked: ["BLOCK_SAFE_BOOT", "UNBLOCK_SAFE_BOOT"],
            unknownSourcesBlocked: ["BLOCK_UNKNOWN_SOURCES", "UNBLOCK_UNKNOWN_SOURCES"],
            wifiBlocked: ["BLOCK_WIFI", "UNBLOCK_WIFI"],
            vpnBlocked: ["BLOCK_VPN", "UNBLOCK_VPN"],
            appsControlBlocked: ["BLOCK_APPS_CONTROL", "UNBLOCK_APPS_CONTROL"],
            adjustVolumeBlocked: ["BLOCK_VOLUME", "UNBLOCK_VOLUME"],
            cameraDisabled: ["DISABLE_CAMERA", "ENABLE_CAMERA"],
            screenCaptureBlocked: ["BLOCK_SCREEN_CAPTURE", "UNBLOCK_SCREEN_CAPTURE"],
            statusBarDisabled: ["DISABLE_STATUSBAR", "ENABLE_STATUSBAR"],
            keyguardDisabled: ["DISABLE_KEYGUARD", "ENABLE_KEYGUARD"],
            internetBlocked: ["BLOCK_INTERNET", "UNBLOCK_INTERNET"],
            bluetoothBlocked: ["BLOCK_BLUETOOTH", "UNBLOCK_BLUETOOTH"],
            bluetoothSharingBlocked: ["BLOCK_BLUETOOTH_SHARING", "UNBLOCK_BLUETOOTH_SHARING"],
            externalMediaBlocked: ["BLOCK_EXTERNAL_MEDIA", "UNBLOCK_EXTERNAL_MEDIA"],
            tetheringBlocked: ["BLOCK_TETHERING", "UNBLOCK_TETHERING"],
            adBlockingEnabled: ["ENABLE_ADBLOCK", "DISABLE_ADBLOCK"],
            gifsBlocked: ["BLOCK_GIFS", "UNBLOCK_GIFS"],
            aiModeEnabled: ["ENABLE_AI_MODE", "DISABLE_AI_MODE"],
            mapsImageBlockingEnabled: ["ENABLE_MAPS_IMAGE_BLOCKING", "DISABLE_MAPS_IMAGE_BLOCKING"],
            whatsappBlockStatus: ["BLOCK_WHATSAPP_STATUS", "UNBLOCK_WHATSAPP_STATUS"],
            whatsappBlockChannels: ["BLOCK_WHATSAPP_CHANNELS", "UNBLOCK_WHATSAPP_CHANNELS"],
            mercadoPagoBlockOffers: ["BLOCK_MERCADOPAGO_OFFERS", "UNBLOCK_MERCADOPAGO_OFFERS"],
            mercadoPagoBlockOffersAccessibility: ["BLOCK_MP_OFFERS_ACCESSIBILITY", "UNBLOCK_MP_OFFERS_ACCESSIBILITY"],
            mercadoPagoBlockOffersVpn: ["BLOCK_MP_OFFERS_VPN", "UNBLOCK_MP_OFFERS_VPN"],
            stealthModeEnabled: ["ENABLE_STEALTH", "DISABLE_STEALTH"],
            webviewBlocked: ["BLOCK_WEBVIEW", "UNBLOCK_WEBVIEW"]
        } [policyKey];
        
        if (!mapping) return;
        const cmd = active ? mapping[0] : mapping[1];
        const pkgs = (policyKey === "webviewBlocked") ? ["com.android.chrome"] : null;
        
        database.ref(`groups/${selectedGroupId}/policies/${policyKey}`).set(active);
        runCommandOnGroup(selectedGroupId, cmd, pkgs, el);
    });
}

// 11. Acciones de Mantenimiento
if (groupActionUnsuspendAll) {
    groupActionUnsuspendAll.addEventListener("click", () => {
        if (!selectedGroupId) return;
        if (confirm("¿Desbloquear todas las aplicaciones suspendidas en TODOS los celulares de este grupo?")) {
            runCommandOnGroup(selectedGroupId, "UNSUSPEND_ALL_APPS", null, groupActionUnsuspendAll);
        }
    });
}

if (groupActionUpdateApp && groupUpdatePackageInput) {
    groupActionUpdateApp.addEventListener("click", () => {
        if (!selectedGroupId) return;
        const pkg = groupUpdatePackageInput.value.trim();
        if (pkg === "") {
            alert("Escribí el nombre del paquete para actualizar.");
            return;
        }
        if (confirm(`¿Enviar orden de actualización para "${pkg}" a todos los celulares del grupo?`)) {
            runCommandOnGroup(selectedGroupId, "UPDATE_APP", [pkg], groupActionUpdateApp);
            groupUpdatePackageInput.value = "";
        }
    });
}

if (groupActionUpdateLocksuite) {
    groupActionUpdateLocksuite.addEventListener("click", () => {
        if (!selectedGroupId) return;
        if (confirm("¿Actualizar la aplicación LockSuite (sistema) en todos los celulares de este grupo?")) {
            runCommandOnGroup(selectedGroupId, "UPDATE_LOCKSUITE", null, groupActionUpdateLocksuite);
        }
    });
}

// 12. Eliminar Grupo
if (groupDeleteBtn) {
    groupDeleteBtn.addEventListener("click", () => {
        if (!selectedGroupId) return;
        const group = currentGroupsData[selectedGroupId] || {};
        if (confirm(`¿Eliminar el grupo "${group.name || "este grupo"}"?\n\nLos celulares seguirán funcionando individualmente.`)) {
            groupDeleteBtn.disabled = true;
            database.ref(`groups/${selectedGroupId}`).remove().then(() => {
                closeGroupSidebar();
            }).catch(err => {
                alert("Error al eliminar grupo: " + err.message);
            }).finally(() => {
                groupDeleteBtn.disabled = false;
            });
        }
    });
}

// ==========================================
// LÓGICA DE DISPOSITIVOS ARCHIVADOS
// ==========================================
function renderArchivedDevicesList(archived) {
    if (!archivedList) return;
    archivedList.innerHTML = "";
    
    const entries = Object.entries(archived);
    if (entries.length === 0) {
        archivedList.innerHTML = '<p class="loading-text" style="grid-column: 1/-1;">No hay ningún dispositivo archivado en la base de datos.</p>';
        return;
    }
    
    entries.forEach(([deviceId, device]) => {
        const name = field(device, "deviceName", "Dispositivo sin nombre");
        const model = field(device, "model", "Modelo desconocido");
        const archivedAt = device._archivedAt ? new Date(device._archivedAt).toLocaleString("es-AR") : "Fecha desconocida";
        
        const card = document.createElement("div");
        card.className = "card";
        card.style.background = "var(--navy-medium)";
        card.style.borderRadius = "16px";
        card.style.padding = "20px";
        card.style.display = "flex";
        card.style.flexDirection = "column";
        card.style.justifyContent = "space-between";
        card.style.gap = "12px";
        
        const header = document.createElement("div");
        header.style.display = "flex";
        header.style.justifyContent = "space-between";
        header.style.alignItems = "flex-start";
        
        const info = document.createElement("div");
        const title = document.createElement("h2");
        title.style.margin = "0";
        title.style.fontSize = "16px";
        title.textContent = name;
        
        const modelLabel = document.createElement("p");
        modelLabel.style.margin = "4px 0 0 0";
        modelLabel.style.fontSize = "12px";
        modelLabel.style.color = "var(--text-gray)";
        modelLabel.textContent = `${model} (ID: ${deviceId.substring(0, 8)}...)`;
        
        info.appendChild(title);
        info.appendChild(modelLabel);
        
        const badge = document.createElement("span");
        badge.className = "badge inactive";
        badge.textContent = "Archivado";
        
        header.appendChild(info);
        header.appendChild(badge);
        card.appendChild(header);
        
        const dateText = document.createElement("p");
        dateText.style.margin = "0";
        dateText.style.fontSize = "11px";
        dateText.style.color = "var(--text-gray)";
        dateText.textContent = `Archivado el: ${archivedAt}`;
        card.appendChild(dateText);
        
        const actions = document.createElement("div");
        actions.style.display = "flex";
        actions.style.gap = "8px";
        
        const restoreBtn = document.createElement("button");
        restoreBtn.style.flex = "1";
        restoreBtn.style.padding = "8px";
        restoreBtn.style.fontSize = "12px";
        restoreBtn.style.background = "var(--success-green)";
        restoreBtn.style.color = "white";
        restoreBtn.style.borderRadius = "8px";
        restoreBtn.style.border = "none";
        restoreBtn.style.cursor = "pointer";
        restoreBtn.textContent = "🔓 Restaurar";
        restoreBtn.addEventListener("click", () => restoreArchivedDevice(deviceId, device, restoreBtn));
        
        const deleteBtn = document.createElement("button");
        deleteBtn.style.flex = "1";
        deleteBtn.style.padding = "8px";
        deleteBtn.style.fontSize = "12px";
        deleteBtn.style.background = "#7f1d1d";
        deleteBtn.style.color = "white";
        deleteBtn.style.border = "1px solid #ef4444";
        deleteBtn.style.borderRadius = "8px";
        deleteBtn.style.cursor = "pointer";
        deleteBtn.textContent = "🗑️ Eliminar";
        deleteBtn.addEventListener("click", () => deleteArchivedDevicePermanently(deviceId, name, deleteBtn));
        
        actions.appendChild(restoreBtn);
        actions.appendChild(deleteBtn);
        card.appendChild(actions);
        
        archivedList.appendChild(card);
    });
}

// Restaurar dispositivo archivado
async function restoreArchivedDevice(deviceId, device, button) {
    if (confirm(`¿Restaurar el dispositivo "${device.deviceName || device.model || deviceId}" al panel activo?`)) {
        button.disabled = true;
        try {
            // Eliminar marca de archivo temporal
            const restoredData = { ...device };
            delete restoredData._archivedAt;
            
            // Escribir en devices y borrar de archivedDevices
            await database.ref(`devices/${deviceId}`).set(restoredData);
            await database.ref(`archivedDevices/${deviceId}`).remove();
        } catch (err) {
            alert("Error al restaurar dispositivo: " + err.message);
            button.disabled = false;
        }
    }
}

// Eliminar dispositivo archivado definitivamente
async function deleteArchivedDevicePermanently(deviceId, name, button) {
    const confirmed1 = confirm(`⚠️ ¿Eliminar definitivamente "${name}"?\n\nEsta acción eliminará para siempre todos los registros y credenciales de este celular.`);
    if (!confirmed1) return;
    const confirmed2 = confirm(`¿Estás seguro de que querés eliminar permanentemente "${name}"?`);
    if (!confirmed2) return;
    
    button.disabled = true;
    try {
        await database.ref(`archivedDevices/${deviceId}`).remove();
        await database.ref(`deviceSecrets/${deviceId}`).remove().catch(() => {});
    } catch (err) {
        alert("Error al eliminar dispositivo: " + err.message);
        button.disabled = false;
    }
}

// ==========================================
// AJUSTES GLOBALES Y TIENDA KOSHER
// ==========================================
if (saveGlobalAllowedPackagesBtn) {
    saveGlobalAllowedPackagesBtn.addEventListener("click", () => {
        const val = globalAllowedPackagesInput.value.split(",").map(p => p.trim()).filter(Boolean);
        saveGlobalAllowedPackagesBtn.disabled = true;
        globalAllowedStatusMsg.textContent = "Guardando...";
        database.ref("globalSettings/allowedPackages").set(val)
            .then(() => {
                globalAllowedStatusMsg.textContent = "✓ Configuración guardada correctamente.";
                setTimeout(() => globalAllowedStatusMsg.textContent = "", 4000);
            })
            .catch(err => {
                globalAllowedStatusMsg.textContent = "✗ Error al guardar: " + err.message;
            })
            .finally(() => {
                saveGlobalAllowedPackagesBtn.disabled = false;
            });
    });
}

if (addStoreAppBtn) {
    addStoreAppBtn.addEventListener("click", () => {
        const label = storeAppLabel.value.trim();
        const pkg = storeAppPackage.value.trim();
        const url = storeAppUrl.value.trim();
        if (!label || !pkg || !url) {
            alert("Por favor, completa todos los campos.");
            return;
        }
        if (!/^[a-zA-Z0-9_.]+$/.test(pkg)) {
            alert("Nombre de paquete inválido.");
            return;
        }
        const key = pkg.replace(/\./g, "_");
        addStoreAppBtn.disabled = true;
        database.ref("storeApps/" + key).set({
            label: label,
            packageName: pkg,
            apkUrl: url
        }).then(() => {
            storeAppLabel.value = "";
            storeAppPackage.value = "";
            storeAppUrl.value = "";
        }).catch(err => {
            alert("Error al agregar: " + err.message);
        }).finally(() => {
            addStoreAppBtn.disabled = false;
        });
    });
}

function renderStoreApps(storeApps) {
    if (!storeAppsList) return;
    storeAppsList.innerHTML = "";
    const entries = Object.entries(storeApps);
    if (entries.length === 0) {
        storeAppsList.innerHTML = '<p class="loading-text" style="grid-column: 1/-1;">No hay aplicaciones en la tienda.</p>';
        return;
    }
    entries.forEach(([key, app]) => {
        const card = document.createElement("div");
        card.className = "group-card";
        card.style.padding = "16px";
        card.style.display = "flex";
        card.style.flexDirection = "column";
        card.style.justifyContent = "space-between";
        card.innerHTML = `
            <div>
                <h3 style="margin-top:0; margin-bottom:8px;">${app.label}</h3>
                <p style="font-size:12px; color:var(--text-gray); margin: 4px 0;"><strong>Paquete:</strong> ${app.packageName}</p>
                <p style="font-size:11px; color:var(--accent); word-break:break-all; margin: 4px 0;"><strong>URL:</strong> ${app.apkUrl}</p>
            </div>
            <button class="action-btn delete-btn" style="background:var(--alert-red); margin-top:12px; align-self:flex-start; font-size:12px; padding:6px 12px; color:white; border-radius:6px; font-weight:bold; border:none; cursor:pointer;">Eliminar</button>
        `;
        card.querySelector(".delete-btn").addEventListener("click", () => {
            if (confirm(`¿Seguro que querés quitar "${app.label}" de la tienda?`)) {
                database.ref("storeApps/" + key).remove();
            }
        });
        storeAppsList.appendChild(card);
    });
}

// Registrar Listeners en Firebase Database
if (auth) {
    auth.onAuthStateChanged(user => {
        if (user) {
            database.ref("globalSettings/allowedPackages").on("value", snap => {
                if (globalAllowedPackagesInput) {
                    const val = snap.val() || [];
                    globalAllowedPackagesInput.value = val.join(", ");
                }
            });

            database.ref("storeApps").on("value", snap => {
                renderStoreApps(snap.val() || {});
            });
        }
    });
}