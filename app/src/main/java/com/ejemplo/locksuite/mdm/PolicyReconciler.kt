package com.ejemplo.locksuite.mdm

/**
 * COMPARAR ANTES DE ESCRIBIR — las decisiones de la re-aplicación de políticas.
 *
 * 27/9/2026 (batería y CPU). Funciones puras, sin Android, para poder probarlas en un
 * banco (`PolicyReconcilerTest`). Las usa `PolicyManager.reapplyAllRestrictions()` y un par
 * de funciones que llama ella.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────
 * POR QUÉ EXISTE
 * ─────────────────────────────────────────────────────────────────────────────────────
 *
 * `reapplyAllRestrictions()` corre cada 15 minutos desde el `WatchdogWorker`, en cada
 * arranque del equipo y en cada arranque del proceso (en el hilo principal, desde
 * `LockSuiteApplication`). Hasta hoy ORDENABA todo de nuevo en cada vuelta, estuviera o no
 * puesto. Y en el sistema ninguna de esas órdenes es gratis aunque no cambie nada — medido
 * leyendo el código de Android (AOSP), no supuesto:
 *
 *  • `addUserRestriction` en Android 13: guarda `device_policies.xml` entero (con fsync),
 *    manda DOS veces el broadcast `ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED` a todas las
 *    apps que lo escuchan (SystemUI, Ajustes, Play Services…) e invalida las cachés del
 *    DevicePolicyManager en TODOS los procesos. En Android 14+: reescribe
 *    `device_policy_state.xml`, fuerza la re-sincronización de la restricción en todos los
 *    usuarios y consulta al PackageManager. Por cada restricción: 20 a 40 por vuelta.
 *  • `setLockTaskPackages`, `setPermittedAccessibilityServices`: guardan y avisan siempre.
 *  • `setUninstallBlocked`: reescribe `package-restrictions.xml`.
 *  • `setPackagesSuspended` (navegadores, WebView): en Android 14 guarda y avisa siempre.
 *  • `setFactoryResetProtectionPolicy`: además manda un broadcast a Play Services.
 *  • `addPersistentPreferredActivity` (launcher kosher): en Android ≤ 13 AGREGA UNA ENTRADA
 *    NUEVA cada vez, porque el sistema no deduplica (`IntentResolver.addFilter` hace
 *    `mFilters.add(f)` sobre un `ArraySet` y el filtro no redefine `equals`). O sea que el
 *    equipo acumulaba ~96 "launcher preferido" duplicados por día, para siempre, en
 *    `package-restrictions.xml`. Ver `homeAlDia()` y `consolidacionVigente()`.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────
 * LA REGLA, Y POR QUÉ NO DEBILITA NADA
 * ─────────────────────────────────────────────────────────────────────────────────────
 *
 * Se lee el estado REAL del sistema y solo se escribe lo que difiere de lo pedido. La
 * llamada que corrige es exactamente la misma de antes; lo único que cambia es que no se
 * hace cuando no hay nada que corregir. Es la lección de B.15 punto 3 y de B.34
 * ("comparar y corregir en vez de ordenar"), aplicada ahora a la re-aplicación entera.
 *
 * ⚠️ Ante la duda, se aplica: si una lectura del estado real falla, llega como `null` y
 * todas las funciones de acá contestan "hay que aplicar". Un error de lectura nunca puede
 * dejar una política sin poner; a lo sumo cuesta la escritura de siempre.
 */
object PolicyReconciler {

    /**
     * ¿Hay que llamar a `addUserRestriction`?
     *
     * Solo se saltea si la restricción está puesta POR NOSOTROS (la ve
     * `dpm.getUserRestrictions(admin)`) **y** además RIGE de verdad (la ve
     * `UserManager.getUserRestrictions()`). Se piden las dos a propósito: en Android 14+
     * el sistema tuvo un error en que la política quedaba guardada sin aplicarse
     * (b/307481299), y justamente por eso cada `addUserRestriction` fuerza una
     * re-sincronización. Si la guardada y la efectiva no coinciden, se aplica igual que
     * antes y esa re-sincronización ocurre.
     *
     * @param puestaPorNosotros `null` si no se pudo leer.
     * @param efectiva `null` si no se pudo leer.
     */
    fun hayQueAplicar(pedida: Boolean, puestaPorNosotros: Boolean?, efectiva: Boolean?): Boolean =
        pedida && !(puestaPorNosotros == true && efectiva == true)

    /**
     * ¿Hay que llamar a `clearUserRestriction`? Solo si la pusimos nosotros, o si no se pudo
     * saber. Quitar una que no pusimos no la saca (la puso otro origen) y en Android ≤ 13
     * igual reescribe el archivo de políticas.
     */
    fun hayQueQuitar(puestaPorNosotros: Boolean?): Boolean = puestaPorNosotros != false

    /** Mismo conjunto de paquetes, sin importar el orden. `null` (no se pudo leer) = distinto. */
    fun mismoConjunto(actual: Collection<String>?, deseado: Collection<String>): Boolean =
        actual != null && actual.toSet() == deseado.toSet()

    /**
     * ¿La VPN permanente ya es LockSuite y sin lockdown?
     *
     * `lockdownActual` es `null` en Android 7-9, donde no hay forma pública de leerlo: ahí
     * alcanza con el paquete, porque solo un Device Owner puede poner lockdown y LockSuite
     * nunca lo pone (ver `setVpnConfigBlocked` y B.4).
     */
    fun vpnPermanenteAlDia(paqueteActual: String?, lockdownActual: Boolean?, paqueteDeseado: String): Boolean =
        paqueteActual == paqueteDeseado && lockdownActual != true

    /**
     * ¿El DNS privado ya está apagado? Solo `"off"` es apagado: `null` es el valor de
     * fábrica, que desde Android 10 significa "automático" (o sea, prendido).
     */
    fun dnsPrivadoYaApagado(valorActual: String?): Boolean = valorActual == "off"

    /**
     * ¿El launcher kosher ya es la pantalla de inicio fijada, sin duplicados?
     *
     * @param consolidada hay una consolidación vigente (borrar todas las entradas propias y
     *   poner UNA): ver `consolidacionVigente()`. Deja de estarlo cada vez que LockSuite pone
     *   o saca la entrada por su cuenta (encender o apagar el launcher, suspender), así la
     *   próxima vuelta la vuelve a consolidar.
     * @param homeResuelveAlNuestro "Inicio" abre hoy nuestro launcher.
     */
    fun homeAlDia(consolidada: Boolean, homeResuelveAlNuestro: Boolean): Boolean =
        consolidada && homeResuelveAlNuestro

    /**
     * ¿La última consolidación de la entrada de inicio sigue vigente?
     *
     * `ultima` = 0 es "nunca" (o LockSuite la invalidó). Si el reloj del equipo va para
     * atrás (`ahora < ultima`) se considera vencida: ante la duda, se vuelve a consolidar.
     */
    fun consolidacionVigente(ultima: Long, ahora: Long, vigenciaMs: Long): Boolean =
        ultima > 0 && ahora >= ultima && ahora - ultima < vigenciaMs

    /**
     * Qué paquetes mandar a `setPackagesSuspended(…, suspender)` (navegadores, WebView).
     *
     * Al SUSPENDER se sacan los que el sistema ya tiene suspendidos: es el mismo criterio que
     * usa desde siempre `AppController.suspendApp(true)` (que pregunta `isPackageSuspended`,
     * lo mismo que dice este flag). Al LIBERAR se mandan todos, como antes: liberar también
     * limpia el registro del DevicePolicyManager aunque el sistema ya la vea liberada.
     *
     * Por qué importa: en Android 14 `setPackagesSuspended` guarda `device_policies.xml` y
     * avisa a todo el sistema SIEMPRE, cambie algo o no; en 15+ reescribe el archivo del
     * motor de políticas. Y esto corre en cada re-aplicación con los interruptores
     * encendidos.
     *
     * @param flagsPorPaquete los paquetes INSTALADOS, con sus `ApplicationInfo.flags`.
     * @param flagSuspendida `ApplicationInfo.FLAG_SUSPENDED` (se pasa para poder probar esto
     *   sin Android).
     */
    fun paquetesAMandar(flagsPorPaquete: Map<String, Int>, suspender: Boolean, flagSuspendida: Int): List<String> =
        if (suspender) {
            flagsPorPaquete.filterValues { (it and flagSuspendida) == 0 }.keys.toList()
        } else {
            flagsPorPaquete.keys.toList()
        }

    // ─────────────────────────────────────────────────────────────────────────────────
    // FRP (protección contra restablecimiento de fábrica)
    // ─────────────────────────────────────────────────────────────────────────────────

    /** Lo que devuelve la API oficial de FRP (Android 11+). */
    sealed class FrpOficial {
        /** Android 10 o anterior: la API no existe. Se mira la vía vieja. */
        object SinApi : FrpOficial()

        /** La ROM no tiene el servicio (tira `UnsupportedOperationException`). Vía vieja. */
        object NoSoportada : FrpOficial()

        /** La API existe y no hay política puesta. */
        object SinPolitica : FrpOficial()

        data class Politica(val habilitada: Boolean, val cuentas: Set<String>) : FrpOficial()
    }

    /** Lo que hay en las restricciones de Play Services (la vía vieja, ver `applyLegacyFrpPolicy`). */
    data class FrpVieja(
        /** Una entrada por cada clave de `LEGACY_FRP_ACCOUNT_KEYS`, en el mismo orden. */
        val cuentasPorClave: List<Set<String>?>,
        val habilitada: Boolean,
        val adminDeshabilitado: Boolean
    )

    /**
     * ¿La política de FRP que pide LockSuite ya está puesta, por la misma vía por la que
     * `setFrpPolicy()` la pondría?
     *
     * `setFrpPolicy()` intenta primero la API oficial y cae a la vieja solo si la oficial
     * tira. Por eso: si la oficial existe y funciona, manda ella — una política vieja que
     * haya quedado de antes (por ejemplo, de antes de actualizar a Android 11) NO cuenta.
     *
     * @param endurecimientoOk están puestas `DISALLOW_FACTORY_RESET` y `DISALLOW_SAFE_BOOT`,
     *   que `setFrpPolicy()` agrega siempre (`setLegacyFrpHardening`).
     * @param vieja `null` si no se pudo leer.
     */
    fun frpAlDia(
        deseadas: Set<String>,
        endurecimientoOk: Boolean,
        oficial: FrpOficial,
        vieja: FrpVieja?
    ): Boolean {
        if (deseadas.isEmpty() || !endurecimientoOk) return false
        return when (oficial) {
            is FrpOficial.Politica -> oficial.habilitada && oficial.cuentas == deseadas
            FrpOficial.SinPolitica -> false
            FrpOficial.SinApi, FrpOficial.NoSoportada ->
                vieja != null &&
                    vieja.habilitada &&
                    !vieja.adminDeshabilitado &&
                    vieja.cuentasPorClave.isNotEmpty() &&
                    vieja.cuentasPorClave.all { it == deseadas }
        }
    }
}
