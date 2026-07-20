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
import java.net.HttpURLConnection
import java.net.URL

object SelfUpdater {
    private const val VERSION_URL = "https://locksuite-nueva.web.app/version.json"

    suspend fun checkAndPerformUpdate(context: Context, showToasts: Boolean = false, onProgress: ((Int) -> Unit)? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (showToasts) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Buscando actualizaciones de LockSuite...", Toast.LENGTH_SHORT).show()
                    }
                }

                val urlConnection = URL(VERSION_URL).openConnection() as HttpURLConnection
                urlConnection.connectTimeout = 10000
                urlConnection.readTimeout = 10000
                urlConnection.connect()

                if (urlConnection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext "Error al consultar versión: HTTP ${urlConnection.responseCode}"
                }

                val responseText = urlConnection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val serverVersionCode = json.optInt("versionCode", 0)
                val apkUrl = json.optString("url", "")

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
                val apkConnection = URL(apkUrl).openConnection() as HttpURLConnection
                apkConnection.connectTimeout = 15000
                apkConnection.readTimeout = 15000
                apkConnection.connect()

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

                if (showToasts) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Instalando actualización de LockSuite...", Toast.LENGTH_SHORT).show()
                    }
                }

                val pm = context.packageManager
                val packageInstaller = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }

                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                val out = session.openWrite("COSU", 0, -1)
                val fis = FileInputStream(tempFile)
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
                session.fsync(out)
                fis.close()
                out.close()

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

                // Desactivar temporalmente restricciones de OS para permitir la instalación MDM
                try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                    val adminComponent = android.content.ComponentName(context, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
                    dpm?.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_INSTALL_APPS)
                    dpm?.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                } catch (e: Exception) {
                    Log.w("SelfUpdater", "No se pudieron limpiar restricciones temporalmente: ${e.message}")
                }

                session.commit(pendingIntent.intentSender)
                session.close()

                tempFile.delete()
                return@withContext null
            } catch (e: Exception) {
                Log.e("SelfUpdater", "Error de actualización", e)
                return@withContext "Error de actualización: ${e.message}"
            }
        }
    }

    suspend fun downloadAndInstallApk(context: Context, apkUrl: String, packageName: String, label: String, onProgress: ((Int) -> Unit)? = null): String? {
        return withContext(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Iniciando descarga de $label...", Toast.LENGTH_SHORT).show()
                }

                val tempFile = File(context.cacheDir, "store_${packageName}_update.apk")
                val apkConnection = URL(apkUrl).openConnection() as HttpURLConnection
                apkConnection.connectTimeout = 15000
                apkConnection.readTimeout = 15000
                apkConnection.connect()

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

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Instalando $label en segundo plano...", Toast.LENGTH_SHORT).show()
                }

                val pm = context.packageManager
                val packageInstaller = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }

                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                val out = session.openWrite("COSU", 0, -1)
                val fis = FileInputStream(tempFile)
                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
                session.fsync(out)
                fis.close()
                out.close()

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

                // Desactivar temporalmente restricciones de OS para permitir la instalación MDM
                try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                    val adminComponent = android.content.ComponentName(context, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
                    dpm?.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_INSTALL_APPS)
                    dpm?.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                } catch (e: Exception) {
                    Log.w("SelfUpdater", "No se pudieron limpiar restricciones temporalmente: ${e.message}")
                }

                session.commit(pendingIntent.intentSender)
                session.close()

                tempFile.delete()
                return@withContext null
            } catch (e: Exception) {
                Log.e("SelfUpdater", "Error al instalar $label", e)
                return@withContext "Error al instalar $label: ${e.message}"
            }
        }
    }
}
