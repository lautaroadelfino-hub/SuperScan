package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Formato
import com.example.data.ProductModel
import com.example.domain.ExtractedItem
import com.example.domain.ExtractedReceipt
import com.example.domain.ReceiptScannerService
import kotlinx.coroutines.launch
import kotlin.math.abs

// Revisión del ticket leído por Gemini. La IA se equivoca: la pantalla lo dice
// de entrada y hace que corregir sea barato. Nada se guarda sin pasar por acá.
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
    var totalAmount by remember { mutableStateOf(initialReceipt.totalAmount) }
    var items by remember { mutableStateOf(initialReceipt.items) }

    var editandoCabecera by remember { mutableStateOf(false) }
    var editandoItem by remember { mutableStateOf<Int?>(null) }
    var vinculandoItem by remember { mutableStateOf<Int?>(null) }

    val sumaItems = items.sumOf { it.totalPrice }
    // 1% de tolerancia: los redondeos del ticket no son un error
    val hayDiferencia = totalAmount > 0.0 && abs(sumaItems - totalAmount) > totalAmount * 0.01
    val desvio = if (totalAmount > 0.0) (sumaItems - totalAmount) / totalAmount * 100.0 else 0.0

    fun receiptFinal() = ExtractedReceipt(
        storeName = storeName,
        date = date,
        totalAmount = totalAmount,
        items = items
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.primary) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Revisar ticket",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            "Leído por IA · corregí lo que haga falta",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                    Button(
                        onClick = { onConfirm(receiptFinal()) },
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        )
                    ) { Text("Guardar", fontWeight = FontWeight.Bold) }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "resumen") {
                Card(
                    onClick = { editandoCabecera = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DatoCabecera("COMERCIO", storeName)
                        Spacer(modifier = Modifier.height(10.dp))
                        DatoCabecera("FECHA", date)
                        Spacer(modifier = Modifier.height(10.dp))
                        DatoCabecera("TOTAL", Formato.precio(totalAmount), grande = true)
                        Text(
                            "Tocá para corregir",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            // El desvío no se esconde: si la suma no cierra, hay que decirlo y
            // ofrecer la corrección de un toque.
            if (hayDiferencia) {
                item(key = "mismatch") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "La suma de ítems (${Formato.precio(sumaItems)}) difiere del total leído " +
                                    "(${Formato.precio(totalAmount)}) un ${Formato.porcentaje(desvio)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { totalAmount = sumaItems },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) { Text("Usar suma", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }

            item(key = "titulo_items") {
                Text(
                    "${items.size} ${if (items.size == 1) "ítem" else "ítems"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            itemsIndexed(items, key = { i, _ -> i }) { index, item ->
                FilaItemTicket(
                    item = item,
                    onEditar = { editandoItem = index },
                    onVincular = { vinculandoItem = index }
                )
            }

            item(key = "pie") {
                Text(
                    "Al guardar, estos ${items.size} precios se aportan (anónimos) al catálogo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
                )
            }
        }
    }

    if (editandoCabecera) {
        EditarCabeceraSheet(
            comercio = storeName,
            fecha = date,
            total = totalAmount,
            onDismiss = { editandoCabecera = false },
            onGuardar = { c, f, t ->
                storeName = c
                date = f
                totalAmount = t
                editandoCabecera = false
            }
        )
    }

    editandoItem?.let { index ->
        items.getOrNull(index)?.let { item ->
            EditarItemSheet(
                item = item,
                onDismiss = { editandoItem = null },
                onGuardar = { nuevo ->
                    items = items.toMutableList().also { it[index] = nuevo }
                    editandoItem = null
                },
                onEliminar = {
                    items = items.toMutableList().also { it.removeAt(index) }
                    editandoItem = null
                }
            )
        }
    }

    vinculandoItem?.let { index ->
        items.getOrNull(index)?.let { item ->
            VincularProductoSheet(
                consultaInicial = item.productName,
                onBuscar = onSearchProducts,
                onDismiss = { vinculandoItem = null },
                onElegir = { producto ->
                    items = items.toMutableList().also {
                        it[index] = item.copy(barcode = producto.ean)
                    }
                    vinculandoItem = null
                }
            )
        }
    }
}

@Composable
private fun DatoCabecera(etiqueta: String, valor: String, grande: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        val colorSubrayado = MaterialTheme.colorScheme.outline
        Text(
            valor,
            style = if (grande) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
            fontWeight = if (grande) FontWeight.Black else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // El punteado dice "esto se puede tocar y cambiar"
            modifier = Modifier.subrayadoPunteado(colorSubrayado)
        )
    }
}

private fun Modifier.subrayadoPunteado(color: Color) = drawBehind {
    val y = size.height
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
    )
}

@Composable
private fun FilaItemTicket(
    item: ExtractedItem,
    onEditar: () -> Unit,
    onVincular: () -> Unit
) {
    Surface(
        onClick = onEditar,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.productName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${Formato.cantidad(item.quantity)} × ${Formato.precio(item.unitPrice)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    Formato.precio(item.totalPrice),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.category.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            item.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // Vincular al catálogo es lo que convierte el precio en dato útil
                if (item.barcode != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "vinculado",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        "vincular al catálogo →",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onVincular() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditarCabeceraSheet(
    comercio: String,
    fecha: String,
    total: Double,
    onDismiss: () -> Unit,
    onGuardar: (String, String, Double) -> Unit
) {
    var c by remember { mutableStateOf(comercio) }
    var f by remember { mutableStateOf(fecha) }
    var t by remember { mutableStateOf(if (total == 0.0) "" else total.toString()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Datos del ticket", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = c,
                onValueChange = { c = it },
                label = { Text("Comercio") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = f,
                onValueChange = { f = it },
                label = { Text("Fecha (AAAA-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = t,
                onValueChange = { t = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Total") },
                prefix = { Text("$") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onGuardar(c, f, t.toDoubleOrNull() ?: 0.0) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(999.dp)
            ) { Text("Listo", fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditarItemSheet(
    item: ExtractedItem,
    onDismiss: () -> Unit,
    onGuardar: (ExtractedItem) -> Unit,
    onEliminar: () -> Unit
) {
    var nombre by remember { mutableStateOf(item.productName) }
    var cantidad by remember { mutableStateOf(Formato.cantidad(item.quantity)) }
    var unitario by remember { mutableStateOf(if (item.unitPrice == 0.0) "" else item.unitPrice.toString()) }
    var total by remember { mutableStateOf(if (item.totalPrice == 0.0) "" else item.totalPrice.toString()) }
    var categoria by remember { mutableStateOf(item.category) }
    var categoriaAbierta by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Corregir ítem", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = nombre,
                onValueChange = { nombre = it },
                label = { Text("Descripción") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cantidad,
                    onValueChange = { cantidad = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Cant.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = unitario,
                    onValueChange = { unitario = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Precio un.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = total,
                    onValueChange = { total = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Total") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = categoriaAbierta,
                onExpandedChange = { categoriaAbierta = it }
            ) {
                OutlinedTextField(
                    value = categoria,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Categoría") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoriaAbierta) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = categoriaAbierta,
                    onDismissRequest = { categoriaAbierta = false }
                ) {
                    ReceiptScannerService.PRESET_CATEGORIES.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat) },
                            onClick = {
                                categoria = cat
                                categoriaAbierta = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    onGuardar(
                        item.copy(
                            productName = nombre,
                            quantity = cantidad.replace(',', '.').toDoubleOrNull() ?: item.quantity,
                            unitPrice = unitario.toDoubleOrNull() ?: 0.0,
                            totalPrice = total.toDoubleOrNull() ?: 0.0,
                            category = categoria
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(999.dp)
            ) { Text("Listo", fontWeight = FontWeight.Bold) }
            TextButton(onClick = onEliminar, modifier = Modifier.fillMaxWidth()) {
                Text("Sacar este ítem del ticket", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VincularProductoSheet(
    consultaInicial: String,
    onBuscar: suspend (String) -> List<ProductModel>,
    onDismiss: () -> Unit,
    onElegir: (ProductModel) -> Unit
) {
    var consulta by remember { mutableStateOf(consultaInicial) }
    var resultados by remember { mutableStateOf<List<ProductModel>>(emptyList()) }
    var buscando by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun buscar() {
        scope.launch {
            buscando = true
            resultados = onBuscar(consulta)
            buscando = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Vincular al catálogo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "La búsqueda es por el comienzo del nombre del catálogo, no por el del ticket.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = consulta,
                onValueChange = { consulta = it },
                label = { Text("Buscar") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { buscar() }) {
                        Icon(Icons.Default.Search, contentDescription = "Buscar")
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            when {
                buscando -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp
                )
                resultados.isEmpty() -> Text(
                    "Buscá y elegí el producto del catálogo que corresponde.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(resultados) { _, producto ->
                        ListItem(
                            headlineContent = { Text(producto.descripcion) },
                            supportingContent = { Text("EAN ${producto.ean}") },
                            leadingContent = { MiniaturaProducto(imagen = producto.imagen, size = 36.dp) },
                            modifier = Modifier.clickable { onElegir(producto) }
                        )
                    }
                }
            }
        }
    }
}
