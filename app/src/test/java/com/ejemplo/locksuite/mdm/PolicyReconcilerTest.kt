package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.PolicyReconciler.FrpOficial
import com.ejemplo.locksuite.mdm.PolicyReconciler.FrpVieja
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 27/9/2026 — "comparar antes de escribir" en la re-aplicación de políticas.
 *
 * Lo que se prueba es la parte que puede dejar un equipo SIN una protección si está mal:
 * cuándo se saltea una escritura. La regla es que solo se saltea si el estado real ya es el
 * pedido, y que ante cualquier duda (una lectura que falló = `null`) se escribe como antes.
 * Lo que toca Android (leer el DPM, el UserManager, el PackageManager) no corre en la JVM;
 * eso va en las pruebas en equipo de las instrucciones del 27/9.
 */
class PolicyReconcilerTest {

    // ── Restricciones ────────────────────────────────────────────────────────────────

    @Test
    fun hayQueAplicar_tablaCompleta() {
        val valores = listOf(true, false, null)
        for (pedida in listOf(true, false)) {
            for (nuestra in valores) {
                for (efectiva in valores) {
                    val esperado = pedida && !(nuestra == true && efectiva == true)
                    assertEquals(
                        "pedida=$pedida nuestra=$nuestra efectiva=$efectiva",
                        esperado,
                        PolicyReconciler.hayQueAplicar(pedida, nuestra, efectiva)
                    )
                }
            }
        }
    }

    @Test
    fun hayQueAplicar_seSalteaSoloSiEstaPuestaYRige() {
        assertFalse(PolicyReconciler.hayQueAplicar(true, true, true))
        // Guardada por nosotros pero SIN regir (el error b/307481299 de Android 14): se
        // aplica, porque es justo la llamada que fuerza la re-sincronización.
        assertTrue(PolicyReconciler.hayQueAplicar(true, true, false))
        // Rige por otro origen pero no la pusimos nosotros: se aplica (si el otro origen la
        // saca, tiene que seguir estando la nuestra).
        assertTrue(PolicyReconciler.hayQueAplicar(true, false, true))
    }

    @Test
    fun hayQueAplicar_anteLaDudaAplica() {
        assertTrue(PolicyReconciler.hayQueAplicar(true, null, true))
        assertTrue(PolicyReconciler.hayQueAplicar(true, true, null))
        assertTrue(PolicyReconciler.hayQueAplicar(true, null, null))
    }

    @Test
    fun hayQueAplicar_noPedidaNuncaSeAplica() {
        assertFalse(PolicyReconciler.hayQueAplicar(false, true, true))
        assertFalse(PolicyReconciler.hayQueAplicar(false, null, null))
    }

    @Test
    fun hayQueQuitar_soloSiEsNuestraOSiNoSeSabe() {
        assertTrue(PolicyReconciler.hayQueQuitar(true))
        assertTrue(PolicyReconciler.hayQueQuitar(null))
        assertFalse(PolicyReconciler.hayQueQuitar(false))
    }

    // ── Conjuntos (Lock Task, accesibilidad permitida) ────────────────────────────────

    @Test
    fun mismoConjunto_ignoraOrdenYRepetidos() {
        assertTrue(PolicyReconciler.mismoConjunto(listOf("b", "a"), listOf("a", "b")))
        assertTrue(PolicyReconciler.mismoConjunto(listOf("a", "a", "b"), listOf("b", "a")))
        assertTrue(PolicyReconciler.mismoConjunto(emptyList(), emptyList()))
    }

    @Test
    fun mismoConjunto_distintoONoLeidoEsDistinto() {
        assertFalse(PolicyReconciler.mismoConjunto(null, emptyList()))
        assertFalse(PolicyReconciler.mismoConjunto(null, listOf("a")))
        assertFalse(PolicyReconciler.mismoConjunto(listOf("a"), listOf("a", "b")))
        assertFalse(PolicyReconciler.mismoConjunto(listOf("a", "c"), listOf("a", "b")))
        // Accesibilidad: "null" del sistema = todos permitidos ≠ "solo LockSuite".
        assertFalse(PolicyReconciler.mismoConjunto(null, listOf("com.ejemplo.locksuite")))
    }

    // ── VPN permanente, DNS privado, inicio ───────────────────────────────────────────

    @Test
    fun vpnPermanente() {
        val yo = "com.ejemplo.locksuite"
        assertTrue(PolicyReconciler.vpnPermanenteAlDia(yo, false, yo))
        assertTrue(PolicyReconciler.vpnPermanenteAlDia(yo, null, yo)) // Android 7-9: no se lee
        assertFalse(PolicyReconciler.vpnPermanenteAlDia(yo, true, yo)) // con lockdown: se corrige
        assertFalse(PolicyReconciler.vpnPermanenteAlDia(null, false, yo))
        assertFalse(PolicyReconciler.vpnPermanenteAlDia("com.otra.vpn", false, yo))
    }

    @Test
    fun dnsPrivado_soloOffEsApagado() {
        assertTrue(PolicyReconciler.dnsPrivadoYaApagado("off"))
        // null = valor de fábrica = "automático" en Android 10+: hay que escribir.
        assertFalse(PolicyReconciler.dnsPrivadoYaApagado(null))
        assertFalse(PolicyReconciler.dnsPrivadoYaApagado("opportunistic"))
        assertFalse(PolicyReconciler.dnsPrivadoYaApagado("hostname"))
        assertFalse(PolicyReconciler.dnsPrivadoYaApagado("OFF"))
        assertFalse(PolicyReconciler.dnsPrivadoYaApagado(""))
    }

    @Test
    fun home_soloAlDiaSiEstaConsolidadaYResuelveAlNuestro() {
        assertTrue(PolicyReconciler.homeAlDia(consolidada = true, homeResuelveAlNuestro = true))
        // Sin consolidar (primera vez con esta versión, LockSuite tocó la entrada, o pasó un
        // día): se reafirma aunque "Inicio" ya abra el nuestro — es la limpieza de duplicados.
        assertFalse(PolicyReconciler.homeAlDia(consolidada = false, homeResuelveAlNuestro = true))
        assertFalse(PolicyReconciler.homeAlDia(consolidada = true, homeResuelveAlNuestro = false))
        assertFalse(PolicyReconciler.homeAlDia(consolidada = false, homeResuelveAlNuestro = false))
    }

    @Test
    fun home_consolidacionVenceAlDiaYAnteRelojParaAtras() {
        val dia = 24L * 60 * 60 * 1000
        val t0 = 1_790_000_000_000L
        assertFalse("nunca consolidada", PolicyReconciler.consolidacionVigente(0L, t0, dia))
        // Equipo que arranca con el reloj en 1970 (sin hora de red todavía): "nunca" tiene
        // que seguir siendo "nunca", aunque `ahora - 0` sea menor que un día.
        assertFalse("nunca, con reloj en 1970", PolicyReconciler.consolidacionVigente(0L, 1_000L, dia))
        assertTrue(PolicyReconciler.consolidacionVigente(t0, t0, dia))
        assertTrue(PolicyReconciler.consolidacionVigente(t0, t0 + dia - 1, dia))
        assertFalse("vencida", PolicyReconciler.consolidacionVigente(t0, t0 + dia, dia))
        assertFalse("reloj para atrás", PolicyReconciler.consolidacionVigente(t0, t0 - 1, dia))
        assertFalse("valor corrupto", PolicyReconciler.consolidacionVigente(-5L, t0, dia))
    }

    // ── Suspensión de navegadores / WebView ──────────────────────────────────────────

    private val suspendida = 1 shl 30

    @Test
    fun paquetesAMandar_alSuspenderSaltaLasYaSuspendidas() {
        val flags = linkedMapOf("a" to 0, "b" to suspendida, "c" to (suspendida or 1), "d" to 1)
        assertEquals(listOf("a", "d"), PolicyReconciler.paquetesAMandar(flags, true, suspendida))
        // Todas suspendidas: no hay nada que mandar (y la llamada no se hace).
        assertEquals(emptyList<String>(), PolicyReconciler.paquetesAMandar(mapOf("b" to suspendida), true, suspendida))
    }

    @Test
    fun paquetesAMandar_alLiberarMandaTodas() {
        val flags = linkedMapOf("a" to 0, "b" to suspendida)
        assertEquals(listOf("a", "b"), PolicyReconciler.paquetesAMandar(flags, false, suspendida))
        assertEquals(emptyList<String>(), PolicyReconciler.paquetesAMandar(emptyMap(), true, suspendida))
    }

    // ── FRP ──────────────────────────────────────────────────────────────────────────

    private val cuentas = setOf("123456789", "987654321")
    private val viejaOk = FrpVieja(List(3) { cuentas }, habilitada = true, adminDeshabilitado = false)

    @Test
    fun frp_oficialIgualEstaAlDia() {
        assertTrue(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.Politica(true, cuentas), null))
    }

    @Test
    fun frp_oficialDistintaODeshabilitadaSeReaplica() {
        assertFalse(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.Politica(true, setOf("123456789")), null))
        assertFalse(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.Politica(false, cuentas), null))
        assertFalse(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.Politica(true, emptySet()), null))
    }

    @Test
    fun frp_conApiOficialSinPoliticaNoCuentaLaVieja() {
        // Equipo que pasó de Android 10 a 11+: la vía vieja quedó escrita de antes, pero
        // setFrpPolicy() hoy pondría la OFICIAL. No se puede dar por aplicada.
        assertFalse(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.SinPolitica, viejaOk))
    }

    @Test
    fun frp_sinApiONoSoportadaMiraLaVieja() {
        assertTrue(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.SinApi, viejaOk))
        assertTrue(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.NoSoportada, viejaOk))
        assertFalse(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.SinApi, null))
        assertFalse(PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.NoSoportada, null))
    }

    @Test
    fun frp_viejaIncompletaSeReaplica() {
        val unaClaveDistinta = FrpVieja(listOf(cuentas, cuentas, setOf("123456789")), true, false)
        val unaClaveVacia = FrpVieja(listOf(cuentas, null, cuentas), true, false)
        val deshabilitada = viejaOk.copy(habilitada = false)
        val adminDeshabilitado = viejaOk.copy(adminDeshabilitado = true)
        val sinClaves = FrpVieja(emptyList(), true, false)
        for (v in listOf(unaClaveDistinta, unaClaveVacia, deshabilitada, adminDeshabilitado, sinClaves)) {
            assertFalse(v.toString(), PolicyReconciler.frpAlDia(cuentas, true, FrpOficial.SinApi, v))
        }
    }

    @Test
    fun frp_sinEndurecimientoOSinCuentasSeReaplica() {
        assertFalse(PolicyReconciler.frpAlDia(cuentas, false, FrpOficial.Politica(true, cuentas), null))
        assertFalse(PolicyReconciler.frpAlDia(cuentas, false, FrpOficial.SinApi, viejaOk))
        assertFalse(PolicyReconciler.frpAlDia(emptySet(), true, FrpOficial.Politica(true, emptySet()), null))
    }
}
