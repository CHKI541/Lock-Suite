// js/ui.js
import { appState, clearSessionState } from './state.js';
import { CONFIG } from './config.js';

let logLineCount = 0;

// --- Theme Management (Material 3 Dark / Light Mode) ---
export function getCurrentTheme() {
    return document.documentElement.getAttribute('data-theme') || (document.body.classList.contains('light-theme') ? 'light' : 'dark');
}

export function setTheme(theme) {
    const isLight = theme === 'light';
    document.documentElement.setAttribute('data-theme', theme);
    document.body.classList.toggle('light-theme', isLight);
    document.body.classList.toggle('dark-theme', !isLight);
    localStorage.setItem('abloq-installer-theme', theme);

    const icon = document.getElementById('theme-toggle-icon');
    const btn = document.getElementById('theme-toggle-btn');
    if (icon) {
        icon.textContent = isLight ? 'dark_mode' : 'light_mode';
    }
    if (btn) {
        btn.setAttribute('title', isLight ? 'החלף למצב כהה' : 'החלף למצב בהיר');
        btn.setAttribute('aria-label', isLight ? 'החלף למצב כהה' : 'החלף למצב בהיר');
    }
}

export function toggleTheme() {
    const current = getCurrentTheme();
    const nextTheme = current === 'light' ? 'dark' : 'light';
    setTheme(nextTheme);
    showToast(nextTheme === 'light' ? 'ערכת נושא בהירה הופעלה' : 'ערכת נושא כהה הופעלה');
}

export function initTheme() {
    const saved = localStorage.getItem('abloq-installer-theme');
    if (saved) {
        setTheme(saved);
    } else {
        const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        setTheme(prefersDark ? 'dark' : 'light');
    }

    if (window.matchMedia) {
        window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', e => {
            if (!localStorage.getItem('abloq-installer-theme')) {
                setTheme(e.matches ? 'dark' : 'light');
            }
        });
    }
}

export function log(text, type = 'info') {
    const el = document.getElementById('install-log');
    if (el) {
        const div = document.createElement('div');
        div.className = `log-entry log-${type}`;

        const time = new Date().toLocaleTimeString('en-GB', { hour12: false });

        let tagLabel = 'INFO';
        if (type === 'success') tagLabel = 'OK';
        else if (type === 'warn') tagLabel = 'WARN';
        else if (type === 'error') tagLabel = 'ERR';
        else if (type === 'cmd') tagLabel = 'CMD';
        else if (type === 'stdout') tagLabel = 'OUT';

        const sanitizedText = String(text).replace(/</g, "&lt;").replace(/>/g, "&gt;");

        div.innerHTML = `<span class="log-time">[${time}]</span><span class="log-tag">${tagLabel}</span><span class="log-text">${sanitizedText.replace(/\n/g, '<br>')}</span>`;

        el.appendChild(div);
        el.scrollTop = el.scrollHeight;

        logLineCount++;
        const badgeCount = document.getElementById('console-badge-count');
        if (badgeCount) badgeCount.textContent = String(logLineCount);

        const summaryText = document.getElementById('console-line-summary');
        if (summaryText) summaryText.textContent = `${logLineCount} שורות יומן`;
    }
}

export function clearConsoleLog() {
    const el = document.getElementById('install-log');
    if (el) {
        el.innerHTML = '';
        logLineCount = 0;
        const badgeCount = document.getElementById('console-badge-count');
        if (badgeCount) badgeCount.textContent = '0';
        const summaryText = document.getElementById('console-line-summary');
        if (summaryText) summaryText.textContent = '0 שורות יומן';
    }
}

export function openConsoleModal() {
    const modal = document.getElementById('modal-console-drawer');
    if (modal) {
        modal.classList.add('active');
        const logEl = document.getElementById('install-log');
        if (logEl) logEl.scrollTop = logEl.scrollHeight;
    }
}

export function closeConsoleModal() {
    const modal = document.getElementById('modal-console-drawer');
    if (modal) modal.classList.remove('active');
}

let toastTimer = null;
export function showToast(message, icon = 'info') {
    const x = document.getElementById("snackbar");
    if (!x) return;

    if (toastTimer) {
        clearTimeout(toastTimer);
    }

    x.innerHTML = `<span class="material-symbols-rounded toast-icon">${icon}</span><span class="toast-text">${message}</span>`;
    x.className = "show";

    toastTimer = setTimeout(() => {
        x.className = x.className.replace("show", "").trim();
    }, 1800);
}

/**
 * Display top contextual banner and toast when a device disconnection is detected
 */
export function notifyDeviceDisconnected(reason = "המכשיר נותק מהמחשב") {
    const banner = document.getElementById('device-disconnect-banner');
    const titleEl = document.getElementById('disconnect-banner-title');
    const descEl = document.getElementById('disconnect-banner-desc');

    if (banner) {
        banner.style.display = 'flex';
        if (titleEl) titleEl.textContent = reason;
        if (descEl) descEl.textContent = "חיבור ה-USB הופסק. ודאו שהכבל מחובר היטב, המסך פתוח ולחצו 'חבר מחדש'.";
    }

    showToast("המכשיר נותק מהמחשב", 'link_off');

    // If install is active or on accounts page, disable advance buttons
    const nextAcc = document.getElementById('btn-next-acc');
    if (nextAcc && !appState.accountsClean) nextAcc.disabled = true;

    const btnInstall = document.getElementById('btn-install-start');
    if (btnInstall) btnInstall.disabled = true;
}

/**
 * Hide disconnect alert banner when connection is restored
 */
export function hideDisconnectAlert() {
    const banner = document.getElementById('device-disconnect-banner');
    if (banner) banner.style.display = 'none';
}

/**
 * Notify user that a USB device was plugged in
 */
export function notifyDevicePluggedIn(deviceName = "מכשיר USB") {
    showToast(`זוהה חיבור התקן: ${deviceName}`, 'usb');
    // If disconnect banner is currently showing, update its text to prompt reconnection
    const descEl = document.getElementById('disconnect-banner-desc');
    if (descEl) {
        descEl.textContent = `זוהה חיבור התקן (${deviceName}). לחצו 'חבר מחדש' כדי להמשיך.`;
    }
}

export async function copyLogToClipboard() {
    const el = document.getElementById('install-log');
    if (!el || el.innerText.trim() === "") {
        showToast("הלוג ריק, אין מה להעתיק");
        return;
    }

    // Gather info
    const date = new Date().toLocaleString();
    const modelInfo = document.getElementById('adb-status')?.innerText || "Unknown";
    const sdk = appState.sdkVersion || "Unknown";

    // Format text
    let logContent = "";
    el.childNodes.forEach(node => {
        logContent += node.innerText + "\n";
    });

    const markdown = `### LockSuite MDM Installer Report
**Date:** ${date}
**Device Status:** ${modelInfo}
**SDK Version:** ${sdk}
**WebUSB Supported:** ${'usb' in navigator}

\`\`\`text
${logContent}
\`\`\`
`;

    try {
        await navigator.clipboard.writeText(markdown);
        showToast("הלוג הועתק ללוח!");
    } catch (err) {
        console.error('Failed to copy: ', err);
        showToast("שגיאה בהעתקה");
    }
}

export function initStepper() {
    const dot3 = document.getElementById('dot-3');
    if (!CONFIG.ENABLE_WEB_UPDATE && dot3) {
        dot3.style.display = 'none';
    }
}

export function navigateTo(pageId, stepIndex) {
    // Skip update logic
    if (!CONFIG.ENABLE_WEB_UPDATE && pageId === 'page-update') {
        pageId = 'page-install';
        stepIndex = 3;
    }

    // Safety checks
    if (stepIndex >= 2 && !appState.adbConnected) {
        showToast("יש לחבר מכשיר תחילה");
        return;
    }

    document.body.classList.toggle('welcome-mode', pageId === 'page-main');

    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    const targetPage = document.getElementById(pageId);
    if (targetPage) targetPage.classList.add('active');

    const visibleDots = Array.from(document.querySelectorAll('.step-dot')).filter(dot => dot.style.display !== 'none');
    visibleDots.forEach((dot, index) => {
        dot.classList.remove('active', 'completed');
        if (index === stepIndex) dot.classList.add('active');
        if (index < stepIndex) dot.classList.add('completed');
    });

    // Auto-run actions
    if (pageId === 'page-accounts' && window.checkAccounts) window.checkAccounts();
    if (pageId === 'page-install' && window.runInstallation) setTimeout(window.runInstallation, 600);

    // Update phone panel guide / video according to current activity
    updatePhoneGuide(pageId);
}

export const PHONE_GUIDE_CONFIG = {
    'page-main': {
        mode: 'hidden',
        badgeIcon: 'play_circle',
        title: 'מתקין LockSuite',
        info: 'ברוכים הבאים למתקין LockSuite',
        videoSrc: null
    },
    'page-adb': {
        mode: 'video',
        badgeIcon: 'usb',
        title: 'מדריך: הפעלת ניפוי באגים (USB)',
        info: 'ייתכנו שינויים קלים במיקום ההגדרות בין דגמים שונים',
        videoSrc: 'Videos/enable-adb.mp4'
    },
    'page-accounts': {
        mode: 'video',
        badgeIcon: 'manage_accounts',
        title: 'מדריך: הסרת חשבונות מהמכשיר',
        info: 'יש להסיר את החשבונות הפעילים לפני ההתקנה',
        videoSrc: 'Videos/remove-accounts.mp4'
    },
    'page-update': {
        mode: 'installing',
        badgeIcon: 'cloud_download',
        title: 'הורדת חבילת התקנה',
        info: 'טוען ומאמת את קובץ ההתקנה של LockSuite',
        videoSrc: null,
        installTitle: 'מוריד חבילת התקנה...',
        installDesc: 'בודק ומוריד את הגרסה העדכנית ביותר',
        progress: 0.2
    },
    'page-install': {
        mode: 'installing',
        badgeIcon: 'sync',
        title: 'התקנת LockSuite במכשיר',
        info: 'מבצע התקנה והגדרת הרשאות מערכת ונגישות',
        videoSrc: null,
        installTitle: 'מתכונן להתקנה...',
        installDesc: 'בודק הרשאות ומכין את תהליך ההתקנה',
        progress: 0.05
    }
};

function safeVideoPlay(video) {
    if (!video) return;
    try {
        const p = video.play?.();
        if (p && typeof p.then === 'function') {
            p.then(() => updatePlayIcon(true)).catch(() => updatePlayIcon(false));
        }
    } catch (e) {
        updatePlayIcon(false);
    }
}

function safeVideoPause(video) {
    if (!video) return;
    try {
        video.pause?.();
    } catch (e) { }
    updatePlayIcon(false);
}

function safeVideoLoad(video) {
    if (!video) return;
    try {
        video.load?.();
    } catch (e) { }
}

export function updatePhoneGuide(pageId) {
    const config = PHONE_GUIDE_CONFIG[pageId] || PHONE_GUIDE_CONFIG['page-adb'];

    // 1. Update Header badge / title
    const badgeEl = document.getElementById('phone-guide-badge') || document.getElementById('phone-activity-badge');
    const badgeIconEl = document.getElementById('phone-badge-icon');
    const titleEl = document.getElementById('phone-guide-title');
    const infoTextEl = document.getElementById('video-info-text');

    if (badgeEl) {
        badgeEl.className = 'phone-guide-badge' + (config.mode === 'installing' ? ' badge-installing' : '');
    }
    if (badgeIconEl && config.badgeIcon) badgeIconEl.textContent = config.badgeIcon;
    if (titleEl && config.title) titleEl.textContent = config.title;
    if (infoTextEl && config.info) infoTextEl.textContent = config.info;

    // 2. Manage Video / Screens
    const video = document.getElementById('guide-video');
    const phoneControls = document.getElementById('phone-controls');
    const installingScreen = document.getElementById('phone-installing-screen');
    const successScreen = document.getElementById('phone-success-message');

    if (config.mode === 'video' && config.videoSrc) {
        if (installingScreen) installingScreen.style.display = 'none';
        if (successScreen) successScreen.style.display = 'none';
        if (video) {
            video.style.display = 'block';
            const currentSrc = video.getAttribute('data-active-src');
            if (currentSrc !== config.videoSrc) {
                video.setAttribute('data-active-src', config.videoSrc);
                video.src = config.videoSrc;
                safeVideoLoad(video);
                safeVideoPlay(video);
            }
        }
        if (phoneControls) phoneControls.style.display = 'flex';
    } else if (config.mode === 'installing') {
        if (video) {
            safeVideoPause(video);
            video.style.display = 'none';
        }
        if (phoneControls) phoneControls.style.display = 'none';
        if (successScreen) successScreen.style.display = 'none';
        if (installingScreen) {
            installingScreen.style.display = 'flex';
            const stepTitle = document.getElementById('phone-install-step-title');
            const stepDesc = document.getElementById('phone-install-step-desc');
            const fill = document.getElementById('phone-install-progress-fill');
            if (stepTitle && config.installTitle) stepTitle.textContent = config.installTitle;
            if (stepDesc && config.installDesc) stepDesc.textContent = config.installDesc;
            if (fill && config.progress !== undefined) fill.style.width = Math.round(config.progress * 100) + '%';
        }
    }
}

export function setPhonePanelState(mode, data = {}) {
    const badgeEl = document.getElementById('phone-guide-badge') || document.getElementById('phone-activity-badge');
    const badgeIconEl = document.getElementById('phone-badge-icon');
    const titleEl = document.getElementById('phone-guide-title');
    const infoTextEl = document.getElementById('video-info-text');

    const video = document.getElementById('guide-video');
    const phoneControls = document.getElementById('phone-controls');
    const installingScreen = document.getElementById('phone-installing-screen');
    const successScreen = document.getElementById('phone-success-message');

    if (mode === 'installing') {
        if (badgeEl) badgeEl.className = 'phone-guide-badge badge-installing';
        if (badgeIconEl) badgeIconEl.textContent = 'sync';
        if (titleEl) titleEl.textContent = data.mainTitle || 'התקנת LockSuite במכשיר';
        if (infoTextEl) infoTextEl.textContent = data.info || 'מבצע התקנה והגדרת הרשאות מערכת ונגישות';

        if (video) { safeVideoPause(video); video.style.display = 'none'; }
        if (phoneControls) phoneControls.style.display = 'none';
        if (successScreen) successScreen.style.display = 'none';
        if (installingScreen) {
            installingScreen.style.display = 'flex';
            const stepTitle = document.getElementById('phone-install-step-title');
            const stepDesc = document.getElementById('phone-install-step-desc');
            const fill = document.getElementById('phone-install-progress-fill');
            if (stepTitle && data.title) stepTitle.textContent = data.title;
            if (stepDesc && data.desc) stepDesc.textContent = data.desc;
            if (fill && data.progress !== undefined) {
                fill.style.width = Math.min(100, Math.max(0, Math.round(data.progress * 100))) + '%';
            }
        }
    } else if (mode === 'success') {
        if (badgeEl) badgeEl.className = 'phone-guide-badge badge-success';
        if (badgeIconEl) badgeIconEl.textContent = 'check_circle';
        if (titleEl) titleEl.textContent = 'ההתקנה וההגדרה הושלמו!';
        if (infoTextEl) infoTextEl.textContent = 'LockSuite הוגדר כמנהל המכשיר ומוכן לשימוש';

        if (video) { safeVideoPause(video); video.style.display = 'none'; }
        if (phoneControls) phoneControls.style.display = 'none';
        if (installingScreen) installingScreen.style.display = 'none';
        if (successScreen) successScreen.style.display = 'flex';
    } else if (mode === 'error') {
        if (badgeEl) badgeEl.className = 'phone-guide-badge badge-error';
        if (badgeIconEl) badgeIconEl.textContent = 'error';
        if (titleEl) titleEl.textContent = 'תקלה בתהליך ההתקנה';
        if (infoTextEl) infoTextEl.textContent = data.desc || 'חלה שגיאה. בדקו את יומן הפקודות או נסו שוב';
    }
}

export function updatePlayIcon(isPlaying) {
    const icon = document.getElementById('video-icon');
    if (icon) icon.innerText = isPlaying ? 'pause' : 'play_arrow';
}

export function showOverlayRipple(iconName) {
    const overlay = document.getElementById('phone-video-overlay');
    const overlayIcon = document.getElementById('phone-overlay-icon');
    if (overlay && overlayIcon) {
        overlayIcon.textContent = iconName;
        overlay.classList.add('show-overlay');
        clearTimeout(overlay._timer);
        overlay._timer = setTimeout(() => {
            overlay.classList.remove('show-overlay');
        }, 450);
    }
}

export function toggleVideo() {
    const vid = document.getElementById('guide-video');
    if (vid && vid.style.display !== 'none') {
        if (vid.paused) {
            safeVideoPlay(vid);
            showOverlayRipple('play_arrow');
        } else {
            safeVideoPause(vid);
            showOverlayRipple('pause');
        }
    }
}

export function replayVideo() {
    const vid = document.getElementById('guide-video');
    if (vid && vid.style.display !== 'none') {
        try {
            vid.currentTime = 0;
        } catch (e) { }
        safeVideoPlay(vid);
        showOverlayRipple('replay');
    }
}

export function handlePhoneFrameClick(event) {
    const vid = document.getElementById('guide-video');
    if (vid && vid.style.display !== 'none') {
        toggleVideo();
    }
}

export function updateStatusBadge(id, text, type) {
    const el = document.getElementById(id);
    if (el) { el.innerHTML = text; el.className = 'status-badge ' + type; }
}

const DEFAULT_MILESTONE_ICONS = {
    1: 'verified_user',
    2: 'cloud_download',
    3: 'sync_alt',
    4: 'install_mobile',
    5: 'admin_panel_settings',
    6: 'rocket_launch'
};

export function resetMilestones() {
    for (let i = 1; i <= 6; i++) {
        const card = document.getElementById(`milestone-${i}`);
        if (!card) continue;
        card.className = 'milestone-card status-pending';
        const badge = card.querySelector('.milestone-badge');
        if (badge) {
            badge.className = 'milestone-badge badge-pending';
            badge.textContent = 'ממתין';
        }
        const iconEl = card.querySelector('.milestone-icon');
        if (iconEl && DEFAULT_MILESTONE_ICONS[i]) {
            iconEl.textContent = DEFAULT_MILESTONE_ICONS[i];
        }
    }
}

export function updateMilestone(stepNum, status, customCaption = null) {
    const card = document.getElementById(`milestone-${stepNum}`);
    if (!card) return;

    card.className = `milestone-card status-${status}`;
    const badge = card.querySelector('.milestone-badge');
    const iconEl = card.querySelector('.milestone-icon');

    if (badge) {
        badge.className = `milestone-badge badge-${status}`;
        if (status === 'pending') badge.textContent = 'ממתין';
        else if (status === 'active') badge.textContent = 'בתהליך...';
        else if (status === 'done') badge.textContent = 'הושלם';
        else if (status === 'error') badge.textContent = 'נכשל';
    }

    if (iconEl) {
        if (status === 'done') {
            iconEl.textContent = 'check';
        } else if (status === 'error') {
            iconEl.textContent = 'priority_high';
        } else if (DEFAULT_MILESTONE_ICONS[stepNum]) {
            iconEl.textContent = DEFAULT_MILESTONE_ICONS[stepNum];
        }
    }

    if (customCaption) {
        const captionEl = card.querySelector('.milestone-caption');
        if (captionEl) captionEl.textContent = customCaption;
    }
}

export function setInstallHeroState(statusType, title, desc, percentVal) {
    const heroCard = document.getElementById('install-hero-status');
    const titleEl = document.getElementById('install-hero-title');
    const descEl = document.getElementById('install-hero-desc');
    const percentEl = document.getElementById('install-hero-percent');
    const iconEl = document.getElementById('install-hero-icon');

    if (heroCard) {
        heroCard.className = `install-hero-card hero-${statusType}`;
    }
    if (title && titleEl) titleEl.textContent = title;
    if (desc && descEl) descEl.textContent = desc;
    if (percentVal !== undefined && percentEl) {
        percentEl.textContent = typeof percentVal === 'number' ? `${Math.round(percentVal)}%` : String(percentVal);
    }
    if (iconEl) {
        if (statusType === 'running') iconEl.textContent = 'sync';
        else if (statusType === 'success') iconEl.textContent = 'check_circle';
        else if (statusType === 'error') iconEl.textContent = 'error';
        else iconEl.textContent = 'play_circle';
    }
}

export function updateProgress(val, activeMilestone = null, heroTitle = null, heroDesc = null) {
    const bar = document.getElementById('install-progress-bar');
    const percent = Math.min(100, Math.max(0, Math.round(val * 100)));

    if (bar) bar.style.width = percent + "%";

    const percentEl = document.getElementById('install-hero-percent');
    if (percentEl) percentEl.textContent = percent + "%";

    if (heroTitle || heroDesc) {
        setInstallHeroState(val >= 1 ? 'success' : 'running', heroTitle, heroDesc, percent);
    }

    // Sync live progress with phone screen
    const phoneFill = document.getElementById('phone-install-progress-fill');
    if (phoneFill) phoneFill.style.width = percent + "%";
    const phoneStepTitle = document.getElementById('phone-install-step-title');
    if (phoneStepTitle && heroTitle) phoneStepTitle.textContent = heroTitle;
    const phoneStepDesc = document.getElementById('phone-install-step-desc');
    if (phoneStepDesc && heroDesc) phoneStepDesc.textContent = heroDesc;

    if (activeMilestone) {
        for (let i = 1; i < activeMilestone; i++) {
            updateMilestone(i, 'done');
        }
        updateMilestone(activeMilestone, 'active');
    }
}

export function startNewDeviceInstall() {
    clearSessionState();
    localStorage.clear();
    appState.adbConnected = false;
    appState.adbInstance = null;
    appState.webUsbInstance = null;
    appState.disabledPackages = [];
    appState.accountsClean = false;
    appState.apkDownloaded = false;

    if (window.resetApkBlob) window.resetApkBlob();

    resetMilestones();
    clearConsoleLog();

    const guideVideo = document.getElementById('guide-video');
    if (guideVideo) {
        guideVideo.removeAttribute('data-active-src');
    }

    const errorBox = document.getElementById('install-error-box');
    if (errorBox) errorBox.style.display = 'none';
    const manualBox = document.getElementById('manual-apk-box');
    if (manualBox) manualBox.style.display = 'none';

    const btnNewDevice = document.getElementById('btn-new-device');
    if (btnNewDevice) {
        btnNewDevice.style.display = 'none';
        btnNewDevice.disabled = true;
    }

    const btnConnect = document.getElementById('btn-connect');
    if (btnConnect) {
        btnConnect.style.display = 'inline-flex';
        btnConnect.disabled = false;
    }
    const btnNextAdb = document.getElementById('btn-next-adb');
    if (btnNextAdb) {
        btnNextAdb.style.display = 'none';
        btnNextAdb.disabled = true;
    }
    updateStatusBadge('adb-status', '<span class="material-symbols-rounded">link_off</span> לא מחובר', '');

    showToast("הזיכרון אופס. מוכן להתקנה על מכשיר חדש");
    navigateTo('page-main', 0);
}
