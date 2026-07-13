package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.domain.ExtractedReceipt
import com.example.domain.ExtractedItem
import com.example.domain.ReceiptScannerService
import com.example.data.ProductModel
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptConfirmationScreen(
    initialReceipt: ExtractedReceipt,
    onConfirm: (ExtractedReceipt) -> Unit,
    onCancel: () -> Unit,
    onSearchProducts: suspend (String) -> List<ProductModel>
) {
    var storeName by remember { mutableStateOf(initialReceipt.storeName) }
    var date by remember { mutableStateOf(initialReceipt.date) }
    var totalAmount by remember { mutableStateOf(initialReceipt.totalAmount.toString()) }
    var items by remember { mutableStateOf(initialReceipt.items) }

    val totalAmountDouble = totalAmount.toDoubleOrNull() ?: 0.0
    val itemsSum = items.sumOf { it.totalPrice }
    val isTotalMismatch = abs(itemsSum - totalAmountDouble) > (totalAmountDouble * 0.01)

    var showSearchDialog by remember { mutableStateOf<Int?>(null) } // Index of item being searched
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirmar Ticket") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancelar") }
                },
                actions = {
                    TextButton(onClick = {
                        val finalReceipt = ExtractedReceipt(
                            storeName = storeName,
                            date = date,
                            totalAmount = totalAmountDouble,
                            items = items
                        )
                        onConfirm(finalReceipt)
                    }) {
                        Text("Guardar", color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = storeName,
                onValueChange = { storeName = it },
                label = { Text("Comercio") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text("Fecha (AAAA-MM-DD)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = totalAmount,
                onValueChange = { totalAmount = it },
                label = { Text("Total") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                isError = isTotalMismatch
            )
            if (isTotalMismatch) {
                Text(
                    text = "⚠️ La suma de ítems ($${String.format(java.util.Locale.US, "%.2f", itemsSum)}) difiere del total por más del 1%.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Ítems", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(items) { index, item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            OutlinedTextField(
                                value = item.productName,
                                onValueChange = { newVal ->
                                    val newItems = items.toMutableList()
                                    newItems[index] = item.copy(productName = newVal)
                                    items = newItems
                                },
                                label = { Text("Descripción") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = if(item.quantity == 0.0) "" else item.quantity.toString(),
                                    onValueChange = { newVal ->
                                        val newItems = items.toMutableList()
                                        newItems[index] = item.copy(quantity = newVal.toDoubleOrNull() ?: 0.0)
                                        items = newItems
                                    },
                                    label = { Text("Cant.") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = if(item.unitPrice == 0.0) "" else item.unitPrice.toString(),
                                    onValueChange = { newVal ->
                                        val newItems = items.toMutableList()
                                        newItems[index] = item.copy(unitPrice = newVal.toDoubleOrNull() ?: 0.0)
                                        items = newItems
                                    },
                                    label = { Text("Precio Un.") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = if(item.totalPrice == 0.0) "" else item.totalPrice.toString(),
                                    onValueChange = { newVal ->
                                        val newItems = items.toMutableList()
                                        newItems[index] = item.copy(totalPrice = newVal.toDoubleOrNull() ?: 0.0)
                                        items = newItems
                                    },
                                    label = { Text("Total") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            var categoryExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = categoryExpanded,
                                onExpandedChange = { categoryExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = item.category,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Categoría") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = categoryExpanded,
                                    onDismissRequest = { categoryExpanded = false }
                                ) {
                                    ReceiptScannerService.PRESET_CATEGORIES.forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat) },
                                            onClick = {
                                                val newItems = items.toMutableList()
                                                newItems[index] = item.copy(category = cat)
                                                items = newItems
                                                categoryExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (item.barcode != null) {
                                Text("Vinculado: EAN ${item.barcode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                TextButton(onClick = { showSearchDialog = index }) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Vincular a producto")
                                }
                            }
                        }
                    }
                }
            }
            
            Button(
                onClick = {
                    val finalReceipt = ExtractedReceipt(
                        storeName = storeName,
                        date = date,
                        totalAmount = totalAmountDouble,
                        items = items
                    )
                    onConfirm(finalReceipt)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text("Confirmar y guardar")
            }
        }
    }

    if (showSearchDialog != null) {
        val index = showSearchDialog!!
        val itemToLink = items[index]
        var searchQuery by remember { mutableStateOf(itemToLink.productName) }
        var searchResults by remember { mutableStateOf<List<ProductModel>>(emptyList()) }
        var isSearching by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showSearchDialog = null },
            title = { Text("Vincular Producto") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Buscar por descripción") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                scope.launch {
                                    isSearching = true
                                    searchResults = onSearchProducts(searchQuery)
                                    isSearching = false
                                }
                            }) {
                                Icon(Icons.Default.Search, contentDescription = "Buscar")
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(searchResults.size) { i ->
                                val prod = searchResults[i]
                                ListItem(
                                    headlineContent = { Text(prod.descripcion) },
                                    supportingContent = { Text("EAN: ${prod.ean}") },
                                    modifier = Modifier.clickable {
                                        val newItems = items.toMutableList()
                                        newItems[index] = itemToLink.copy(barcode = prod.ean)
                                        items = newItems
                                        showSearchDialog = null
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchDialog = null }) { Text("Cancelar") }
            }
        )
    }
}
