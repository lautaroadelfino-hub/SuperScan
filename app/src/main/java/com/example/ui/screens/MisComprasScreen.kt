package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.ReceiptEntity

// "Mis compras": todo lo de DESPUÉS de comprar en un solo lugar — el historial
// de tickets y las estadísticas (antes eran dos solapas del mismo tema).
// No es una billetera: acá no vive plata, viven tus tickets.
@Composable
fun MisComprasScreen(
    receipts: List<ReceiptEntity>,
    budget: Double,
    cargando: Boolean,
    errorCarga: String?,
    onReintentar: () -> Unit,
    onUpdateBudget: (Double) -> Unit,
    onDeleteReceipt: (String) -> Unit
) {
    var pestana by rememberSaveable { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        PildoraSegmentada(
            opciones = listOf("Historial", "Estadísticas"),
            seleccion = pestana,
            onSelect = { pestana = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        when (pestana) {
            0 -> {
                when {
                    errorCarga != null -> {
                        ErrorDeCarga(
                            titulo = "No pudimos traer tu historial",
                            causa = errorCarga,
                            onReintentar = onReintentar,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    // Nunca mostrar "vacío" mientras carga: el vacío es una
                    // afirmación fuerte ("no compraste nada") y sería mentira.
                    cargando -> {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            repeat(4) {
                                SkeletonBox(modifier = Modifier.fillMaxWidth().height(84.dp))
                            }
                        }
                    }
                    receipts.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Todavía no cargaste ningún ticket",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Tocá el botón amarillo y sacale una foto al ticket del súper: " +
                                    "tus gastos se registran solos y tus precios ayudan a toda la comunidad.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(receipts, key = { it.id }) { receipt ->
                                ReceiptCard(
                                    receipt = receipt,
                                    onDelete = onDeleteReceipt,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
            1 -> StatsScreen(receipts = receipts, prefs = budget, onUpdateBudget = onUpdateBudget)
        }
    }
}

// Píldora segmentada del mockup: canal gris y un "thumb" blanco con sombra
// sobre la opción activa.
@Composable
private fun PildoraSegmentada(
    opciones: List<String>,
    seleccion: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            opciones.forEachIndexed { indice, opcion ->
                val activa = indice == seleccion
                val color by animateColorAsState(
                    targetValue = if (activa) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant,
                    label = "segmento_$indice"
                )
                Surface(
                    color = color,
                    shape = RoundedCornerShape(999.dp),
                    shadowElevation = if (activa) 2.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(indice) }
                ) {
                    Text(
                        opcion,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (activa) FontWeight.Bold else FontWeight.Medium,
                        color = if (activa) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)
                    )
                }
            }
        }
    }
}
