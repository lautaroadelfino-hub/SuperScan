package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FirebaseRepository
import com.example.data.ReceiptEntity
import com.example.data.TicketModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

// Los rangos de la comunidad, en orden. El aporte de precios es lo que sostiene
// el catálogo: el perfil tiene que mostrar cuánto aportó esta persona.
private val RANGOS = listOf("Pionero", "Explorador", "Experto", "Leyenda")
private val UMBRALES = listOf(0, 6, 16, 31)

class ProfileViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val repository = FirebaseRepository()

    val email = auth.currentUser?.email ?: ""

    val userName: StateFlow<String> = repository.getUserName()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val city: StateFlow<String> = repository.getUserCity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val tickets: StateFlow<List<TicketModel>> = repository.getTickets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val observationsCount: StateFlow<Int> = repository.getObservationsCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalContributions: StateFlow<Int> = combine(tickets, observationsCount) { t, o ->
        t.size + o
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Errores de acción: los reporta la pantalla contenedora con un Snackbar */
    var onError: ((String) -> Unit)? = null

    fun saveCity(city: String) {
        viewModelScope.launch {
            try {
                repository.saveUserCity(city)
            } catch (e: Exception) {
                onError?.invoke("No se pudo guardar la ciudad. Probá de nuevo.")
            }
        }
    }

    fun saveName(name: String) {
        viewModelScope.launch {
            try {
                repository.saveUserName(name)
            } catch (e: Exception) {
                onError?.invoke("No se pudo guardar el nombre. Probá de nuevo.")
            }
        }
    }

    fun deleteAccount(onLogout: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteUserAccount()
                onLogout()
            } catch (e: com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
                onError?.invoke(
                    "Por seguridad, volvé a entrar antes de eliminar la cuenta: cerrá sesión, ingresá de nuevo y probá otra vez."
                )
            } catch (e: Exception) {
                onError?.invoke("No se pudo eliminar la cuenta. Probá de nuevo.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onLogout: () -> Unit,
    onMensaje: (String) -> Unit
) {
    val context = LocalContext.current
    val name by viewModel.userName.collectAsState()
    val city by viewModel.city.collectAsState()
    val tickets by viewModel.tickets.collectAsState()
    val observationsCount by viewModel.observationsCount.collectAsState()
    val totalAportes by viewModel.totalContributions.collectAsState()

    var nameInput by remember(name) { mutableStateOf(name) }
    var isEditingName by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editandoCiudad by remember { mutableStateOf(false) }

    // El ViewModel no conoce la UI: le pasamos por dónde avisar
    SideEffect { viewModel.onError = onMensaje }

    val indiceRango = UMBRALES.indexOfLast { totalAportes >= it }.coerceAtLeast(0)
    val rango = RANGOS[indiceRango]
    val siguienteUmbral = UMBRALES.getOrNull(indiceRango + 1)
    val progreso = if (siguienteUmbral == null) 1f else {
        val piso = UMBRALES[indiceRango]
        ((totalAportes - piso).toFloat() / (siguienteUmbral - piso).toFloat()).coerceIn(0f, 1f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // --- Identidad ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val inicial = (name.ifBlank { viewModel.email }).take(1).uppercase()
                    Text(
                        inicial,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (isEditingName) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Tu nombre") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    viewModel.saveName(nameInput)
                                    isEditingName = false
                                }) { Icon(Icons.Default.Check, contentDescription = "Guardar nombre") }
                            }
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name.ifBlank { "Sin nombre" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { isEditingName = true }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Editar nombre",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            viewModel.email + if (city.isNotBlank()) " · $city" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Rango en la comunidad ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Rango: $rango",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            "$totalAportes ${if (totalAportes == 1) "aporte" else "aportes"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progreso },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {}
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (siguienteUmbral != null) {
                    val faltan = siguienteUmbral - totalAportes
                    Text(
                        "Faltan $faltan ${if (faltan == 1) "aporte" else "aportes"} para ser ${RANGOS[indiceRango + 1]}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        "Llegaste al último rango. Gracias por sostener el catálogo.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    RANGOS.joinToString(" → "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ContadorAporte("TICKETS", tickets.size, Modifier.weight(1f))
                    ContadorAporte("PRECIOS DE GÓNDOLA", observationsCount, Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Acciones ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                ListItem(
                    headlineContent = { Text("Ciudad") },
                    supportingContent = { Text(city.ifBlank { "Elegí tu ciudad" }) },
                    leadingContent = {
                        Icon(
                            Icons.Default.LocationCity,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable { editandoCiudad = true },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ListItem(
                    headlineContent = { Text("Exportar mis datos (CSV)") },
                    supportingContent = { Text("Tus tickets, para abrirlos donde quieras") },
                    leadingContent = {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable {
                        val entities = tickets.map { t ->
                            ReceiptEntity(
                                id = t.id,
                                storeName = t.storeName,
                                date = t.date,
                                totalAmount = t.totalAmount,
                                items = t.items.map { i ->
                                    com.example.data.ReceiptItem(
                                        productName = i.productName,
                                        category = i.category,
                                        quantity = i.quantity,
                                        unitPrice = i.unitPrice,
                                        totalPrice = i.totalPrice,
                                        barcode = i.barcode
                                    )
                                }
                            )
                        }
                        exportToCsvAndShare(context, entities)
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ListItem(
                    headlineContent = { Text("Cerrar sesión") },
                    leadingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable { onLogout() },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Eliminar mi cuenta y datos", color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (editandoCiudad) {
        var ciudadInput by remember { mutableStateOf(city) }
        ModalBottomSheet(onDismissRequest = { editandoCiudad = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text("Tu ciudad", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Define qué precios ves. Hoy Góndola tiene el catálogo de Tandil.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )
                OutlinedTextField(
                    value = ciudadInput,
                    onValueChange = { ciudadInput = it },
                    label = { Text("Ciudad") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        viewModel.saveCity(ciudadInput.trim())
                        editandoCiudad = false
                    },
                    enabled = ciudadInput.isNotBlank() && ciudadInput != city,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(999.dp)
                ) { Text("Guardar", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("¿Eliminar tu cuenta?") },
            text = {
                Text(
                    "Se borran para siempre tu cuenta, tus ${tickets.size} tickets y tus $observationsCount precios informados. " +
                        "Esto no se puede deshacer."
                )
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteAccount(onLogout)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun ContadorAporte(titulo: String, valor: Int, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                titulo,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$valor",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
