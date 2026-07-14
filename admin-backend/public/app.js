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
    sidebarTabButtons = document.querySelectorAll(".sidebar-tabs .tab-btn"),
    sidebarTabPanels = document.querySelectorAll(".tab-panel"),
    deviceNameInput = document.getElementById("device-name-input"),
    saveNameBtn = document.getElementById("save-name-btn"),
    sidebarAppsList = document.getElementById("sidebar-apps-list"),
    appSearchInput = document.getElementById("app-search-input"),
    sidebarAllowlistInput = document.getElementById("sidebar-allowlist-input"),
    sidebarAllowlistBtn = document.getElementById("sidebar-allowlist-btn"),
    sidebarLockBtn = document.getElementById("sidebar-lock-btn"),
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
    sidebarChangePinBtn = document.getElementById("sidebar-change-pin-btn");
let selectedDeviceId = null,
    currentDevicesData = {},
    activeTabId = "panel-policies",
    devicesListener = null;

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

function startRealtimeSync() {
    devicesListener && database.ref("devices").off("value", devicesListener), devicesListener = database.ref("devices").on("value", e => {
        const t = e.val() || {};
        currentDevicesData = t, renderDevicesList(t), selectedDeviceId && t[selectedDeviceId] && updateSidebarUI(selectedDeviceId, t[selectedDeviceId])
    }, e => {
        globalError.textContent = "Error de sincronización en tiempo real: " + e.message
    })
}

function stopRealtimeSync() {
    devicesListener && (database.ref("devices").off("value", devicesListener), devicesListener = null)
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

function openDeviceSidebar(e, t) {
    selectedDeviceId = e, sidebar.classList.remove("hidden"), updateSidebarUI(e, t)
}

function closeDeviceSidebar() {
    selectedDeviceId = null, sidebar.classList.add("hidden")
}

function updateSidebarUI(e, t) {
    const n = field(t, "deviceName", ""),
        a = field(t, "model", "Celular");
    sidebarDeviceName.textContent = n || a || "Celular sin nombre", sidebarDeviceId.textContent = e, document.activeElement !== deviceNameInput && (deviceNameInput.value = n);
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
            advancedPanel.style.alignItems = "center";
            advancedPanel.style.justifyContent = "space-between";

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

            gearBtn.addEventListener("click", (evt) => {
                evt.stopPropagation();
                advancedPanel.classList.toggle("hidden");
            });

            advancedPanel.appendChild(label);
            advancedPanel.appendChild(select);
            t.appendChild(advancedPanel);
        }
        sidebarAppsList.appendChild(t);
    }) : sidebarAppsList.innerHTML = '<p class="loading-text">Ninguna aplicación coincide con la búsqueda.</p>';
}
async function runCommandOnDevice(e, t, n = null, a = null, i = null, extraParams = null) {
    a && (a.disabled = !0), sidebarStatusMsg.textContent = "Enviando comando al celular...";
    let d = null,
        s = !1,
        o = "";
    const c = currentDevicesData[e],
        r = c && (c.model || c.info && c.info.model) || "Celular";
    for (;;) try {
        // Obtener Firebase ID token del administrador actualmente logueado
        const idToken = await auth.currentUser.getIdToken();
        const payload = {
            deviceId: e,
            command: t,
            packages: n,
            devicePin: d,
            rememberDevice: s
        };
        if (extraParams) {
            Object.assign(payload, extraParams);
        }
        
        const response = await fetch("https://sendcommandv4-687828714595.us-central1.run.app", {
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
            sidebarStatusMsg.textContent = "Comando enviado. Esperando respuesta del celular...";
            const ackRef = database.ref(`devices/${e}/commandAcks/${commandId}`);
            const timeoutId = setTimeout(() => {
                ackRef.off();
                sidebarStatusMsg.textContent = "✓ Comando enviado (sin confirmación del celular)";
                if (a) a.disabled = false;
            }, 10000);
            
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
                        sidebarStatusMsg.textContent = "✗ El comando falló en el celular";
                        if (a) a.disabled = false;
                    }
                }
            });
        } else {
            sidebarStatusMsg.textContent = "✓ Comando enviado";
            setTimeout(() => {
                "✓ Comando enviado" === sidebarStatusMsg.textContent && (sidebarStatusMsg.textContent = "")
            }, 4000);
        }
        break
    } catch (e) {
        if ("PIN_REQUIRED" === e.message || "PIN_INCORRECT" === e.message) {
            o = "PIN_INCORRECT" === e.message ? "PIN incorrecto. Intentá de nuevo." : "";
            const t = await showPinModal(r, o);
            if (!t) {
                sidebarStatusMsg.textContent = "Cancelado — PIN requerido.", i && i();
                break
            }
            d = t.pin, s = t.remember;
            continue
        }
        sidebarStatusMsg.textContent = "✗ Error: " + (e.message || "desconocido"), i && i();
        break
    }
    a && (a.disabled = !1)
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
            aiModeEnabled: ["ENABLE_AI_MODE", "DISABLE_AI_MODE"],
            mapsImageBlockingEnabled: ["ENABLE_MAPS_IMAGE_BLOCKING", "DISABLE_MAPS_IMAGE_BLOCKING"],
            whatsappBlockStatus: ["BLOCK_WHATSAPP_STATUS", "UNBLOCK_WHATSAPP_STATUS"],
            whatsappBlockChannels: ["BLOCK_WHATSAPP_CHANNELS", "UNBLOCK_WHATSAPP_CHANNELS"],
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
}), sidebarAllowlistBtn.addEventListener("click", () => {
    const e = sidebarAllowlistInput.value.split(",").map(e => e.trim()).filter(Boolean);
    if (e.length === 0) {
        if (!confirm("¿Seguro que querés vaciar la lista de aplicaciones permitidas? Esto bloqueará el acceso a todas las aplicaciones no esenciales.")) {
            return;
        }
    }
    sidebarAllowlistBtn.disabled = !0, runCommandOnDevice(selectedDeviceId, "UPDATE_ALLOWLIST", e, sidebarAllowlistBtn);
}), sidebarLockBtn.addEventListener("click", () => {
    sidebarLockBtn.disabled = !0, runCommandOnDevice(selectedDeviceId, "LOCK_DEVICE", null, sidebarLockBtn)
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