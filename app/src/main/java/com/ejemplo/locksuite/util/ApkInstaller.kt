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

            // ── VERIFICACIÓN DE FIRMA ANTES DE INSTALAR (B.6, 2/9/2026) ──
            //
            // Si el paquete ya está instalado, su firma tiene que coincidir. Android lo
            // exige igual y rechazaría la instalación — pero lo hace al final, después de
            // abrir la sesión de PackageInstaller, y con un error opaco. Comprobarlo acá
            // convierte "falló la actualización" en un mensaje que dice qué pasó.
            //
            // Un paquete NO instalado (primera instalación desde la Tienda administrada)
            // no tiene con qué compararse: ahí no se puede decidir nada por firma, y por eso
            // B.6 sigue necesitando el `sha256` publicado en `storeApps`. Se deja pasar,
            // porque negarlo rompería la Tienda administrada entera.
            when (val v = ApkSignatureVerifier.verify(context, tempFile.absolutePath, packageName)) {
                is ApkSignatureVerifier.Result.Mismatch -> {
                    Log.e("ApkInstaller", "Firma distinta para $packageName: instalada=${v.expected} apk=${v.actual}")
                    return "El archivo APK de $packageName está firmado por otro desarrollador " +
                        "que el que ya está instalado. No se instaló: puede ser una versión falsificada."
                }
                is ApkSignatureVerifier.Result.Unknown -> {
                    Log.w("ApkInstaller", "No se pudo verificar la firma de $packageName: ${v.reason}")
                    return "No se pudo verificar la firma del APK de $packageName (${v.reason}). " +
                        "No se instaló."
                }
                ApkSignatureVerifier.Result.NotInstalled -> {
                    Log.i("ApkInstaller", "$packageName no está instalado: primera instalación, sin firma previa que comparar.")
                }
                ApkSignatureVerifier.Result.Match -> {
                    Log.i("ApkInstaller", "Firma de $packageName verificada correctamente.")
                }
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
