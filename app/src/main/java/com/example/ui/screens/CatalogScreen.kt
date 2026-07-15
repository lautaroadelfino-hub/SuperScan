package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.ProductEntity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    hasCurrentList: Boolean,
    onAddToCurrentList: (ProductEntity) -> Unit,
    localProducts: List<ProductEntity>,
    remoteProducts: List<ProductEntity>,
    isSearchingRemote: Boolean,
    categories: List<String>,
    onSearchQueryChange: (String) -> Unit,
    onLookupBarcode: suspend (String) -> ProductEntity?,
    onAddProduct: (String, String, String, Double) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var scannedBarcode by remember { mutableStateOf<String?>(null) }
    var newProductName by remember { mutableStateOf("") }
    var newProductCategory by remember { mutableStateOf("Otros") }
    var newProductPrice by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val barcode = result.contents
            scope.launch {
                // Busca en el catálogo personal y en Firestore (normaliza a GTIN-13)
                val existing = onLookupBarcode(barcode)
                if (existing != null) {
                    searchQuery = existing.productName
                    onSearchQueryChange(searchQuery)
                } else {
                    scannedBarcode = barcode
                    showAddDialog = true
                }
            }
        }
    }

    if (showAddDialog) {
        var categoryMenuExpanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Nuevo Producto") },
            text = {
                Column {
                    Text("Código: ${scannedBarcode ?: "N/A"}")
                    OutlinedTextField(
                        value = newProductName,
                        onValueChange = { newProductName = it },
                        label = { Text("Nombre del Producto") }
                    )
                    Box {
                        OutlinedTextField(
                            value = newProductCategory,
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            label = { Text("Categoría") },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.clickable { categoryMenuExpanded = true }
                        )
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false }
                        ) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat) },
                                    onClick = {
                                        newProductCategory = cat
                                        categoryMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newProductPrice,
                        onValueChange = { input -> if (input.all { it.isDigit() || it == '.' }) newProductPrice = input },
                        label = { Text("Precio Aproximado") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newProductName.isNotBlank(),
                    onClick = {
                        onAddProduct(scannedBarcode ?: "", newProductName.trim(), newProductCategory, newProductPrice.toDoubleOrNull() ?: 0.0)
                        showAddDialog = false
                        newProductName = ""
                        newProductPrice = ""
                        newProductCategory = "Otros"
                    }
                ) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancelar") }
            }
        )
    }

    val filteredLocal = localProducts.filter {
        it.productName.contains(searchQuery, ignoreCase = true) || it.category.contains(searchQuery, ignoreCase = true)
    }.sortedBy { it.productName }

    // Evita repetir en "Resultados Globales" productos que ya están en "Mis Productos"
    val filteredRemote = remoteProducts.filter { remote ->
        localProducts.none { it.barcode == remote.barcode }
    }

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
                onValueChange = {
                    searchQuery = it
                    onSearchQueryChange(it)
                },
                placeholder = { Text("Buscar en 60.000+ productos...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
                trailingIcon = {
                    if (isSearchingRemote) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filteredLocal.isNotEmpty()) {
                    item { Text("Mis Productos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    items(filteredLocal, key = { "local_${it.barcode}" }) { entry ->
                        ProductItemRow(entry, hasCurrentList, onAddToCurrentList)
                    }
                }

                if (filteredRemote.isNotEmpty()) {
                    item { Text("Resultados Globales", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp)) }
                    items(filteredRemote, key = { "remote_${it.barcode}" }) { entry ->
                        ProductItemRow(entry, hasCurrentList, onAddToCurrentList)
                    }
                } else if (searchQuery.isNotBlank() && !isSearchingRemote && filteredLocal.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No se encontraron productos", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProductItemRow(
    entry: ProductEntity,
    hasCurrentList: Boolean,
    onAddToCurrentList: (ProductEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val history = entry.supermarketHistory.sortedByDescending { it.date }
    val latestSupermarket = history.firstOrNull()?.supermarket ?: "-"
    val bestPrice = history.minByOrNull { it.price }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = { if (history.isNotEmpty()) expanded = !expanded }
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
                        if (entry.lastPrice > 0.0) {
                            val userHistory = entry.supermarketHistory
                            if (userHistory.isNotEmpty()) {
                                // User has their own history
                                Text("$${String.format(Locale.US, "%.2f", entry.lastPrice)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("último precio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                // SEPA reference price
                                Text("$${String.format(Locale.US, "%.2f", entry.lastPrice)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("precio de referencia", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            }
                            if (latestSupermarket != "-") {
                                Text("en $latestSupermarket", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Text("Sin precio", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (hasCurrentList) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { onAddToCurrentList(entry) }) {
                            Icon(Icons.Default.Add, contentDescription = "Añadir a lista")
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
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
