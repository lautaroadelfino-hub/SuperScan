package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
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
    val estaOnline = rememberEstaOnline()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(0) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var productToAddToList by remember { mutableStateOf<com.example.data.ProductModel?>(null) }

    // Los errores de acción viven acá: un Snackbar que no tapa la app ni obliga
    // a tocar "OK". El AlertDialog quedó solo para lo destructivo (fix 1).
    LaunchedEffect(snackbarHostState) {
        viewModel.mensajes.collect { mensaje ->
            val resultado = snackbarHostState.showSnackbar(
                message = mensaje.texto,
                actionLabel = mensaje.accion,
                duration = if (mensaje.duracionLarga) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (resultado == SnackbarResult.ActionPerformed) {
                mensaje.onAccion?.invoke()
            }
        }
    }

    // TakePicture (y no TakePicturePreview): el contrato "Preview" devuelve la
    // MINIATURA del extra "data", de unos 240x320. Con eso es imposible leer los
    // renglones de un ticket. Este escribe la foto entera en un archivo nuestro.
    var fotoTicketUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { exito: Boolean ->
        val uri = fotoTicketUri
        if (exito && uri != null) {
            val bitmap = uriToBitmap(context, uri)
            if (bitmap != null) viewModel.processImage(bitmap)
            else viewModel.showError("No pudimos abrir la foto que sacaste. Probá de nuevo.")
            // El archivo ya no hace falta: la foto del ticket no se guarda.
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        fotoTicketUri = null
    }

    // El manifest declara CAMERA, así que lanzar ACTION_IMAGE_CAPTURE sin tenerlo
    // concedido tira SecurityException y voltea la app. Hay que pedirlo acá: el
    // único pedido en runtime que existía estaba en el Modo Súper, y a esta
    // pantalla se llega sin pasar por ahí.
    val permisoCamara = rememberPermissionState(android.Manifest.permission.CAMERA)
    var esperandoPermisoCamara by remember { mutableStateOf(false) }

    fun sacarFotoDelTicket() {
        val archivo = File(context.cacheDir, "ticket_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
        fotoTicketUri = uri
        cameraLauncher.launch(uri)
    }

    LaunchedEffect(esperandoPermisoCamara, permisoCamara.status.isGranted) {
        if (esperandoPermisoCamara && permisoCamara.status.isGranted) {
            esperandoPermisoCamara = false
            sacarFotoDelTicket()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bitmap = uriToBitmap(context, uri)
            if (bitmap != null) {
                viewModel.processImage(bitmap)
            } else {
                viewModel.showError("No pudimos abrir esa imagen. Probá con otra.")
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
                        viewModel.showError("Ese producto no está en el catálogo todavía. Podés darlo de alta desde el Modo Súper.")
                    is EanLookupResult.InvalidEan -> viewModel.showError("Ese código de barras no es válido: ${res.raw}")
                    is EanLookupResult.Offline -> viewModel.showError("Sin conexión: ese producto no está en los datos guardados.")
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
                snackbarHost = {
                    SnackbarHost(snackbarHostState) { data ->
                        // Fondo tinta, texto papel, acción en amarillo y en Bold
                        Snackbar(
                            shape = RoundedCornerShape(12.dp),
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.background,
                            action = data.visuals.actionLabel?.let { etiqueta ->
                                {
                                    TextButton(onClick = { data.performAction() }) {
                                        Text(
                                            etiqueta,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        ) {
                            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GondolaLockup()
                                    // De cuándo son los precios: al lado de la
                                    // ciudad, porque las dos cosas acotan lo
                                    // que el usuario está mirando.
                                    val fecha = viewModel.estadoPrecios
                                        ?.fecha_datos
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { Formato.fechaCorta(it) }
                                    if (fecha != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "precios al $fecha",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                        // Sin señal la app sigue andando con los precios que ya
                        // tiene guardados; lo que no se puede es callarlo.
                        AnimatedVisibility(
                            visible = !estaOnline,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CloudOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.background,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Sin conexión · usando precios guardados",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.background
                                    )
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    BarraGondola(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },
                        onAccionCentral = {
                            if (selectedTab == 1) {
                                // Escaneo de código de barras: es ML Kit corriendo en
                                // el propio teléfono, no depende de ningún servicio
                                // externo. Nunca se apaga.
                                productScanLauncher.launch(
                                    ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES) }
                                )
                            } else if (!viewModel.estadoEscaneoTicket.habilitado) {
                                // Mejor avisar acá que hacerlo sacar la foto, esperar
                                // y recién entonces fallar.
                                viewModel.mostrarMensaje(
                                    MensajeUi(
                                        texto = viewModel.estadoEscaneoTicket.mensajeODefault(),
                                        duracionLarga = true
                                    )
                                )
                            } else {
                                showBottomSheet = true
                            }
                        }
                    )
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
                                    estaOnline = estaOnline,
                                    cargando = !viewModel.listasCargadas,
                                    errorCarga = viewModel.errorCargaListas,
                                    onReintentar = { viewModel.reintentarListas() },
                                    onVerGastos = { selectedTab = 2 },
                                    onVerPerfil = { selectedTab = 3 },
                                    onAbrirProducto = { producto ->
                                        catalogViewModel.abrirDetalle(producto)
                                        selectedTab = 1
                                    },
                                    onBuscarEnCatalogo = { termino ->
                                        catalogViewModel.buscarDesdeLaPortada(termino)
                                        selectedTab = 1
                                    },
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
                                    onAddToList = { product -> productToAddToList = product },
                                    onMensaje = { texto -> viewModel.showError(texto) }
                                )
                            }
                            2 -> {
                                MisComprasScreen(
                                    receipts = receipts,
                                    budget = budget,
                                    cargando = !viewModel.ticketsCargados,
                                    errorCarga = viewModel.errorCargaTickets,
                                    onReintentar = { viewModel.reintentarTickets() },
                                    onUpdateBudget = { viewModel.updateBudget(it) },
                                    onDeleteReceipt = { viewModel.deleteReceipt(it) }
                                )
                            }
                            3 -> {
                                ProfileScreen(
                                    onLogout = onLogout,
                                    onMensaje = { texto -> viewModel.showError(texto) }
                                )
                            }
                        }
                    }

                    // Lo que está pasando con el ticket, sin tapar la app (fix 2)
                    PildoraEscaneo(
                        estado = viewModel.estadoEscaneo,
                        procesoEnCurso = viewModel.procesoEnCurso,
                        onRevisar = { viewModel.abrirTicketLeido() },
                        onDescartar = { viewModel.descartarEscaneo() },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    )

                    if (productToAddToList != null) {
                        val product = productToAddToList!!
                        ElegirListaSheet(
                            producto = product,
                            listas = shoppingLists,
                            onDismiss = { productToAddToList = null },
                            onElegir = { lista, cantidad ->
                                viewModel.addProductToShoppingList(lista.id, product, cantidad, lista.name)
                                productToAddToList = null
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
                                        if (permisoCamara.status.isGranted) {
                                            sacarFotoDelTicket()
                                        } else {
                                            // Si deniega, las otras dos opciones del
                                            // sheet siguen andando sin permiso.
                                            esperandoPermisoCamara = true
                                            permisoCamara.launchPermissionRequest()
                                        }
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
    }
}

// Barra de 4 zonas con el botón de escaneo encastrado en el centro, mitad
// adentro y mitad afuera. El micro-label dice qué hace acá y ahora: en Perfil
// no hay nada que escanear, así que el botón no está.
@Composable
private fun BarraGondola(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onAccionCentral: () -> Unit
) {
    val esCatalogo = selectedTab == 1
    Box {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(top = 28.dp)
        ) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { onSelectTab(0) },
                icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Listas") },
                label = { Text("Listas") }
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { onSelectTab(1) },
                icon = { Icon(Icons.Default.Search, contentDescription = "Catálogo") },
                label = { Text("Catálogo") }
            )
            // Hueco para el botón central de escaneo
            Spacer(modifier = Modifier.weight(0.9f))
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = { onSelectTab(2) },
                icon = { Icon(Icons.Default.History, contentDescription = "Mis compras") },
                label = { Text("Compras") }
            )
            NavigationBarItem(
                selected = selectedTab == 3,
                onClick = { onSelectTab(3) },
                icon = { Icon(Icons.Default.Person, contentDescription = "Perfil") },
                label = { Text("Perfil") }
            )
        }
        if (selectedTab != 3) {
            // El micro-label va ADENTRO del círculo, debajo del ícono: el botón
            // dice lo que hace sin depender de una palabra suelta flotando sobre
            // la barra.
            FloatingActionButton(
                onClick = onAccionCentral,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                modifier = Modifier.align(Alignment.TopCenter).size(64.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (esCatalogo) Icons.Default.QrCodeScanner else Icons.Default.ReceiptLong,
                        contentDescription = if (esCatalogo) "Escanear producto" else "Cargar ticket",
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        if (esCatalogo) "ESCANEAR" else "TICKET",
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
        }
    }
}

// La píldora que cuenta qué pasa con el ticket mientras la app sigue viva.
// Leyendo → spinner; leído → botón "Revisar" (nadie pierde un ticket por
// haberse ido a otra solapa).
@Composable
private fun PildoraEscaneo(
    estado: EstadoEscaneo,
    procesoEnCurso: String?,
    onRevisar: () -> Unit,
    onDescartar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = estado !is EstadoEscaneo.Inactivo || procesoEnCurso != null
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    estado is EstadoEscaneo.Listo -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val n = estado.receipt.items.size
                        Text(
                            "Ticket leído · $n ${if (n == 1) "ítem" else "ítems"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onRevisar,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text("Revisar", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        }
                        IconButton(onClick = onDescartar, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Descartar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    else -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            procesoEnCurso ?: "Leyendo el ticket… podés seguir usando la app",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

// Agregar a una lista no es destructivo: hoja, no diálogo.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ElegirListaSheet(
    producto: com.example.data.ProductModel,
    listas: List<com.example.data.SharedListModel>,
    onDismiss: () -> Unit,
    onElegir: (com.example.data.SharedListModel, Double) -> Unit
) {
    var cantidad by remember { mutableStateOf("1") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text(
                "Agregar a una lista",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                producto.descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = cantidad,
                onValueChange = { if (it.all { c -> c.isDigit() || c == '.' }) cantidad = it },
                label = { Text("Cantidad") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (listas.isEmpty()) {
                Text(
                    "Todavía no tenés ninguna lista. Creá una desde la solapa Listas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            listas.forEach { lista ->
                ListItem(
                    headlineContent = { Text(lista.name) },
                    supportingContent = {
                        Text(
                            if (lista.members.size > 1) "Colaborativa · ${lista.members.size}" else "Personal"
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable {
                        onElegir(lista, cantidad.toDoubleOrNull() ?: 1.0)
                    }
                )
            }
        }
    }
}

@Composable
fun ReceiptCard(receipt: ReceiptEntity, onDelete: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var confirmarBorrado by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ReceiptLong,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(receipt.storeName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${receipt.date} · ${receipt.items.size} ítems",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    Formato.precio(receipt.totalAmount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    receipt.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.productName, style = MaterialTheme.typography.bodyMedium)
                                val unitPrice = if (item.quantity > 0.0) item.totalPrice / item.quantity else item.totalPrice
                                Text(
                                    "${Formato.cantidad(item.quantity)} × ${Formato.precio(unitPrice)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(Formato.precio(item.totalPrice), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmarBorrado = true
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Eliminar ticket", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // Destructivo: acá sí va un diálogo, y dice exactamente qué se pierde.
    if (confirmarBorrado) {
        val n = receipt.items.size
        AlertDialog(
            onDismissRequest = { confirmarBorrado = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("¿Eliminar este ticket?") },
            text = {
                Text(
                    "Se borra de tu historial y dejan de contar sus $n ${if (n == 1) "precio aportado" else "precios aportados"} " +
                        "a la comunidad."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmarBorrado = false
                        onDelete(receipt.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmarBorrado = false }) { Text("Cancelar") }
            }
        )
    }
}

// Tarjeta de error de CARGA: no es un Snackbar (el dato no está y hay que
// reintentar), no es un diálogo (no bloquea). Dice la causa y ofrece la salida.
@Composable
fun ErrorDeCarga(
    titulo: String,
    causa: String,
    onReintentar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                causa,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onReintentar, shape = RoundedCornerShape(999.dp)) {
                Text("Reintentar")
            }
        }
    }
}

// Un ticket a 2000 px de lado mayor se lee sin problemas. Más que eso solo suma
// megabytes: una foto de 12 MP ocupa ~48 MB en memoria como ARGB_8888 y hace
// reventar los celulares con poca RAM antes siquiera de llegar a mandarla.
private const val LADO_MAXIMO_TICKET = 2000

/** Potencia de 2 más chica que deja el lado mayor por debajo del máximo. */
private fun muestreoPara(ladoMayor: Int): Int {
    var muestreo = 1
    while (ladoMayor / muestreo > LADO_MAXIMO_TICKET) muestreo *= 2
    return muestreo
}

private fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = true
                // Se baja la resolución DURANTE la decodificación, no después:
                // escalar a posteriori exige haber cargado la imagen entera, que
                // es justamente lo que queremos evitar.
                val lado = maxOf(info.size.width, info.size.height)
                if (lado > LADO_MAXIMO_TICKET) decoder.setTargetSampleSize(muestreoPara(lado))
            }
        } else {
            val opcionesMedida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opcionesMedida)
            }
            val opciones = BitmapFactory.Options().apply {
                inSampleSize = muestreoPara(maxOf(opcionesMedida.outWidth, opcionesMedida.outHeight))
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opciones)
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("GondolaScanner", "No se pudo decodificar la imagen del ticket", e)
        null
    }
}
