package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ProductEntity
import com.example.data.SupermarketHistory
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    hasCurrentList: Boolean,
    onAddToCurrentList: (ProductEntity) -> Unit,
    products: List<ProductEntity>,
    onAddProduct: (String, String, String, Double) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var scannedBarcode by remember { mutableStateOf<String?>(null) }
    var newProductName by remember { mutableStateOf("") }
    var newProductCategory by remember { mutableStateOf("Otros") }
    var newProductPrice by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val barcode = result.contents
            val existing = products.find { it.barcode == barcode }
            if (existing != null) {
                searchQuery = existing.productName
            } else {
                scannedBarcode = barcode
                showAddDialog = true
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Nuevo Producto") },
            text = {
                Column {
                    Text("Código: ${scannedBarcode ?: "N/A"}")
                    OutlinedTextField(value = newProductName, onValueChange = { newProductName = it }, label = { Text("Nombre del Producto") })
                    OutlinedTextField(value = newProductCategory, onValueChange = { newProductCategory = it }, label = { Text("Categoría") })
                    OutlinedTextField(value = newProductPrice, onValueChange = { newProductPrice = it }, label = { Text("Precio Aproximado") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddProduct(scannedBarcode ?: "", newProductName, newProductCategory, newProductPrice.toDoubleOrNull() ?: 0.0)
                    showAddDialog = false
                    newProductName = ""
                    newProductPrice = ""
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancelar") }
            }
        )
    }

    val filteredCatalog = products.filter { 
        it.productName.contains(searchQuery, ignoreCase = true) || it.category.contains(searchQuery, ignoreCase = true)
    }.sortedBy { it.productName }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { scanLauncher.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES) }) }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Escanear Producto")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Catálogo de Productos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar por nombre o código...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filteredCatalog, key = { it.barcode }) { entry ->
                    var expanded by remember { mutableStateOf(false) }
                    val history = entry.supermarketHistory.sortedByDescending { it.date }
                    val latestSupermarket = history.firstOrNull()?.supermarket ?: "-"
                    val bestPrice = history.minByOrNull { it.price }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        onClick = { expanded = !expanded }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.productName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Box(
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(entry.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("$${String.format(Locale.US, "%.2f", entry.lastPrice)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        Text("en $latestSupermarket", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (hasCurrentList) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(onClick = { onAddToCurrentList(entry) }) {
                                            Icon(Icons.Default.Add, contentDescription = "Añadir a lista")
                                        }
                                    }
                                }
                            }
                            
                            if (expanded) {
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(modifier = Modifier.height(8.dp))
                                if (bestPrice != null) {
                                    Text("Mejor precio histórico:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                    Text("$${String.format(Locale.US, "%.2f", bestPrice.price)} en ${bestPrice.supermarket}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Historial de Compras:", style = MaterialTheme.typography.labelMedium)
                                history.take(5).forEach { historyItem ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${historyItem.date} - ${historyItem.supermarket}", style = MaterialTheme.typography.bodySmall)
                                        Text("$${String.format(Locale.US, "%.2f", historyItem.price)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
