package com.ejemplo.locksuite.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ApkInstaller {
    fun installApk(context: Context, apkUri: Uri): String? {
        val tempFile = File(context.cacheDir, "temp_install.apk")
        try {
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(tempFile.absolutePath, 0)
            if (info == null) {
                return "No se pudo leer el archivo APK. Asegúrate de que sea un archivo de instalación válido."
            }

            val packageName = info.packageName
            val prefs = PrefsHelper.getMdmPrefs(context)
            val allowed = prefs.getStringSet("allowed_packages", null) ?: emptySet()
            val isBlocked = prefs.getBoolean("install_apps_blocked_admin", false)

            // com.ejemplo.locksuite y las apps permitidas en la lista blanca pueden ser instaladas/actualizadas.
            val isAllowed = !isBlocked || allowed.contains(packageName) || packageName == context.packageName
            if (!isAllowed) {
                return "La aplicación $packageName no está permitida en la lista blanca de la administración."
            }

            val packageInstaller = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(packageName)

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            // session.close() se mueve a un finally: antes, si algo fallaba a mitad
            // de la copia (out.write/fis.read), el control saltaba directo al catch
            // de más abajo sin cerrar nunca "fis", "out" ni la sesión de
            // PackageInstaller — quedaban colgados hasta que el sistema los
            // recolectara, y con instalaciones fallidas repetidas se puede llegar
            // al límite de sesiones activas y bloquear instalaciones futuras sin
            // ningún aviso claro. "out"/"fis" ahora usan use{} (que cierra incluso
            // ante excepción).
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
            } finally {
                session.close()
            }
            return null // Iniciado con éxito
        } catch (e: Exception) {
            Log.e("ApkInstaller", "Error al iniciar la instalación", e)
            return "Error: ${e.message}"
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
