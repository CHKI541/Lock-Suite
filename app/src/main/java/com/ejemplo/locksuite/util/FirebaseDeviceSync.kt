package com.ejemplo.locksuite.util

// FirebaseDeviceSync.kt — CORREGIDO
//
// Cambios respecto al original que mandaste (todo lo demás es idéntico,
// línea por línea, para que el diff sea mínimo):
//
//   1) syncDeviceInfo(): la rama "else" del fetch del token (cuando
//      task.isSuccessful es false) no existía. Si Play Services está
//      desactualizado/restringido — típico en un celular kosher bloqueado —
//      el token nunca se genera y esto quedaba en silencio absoluto, para
//      siempre, build tras build. Ahora loguea la excepción real y además
//      escribe un campo "fcmTokenError" en la DB (raíz + info) para que
//      puedas ver la causa sin conectar el celular por USB.
//
//   2) syncToken() y writeFields(): sus updateChildren() no tenían
//      addOnFailureListener. El try/catch de Kotlin NO atrapa fallos
//      asincrónicos de Firebase (ej. permission-denied de las reglas) —
//      solo excepciones lanzadas de forma síncrona. Sin esto, un rechazo
//      de las reglas de seguridad también sería silencioso.

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.ejemplo.locksuite.mdm.PolicyManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.security.SecureRandom

object FirebaseDeviceSync {

    private const val COMMAND_SECRET_KEY = "command_auth_secret"

    /**
     * Credencial aleatoria por dispositivo para autenticar comandos FCM. No se
     * incluye en la APK y queda protegida por el almacén cifrado local.
     */
    fun getOrCreateCommandSecret(context: Context): String {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        prefs.getString(COMMAND_SECRET_KEY, null)?.let { return it }
        val entropy = ByteArray(32)
        SecureRandom().nextBytes(entropy)
        return Base64.encodeToString(entropy, Base64.NO_WRAP).also { secret ->
            prefs.edit().putString(COMMAND_SECRET_KEY, secret).commit()
        }
    }

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    fun syncToken(context: Context, token: String) {
        withAuth {
            val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
            val payload = mutableMapOf<String, Any>(
                "fcmToken" to token,
                "info/fcmToken" to token,
                "lastSeen" to ServerValue.TIMESTAMP,
                "info/lastSeen" to ServerValue.TIMESTAMP
            )
            if (authUid.isNotEmpty()) {
                payload["ownerUid"] = authUid
                payload["info/ownerUid"] = authUid
            }
            ref.updateChildren(payload).addOnFailureListener { it.printStackTrace() }
        }
    }

    /**
     * Escribe únicamente el timestamp de lastSeen. Llamar al arrancar la app para
     * aparecer "En línea" en el panel web de forma casi instantánea, sin esperar a
     * que se complete la sincronización completa del estado del dispositivo.
     */
    fun syncLastSeenOnly(context: Context) {
        withAuth {
            val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
            val payload = mutableMapOf<String, Any>(
                "lastSeen" to ServerValue.TIMESTAMP,
                "info/lastSeen" to ServerValue.TIMESTAMP
            )
            if (authUid.isNotEmpty()) {
                payload["ownerUid"] = authUid
                payload["info/ownerUid"] = authUid
            }
            ref.updateChildren(payload).addOnFailureListener { it.printStackTrace() }
        }
    }

    /**
     * Publica el estado del flujo de actualización por Play Store para que el
     * panel pueda mostrarlo en vivo (y ofrecer el botón de cancelar) sin tener
     * que sincronizar el dispositivo entero en cada cambio de etapa.
     */
    fun syncUpdateFlow(context: Context) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        val running = prefs.getBoolean(UpdateFlowManager.KEY_IN_PROGRESS, false) &&
            !prefs.getString(UpdateFlowManager.KEY_PKG, null).isNullOrBlank()
        val stage = prefs.getString(UpdateFlowManager.KEY_STAGE, UpdateFlowManager.STAGE_IDLE)
            ?: UpdateFlowManager.STAGE_IDLE
        val detail = prefs.getString(UpdateFlowManager.KEY_DETAIL, null)
        val pkg = prefs.getString(UpdateFlowManager.KEY_PKG, "") ?: ""

        withAuth {
            val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
            val flow = mapOf(
                "running" to running,
                "packageName" to pkg,
                "stage" to stage,
                "statusText" to UpdateFlowManager.stageLabel(stage, detail),
                "source" to (prefs.getString(UpdateFlowManager.KEY_SOURCE, "") ?: ""),
                "cancelable" to prefs.getBoolean(UpdateFlowManager.KEY_CANCELABLE, true),
                "startedAt" to prefs.getLong(UpdateFlowManager.KEY_STARTED_AT, 0L),
                "lastResult" to (prefs.getString(UpdateFlowManager.KEY_LAST_RESULT, "") ?: ""),
                "lastResultPackage" to (prefs.getString(UpdateFlowManager.KEY_LAST_RESULT_PKG, "") ?: ""),
                "lastResultAt" to prefs.getLong(UpdateFlowManager.KEY_LAST_RESULT_AT, 0L),
                "debugLabels" to (prefs.getString(UpdateFlowManager.KEY_DEBUG_LABELS, "") ?: "")
            )
            val payload = mutableMapOf<String, Any>(
                "updateFlow" to flow,
                "info/updateFlow" to flow,
                "lastSeen" to ServerValue.TIMESTAMP
            )
            if (authUid.isNotEmpty()) payload["ownerUid"] = authUid
            ref.updateChildren(payload).addOnFailureListener { it.printStackTrace() }
        }
    }

    /**
     * Sincroniza el código de recuperación de emergencia EN TEXTO PLANO a
     * deviceSecrets/{id}/recoveryCode. Solo lectura para admins autorizados
     * (ver database.rules.json) — el dispositivo verifica localmente contra
     * el hash+salt, nunca contra este valor en texto plano.
     */
    fun syncRecoveryCode(context: Context, plaintextCode: String) {
        withAuth {
            val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val secretsRef = FirebaseDatabase.getInstance().getReference("deviceSecrets/${deviceId(context)}")
            val payload = mutableMapOf<String, Any>(
                "recoveryCode" to plaintextCode
            )
            if (authUid.isNotEmpty()) {
                payload["ownerUid"] = authUid
            }
            secretsRef.updateChildren(payload).addOnFailureListener { it.printStackTrace() }
        }
    }

    fun syncPinCredentials(context: Context, pinHash: String, pinSalt: String) {
        withAuth {
            val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            // 1. Escribir credenciales reales en la ruta protegida deviceSecrets
            //
            // ⚠️ 2/9/2026 — POR QUÉ EL commandSecret VA EN SU PROPIA ESCRITURA Y NO ACÁ.
            //
            // Antes este updateChildren() mandaba {pinHash, pinSalt, commandSecret}
            // JUNTOS. Un updateChildren de Realtime Database es ATÓMICO: si las reglas
            // rechazan UNA sola de las rutas, se rechaza la escritura ENTERA. Y la regla
            // de deviceSecrets/$id/commandSecret dice, textual, que el dispositivo NO
            // puede cambiar el valor una vez que existe (solo reescribir el mismo, o un
            // admin):
            //
            //   ".write": "auth != null && (!data.exists() || newData.val() === data.val()
            //              || auth.token.admin === true || root.child('authorizedAdminsUids')...)"
            //
            // El commandSecret vive en EncryptedSharedPreferences, que NO sobrevive a
            // reinstalar la app ni a que se invalide la clave del Keystore — mientras que
            // el deviceId es el ANDROID_ID, que SÍ sobrevive. O sea: reinstalás LockSuite,
            // el equipo genera un secreto nuevo, el servidor conserva el viejo, y a partir
            // de ahí la escritura del secreto se rechaza para siempre. Con el paquete
            // atómico, ese rechazo se llevaba puesto también el pinHash y el pinSalt: el
            // panel seguía viendo el PIN VIEJO. Ese es exactamente el síntoma reportado
            // por el dueño — "a veces hay que cambiar el PIN varias veces hasta que puedo
            // controlar el equipo desde la web".
            //
            // Separadas, el PIN llega siempre; el secreto falla solo (y se reporta más
            // abajo con `commandSecretMismatch` para que el panel ofrezca re-vincular).
            val secretsRef = FirebaseDatabase.getInstance().getReference("deviceSecrets/${deviceId(context)}")
            val secretsPayload = mutableMapOf<String, Any>(
                "pinHash" to pinHash,
                "pinSalt" to pinSalt
            )
            if (authUid.isNotEmpty()) {
                secretsPayload["ownerUid"] = authUid
            }
            secretsRef.updateChildren(secretsPayload).addOnFailureListener { it.printStackTrace() }

            // Escritura separada e independiente del secreto de comandos.
            pushCommandSecret(context)

            // 2. Escribir solo la bandera hasPinConfigured en la ruta pública
            val publicRef = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
            val publicPayload = mutableMapOf<String, Any>(
                "hasPinConfigured" to true,
                "info/hasPinConfigured" to true
            )
            if (authUid.isNotEmpty()) {
                publicPayload["ownerUid"] = authUid
                publicPayload["info/ownerUid"] = authUid
            }
            publicRef.updateChildren(publicPayload).addOnFailureListener { it.printStackTrace() }
        }
    }

    /**
     * Publica el secreto de firma de comandos en su PROPIA escritura de una sola ruta, y
     * deja anotado en `devices/{id}` si el servidor lo rechazó.
     *
     * Por qué existe separado (2/9/2026): la regla de `deviceSecrets/$id/commandSecret`
     * no deja que el dispositivo cambie el valor una vez que existe. Eso es correcto como
     * defensa (que nadie que adivine un deviceId pueda re-vincularse el equipo), pero
     * tiene un efecto colateral grave: el secreto vive en EncryptedSharedPreferences, que
     * NO sobrevive a reinstalar la app ni a una invalidación del Keystore, mientras que el
     * deviceId (ANDROID_ID) SÍ sobrevive. Cuando se desincronizan, la Cloud Function firma
     * cada comando con el secreto viejo, el equipo verifica con el nuevo, y **todos los
     * comandos del panel se descartan en silencio**: el panel dice "enviado" y en el
     * celular no pasa nada.
     *
     * No se puede arreglar desde el equipo (la regla lo impide, y aflojarla abriría el
     * agujero que la regla cierra). Lo que sí se puede es DECIRLO: si la escritura falla,
     * se marca `commandSecretMismatch = true` en `devices/{id}` —una ruta que el equipo sí
     * puede escribir— y el panel muestra el aviso con el botón "Re-vincular", que borra
     * `deviceSecrets/{id}/commandSecret` (un admin autorizado sí puede) para que la próxima
     * sincronización del equipo lo vuelva a escribir con `!data.exists()`.
     */
    fun pushCommandSecret(context: Context) {
        withAuth {
            val id = deviceId(context)
            val secret = getOrCreateCommandSecret(context)
            FirebaseDatabase.getInstance()
                .getReference("deviceSecrets/$id/commandSecret")
                .setValue(secret)
                .addOnSuccessListener { markCommandSecretMismatch(context, false) }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                    markCommandSecretMismatch(context, true)
                }
        }
    }

    /**
     * Reconcilia el NOMBRE del dispositivo entre el panel y el celular.
     *
     * ⚠️ 2/9/2026 — ESTE ERA EL BUG DE "PONGO EL NOMBRE DESDE LA WEB Y NO SE REGISTRA".
     *
     * El panel escribe `devices/{id}/deviceName`. Pero `syncDeviceInfo()` incluía
     * `"deviceName" to prefs.getString("device_name", "")` en su writeFields() —o sea que
     * en la siguiente sincronización (el Watchdog las dispara todo el tiempo, y de entrada
     * al arrancar el servicio) el celular **pisaba el nombre del panel con la cadena
     * vacía**. El único lugar que traía el nombre desde Firebase era un LaunchedEffect
     * dentro de DashboardScreen, o sea que solo pasaba si alguien abría el panel local del
     * celular. En la práctica el nombre duraba segundos.
     *
     * Regla nueva, explícita: **el panel manda.** Si Firebase tiene un nombre no vacío, es
     * el bueno y el celular lo adopta en sus preferencias (para mostrarlo en su propia
     * pantalla). Solo si Firebase NO tiene nombre y el celular sí, se sube el del celular.
     * Nunca se escribe una cadena vacía sobre un nombre existente.
     */
    private fun reconcileDeviceName(context: Context) {
        withAuth {
            try {
                val id = deviceId(context)
                val prefs = PrefsHelper.getMdmPrefs(context)
                val localName = prefs.getString("device_name", "") ?: ""
                FirebaseDatabase.getInstance()
                    .getReference("devices/$id/deviceName")
                    .get()
                    .addOnSuccessListener { snap ->
                        val remoteName = snap.getValue(String::class.java) ?: ""
                        if (remoteName.isNotEmpty()) {
                            // El panel manda: adoptarlo localmente si cambió.
                            if (remoteName != localName) {
                                prefs.edit().putString("device_name", remoteName).apply()
                            }
                        } else if (localName.isNotEmpty()) {
                            // Firebase no tiene nombre todavía y el celular sí: subirlo.
                            writeFields(context, mapOf("deviceName" to localName))
                        }
                    }
                    .addOnFailureListener { it.printStackTrace() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Sube el nombre elegido EN EL CELULAR, pisando el de Firebase a propósito.
     * Lo llama únicamente el botón "Guardar" de la pantalla de identificación del
     * Dashboard local — es la única situación en que el celular tiene que ganarle al panel.
     */
    fun pushDeviceName(context: Context, name: String) {
        PrefsHelper.getMdmPrefs(context).edit().putString("device_name", name).apply()
        withAuth { writeFields(context, mapOf("deviceName" to name)) }
    }

    /** Bandera visible para el panel. Se escribe en `devices/{id}`, no en `deviceSecrets`. */
    private fun markCommandSecretMismatch(context: Context, mismatch: Boolean) {
        try {
            val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val payload = mutableMapOf<String, Any>(
                "commandSecretMismatch" to mismatch,
                "info/commandSecretMismatch" to mismatch
            )
            if (authUid.isNotEmpty()) {
                payload["ownerUid"] = authUid
                payload["info/ownerUid"] = authUid
            }
            FirebaseDatabase.getInstance()
                .getReference("devices/${deviceId(context)}")
                .updateChildren(payload)
                .addOnFailureListener { it.printStackTrace() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun syncDeviceInfo(context: Context) {
        val policyManager = PolicyManager(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val prefs = PrefsHelper.getMdmPrefs(context)

        // El nombre del dispositivo YA NO se manda dentro del writeFields() de abajo.
        // Ver reconcileDeviceName() para el porqué completo: mandarlo ahí era lo que
        // borraba el nombre puesto desde el panel.
        reconcileDeviceName(context)

        val aliasComponent = android.content.ComponentName(context, "com.ejemplo.locksuite.LauncherAlias")
        val isStealth = try {
            val state = context.packageManager.getComponentEnabledSetting(aliasComponent)
            state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            false
        }

        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        task.result?.let { token ->
                            syncToken(context, token)
                        }
                    } else {
                        // FIX #1: ver nota al principio del archivo.
                        val reason = task.exception?.message ?: "desconocido"
                        task.exception?.printStackTrace()
                        withAuth {
                            val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
                            ref.updateChildren(
                                mapOf(
                                    "fcmTokenError" to reason,
                                    "info/fcmTokenError" to reason
                                )
                            ).addOnFailureListener { it.printStackTrace() }
                        }
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val pInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val currentVersionCode = if (pInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } else {
            0
        }
        val currentVersionName = pInfo?.versionName ?: "Unknown"

        // Una sola consulta a getUserRestrictions() por sincronización: la lista se usa
        // dos veces en el reporte de abajo.
        val policyDrift = policyManager.divergentRestrictions()

        withAuth {
            // La Function usa esta credencial para firmar cada comando. No se
            // replica bajo /devices, que el panel consume habitualmente.
            // Va por pushCommandSecret() para que un rechazo de las reglas quede
            // REPORTADO (commandSecretMismatch) en vez de perderse en un stack trace:
            // ese rechazo es lo que deja el equipo sordo a los comandos del panel.
            pushCommandSecret(context)
            writeFields(
                context,
                mapOf(
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "isDeviceOwner" to dpm.isDeviceOwnerApp(context.packageName),
                    "kioskEnabled" to false,
                    "allowedAppCount" to 0,
                    "versionCode" to currentVersionCode,
                    "versionName" to currentVersionName,

                    // Las reglas DNS POR APP (bloquear WebView / bloquear internet de
                    // una app puntual) dependen de ConnectivityManager.getConnectionOwnerUid(),
                    // que existe recién desde Android 10 (API 29). En equipos con
                    // Android 7, 8 o 9 el filtro no puede saber qué app hizo cada
                    // consulta DNS, así que esas reglas NO se aplican — y hasta ahora
                    // eso pasaba en silencio: el panel mostraba el interruptor activado
                    // igual. Se reporta explícitamente para que se vea desde el panel
                    // en qué equipos esas reglas realmente rigen.
                    "perAppDnsRulesSupported" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
                    "androidSdkInt" to Build.VERSION.SDK_INT,

                    "wifiBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_WIFI),
                    "bluetoothBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_BLUETOOTH),
                    "vpnBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_VPN),
                    "installAppsBlocked" to policyManager.isInstallAppsBlocked(),
                    "uninstallAppsBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_UNINSTALL_APPS),
                    "factoryResetBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_FACTORY_RESET),
                    "flashingBlocked" to policyManager.isFlashingBlocked(),
                    "adbBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES),
                    "userSwitchBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_USER_SWITCH),
                    "modifyAccountsBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS),
                    "safeBootBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_SAFE_BOOT),
                    // 2/9/2026 — faltaba reportarla, y el perfil del panel la necesita:
                    // sin este campo, guardar un perfil desde el panel la daba por
                    // apagada y al aplicarlo la QUITABA del equipo de destino.
                    "networkResetBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_NETWORK_RESET),
                    "unknownSourcesBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES),
                    "adjustVolumeBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_ADJUST_VOLUME),
                    "appsControlBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_APPS_CONTROL),
                    "bluetoothSharingBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_BLUETOOTH_SHARING),
                    "externalMediaBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA),
                    "tetheringBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_TETHERING),

                    "cameraDisabled" to policyManager.isCameraDisabled(),
                    "screenCaptureBlocked" to policyManager.isScreenCaptureBlocked(),
                    "statusBarDisabled" to policyManager.isStatusBarDisabled(),
                    "keyguardDisabled" to policyManager.isKeyguardDisabled(),
                    "internetBlocked" to policyManager.isInternetBlocked(),
                    "adBlockingEnabled" to policyManager.isAdBlockingEnabled(),
                    "gifsBlocked" to policyManager.isGifsBlocked(),
                    "aiModeEnabled" to com.ejemplo.locksuite.mdm.ImageBlockManager.isGlobalAiEnabled(context),
                    "mapsImageBlockingEnabled" to com.ejemplo.locksuite.mdm.ImageBlockManager.isMapsImageBlockingEnabled(context),
                    "whatsappBlockStatus" to policyManager.isWhatsAppBlockStatusEnabled(),
                    "whatsappBlockChannels" to policyManager.isWhatsAppBlockChannelsEnabled(),
                    "mercadoPagoBlockOffers" to policyManager.isMercadoPagoBlockOffersEnabled(),
                    "mercadoPagoBlockOffersAccessibility" to policyManager.isMercadoPagoBlockOffersAccessibilityEnabled(),
                    "mercadoPagoBlockOffersVpn" to policyManager.isMercadoPagoBlockOffersVpnEnabled(),
                    "blockMlInMp" to policyManager.isMercadoLibreInMpBlocked(),
                    "kosherLauncherEnabled" to policyManager.isKosherLauncherEnabled(),
                    "stealthModeEnabled" to isStealth,

                    // Suspensión temporal de LockSuite: el panel muestra un
                    // interruptor con este valor y un cartel bien visible cuando
                    // está en true, para que no se olvide un equipo suspendido.
                    "locksuiteSuspended" to policyManager.isLockSuiteSuspended(),
                    "locksuiteSuspendedAt" to policyManager.getLockSuiteSuspendedAt(),
                    "accessibilityProtected" to policyManager.isAccessibilityProtectionEnabled(),

                    // Sub-interruptores de Protecciones de Accesibilidad (17/8/2026).
                    // El panel los muestra como una sección propia; sin esto los switches
                    // del panel arrancarían siempre apagados aunque en el equipo estén
                    // encendidos.
                    "accBounceSettings" to policyManager.isAccBounceSettingsEnabled(),
                    "accNag" to policyManager.isAccNagEnabled(),
                    "accSuspendAll" to policyManager.isAccSuspendAllEnabled(),
                    // Estado REAL, no la intención: dice si la suspensión de emergencia
                    // está aplicada ahora mismo. Sin esto, la única forma de saber si el
                    // interruptor hizo algo era mirar el equipo y adivinar.
                    "accEmergencySuspendActive" to prefs.getBoolean(
                        com.ejemplo.locksuite.util.AccessibilityEnforcer.KEY_EMERGENCY_ACTIVE, false
                    ),
                    // Estado en vivo del servicio de accesibilidad, medido como lo mide
                    // el reconciliador (AccessibilityManager, no la cadena de Settings).
                    "accessibilityRunning" to
                        com.ejemplo.locksuite.util.AccessibilityEnforcer.isServiceRunning(context),
                    // Bloqueo de cambio de idioma: la defensa más barata contra la
                    // evasión por cambio de idioma del sistema.
                    "localeChangeBlocked" to policyManager.isLocaleChangeBlocked(),

                    // Arranque protegido
                    "bootGateEnabled" to policyManager.isBootGateEnabled(),
                    "bootGateWaitAccessibility" to policyManager.isBootGateWaitAccessibilityEnabled(),
                    "bootGateLastResult" to com.ejemplo.locksuite.util.BootGate.lastResult(context),

                    // Bloqueo de imágenes
                    "imageStrictScroll" to policyManager.isImageBlockStrictScrollEnabled(),

                    // Kiosco real del sistema operativo (Lock Task)
                    "kioskLockTaskEnabled" to policyManager.isKioskLockTaskEnabled(),

                    // Modo teléfono de teclas
                    "nokiaKeypadMode" to policyManager.isNokiaKeypadMode(),
                    "nokiaTouchEnabled" to policyManager.isNokiaTouchEnabled(),

                    // ── Divergencia entre lo pedido y lo que el sistema TIENE puesto ──
                    // Ver PolicyManager.divergentRestrictions(). `isRestrictionEnabled()`
                    // lee la preferencia, o sea lo que LockSuite QUISO aplicar; esto compara
                    // contra `dpm.getUserRestrictions()`, que es lo que el equipo tiene de
                    // verdad. Cuando difieren, el panel mostraba el interruptor encendido
                    // sobre un equipo desprotegido, en silencio.
                    "policyDrift" to policyDrift.joinToString(","),
                    "policyDriftCount" to policyDrift.size
                )
                // Restricciones del registro declarativo (mdm/PolicySpec.kt). Se agregan
                // desde la misma lista que las aplica, así una restricción nueva aparece en
                // el panel sin tocar este archivo.
                    + policyManager.extraRestrictionsStatus()
            )
            syncAppsListInternal(context)
        }
    }

    private fun syncAppsListInternal(context: Context) {
        try {
            val appController = com.ejemplo.locksuite.mdm.AppController(context)
            val apps = appController.getUserApps(loadIcon = false)
            val appsMap = mutableMapOf<String, Any>()
            for (app in apps) {
                val safePackageName = app.packageName.replace(".", "_")
                appsMap[safePackageName] = mapOf(
                    "packageName" to app.packageName,
                    "label" to app.label,
                    "isHidden" to app.isHidden,
                    "isSuspended" to app.isSuspended,
                    "isWebViewBlocked" to app.isWebViewBlocked,
                    "isInternetBlocked" to app.isInternetBlocked,
                    "imageBlockingMode" to app.imageBlockingMode,
                    "appType" to app.appType,
                    "isCritical" to app.isCritical
                )
            }
            // 2/9/2026 — Va DENTRO de withAuth y reafirmando `ownerUid` en la misma
            // escritura. Antes eran dos setValue() sueltos, sin autenticar y sin ownerUid:
            // la regla de `devices/$id` solo los aceptaba por la rama
            // `data.child('ownerUid').val() === auth.uid`, o sea que después de que el uid
            // anónimo rotara (reinstalar la app, limpiar datos) esta escritura quedaba
            // rechazada hasta que corriera antes un writeFields() —que sí reafirma el
            // ownerUid—. Resultado visible: la lista de apps del panel se quedaba
            // congelada en la de la instalación anterior, sin ningún error a la vista.
            withAuth {
                val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                val id = deviceId(context)
                val payload = mutableMapOf<String, Any>(
                    "apps" to appsMap,
                    "info/apps" to appsMap
                )
                if (authUid.isNotEmpty()) {
                    payload["ownerUid"] = authUid
                    payload["info/ownerUid"] = authUid
                }
                FirebaseDatabase.getInstance()
                    .getReference("devices/$id")
                    .updateChildren(payload)
                    .addOnFailureListener { it.printStackTrace() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeFields(context: Context, fields: Map<String, Any>) {
        val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
        val payload = mutableMapOf<String, Any>()
        fields.forEach { (key, value) ->
            payload[key] = value
            payload["info/$key"] = value
        }
        if (authUid.isNotEmpty()) {
            payload["ownerUid"] = authUid
            payload["info/ownerUid"] = authUid
        }
        payload["lastSeen"] = ServerValue.TIMESTAMP
        payload["info/lastSeen"] = ServerValue.TIMESTAMP
        try {
            ref.updateChildren(payload)
                .addOnFailureListener { it.printStackTrace() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun withAuth(action: () -> Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            // Reutilizar sesión existente: no re-autenticar en cada ciclo
            action()
            return
        }
        // Primera vez: iniciar sesión anónima (requiere Anonymous Auth habilitado en Firebase Console)
        auth.signInAnonymously()
            .addOnSuccessListener { action() }
            .addOnFailureListener { e ->
                // Loguear el error real para facilitar el diagnóstico
                android.util.Log.e("FirebaseDeviceSync", "signInAnonymously falló: ${e.message}", e)
                // Intentar igual — si hay reglas permisivas podría funcionar sin auth
                action()
            }
    }
}
