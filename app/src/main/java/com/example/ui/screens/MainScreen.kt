package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.EanLookupResult
import com.example.data.Formato
import com.example.data.ReceiptEntity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

// Estructura Góndola: 4 zonas (Listas · Catálogo · Mis compras · Perfil) con el
// botón de escaneo en el centro de la barra. El botón es CONTEXTUAL: en Listas
// y Mis compras carga un ticket; en Catálogo escanea un producto. Nunca promete
// dos cosas a la vez.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, catalogViewModel: CatalogViewModel, onLogout: () -> Unit) {
    val receipts by viewModel.receipts.collectAsState()
    val budget by viewModel.budget.collectAsState()
    val shoppingLists by viewModel.shoppingLists.collectAsState()
    val currentListItems by viewModel.currentListItems.collectAsState()
    val activeListId by viewModel.currentListId.collectAsState()
    val misAportes by viewModel.misAportes.collectAsState()
    val gastoDelMes = remember(receipts) {
        val mes = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
        receipts.filter { it.date.startsWith(mes) }.sumOf { it.totalAmount }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var productToAddToList by remember { mutableStateOf<com.example.data.ProductModel?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) {
            viewModel.processImage(bitmap)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bitmap = uriToBitmap(context, uri)
            if (bitmap != null) {
                viewModel.processImage(bitmap)
            } else {
                viewModel.showError("No se pudo leer la imagen.")
            }
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            viewModel.processPdf(uri, context)
        }
    }

    // Escaneo de producto (Catálogo): busca el EAN y abre el detalle
    val productScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            scope.launch {
                when (val res = viewModel.lookupBarcode(result.contents)) {
                    is EanLookupResult.Found -> catalogViewModel.abrirDetalle(res.producto)
                    is EanLookupResult.NotFound ->
                        viewModel.showError("El producto no está en el catálogo todavía. Podés cargarlo escaneándolo desde el Modo Súper.")
                    is EanLookupResult.InvalidEan -> viewModel.showError("Código de barras inválido: ${res.raw}")
                    is EanLookupResult.Offline -> viewModel.showError("Sin conexión: el producto no está en la caché local.")
                    is EanLookupResult.Failure -> viewModel.showError(res.mensaje)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (viewModel.pendingReceipt != null) {
            ReceiptConfirmationScreen(
                initialReceipt = viewModel.pendingReceipt!!,
                onConfirm = { viewModel.confirmReceipt(it) },
                onCancel = { viewModel.cancelReceipt() },
                onSearchProducts = { viewModel.searchProducts(it) }
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "góndola",
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = (-0.5).sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(999.dp)
                                ) {
                                    Text(
                                        "Tandil",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                },
                bottomBar = {
                    // Barra con el botón de escaneo ENCASTRADO en el centro,
                    // mitad adentro mitad afuera, como en el diseño de marca
                    Box {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.padding(top = 26.dp)
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Listas") },
                                label = { Text("Listas") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.Search, contentDescription = "Catálogo") },
                                label = { Text("Catálogo") }
                            )
                            // Hueco para el botón central de escaneo
                            Spacer(modifier = Modifier.weight(0.8f))
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.History, contentDescription = "Mis compras") },
                                label = { Text("Compras") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "Perfil") },
                                label = { Text("Perfil") }
                            )
                        }
                        // Botón contextual: dice exactamente lo que hace acá
                        if (selectedTab != 3) {
                            FloatingActionButton(
                                onClick = {
                                    if (selectedTab == 1) {
                                        productScanLauncher.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES) })
                                    } else {
                                        showBottomSheet = true
                                    }
                                },
                                shape = CircleShape,
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .size(60.dp)
                            ) {
                                Icon(
                                    if (selectedTab == 1) Icons.Default.QrCodeScanner else Icons.Default.ReceiptLong,
                                    contentDescription = if (selectedTab == 1) "Escanear producto" else "Cargar ticket",
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Crossfade(targetState = selectedTab, label = "tab_transition") { tab ->
                        when (tab) {
                            0 -> {
                                ShoppingListsScreen(
                                    viewModel = viewModel,
                                    lists = shoppingLists,
                                    currentItems = currentListItems,
                                    activeListId = activeListId,
                                    gastoDelMes = gastoDelMes,
                                    aportes = misAportes,
                                    onBuscar = { selectedTab = 1 },
                                    onListSelected = { viewModel.selectShoppingList(it) },
                                    onItemToggled = { itemId, isChecked -> viewModel.toggleShoppingItem(itemId, isChecked) },
                                    onItemDeleted = { itemId -> viewModel.removeShoppingItem(itemId) },
                                    onAddMember = { email -> viewModel.addMemberToList(email) },
                                    onCreateList = { name -> viewModel.createShoppingList(name) }
                                )
                            }
                            1 -> {
                                CatalogScreen(
                                    catalogViewModel = catalogViewModel,
                                    hasCurrentList = shoppingLists.isNotEmpty(),
                                    onAddToList = { product -> productToAddToList = product }
                                )
                            }
                            2 -> {
                                MisComprasScreen(
                                    receipts = receipts,
                                    budget = budget,
                                    onUpdateBudget = { viewModel.updateBudget(it) },
                                    onDeleteReceipt = { viewModel.deleteReceipt(it) }
                                )
                            }
                            3 -> {
                                ProfileScreen(onLogout = onLogout)
                            }
                        }
                    }

                    if (productToAddToList != null) {
                        val product = productToAddToList!!
                        var quantity by remember { mutableStateOf("1") }
                        AlertDialog(
                            onDismissRequest = { productToAddToList = null },
                            title = { Text("Añadir a lista") },
                            text = {
                                Column {
                                    Text("¿A qué lista quieres añadir \"${product.descripcion}\"?")
                                    Spacer(modifier = Modifier.height(16.dp))
                                    OutlinedTextField(
                                        value = quantity,
                                        onValueChange = { if (it.all { c -> c.isDigit() || c == '.' }) quantity = it },
                                        label = { Text("Cantidad") },
                                        modifier = Modifier.fillMaxWidth(),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Selecciona una lista:", style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    shoppingLists.forEach { list ->
                                        ListItem(
                                            headlineContent = { Text(list.name) },
                                            supportingContent = { Text("${if(list.members.size > 1) "Colaborativa" else "Personal"} (${list.members.size} miembros)") },
                                            modifier = Modifier.clickable {
                                                val qty = quantity.toDoubleOrNull() ?: 1.0
                                                viewModel.addProductToShoppingList(list.id, product, qty)
                                                productToAddToList = null
                                            }
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { productToAddToList = null }) { Text("Cancelar") }
                            }
                        )
                    }

                    if (showBottomSheet) {
                        ModalBottomSheet(onDismissRequest = { showBottomSheet = false }) {
                            Column(modifier = Modifier.padding(16.dp).padding(bottom = 32.dp)) {
                                Text("Cargar ticket", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                                ListItem(
                                    headlineContent = { Text("Sacar foto") },
                                    leadingContent = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                                    modifier = Modifier.clickable {
                                        showBottomSheet = false
                                        cameraLauncher.launch(null)
                                    }
                                )
                                ListItem(
                                    headlineContent = { Text("Elegir de la galería") },
                                    leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                                    modifier = Modifier.clickable {
                                        showBottomSheet = false
                                        imagePickerLauncher.launch("image/*")
                                    }
                                )
                                ListItem(
                                    headlineContent = { Text("Subir PDF") },
                                    leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                                    modifier = Modifier.clickable {
                                        showBottomSheet = false
                                        pdfPickerLauncher.launch("application/pdf")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (viewModel.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(viewModel.loadingMessage)
                }
            }
        }

        if (viewModel.errorMessage != null) {
            AlertDialog(
                onDismissRequest = { viewModel.clearError() },
                title = { Text("Aviso") },
                text = { Text(viewModel.errorMessage!!) },
                confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text("OK") } }
            )
        }
    }
}

@Composable
fun ReceiptCard(receipt: ReceiptEntity, onDelete: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(receipt.storeName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(receipt.date, style = MaterialTheme.typography.bodySmall)
                }
                Text(Formato.precio(receipt.totalAmount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar Detalles" else "Ver Detalles") }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete(receipt.id)
                }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error) }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    receipt.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.productName, style = MaterialTheme.typography.bodyMedium)
                                val unitPrice = if (item.quantity > 0.0) item.totalPrice / item.quantity else item.totalPrice
                                Text("${item.quantity}x a ${Formato.precio(unitPrice)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(Formato.precio(item.totalPrice), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = true }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
