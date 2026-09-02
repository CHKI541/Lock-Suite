package com.ejemplo.locksuite.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * ApkSignatureVerifier — verificación de integridad antes de instalar un APK (2/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ CIERRA (B.6) Y DE DÓNDE SALE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Es la parte de `SecureUpdateHelper` de A Bloq que sirve, **con la verificación
 * encendida**. Conviene decirlo explícito porque es una trampa: en A Bloq esa función
 * existe entera pero está COMENTADA, y el cuerpo real es
 *
 *     return true // TEMPORARILY ALWAYS RETURN TRUE TO BYPASS SIGNATURE VERIFICATION
 *
 * o sea que allá no verifica nada. Se copió la forma, no el estado.
 *
 * El problema que resuelve (B.6, abierto desde el 14/8): `SelfUpdater` descarga un APK y
 * lo instala **sin comparar nada**. Para la autoactualización de LockSuite eso está
 * medio cubierto de arriba: Android se niega a actualizar un paquete instalado con otra
 * firma, así que un APK falso no puede reemplazar a LockSuite. Pero hay dos huecos que
 * esa protección gratis NO cubre:
 *
 *  1. **La Tienda administrada** (`downloadAndInstallApk`) instala paquetes que todavía
 *     no están en el equipo: no hay firma previa contra la cual comparar, así que Android
 *     acepta cualquier cosa. Ahí es donde de verdad hace falta el `sha256` en
 *     `storeApps` (sigue pendiente, ver más abajo).
 *  2. Aunque Android rechace el APK ajeno, hoy LockSuite **igual lo descarga entero,
 *     abre una sesión de PackageInstaller y lanza la instalación**, y el fallo aparece
 *     recién al final y sin explicación clara. Verificar antes convierte un fallo opaco
 *     en un mensaje concreto.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LO QUE ESTO **NO** ES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Esto **no reemplaza al checksum de B.6**, y no hay que darlo por cerrado con esto.
 * Comparar firmas responde "¿este APK lo firmó el mismo que firmó lo que ya está
 * instalado?", que es una pregunta que solo se puede hacer si el paquete YA está
 * instalado. Para la primera instalación de una app de la Tienda administrada sigue
 * haciendo falta publicar el `sha256` en `version.json` / `storeApps` y compararlo contra
 * el archivo descargado. Eso queda pendiente y necesita un cambio del lado del panel.
 */
object ApkSignatureVerifier {

    sealed class Result {
        /** El APK está firmado igual que el paquete ya instalado. */
        object Match : Result()

        /** El paquete no está instalado: no hay con qué comparar (primera instalación). */
        object NotInstalled : Result()

        /** El APK está firmado por otro: NO instalar. */
        data class Mismatch(val expected: String, val actual: String) : Result()

        /** No se pudo leer una de las dos firmas. Ante la duda, no se afirma que coincidan. */
        data class Unknown(val reason: String) : Result()
    }

    /**
     * Compara la firma del APK del archivo contra la del paquete instalado con ese mismo
     * nombre.
     *
     * @param apkPath ruta al APK ya descargado en disco.
     * @param packageName paquete que declara ese APK.
     */
    fun verify(context: Context, apkPath: String, packageName: String): Result {
        val pm = context.packageManager

        val archivo = try {
            File(apkPath)
        } catch (e: Exception) {
            return Result.Unknown("ruta inválida: ${e.message}")
        }
        if (!archivo.exists() || archivo.length() == 0L) {
            return Result.Unknown("el archivo descargado no existe o está vacío")
        }

        val firmaApk = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
            } ?: return Result.Unknown("no se pudo leer el APK descargado")
            huellaDe(info)
        } catch (e: Exception) {
            return Result.Unknown("no se pudo leer la firma del APK: ${e.message}")
        } ?: return Result.Unknown("el APK descargado no tiene firma")

        val firmaInstalada = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            huellaDe(info)
        } catch (e: PackageManager.NameNotFoundException) {
            // No es un error: es una primera instalación. Quien llama decide si la permite.
            return Result.NotInstalled
        } catch (e: Exception) {
            return Result.Unknown("no se pudo leer la firma instalada: ${e.message}")
        } ?: return Result.Unknown("el paquete instalado no tiene firma legible")

        return if (firmaApk == firmaInstalada) {
            Result.Match
        } else {
            Result.Mismatch(expected = firmaInstalada, actual = firmaApk)
        }
    }

    /**
     * Huella SHA-256 del primer certificado de firma, en hexadecimal.
     *
     * Se usa el conjunto de `apkContentsSigners` en API 28+ (el esquema v2/v3 puede tener
     * más de un firmante) y `signatures` por debajo. Se compara la huella y no el
     * `Signature` crudo porque así el valor se puede loguear y mostrar en un mensaje sin
     * volcar el certificado entero.
     */
    private fun huellaDe(info: PackageInfo): String? {
        val firmas: Array<out Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val primera = firmas?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(primera.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
