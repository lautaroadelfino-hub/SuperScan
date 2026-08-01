package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.EstadoPrecios
import com.example.data.Formato

/**
 * Lo que Góndola tiene, dicho sin inflar: productos CON PRECIO (no el total de
 * la base, que incluye miles de cosas que no se consiguen acá), cuántos precios
 * y de cuándo son. Si el dato no está, no se muestra nada: un número inventado
 * acá haría exactamente el daño que la app trata de evitar.
 */
@Composable
fun SelloCobertura(
    estado: EstadoPrecios?,
    modifier: Modifier = Modifier,
    ciudad: String = "Tandil"
) {
    if (estado == null || !estado.tieneDatos()) return

    val fecha = estado.fecha_datos.takeIf { it.isNotBlank() }?.let { Formato.fechaCorta(it) }
    val cadenas = estado.cadenas()

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "${Formato.entero(estado.productos_con_precio)} productos con precio",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            buildString {
                append("${Formato.entero(estado.precios_totales)} precios")
                if (cadenas > 0) {
                    append(" de $cadenas ")
                    append(if (cadenas == 1) "súper" else "súper")
                    append(" de $ciudad")
                }
                if (fecha != null) append(" · al $fecha")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
