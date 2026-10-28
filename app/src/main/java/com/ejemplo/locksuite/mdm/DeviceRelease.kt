package com.ejemplo.locksuite.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.ejemplo.locksuite.util.PrefsHelper

/**
 * Libera el equipo por completo y lo deja listo para desinstalar LockSuite, SIN
 * necesitar una Activity (se puede correr desde un comando del panel, en un hilo
 * de fondo).
 *
 * Es el MISMO camino que `EmergencyActivity.executeFullPurge()`, extraído acá para
 * que el comando remoto `UNINSTALL_LOCKSUITE` y el código de recuperación en el
 * propio equipo compartan exactamente la misma secuencia y el mismo orden — un
 * orden que es delicado y que ya costó varios arreglos (P-1, P-3, P-4; ver los
 * comentarios de `PolicyManager.clearAllRestrictions()`):
 *
 *   1. Des-ocultar y des-suspender TODAS las apps del usuario mientras TODAVÍA
 *      somos Device Owner. Una app oculta es una app deshabilitada: si esto
 *      quedara sin hacer, el equipo liberado tendría apps muertas para siempre.
 *   2. `clearAllRestrictions()`: levanta todas las restricciones de usuario, suelta
 *      el kiosco, re-habilita hardware y táctil, quita el bloqueo de desinstalación,
 *      corta la VPN y, al final, borra las preferencias (incluido el secreto de
 *      comandos y la marca de suspensión). Usa Device Owner, así que va ANTES de
 *      soltarlo.
 *   3. Reactivar el ícono del lanzador (salir del modo oculto), para que el usuario
 *      pueda encontrar la app y desinstalarla.
 *   4. Parar servicios (VPN, watchdog, marca de agua), cancelar el Worker y los
 *      avisos.
 *   5. Soltar Device Owner con `clearDeviceOwnerApp()` (irreversible). Después de
 *      esto ya no se pueden aplicar políticas.
 *   6. Marcar `locksuite_purged = true` DESPUÉS del paso 2 (que borra las
 *      preferencias): a partir de acá los watchdogs, el arranque, el buzón de
 *      comandos y el servicio de accesibilidad se quedan inertes aunque la
 *      desinstalación se demore o el usuario no la confirme.
 *   7. (opcional) Abrir el diálogo del sistema «¿Desinstalar esta app?».
 *
 * Cada paso va en su try/catch: que un fabricante rechace una llamada puntual no
 * puede dejar el equipo a medio liberar.
 */
object DeviceRelease {

    private const val TAG = "DeviceRelease"

    /**
     * @param context         cualquier contexto; se usa el de aplicación.
     * @param promptUninstall si es true, al terminar abre el diálogo del sistema
     *        para desinstalar LockSuite. Si no hay nadie frente al equipo, el
     *        diálogo queda esperando; la app ya quedó inerte y liberada igual.
     * @return true si la liberación llegó a soltar Device Owner.
     */
    fun releaseAndPrepareUninstall(context: Context, promptUninstall: Boolean): Boolean {
        val appCtx = context.applicationContext
        val policyManager = PolicyManager(appCtx)
        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent =
            ComponentName(appCtx, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)

        // 0. Cortar cualquier actualización en curso: si no, quedaría una pantalla
        //    negra del flujo encima de un equipo que ya no tiene restricciones (mismo
        //    razonamiento que PolicyManager.setLockSuiteSuspended()).
        try {
            if (com.ejemplo.locksuite.util.UpdateFlowManager.hasOpenFlow(appCtx)) {
                com.ejemplo.locksuite.util.UpdateFlowManager.forceCleanup(
                    appCtx, com.ejemplo.locksuite.util.UpdateFlowManager.RESULT_CANCELLED
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "No se pudo cortar la actualización en curso: ${e.message}")
        }

        // 1. Des-ocultar y des-suspender TODAS las apps mientras somos Device Owner.
        try {
            val appController = AppController(appCtx)
            val apps = appController.getUserApps(loadIcon = false)
            for (app in apps) {
                if (app.isHidden) {
                    try { appController.hideApp(app.packageName, false) } catch (_: Exception) {}
                }
                if (app.isSuspended) {
                    try { appController.suspendApp(app.packageName, false) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "No se pudieron desbloquear todas las apps: ${e.message}")
        }

        // 2. Levantar TODAS las restricciones y borrar las preferencias (usa Device Owner).
        try {
            policyManager.clearAllRestrictions()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clearAllRestrictions() falló: ${e.message}")
        }

        // 3. Reactivar el ícono del lanzador (salir del modo oculto).
        setLauncherIconVisible(appCtx)

        // 4. Barra de estado de vuelta por las dudas (clearAllRestrictions ya lo hace,
        //    pero es barato y deja el equipo usable aunque aquel paso haya fallado).
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                dpm.setStatusBarDisabled(adminComponent, false)
            }
        } catch (_: Exception) {}

        // 5. Parar servicios y cancelar el trabajo de fondo.
        try {
            appCtx.stopService(Intent(appCtx, com.ejemplo.locksuite.service.KosherVpnService::class.java))
            appCtx.stopService(Intent(appCtx, com.ejemplo.locksuite.service.WatchdogForegroundService::class.java))
            appCtx.stopService(Intent(appCtx, com.ejemplo.locksuite.service.WatermarkService::class.java))
            androidx.work.WorkManager.getInstance(appCtx).cancelUniqueWork("LockSuiteWatchdog")
            androidx.core.app.NotificationManagerCompat.from(appCtx).cancelAll()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "No se pudieron parar todos los servicios: ${e.message}")
        }

        // 6. Soltar Device Owner (irreversible).
        var released = false
        try {
            dpm.clearDeviceOwnerApp(appCtx.packageName)
            released = true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "No se pudo soltar Device Owner: ${e.message}")
        }

        // 7. Marcar la purga DESPUÉS de clearAllRestrictions() (que borró las prefs):
        //    deja inertes watchdog, arranque, buzón y accesibilidad. commit() y no
        //    apply() porque el proceso puede morir enseguida (desinstalación).
        try {
            PrefsHelper.getMdmPrefs(appCtx).edit().putBoolean("locksuite_purged", true).commit()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "No se pudo marcar locksuite_purged: ${e.message}")
        }

        // 8. Cerrar cualquier sesión local abierta.
        try { com.ejemplo.locksuite.security.SessionManager.closeSession() } catch (_: Exception) {}

        // 9. (opcional) Abrir el diálogo del sistema para desinstalar.
        if (promptUninstall) launchSelfUninstall(appCtx)

        android.util.Log.w(TAG, "Equipo liberado (Device Owner soltado=$released). LockSuite listo para desinstalar.")
        return released
    }

    private fun setLauncherIconVisible(appCtx: Context) {
        try {
            val aliasComponent = ComponentName(appCtx, "com.ejemplo.locksuite.LauncherAlias")
            appCtx.packageManager.setComponentEnabledSetting(
                aliasComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "No se pudo reactivar el ícono del lanzador: ${e.message}")
        }
    }

    private fun launchSelfUninstall(appCtx: Context) {
        try {
            val intent = Intent(
                Intent.ACTION_DELETE,
                Uri.fromParts("package", appCtx.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "No se pudo abrir el diálogo de desinstalación: ${e.message}")
        }
    }
}
