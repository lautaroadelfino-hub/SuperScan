package com.example.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Cadenas
import com.example.data.ComparadorLista
import com.example.data.Formato
import com.example.data.SharedListItemModel
import com.example.data.SharedListModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun ShoppingListsScreen(
    viewModel: MainViewModel,
    lists: List<SharedListModel>,
    currentItems: List<SharedListItemModel>,
    activeListId: String?,
    gastoDelMes: Double,
    aportes: Int,
    onBuscar: () -> Unit,
    onListSelected: (String) -> Unit,
    onItemToggled: (String, Boolean) -> Unit,
    onItemDeleted: (String) -> Unit,
    onAddMember: (String) -> Unit,
    onCreateList: (String) -> Unit
) {
    var selectedListId by remember { mutableStateOf<String?>(null) }
    val comparacion by viewModel.comparacionLista.collectAsState()
    var superMode by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var showCreateListDialog by remember { mutableStateOf(false) }
    var showDeleteListDialog by remember { mutableStateOf(false) }
    var showClearItemsDialog by remember { mutableStateOf(false) }
    var editingQuantityItem by remember { mutableStateOf<SharedListItemModel?>(null) }
    
    var newMemberEmail by remember { mutableStateOf("") }
    var newListName by remember { mutableStateOf("") }
    var newQuantity by remember { mutableStateOf("") }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    if (showCreateListDialog) {
        AlertDialog(
            onDismissRequest = { showCreateListDialog = false },
            title = { Text("Nueva Lista") },
            text = {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("Nombre de la lista") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newListName.isNotBlank()) {
                        onCreateList(newListName)
                    }
                    showCreateListDialog = false
                    newListName = ""
                }) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateListDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showDeleteListDialog && selectedListId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteListDialog = false },
            title = { Text("¿Eliminar lista?") },
            text = { Text("Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteShoppingList(selectedListId!!)
                    selectedListId = null
                    showDeleteListDialog = false
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteListDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showClearItemsDialog && selectedListId != null) {
        AlertDialog(
            onDismissRequest = { showClearItemsDialog = false },
            title = { Text("¿Vaciar lista?") },
            text = { Text("Se borrarán todos los productos de esta lista.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearShoppingListItems()
                    showClearItemsDialog = false
                }) { Text("Vaciar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearItemsDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (editingQuantityItem != null) {
        AlertDialog(
            onDismissRequest = { editingQuantityItem = null },
            title = { Text("Ajustar Cantidad") },
            text = {
                Column {
                    Text(editingQuantityItem!!.productName)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newQuantity,
                        onValueChange = { if (it.all { c -> c.isDigit() || c == '.' }) newQuantity = it },
                        label = { Text("Cantidad") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val qty = newQuantity.toDoubleOrNull() ?: 1.0
                    viewModel.updateShoppingItemQuantity(editingQuantityItem!!.id, qty)
                    editingQuantityItem = null
                    newQuantity = ""
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { editingQuantityItem = null }) { Text("Cancelar") }
            }
        )
    }

    if (showAddMemberDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false },
            title = { Text("Invitar Miembro") },
            text = {
                Column {
                    Text("Ingresa el email del usuario para añadirlo a esta lista.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newMemberEmail,
                        onValueChange = { newMemberEmail = it },
                        label = { Text("Email") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newMemberEmail.isNotBlank()) {
                        onAddMember(newMemberEmail.trim())
                    }
                    showAddMemberDialog = false
                    newMemberEmail = ""
                }) { Text("Añadir") }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemberDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (superMode && selectedListId != null) {
        SuperModeScreen(
            viewModel = viewModel,
            listId = selectedListId!!,
            onClose = { superMode = false }
        )
        return
    }

    // (la tarjeta del comparador está definida al final del archivo)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (selectedListId == null) {
                FloatingActionButton(onClick = { showCreateListDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Nueva Lista")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (selectedListId == null) {
                // ---- Portada Góndola: buscador, hero del comparador, resumen ----
                Surface(
                    onClick = onBuscar,
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "¿Qué estás por comprar?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val listaActiva = lists.find { it.id == activeListId }
                    val comp = comparacion
                    if (listaActiva != null && comp != null) {
                        item(key = "hero") {
                            ComparadorListaCard(
                                nombreLista = listaActiva.name,
                                comparacion = comp,
                                onClick = {
                                    selectedListId = listaActiva.id
                                    onListSelected(listaActiva.id)
                                }
                            )
                        }
                    }
                    item(key = "resumen") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MiniResumenCard(
                                titulo = "Gasto del mes",
                                valor = Formato.precio(gastoDelMes),
                                modifier = Modifier.weight(1f)
                            )
                            MiniResumenCard(
                                titulo = "Tu aporte",
                                valor = "$aportes precios",
                                detalle = "informados a la comunidad",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item(key = "titulo_listas") {
                        Text(
                            "Tus listas",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    if (lists.isEmpty()) {
                        item {
                            Text(
                                "Creá tu primera lista con el botón + y Góndola te dice a qué súper conviene ir.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(lists, key = { it.id }) { list ->
                            Card(
                                modifier = Modifier.fillMaxWidth().animateItem().clickable {
                                    selectedListId = list.id
                                    onListSelected(list.id)
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (list.members.size > 1) Icons.Default.Share else Icons.AutoMirrored.Filled.List,
                                        contentDescription = null,
                                        tint = if (list.members.size > 1) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(list.name, style = MaterialTheme.typography.titleMedium)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val isCollab = list.members.size > 1
                                            Surface(
                                                color = if (isCollab) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                                                shape = MaterialTheme.shapes.extraSmall
                                            ) {
                                                Text(
                                                    if (isCollab) "Colaborativa" else "Personal",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    color = if (isCollab) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("${list.members.size} miembros", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(onClick = { 
                                        selectedListId = list.id
                                        showDeleteListDialog = true 
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Borrar Lista", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                                    }
                                }
                            }
                        }
                    }
            } else {
                val list = lists.find { it.id == selectedListId }
                val listName = list?.name ?: "Lista"
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        TextButton(onClick = { selectedListId = null }) {
                            Text("←")
                        }
                        Text(listName, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    }
                    
                    Row {
                        IconButton(onClick = { showClearItemsDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Vaciar Lista", tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = { showAddMemberDialog = true }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Invitar Miembro", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { superMode = true }) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = "Modo Súper", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                
                val comp = comparacion
                if (comp != null) {
                    // El corazón de Góndola: la lista cotizada en cada súper
                    ComparadorListaCard(nombreLista = listName, comparacion = comp, onClick = {})
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (currentItems.isNotEmpty()) {
                    // Sin datos de catálogo (offline sin caché): total estimado clásico
                    val estimatedTotal = currentItems.sumOf { it.expectedPrice * it.targetQuantity }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Total Estimado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(Formato.precio(estimatedTotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                
                if (list != null && list.members.size > 1) {
                    Text("Colaborativa: ${list.members.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                if (currentItems.isEmpty()) {
                    Text("La lista está vacía. Agregá productos desde el Catálogo o escaneando.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val groupedItems = currentItems.groupBy { it.category }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groupedItems.forEach { (category, items) ->
                            item(key = "header_$category") {
                                Text(
                                    text = if (category.isNotEmpty()) category else "Otros",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                                )
                            }
                            items(items.sortedBy { it.scanned }, key = { it.id }) { item ->
                                val cardColor by animateColorAsState(
                                    targetValue = if (item.scanned) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                                    label = "item_card_color"
                                )
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onItemToggled(item.id, !item.scanned)
                                        },
                                    colors = CardDefaults.cardColors(containerColor = cardColor),
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (item.scanned) 0.dp else 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = item.scanned,
                                            onCheckedChange = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onItemToggled(item.id, it)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.productName,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = if (item.scanned) FontWeight.Normal else FontWeight.Bold,
                                                    textDecoration = if (item.scanned) TextDecoration.LineThrough else TextDecoration.None
                                                ),
                                                color = if (item.scanned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                            )
                                            Row(
                                                modifier = Modifier.clickable {
                                                    newQuantity = item.targetQuantity.toString()
                                                    editingQuantityItem = item
                                                },
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Cantidad: ${if(item.targetQuantity % 1.0 == 0.0) item.targetQuantity.toInt() else item.targetQuantity}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (item.scanned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                                    fontWeight = if (item.scanned) FontWeight.Normal else FontWeight.Bold
                                                )
                                                if (!item.scanned) {
                                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        }
                                        if (item.expectedPrice > 0.0) {
                                            Column(horizontalAlignment = Alignment.End) {
                                                val totalItem = item.expectedPrice * item.targetQuantity
                                                Text(
                                                    text = Formato.precio(totalItem),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (item.scanned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                                )
                                                if (item.targetQuantity > 1.0) {
                                                    Text(
                                                        text = "(${Formato.precio(item.expectedPrice)} u.)",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        IconButton(onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onItemDeleted(item.id)
                                        }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Eliminar producto",
                                                tint = MaterialTheme.colorScheme.error
                                            )
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
}

// La lista activa cotizada en cada súper: el hero de Góndola, calcado del
// mockup — tarjeta blanca, eyebrow verde, nombre del súper y total en negro
// bien grandes, chip de ahorro, y las demás cadenas como píldoras color papel.
// La cobertura ("9/12 con precio") se muestra siempre: un súper barato al que
// le faltan productos no necesariamente gana en serio.
@Composable
private fun ComparadorListaCard(
    nombreLista: String,
    comparacion: ComparadorLista.Resultado,
    onClick: () -> Unit
) {
    val mejor = comparacion.cadenas.first()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "CONVIENE IR A",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "$nombreLista · ${comparacion.itemsTotal} ítems",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                Cadenas.nombre(mejor.cadenaId),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    Formato.precio(mejor.total),
                    style = MaterialTheme.typography.headlineMedium
                )
                if (comparacion.ahorroVsPeor > 0.0 && comparacion.cadenas.size > 1) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "ahorrás ${Formato.precio(comparacion.ahorroVsPeor)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Text(
                "${mejor.itemsConPrecio}/${comparacion.itemsTotal} productos con precio",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            comparacion.cadenas.drop(1).forEach { cadena ->
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            Cadenas.nombre(cadena.cadenaId),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${cadena.itemsConPrecio}/${comparacion.itemsTotal}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            Formato.precio(cadena.total),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            Formato.porcentaje(cadena.difPorcentaje),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Text(
                "Los ítems sin precio no suman al total.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun MiniResumenCard(
    titulo: String,
    valor: String,
    modifier: Modifier = Modifier,
    detalle: String? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                titulo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(valor, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (detalle != null) {
                Text(
                    detalle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
