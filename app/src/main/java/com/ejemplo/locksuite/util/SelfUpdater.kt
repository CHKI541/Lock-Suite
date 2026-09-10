package com.ejemplo.locksuite.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object SelfUpdater {
    private const val VERSION_URL = "https://locksuite-nueva.web.app/version.json"
    private const val FALLBACK_VERSION_URL = "https://locksuite-nueva.firebaseapp.com/version.json"

    suspend fun checkAndPerformUpdate(context: Context, showToasts: Boolean = false, onProgress: ((Int) -> Unit)? = null): String? {
        return withContext(Dispatchers.IO) {
            var temporaryInstallAccessPrepared = false
            var installCommitSubmitted = false
            try {
                if (showToasts) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Buscando actualizaciones de LockSuite...", Toast.LENGTH_SHORT).show()
                    }
                }

                val responseText = fetchVersionManifest()
                val json = JSONObject(responseText)
                val serverVersionCode = json.optInt("versionCode", 0)
                val apkUrl = json.optString("url", "")
                // 10/9/2026 — la mitad de B.6 que corresponde al OTA. Opcional a propósito:
                // acá Android ya exige la misma firma para reemplazar el paquete, y el OTA
                // es la vía de rescate de un equipo — un `version.json` viejo sin hash que
                // impidiera actualizar dejaría equipos sin poder recibir el arreglo de lo
                // que sea que esté roto. Mismo criterio que el techo de 120 s del arranque
                // protegido en B.16: "es preferible unos minutos sin filtrar a un ladrillo".
                // En la Tienda, en cambio, es OBLIGATORIO. Ver util/ApkChecksum.kt.
                val expectedSha256 = json.optString("sha256", "")

                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }

                if (serverVersionCode <= currentVersionCode) {
                    if (showToasts) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "LockSuite ya está actualizado (v${pInfo.versionName})", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@withContext null
                }

                if (showToasts) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Descargando actualización de LockSuite...", Toast.LENGTH_SHORT).show()
                    }
                }

                val tempFile = File(context.cacheDir, "locksuite_update.apk")
                val urlWithCacheBuster = if (apkUrl.contains("?")) "$apkUrl&t=${System.currentTimeMillis()}" else "$apkUrl?t=${System.currentTimeMillis()}"
                val apkConnection = openDownloadConnection(urlWithCacheBuster)

                if (apkConnection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext "Error al descargar APK: HTTP ${apkConnection.responseCode}"
                }

                val totalBytes = apkConnection.contentLength
                var bytesDownloaded = 0
                apkConnection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var lastReportedProgress = -1
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            if (totalBytes > 0) {
                                val progress = (bytesDownloaded * 100L / totalBytes).toInt()
                                if (progress != lastReportedProgress) {
                                    lastReportedProgress = progress
                                    onProgress?.invoke(progress)
                                }
                            }
                        }
                    }
                }

                // Igual que en la Tienda: contra el archivo ya en disco y ANTES de levantar
                // las restricciones de instalación. Acá la ausencia de hash NO bloquea
                // (ver arriba); lo que bloquea es un hash publicado que no coincide, que
                // es la única señal inequívoca de que el archivo no es el que se subió.
                com.ejemplo.locksuite.util.ApkChecksum.blockSelfUpdate(tempFile, expectedSha256)?.let { error ->
                    tempFile.delete()
                    if (showToasts) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    }
                    return@withContext error
                }

                if (showToasts) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Instalando actualización de LockSuite...", Toast.LENGTH_SHORT).show()
                    }
                }

                // Levantar restricciones ANTES de crear la sesión (Android valida al crear, no al commit)
                if (!prepareTemporaryInstallAccess(context)) {
                    return@withContext "No se pudieron preparar los permisos temporales de instalación."
                }
                temporaryInstallAccessPrepared = true

                val pm = context.packageManager
                val packageInstaller = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }

                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)
                // session.close() en finally: antes, si la copia fallaba a mitad de
                // camino, "fis"/"out"/la sesión de PackageInstaller quedaban sin
                // cerrar (saltaba directo al catch de abajo), acumulando sesiones
                // colgadas ante fallos repetidos de auto-actualización.
                try {
                    session.openWrite("COSU", 0, -1).use { out ->
                        FileInputStream(tempFile).use { fis ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                out.write(buffer, 0, bytesRead)
                            }
                            session.fsync(out)
                        }
                    }

                    val intent = Intent(context, com.ejemplo.locksuite.receiver.PackageInstallStatusReceiver::class.java)
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        intent,
                        flags
                    )

                    session.commit(pendingIntent.intentSender)
                    installCommitSubmitted = true
                } finally {
                    session.close()
                }

                tempFile.delete()
                return@withContext null
            } catch (e: Exception) {
                if (temporaryInstallAccessPrepared && !installCommitSubmitted) {
                    com.ejemplo.locksuite.mdm.PolicyManager(context).restoreInstallRestrictions()
                }
                Log.e("SelfUpdater", "Error de actualización", e)
                return@withContext "Error de actualización: ${e.message}"
            }
        }
    }

    /**
     * Descarga e instala un APK de la **Tienda administrada**, en silencio, como Device Owner.
     *
     * @param sha256 huella publicada en la entrada de `storeApps`. **Obligatoria**: sin
     *   ella no se instala. Ver `util/ApkChecksum.kt` para el porqué completo — en dos
     *   líneas: esta ruta instala paquetes que NO están instalados, así que
     *   `ApkSignatureVerifier` (B.37) no tiene contra qué comparar, y sin el hash
     *   cualquiera que pueda cambiar el contenido servido en `apkUrl` consigue ejecución
     *   silenciosa con privilegios en toda la flota.
     */
    suspend fun downloadAndInstallApk(context: Context, apkUrl: String, packageName: String, label: String, sha256: String? = null, onProgress: ((Int) -> Unit)? = null): String? {
        return withContext(Dispatchers.IO) {
            var temporaryInstallAccessPrepared = false
            var installCommitSubmitted = false
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Iniciando descarga de $label...", Toast.LENGTH_SHORT).show()
                }

                val tempFile = File(context.cacheDir, "store_${packageName}_update.apk")
                val urlWithCacheBuster = if (apkUrl.contains("?")) "$apkUrl&t=${System.currentTimeMillis()}" else "$apkUrl?t=${System.currentTimeMillis()}"
                val apkConnection = openDownloadConnection(urlWithCacheBuster)

                if (apkConnection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext "Error al descargar APK: HTTP ${apkConnection.responseCode}"
                }

                val totalBytes = apkConnection.contentLength
                var bytesDownloaded = 0
                apkConnection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var lastReportedProgress = -1
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            if (totalBytes > 0) {
                                val progress = (bytesDownloaded * 100L / totalBytes).toInt()
                                if (progress != lastReportedProgress) {
                                    lastReportedProgress = progress
                                    onProgress?.invoke(progress)
                                }
                            }
                        }
                    }
                }

                // ── B.6, POR FIN CERRADO PARA LA TIENDA (10/9/2026) ──
                //
                // Va ACÁ y no más abajo, y el orden importa: se verifica contra el archivo
                // YA EN DISCO y ANTES de levantar las restricciones de instalación y de
                // abrir la sesión de PackageInstaller. Si se verificara después, un APK que
                // no es el publicado habría llegado igual a tener el equipo con
                // DISALLOW_INSTALL_APPS levantado durante la comprobación.
                //
                // Falla CERRADO: sin sha256 publicado, no se instala. No hay interruptor
                // para saltearlo, a propósito — ver el comentario de ApkChecksum y B.31
                // (A Bloq tiene este mismo código con un `return true // TEMPORARILY
                // BYPASS` adentro: una verificación que parece existir y no existe).
                com.ejemplo.locksuite.util.ApkChecksum.blockStoreInstall(tempFile, sha256, label)?.let { error ->
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                    return@withContext error
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Instalando $label en segundo plano...", Toast.LENGTH_SHORT).show()
                }

                // Levantar restricciones ANTES de crear la sesión (Android valida al crear, no al commit)
                if (!prepareTemporaryInstallAccess(context)) {
                    return@withContext "No se pudieron preparar los permisos temporales de instalación."
                }
                temporaryInstallAccessPrepared = true

                val pm = context.packageManager
                val packageInstaller = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }

                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)
                // Mismo problema que en checkAndPerformUpdate: session.close() se
                // mueve a un finally para que "fis"/"out"/la sesión siempre se
                // cierren, incluso si la copia falla a mitad de camino.
                try {
                    session.openWrite("COSU", 0, -1).use { out ->
                        FileInputStream(tempFile).use { fis ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                out.write(buffer, 0, bytesRead)
                            }
                            session.fsync(out)
                        }
                    }

                    val intent = Intent(context, com.ejemplo.locksuite.receiver.PackageInstallStatusReceiver::class.java)
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        intent,
                        flags
                    )

                    session.commit(pendingIntent.intentSender)
                    installCommitSubmitted = true
                } finally {
                    session.close()
                }

                tempFile.delete()
                return@withContext null
            } catch (e: Exception) {
                if (temporaryInstallAccessPrepared && !installCommitSubmitted) {
                    com.ejemplo.locksuite.mdm.PolicyManager(context).restoreInstallRestrictions()
                }
                Log.e("SelfUpdater", "Error al instalar $label", e)
                return@withContext "Error al instalar $label: ${e.message}"
            }
        }
    }

    private fun prepareTemporaryInstallAccess(context: Context): Boolean {
        try {
            PrefsHelper.getMdmPrefs(context)
                .edit()
                .putBoolean("mdm_install_in_progress", true)
                .apply()

            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                ?: throw IllegalStateException("DevicePolicyManager no disponible")
            val adminComponent = android.content.ComponentName(context, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                throw IllegalStateException("LockSuite no es el propietario del dispositivo")
            }

            // Programar el cierre de seguridad antes de abrir la ventana de
            // instalación. Si no puede programarse, las políticas no se relajan.
            if (!scheduleInstallSafetyTimeout(context)) {
                throw IllegalStateException("No se pudo programar el cierre de seguridad de instalación")
            }

            dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_INSTALL_APPS)
            dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)

            // Esperar a que Android propague el cambio de política antes de createSession()
            Thread.sleep(500)
            return true
        } catch (e: Exception) {
            Log.w("SelfUpdater", "Error al preparar permisos temporales de instalación: ${e.message}")
            com.ejemplo.locksuite.mdm.PolicyManager(context).restoreInstallRestrictions()
            return false
        }
    }

    private fun scheduleInstallSafetyTimeout(context: Context): Boolean {
        try {
            val intent = Intent(context, com.ejemplo.locksuite.receiver.PackageReceiver::class.java).apply {
                action = "INSTALL_SAFETY_TIMEOUT"
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 9922, intent, flags)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val triggerAtMs = android.os.SystemClock.elapsedRealtime() + 120_000

            // ⚠️ 10/9/2026 — ES EL MISMO BUG QUE B.54 ARREGLÓ EN `UpdateFlowManager`, Y
            // ESTA COPIA HABÍA QUEDADO AFUERA.
            //
            // Desde Android 12 (API 31), `setExactAndAllowWhileIdle` exige permiso de
            // alarma exacta. Sin él lanza `SecurityException`, esta función devolvía
            // `false`, `prepareTemporaryInstallAccess()` tiraba `IllegalStateException`…
            // y entonces **la Tienda no instalaba absolutamente nada y la
            // autoactualización tampoco**, con el mensaje "No se pudieron preparar los
            // permisos temporales de instalación", que no dice ni de lejos que el
            // problema es un permiso de alarmas. El Manifest declara `SCHEDULE_EXACT_ALARM`
            // y `USE_EXACT_ALARM` desde B.54, así que en el caso normal hay permiso — pero
            // en Android 12 el usuario lo puede revocar desde Ajustes → Alarmas y
            // recordatorios, y ahí se caía la Tienda entera sin ninguna pista.
            //
            // Igual que en B.54: se verifica en caliente y se cae a `setAndAllowWhileIdle`,
            // que NO necesita permiso y dispara igual en Doze. **La red de seguridad
            // inexacta es infinitamente mejor que no abrir la ventana de instalación**:
            // acá el cierre de seguridad puede llegar unos minutos tarde, contra la
            // alternativa de que la Tienda no funcione nunca.
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                alarmManager.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pendingIntent)
            } else if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pendingIntent)
                Log.w("SelfUpdater", "Sin permiso de alarma exacta: cierre de instalacion programado inexacto")
            }
            return true
        } catch (e: Exception) {
            Log.w("SelfUpdater", "Error al programar timeout de instalación: ${e.message}")
            return false
        }
    }

    /**
     * Abre una conexión HTTP para descarga siguiendo redirecciones cross-domain (301, 302, 307, 308).
     * En Android, HttpURLConnection no sigue redirecciones entre distintos dominios
     * (por ejemplo de github.com a release-assets.githubusercontent.com / S3 / Azure Blob),
     * cortando con HTTP 302 a menos que se siga manualmente la cabecera 'Location'.
     */
    private fun openDownloadConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 7) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "LockSuite-Updater/1.0")
            conn.connect()
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 || code == 308) {
                val newLocation = conn.getHeaderField("Location")
                if (!newLocation.isNullOrBlank()) {
                    conn.disconnect()
                    val nextUrl = if (newLocation.startsWith("http://") || newLocation.startsWith("https://")) {
                        newLocation
                    } else {
                        URL(URL(currentUrl), newLocation).toString()
                    }
                    // No se sigue un redirect que BAJE de https a http. La huella SHA-256
                    // que exige la Tienda (B.58) ya impide instalar un archivo cambiado,
                    // pero un salto a texto plano expone igual qué APK baja cada equipo y
                    // no hace falta para nada: todos los orígenes reales de este proyecto
                    // (GitHub Releases, Firebase Hosting, S3) son https de punta a punta.
                    if (currentUrl.startsWith("https://") && nextUrl.startsWith("http://")) {
                        conn.disconnect()
                        throw IOException("Redireccion insegura de https a http, descarga cancelada")
                    }
                    currentUrl = nextUrl
                    redirects++
                    continue
                }
            }
            return conn
        }
        // Sin esto se devolvía una conexión NUEVA, sin timeouts y sin User-Agent, sobre
        // la última URL de una cadena que ya había dado siete saltos: o sea que un bucle
        // de redirecciones terminaba en una descarga que podía colgarse para siempre.
        // Un error claro es mejor: el llamador lo muestra y el panel lo registra.
        throw IOException("Demasiadas redirecciones (7) al descargar el APK")
    }

    private fun fetchVersionManifest(): String {
        var lastFailure: Exception? = null
        for (manifestUrl in listOf(VERSION_URL, FALLBACK_VERSION_URL)) {
            try {
                val urlWithCacheBuster = "$manifestUrl?t=${System.currentTimeMillis()}"
                val connection = URL(urlWithCacheBuster).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("HTTP ${connection.responseCode} al consultar $manifestUrl")
                    }
                    return connection.inputStream.bufferedReader().use { reader -> reader.readText() }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastFailure = e
                Log.w("SelfUpdater", "No se pudo consultar manifiesto OTA: $manifestUrl", e)
            }
        }
        throw IOException("No se pudo consultar ningún manifiesto OTA", lastFailure)
    }
}
