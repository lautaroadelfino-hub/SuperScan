package com.example.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SharedListItemModel
import com.example.data.SharedListModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun ShoppingListsScreen(
    viewModel: MainViewModel,
    lists: List<SharedListModel>,
    currentItems: List<SharedListItemModel>,
    onListSelected: (String) -> Unit,
    onItemToggled: (String, Boolean) -> Unit,
    onAddMember: (String) -> Unit,
    onCreateList: (String) -> Unit
) {
    var selectedListId by remember { mutableStateOf<String?>(null) }
    var superMode by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var showCreateListDialog by remember { mutableStateOf(false) }
    var newMemberEmail by remember { mutableStateOf("") }
    var newListName by remember { mutableStateOf("") }
    
    val context = LocalContext.current

    if (showCreateListDialog) {
        AlertDialog(
            onDismissRequest = { showCreateListDialog = false },
            title = { Text("Nueva Lista Compartida") },
            text = {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("Nombre de la lista") }
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

    Scaffold(
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
                Text("Listas Colaborativas", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                if (lists.isEmpty()) {
                    Text(
                        "No tienes listas de compras todavía.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(lists) { list ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedListId = list.id
                                    onListSelected(list.id)
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(list.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${list.members.size} miembros", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val list = lists.find { it.id == selectedListId }
                val listName = list?.name ?: "Lista"
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedListId = null }) {
                            Text("← Volver")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(listName, style = MaterialTheme.typography.titleLarge)
                    }
                    
                    Row {
                        IconButton(onClick = { showAddMemberDialog = true }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Invitar Miembro", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { superMode = true }) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = "Modo Súper", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                if (list != null) {
                    Text("Miembros: ${list.members.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                if (currentItems.isEmpty()) {
                    Text("La lista está vacía.", modifier = Modifier.padding(16.dp))
                } else {
                    val groupedItems = currentItems.groupBy { it.category }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groupedItems.forEach { (category, items) ->
                            item {
                                Text(
                                    text = if (category.isNotEmpty()) category else "Otros",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                                )
                            }
                            items(items.sortedBy { it.scanned }) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onItemToggled(item.id, !item.scanned) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (item.scanned) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (item.scanned) 0.dp else 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = item.scanned,
                                            onCheckedChange = { onItemToggled(item.id, it) }
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
                                            if (item.targetQuantity > 1.0) {
                                                Text(
                                                    text = "Cantidad: ${item.targetQuantity}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (item.expectedPrice > 0.0) {
                                            Text(
                                                text = "$${String.format(java.util.Locale.US, "%.2f", item.expectedPrice)}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (item.scanned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
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
