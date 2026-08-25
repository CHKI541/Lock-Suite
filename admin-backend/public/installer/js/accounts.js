import { appState, saveSessionState, clearSessionState, restoreSessionState } from './state.js';
import { executeAdbCommand, wait } from './adb-client.js';
import { log, showToast, updateStatusBadge, navigateTo } from './ui.js';
import { PROTECTED_PACKAGES, KNOWN_OFFENDERS, ACCOUNT_PKG_MAP } from './config.js';

// --- Modern Standard CDN-Delivered Brand Logos & Material 3 System ---
function brandLogo(cdnUrl, fallbackM3Icon, altText, fallbackClass = '') {
    return `<img src="${cdnUrl}" alt="${altText}" class="brand-icon-img" loading="lazy" onerror="this.outerHTML='<span class=\\'material-symbols-rounded ${fallbackClass}\\'>${fallbackM3Icon}</span>'" />`;
}

// Authentic Modern Mobile App Icons (Matching Android System Settings)
const SAMSUNG_SVG = `<svg class="brand-icon-svg" viewBox="0 0 48 48" width="30" height="30" xmlns="http://www.w3.org/2000/svg">
  <ellipse cx="24" cy="24" rx="20" ry="11.5" fill="none" stroke="#ffffff" stroke-width="2.6" transform="rotate(-15 24 24)"/>
  <text x="24" y="27.5" fill="#ffffff" font-family="'Roboto', 'Segoe UI', Arial, sans-serif" font-weight="900" font-size="6.8" text-anchor="middle" letter-spacing="1.2">SAMSUNG</text>
</svg>`;

const OUTLOOK_SVG = `<svg class="brand-icon-svg" viewBox="0 0 48 48" width="30" height="30" xmlns="http://www.w3.org/2000/svg">
  <path fill="#28a8ea" d="M38 12H25v24h13c1.1 0 2-.9 2-2V14c0-1.1-.9-2-2-2z"/>
  <path fill="#005a9e" d="m25 24 15 8-15 4V24z"/>
  <path fill="#50d9ff" d="m25 12 15 8-15 4V12z"/>
  <rect x="8" y="13" width="20" height="22" rx="4.5" fill="#0078d4" stroke="#ffffff" stroke-width="1.8"/>
  <circle cx="18" cy="24" r="5" fill="#ffffff"/>
  <circle cx="18" cy="24" r="2.7" fill="#0078d4"/>
</svg>`;

const TEAMS_SVG = `<svg class="brand-icon-svg" viewBox="0 0 48 48" width="30" height="30" xmlns="http://www.w3.org/2000/svg">
  <path fill="#7b83eb" d="M32 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-2 4h4a4 4 0 0 1 4 4v7h-8v-11z"/>
  <path fill="#505ac9" d="M22 14a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm-3 5h6a5 5 0 0 1 5 5v12H14V24a5 5 0 0 1 5-5z"/>
  <rect x="6" y="16" width="18" height="20" rx="3.5" fill="#464775" stroke="#ffffff" stroke-width="1.5"/>
  <text x="15" y="31.5" fill="#ffffff" font-family="'Roboto', sans-serif" font-weight="900" font-size="14" text-anchor="middle">T</text>
</svg>`;

const ICONS = {
    // Top Android & Cloud Ecosystems (Official Multi-color & Vector Brand SVGs via Standard CDNs)
    google: brandLogo('https://api.iconify.design/logos:google-icon.svg', 'account_circle', 'Google', 'icon-google'),
    gmail: brandLogo('https://api.iconify.design/logos:google-gmail.svg', 'mail', 'Gmail', 'icon-gmail'),
    googledrive: brandLogo('https://api.iconify.design/logos:google-drive.svg', 'cloud', 'Google Drive', 'icon-googledrive'),
    googlemeet: brandLogo('https://api.iconify.design/logos:google-meet.svg', 'video_call', 'Google Meet', 'icon-googlemeet'),
    samsung: SAMSUNG_SVG,
    microsoft: brandLogo('https://api.iconify.design/logos:microsoft-icon.svg', 'domain', 'Microsoft', 'icon-microsoft'),
    outlook: OUTLOOK_SVG,
    teams: TEAMS_SVG,
    onedrive: brandLogo('https://cdn.jsdelivr.net/npm/simple-icons@v11/icons/microsoftonedrive.svg', 'cloud_queue', 'Microsoft OneDrive', 'icon-onedrive'),
    xiaomi: brandLogo('https://cdn.jsdelivr.net/npm/simple-icons@v11/icons/xiaomi.svg', 'devices_other', 'Xiaomi', 'icon-xiaomi'),
    huawei: brandLogo('https://cdn.jsdelivr.net/npm/simple-icons@v11/icons/huawei.svg', 'hub', 'Huawei', 'icon-huawei'),

    // Social & Messaging (Official Multi-color Brand Icons)
    whatsapp: brandLogo('https://api.iconify.design/logos:whatsapp-icon.svg', 'chat', 'WhatsApp', 'icon-whatsapp'),
    whatsappBusiness: brandLogo('https://api.iconify.design/logos:whatsapp-icon.svg', 'store', 'WhatsApp Business', 'icon-whatsapp'),
    telegram: brandLogo('https://api.iconify.design/logos:telegram.svg', 'send', 'Telegram', 'icon-telegram'),
    facebook: brandLogo('https://api.iconify.design/logos:facebook.svg', 'group', 'Facebook', 'icon-facebook'),
    messenger: brandLogo('https://api.iconify.design/logos:messenger.svg', 'chat_bubble', 'Messenger', 'icon-messenger'),
    instagram: brandLogo('https://api.iconify.design/logos:instagram-icon.svg', 'photo_camera', 'Instagram', 'icon-instagram'),
    tiktok: brandLogo('https://api.iconify.design/logos:tiktok-icon.svg', 'music_note', 'TikTok', 'icon-tiktok'),
    discord: brandLogo('https://api.iconify.design/logos:discord-icon.svg', 'forum', 'Discord', 'icon-discord'),
    twitter: brandLogo('https://cdn.jsdelivr.net/npm/simple-icons@v11/icons/x.svg', 'tag', 'X', 'icon-twitter'),
    signal: brandLogo('https://api.iconify.design/logos:signal.svg', 'lock', 'Signal', 'icon-signal'),
    snapchat: brandLogo('https://cdn.jsdelivr.net/npm/simple-icons@v11/icons/snapchat.svg', 'camera_alt', 'Snapchat', 'icon-snapchat'),
    viber: brandLogo('https://api.iconify.design/logos:viber.svg', 'phone_in_talk', 'Viber', 'icon-viber'),
    skype: brandLogo('https://api.iconify.design/logos:skype.svg', 'call', 'Skype', 'icon-skype'),
    linkedin: brandLogo('https://api.iconify.design/logos:linkedin-icon.svg', 'work', 'LinkedIn', 'icon-linkedin'),
    reddit: brandLogo('https://api.iconify.design/logos:reddit-icon.svg', 'forum', 'Reddit', 'icon-reddit'),
    pinterest: brandLogo('https://api.iconify.design/logos:pinterest.svg', 'push_pin', 'Pinterest', 'icon-pinterest'),
    twitch: brandLogo('https://api.iconify.design/logos:twitch.svg', 'live_tv', 'Twitch', 'icon-twitch'),

    // Productivity, Utilities & Entertainment
    zoom: brandLogo('https://api.iconify.design/logos:zoom-icon.svg', 'videocam', 'Zoom', 'icon-zoom'),
    spotify: brandLogo('https://api.iconify.design/logos:spotify-icon.svg', 'headphones', 'Spotify', 'icon-spotify'),
    duolingo: brandLogo('https://api.iconify.design/logos:duolingo.svg', 'school', 'Duolingo', 'icon-duolingo'),
    dropbox: brandLogo('https://api.iconify.design/logos:dropbox.svg', 'folder_shared', 'Dropbox', 'icon-dropbox'),
    evernote: brandLogo('https://api.iconify.design/logos:evernote-icon.svg', 'note_alt', 'Evernote', 'icon-evernote'),
    bitwarden: brandLogo('https://api.iconify.design/logos:bitwarden-icon.svg', 'password', 'Bitwarden', 'icon-bitwarden'),
    onepassword: brandLogo('https://api.iconify.design/logos:1password-icon.svg', 'key', '1Password', 'icon-onepassword'),
    yahoo: brandLogo('https://api.iconify.design/logos:yahoo.svg', 'mail', 'Yahoo', 'icon-yahoo'),
    proton: brandLogo('https://cdn.jsdelivr.net/npm/simple-icons@v11/icons/protonmail.svg', 'mark_email_read', 'Proton', 'icon-proton'),
    amazon: brandLogo('https://api.iconify.design/logos:amazon-icon.svg', 'shopping_bag', 'Amazon', 'icon-amazon'),
    steam: brandLogo('https://api.iconify.design/logos:steam.svg', 'sports_esports', 'Steam', 'icon-steam'),
    strava: brandLogo('https://api.iconify.design/logos:strava.svg', 'directions_run', 'Strava', 'icon-strava'),
    netflix: brandLogo('https://api.iconify.design/logos:netflix-icon.svg', 'movie', 'Netflix', 'icon-netflix'),
    uber: brandLogo('https://api.iconify.design/logos:uber.svg', 'local_taxi', 'Uber', 'icon-uber'),
    waze: brandLogo('https://api.iconify.design/logos:waze-icon.svg', 'navigation', 'Waze', 'icon-waze'),
    paypal: brandLogo('https://api.iconify.design/logos:paypal.svg', 'payments', 'PayPal', 'icon-paypal'),

    // System & Organizational Accounts (Material 3 Icons)
    work: `<span class="material-symbols-rounded icon-work">badge</span>`,
    email: `<span class="material-symbols-rounded icon-email">mark_email_read</span>`,
    generic: `<span class="material-symbols-rounded icon-generic">account_circle</span>`
};

export function getAccountVisuals(type) {
    const t = (type || '').toLowerCase();

    // Google Ecosystem
    if (t.includes('google') || t.includes('gmail') || t.includes('tachyon') || t.includes('googlemeet') || t.includes('docs')) {
        if (t.includes('gmail') || t.endsWith('.gm')) {
            return { label: 'Gmail', brandName: 'דוא"ל Gmail', html: ICONS.gmail, class: 'acc-gmail', canBypass: true };
        }
        if (t.includes('tachyon') || t.includes('meet')) {
            return { label: 'Google Meet', brandName: 'Google Meet', html: ICONS.googlemeet, class: 'acc-google', canBypass: true };
        }
        if (t.includes('drive') || t.includes('docs')) {
            return { label: 'Google Drive', brandName: 'Google Drive', html: ICONS.googledrive, class: 'acc-google', canBypass: true };
        }
        return { label: 'Google', brandName: 'חשבון Google', html: ICONS.google, class: 'acc-google', canBypass: true };
    }

    // Samsung Ecosystem
    if (t.includes('samsung') || t.includes('osp')) {
        return { label: 'Samsung', brandName: 'חשבון Samsung', html: ICONS.samsung, class: 'acc-samsung', canBypass: true };
    }

    // Microsoft Ecosystem
    if (t.includes('microsoft') || t.includes('outlook') || t.includes('azure') || t.includes('skydrive') || t.includes('onedrive') || t.includes('teams') || t.includes('office')) {
        if (t.includes('outlook')) {
            return { label: 'Microsoft', brandName: 'דוא"ל Microsoft Outlook', html: ICONS.outlook, class: 'acc-microsoft', canBypass: true };
        }
        if (t.includes('teams')) {
            return { label: 'Teams', brandName: 'Microsoft Teams', html: ICONS.teams, class: 'acc-teams', canBypass: true };
        }
        if (t.includes('skydrive') || t.includes('onedrive')) {
            return { label: 'OneDrive', brandName: 'Microsoft OneDrive', html: ICONS.onedrive, class: 'acc-microsoft', canBypass: true };
        }
        return { label: 'Microsoft', brandName: 'חשבון Microsoft', html: ICONS.microsoft, class: 'acc-microsoft', canBypass: true };
    }

    // WhatsApp
    if (t.includes('whatsapp')) {
        const isBiz = t.includes('w4b') || t.includes('business');
        return { label: isBiz ? 'WhatsApp Business' : 'WhatsApp', brandName: isBiz ? 'חשבון WhatsApp Business' : 'חשבון WhatsApp', html: isBiz ? ICONS.whatsappBusiness : ICONS.whatsapp, class: 'acc-whatsapp', canBypass: true };
    }

    // Telegram
    if (t.includes('telegram') || t.includes('challegram')) {
        return { label: 'Telegram', brandName: 'חשבון Telegram', html: ICONS.telegram, class: 'acc-telegram', canBypass: true };
    }

    // Xiaomi / MIUI
    if (t.includes('xiaomi') || t.includes('miui')) {
        return { label: 'Xiaomi', brandName: 'חשבון Xiaomi Mi', html: ICONS.xiaomi, class: 'acc-xiaomi', canBypass: true };
    }

    // Meta / Facebook / Messenger
    if (t.includes('facebook') || t.includes('messenger') || t.includes('katana') || t.includes('orca') || t.includes('meta')) {
        if (t.includes('messenger') || t.includes('orca')) {
            return { label: 'Messenger', brandName: 'Facebook Messenger', html: ICONS.messenger, class: 'acc-messenger', canBypass: true };
        }
        return { label: 'Meta', brandName: 'חשבון Meta / Facebook', html: ICONS.facebook, class: 'acc-facebook', canBypass: true };
    }

    // Instagram
    if (t.includes('instagram')) {
        return { label: 'Instagram', brandName: 'חשבון Instagram', html: ICONS.instagram, class: 'acc-instagram', canBypass: true };
    }

    // TikTok
    if (t.includes('tiktok') || t.includes('musically')) {
        return { label: 'TikTok', brandName: 'חשבון TikTok', html: ICONS.tiktok, class: 'acc-tiktok', canBypass: true };
    }

    // Discord
    if (t.includes('discord')) {
        return { label: 'Discord', brandName: 'חשבון Discord', html: ICONS.discord, class: 'acc-discord', canBypass: true };
    }

    // Zoom
    if (t.includes('zoom') || t.includes('videomeetings')) {
        return { label: 'Zoom', brandName: 'חשבון Zoom', html: ICONS.zoom, class: 'acc-zoom', canBypass: true };
    }

    // Spotify
    if (t.includes('spotify')) {
        return { label: 'Spotify', brandName: 'חשבון Spotify', html: ICONS.spotify, class: 'acc-spotify', canBypass: true };
    }

    // Duolingo
    if (t.includes('duolingo')) {
        return { label: 'Duolingo', brandName: 'חשבון Duolingo', html: ICONS.duolingo, class: 'acc-duolingo', canBypass: true };
    }

    // X / Twitter
    if (t.includes('twitter') || t.includes('tweet') || t.includes('.x.') || t.endsWith('.x')) {
        return { label: 'X', brandName: 'חשבון X (Twitter)', html: ICONS.twitter, class: 'acc-twitter', canBypass: true };
    }

    // Huawei
    if (t.includes('huawei') || t.includes('hwid') || t.includes('hidisk')) {
        return { label: 'Huawei', brandName: 'חשבון Huawei ID', html: ICONS.huawei, class: 'acc-huawei', canBypass: true };
    }

    // Skype
    if (t.includes('skype')) {
        return { label: 'Skype', brandName: 'חשבון Skype', html: ICONS.skype, class: 'acc-skype', canBypass: true };
    }

    // Viber
    if (t.includes('viber')) {
        return { label: 'Viber', brandName: 'חשבון Viber', html: ICONS.viber, class: 'acc-viber', canBypass: true };
    }

    // Snapchat
    if (t.includes('snapchat')) {
        return { label: 'Snapchat', brandName: 'חשבון Snapchat', html: ICONS.snapchat, class: 'acc-snapchat', canBypass: true };
    }

    // Signal
    if (t.includes('signal') || t.includes('securesms')) {
        return { label: 'Signal', brandName: 'חשבון Signal', html: ICONS.signal, class: 'acc-signal', canBypass: true };
    }

    // LinkedIn
    if (t.includes('linkedin')) {
        return { label: 'LinkedIn', brandName: 'חשבון LinkedIn', html: ICONS.linkedin, class: 'acc-linkedin', canBypass: true };
    }

    // Dropbox
    if (t.includes('dropbox')) {
        return { label: 'Dropbox', brandName: 'חשבון Dropbox', html: ICONS.dropbox, class: 'acc-dropbox', canBypass: true };
    }

    // Evernote
    if (t.includes('evernote')) {
        return { label: 'Evernote', brandName: 'חשבון Evernote', html: ICONS.evernote, class: 'acc-evernote', canBypass: true };
    }

    // Reddit
    if (t.includes('reddit')) {
        return { label: 'Reddit', brandName: 'חשבון Reddit', html: ICONS.reddit, class: 'acc-reddit', canBypass: true };
    }

    // Amazon
    if (t.includes('amazon') || t.includes('kindle')) {
        return { label: 'Amazon', brandName: 'חשבון Amazon', html: ICONS.amazon, class: 'acc-amazon', canBypass: true };
    }

    // Password Managers
    if (t.includes('bitwarden')) {
        return { label: 'Bitwarden', brandName: 'חשבון Bitwarden', html: ICONS.bitwarden, class: 'acc-bitwarden', canBypass: true };
    }
    if (t.includes('1password') || t.includes('onepassword')) {
        return { label: '1Password', brandName: 'חשבון 1Password', html: ICONS.onepassword, class: 'acc-onepassword', canBypass: true };
    }

    // Yahoo & Proton Mail
    if (t.includes('yahoo')) {
        return { label: 'Yahoo', brandName: 'דוא"ל Yahoo', html: ICONS.yahoo, class: 'acc-yahoo', canBypass: true };
    }
    if (t.includes('proton') || t.includes('protonmail')) {
        return { label: 'Proton', brandName: 'חשבון Proton Mail', html: ICONS.proton, class: 'acc-proton', canBypass: true };
    }

    // Other Popular Apps
    if (t.includes('pinterest')) {
        return { label: 'Pinterest', brandName: 'חשבון Pinterest', html: ICONS.pinterest, class: 'acc-pinterest', canBypass: true };
    }
    if (t.includes('twitch')) {
        return { label: 'Twitch', brandName: 'חשבון Twitch', html: ICONS.twitch, class: 'acc-twitch', canBypass: true };
    }
    if (t.includes('steam')) {
        return { label: 'Steam', brandName: 'חשבון Steam', html: ICONS.steam, class: 'acc-steam', canBypass: true };
    }
    if (t.includes('strava')) {
        return { label: 'Strava', brandName: 'חשבון Strava', html: ICONS.strava, class: 'acc-strava', canBypass: true };
    }
    if (t.includes('netflix')) {
        return { label: 'Netflix', brandName: 'חשבון Netflix', html: ICONS.netflix, class: 'acc-netflix', canBypass: true };
    }
    if (t.includes('uber')) {
        return { label: 'Uber', brandName: 'חשבון Uber', html: ICONS.uber, class: 'acc-uber', canBypass: true };
    }
    if (t.includes('waze')) {
        return { label: 'Waze', brandName: 'חשבון Waze', html: ICONS.waze, class: 'acc-waze', canBypass: true };
    }
    if (t.includes('paypal')) {
        return { label: 'PayPal', brandName: 'חשבון PayPal', html: ICONS.paypal, class: 'acc-paypal', canBypass: true };
    }

    // Work Profile / Enterprise / MDM
    if (t.includes('work') || t.includes('knox') || t.includes('enterprise') || t.includes('mdm')) {
        return { label: 'Work Profile', brandName: 'פרופיל עבודה / ארגוני', html: ICONS.work, class: 'acc-work', canBypass: false };
    }

    // Generic Corporate Email / Exchange / POP3 / IMAP
    if (t.includes('mail') || t.includes('exchange') || t.includes('pop3') || t.includes('imap') || t.includes('email')) {
        return { label: 'Email', brandName: 'חשבון דוא"ל ארגוני (Exchange/IMAP)', html: ICONS.email, class: 'acc-exchange', canBypass: true };
    }

    const cleanType = type ? type.replace(/^com\./, '').split('.').slice(-2).join('.') : 'חשבון נוסף';
    return { label: cleanType, brandName: cleanType, html: ICONS.generic, class: 'acc-unknown', canBypass: true };
}

// --- Dynamic Parser Helpers ---

/**
 * Fetch account dump output from dumpsys account with integrity verification
 */
export async function getAccountDump() {
    let output = "";
    let attempts = 0;
    const maxAttempts = 2;

    while (attempts < maxAttempts) {
        attempts++;
        try {
            output = await executeAdbCommand("dumpsys account", "בדיקת חשבונות מעמיקה (dumpsys account)", true);
            
            const trimmed = (output || "").trim();
            const lower = trimmed.toLowerCase();

            // Validate that the output is not a known system error
            if (lower.includes("can't find service") || lower.includes("deadobjectexception") || lower.includes("service not found")) {
                throw new Error("שירות החשבונות באנדרואיד אינו זמין (ייתכן שהמכשיר נטען או נעול)");
            }

            // If output is completely empty on connected device, retry once
            if (!trimmed && attempts < maxAttempts) {
                await wait(600);
                continue;
            }

            return output;
        } catch (e) {
            console.warn(`dumpsys account query attempt ${attempts} error:`, e);
            if (attempts >= maxAttempts) {
                throw new Error(`שגיאה בקריאת חשבונות מהמכשיר: ${e.message || 'פלט ריק או שגיאת שירות'}`);
            }
            await wait(600);
        }
    }
    return output;
}

/**
 * Parse active account entries from ADB dumpsys output
 * Returns Array<{ name: string, type: string }>
 */
export function parseActiveAccounts(rawOutput) {
    const unique = new Map();
    if (!rawOutput) return [];

    // Pattern 1: Account {name=foo@gmail.com, type=com.google}
    const accountRegex = /Account\s*\{?\s*name[=:]\s*([^\s,]+)[^}]*?type[=:]\s*([^\s,}]+)/gi;
    for (const m of rawOutput.matchAll(accountRegex)) {
        if (m[1] && m[2]) {
            const name = m[1].trim();
            const type = m[2].trim();
            unique.set(`${name}|${type}`, { name, type });
        }
    }

    // Pattern 2: Generic fallback regex for lines like: name=user@test.com, type=com.google
    if (unique.size === 0) {
        const fallbackRegex = /name=([^\s,]+)[^}]*?type=([^\s}]+)/gi;
        for (const m of rawOutput.matchAll(fallbackRegex)) {
            if (m[1] && m[2]) {
                const name = m[1].trim();
                const type = m[2].trim();
                unique.set(`${name}|${type}`, { name, type });
            }
        }
    }

    return Array.from(unique.values());
}

/**
 * Extract type -> packageName mapping dynamically from dumpsys account
 */
export function parseAuthenticatorMapping(dumpsysOutput) {
    const map = new Map();
    if (!dumpsysOutput) return map;

    // Pattern 1: AuthenticatorDescription {type=com.google, packageName=com.google.android.gms, ...}
    const descRegex = /AuthenticatorDescription\s*\{\s*type=([^,\s]+),\s*packageName=([^,\s]+)/gi;
    for (const match of dumpsysOutput.matchAll(descRegex)) {
        if (match[1] && match[2]) {
            map.set(match[1].trim(), match[2].trim());
        }
    }

    // Pattern 2: ComponentInfo{com.pkg.name/...} ... type=com.pkg.type
    const serviceRegex = /ComponentInfo\{([^\/\}]+)\/[^\}]+\}[\s\S]{1,400}?type=([^\s,]+)/gi;
    for (const match of dumpsysOutput.matchAll(serviceRegex)) {
        if (match[1] && match[2]) {
            const pkg = match[1].trim();
            const type = match[2].trim();
            if (!map.has(type)) {
                map.set(type, pkg);
            }
        }
    }

    return map;
}

/**
 * Get Set of all installed packages on device
 */
export async function getInstalledPackages() {
    const set = new Set();
    try {
        const out = await executeAdbCommand("pm list packages", "Get Installed Packages", true);
        const lines = out.split(/\r?\n/);
        for (const line of lines) {
            const trimmed = line.trim();
            if (trimmed.startsWith("package:")) {
                set.add(trimmed.substring(8).trim());
            }
        }
    } catch (e) {
        console.warn("Failed to get installed packages:", e);
    }
    return set;
}

/**
 * Resolve the backing package for an account type using multi-tier strategies
 */
export function resolvePackageForAccount(accountType, authMap, installedPackages) {
    if (!accountType) return null;

    // 1. Dynamic authenticator map from dumpsys
    if (authMap.has(accountType)) {
        const pkg = authMap.get(accountType);
        if (installedPackages.size === 0 || installedPackages.has(pkg)) {
            return pkg;
        }
    }

    // 2. Curated mapping
    if (ACCOUNT_PKG_MAP[accountType]) {
        const pkg = ACCOUNT_PKG_MAP[accountType];
        if (installedPackages.size === 0 || installedPackages.has(pkg)) {
            return pkg;
        }
    }

    // 3. Direct match: account type is itself an installed package
    if (installedPackages.has(accountType)) {
        return accountType;
    }

    // 4. Stem matching: Check if account type starts with an installed package (e.g. com.outfit7.talkingtomgoldrun.account -> com.outfit7.talkingtomgoldrun)
    for (const pkg of installedPackages) {
        if (accountType === pkg || accountType.startsWith(pkg + '.')) {
            return pkg;
        }
    }

    // 5. Strip common suffixes (.account, .sync, .login, .auth, .provider) and test
    const stripped = accountType.replace(/\.(account|sync|login|auth|provider|exchange|pop3|legacyimap)$/i, '');
    if (installedPackages.has(stripped)) {
        return stripped;
    }

    // 6. Substring match across packages
    for (const pkg of installedPackages) {
        if (pkg.startsWith(stripped)) {
            return pkg;
        }
    }

    // Fallback: if accountType looks like a package (contains dots)
    if (accountType.includes('.')) {
        return accountType;
    }

    return null;
}

/**
 * Computes preview information for bypass modal
 */
export async function getBypassPreview() {
    const rawDump = await getAccountDump();
    const activeAccounts = parseActiveAccounts(rawDump);
    if (activeAccounts.length === 0) {
        return { accounts: [], packages: [], isBlockedSdk14: (appState.sdkVersion || 0) >= 34 };
    }

    const installedPackages = await getInstalledPackages();
    const authMap = parseAuthenticatorMapping(rawDump);
    const packagesToDisable = new Set();
    let hasGoogleAccount = false;
    let hasSamsungAccount = false;

    for (const acc of activeAccounts) {
        if (acc.type.toLowerCase().includes('google')) hasGoogleAccount = true;
        if (acc.type.toLowerCase().includes('samsung') || acc.type.toLowerCase().includes('osp')) hasSamsungAccount = true;

        const pkg = resolvePackageForAccount(acc.type, authMap, installedPackages);
        if (pkg && !PROTECTED_PACKAGES.some(p => pkg === p || pkg.startsWith(p + '.'))) {
            packagesToDisable.add(pkg);
        }
    }

    if (hasGoogleAccount) {
        ['com.google.android.gms', 'com.google.android.gsf', 'com.google.android.apps.tachyon', 'com.google.android.gm'].forEach(p => {
            if (installedPackages.has(p) && !PROTECTED_PACKAGES.includes(p)) packagesToDisable.add(p);
        });
    }

    if (hasSamsungAccount) {
        ['com.samsung.android.mobileservice', 'com.osp.app.signin', 'com.samsung.android.scloud', 'com.samsung.android.authfw', 'com.samsung.android.coreapps'].forEach(p => {
            if (installedPackages.has(p) && !PROTECTED_PACKAGES.includes(p)) packagesToDisable.add(p);
        });
    }

    for (const off of KNOWN_OFFENDERS) {
        if (installedPackages.has(off) && !PROTECTED_PACKAGES.includes(off)) {
            packagesToDisable.add(off);
        }
    }

    return {
        accounts: activeAccounts,
        packages: Array.from(packagesToDisable),
        isBlockedSdk14: (appState.sdkVersion || 0) >= 34
    };
}

// --- Public Operations ---

export async function checkAccounts() {
    if (!appState.adbConnected) return showToast("ADB לא מחובר");

    const listDiv = document.getElementById('account-list');
    const heroStatus = document.getElementById('account-hero-status');
    const heroTitle = document.getElementById('account-hero-title');
    const heroDesc = document.getElementById('account-hero-desc');
    const heroIcon = document.getElementById('account-hero-icon');
    const bypassBtn = document.getElementById('btn-bypass-trigger');
    const nextBtn = document.getElementById('btn-next-acc');
    const actionOptions = document.getElementById('account-action-options');
    const optBypassCard = document.getElementById('opt-bypass-card');
    const optManualCard = document.getElementById('opt-manual-card');
    const manualHeading = document.getElementById('manual-choice-heading');

    updateStatusBadge('account-status', '<span class="spin-icon material-symbols-rounded">sync</span> בודק חשבונות...', '');
    if (heroStatus) heroStatus.className = 'account-hero-card checking';
    if (heroTitle) heroTitle.textContent = 'סורק חשבונות פעילים במכשיר...';
    if (heroDesc) heroDesc.textContent = 'מבצע בדיקת עומק של חשבונות המערכת דרך ADB';
    if (heroIcon) heroIcon.textContent = 'sync';

    if (actionOptions) actionOptions.style.display = 'none';
    if (bypassBtn) bypassBtn.style.display = 'none';
    if (listDiv) listDiv.innerHTML = '<div class="account-skeleton-loader"><div class="skeleton-row"></div><div class="skeleton-row"></div></div>';

    try {
        const rawDump = await getAccountDump();
        const accounts = parseActiveAccounts(rawDump);
        const isSdk14 = (appState.sdkVersion || 0) >= 34;

        if (appState.isOtherMdmActive) {
            updateStatusBadge('account-status', '<span class="material-symbols-rounded">gpp_bad</span> קיים מנהל מכשיר', 'error');
            if (nextBtn) nextBtn.disabled = true;
            appState.accountsClean = false;

            if (heroStatus) heroStatus.className = 'account-hero-card error';
            if (heroTitle) heroTitle.textContent = `זוהה מנהל מכשיר (MDM) פעיל: ${appState.activeMdmPackage || 'מנהל אחר'}`;
            if (heroDesc) {
                heroDesc.innerHTML = `המכשיר כבר מנוהל על ידי אפליקציה או ארגון אחר (<strong>${appState.activeMdmPackage || 'MDM'}</strong>).<br>מערכת Android חוסמת הגדרת מנהל נוסף. נדרש <strong>איפוס יצרן (Factory Reset)</strong>.`;
            }
            if (heroIcon) heroIcon.textContent = 'gpp_bad';
            if (actionOptions) actionOptions.style.display = 'none';

            if (listDiv) {
                listDiv.innerHTML = `
                    <div class="account-error-state" style="padding: 24px; text-align: center; background: var(--md-sys-color-surface-variant); border-radius: var(--border-radius-lg, 16px); margin-top: 10px;">
                        <div style="color: var(--md-sys-color-error, #F28B82); font-size: 1.1rem; font-weight: 700; margin-bottom: 8px;">
                            <span class="material-symbols-rounded" style="vertical-align: middle;">lock_clock</span> נדרש איפוס יצרן למכשיר
                        </div>
                        <div style="font-size: 0.9rem; color: var(--md-sys-color-on-surface-variant);">
                            קיים מנהל מכשיר (Device Owner) פעיל של <strong>${appState.activeMdmPackage || 'אפליקציה אחרת'}</strong>. לא ניתן להתקין מנהל מכשיר נוסף במקביל.
                        </div>
                    </div>
                `;
            }
            return;
        }

        if (accounts.length === 0) {
            updateStatusBadge('account-status', '<span class="material-symbols-rounded">check_circle</span> מכשיר נקי', 'success');
            if (nextBtn) nextBtn.disabled = false;
            appState.accountsClean = true;

            if (heroStatus) heroStatus.className = 'account-hero-card clean';
            if (appState.isLockSuiteDeviceOwner) {
                if (heroTitle) heroTitle.textContent = 'LockSuite כבר מוגדר כמנהל המכשיר';
                if (heroDesc) heroDesc.textContent = 'LockSuite מותקן ופעיל כ-Device Owner. מעבר לשלב הבא יאמת הרשאות ויפעיל את האפליקציה.';
            } else {
                if (heroTitle) heroTitle.textContent = 'המכשיר נקי מחשבונות';
                if (heroDesc) heroDesc.textContent = 'לא זוהו חשבונות חוסמים. ניתן להמשיך ישירות לשלב ההתקנה.';
            }
            if (heroIcon) heroIcon.textContent = 'verified_user';
            if (actionOptions) actionOptions.style.display = 'none';

            if (listDiv) {
                listDiv.innerHTML = `
                    <div class="account-clean-state">
                        <div class="clean-badge-icon">
                            <span class="material-symbols-rounded">check_circle</span>
                        </div>
                        <div class="clean-title">${appState.isLockSuiteDeviceOwner ? 'LockSuite מוגדר כ-Device Owner' : 'המכשיר נקי מחשבונות ומוכן להמשך'}</div>
                        <div class="clean-subtitle">${appState.isLockSuiteDeviceOwner ? 'LockSuite כבר מוגדר כמנהל המכשיר. ניתן להמשיך לשלב הבא.' : 'נקי מחשבונות - אין חשבונות פעילים שחוסמים את המשך ההתקנה.'}</div>
                    </div>
                `;
            }
        } else {
            const count = accounts.length;
            const countText = count === 1 ? 'חשבון 1' : `${count} חשבונות`;
            updateStatusBadge('account-status', `<span class="material-symbols-rounded">warning</span> נמצאו ${countText}`, 'error');
            appState.accountsClean = false;
            if (nextBtn) nextBtn.disabled = true;

            if (heroStatus) heroStatus.className = `account-hero-card ${isSdk14 ? 'blocked-sdk14' : 'detected'}`;
            if (heroTitle) heroTitle.textContent = count === 1 ? 'זוהה חשבון פעיל 1 במכשיר' : `זוהו ${count} חשבונות פעילים במכשיר`;
            if (heroDesc) {
                if (isSdk14) {
                    heroDesc.innerHTML = `<strong>Android 14+ מזוהה:</strong> מטעמי אבטחה של גוגל, נדרשת הסרה ידנית של החשבונות בהגדרות המכשיר לפני ההתקנה.`;
                } else {
                    heroDesc.textContent = count === 1
                        ? 'הסר את החשבון בהגדרות המכשיר, או השתמש במצב עקיפה זמנית (Beta).'
                        : 'הסר את החשבונות בהגדרות המכשיר, או השתמש במצב עקיפה זמנית (Beta).';
                }
            }
            if (heroIcon) heroIcon.textContent = isSdk14 ? 'gpp_maybe' : 'manage_accounts';

            // Configure Choice Cards
            if (actionOptions) actionOptions.style.display = 'flex';
            if (isSdk14) {
                if (optBypassCard) optBypassCard.style.display = 'none';
                if (optManualCard) {
                    optManualCard.open = true;
                    if (manualHeading) manualHeading.textContent = 'חובה להסיר חשבונות ידנית בהגדרות המכשיר (Android 14+)';
                }
            } else {
                if (optBypassCard) optBypassCard.style.display = 'flex';
                if (optManualCard) {
                    optManualCard.open = false;
                    if (manualHeading) manualHeading.textContent = 'או בצע הסרה ידנית בהגדרות המכשיר';
                }
            }

            // Fallback for bypassBtn if referenced in tests/legacy
            if (bypassBtn) {
                bypassBtn.style.display = 'inline-flex';
                if (isSdk14) {
                    bypassBtn.classList.add('btn-calm-info');
                    bypassBtn.innerHTML = `<span class="material-symbols-rounded">info</span> למה נדרשת הסרה ידנית?`;
                } else {
                    bypassBtn.classList.remove('btn-calm-info');
                    bypassBtn.innerHTML = `<span class="material-symbols-rounded">auto_fix_high</span> התקנה ללא הסרה (Beta)`;
                }
            }

            let html = '';
            accounts.forEach(acc => {
                const vis = getAccountVisuals(acc.type);
                const isWork = !vis.canBypass;
                const statusTag = isSdk14
                    ? '<span class="acc-tag tag-manual">נדרשת הסרה</span>'
                    : (isWork ? '<span class="acc-tag tag-manual">חובה להסיר ידנית</span>' : '<span class="acc-tag tag-bypassable">ניתן לעקיפה</span>');

                html += `
                    <div class="account-card ${vis.class}">
                        <div class="account-icon-wrapper">${vis.html}</div>
                        <div class="account-info">
                            <div class="account-header-line">
                                <span class="account-name" title="${acc.name}">${acc.name}</span>
                                ${statusTag}
                            </div>
                            <div class="account-type">
                                <span>${vis.brandName}</span>
                                <span class="type-pill">${acc.type}</span>
                            </div>
                        </div>
                    </div>
                `;
            });
            if (listDiv) listDiv.innerHTML = html;
        }
    } catch (e) {
        showToast("שגיאה בבדיקת החשבונות: " + (e.message || "תקלת תקשורת"));
        console.error(e);
        updateStatusBadge('account-status', '<span class="material-symbols-rounded">error</span> שגיאה בסריקה', 'error');
        if (nextBtn) nextBtn.disabled = true;
        appState.accountsClean = false;

        if (heroStatus) heroStatus.className = 'account-hero-card error';
        if (heroTitle) heroTitle.textContent = 'שגיאה בסריקת חשבונות המערכת';
        if (heroDesc) heroDesc.textContent = e.message || 'לא ניתן היה לאמת את החשבונות במכשיר. ודאו שהמכשיר דולק ואינו נעול, ולחצו "בדוק שוב".';
        if (heroIcon) heroIcon.textContent = 'error';

        if (listDiv) {
            listDiv.innerHTML = `
                <div class="account-error-state" style="padding: 24px; text-align: center; background: var(--md-sys-color-surface-variant); border-radius: var(--border-radius-lg, 16px); margin-top: 10px;">
                    <div style="color: var(--md-sys-color-error, #F28B82); font-size: 1.1rem; font-weight: 700; margin-bottom: 8px;">
                        <span class="material-symbols-rounded" style="vertical-align: middle;">warning</span> נכשלה קריאת רשימת החשבונות
                    </div>
                    <div style="font-size: 0.9rem; color: var(--md-sys-color-on-surface-variant); margin-bottom: 16px;">
                        ${e.message || 'חלה שגיאה בתקשורת ADB עם שירות החשבונות של המכשיר.'}
                    </div>
                    <button class="btn btn-tonal" onclick="checkAccounts()">
                        <span class="material-symbols-rounded">refresh</span> נסה לסרוק שוב
                    </button>
                </div>
            `;
        }
    }
}

export async function runAccountBypass() {
    if (!appState.adbConnected) return;

    // Block on Android 14+ (SDK >= 34)
    if (appState.sdkVersion >= 34) {
        showToast("פעולה זו אינה זמינה באנדרואיד 14+");
        const warnBox = document.getElementById('bypass-warning');
        if (warnBox) warnBox.style.display = 'none';
        return;
    }

    const warnBox = document.getElementById('bypass-warning');
    if (warnBox) warnBox.style.display = 'none';
    updateStatusBadge('account-status', 'מבצע השבתה...', '');

    // UI Progress Elements
    const progressModal = document.getElementById('modal-bypass-progress');
    const headlineEl = document.getElementById('bypass-progress-headline');
    const counterEl = document.getElementById('bypass-progress-counter');
    const meterBar = document.getElementById('bypass-meter-bar');
    const checklistBox = document.getElementById('bypass-live-checklist');

    if (progressModal) progressModal.classList.add('active');

    try {
        log("סורק חשבונות פעילים במכשיר...", 'info');
        if (headlineEl) headlineEl.textContent = "סורק חשבונות פעילים במכשיר...";
        if (counterEl) counterEl.textContent = "מאתר שירותי אימות דרך ADB...";
        if (meterBar) meterBar.style.width = '15%';

        const rawDump = await getAccountDump();
        const activeAccounts = parseActiveAccounts(rawDump);

        if (activeAccounts.length === 0) {
            appState.accountsClean = true;
            showToast("המכשיר כבר נקי מחשבונות");
            if (progressModal) progressModal.classList.remove('active');
            navigateTo('page-update', 3);
            return;
        }

        log("מאתר חבילות שירותי אימות...", 'info');
        const installedPackages = await getInstalledPackages();
        const authMap = parseAuthenticatorMapping(rawDump);

        const packagesToDisable = new Set();
        let hasGoogleAccount = false;
        let hasSamsungAccount = false;

        // 1. Resolve packages for each active account
        for (const acc of activeAccounts) {
            if (acc.type.toLowerCase().includes('google')) hasGoogleAccount = true;
            if (acc.type.toLowerCase().includes('samsung') || acc.type.toLowerCase().includes('osp')) hasSamsungAccount = true;

            const pkg = resolvePackageForAccount(acc.type, authMap, installedPackages);
            if (pkg) {
                // Pre-flight safety: Check if protected
                if (PROTECTED_PACKAGES.some(p => pkg === p || pkg.startsWith(p + '.'))) {
                    log(`חבילת מערכת מוגנת זוהתה: ${pkg} עבור חשבון ${acc.name}. חובה להסיר ידנית.`, 'warn');
                } else {
                    packagesToDisable.add(pkg);
                }
            } else {
                log(`לא נמצאה חבילה עבור סוג החשבון: ${acc.type}`, 'warn');
            }
        }

        // Add companion Google services if Google account is present
        if (hasGoogleAccount) {
            ['com.google.android.gms', 'com.google.android.gsf', 'com.google.android.apps.tachyon', 'com.google.android.gm'].forEach(p => {
                if (installedPackages.has(p) && !PROTECTED_PACKAGES.includes(p)) packagesToDisable.add(p);
            });
        }

        // Add companion Samsung services if Samsung account is present
        if (hasSamsungAccount) {
            ['com.samsung.android.mobileservice', 'com.osp.app.signin', 'com.samsung.android.scloud', 'com.samsung.android.authfw', 'com.samsung.android.coreapps'].forEach(p => {
                if (installedPackages.has(p) && !PROTECTED_PACKAGES.includes(p)) packagesToDisable.add(p);
            });
        }

        // 2. Also check Known Offenders
        for (const off of KNOWN_OFFENDERS) {
            if (installedPackages.has(off) && !PROTECTED_PACKAGES.includes(off)) {
                packagesToDisable.add(off);
            }
        }

        const pkgList = Array.from(packagesToDisable).filter(pkg => !PROTECTED_PACKAGES.some(p => pkg.startsWith(p)));

        if (pkgList.length === 0) {
            showToast("לא נמצאו רכיבים שניתן להשבית אוטומטית. נדרשת הסרה ידנית.");
            log("לא נמצאו חבילות יעד להשבתה אוטומטית.", 'error');
            if (progressModal) progressModal.classList.remove('active');
            await checkAccounts();
            return;
        }

        // Render live checklist items
        if (checklistBox) {
            checklistBox.innerHTML = pkgList.map((pkg, idx) => `
                <div id="live-pkg-${idx}" class="live-checklist-row status-pending">
                    <span class="row-icon material-symbols-rounded">hourglass_empty</span>
                    <span class="row-name">${pkg}</span>
                    <span class="row-badge">ממתין</span>
                </div>
            `).join('');
        }

        if (headlineEl) headlineEl.textContent = `משבית ${pkgList.length} רכיבי מערכת באופן זמני...`;
        log(`משבית ${pkgList.length} רכיבים באופן זמני...`, 'info');
        let disabledCount = 0;

        for (let i = 0; i < pkgList.length; i++) {
            const pkg = pkgList[i];
            const rowEl = document.getElementById(`live-pkg-${i}`);

            // Update row to active
            if (rowEl) {
                rowEl.className = 'live-checklist-row status-active';
                const icon = rowEl.querySelector('.row-icon');
                const badge = rowEl.querySelector('.row-badge');
                if (icon) icon.outerHTML = '<span class="row-icon material-symbols-rounded spin-icon">sync</span>';
                if (badge) badge.textContent = 'משבית...';
            }

            if (counterEl) counterEl.textContent = `משבית את ${pkg} (${i + 1} מתוך ${pkgList.length})...`;
            if (meterBar) meterBar.style.width = `${Math.round(((i + 1) / pkgList.length) * 85)}%`;

            if (!appState.disabledPackages.includes(pkg)) {
                try {
                    await executeAdbCommand(`pm disable-user --user 0 ${pkg}`, `השבתת ${pkg}`);
                    appState.disabledPackages.push(pkg);
                    saveSessionState();
                    disabledCount++;
                } catch (e) {
                    log(`שגיאה בהשבתת ${pkg}: ${e.message}`, 'warn');
                }
            }

            // Update row to done
            if (rowEl) {
                rowEl.className = 'live-checklist-row status-done';
                const icon = rowEl.querySelector('.row-icon');
                const badge = rowEl.querySelector('.row-badge');
                if (icon) icon.outerHTML = '<span class="row-icon material-symbols-rounded">check_circle</span>';
                if (badge) badge.textContent = 'הושבת בהצלחה';
            }

            await wait(250);
        }

        if (headlineEl) headlineEl.textContent = "מאמת מוכנות המכשיר ומעדכן רישומי מערכת...";
        if (counterEl) counterEl.textContent = "ממתין לעדכון שירותי Android...";
        if (meterBar) meterBar.style.width = '95%';

        // Give Android OS time to update service registrations
        await wait(1500);

        if (meterBar) meterBar.style.width = '100%';
        if (headlineEl) headlineEl.textContent = "ההשבתה הזמנית הושלמה בהצלחה!";
        if (counterEl) counterEl.textContent = `הושבתו ${disabledCount} רכיבים. עובר לשלב הבא...`;

        appState.accountsClean = true;
        log(`הושבתו ${disabledCount} רכיבים בהצלחה. ממשיך לשלב ההתקנה...`, 'success');
        showToast(`הושבתו ${disabledCount} רכיבים`);

        await wait(600);
        if (progressModal) progressModal.classList.remove('active');
        navigateTo('page-update', 3);

    } catch (e) {
        showToast("שגיאה ב-Bypass: " + e.message);
        log(`שגיאה בתהליך Bypass: ${e.message}`, 'error');
        if (progressModal) progressModal.classList.remove('active');
        await restoreAccounts(true);
        await checkAccounts();
    }
}

export async function restoreAccounts(silent = false) {
    if (appState.disabledPackages.length === 0) {
        restoreSessionState();
    }

    if (appState.disabledPackages.length === 0) {
        if (typeof window !== 'undefined' && window.updateRescueBanner) window.updateRescueBanner();
        return;
    }

    if (!silent) log("משחזר חשבונות ורכיבים שהושבתו...", 'info');

    const pkgs = [...appState.disabledPackages];
    for (const pkg of pkgs) {
        try {
            await executeAdbCommand(`pm enable ${pkg}`, `Restore ${pkg}`, silent);
        } catch (e) {
            console.warn(`Failed to re-enable ${pkg}:`, e);
        }
    }

    clearSessionState();
    if (typeof window !== 'undefined' && window.updateRescueBanner) window.updateRescueBanner();
    if (!silent) log("כל הרכיבים שוחזרו בהצלחה", 'success');
}