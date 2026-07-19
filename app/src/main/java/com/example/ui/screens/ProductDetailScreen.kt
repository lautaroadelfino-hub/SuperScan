package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.Cadenas
import com.example.data.DisplayPrice
import com.example.data.Precios
import com.example.data.ProductModel
import java.util.Locale

// Atribución de la imagen del detalle:
//  - Open Food Facts (o histórico sin fuente) exige el crédito por licencia CC-BY-SA.
//  - Imágenes de las cadenas (VTEX): "imagen ilustrativa", que puede no coincidir
//    exactamente con la presentación vendida.
private fun creditoImagen(producto: ProductModel): String? = when {
    producto.imagen == null && producto.imagen_grande == null -> null
    producto.imagen_fuente in listOf("vea", "jumbo", "carrefour", "dia") -> "Imagen ilustrativa"
    else -> "Imagen: Open Food Facts"
}

// Detalle de producto: precio a mostrar (con la observación propia del usuario
// como prioridad máxima) y comparador con una fila por cadena del map precios,
// de menor a mayor, resaltando la más barata y el % de diferencia contra ella.
@Composable
fun ProductDetailScreen(
    producto: ProductModel,
    displayPrice: DisplayPrice?, // null mientras se resuelve la consulta
    hasCurrentList: Boolean,
    onAddToList: (ProductModel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center
        ) {
            MiniaturaProducto(imagen = producto.imagen_grande ?: producto.imagen, size = 160.dp)
        }
        creditoImagen(producto)?.let { credito ->
            Text(
                credito,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (producto.marca.isNotBlank()) {
            Text(
                producto.marca,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            producto.descripcion,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            listOf(producto.categoria, producto.subcategoria).filter { it.isNotBlank() }.joinToString(" › "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "EAN: ${producto.ean}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (producto.revisar) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Este dato está en revisión y puede ser impreciso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                when (displayPrice) {
                    null -> SkeletonBox(modifier = Modifier.height(28.dp).fillMaxWidth(0.6f))
                    is DisplayPrice.None -> Column {
                        Text(
                            "Sin precio todavía",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "¿Lo viste en góndola? Escanealo en el Modo Súper para informar su precio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is DisplayPrice.Failure -> Text(
                        displayPrice.mensaje,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> PrecioResumen(displayPrice)
                }
            }
        }

        val comparador = Precios.comparador(producto.precios)
        if (comparador.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Comparador de precios",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    comparador.forEach { fila ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (fila.esMinimo) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(
                                    Cadenas.nombre(fila.cadenaId),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (fila.esMinimo) FontWeight.Bold else FontWeight.Normal
                                )
                                if (fila.esMinimo) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "más barato",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "$${String.format(Locale.US, "%.2f", fila.precio)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (!fila.esMinimo) {
                                    Text(
                                        String.format(Locale.US, "+%.1f%%", fila.difPorcentaje),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hasCurrentList) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onAddToList(producto) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Añadir a una lista")
            }
        }
    }
}
