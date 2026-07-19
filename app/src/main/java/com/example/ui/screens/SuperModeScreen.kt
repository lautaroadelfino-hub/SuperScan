package com.example.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
// No longer importing androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.Cadenas
import com.example.data.DisplayPrice
import com.example.data.EanLookupResult
import com.example.data.Formato
import com.example.data.ProductModel
import com.example.ui.screens.MainViewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalGetImage::class, ExperimentalPermissionsApi::class)
@Composable
fun SuperModeScreen(
    viewModel: MainViewModel,
    listId: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    var scannedProduct by remember { mutableStateOf<ProductModel?>(null) }
    var scannedPrice by remember { mutableStateOf<DisplayPrice?>(null) }
    var scannedBarcode by remember { mutableStateOf<String?>(null) }
    var isProcessingBarcode by remember { mutableStateOf(false) }

    // Dialog states
    var showManualAddDialog by remember { mutableStateOf(false) }
    var showGondolaDialog by remember { mutableStateOf(false) }

    // Forms
    var newProductDesc by remember { mutableStateOf("") }
    var newProductPres by remember { mutableStateOf("") }
    var gondolaPrice by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    fun vibrateAndCheck() {
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                                                vibrateAndCheck()
                                                scannedBarcode = barcode
                                                scope.launch {
                                                    when (val res = viewModel.lookupBarcode(barcode)) {
                                                        is EanLookupResult.Found -> {
                                                            val prod = res.producto
                                                            scannedProduct = prod
                                                            scannedBarcode = prod.ean
                                                            scannedPrice = viewModel.getDisplayPrice(prod.ean)
                                                            // Toggle or add item to list (los ítems guardan el ean normalizado)
                                                            val items = viewModel.currentListItems.value
                                                            val existing = items.find { it.barcode == prod.ean }
                                                            if (existing != null) {
                                                                if (!existing.scanned) {
                                                                    viewModel.toggleShoppingItem(existing.id, true)
                                                                }
                                                            } else {
                                                                viewModel.addManualProductToList(listId, prod)
                                                            }
                                                            showGondolaDialog = true
                                                        }
                                                        is EanLookupResult.NotFound -> {
                                                            // ean ya normalizado: el alta manual escribe en productos_usuarios
                                                            scannedBarcode = res.ean
                                                            showManualAddDialog = true
                                                        }
                                                        is EanLookupResult.InvalidEan -> {
                                                            viewModel.showError("Código de barras inválido: ${res.raw}")
                                                            isProcessingBarcode = false
                                                        }
                                                        is EanLookupResult.Offline -> {
                                                            viewModel.showError("Sin conexión: el producto no está en la caché local.")
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
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Se requiere permiso de cámara para el Modo Súper.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Otorgar permiso")
                }
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = MaterialTheme.colorScheme.primary)
        }

        if (scannedProduct != null && !showGondolaDialog) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Escaneado: ${scannedProduct?.descripcion}", style = MaterialTheme.typography.titleMedium)
                    when (val p = scannedPrice) {
                        is DisplayPrice.UserObservation ->
                            Text("${Formato.precio(p.precio)} en ${p.comercio} (${p.fecha}) — tu último precio", style = MaterialTheme.typography.bodyMedium)
                        is DisplayPrice.PublicPrice ->
                            Text("${Formato.precio(p.precio)} · informado por usuarios (${p.n})", style = MaterialTheme.typography.bodyMedium)
                        is DisplayPrice.MinPrice ->
                            Text("desde ${Formato.precio(p.precio)} en ${Cadenas.nombre(p.cadena)}", style = MaterialTheme.typography.bodyMedium)
                        is DisplayPrice.None ->
                            Text("Sin precio todavía: podés informarlo acá", style = MaterialTheme.typography.bodyMedium)
                        else -> {}
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        isProcessingBarcode = false
                        scannedProduct = null
                        scannedPrice = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continuar escaneando")
                    }
                }
            }
        }
    }

    if (showManualAddDialog) {
        AlertDialog(
            onDismissRequest = { 
                showManualAddDialog = false
                isProcessingBarcode = false 
            },
            title = { Text("Producto no encontrado") },
            text = {
                Column {
                    Text("EAN: $scannedBarcode")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newProductDesc, onValueChange = { newProductDesc = it }, label = { Text("Descripción") })
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newProductPres, onValueChange = { newProductPres = it }, label = { Text("Presentación") })
                }
            },
            confirmButton = {
                Button(
                    enabled = newProductDesc.isNotBlank(),
                    onClick = {
                        scope.launch {
                            scannedBarcode?.let { code ->
                                // Escribe en productos_usuarios (productos es solo lectura)
                                val saved = viewModel.saveUserProduct(code, newProductDesc, newProductPres)
                                if (saved != null) {
                                    viewModel.addManualProductToList(listId, saved)
                                    scannedProduct = saved
                                    scannedPrice = null
                                    showManualAddDialog = false
                                    showGondolaDialog = true
                                } else {
                                    // El error ya quedó en viewModel.errorMessage
                                    showManualAddDialog = false
                                    isProcessingBarcode = false
                                }
                            }
                        }
                    }
                ) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showManualAddDialog = false
                    isProcessingBarcode = false 
                }) { Text("Ignorar") }
            }
        )
    }

    if (showGondolaDialog && scannedProduct != null) {
        AlertDialog(
            onDismissRequest = { 
                showGondolaDialog = false
                isProcessingBarcode = false 
                scannedProduct = null
                gondolaPrice = ""
            },
            title = { Text("Precio en góndola (Opcional)") },
            text = {
                Column {
                    Text(scannedProduct!!.descripcion)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gondolaPrice, 
                        onValueChange = { gondolaPrice = it }, 
                        label = { Text("Precio $") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val price = gondolaPrice.toDoubleOrNull()
                        if (price != null && scannedBarcode != null) {
                            viewModel.saveGondolaObservation(scannedBarcode!!, scannedProduct!!.descripcion, price)
                        }
                        showGondolaDialog = false
                        isProcessingBarcode = false
                        scannedProduct = null
                        gondolaPrice = ""
                    }
                }) { Text("Guardar precio") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showGondolaDialog = false
                    isProcessingBarcode = false 
                    scannedProduct = null
                    gondolaPrice = ""
                }) { Text("Omitir") }
            }
        )
    }
}
