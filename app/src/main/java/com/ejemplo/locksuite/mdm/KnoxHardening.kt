package com.ejemplo.locksuite.mdm

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Puente hacia el Knox SDK de Samsung (Knox Platform for Enterprise, licencia
 * Standard — gratuita) para dos politicas que DevicePolicyManager NO cubre de forma
 * documentada, ni en Samsung ni en el resto de marcas:
 *
 *   - allowFactoryReset(false)     -> ademas de sacar la opcion de Ajustes (lo que ya
 *                                      hace DISALLOW_FACTORY_RESET en cualquier marca),
 *                                      en Samsung tambien bloquea el recovery.
 *   - allowFirmwareRecovery(false) -> bloquea el flasheo de firmware por Odin/Download
 *                                      mode. No existe otra forma publica de lograr esto.
 *
 * Solo tiene efecto en equipos Samsung con Knox (no existe equivalente para otras
 * marcas — ver el informe de investigacion entregado por chat para el detalle
 * completo de por que y que tan lejos se puede llegar en el resto de marcas).
 *
 * ESTADO ACTUAL: INERTE A PROPOSITO. Las llamadas reales al Knox SDK estan comentadas
 * mas abajo porque ese SDK no esta agregado todavia al proyecto — si se descomentaran
 * ahora, el proyecto entero dejaria de compilar (las clases com.samsung.android.knox.*
 * no existen en el classpath hasta que se agregue el .jar). Mientras tanto, las
 * funciones de aca abajo solo loguean una advertencia y devuelven false: no rompen
 * nada, simplemente todavia no hacen el bloqueo real.
 *
 * COMO ACTIVARLO DE VERDAD (una sola vez):
 *   1. Crear cuenta gratis en developer.samsungknox.com y en el Knox Partner Program
 *      (KPP) — ver el informe adjunto para el detalle.
 *   2. Descargar el Knox SDK (nivel Standard, gratis) desde SDK Tools > SDK Downloads
 *      y copiar "knoxsdk.jar" a app/libs/knoxsdk.jar. Como minSdk = 24 (< 27), copiar
 *      tambien "supportlib.jar" a la misma carpeta (viene en el mismo paquete).
 *   3. Generar una licencia KPE Standard (gratis) en el dashboard de KPP y pegarla en
 *      R.string.knox_license_key (app/src/main/res/values/strings.xml) reemplazando
 *      el placeholder.
 *   4. Descomentar el bloque real de cada funcion de aca abajo (y borrar el
 *      "return false" / el log del stub que lo precede). app/build.gradle.kts ya
 *      tiene la dependencia lista, condicionada a que el .jar exista.
 *   5. Compilar, instalar en un Samsung real con Knox, y confirmar en la pantalla de
 *      Odin/recovery que el bloqueo ocurre de verdad — segun la propia auditoria del
 *      proyecto, el comportamiento de Device Owner varia por fabricante/ROM y hay que
 *      validarlo en equipos fisicos, no asumirlo.
 *
 * Auditoria: archivo escrito sin poder compilarlo ni probarlo (no hay entorno Android
 * ni el .jar de Knox disponibles en el entorno donde se escribio esto). El codigo
 * comentado sigue al pie de la letra la referencia oficial de la API
 * (EnterpriseDeviceManager.getInstance(context).restrictionPolicy.allowFactoryReset /
 * allowFirmwareRecovery, y KnoxEnterpriseLicenseManager.getInstance(context)
 * .activateLicense(key)), pero no hay garantia de que compile sin ajustes menores
 * hasta que alguien lo pruebe con el SDK real puesto.
 */
object KnoxHardening {

    private const val TAG = "KnoxHardening"

    private fun isSamsung(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Llamar una vez al arrancar la app (ver LockSuiteApplication.onCreate). */
    fun activateLicense(context: Context) {
        if (!isSamsung()) return
        Log.i(TAG, "Knox SDK todavia no integrado (ver KnoxHardening.kt) — omitiendo activacion de licencia.")

        // -- Descomentar una vez agregado app/libs/knoxsdk.jar + supportlib.jar --
        // try {
        //     val licenseKey = context.getString(com.ejemplo.locksuite.R.string.knox_license_key)
        //     if (licenseKey.isBlank() || licenseKey == "TU_LICENCIA_KPE_STANDARD_ACA") {
        //         Log.w(TAG, "Falta configurar knox_license_key en strings.xml.")
        //         return
        //     }
        //     com.samsung.android.knox.license.KnoxEnterpriseLicenseManager
        //         .getInstance(context)
        //         .activateLicense(licenseKey)
        // } catch (e: Exception) {
        //     Log.w(TAG, "No se pudo activar la licencia Knox.", e)
        // }
    }

    fun setFactoryResetBlocked(context: Context, block: Boolean): Boolean {
        if (!isSamsung()) return false
        Log.i(TAG, "Knox SDK todavia no integrado — setFactoryResetBlocked($block) no tiene efecto todavia.")
        return false

        // -- Descomentar junto con lo anterior; borrar el "return false" de arriba --
        // return try {
        //     com.samsung.android.knox.EnterpriseDeviceManager.getInstance(context)
        //         .restrictionPolicy
        //         .allowFactoryReset(!block)
        //     true
        // } catch (e: Exception) {
        //     Log.w(TAG, "Knox allowFactoryReset fallo (¿licencia no activada o falta Device Owner?)", e)
        //     false
        // }
    }

    fun setFlashingBlocked(context: Context, block: Boolean): Boolean {
        if (!isSamsung()) return false
        Log.i(TAG, "Knox SDK todavia no integrado — setFlashingBlocked($block) no tiene efecto todavia.")
        return false

        // -- Descomentar junto con lo anterior; borrar el "return false" de arriba --
        // return try {
        //     com.samsung.android.knox.EnterpriseDeviceManager.getInstance(context)
        //         .restrictionPolicy
        //         .allowFirmwareRecovery(!block)
        //     true
        // } catch (e: Exception) {
        //     Log.w(TAG, "Knox allowFirmwareRecovery fallo (¿licencia no activada o falta Device Owner?)", e)
        //     false
        // }
    }
}
