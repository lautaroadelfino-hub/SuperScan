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
import com.example.data.ComparadorLista
import com.example.data.DisplayPrice
import com.example.data.Ean
import com.example.data.EanLookupResult
import com.example.data.FirebaseRepository
import com.example.data.LocalRepository
import com.example.data.ObservacionPrecio
import com.example.data.ProductEntity
import com.example.data.ProductModel
import com.example.data.ReceiptEntity
import com.example.data.ReceiptItem
import com.example.data.SupermarketHistory
import com.example.domain.ExtractedReceipt
import com.example.domain.ReceiptScannerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    // Comparador de lista: la lista activa cotizada en cada cadena.
    // Se recalcula solo cuando cambian los ítems; los EANs se buscan por lote
    // (resuelve desde la caché offline) y la cuenta la hace ComparadorLista.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val comparacionLista: StateFlow<ComparadorLista.Resultado?> = currentListItems
        .mapLatest { items ->
            if (items.isEmpty()) return@mapLatest null
            val porEan = try {
                val eans = items.mapNotNull { it.barcode?.let { b -> Ean.normalizar(b) } }
                if (eans.isEmpty()) emptyMap() else firebaseRepository.getProductosPorEans(eans)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap() // sin red y sin caché: la lista sigue funcionando sin comparador
            }
            ComparadorLista.cotizar(
                items.map { item ->
                    val ean = item.barcode?.let { b -> Ean.normalizar(b) }
                    porEan[ean] to item.targetQuantity
                }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Clave para productos sin código de barras: se identifican por nombre
    private fun nameKey(name: String) = "nombre:" + name.trim().lowercase(Locale.getDefault())

    // Busca un código escaneado: primero en Firestore (productos →
    // productos_usuarios; el catálogo rico con precios por cadena, marca e
    // imagen, y resuelve offline desde la caché). El catálogo personal de Room
    // queda como último recurso para códigos que no están en ninguna colección.
    suspend fun lookupBarcode(rawBarcode: String): EanLookupResult {
        val normalized = Ean.normalizar(rawBarcode)
            ?: return EanLookupResult.InvalidEan(rawBarcode)
        val remoto = firebaseRepository.lookupProductByEan(rawBarcode)
        if (remoto is EanLookupResult.Found || remoto is EanLookupResult.Failure) {
            return remoto
        }
        val local = repository.getProduct(rawBarcode) ?: repository.getProduct(normalized)
        if (local != null) {
            return EanLookupResult.Found(
                ProductModel(ean = normalized, descripcion = local.productName)
            )
        }
        return remoto // NotFound u Offline
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

    fun showError(message: String) {
        errorMessage = message
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
            val key = item.barcode?.let { Ean.normalizar(it) }
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
                loadingMessage = "Enviando imagen a Gemini 3.5 Flash..."
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
                loadingMessage = "Enviando pdf a Gemini 3.5 Flash..."
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

    suspend fun searchProducts(query: String): List<ProductModel> = try {
        firebaseRepository.searchProductsByDescription(query)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorMessage = "Error buscando productos: ${e.message}"
        emptyList()
    }

    // Precio a mostrar: observación propia más reciente, o precio de referencia
    suspend fun getDisplayPrice(ean: String): DisplayPrice = firebaseRepository.getDisplayPrice(ean)

    suspend fun getPriceHistory(ean: String): List<ObservacionPrecio> = try {
        firebaseRepository.getPriceHistory(ean)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Si falta el índice compuesto, el mensaje de Firestore trae el link de creación
        errorMessage = "Error cargando historial: ${e.message}"
        emptyList()
    }

    suspend fun saveUserProduct(ean: String, desc: String, pres: String): ProductModel? = try {
        firebaseRepository.saveUserProduct(ean, desc, pres)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorMessage = "No se pudo guardar el producto: ${e.message}"
        null
    }

    suspend fun saveGondolaObservation(ean: String, name: String, price: Double): Boolean = try {
        firebaseRepository.saveGondolaObservation(ean, name, price)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorMessage = "No se pudo guardar el precio: ${e.message}"
        false
    }

    // Funciones para Listas de Compras
    fun createShoppingList(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.createSharedList(name)
            } catch(e: Exception) {
                errorMessage = e.message
            }
        }
    }

    fun addProductToShoppingList(listId: String, product: ProductModel, quantity: Double) {
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
