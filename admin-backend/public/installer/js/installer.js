// js/installer.js
import { appState } from './state.js';
import { CONFIG } from './config.js';
import { executeAdbCommand, wait, checkDeviceMdmAndPackageStatus } from './adb-client.js';
import { log, showToast, updateProgress, navigateTo, resetMilestones, updateMilestone, setInstallHeroState, clearConsoleLog, setPhonePanelState } from './ui.js';
import { restoreAccounts } from './accounts.js';

let apkBlob = null;
let foundRelease = null;

/**
 * Format bytes into human readable format (MB/KB)
 */
function formatBytes(bytes) {
    if (!bytes || isNaN(bytes)) return '0 B';
    const mb = bytes / (1024 * 1024);
    if (mb >= 1) return `${mb.toFixed(1)} MB`;
    const kb = bytes / 1024;
    return `${kb.toFixed(0)} KB`;
}

/**
 * Fetch with configurable timeout (prevents hanging on ISP filter inspection)
 */
async function fetchWithTimeout(url, options = {}, timeoutMs = CONFIG.APK_FETCH_TIMEOUT_MS || 8000) {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
    try {
        const resp = await fetch(url, { ...options, signal: controller.signal });
        return resp;
    } catch (err) {
        if (err.name === 'AbortError') {
            throw new Error(`פסק זמן בשרת (Timeout לאחר ${timeoutMs / 1000} שניות)`);
        }
        throw err;
    } finally {
        clearTimeout(timeoutId);
    }
}

/**
 * Download a stream with progress reporting and stream fault protection
 */
async function downloadStreamWithProgress(resp, onProgress) {
    if (!resp.body || !resp.body.getReader) {
        const blob = await resp.blob();
        if (onProgress) onProgress(blob.size, blob.size);
        return blob;
    }

    const reader = resp.body.getReader();
    const lenHeader = resp.headers.get('Content-Length');
    const totalBytes = lenHeader ? parseInt(lenHeader, 10) : 0;
    let receivedBytes = 0;
    const chunks = [];

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            chunks.push(value);
            receivedBytes += value.length;
            if (onProgress) {
                onProgress(receivedBytes, totalBytes);
            }
        }
    } catch (streamErr) {
        throw new Error(`שגיאת הזרמת נתונים (Stream Error): ${streamErr.message}`);
    }

    return new Blob(chunks, { type: 'application/vnd.android.package-archive' });
}

/**
 * Resolve local APK path relative to current document base
 */
function getLocalApkUrl() {
    let base = window.location.href.split('?')[0].split('#')[0];
    if (!base.endsWith('/')) {
        base = base.substring(0, base.lastIndexOf('/') + 1);
    }
    return new URL(CONFIG.APK_LOCAL_PATH, base).href;
}

/**
 * Multi-tier resilient APK fetching with vast CDN fallback layer
 */
export async function fetchApkBlobWithFallbacks(onProgress) {
    if (apkBlob) return apkBlob;

    const sources = [];

    // 1. Primary: Local relative path
    const localUrl = getLocalApkUrl();
    sources.push({ name: "קובץ מקומי (Local Repo)", url: `${localUrl}?t=${Date.now()}` });

    // 2. Secondary: Configured Fallback URLs (GitHack, jsDelivr, Staticaly, GitHub raw, Releases)
    if (Array.isArray(CONFIG.APK_FALLBACK_URLS)) {
        CONFIG.APK_FALLBACK_URLS.forEach((u, i) => {
            let label = "גיבוי ענן";
            if (u.includes('rawcdn.githack.com')) label = "GitHack CDN (Cloudflare)";
            else if (u.includes('raw.githack.com')) label = "GitHack Dev CDN";
            else if (u.includes('fastly.jsdelivr.net')) label = "jsDelivr Fastly Edge";
            else if (u.includes('gcore.jsdelivr.net')) label = "jsDelivr GCore Edge";
            else if (u.includes('testingcf.jsdelivr.net')) label = "jsDelivr Cloudflare Edge";
            else if (u.includes('cdn.jsdelivr.net')) label = "jsDelivr Main CDN";
            else if (u.includes('staticaly.com')) label = "Staticaly CDN";
            else if (u.includes('raw.githubusercontent')) label = "GitHub Raw Mirror";
            else if (u.includes('releases')) label = "שרת גיבוי ישיר (GitHub Releases)";
            sources.push({ name: `${label} (${i + 1})`, url: `${u}?t=${Date.now()}` });
        });
    }

    // Try sources sequentially
    for (const source of sources) {
        try {
            log(`טוען מ-${source.name}...`, 'info');
            const resp = await fetchWithTimeout(source.url);
            if (!resp.ok) {
                log(`טעינה מ-${source.name} נכשלה (קוד: ${resp.status})`, 'warn');
                continue;
            }

            // Verify content type is not HTML (which occurs on filter block pages or 404 pages)
            const ctype = (resp.headers.get('Content-Type') || '').toLowerCase();
            if (ctype.includes('text/html')) {
                log(`התקבלה תשובת HTML/חסימת תוכן מ-${source.name}`, 'warn');
                continue;
            }

            const blob = await downloadStreamWithProgress(resp, (received, total) => {
                if (onProgress) onProgress(received, total, source.name);
            });

            // Sanity check: valid APK should be at least 1MB
            if (blob && blob.size > 1000000) {
                apkBlob = blob;
                appState.apkDownloaded = true;
                log(`הקובץ נטען בהצלחה מ-${source.name} (${formatBytes(blob.size)})`, 'success');
                return apkBlob;
            } else {
                log(`הקובץ שהתקבל קטן מדי (${formatBytes(blob?.size || 0)})`, 'warn');
            }
        } catch (err) {
            log(`שגיאה בגישה ל-${source.name}: ${err.message}`, 'warn');
        }
    }

    // 3. Last Resort: Live lookup against GitHub Releases API
    const ghUsers = [CONFIG.INSTALLER_REPO_OWNER, CONFIG.GITHUB_USERNAME, CONFIG.FALLBACK_GITHUB_USERNAME].filter(Boolean);
    for (const user of ghUsers) {
        try {
            const repoName = user === CONFIG.INSTALLER_REPO_OWNER ? CONFIG.INSTALLER_REPO_NAME : CONFIG.GITHUB_REPO_NAME;
            log(`מחפש גרסה עדכנית ב-GitHub Releases (${user}/${repoName})...`, 'info');
            const apiResp = await fetchWithTimeout(`https://api.github.com/repos/${user}/${repoName}/releases/latest`);
            if (!apiResp.ok) continue;

            const data = await apiResp.json();
            const asset = data.assets && data.assets.find(a => a.name.endsWith('.apk'));
            if (asset) {
                const dlUrl = asset.browser_download_url || asset.url;
                log(`מוריד גרסה רשמית ${data.tag_name}...`, 'info');
                const resp = await fetchWithTimeout(dlUrl);
                if (resp.ok) {
                    const blob = await downloadStreamWithProgress(resp, (received, total) => {
                        if (onProgress) onProgress(received, total, `GitHub Release ${data.tag_name}`);
                    });
                    if (blob && blob.size > 1000000) {
                        apkBlob = blob;
                        appState.apkDownloaded = true;
                        log(`הורד בהצלחה מ-GitHub (${formatBytes(blob.size)})`, 'success');
                        return apkBlob;
                    }
                }
            }
        } catch (ghErr) {
            log(`פנייה ל-GitHub API (${user}) נכשלה: ${ghErr.message}`, 'warn');
        }
    }

    throw new Error("לא ניתן היה להוריד את קובץ ה-APK מאף מקור (חסימת רשת/נטפרי). באפשרותך לבחור את הקובץ ידנית למטה.");
}

/**
 * Handle manual APK file selection from local filesystem
 */
export function setManualApkFile(file) {
    if (!file) return;
    if (!file.name.toLowerCase().endsWith('.apk')) {
        showToast("יש לבחור קובץ APK בלבד");
        return;
    }

    apkBlob = file;
    appState.apkDownloaded = true;
    log(`נטען קובץ מקומי שנבחר: ${file.name} (${formatBytes(file.size)})`, 'success');
    showToast(`נטען: ${file.name}`);

    const fallbackBox = document.getElementById('manual-apk-box');
    if (fallbackBox) fallbackBox.style.display = 'none';

    // Enable install button if on install page
    const btn = document.getElementById('btn-install-start');
    if (btn) btn.disabled = false;
}

export async function checkForUpdates() {
    const infoText = document.getElementById('update-info-text');
    const btn = document.getElementById('btn-download');

    try {
        const ghUser = CONFIG.GITHUB_USERNAME || CONFIG.FALLBACK_GITHUB_USERNAME;
        const resp = await fetch(`https://api.github.com/repos/${ghUser}/${CONFIG.GITHUB_REPO_NAME}/releases/latest`);
        if (!resp.ok) throw new Error("GitHub Error");
        const data = await resp.json();
        const asset = data.assets && data.assets.find(a => a.name.endsWith('.apk'));

        if (asset) {
            foundRelease = asset;
            infoText.innerHTML = `גרסה חדשה: <b>${data.tag_name}</b>`;
            btn.disabled = false;
        } else { throw new Error("No APK"); }
    } catch (e) {
        infoText.innerText = "משתמש בגרסה מובנית.";
        console.error(e);
    }
}

export async function startDownload() {
    if (!foundRelease) return;
    const btn = document.getElementById('btn-download');
    const bar = document.getElementById('dl-progress-bar');
    const statusText = document.getElementById('dl-status-text');

    btn.disabled = true;
    document.getElementById('dl-progress-wrapper').style.display = 'block';

    try {
        const downloadUrl = foundRelease.browser_download_url || foundRelease.url;
        const resp = await fetch(downloadUrl);
        if (!resp.ok) throw new Error("Download failed");

        apkBlob = await downloadStreamWithProgress(resp, (received, total) => {
            if (total > 0) {
                const percent = Math.round((received / total) * 100);
                if (bar) bar.style.width = percent + "%";
                if (statusText) statusText.innerText = `מוריד... ${formatBytes(received)} / ${formatBytes(total)} (${percent}%)`;
            } else {
                if (statusText) statusText.innerText = `מוריד... ${formatBytes(received)}`;
            }
        });

        appState.apkDownloaded = true;
        if (statusText) statusText.innerText = "ההורדה הושלמה!";
        setTimeout(() => navigateTo('page-install', 4), 800);
    } catch (e) {
        showToast("שגיאה בהורדה");
        console.error(e);
        btn.disabled = false;
        if (statusText) statusText.innerText = "שגיאה בהורדה, מנסה מקור חלופי בהמשך.";
    }
}

/**
 * Clean up incomplete installation artifacts and restore state
 */
async function rollbackInstallation(isApkInstalled = false) {
    log("מבצע שחזור וביטול שינויים (Rollback)...", 'warn');

    // 1. Uninstall partially installed APK if owner couldn't be granted
    if (isApkInstalled) {
        try {
            log(`מסיר את חבילת הניהול (${CONFIG.TARGET_PACKAGE}) כדי למנוע השארת אפליקציה שבורה...`, 'info');
            await executeAdbCommand(`pm uninstall ${CONFIG.TARGET_PACKAGE}`, "Uninstall incomplete MDM", true);
        } catch (e) {
            console.warn("Uninstall during rollback skipped/failed", e);
        }
    }

    // 2. Remove pushed temp APK file
    try {
        await executeAdbCommand("rm -f /data/local/tmp/app.apk", "Cleanup Temp APK", true);
    } catch (e) {
        console.warn("Temp cleanup skipped/failed", e);
    }

    // 3. Restore all disabled packages
    if (appState.disabledPackages.length > 0) {
        await restoreAccounts();
    }
}

export function resetApkBlob() {
    apkBlob = null;
    foundRelease = null;
}

/**
 * Display structured failure information and recovery actions in the UI
 */
function showInstallationFailureUI(errorMessage) {
    const errorBox = document.getElementById('install-error-box');
    const errorTitle = document.getElementById('install-error-title');
    const errorDesc = document.getElementById('install-error-desc');
    const btnBackAcc = document.getElementById('btn-err-back-accounts');

    if (!errorBox) return;

    errorBox.style.display = 'block';

    const lowerMsg = (errorMessage || '').toLowerCase();

    if (lowerMsg.includes('there are already some accounts') || lowerMsg.includes('חשבונות פעילים') || lowerMsg.includes('accounts:')) {
        if (errorTitle) errorTitle.innerText = "נמצאו חשבונות פעילים במכשיר";
        if (errorDesc) {
            errorDesc.innerHTML = `הגדרת הניהול נכשלה כי קיימים במכשיר חשבונות פעילים (כגון גוגל, סמסונג, וואטסאפ או רשתות חברתיות).<br><strong>יש להסיר את כל החשבונות בהגדרות המכשיר ולאחר מכן לנסות שוב.</strong>`;
        }
        if (btnBackAcc) btnBackAcc.style.display = 'inline-flex';
    } else if (lowerMsg.includes('already a device owner') || lowerMsg.includes('קיים מנהל מכשיר') || lowerMsg.includes('trying to set the device owner') || lowerMsg.includes('איפוס יצרן')) {
        if (errorTitle) errorTitle.innerText = "קיים כבר מנהל מכשיר במערכת";
        if (errorDesc) {
            const mdmName = appState.activeMdmPackage ? ` (<b>${appState.activeMdmPackage}</b>)` : '';
            errorDesc.innerHTML = `המכשיר כבר מנוהל על ידי אפליקציית ניהול אחרת${mdmName}.<br>Android חוסמת הגדרת מנהל נוסף. <strong>נדרש לבצע איפוס יצרן (Factory Reset) למכשיר על מנת להגדירו מחדש.</strong>`;
        }
        if (btnBackAcc) btnBackAcc.style.display = 'none';
    } else if (lowerMsg.includes('insufficient_storage') || lowerMsg.includes('מקום פנוי')) {
        if (errorTitle) errorTitle.innerText = "אין מספיק מקום פנוי במכשיר";
        if (errorDesc) {
            errorDesc.innerHTML = `זיכרון האחסון במכשיר מלא.<br><strong>יש לפנות שטח אחסון על ידי מחיקת קבצים או אפליקציות ולנסות שוב.</strong>`;
        }
        if (btnBackAcc) btnBackAcc.style.display = 'none';
    } else if (lowerMsg.includes('update_incompatible') || lowerMsg.includes('signatures')) {
        if (errorTitle) errorTitle.innerText = "גרסה קודמת חוסמת התקנה";
        if (errorDesc) {
            errorDesc.innerHTML = `קיימת במכשיר גרסה קודמת של LockSuite בעלת חתימה שונה.<br><strong>יש להסיר את האפליקציה הקודמת ידנית מהמכשיר ולנסות שוב.</strong>`;
        }
        if (btnBackAcc) btnBackAcc.style.display = 'none';
    } else if (lowerMsg.includes('unauthorized') || lowerMsg.includes('permission denied')) {
        if (errorTitle) errorTitle.innerText = "נדרש אישור במכשיר";
        if (errorDesc) {
            errorDesc.innerHTML = `המכשיר לא אישר את בקשת החיבור.<br><strong>אנא הביטו במסך המכשיר, סמנו "אפשר תמיד ממחשב זה" ואשרו.</strong>`;
        }
        if (btnBackAcc) btnBackAcc.style.display = 'none';
    } else if (lowerMsg.includes('נותק') || lowerMsg.includes('disconnect') || lowerMsg.includes('not found') || lowerMsg.includes('lost') || lowerMsg.includes('offline')) {
        if (errorTitle) errorTitle.innerText = "המכשיר נותק במהלך ההתקנה";
        if (errorDesc) {
            errorDesc.innerHTML = `חיבור ה-USB עם המכשיר הופסק.<br><strong>ודאו שכבל ה-USB מחובר היטב, חברו את המכשיר מחדש ולחצו 'נסה שוב'.</strong>`;
        }
        if (btnBackAcc) btnBackAcc.style.display = 'none';
    } else if (lowerMsg.includes('timeout') || lowerMsg.includes('פסק זמן')) {
        if (errorTitle) errorTitle.innerText = "פסק זמן בתקשורת עם המכשיר";
        if (errorDesc) {
            errorDesc.innerHTML = `המכשיר לא הגיב בזמן לפקודת ההתקנה.<br><strong>ודאו שהמכשיר דולק והמסך אינו נעול, ולחצו 'נסה שוב'.</strong>`;
        }
        if (btnBackAcc) btnBackAcc.style.display = 'none';
    } else if (lowerMsg.includes('unknown admin') || lowerMsg.includes('רכיב הניהול לא זוהה') || lowerMsg.includes('unknown_admin')) {
        if (errorTitle) errorTitle.innerText = "רכיב הניהול טרם זוהה במערכת";
        if (errorDesc) {
            errorDesc.innerHTML = `רכיב ניהול המכשיר (Device Admin) טרם נרשם במערכת Android או שגרסת המערכת חסמה את הפעלתו.<br><strong>אנא הפעילו מחדש (Restart) את המכשיר ונסו שוב.</strong>`;
        }
        if (btnBackAcc) btnBackAcc.style.display = 'none';
    } else {
        if (errorTitle) {
            errorTitle.textContent = "שגיאה במהלך ההתקנה";
            errorTitle.innerText = "שגיאה במהלך ההתקנה";
        }
        if (errorDesc) {
            errorDesc.textContent = errorMessage || "חלה תקלה במהלך תהליך ההתקנה וההגדרה.";
            errorDesc.innerHTML = errorMessage || "חלה תקלה במהלך תהליך ההתקנה וההגדרה.";
            errorDesc.innerText = errorMessage || "חלה תקלה במהלך תהליך ההתקנה וההגדרה.";
        }
        if (btnBackAcc) btnBackAcc.style.display = 'none';
    }
}

/**
 * Resiliently set device owner with component discovery, format variations, and registration retry
 */
export async function setDeviceOwnerWithRetry(pkg = CONFIG.TARGET_PACKAGE, admin = CONFIG.DEVICE_ADMIN) {
    log("מגדיר ניהול מכשיר...", 'info');
    
    // Normalize admin component names
    const shortAdmin = admin.startsWith('.') ? admin : `.${admin}`;
    const fullAdminClass = admin.startsWith('.') ? `${pkg}${admin}` : admin;
    
    const candidateCommands = [
        `dpm set-device-owner ${pkg}/${shortAdmin}`,
        `dpm set-device-owner --user 0 ${pkg}/${shortAdmin}`,
        `dpm set-device-owner ${pkg}/${fullAdminClass}`,
        `dpm set-device-owner --user 0 ${pkg}/${fullAdminClass}`,
        `dpm set-device-owner --user current ${pkg}/${shortAdmin}`
    ];

    const maxAttempts = 6;
    let lastError = null;

    // Stabilization pause to let PackageManager finish component indexing
    await wait(1500);

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        // Try candidate command formats
        for (const cmd of candidateCommands) {
            try {
                const res = await executeAdbCommand(cmd, "הגדרת מנהל מכשיר", true);
                if (res.toLowerCase().includes("success") || res.includes("set to package") || res.includes("device owner set")) {
                    log("מנהל המכשיר הוגדר בהצלחה!", 'success');
                    return true;
                }
            } catch (err) {
                lastError = err;
                const errLower = (err.message || '').toLowerCase();
                
                // If it failed because of active accounts or already device owner, abort immediately
                if (errLower.includes('already some accounts') || errLower.includes('already a device owner') || errLower.includes('trying to set')) {
                    throw err;
                }
                // If unknown admin, component might still be registering in background
                if (errLower.includes('unknown admin') || errLower.includes('illegalargumentexception')) {
                    continue;
                }
            }
        }

        if (attempt < maxAttempts) {
            log(`ממתין להשלמת רישום רכיב הניהול במערכת (ניסיון ${attempt}/${maxAttempts})...`, 'info');
            await wait(2000);
        }
    }

    throw lastError || new Error(`הגדרת מנהל המכשיר נכשלה: רכיב הניהול (${pkg}/${shortAdmin}) לא זוהה על ידי המערכת.`);
}

/**
 * Grant all runtime, system, and AppOp privileges required by the application
 * (Secure settings, Usage stats, Write settings, Notifications, Camera, Package installer, Battery whitelist, etc.)
 */
export async function grantAllAppPermissions(pkg = CONFIG.TARGET_PACKAGE) {
    log("מעניק הרשאות מערכת וגישה מורחבת...", 'info');

    const permissionTasks = [
        // 0. Accessibility Service & Restricted Settings (Crucial for LockSuite)
        { cmd: `appops set ${pkg} ACCESS_RESTRICTED_SETTINGS allow`, desc: "הרשאת גישה להגדרות מוגבלות (ACCESS_RESTRICTED_SETTINGS)" },
        { cmd: `settings put secure enabled_accessibility_services ${CONFIG.ACCESSIBILITY_SERVICE || `${pkg}/com.ejemplo.locksuite.service.LockSuiteAccessibilityService`}`, desc: "הפעלת שירות נגישות (enabled_accessibility_services)" },
        { cmd: `settings put secure accessibility_enabled 1`, desc: "הפעלת נגישות ראשית (accessibility_enabled = 1)" },

        // 1. System & Secure Settings
        { cmd: `pm grant ${pkg} android.permission.WRITE_SECURE_SETTINGS`, desc: "הרשאת הגדרות מאובטחות (WRITE_SECURE_SETTINGS)" },
        
        // 2. Usage Stats (Access to package usage statistics)
        { cmd: `pm grant ${pkg} android.permission.PACKAGE_USAGE_STATS`, desc: "הרשאת נתוני שימוש (PACKAGE_USAGE_STATS)" },
        { cmd: `appops set ${pkg} GET_USAGE_STATS allow`, desc: "הרשאת AppOp (GET_USAGE_STATS)" },
        { cmd: `appops set ${pkg} PACKAGE_USAGE_STATS allow`, desc: "הרשאת AppOp (PACKAGE_USAGE_STATS)" },

        // 3. Write System Settings
        { cmd: `appops set ${pkg} WRITE_SETTINGS allow`, desc: "הרשאת שינוי הגדרות מערכת (WRITE_SETTINGS)" },

        // 4. Notifications (Android 13+)
        { cmd: `pm grant ${pkg} android.permission.POST_NOTIFICATIONS`, desc: "הרשאת התראות (POST_NOTIFICATIONS)" },

        // 5. Camera (For QR scanning / validation)
        { cmd: `pm grant ${pkg} android.permission.CAMERA`, desc: "הרשאת מצלמה (CAMERA)" },

        // 6. Install Unknown Packages (Package management / updates)
        { cmd: `appops set ${pkg} REQUEST_INSTALL_PACKAGES allow`, desc: "הרשאת התקנת חבילות (REQUEST_INSTALL_PACKAGES)" },

        // 7. Overlay / Draw over other apps (Kiosk & Blocker UI)
        { cmd: `appops set ${pkg} SYSTEM_ALERT_WINDOW allow`, desc: "הרשאת תצוגה מעל יישומים (SYSTEM_ALERT_WINDOW)" },

        // 8. Battery Optimization Exemption (Whitelist for continuous background operation & VPN)
        { cmd: `dumpsys deviceidle whitelist +${pkg}`, desc: "החרגה מחסכון בסוללה (deviceidle whitelist)" },
        { cmd: `cmd deviceidle whitelist +${pkg}`, desc: "החרגה מחסכון בסוללה (cmd deviceidle whitelist)" },

        // 9. Storage permissions (Android 9 and below)
        { cmd: `pm grant ${pkg} android.permission.WRITE_EXTERNAL_STORAGE`, desc: "הרשאת כתיבה לאחסון (WRITE_EXTERNAL_STORAGE)" },
        { cmd: `pm grant ${pkg} android.permission.READ_EXTERNAL_STORAGE`, desc: "הרשאת קריאה מאחסון (READ_EXTERNAL_STORAGE)" }
    ];

    for (const task of permissionTasks) {
        try {
            await executeAdbCommand(task.cmd, task.desc, true);
        } catch (err) {
            // Non-fatal if unsupported on specific Android version or OEM ROM (e.g. POST_NOTIFICATIONS on Android < 13)
            console.warn(`Permission grant skipped/failed (${task.desc}):`, err.message || err);
        }
    }
}

export async function runInstallation() {
    if (!appState.adbConnected) return showToast("המכשיר אינו מחובר");
    const btn = document.getElementById('btn-install-start');
    if (btn) btn.disabled = true;

    const btnNewDevice = document.getElementById('btn-new-device');
    if (btnNewDevice) {
        btnNewDevice.style.display = 'none';
        btnNewDevice.disabled = true;
    }

    // Clean console and state on every fresh flash
    clearConsoleLog();
    resetMilestones();
    updateMilestone(1, 'active');
    setInstallHeroState('running', "מתחיל בדיקות מקדימות...", "בודק תקינות חיבור ומנהל מכשיר", 5);
    setPhonePanelState('installing', { title: "מתחיל בדיקות מקדימות...", desc: "בודק תקינות חיבור ומנהל מכשיר", progress: 0.05 });
    updateProgress(0.05);

    const manualBox = document.getElementById('manual-apk-box');
    if (manualBox) manualBox.style.display = 'none';

    const errorBox = document.getElementById('install-error-box');
    if (errorBox) errorBox.style.display = 'none';

    let isApkInstalled = false;
    let currentPhase = 1;

    try {
        // 1. Pre-checks: Dynamic check of device owner, foreign MDMs, and A-Bloq status
        currentPhase = 1;
        log("בודק מנהל מכשיר ותקינות מקדימה...", 'info');
        const mdmStatus = await checkDeviceMdmAndPackageStatus(CONFIG.TARGET_PACKAGE);

        if (mdmStatus.isOtherMdmActive) {
            const foreignPkg = mdmStatus.activeMdmPackage || "לא מזוהה";
            throw new Error(`קיים מנהל מכשיר (MDM) אחר על המכשיר (${foreignPkg}). מערכת Android אינה מאפשרת הגדרת מנהל נוסף ללא איפוס יצרן (Factory Reset).`);
        }

        if (mdmStatus.isLockSuiteDeviceOwner) {
            log("LockSuite כבר מותקן ומוגדר כמנהל המכשיר (Device Owner) בהצלחה!", 'success');
            // Mark pre-check done
            updateMilestone(1, 'done');
            updateProgress(0.50);

            // Skip redundant download/push/dpm set-device-owner to prevent duplicate owner crash
            log("מאמת ומעניק הרשאות מערכת...", 'info');
            updateMilestone(5, 'active');
            setInstallHeroState('running', "מאמת הרשאות מערכת...", "מוודא הרשאות ניהול מאובטחות", 70);
            updateProgress(0.70);
            await grantAllAppPermissions(CONFIG.TARGET_PACKAGE);
            updateMilestone(5, 'done');

            // Launch app
            updateMilestone(6, 'active');
            setInstallHeroState('running', "מפעיל את האפליקציה...", "פותח את LockSuite במכשיר", 90);
            updateProgress(0.90);
            log("מפעיל את האפליקציה...", 'info');
            await executeAdbCommand(`am start -n ${CONFIG.TARGET_PACKAGE}/.MainActivity`, "הפעלת אפליקציה");
            updateMilestone(6, 'done');

            // Complete intermediate milestones
            [2, 3, 4].forEach(i => updateMilestone(i, 'done'));

            updateProgress(1.0);
            setInstallHeroState('success', "LockSuite כבר מותקן ומוגדר כמנהל המכשיר!", "LockSuite פעיל ומוכן לשימוש.", 100);
            log("ההתקנה וההגדרה הושלמו בהצלחה!", 'success');
            showToast("LockSuite כבר מוגדר ופעיל!");

            if (btnNewDevice) {
                btnNewDevice.style.display = 'inline-flex';
                btnNewDevice.disabled = false;
            }
            setPhonePanelState('success');
            return;
        }

        if (mdmStatus.isLockSuiteInstalled) {
            log("LockSuite מותקן במכשיר. ממשיך בהגדרת ניהול המכשיר (Device Owner)...", 'info');
        }

        updateMilestone(1, 'done');

        // 2. Load APK (10% - 35%)
        currentPhase = 2;
        updateMilestone(2, 'active');
        setInstallHeroState('running', "מוריד את קובץ ההתקנה...", "טוען ומאמת את חבילת ההתקנה", 15);

        if (!apkBlob) {
            log("מתחיל בטעינת קובץ ההתקנה...", 'info');
            let lastReportedPercent = -1;
            await fetchApkBlobWithFallbacks((received, total, sourceName) => {
                if (total > 0) {
                    const ratio = received / total;
                    const overallProgress = 0.10 + ratio * 0.25;
                    updateProgress(overallProgress);
                    const percent = Math.round(ratio * 100);
                    setInstallHeroState('running', `מוריד קובץ מ-${sourceName}...`, `${formatBytes(received)} / ${formatBytes(total)} (${percent}%)`, overallProgress * 100);
                    if (percent % 25 === 0 && percent !== lastReportedPercent) {
                        lastReportedPercent = percent;
                        log(`מוריד: ${formatBytes(received)} / ${formatBytes(total)} (${percent}%)`, 'info');
                    }
                } else {
                    updateProgress(0.20);
                }
            });
        }

        updateMilestone(2, 'done');
        updateProgress(0.35);

        // 3. Push APK to Device (35% - 65%)
        currentPhase = 3;
        updateMilestone(3, 'active');
        setInstallHeroState('running', "מעביר את הקובץ למכשיר...", "מעביר את קובץ ההתקנה אל המכשיר", 35);
        log("מעביר קובץ התקנה למכשיר...", 'info');

        try {
            const sync = await appState.adbInstance.sync();
            const file = new File([apkBlob], "app.apk");
            await sync.push(file, "/data/local/tmp/app.apk", 0o644, (s, t) => {
                if (t > 0) {
                    const ratio = s / t;
                    const overallProgress = 0.35 + ratio * 0.30;
                    updateProgress(overallProgress);
                    setInstallHeroState('running', "מעביר קובץ למכשיר...", `${formatBytes(s)} / ${formatBytes(t)} (${Math.round(ratio * 100)}%)`, overallProgress * 100);
                }
            });
            await sync.quit();
        } catch (syncErr) {
            throw new Error(`שגיאה בהעברת הקובץ למכשיר: ${syncErr.message || 'העברת הנתונים נפסקה'}`);
        }

        updateMilestone(3, 'done');
        await wait(1000);

        // 4. Install APK (65% - 80%)
        currentPhase = 4;
        updateMilestone(4, 'active');
        setInstallHeroState('running', "מתקין את LockSuite במכשיר...", "מבצע התקנה", 65);
        updateProgress(0.65);

        log("מתקין את LockSuite במכשיר...", 'info');
        const installResult = await executeAdbCommand(`pm install -r -g "/data/local/tmp/app.apk"`, "התקנת אפליקציה");
        
        // Anti-quiet failure: check if install succeeded
        if (installResult.toLowerCase().includes('failure') || installResult.toLowerCase().includes('error')) {
            throw new Error("התקנת האפליקציה נכשלה: " + installResult.trim());
        }
        isApkInstalled = true;

        updateMilestone(4, 'done');
        await wait(1500);

        // 5. Set Device Owner & Grant Permissions (80% - 95%)
        currentPhase = 5;
        updateMilestone(5, 'active');
        setInstallHeroState('running', "מגדיר ניהול ראשי והרשאות...", "מפעיל הרשאות ניהול מאובטחות במערכת", 80);
        updateProgress(0.80);

        await setDeviceOwnerWithRetry(CONFIG.TARGET_PACKAGE, CONFIG.DEVICE_ADMIN);

        // Anti-Quiet-Failure: Deep verify device owner status
        log("מאמת הגדרת מנהל מכשיר...", 'info');
        try {
            const verifyOwner = await executeAdbCommand("dumpsys device_policy", "אימות מנהל מכשיר", true);
            if (!verifyOwner.includes(CONFIG.TARGET_PACKAGE)) {
                throw new Error("הגדרת מנהל המכשיר נדחתה על ידי Android. ייתכן שחשבונות שלא הוסרו חוסמים את הפעולה.");
            }
        } catch (verifyErr) {
            if (verifyErr.message.includes("נדחתה על ידי Android")) {
                throw verifyErr;
            }
            console.warn("Device policy verification warning:", verifyErr);
        }
        updateProgress(0.90);
        await grantAllAppPermissions(CONFIG.TARGET_PACKAGE);
        updateMilestone(5, 'done');

        // 6. Launch App (95% - 100%)
        currentPhase = 6;
        updateMilestone(6, 'active');
        setInstallHeroState('running', "מפעיל את האפליקציה...", "פותח את LockSuite במכשיר ומשלים את ההתקנה", 95);
        updateProgress(0.95);

        log("מפעיל את האפליקציה...", 'info');
        await executeAdbCommand(`am start -n ${CONFIG.TARGET_PACKAGE}/.MainActivity`, "הפעלת אפליקציה");

        updateMilestone(6, 'done');
        updateProgress(1.0);
        setInstallHeroState('success', "ההתקנה וההגדרה הושלמו בהצלחה!", "LockSuite הוגדר כמנהל המכשיר ומוכן לשימוש.", 100);
        log("ההתקנה וההגדרה הושלמו בהצלחה!", 'success');
        showToast("ההתקנה הסתיימה בהצלחה!");

        // Show and enable "Install on another device" button only on success
        if (btnNewDevice) {
            btnNewDevice.style.display = 'inline-flex';
            btnNewDevice.disabled = false;
        }

        // Show Success Screen on phone frame
        setPhonePanelState('success');

    } catch (e) {
        log(`שגיאה בתהליך: ${e.message}`, 'error');
        showToast("ההתקנה נכשלה");

        if (btnNewDevice) {
            btnNewDevice.style.display = 'none';
            btnNewDevice.disabled = true;
        }

        if (currentPhase >= 1 && currentPhase <= 6) {
            updateMilestone(currentPhase, 'error');
        }
        setInstallHeroState('error', "ההתקנה נכשלה", e.message || "חלה תקלה במהלך תהליך ההתקנה.", undefined);
        setPhonePanelState('error', { desc: e.message || "חלה תקלה במהלך תהליך ההתקנה." });

        // Rollback unmanaged state & restore disabled packages
        await rollbackInstallation(isApkInstalled);

        // Display guided failure feedback
        showInstallationFailureUI(e.message);

        // Only display the manual fallback file selector if APK retrieval itself failed
        const isApkError = e.message.includes('APK') || e.message.includes('קובץ') || e.message.includes('הורד') || e.message.includes('רשת');
        if (!apkBlob && isApkError && manualBox) {
            manualBox.style.display = 'block';
        }
    } finally {
        // Ensure any remaining disabled packages are restored
        if (appState.disabledPackages.length > 0) {
            await restoreAccounts();
        }
        if (btn) btn.disabled = false;
    }
}

