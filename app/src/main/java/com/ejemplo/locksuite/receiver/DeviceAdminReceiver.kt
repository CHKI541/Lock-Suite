package com.ejemplo.locksuite.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "LockSuite MDM: Administrador Habilitado", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "No se puede desactivar la administración de LockSuite. Contacte al departamento de TI."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "LockSuite MDM: Administrador Deshabilitado", Toast.LENGTH_SHORT).show()
    }

    /**
     * ALTA POR QR: el equipo terminó de aprovisionarse (10/9/2026, B.61).
     *
     * Android llama acá cuando LockSuite quedó instalada como Device Owner por el flujo
     * de aprovisionamiento (los seis toques en la pantalla de bienvenida + el QR). El
     * QR trae, dentro de `PROVISIONING_ADMIN_EXTRAS_BUNDLE`, el nivel de perfil que el
     * administrador eligió en el panel; acá se lee y se aplica.
     *
     * ─────────────────────────────────────────────────────────────────────────
     * ⚠️ EL PERFIL SE APLICA RECORTADO, Y ESE ES EL PUNTO MÁS FÁCIL DE EMBARRAR
     * ─────────────────────────────────────────────────────────────────────────
     *
     * En este momento **todavía no hay cuenta de Google en el equipo** y el idioma
     * puede no ser el definitivo: el instalador hace las dos cosas después. El Nivel 1
     * bloquea `DISALLOW_MODIFY_ACCOUNTS` y `DISALLOW_CONFIG_LOCALE`, así que aplicarlo
     * entero acá **deja un equipo que no se puede terminar de dar de alta**.
     *
     * Es el mismo error de razonamiento que ya costó B.41 punto 3 y B.43: LockSuite se
     * instala con el equipo SIN cuenta, así que "todavía no hay cuenta" es el ESTADO DE
     * FÁBRICA del procedimiento, no un caso raro.
     *
     * Por eso `applyMasterProfile(..., incluirPostAlta = false)`: se aplica todo el
     * perfil menos esas dos, el equipo queda protegido desde el minuto cero con el piso
     * anti-manipulación entero, y el panel muestra un botón **"Terminar alta"** que
     * aplica el resto cuando la cuenta ya está puesta. Ver `EnrollmentProfiles.POST_ALTA`.
     *
     * Nada de acá puede lanzar hacia afuera: una excepción en el receptor de
     * aprovisionamiento deja el equipo a medio enrolar y sin forma de reintentarlo.
     * Es la misma regla que `LockSuiteApplication` tuvo que aprender en B.20.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        try {
            val extras: PersistableBundle? =
                intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
            val nivel = extras?.getString(EXTRA_NIVEL_PERFIL)

            if (nivel.isNullOrBlank()) {
                // Sin nivel en el QR el equipo queda enrolado y sin perfil, que es un
                // estado válido: el administrador puede aplicar uno desde el panel. Se
                // dice igual, porque un alta silenciosa que no configuró nada se
                // confunde con una que sí (B.28/B.42).
                android.util.Log.w(
                    "DeviceAdminReceiver",
                    "Alta por QR sin nivel de perfil: el equipo queda enrolado sin configurar."
                )
                Toast.makeText(
                    context,
                    "LockSuite instalada. Falta aplicarle un perfil desde el panel.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val pm = com.ejemplo.locksuite.mdm.PolicyManager(context)
            val ok = pm.applyMasterProfile(nivel, incluirPostAlta = false)
            val pendientes = com.ejemplo.locksuite.mdm.EnrollmentProfiles.pendientesDeAlta(nivel)

            android.util.Log.i(
                "DeviceAdminReceiver",
                "Alta por QR: perfil=$nivel aplicado=$ok pendientes=${pendientes.size}"
            )
            Toast.makeText(
                context,
                if (!ok) {
                    "LockSuite: no se pudo aplicar el perfil «$nivel». Aplicalo desde el panel."
                } else if (pendientes.isNotEmpty()) {
                    "LockSuite configurada. Agregá la cuenta de Google y el idioma, " +
                        "y después tocá «Terminar alta» en el panel."
                } else {
                    "LockSuite configurada con el perfil «$nivel»."
                },
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            android.util.Log.e(
                "DeviceAdminReceiver",
                "Alta por QR: onProfileProvisioningComplete falló: ${e.message}", e
            )
        }
    }

    companion object {
        /**
         * Clave del nivel de perfil dentro de `PROVISIONING_ADMIN_EXTRAS_BUNDLE`.
         *
         * ⚠️ Tiene que coincidir EXACTA con la que escribe el panel al armar el QR
         * (`admin-backend/public/app.js`). Una clave desalineada acá no da error en
         * ningún lado: el equipo se enrola, no encuentra el nivel, y queda sin
         * configurar — la forma exacta del bug de `no_apps_control` (B.28). Por eso
         * `tools/check_profile_sync.py` compara las dos.
         */
        const val EXTRA_NIVEL_PERFIL = "com.ejemplo.locksuite.PROFILE_LEVEL"
    }
}
