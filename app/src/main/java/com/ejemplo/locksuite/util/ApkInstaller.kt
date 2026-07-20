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
            session.commit(pendingIntent.intentSender)
            session.close()
            return null // Iniciado con éxito
        } catch (e: Exception) {
            Log.e("ApkInstaller", "Error al iniciar la instalación", e)
            return "Error: ${e.message}"
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
