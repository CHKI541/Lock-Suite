// js/state.js
export const appState = {
    adbConnected: false,
    accountsClean: false,
    apkDownloaded: false,
    disabledPackages: [],
    adbInstance: null,
    webUsbInstance: null,
    sdkVersion: 0, // Track Android SDK Version
    deviceModel: "", // Track connected device model name
    isExecutingCommand: false, // Prevent concurrent heartbeat while running intensive operations
    lastDisconnectReason: null,
    // MDM & LockSuite Precheck State
    isLockSuiteInstalled: false,
    isLockSuiteDeviceOwner: false,
    isOtherMdmActive: false,
    activeMdmPackage: null,
    activeMdmComponent: null
};

export function saveSessionState() {
    try {
        if (appState.disabledPackages.length > 0) {
            localStorage.setItem('mdm_disabled_packages', JSON.stringify(appState.disabledPackages));
        } else {
            localStorage.removeItem('mdm_disabled_packages');
        }
    } catch (e) {
        console.error("Failed to save session state", e);
    }
}

export function restoreSessionState() {
    try {
        const saved = localStorage.getItem('mdm_disabled_packages');
        if (saved) {
            const parsed = JSON.parse(saved);
            if (Array.isArray(parsed) && parsed.length > 0) {
                appState.disabledPackages = parsed;
                return parsed.length; // Return count of disabled packages
            }
        }
    } catch (e) {
        console.error("Failed to restore session state", e);
    }
    return 0;
}

export function clearSessionState() {
    appState.disabledPackages = [];
    appState.isLockSuiteInstalled = false;
    appState.isLockSuiteDeviceOwner = false;
    appState.isOtherMdmActive = false;
    appState.activeMdmPackage = null;
    appState.activeMdmComponent = null;
    try {
        localStorage.removeItem('mdm_disabled_packages');
    } catch (e) {
        console.error("Failed to clear session state", e);
    }
}