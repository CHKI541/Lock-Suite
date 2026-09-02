package com.ejemplo.locksuite.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * NokiaKeypadScreen — modo kiosco de teléfono de teclas (2/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ ES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Pedido del dueño: *"un modo kiosco especial que imita a esta app… y que se controla
 * con botones"*, tomando como referencia KeyLauncher (mitmachim.top/post/1235807), y con
 * *"opción de activar/desactivar el touch"*.
 *
 * Lo que se reprodujo es el **modelo de interacción de un teléfono de teclas**, que es una
 * convención de la industria y no de nadie en particular: pantalla de inicio con reloj
 * grande, menú de 3×3 con las apps numeradas del 1 al 9, navegación con la cruceta,
 * selección con el centro, vuelta atrás con la tecla derecha. Nada de esto se decompiló ni
 * se copió de la app de referencia; los íconos son propios (ver `NokiaIconSet`).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ EL ESTADO VIVE EN LA ACTIVITY Y NO ACÁ
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Las teclas físicas llegan por `onKeyDown()` de la Activity, no por el sistema de foco de
 * Compose. Se podría puentear con `focusRequester`/`onKeyEvent`, pero eso obliga a pelear
 * con el foco en cada recomposición y a que el orden de foco coincida con el orden visual
 * — que es exactamente donde este tipo de pantallas se rompe. Acá la Activity es dueña de
 * `seleccion` y de `enMenu`, y esta función solo dibuja: no hay dos fuentes de verdad
 * sobre qué está seleccionado.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CÓMO SE VE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Fondo crema (como los Series 40 sobre LCD), tipografía monoespaciada para el reloj,
 * y cada app en un cuadrado de color liso con la silueta en blanco. El seleccionado se
 * marca con un borde grueso oscuro, no con un cambio de color: en una pantalla chica y a
 * contraluz, el borde se ve y el matiz no.
 */

private val CREMA = Color(0xFFF5EFE3)
private val TINTA = Color(0xFF1E1E1E)
private val TINTA_SUAVE = Color(0xFF5B6770)
private val BARRA = Color(0xFFDCD3C2)

@Composable
fun NokiaKeypadScreen(
    apps: List<AppItem>,
    enMenu: Boolean,
    seleccion: Int,
    pagina: Int,
    hora: String,
    fecha: String,
    bateria: Int,
    touchHabilitado: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CREMA)
    ) {
        BarraEstado(bateria = bateria, touchHabilitado = touchHabilitado)

        Box(modifier = Modifier.weight(1f)) {
            if (enMenu) {
                MenuApps(apps = apps, seleccion = seleccion, pagina = pagina)
            } else {
                Inicio(hora = hora, fecha = fecha)
            }
        }

        // Barra de teclas suaves. En un teléfono de teclas de verdad estas etiquetas
        // corresponden a las dos teclas de abajo de la pantalla; en un equipo táctil sin
        // esas teclas quedan como referencia visual de qué hace cada botón.
        BarraTeclas(
            izquierda = if (enMenu) "Abrir" else "Menú",
            derecha = if (enMenu) "Atrás" else "Apagar pantalla"
        )
    }
}

@Composable
private fun BarraEstado(bateria: Int, touchHabilitado: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BARRA)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.SignalCellularAlt,
            contentDescription = null,
            tint = TINTA,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.weight(1f))
        // Cuando el táctil está apagado se dice, y no con un ícono que haya que adivinar:
        // es la diferencia entre "el equipo está trabado" y "el equipo está en modo teclas".
        if (!touchHabilitado) {
            Text(
                text = "SOLO TECLAS",
                color = TINTA_SUAVE,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "$bateria%",
            color = TINTA,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun Inicio(hora: String, fecha: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = hora,
            color = TINTA,
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = fecha,
            color = TINTA_SUAVE,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun MenuApps(apps: List<AppItem>, seleccion: Int, pagina: Int) {
    val porPagina = 9
    val desde = pagina * porPagina
    val visibles = apps.drop(desde).take(porPagina)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Tres filas de tres. Se dibuja siempre la grilla completa aunque falten apps, para
        // que el número de cada casilla no se mueva de lugar entre páginas: en un teléfono
        // de teclas la posición ES el atajo, y que "5" cambie de app según la página sería
        // exactamente el error que hace inusable este tipo de menú.
        for (fila in 0 until 3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (col in 0 until 3) {
                    val indiceEnPagina = fila * 3 + col
                    val app = visibles.getOrNull(indiceEnPagina)
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (app != null) {
                            Casilla(
                                app = app,
                                numero = indiceEnPagina + 1,
                                seleccionada = (desde + indiceEnPagina) == seleccion
                            )
                        }
                    }
                }
            }
            if (fila < 2) Spacer(Modifier.height(10.dp))
        }

        val paginas = if (apps.isEmpty()) 1 else ((apps.size + porPagina - 1) / porPagina)
        if (paginas > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${pagina + 1} / $paginas",
                color = TINTA_SUAVE,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Casilla(app: AppItem, numero: Int, seleccionada: Boolean) {
    val nokia = NokiaIconSet.para(app.packageName, app.label)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (seleccionada) {
                    Modifier.border(3.dp, TINTA, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(nokia.tile),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = nokia.icon,
                contentDescription = nokia.label,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = nokia.label,
            color = TINTA,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = numero.toString(),
            color = TINTA_SUAVE,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BarraTeclas(izquierda: String, derecha: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BARRA)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(izquierda, color = TINTA, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(derecha, color = TINTA, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
