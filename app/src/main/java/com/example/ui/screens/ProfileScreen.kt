package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.flow.map

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

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        errorMessage = null
    }

    fun saveCity(city: String) {
        viewModelScope.launch {
            try {
                repository.saveUserCity(city)
            } catch (e: Exception) {
                errorMessage = "No se pudo guardar la ciudad: ${e.message}"
            }
        }
    }

    fun saveName(name: String) {
        viewModelScope.launch {
            try {
                repository.saveUserName(name)
            } catch (e: Exception) {
                errorMessage = "No se pudo guardar el nombre: ${e.message}"
            }
        }
    }

    fun deleteAccount(onLogout: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteUserAccount()
                onLogout()
            } catch (e: com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
                errorMessage = "Por seguridad, debes volver a iniciar sesión antes de eliminar tu cuenta. Cierra sesión, ingresa de nuevo e inténtalo otra vez."
            } catch (e: Exception) {
                errorMessage = "No se pudo eliminar la cuenta: ${e.message}"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val name by viewModel.userName.collectAsState()
    val city by viewModel.city.collectAsState()
    val tickets by viewModel.tickets.collectAsState()
    val observationsCount by viewModel.observationsCount.collectAsState()
    val totalAportes by viewModel.totalContributions.collectAsState()
    
    var cityInput by remember(city) { mutableStateOf(city) }
    var nameInput by remember(name) { mutableStateOf(name) }
    var isEditingName by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Rank Logic
    val rank = when {
        totalAportes <= 5 -> "Pionero"
        totalAportes <= 15 -> "Explorador"
        totalAportes <= 30 -> "Experto"
        else -> "Leyenda"
    }

    val nextRankInfo = when {
        totalAportes <= 5 -> 6 to "Explorador"
        totalAportes <= 15 -> 16 to "Experto"
        totalAportes <= 30 -> 31 to "Leyenda"
        else -> null
    }

    val progress = nextRankInfo?.let { (limit, _) ->
        val prevLimit = when(rank) {
            "Pionero" -> 0
            "Explorador" -> 6
            "Experto" -> 16
            else -> 0
        }
        (totalAportes - prevLimit).toFloat() / (limit - prevLimit).toFloat()
    } ?: 1f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Mi Perfil", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))
        
        // Identity Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val initial = if (name.isNotBlank()) name.take(1).uppercase() else viewModel.email.take(1).uppercase()
                    Text(initial, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (isEditingName) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Nombre") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    viewModel.saveName(nameInput)
                                    isEditingName = false
                                }) { Icon(Icons.Default.Edit, "Guardar") }
                            }
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (name.isBlank()) "Usuario" else name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { isEditingName = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Editar nombre", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Text(viewModel.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Rank Progress Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Rango: $rank", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$totalAportes aportes", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                )
                if (nextRankInfo != null) {
                    Text(
                        "Faltan ${nextRankInfo.first - totalAportes} aportes para ser ${nextRankInfo.second}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Ubicación", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = cityInput,
            onValueChange = { cityInput = it },
            label = { Text("Ciudad") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { viewModel.saveCity(cityInput) },
            enabled = cityInput != city
        ) {
            Text("Guardar Ciudad")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Resumen de Aportes", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tickets", style = MaterialTheme.typography.labelMedium)
                    Text("${tickets.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Scaneados", style = MaterialTheme.typography.labelMedium)
                    Text("$observationsCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Datos", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FileDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Exportar mis Datos (CSV)")
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Cerrar Sesión")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Eliminar mi cuenta y datos", color = MaterialTheme.colorScheme.error)
        }
    }
    
    if (viewModel.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Aviso") },
            text = { Text(viewModel.errorMessage!!) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("¿Borrar cuenta?") },
            text = { Text("Esta acción eliminará permanentemente tu cuenta, tus tickets y observaciones de precio. No se puede deshacer.") },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteAccount(onLogout)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
