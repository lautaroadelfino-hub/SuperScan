package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ReceiptEntity

// "Mis compras": todo lo de DESPUÉS de comprar en un solo lugar — el historial
// de tickets y las estadísticas (antes eran dos solapas del mismo tema).
// No es una billetera: acá no vive plata, viven tus tickets.
@Composable
fun MisComprasScreen(
    receipts: List<ReceiptEntity>,
    budget: Double,
    onUpdateBudget: (Double) -> Unit,
    onDeleteReceipt: (String) -> Unit
) {
    var pestana by rememberSaveable { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SegmentedButton(
                selected = pestana == 0,
                onClick = { pestana = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text("Historial") }
            SegmentedButton(
                selected = pestana == 1,
                onClick = { pestana = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text("Estadísticas") }
        }

        when (pestana) {
            0 -> {
                if (receipts.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Todavía no cargaste ningún ticket",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tocá el botón amarillo y sacale una foto al ticket del súper: " +
                                "tus gastos se registran solos y tus precios ayudan a toda la comunidad.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
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
            1 -> StatsScreen(receipts = receipts, prefs = budget, onUpdateBudget = onUpdateBudget)
        }
    }
}
