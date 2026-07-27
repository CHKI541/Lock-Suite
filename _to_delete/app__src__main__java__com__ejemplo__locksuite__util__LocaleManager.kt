package com.ejemplo.locksuite.util

import android.content.Context

object LocaleManager {
    private var lang = "es"

    fun init(context: Context) {
        lang = context.getSharedPreferences("locale_prefs", Context.MODE_PRIVATE)
            .getString("app_lang", "es") ?: "es"
    }

    fun getLang(): String = lang

    fun setLang(context: Context, newLang: String) {
        lang = newLang
        context.getSharedPreferences("locale_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("app_lang", newLang)
            .apply()
    }

    private val translations = mapOf(
        "app_name" to mapOf("es" to "LockSuite MDM", "en" to "LockSuite MDM", "he" to "LockSuite MDM"),
        "active_protection" to mapOf("es" to "Protección Activa", "en" to "Active Protection", "he" to "הגנה פעילה"),
        "enter_pin" to mapOf("es" to "Ingrese PIN de Administrador", "en" to "Enter Administrator PIN", "he" to "נא להזין קוד מנהל"),
        "incorrect_pin" to mapOf("es" to "PIN incorrecto. Intentos restantes: ", "en" to "Incorrect PIN. Remaining attempts: ", "he" to "קוד שגוי. ניסיונות נותרים: "),
        "device_locked_5m" to mapOf("es" to "Dispositivo bloqueado por 5 minutos", "en" to "Device blocked for 5 minutes", "he" to "המכשיר חסום ל-5 דקות"),
        "try_again_in" to mapOf("es" to "Intente nuevamente en ", "en" to "Try again in ", "he" to "נא לנסות שוב בעוד "),
        "entry_blocked" to mapOf("es" to "Entrada bloqueada por seguridad", "en" to "Entry blocked for security", "he" to "הכניסה חסומה מטעמי אבטחה"),
        "settings" to mapOf("es" to "Configuración", "en" to "Settings", "he" to "הגדרות"),
        "back" to mapOf("es" to "Volver", "en" to "Back", "he" to "חזור"),
        
        // Tabs
        "Políticas" to mapOf("es" to "Políticas", "en" to "Policies", "he" to "מדיניות"),
        "Aplicaciones" to mapOf("es" to "Aplicaciones", "en" to "Applications", "he" to "אפליקציες"),
        "Servicios" to mapOf("es" to "Servicios", "en" to "Services", "he" to "שירותים"),

        // Filters
        "Todas" to mapOf("es" to "Todas", "en" to "All", "he" to "הכל"),
        "Usuario" to mapOf("es" to "Usuario", "en" to "User", "he" to "משתמש"),
        "Bloqueadas" to mapOf("es" to "Bloqueadas", "en" to "Blocked", "he" to "חסומות"),
        "Sistema" to mapOf("es" to "Sistema", "en" to "System", "he" to "מערכת"),
        "Preinstaladas" to mapOf("es" to "Preinstaladas", "en" to "Preinstalled", "he" to "מותקנות מראש"),

        // Policy Groups
        "Políticas de Sistema (Device Owner)" to mapOf("es" to "Políticas de Sistema (Device Owner)", "en" to "System Policies (Device Owner)", "he" to "מדיניות מערכת (Device Owner)"),
        "Control de Hardware y Pantalla" to mapOf("es" to "Control de Hardware y Pantalla", "en" to "Hardware & Screen Control", "he" to "שליטת חומרה ומסך"),
        "Control de Conectividad" to mapOf("es" to "Control de Conectividad", "en" to "Connectivity Control", "he" to "שליטת חיבורים"),

        // Policy switch rows
        "Bloquear Restauración de Fábrica" to mapOf("es" to "Bloquear Restauración de Fábrica", "en" to "Block Factory Reset", "he" to "חסום איפוס יצרן"),
        "Bloquear Instalación de Apps" to mapOf("es" to "Bloquear Instalación de Apps", "en" to "Block App Installations", "he" to "חסום התקנת אפליקציες"),
        "Bloquear Desinstalación de Apps" to mapOf("es" to "Bloquear Desinstalación de Apps", "en" to "Block App Uninstallations", "he" to "חסום הסרת אפליקציες"),
        "Bloquear ADB y Opciones de Desarrollador" to mapOf("es" to "Bloquear ADB y Opciones de Desarrollador", "en" to "Block ADB & Developer Options", "he" to "חסום ADB ואפשרויות מפתח"),
        "Bloquear Cambio de Usuario" to mapOf("es" to "Bloquear Cambio de Usuario", "en" to "Block User Switching", "he" to "חסום החלפת משתמש"),
        "Bloquear Modificación de Cuentas" to mapOf("es" to "Bloquear Modificación de Cuentas", "en" to "Block Account Modification", "he" to "חסום שינוי חשבונות"),
        "Bloquear Reinicio Seguro (Safe Boot)" to mapOf("es" to "Bloquear Reinicio Seguro (Safe Boot)", "en" to "Block Safe Boot", "he" to "חסום אתחול בטוח (Safe Boot)"),
        "Bloquear Orígenes Desconocidos (APK)" to mapOf("es" to "Bloquear Orígenes Desconocidos (APK)", "en" to "Block Unknown Sources (APK)", "he" to "חסום מקורות לא ידועים (APK)"),
        "Bloquear Ajustes de Red / WiFi / Datos" to mapOf("es" to "Bloquear Ajustes de Red / WiFi / Datos", "en" to "Block Network / WiFi / Data Settings", "he" to "חסום הגדרות רשת / WiFi / נתונים"),
        "Deshabilitar Cámara Física" to mapOf("es" to "Deshabilitar Cámara Física", "en" to "Disable Physical Camera", "he" to "השבת מצלמה פיזית"),
        "Bloquear Capturas de Pantalla (Screenshots)" to mapOf("es" to "Bloquear Capturas de Pantalla (Screenshots)", "en" to "Block Screen Captures (Screenshots)", "he" to "חסום צילום מסך (Screenshots)"),
        "Bloquear Barra de Notificaciones (Android 9+)" to mapOf("es" to "Bloquear Barra de Notificaciones (Android 9+)", "en" to "Block Status/Notification Bar (Android 9+)", "he" to "חסום שורת מצב/התראות (Android 9+)"),
        "Deshabilitar Pantalla de Bloqueo (Keyguard)" to mapOf("es" to "Deshabilitar Pantalla de Bloqueo (Keyguard)", "en" to "Disable Lock Screen (Keyguard)", "he" to "השבת מסך נעילה (Keyguard)"),
        "Bloquear Ajustes de Volumen" to mapOf("es" to "Bloquear Ajustes de Volumen", "en" to "Block Volume Settings", "he" to "חסום הגדרות עוצמת קול"),
        "Bloquear Controles de Aplicación (Ajustes)" to mapOf("es" to "Bloquear Controles de Aplicación (Ajustes)", "en" to "Block App Info / Settings Controls", "he" to "חסום פרטי אפליקציה / הגדרות"),
        "Bloquear Bluetooth" to mapOf("es" to "Bloquear Bluetooth", "en" to "Block Bluetooth", "he" to "חסום בלוטות'"),
        "Bloquear Envío de Archivos Bluetooth" to mapOf("es" to "Bloquear Envío de Archivos Bluetooth", "en" to "Block Bluetooth File Sharing", "he" to "חסום שיתוף קבצים בבלוטות'"),
        "Bloquear Medios Externos (USB OTG/SD)" to mapOf("es" to "Bloquear Medios Externos (USB OTG/SD)", "en" to "Block External Media (USB OTG/SD)", "he" to "חסום מדיה חיצונית (USB OTG/SD)"),
        "Bloquear Zona WiFi / Compartir Internet" to mapOf("es" to "Bloquear Zona WiFi / Compartir Internet", "en" to "Block Hotspot / Tethering", "he" to "חסום נקודה חמה / שיתוף אינטרנט"),
        "Bloquear Configuración de VPN" to mapOf("es" to "Bloquear Configuración de VPN", "en" to "Block VPN Settings", "he" to "חסום הגדרות VPN"),
        "Bloquear Internet Completo (WiFi y Datos)" to mapOf("es" to "Bloquear Internet Completo (WiFi y Datos)", "en" to "Block All Internet (WiFi & Mobile)", "he" to "חסום אינטרנט מלא (WiFi ונתונים)"),
        "Deshabilitar Navegadores de Internet (Chrome, Firefox, etc.)" to mapOf("es" to "Deshabilitar Navegadores de Internet (Chrome, Firefox, etc.)", "en" to "Disable Web Browsers (Chrome, Firefox, etc.)", "he" to "השבת דפדפני אינטרנט (Chrome, Firefox וכו')"),
        "Deshabilitar WebView del Sistema (Bloqueo Global)" to mapOf("es" to "Deshabilitar WebView del Sistema (Bloqueo Global)", "en" to "Disable System WebView (Global)", "he" to "השבת WebView של המערכת (חסום גלובלית)"),
        "Bloquear Anuncios en todo el Dispositivo (Global)" to mapOf("es" to "Bloquear Anuncios en todo el Dispositivo (Global)", "en" to "Block Ads System-wide (Global)", "he" to "חסום פרסומות בכל המכשיר (גלובלי)"),

        // Dialogs & topBar
        "Panel de Administración" to mapOf("es" to "Panel de Administración", "en" to "Administration Panel", "he" to "לוח ניהול"),
        "Cambiar PIN de Administrador" to mapOf("es" to "Cambiar PIN de Administrador", "en" to "Change Administrator PIN", "he" to "שנה קוד מנהל"),
        "Cerrar Sesión" to mapOf("es" to "Cerrar Sesión", "en" to "Log Out", "he" to "התנתק"),
        "Re-autenticación Requerida" to mapOf("es" to "Re-autenticación Requerida", "en" to "Re-authentication Required", "he" to "נדרש אימות מחדש"),
        "Identificación del Dispositivo" to mapOf("es" to "Identificación del Dispositivo", "en" to "Device Identification", "he" to "זיהוי המכשיר"),
        "Nombre del dispositivo" to mapOf("es" to "Nombre del dispositivo", "en" to "Device name", "he" to "שם המכשיר"),
        "Nombre guardado con éxito" to mapOf("es" to "Nombre guardado con éxito", "en" to "Name saved successfully", "he" to "השם נשמר בהצלחה"),
        "Desinstalar Aplicación" to mapOf("es" to "Desinstalar Aplicación", "en" to "Uninstall Application", "he" to "הסר אפליקציה"),

        // Status Row Labels
        "Licencia de Propietario (Device Owner)" to mapOf("es" to "Licencia de Propietario (Device Owner)", "en" to "Device Owner Status", "he" to "מצב מנהל מכשיר (Device Owner)"),
        "Servicio Watchdog (Persistencia)" to mapOf("es" to "Servicio Watchdog (Persistencia)", "en" to "Watchdog Service (Persistence)", "he" to "שירות Watchdog (התמדה)"),
        "Canal FCM de Control Remoto" to mapOf("es" to "Canal FCM de Control Remoto", "en" to "FCM Remote Control Channel", "he" to "ערוץ שליטה מרחוק FCM"),
        "Modo Stealth (Launcher Oculto)" to mapOf("es" to "Modo Stealth (Launcher Oculto)", "en" to "Stealth Mode (Hidden Launcher)", "he" to "מצב סמוי (משגר מוסתר)"),
        
        "ACTIVO (Seguridad de Sistema)" to mapOf("es" to "ACTIVO (Seguridad de Sistema)", "en" to "ACTIVE (System Security)", "he" to "פעיל (אבטחת מערכת)"),
        "ACTIVO (Servicio de Primer Plano)" to mapOf("es" to "ACTIVO (Servicio de Primer Plano)", "en" to "ACTIVE (Foreground Service)", "he" to "פעיל (שירות חזית)"),
        "LISTO (Firebase Cloud Messaging)" to mapOf("es" to "LISTO (Firebase Cloud Messaging)", "en" to "READY (Firebase Cloud Messaging)", "he" to "מוכן (Firebase Cloud Messaging)"),
        "ACTIVADO" to mapOf("es" to "ACTIVADO", "en" to "ENABLED", "he" to "מופעל"),
        "DESACTIVADO" to mapOf("es" to "DESACTIVADO", "en" to "DISABLED", "he" to "מושבת"),
        "INACTIVO" to mapOf("es" to "INACTIVO", "en" to "INACTIVE", "he" to "לא פעיל"),

        // Accessibility/Emergency
        "block_acc_title" to mapOf("es" to "Servicio de Accesibilidad Requerido", "en" to "Accessibility Service Required", "he" to "נדרש שירות נגישות"),
        "block_acc_desc" to mapOf("es" to "LockSuite MDM necesita que el Servicio de Accesibilidad esté habilitado para poder aplicar las políticas de seguridad y protección empresarial.\n\nPor favor, actívelo en Ajustes.", "en" to "LockSuite MDM needs the Accessibility Service to be enabled to apply enterprise security and protection policies.\n\nPlease enable it in Settings.", "he" to "LockSuite MDM צריך ששירות הנגישות יהיה פעיל כדי להחיל מדיניות אבטחה והגנה ארגונית.\n\nאנא הפעל אותו בהגדרות."),
        "block_acc_btn" to mapOf("es" to "Ir a Ajustes", "en" to "Go to Settings", "he" to "עבור להגדרות"),
        "emerg_title" to mapOf("es" to "MENÚ DE EMERGENCIA MDM", "en" to "MDM EMERGENCY MENU", "he" to "תפריט חירום MDM"),
        "emerg_warning" to mapOf("es" to "¡ATENCIÓN! ESTA OPERACIÓN ELIMINARÁ TODAS LAS RESTRICCIONES Y RESTABLECERÁ EL TELÉFONO DE FÁBRICA.", "en" to "WARNING! THIS OPERATION WILL REMOVE ALL RESTRICTIONS AND FACTORY RESET THE PHONE.", "he" to "אזהרה! פעולה זו תסיר את כל ההגבלות ותבצע איפוס יצרן לטלפון."),
        "emerg_pin" to mapOf("es" to "Ingrese PIN de Recuperación", "en" to "Enter Recovery PIN", "he" to "הזן קוד שחזור"),
        "emerg_wipe" to mapOf("es" to "RESTABLECER AHORA", "en" to "RESET NOW", "he" to "אפס עכשיו")
    )

    fun t(key: String): String {
        val entry = translations[key] ?: return key
        return entry[lang] ?: entry["es"] ?: key
    }
}
