export const CONFIG = {
    ENABLE_WEB_UPDATE: false,
    GITHUB_USERNAME: "israel",
    GITHUB_REPO_NAME: "LockSuite",
    FALLBACK_GITHUB_USERNAME: "LockSuite",
    INSTALLER_REPO_OWNER: "LockSuite",
    INSTALLER_REPO_NAME: "locksuite-installer",
    TARGET_PACKAGE: "com.ejemplo.locksuite",
    DEVICE_ADMIN: ".receiver.DeviceAdminReceiver",
    ACCESSIBILITY_SERVICE: "com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService",
    APK_LOCAL_PATH: "../locksuite-latest.apk",
    APK_FETCH_TIMEOUT_MS: 8000,
    ADB_DEFAULT_TIMEOUT_MS: 15000,
    ADB_HEARTBEAT_INTERVAL_MS: 4000,
    APK_FALLBACK_URLS: [
        "../locksuite-latest.apk",
        "../LockSuite_MDM.apk",
        "/locksuite-latest.apk",
        "https://locksuite-nueva.web.app/locksuite-latest.apk"
    ]
};

// Packages that must NEVER be disabled
export const PROTECTED_PACKAGES = [
    'com.android.settings',      
    'com.android.systemui',      
    'android',                   
    'com.google.android.setupwizard',
    'com.android.phone',
    'com.android.providers.telephony',
    CONFIG.TARGET_PACKAGE               
];

// Apps to check for explicitly
export const KNOWN_OFFENDERS = [
    'com.facebook.katana',
    'com.facebook.orca',
    'com.instagram.android',
    'com.whatsapp',
    'com.microsoft.office.outlook',
    'com.google.android.gm',
    'com.samsung.android.email.provider'
];

// Static fallback mapping for account types when dynamic discovery isn't available
export const ACCOUNT_PKG_MAP = {
    // Google
    'com.google': 'com.google.android.gms', 
    'com.google.work': 'com.google.android.gms',
    'com.google.android.gm.pop3': 'com.google.android.gm',
    'com.google.android.gm.exchange': 'com.google.android.gm',
    'com.google.android.gm.legacyimap': 'com.google.android.gm',
    'com.google.android.apps.tachyon': 'com.google.android.apps.tachyon',
    // Samsung
    'com.osp.app.signin': 'com.samsung.android.mobileservice', 
    'com.samsung.android.mobileservice': 'com.samsung.android.mobileservice',
    'com.samsung.android.scloud': 'com.samsung.android.scloud',
    'com.samsung.android.email': 'com.samsung.android.email.provider',
    'com.samsung.android.email.provider': 'com.samsung.android.email.provider',
    // Xiaomi / MIUI
    'com.xiaomi': 'com.xiaomi.account',
    'com.xiaomi.account': 'com.xiaomi.account',
    'com.miui.cloudservice': 'com.miui.cloudservice',
    // Huawei / Honor
    'com.huawei.hwid': 'com.huawei.hwid',
    // Microsoft
    'com.microsoft.office.outlook': 'com.microsoft.office.outlook',
    'com.microsoft.workaccount': 'com.azure.authenticator',
    'com.microsoft.skydrive': 'com.microsoft.skydrive',
    'com.microsoft.teams': 'com.microsoft.teams',
    // Messaging & Social
    'com.whatsapp': 'com.whatsapp',
    'com.whatsapp.w4b': 'com.whatsapp.w4b',
    'com.facebook.auth.login': 'com.facebook.katana',
    'com.facebook.messenger': 'com.facebook.orca',
    'com.instagram.android': 'com.instagram.android',
    'us.zoom.videomeetings': 'us.zoom.videomeetings',
    'org.telegram.messenger': 'org.telegram.messenger',
    'org.telegram.plus': 'org.telegram.plus',
    'org.thunderdog.challegram': 'org.thunderdog.challegram',
    'com.viber.voip': 'com.viber.voip',
    'com.skype.raider': 'com.skype.raider',
    'com.snapchat.android': 'com.snapchat.android',
    'com.twitter.android': 'com.twitter.android',
    'com.spotify.music': 'com.spotify.music',
    'com.duolingo': 'com.duolingo'
};

export const ADB_ERRORS = {
    "INSTALL_FAILED_ALREADY_EXISTS": "האפליקציה כבר מותקנת. מנסה לעדכן...",
    "INSTALL_FAILED_INSUFFICIENT_STORAGE": "אין מספיק מקום פנוי במכשיר.",
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE": "קיימת גרסה קודמת עם חתימה שונה. יש למחוק אותה ידנית.",
    "Permission denied": "אין הרשאה לביצוע הפעולה. וודא שאישרת 'ניפוי באגים' במכשיר.",
    "device unauthorized": "המכשיר ממתין לאישור. הדליקו את מסך המכשיר, סמנו 'אפשר תמיד ממחשב זה' ואשרו.",
    "device offline": "המכשיר לא מקוון. נתקו וחברו מחדש את כבל ה-USB.",
    "not found": "המכשיר התנתק. בדקו את תקינות כבל ה-USB.",
    "device not found": "המכשיר לא זוהה. חברו את המכשיר מחדש.",
    "closed": "ערוץ התקשורת עם המכשיר נסגר.",
    "there are already some accounts": "שגיאה: נמצאו חשבונות פעילים במכשיר. חובה להסירם לפני ההתקנה.",
    "already a device owner": "שגיאה: כבר קיים מנהל מכשיר (Device Owner). יש לבצע איפוס יצרן.",
    "java.lang.IllegalStateException": "שגיאה קריטית (IllegalStateException). חלה תקלה בעת הגדרת ניהול המכשיר.",
    "java.lang.SecurityException": "מערכת Android חסמה את הפקודה מטעמי אבטחה או היעדר הרשאות.",
    "Trying to set the device owner": "שגיאה: הגדרת הבעלים נכשלה. המכשיר אינו 'נקי' מחשבונות.",
    "Can't find service": "שירות מערכת באנדרואיד אינו מגיב (יתכן שהמכשיר מופעל מחדש או שהמסך נעול).",
    "DeadObjectException": "שירות המערכת באנדרואיד קרס במהלך הביצוע."
};