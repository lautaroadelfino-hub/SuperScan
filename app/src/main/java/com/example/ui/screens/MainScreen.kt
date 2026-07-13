package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReceiptEntity
import java.util.Locale

import com.example.ui.screens.ReceiptConfirmationScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onLogout: () -> Unit) {
    if (viewModel.pendingReceipt != null) {
        ReceiptConfirmationScreen(
            initialReceipt = viewModel.pendingReceipt!!,
            onConfirm = { viewModel.confirmReceipt(it) },
            onCancel = { viewModel.cancelReceipt() },
            onSearchProducts = { viewModel.searchProducts(it) }
        )
        return
    }

    val receipts by viewModel.receipts.collectAsState()
    val allProducts by viewModel.allProducts.collectAsState()
    val budget by viewModel.budget.collectAsState()
    val shoppingLists by viewModel.shoppingLists.collectAsState()
    val currentListItems by viewModel.currentListItems.collectAsState()
    
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var productToAddToList by remember { mutableStateOf<com.example.data.ProductEntity?>(null) }

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
                // Should show error
            }
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            viewModel.processPdf(uri, context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SuperScan", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showBottomSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = "Escanear") },
                    text = { Text("Escanear") }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Inicio") },
                    label = { Text("Inicio") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PieChart, contentDescription = "Estadísticas") },
                    label = { Text("Estadísticas") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Catálogo") },
                    label = { Text("Catálogo") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Listas") },
                    label = { Text("Listas") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Perfil") },
                    label = { Text("Perfil") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Historial de Compras", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(receipts) { receipt ->
                                ReceiptCard(receipt = receipt, onDelete = { viewModel.deleteReceipt(it) })
                            }
                        }
                    }
                }
                1 -> {
                    StatsScreen(receipts = receipts, prefs = budget, onUpdateBudget = { viewModel.updateBudget(it) })
                }
                2 -> {
                    CatalogScreen(
                        hasCurrentList = shoppingLists.isNotEmpty(),
                        onAddToCurrentList = { product -> productToAddToList = product },
                        products = allProducts,
                        onAddProduct = { barcode, name, cat, price -> viewModel.addManualProduct(barcode, name, cat, price) }
                    )
                }
                3 -> {
                    ShoppingListsScreen(
                        viewModel = viewModel,
                        lists = shoppingLists,
                        currentItems = currentListItems,
                        onListSelected = { viewModel.selectShoppingList(it) },
                        onItemToggled = { itemId, isChecked -> viewModel.toggleShoppingItem(itemId, isChecked) },
                        onItemDeleted = { itemId -> viewModel.removeShoppingItem(itemId) },
                        onAddMember = { email -> viewModel.addMemberToList(email) },
                        onCreateList = { name -> viewModel.createShoppingList(name) }
                    )
                }
                4 -> {
                    ProfileScreen(onLogout = onLogout)
                }
            }

            if (viewModel.isProcessing) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(viewModel.loadingMessage)
                    }
                }
            }

            if (productToAddToList != null) {
                val product = productToAddToList!!
                AlertDialog(
                    onDismissRequest = { productToAddToList = null },
                    title = { Text("Añadir a lista") },
                    text = {
                        Column {
                            Text("¿A qué lista quieres añadir \"${product.productName}\"?")
                            Spacer(modifier = Modifier.height(8.dp))
                            shoppingLists.forEach { list ->
                                ListItem(
                                    headlineContent = { Text(list.name) },
                                    supportingContent = { Text("${list.members.size} miembros") },
                                    modifier = Modifier.clickable {
                                        viewModel.addProductToShoppingList(list.id, product, 1.0)
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

            if (viewModel.errorMessage != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.clearError() },
                    title = { Text("Aviso") },
                    text = { Text(viewModel.errorMessage!!) },
                    confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text("OK") } }
                )
            }

            if (showBottomSheet) {
                ModalBottomSheet(onDismissRequest = { showBottomSheet = false }) {
                    Column(modifier = Modifier.padding(16.dp).padding(bottom = 32.dp)) {
                        Text("Opciones de escaneo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                        ListItem(
                            headlineContent = { Text("Cámara") },
                            leadingContent = { Icon(Icons.Default.AddCircle, contentDescription = null) },
                            modifier = Modifier.clickable {
                                showBottomSheet = false
                                cameraLauncher.launch(null)
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Galería") },
                            leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                            modifier = Modifier.clickable {
                                showBottomSheet = false
                                imagePickerLauncher.launch("image/*")
                            }
                        )
                        ListItem(
                            headlineContent = { Text("PDF") },
                            leadingContent = { Icon(Icons.Default.Create, contentDescription = null) },
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

@Composable
fun ReceiptCard(receipt: ReceiptEntity, onDelete: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(receipt.storeName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(receipt.date, style = MaterialTheme.typography.bodySmall)
                }
                Text("$${String.format(Locale.US, "%.2f", receipt.totalAmount)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar Detalles" else "Ver Detalles") }
                IconButton(onClick = { onDelete(receipt.id) }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error) }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                receipt.items.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.productName, style = MaterialTheme.typography.bodyMedium)
                            Text("${item.quantity}x a $${String.format(Locale.US, "%.2f", item.totalPrice / item.quantity)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("$${String.format(Locale.US, "%.2f", item.totalPrice)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
