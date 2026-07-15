package com.example.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FirebaseRepository
import com.example.data.LocalRepository
import com.example.data.ProductEntity
import com.example.data.ProductModel
import com.example.data.ReceiptEntity
import com.example.data.ReceiptItem
import com.example.data.SupermarketHistory
import com.example.domain.ExtractedReceipt
import com.example.domain.ReceiptScannerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainViewModel(
    private val repository: LocalRepository,
    private val scannerService: ReceiptScannerService = ReceiptScannerService(),
    private val firebaseRepository: FirebaseRepository = FirebaseRepository(),
    private val prefs: SharedPreferences? = null
) : ViewModel() {

    private companion object {
        const val BUDGET_PREF_KEY = "budget"
    }

    // Hardcoded categories as requested (single source of truth in ReceiptScannerService)
    val PRESET_CATEGORIES = ReceiptScannerService.PRESET_CATEGORIES

    // Presupuesto del usuario, persistido en SharedPreferences
    private val _budget = MutableStateFlow(prefs?.getFloat(BUDGET_PREF_KEY, 0f)?.toDouble() ?: 0.0)
    val budget: StateFlow<Double> = _budget

    fun updateBudget(newBudget: Double) {
        _budget.value = newBudget
        prefs?.edit()?.putFloat(BUDGET_PREF_KEY, newBudget.toFloat())?.apply()
    }

    val receipts: StateFlow<List<ReceiptEntity>> = firebaseRepository.getTickets()
        .map { tickets ->
            tickets
                .sortedByDescending { it.date }
                .map { t ->
                    ReceiptEntity(
                        id = t.id,
                        date = t.date,
                        totalAmount = t.totalAmount,
                        storeName = t.storeName,
                        items = t.items.map { i ->
                            ReceiptItem(
                                productName = i.productName,
                                category = i.category,
                                unitPrice = i.unitPrice,
                                totalPrice = i.totalPrice,
                                quantity = i.quantity,
                                barcode = i.barcode
                            )
                        }
                    )
                }
        }
        .catch { e -> errorMessage = "Error cargando datos: ${e.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val shoppingLists: StateFlow<List<com.example.data.SharedListModel>> = firebaseRepository.getSharedLists()
        .catch { e -> errorMessage = "Error cargando listas: ${e.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentListId = MutableStateFlow<String?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentListItems: StateFlow<List<com.example.data.SharedListItemModel>> = _currentListId.flatMapLatest { listId ->
        if (listId == null) flowOf(emptyList())
        else firebaseRepository.getSharedListItems(listId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Catálogo personal: solo los productos del usuario (Room).
    // Los resultados del catálogo global llegan por remoteSearchResults.
    val allProducts: StateFlow<List<ProductEntity>> = repository.getProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _remoteSearchResults = MutableStateFlow<List<ProductEntity>>(emptyList())
    val remoteSearchResults: StateFlow<List<ProductEntity>> = _remoteSearchResults

    var isSearchingRemote by mutableStateOf(false)
        private set

    private var searchJob: Job? = null

    private fun ProductModel.toProductEntity() = ProductEntity(
        barcode = ean,
        productName = descripcion,
        category = "Varios",
        lastPrice = effectivePrice(),
        supermarketHistory = emptyList()
    )

    // Clave para productos sin código de barras: se identifican por nombre
    private fun nameKey(name: String) = "nombre:" + name.trim().lowercase(Locale.getDefault())

    fun performSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _remoteSearchResults.value = emptyList()
            isSearchingRemote = false
            return
        }

        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(500) // Debounce
            isSearchingRemote = true
            try {
                val results = firebaseRepository.searchProductsByDescription(query)
                _remoteSearchResults.value = results.map { it.toProductEntity() }
            } catch (e: CancellationException) {
                // Una búsqueda más nueva canceló a esta: no pisar resultados ni mostrar error
                throw e
            } catch (e: Exception) {
                errorMessage = "Error en búsqueda remota: ${e.message}"
            } finally {
                isSearchingRemote = false
            }
        }
    }

    // Busca un código escaneado primero en el catálogo personal (crudo y
    // normalizado a GTIN-13) y después en Firestore.
    suspend fun lookupProductByBarcode(rawBarcode: String): ProductEntity? {
        val normalized = FirebaseRepository.normalizeToGtin13(rawBarcode)
        repository.getProduct(rawBarcode)?.let { return it }
        if (normalized != rawBarcode) {
            repository.getProduct(normalized)?.let { return it }
        }
        return firebaseRepository.searchProductByEan(rawBarcode)?.toProductEntity()
    }

    var isProcessing by mutableStateOf(false)
        private set
    var loadingMessage by mutableStateOf("Analizando documento con IA...")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun selectShoppingList(listId: String) {
        _currentListId.value = listId
    }

    fun toggleShoppingItem(itemId: String, isChecked: Boolean) {
        val listId = _currentListId.value ?: return
        viewModelScope.launch {
            try {
                firebaseRepository.toggleItemScanned(listId, itemId, isChecked)
            } catch (e: Exception) {
                errorMessage = "Error al actualizar: ${e.message}"
            }
        }
    }

    fun removeShoppingItem(itemId: String) {
        val listId = _currentListId.value ?: return
        viewModelScope.launch {
            try {
                firebaseRepository.removeItemFromList(listId, itemId)
            } catch (e: Exception) {
                errorMessage = "Error al eliminar: ${e.message}"
            }
        }
    }

    fun updateShoppingItemQuantity(itemId: String, quantity: Double) {
        val listId = _currentListId.value ?: return
        viewModelScope.launch {
            try {
                firebaseRepository.updateItemQuantity(listId, itemId, quantity)
            } catch (e: Exception) {
                errorMessage = "Error al actualizar cantidad: ${e.message}"
            }
        }
    }

    fun clearShoppingListItems() {
        val listId = _currentListId.value ?: return
        viewModelScope.launch {
            try {
                isProcessing = true
                loadingMessage = "Vaciando lista..."
                firebaseRepository.clearListItems(listId)
            } catch (e: Exception) {
                errorMessage = "Error al vaciar lista: ${e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    fun deleteShoppingList(listId: String) {
        viewModelScope.launch {
            try {
                isProcessing = true
                loadingMessage = "Eliminando lista..."
                firebaseRepository.deleteSharedList(listId)
                if (_currentListId.value == listId) {
                    _currentListId.value = null
                }
            } catch (e: Exception) {
                errorMessage = "Error al eliminar lista: ${e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    fun clearError() {
        errorMessage = null
    }

    fun deleteReceipt(receiptId: String) {
        viewModelScope.launch {
            try {
                firebaseRepository.deleteTicket(receiptId)
            } catch (e: Exception) {
                errorMessage = "Error al eliminar: ${e.message}"
            }
        }
    }

    var pendingReceipt by mutableStateOf<ExtractedReceipt?>(null)
        private set

    fun confirmReceipt(receipt: ExtractedReceipt) {
        viewModelScope.launch {
            try {
                isProcessing = true
                loadingMessage = "Guardando ticket..."
                val ticket = com.example.data.TicketModel(
                    storeName = receipt.storeName,
                    date = receipt.date,
                    totalAmount = receipt.totalAmount,
                    items = receipt.items.map {
                        com.example.data.TicketItemModel(
                            productName = it.productName,
                            category = it.category,
                            quantity = it.quantity,
                            unitPrice = it.unitPrice,
                            totalPrice = it.totalPrice,
                            barcode = it.barcode
                        )
                    }
                )
                firebaseRepository.saveTicket(ticket)
                updateLocalCatalogFromReceipt(receipt)
                pendingReceipt = null
            } catch (e: Exception) {
                errorMessage = "Error al guardar: ${e.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    // Mantiene el catálogo personal (Room) al día con cada ticket confirmado:
    // acumula el historial de precios por producto. Los ítems sin código de
    // barras se identifican por nombre normalizado.
    private suspend fun updateLocalCatalogFromReceipt(receipt: ExtractedReceipt) {
        receipt.items.forEach { item ->
            if (item.productName.isBlank()) return@forEach
            val key = item.barcode?.takeIf { it.isNotBlank() }
                ?.let { FirebaseRepository.normalizeToGtin13(it) }
                ?: nameKey(item.productName)
            val newHistoryItem = SupermarketHistory(
                supermarket = receipt.storeName,
                price = item.unitPrice,
                quantity = item.quantity,
                date = receipt.date
            )
            val existing = repository.getProduct(key)
            val product = existing?.copy(
                lastPrice = item.unitPrice,
                supermarketHistory = existing.supermarketHistory + newHistoryItem
            ) ?: ProductEntity(
                barcode = key,
                productName = item.productName,
                category = item.category,
                lastPrice = item.unitPrice,
                supermarketHistory = listOf(newHistoryItem)
            )
            repository.saveProduct(product)
        }
    }

    fun cancelReceipt() {
        pendingReceipt = null
    }

    fun processImage(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                isProcessing = true
                loadingMessage = "Enviando imagen a Gemini Flash..."
                errorMessage = null
                val result = scannerService.analyzeReceiptImage(bitmap)
                result.onSuccess { extracted ->
                    pendingReceipt = extracted
                }.onFailure { e ->
                    errorMessage = "Error al procesar la imagen: ${e.message ?: e.toString()}"
                }
            } finally {
                isProcessing = false
            }
        }
    }

    fun processPdf(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                isProcessing = true
                loadingMessage = "Enviando pdf a Gemini Flash..."
                errorMessage = null
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        val result = scannerService.analyzeReceiptPdf(bytes)
                        result.onSuccess { extracted ->
                            pendingReceipt = extracted
                        }.onFailure { e ->
                            errorMessage = "Error al procesar el PDF: ${e.message ?: e.toString()}"
                        }
                    } else {
                        errorMessage = "No se pudo leer el archivo PDF."
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorMessage = "Ocurrió un error al leer el archivo: ${e.message}"
                }
            } finally {
                isProcessing = false
            }
        }
    }

    suspend fun searchProducts(query: String) = firebaseRepository.searchProductsByDescription(query)

    suspend fun searchProductByEan(ean: String) = firebaseRepository.searchProductByEan(ean)
    suspend fun saveUserProduct(ean: String, desc: String, pres: String) = firebaseRepository.saveUserProduct(ean, desc, pres)
    suspend fun saveGondolaObservation(ean: String, name: String, price: Double) = firebaseRepository.saveGondolaObservation(ean, name, price)

    // Funciones para Listas de Compras y Catálogo Manual
    fun createShoppingList(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.createSharedList(name)
            } catch(e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun addProductToShoppingList(listId: String, product: ProductEntity, quantity: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.addProductToList(listId, product, quantity)
            } catch(e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun addManualProductToList(listId: String, product: ProductModel) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.addProductToList(listId, product, 1.0)
            } catch(e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun addManualProduct(barcode: String, name: String, category: String, price: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = if (barcode.isBlank()) nameKey(name)
                          else FirebaseRepository.normalizeToGtin13(barcode)
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())
                val newEntry = SupermarketHistory(supermarket = "Manual", price = price, quantity = 1.0, date = dateStr)
                // Si el producto ya existe se conserva y amplía su historial
                val existing = repository.getProduct(key)
                val product = existing?.copy(
                    productName = name,
                    category = category,
                    lastPrice = price,
                    supermarketHistory = existing.supermarketHistory + newEntry
                ) ?: ProductEntity(
                    barcode = key,
                    productName = name,
                    category = category,
                    lastPrice = price,
                    supermarketHistory = listOf(newEntry)
                )
                repository.saveProduct(product)
            } catch (e: Exception) {
                errorMessage = "Error al guardar producto: ${e.message}"
            }
        }
    }

    fun addMemberToList(email: String) {
        val listId = _currentListId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.addMemberToList(listId, email)
            } catch (e: Exception) {
                errorMessage = "Error al añadir miembro: ${e.message}"
            }
        }
    }
}
