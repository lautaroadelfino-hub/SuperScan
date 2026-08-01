package com.example.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.Cadenas
import com.example.data.DisplayPrice
import com.example.data.EanLookupResult
import com.example.data.Formato
import com.example.data.ProductModel
import com.example.data.SharedListItemModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

// Modo Súper: la app en la mano, adentro del súper. Escanea, tilda lo que ya
// está en el changuito y toma precios de góndola. Todo precio que se informa
// acá sabe EN QUÉ SÚPER se tomó: sin eso el dato no sirve para comparar.
@OptIn(ExperimentalGetImage::class, ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SuperModeScreen(
    viewModel: MainViewModel,
    listId: String,
    nombreLista: String,
    itemsDeLaLista: List<SharedListItemModel>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    var scannedProduct by remember { mutableStateOf<ProductModel?>(null) }
    var scannedPrice by remember { mutableStateOf<DisplayPrice?>(null) }
    var scannedBarcode by remember { mutableStateOf<String?>(null) }
    var tildadoEnLista by remember { mutableStateOf(false) }
    var isProcessingBarcode by remember { mutableStateOf(false) }

    var mostrarAltaRapida by remember { mutableStateOf(false) }
    var informandoPrecio by remember { mutableStateOf(false) }
    var eligiendoCadena by remember { mutableStateOf(false) }

    var cadenas by remember { mutableStateOf<List<String>>(emptyList()) }
    val cadenaActiva = viewModel.cadenaActiva

    // Al entrar hay que saber dónde estás: sin cadena no se puede informar nada.
    LaunchedEffect(Unit) {
        cadenas = viewModel.cadenasConocidas()
        if (cadenaActiva == null) eligiendoCadena = true
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    fun vibrar() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    fun seguirEscaneando() {
        scannedProduct = null
        scannedPrice = null
        scannedBarcode = null
        tildadoEnLista = false
        isProcessingBarcode = false
    }

    // El Modo Súper tapa toda la pantalla: sin esto, "atrás" cierra la app en
    // vez de devolverte a la lista (y la cámara sigue prendida un instante).
    BackHandler { onClose() }

    val faltantes = itemsDeLaLista.filterNot { it.scanned }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermissionState.status.isGranted) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val options = BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(
                                Barcode.FORMAT_EAN_13,
                                Barcode.FORMAT_EAN_8,
                                Barcode.FORMAT_UPC_A,
                                Barcode.FORMAT_UPC_E
                            )
                            .build()
                        val scanner = BarcodeScanning.getClient(options)

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                    if (isProcessingBarcode) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                        scanner.process(image)
                                            .addOnSuccessListener { barcodes ->
                                                val barcode = barcodes.firstOrNull()?.rawValue
                                                if (barcode != null && barcode.isNotBlank() && !isProcessingBarcode) {
                                                    isProcessingBarcode = true
                                                    vibrar()
                                                    scannedBarcode = barcode
                                                    scope.launch {
                                                        when (val res = viewModel.lookupBarcode(barcode)) {
                                                            is EanLookupResult.Found -> {
                                                                val prod = res.producto
                                                                scannedProduct = prod
                                                                scannedBarcode = prod.ean
                                                                scannedPrice = viewModel.getDisplayPrice(prod.ean)
                                                                // Tildar en la lista (los ítems guardan el ean normalizado)
                                                                val items = viewModel.currentListItems.value
                                                                val existing = items.find { it.barcode == prod.ean }
                                                                if (existing != null) {
                                                                    if (!existing.scanned) {
                                                                        viewModel.toggleShoppingItem(existing.id, true)
                                                                    }
                                                                    tildadoEnLista = true
                                                                } else {
                                                                    viewModel.addManualProductToList(listId, prod)
                                                                    tildadoEnLista = false
                                                                }
                                                            }
                                                            is EanLookupResult.NotFound -> {
                                                                // ean normalizado: el alta escribe en productos_usuarios
                                                                scannedBarcode = res.ean
                                                                mostrarAltaRapida = true
                                                            }
                                                            is EanLookupResult.InvalidEan -> {
                                                                viewModel.showError("Ese código de barras no es válido: ${res.raw}")
                                                                isProcessingBarcode = false
                                                            }
                                                            is EanLookupResult.Offline -> {
                                                                viewModel.showError("Sin conexión: ese producto no está en los datos guardados.")
                                                                isProcessingBarcode = false
                                                            }
                                                            is EanLookupResult.Failure -> {
                                                                viewModel.showError(res.mensaje)
                                                                isProcessingBarcode = false
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Para escanear en la góndola necesitamos la cámara.",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Dar permiso")
                }
            }
        }

        // Barra superior sobre la cámara: salida a la izquierda (blanca sobre
        // el scrim, no verde: sobre la imagen el verde desaparece) y el súper
        // en el que estás a la derecha.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                IconButton(onClick = onClose, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Close, contentDescription = "Salir del Modo Súper", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            ChipCadenaActiva(
                cadenaId = cadenaActiva,
                onClick = { eligiendoCadena = true }
            )
        }

        // Lo que falta del changuito, siempre a la vista
        if (faltantes.isNotEmpty() && scannedProduct == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    "TE FALTAN EN ${nombreLista.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = Color.White,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    faltantes.take(12).forEach { item ->
                        Surface(
                            color = Color.White.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                item.productName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = 160.dp)
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // --- Producto escaneado ---
    val producto = scannedProduct
    if (producto != null && !informandoPrecio) {
        ModalBottomSheet(onDismissRequest = { seguirEscaneando() }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                if (tildadoEnLista) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Tildado en $nombreLista",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniaturaProducto(imagen = producto.imagen, size = 56.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            producto.descripcion,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (producto.marca.isNotBlank()) {
                            Text(
                                producto.marca,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                when (val p = scannedPrice) {
                    is DisplayPrice.UserObservation ->
                        Text("${Formato.precio(p.precio)} · lo informaste en ${p.comercio} (${p.fecha})", style = MaterialTheme.typography.bodyMedium)
                    is DisplayPrice.PublicPrice ->
                        Text("${Formato.precio(p.precio)} · informado por usuarios (${p.n})", style = MaterialTheme.typography.bodyMedium)
                    is DisplayPrice.MinPrice ->
                        Text("desde ${Formato.precio(p.precio)} en ${Cadenas.nombre(p.cadena)}", style = MaterialTheme.typography.bodyMedium)
                    is DisplayPrice.None ->
                        Text(
                            "sin precio todavía · sos la primera persona en informarlo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    else -> {}
                }
                // Lo que dice el catálogo para el súper donde estás parado
                val precioAca = cadenaActiva?.let { producto.precios[it] }
                if (precioAca != null && precioAca > 0.0) {
                    Text(
                        "acá suele estar ${Formato.precio(precioAca)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { informandoPrecio = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                ) { Text("Informar precio de góndola", fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { seguirEscaneando() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(999.dp)
                ) { Text("Seguir escaneando") }
            }
        }
    }

    // --- Informar precio: chips de TODAS las cadenas, la activa preseleccionada ---
    if (informandoPrecio && producto != null) {
        InformarPrecioSheet(
            producto = producto,
            cadenas = cadenas,
            cadenaInicial = cadenaActiva,
            onDismiss = { informandoPrecio = false },
            onGuardar = { precio, cadenaElegida ->
                scope.launch {
                    val ean = scannedBarcode
                    if (ean != null) {
                        val ok = viewModel.saveGondolaObservation(ean, producto.descripcion, precio, cadenaElegida)
                        if (ok) {
                            viewModel.elegirCadenaActiva(cadenaElegida)
                            viewModel.mostrarMensaje(
                                MensajeUi("Precio informado en ${Cadenas.nombre(cadenaElegida)} · gracias")
                            )
                        }
                    }
                    informandoPrecio = false
                    seguirEscaneando()
                }
            }
        )
    }

    // --- Producto que no está en el catálogo: alta rápida ---
    if (mostrarAltaRapida) {
        AltaRapidaSheet(
            ean = scannedBarcode.orEmpty(),
            onDismiss = {
                mostrarAltaRapida = false
                seguirEscaneando()
            },
            onGuardar = { descripcion, presentacion ->
                scope.launch {
                    val code = scannedBarcode
                    if (code != null) {
                        val guardado = viewModel.saveUserProduct(code, descripcion, presentacion)
                        if (guardado != null) {
                            viewModel.addManualProductToList(listId, guardado)
                            scannedProduct = guardado
                            scannedPrice = DisplayPrice.None
                            tildadoEnLista = false
                            mostrarAltaRapida = false
                        } else {
                            mostrarAltaRapida = false
                            seguirEscaneando()
                        }
                    }
                }
            }
        )
    }

    // --- ¿En qué súper estás? ---
    if (eligiendoCadena) {
        ElegirCadenaSheet(
            cadenas = cadenas,
            cadenaActual = cadenaActiva,
            onDismiss = {
                eligiendoCadena = false
                // Sin súper elegido no hay Modo Súper posible
                if (viewModel.cadenaActiva == null) onClose()
            },
            onElegir = {
                viewModel.elegirCadenaActiva(it)
                eligiendoCadena = false
            }
        )
    }
}

@Composable
private fun ChipCadenaActiva(cadenaId: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.tertiary,
        shape = RoundedCornerShape(999.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Storefront,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (cadenaId == null) "¿En qué súper estás?" else "Estás en ${Cadenas.nombre(cadenaId)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = "Cambiar de súper",
                tint = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ElegirCadenaSheet(
    cadenas: List<String>,
    cadenaActual: String?,
    onDismiss: () -> Unit,
    onElegir: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("¿En qué súper estás?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Los precios que informes se guardan en este súper. Podés cambiarlo cuando quieras.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            if (cadenas.isEmpty()) {
                Text(
                    "No pudimos traer la lista de súperes. Revisá la conexión y volvé a entrar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cadenas.forEach { id ->
                    FilterChip(
                        selected = id == cadenaActual,
                        onClick = { onElegir(id) },
                        label = { Text(Cadenas.nombre(id)) },
                        leadingIcon = if (id == cadenaActual) {
                            { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InformarPrecioSheet(
    producto: ProductModel,
    cadenas: List<String>,
    cadenaInicial: String?,
    onDismiss: () -> Unit,
    onGuardar: (Double, String) -> Unit
) {
    var precio by remember { mutableStateOf("") }
    var cadenaElegida by remember { mutableStateOf(cadenaInicial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Precio de góndola", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                producto.descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = precio,
                onValueChange = { precio = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text("Precio que ves en la góndola") },
                prefix = { Text("$") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "¿En qué súper?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Todas las cadenas del catálogo, nunca una lista fija
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cadenas.forEach { id ->
                    FilterChip(
                        selected = id == cadenaElegida,
                        onClick = { cadenaElegida = id },
                        label = { Text(Cadenas.nombre(id)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            val valor = precio.replace(',', '.').toDoubleOrNull()
            val elegida = cadenaElegida
            Button(
                onClick = { if (valor != null && elegida != null) onGuardar(valor, elegida) },
                enabled = valor != null && valor > 0.0 && elegida != null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Text(
                    if (elegida == null) "Elegí el súper" else "Guardar precio en ${Cadenas.nombre(elegida)}",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AltaRapidaSheet(
    ean: String,
    onDismiss: () -> Unit,
    onGuardar: (String, String) -> Unit
) {
    var descripcion by remember { mutableStateOf("") }
    var presentacion by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Este producto no está todavía", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Cargalo vos y queda para toda la comunidad. Código $ean",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = descripcion,
                onValueChange = { descripcion = it },
                label = { Text("Descripción") },
                placeholder = { Text("Fideos guiseros") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = presentacion,
                onValueChange = { presentacion = it },
                label = { Text("Presentación") },
                placeholder = { Text("500 g") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onGuardar(descripcion, presentacion) },
                enabled = descripcion.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(999.dp)
            ) { Text("Guardar y agregar", fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Ignorar") }
        }
    }
}
