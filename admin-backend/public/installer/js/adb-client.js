import { appState, restoreSessionState } from './state.js';
import { log, showToast, updateStatusBadge, notifyDeviceDisconnected, notifyDevicePluggedIn, hideDisconnectAlert } from './ui.js';
import { ADB_ERRORS, CONFIG } from './config.js';

// Helper to wait
export const wait = (ms) => new Promise(resolve => setTimeout(resolve, ms));

/**
 * Execute a promise with a timeout
 */
export function withTimeout(promise, timeoutMs = CONFIG.ADB_DEFAULT_TIMEOUT_MS || 15000, errorMsg = "פסק זמן לתקשורת עם המכשיר (Timeout)") {
    let timeoutId;
    const timeoutPromise = new Promise((_, reject) => {
        timeoutId = setTimeout(() => {
            reject(new Error(errorMsg));
        }, timeoutMs);
    });

    return Promise.race([
        promise,
        timeoutPromise
    ]).finally(() => {
        if (timeoutId) clearTimeout(timeoutId);
    });
}

let daemonTimer = null;
let isDaemonRunning = false;

/**
 * Initialize WebUSB connection event listeners and heartbeat daemon
 */
export function initUsbConnectionDaemon() {
    if (typeof navigator === 'undefined' || !('usb' in navigator)) {
        return;
    }

    if (isDaemonRunning) return;
    isDaemonRunning = true;

    // 1. Native WebUSB Disconnect Event Listener
    navigator.usb.addEventListener('disconnect', (event) => {
        log(`אירוע חומרה: זוהה ניתוק התקן USB (${event.device?.productName || 'USB Device'})`, 'warn');
        handleDeviceDisconnect("כבל ה-USB נותק מהמחשב");
    });

    // 2. Native WebUSB Connect Event Listener
    navigator.usb.addEventListener('connect', (event) => {
        log(`אירוע חומרה: זוהה חיבור התקן USB (${event.device?.productName || 'USB Device'})`, 'info');
        if (notifyDevicePluggedIn) {
            notifyDevicePluggedIn(event.device?.productName || 'מכשיר אנדרואיד');
        }
    });

    // 3. Periodic Heartbeat Daemon
    startHeartbeatDaemon();
}

/**
 * Start periodic non-intrusive heartbeat to verify device liveness
 */
function startHeartbeatDaemon() {
    if (daemonTimer) clearInterval(daemonTimer);

    const interval = CONFIG.ADB_HEARTBEAT_INTERVAL_MS || 4000;
    daemonTimer = setInterval(async () => {
        if (!appState.adbConnected || appState.isExecutingCommand || !appState.adbInstance) {
            return;
        }

        try {
            // Check underlying WebUSB device status if available
            if (appState.webUsbInstance?.device && !appState.webUsbInstance.device.opened) {
                handleDeviceDisconnect("החיבור עם התקן ה-USB נסגר");
                return;
            }
        } catch (e) {
            console.warn("Heartbeat error:", e);
            handleDeviceDisconnect("החיבור למכשיר אבד");
        }
    }, interval);
}

/**
 * Handle unexpected or detected device disconnection
 */
export function handleDeviceDisconnect(reason = "המכשיר נותק מהמחשב") {
    if (!appState.adbConnected && !appState.adbInstance) return;

    appState.adbConnected = false;
    appState.adbInstance = null;
    appState.webUsbInstance = null;
    appState.lastDisconnectReason = reason;

    log(`אזהרה: ${reason}. התקשורת הופסקה.`, 'warn');
    updateStatusBadge('adb-status', '<span class="material-symbols-rounded">link_off</span> מנותק', 'error');

    // Update ADB page buttons if visible
    const btnConnect = document.getElementById('btn-connect');
    if (btnConnect) {
        btnConnect.style.display = 'inline-flex';
        btnConnect.disabled = false;
    }
    const nextBtn = document.getElementById('btn-next-adb');
    if (nextBtn) {
        nextBtn.style.display = 'none';
        nextBtn.disabled = true;
    }

    // Call UI handler to show contextual banner / notification
    if (notifyDeviceDisconnected) {
        notifyDeviceDisconnected(reason);
    }
}

export async function connectAdb() {
    try {
        log("מאתר מכשיר אנדרואיד דרך WebUSB...", 'info');
        appState.webUsbInstance = await Adb.open("WebUSB");
        
        log("מבצע אימות מול שירות ADB במכשיר...", 'info');
        appState.adbInstance = await withTimeout(
            appState.webUsbInstance.connectAdb("host::"),
            25000,
            "המכשיר לא אישר את החיבור בזמן. ודאו שהמסך פתוח ואישרתם 'התר תמיד ממחשב זה'."
        );

        if (appState.adbInstance) {
            await checkDeviceIntegrity();

            let model = "Generic Android";
            try {
                let shell = await withTimeout(appState.adbInstance.shell("getprop ro.product.model"), 8000);
                let rawModel = await withTimeout(readAll(shell), 8000);
                rawModel = rawModel.replace('ro.product.model:', '').trim();
                if (rawModel) model = rawModel;
            } catch (e) {
                console.warn("Could not fetch device model name:", e);
            }

            appState.deviceModel = model;
            appState.adbConnected = true;
            appState.lastDisconnectReason = null;

            updateStatusBadge('adb-status', `<span class="material-symbols-rounded">link</span> מחובר: ${model}`, 'success');

            document.getElementById('btn-connect').style.display = 'none';
            const nextBtn = document.getElementById('btn-next-adb');
            if (nextBtn) {
                nextBtn.style.display = 'inline-flex';
                nextBtn.disabled = false;
            }

            if (hideDisconnectAlert) hideDisconnectAlert();
            showToast(`מחובר בהצלחה: ${model}`);
            log(`המכשיר חובר בהצלחה (${model})`, 'success');

            const restoredCount = restoreSessionState();
            if (restoredCount > 0) {
                const box = document.getElementById('emergency-restore-box');
                if (box) box.style.display = 'block';
                log(`אזהרה: זוהו ${restoredCount} רכיבים שהושבתו בהפעלה קודמת. ניתן לשחזרם באמצעות כפתור השחזור.`, 'warn');
            }
        }
    } catch (e) {
        const errorMsg = parseDisconnectOrRejectionReason(e.message || String(e));
        showToast("שגיאה בחיבור: " + errorMsg);
        log(`שגיאה בחיבור למכשיר: ${errorMsg}`, 'error');
        console.error("Connect ADB error:", e);
        updateStatusBadge('adb-status', '<span class="material-symbols-rounded">link_off</span> לא מחובר', 'error');
    }
}

/**
 * Classify and format disconnection or rejection messages into clear Hebrew guidance
 */
export function parseDisconnectOrRejectionReason(rawError) {
    const err = (rawError || '').toLowerCase();

    if (err.includes('unauthorized') || err.includes('not authorized') || err.includes('rsa')) {
        return "המכשיר ממתין לאישור. הדליקו את מסך המכשיר, סמנו 'אפשר תמיד ממחשב זה' ואשרו.";
    }
    if (err.includes('offline')) {
        return "המכשיר במצב לא מקוון (Offline). נתקו וחברו מחדש את כבל ה-USB.";
    }
    if (err.includes('not found') || err.includes('no device') || err.includes('unable to claim') || err.includes('lost') || err.includes('disconnected')) {
        return "המכשיר נותק או שלא זוהה. בדקו את תקינות כבל ה-USB.";
    }
    if (err.includes('securityexception') || err.includes('permission denied')) {
        return "אין הרשאה לביצוע הפעולה (אבטחת Android חסמה את הפקודה).";
    }
    if (err.includes('timeout') || err.includes('פסק זמן')) {
        return "פסק זמן בתקשורת עם המכשיר. ודאו שהמסך אינו נעול.";
    }
    return rawError;
}

/**
 * Dynamically parse dumpsys device_policy output to extract Device Owner, Profile Owners, and active admins
 */
export function parseDevicePolicyDump(dumpsysOutput, targetPackage = CONFIG.TARGET_PACKAGE) {
    const result = {
        hasDeviceOwner: false,
        deviceOwnerComponent: null,
        deviceOwnerPackage: null,
        isLockSuiteDeviceOwner: false,
        isOtherMdmActive: false,
        activeMdmPackage: null,
        activeMdmComponent: null,
        activeAdmins: []
    };

    if (!dumpsysOutput || typeof dumpsysOutput !== 'string') {
        return result;
    }

    // 1. Dynamic Device Owner Detection across multiple Android dumpsys formats
    const doSectionMatch = dumpsysOutput.match(/Device Owner(?:\s*\([^)]*\))?:[\s\S]*?(?:(?:\r?\n\s*\r?\n)|(?:Profile Owner)|(?:Active Admins)|$)/i);
    const doText = doSectionMatch ? doSectionMatch[0] : dumpsysOutput;

    let compMatch = doText.match(/admin=ComponentInfo\{([^}]+)\}/i) 
        || doText.match(/ComponentInfo\{([^}]+)\}/i)
        || dumpsysOutput.match(/Device Owner[\s\S]*?admin=ComponentInfo\{([^}]+)\}/i)
        || dumpsysOutput.match(/Device Owner[\s\S]*?ComponentInfo\{([^}]+)\}/i);

    let pkgMatch = doText.match(/package=([^\s\r\n]+)/i);

    if (compMatch && compMatch[1]) {
        const fullComp = compMatch[1].trim();
        const pkg = fullComp.includes('/') ? fullComp.split('/')[0].trim() : fullComp;
        result.hasDeviceOwner = true;
        result.deviceOwnerComponent = fullComp;
        result.deviceOwnerPackage = pkg;
    } else if (pkgMatch && pkgMatch[1]) {
        const pkg = pkgMatch[1].trim();
        result.hasDeviceOwner = true;
        result.deviceOwnerPackage = pkg;
        result.deviceOwnerComponent = pkg;
    }

    // 2. Classify Device Owner
    if (result.hasDeviceOwner) {
        if (result.deviceOwnerPackage === targetPackage) {
            result.isLockSuiteDeviceOwner = true;
        } else {
            result.isOtherMdmActive = true;
            result.activeMdmPackage = result.deviceOwnerPackage;
            result.activeMdmComponent = result.deviceOwnerComponent;
        }
    }

    // 3. Dynamic Active Admins extraction
    const adminMatches = dumpsysOutput.matchAll(/ComponentInfo\{([^}]+)\}/gi);
    for (const m of adminMatches) {
        if (m[1]) {
            const comp = m[1].trim();
            const pkg = comp.includes('/') ? comp.split('/')[0].trim() : comp;
            if (!result.activeAdmins.some(a => a.component === comp)) {
                result.activeAdmins.push({ component: comp, package: pkg });
            }
        }
    }

    return result;
}

/**
 * Perform a dynamic check of MDM and LockSuite package status on the connected device
 */
export async function checkDeviceMdmAndPackageStatus(targetPackage = CONFIG.TARGET_PACKAGE) {
    const status = {
        isLockSuiteInstalled: false,
        isLockSuiteDeviceOwner: false,
        isOtherMdmActive: false,
        activeMdmPackage: null,
        activeMdmComponent: null,
        hasDeviceOwner: false,
        deviceOwnerPackage: null,
        deviceOwnerComponent: null,
        activeAdmins: []
    };

    try {
        // 1. Check dumpsys device_policy
        const policyDump = await executeAdbCommand("dumpsys device_policy", "בדיקת מנהל מכשיר", true);
        const policy = parseDevicePolicyDump(policyDump, targetPackage);

        status.hasDeviceOwner = policy.hasDeviceOwner;
        status.deviceOwnerPackage = policy.deviceOwnerPackage;
        status.deviceOwnerComponent = policy.deviceOwnerComponent;
        status.isLockSuiteDeviceOwner = policy.isLockSuiteDeviceOwner;
        status.isOtherMdmActive = policy.isOtherMdmActive;
        status.activeMdmPackage = policy.activeMdmPackage;
        status.activeMdmComponent = policy.activeMdmComponent;
        status.activeAdmins = policy.activeAdmins;

        // 2. Check if LockSuite package is installed
        try {
            const pkgList = await executeAdbCommand("pm list packages", "בדיקת התקנת LockSuite", true);
            const isInstalled = pkgList.split(/\r?\n/).some(line => {
                const trimmed = line.trim();
                return trimmed === `package:${targetPackage}` || trimmed === targetPackage;
            });
            status.isLockSuiteInstalled = isInstalled;
        } catch (pkgErr) {
            console.warn("Package list check error:", pkgErr);
        }

        // Sync with appState
        appState.isLockSuiteInstalled = status.isLockSuiteInstalled;
        appState.isLockSuiteDeviceOwner = status.isLockSuiteDeviceOwner;
        appState.isOtherMdmActive = status.isOtherMdmActive;
        appState.activeMdmPackage = status.activeMdmPackage;
        appState.activeMdmComponent = status.activeMdmComponent;

    } catch (e) {
        console.warn("Device MDM & Package check failed:", e);
    }

    return status;
}

export async function checkDeviceIntegrity() {
    log("מבצע בדיקות תקינות מקדימות...", 'info');
    try {
        const sdkOut = await executeAdbCommand("getprop ro.build.version.sdk", "בדיקת גרסת אנדרואיד", true);
        const parsedSdk = parseInt(sdkOut.trim(), 10);
        
        if (!isNaN(parsedSdk) && parsedSdk > 0) {
            appState.sdkVersion = parsedSdk;
        } else {
            // Fallback check release version
            try {
                const relOut = await executeAdbCommand("getprop ro.build.version.release", "בדיקת גרסת שחרור", true);
                const relNum = parseInt(relOut.trim(), 10);
                if (relNum >= 14) appState.sdkVersion = 34;
                else if (relNum === 13) appState.sdkVersion = 33;
                else if (relNum === 12) appState.sdkVersion = 31;
                else if (relNum === 11) appState.sdkVersion = 30;
                else appState.sdkVersion = 29;
            } catch (fallbackErr) {
                appState.sdkVersion = 30; // Safe default
            }
        }

        if (appState.sdkVersion >= 34) { 
            log(`זוהה Android 14+ (SDK ${appState.sdkVersion}). השבתת חשבונות אוטומטית חסומה לפי מדיניות Google.`, 'warn');
        } else {
            log(`גרסת מערכת זוהתה: SDK ${appState.sdkVersion}`, 'info');
        }
        
        // Root check
        const cmd = "test -e /system/bin/su && echo ROOT_FOUND || test -e /system/xbin/su && echo ROOT_FOUND || test -e /sbin/su && echo ROOT_FOUND";
        const rootOut = await executeAdbCommand(cmd, "Root Check", true);
        if (rootOut.includes("ROOT_FOUND")) {
            log("אזהרה קריטית: זוהה מכשיר עם ROOT (הרשאות שורש).", 'warn');
            showToast("אזהרה: המכשיר מזוהה כבעל Root");
        }

        // Dynamic MDM & LockSuite Installation Precheck
        const mdmStatus = await checkDeviceMdmAndPackageStatus();
        if (mdmStatus.isOtherMdmActive) {
            const mdmName = mdmStatus.activeMdmPackage || "לא מזוהה";
            log(`אזהרה: זוהה מנהל מכשיר (MDM) אחר פעיל במכשיר: ${mdmName}. נדרש איפוס יצרן.`, 'warn');
            showToast(`אזהרה: קיים מנהל מכשיר (${mdmName})`);
        } else if (mdmStatus.isLockSuiteDeviceOwner) {
            log("LockSuite כבר מותקן ומוגדר כמנהל המכשיר במכשיר זה.", 'success');
        } else if (mdmStatus.isLockSuiteInstalled) {
            log("LockSuite מותקן במכשיר (טרם הוגדר כמנהל מכשיר).", 'info');
        }
    } catch (e) {
        console.warn("Device integrity check warning:", e);
    }
}

let commandQueue = Promise.resolve();

/**
 * Execute ADB command with timeout, rejection detection, and disconnect tracking.
 * All commands are sequenced to prevent WebUSB endpoint collision and packet corruption.
 */
export async function executeAdbCommand(command, description, silent = false, timeoutMs = CONFIG.ADB_DEFAULT_TIMEOUT_MS || 15000) {
    if (!appState.adbInstance) {
        throw new Error("ADB לא מחובר. יש לחבר את המכשיר מחדש.");
    }

    const execute = async () => {
        if (!appState.adbInstance) {
            throw new Error("ADB לא מחובר. יש לחבר את המכשיר מחדש.");
        }

        if (!silent) {
            log(`$ adb shell ${command}`, 'cmd');
        }

        appState.isExecutingCommand = true;

        try {
            const shellPromise = (async () => {
                let shell;
                let openAttempts = 0;
                const maxOpenAttempts = 3;

                while (openAttempts < maxOpenAttempts) {
                    openAttempts++;
                    try {
                        shell = await appState.adbInstance.shell(command);
                        break;
                    } catch (openErr) {
                        const isTransientOpenFailure = (openErr.message || '').includes('Open failed');
                        if (isTransientOpenFailure && openAttempts < maxOpenAttempts) {
                            await wait(350);
                            continue;
                        }
                        throw openErr;
                    }
                }

                return await readAll(shell);
            })();

            const response = await withTimeout(
                shellPromise,
                timeoutMs,
                `פסק זמן בביצוע הפקודה: ${description || command} (המכשיר לא השיב תוך ${timeoutMs / 1000} שניות)`
            );

            const trimmed = response.trim();
            const lowerRes = response.toLowerCase();

            if (!silent && trimmed) {
                log(trimmed, 'stdout');
            }

            // 1. Check for specific known ADB error signatures
            for (const [key, hebrewMsg] of Object.entries(ADB_ERRORS)) {
                if (response.includes(key)) {
                    // If the error signature indicates disconnect
                    if (key === "not found" || key === "device not found" || key === "closed" || key === "device offline") {
                        handleDeviceDisconnect(hebrewMsg);
                    }
                    throw new Error(`${hebrewMsg} (${key})`);
                }
            }

            // 2. Check for general failure indicators
            if (lowerRes.startsWith("error:") || lowerRes.includes("failure [") || lowerRes.includes("exception occurred while executing")) {
                throw new Error("נכשלה הפעולה: " + trimmed);
            }

            if (!silent) log(`הושלם בהצלחה: ${description}`, 'success');
            return response;

        } catch (e) {
            const rawMsg = e.message || String(e);
            const lowerMsg = rawMsg.toLowerCase();

            // Check if error is due to physical/pipe disconnection
            if (lowerMsg.includes('claim') || lowerMsg.includes('networkerror') || lowerMsg.includes('transfer') || lowerMsg.includes('lost') || lowerMsg.includes('not found') || lowerMsg.includes('closed')) {
                handleDeviceDisconnect("התקשורת עם המכשיר נותקה");
            }

            if (!silent) log(`שגיאה: ${rawMsg}`, 'error');
            throw e;
        } finally {
            appState.isExecutingCommand = false;
        }
    };

    const task = commandQueue.then(execute, execute);
    commandQueue = task.catch(() => {});
    return task;
}

// Stream Reader with chunk timeout support and clean teardown
export async function readAll(stream, chunkTimeoutMs = 12000) {
    if (!stream) return "";
    const decoder = new TextDecoder();
    let res = "";
    try {
        while (true) {
            let msgPromise = stream.receive();
            let msg = await withTimeout(msgPromise, chunkTimeoutMs, "פסק זמן בקריאת נתונים מהמכשיר");
            
            if (msg.cmd === "WRTE") {
                res += decoder.decode(msg.data);
                await stream.send("OKAY");
            } else if (msg.cmd === "CLSE") {
                if (stream.close) {
                    await stream.close().catch(() => {});
                }
                break;
            }
        }
    } catch (e) {
        console.warn("Stream reading interrupted or completed:", e);
        if (stream.close) {
            await stream.close().catch(() => {});
        }
    }
    return res.trim();
}
