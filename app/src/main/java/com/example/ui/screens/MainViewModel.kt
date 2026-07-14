package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LocalRepository
import com.example.data.ReceiptEntity
import com.example.data.ReceiptItem
import com.example.data.ProductEntity
import com.example.data.ProductModel
import com.example.data.SupermarketHistory
import com.example.data.ShoppingListEntity
import com.example.data.ShoppingListItem
import com.example.domain.ExtractedReceipt
import com.example.domain.ReceiptScannerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers

class MainViewModel(
    private val repository: LocalRepository,
    private val scannerService: ReceiptScannerService = ReceiptScannerService(),
    private val firebaseRepository: com.example.data.FirebaseRepository = com.example.data.FirebaseRepository()
) : ViewModel() {


    // Hardcoded categories as requested (single source of truth in ReceiptScannerService)
    val PRESET_CATEGORIES = ReceiptScannerService.PRESET_CATEGORIES

    // User preferences mock (since not in DB req)
    private val _budget = MutableStateFlow(0.0)
    val budget: StateFlow<Double> = _budget

    fun updateBudget(newBudget: Double) {
        _budget.value = newBudget
    }

    val receipts: StateFlow<List<ReceiptEntity>> = firebaseRepository.getTickets()
        .map { tickets ->
            tickets.map { t ->
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
    
    val currentListItems: StateFlow<List<com.example.data.SharedListItemModel>> = _currentListId.flatMapLatest { listId ->
        if (listId == null) flowOf(emptyList()) 
        else firebaseRepository.getSharedListItems(listId) 
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allProducts: StateFlow<List<ProductEntity>> = repository.getProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                pendingReceipt = null
            } catch (e: Exception) {
                errorMessage = "Error al guardar: ${e.message}"
            } finally {
                isProcessing = false
            }
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

    private suspend fun saveExtracted(extracted: ExtractedReceipt) {
        try {
            val dateStr = extracted.date // Assume it's YYYY-MM-DD
            
            val items = extracted.items.map {
                ReceiptItem(
                    productName = it.productName,
                    category = it.category,
                    unitPrice = it.unitPrice,
                    totalPrice = it.totalPrice,
                    quantity = it.quantity,
                    barcode = it.barcode
                )
            }

            val receipt = ReceiptEntity(
                id = UUID.randomUUID().toString(),
                date = dateStr,
                totalAmount = extracted.totalAmount,
                storeName = extracted.storeName,
                items = items
            )
            repository.saveReceipt(receipt)
            
            // Requisito 3: Actualizar/Crear el Catálogo de Productos
            extracted.items.forEach { item ->
                if (!item.barcode.isNullOrBlank()) {
                    val existing = repository.getProduct(item.barcode)
                    val newHistoryItem = SupermarketHistory(
                        supermarket = extracted.storeName,
                        price = item.unitPrice,
                        quantity = item.quantity,
                        date = dateStr
                    )
                    if (existing == null) {
                        val history = listOf(newHistoryItem)
                        val product = ProductEntity(
                            barcode = item.barcode,
                            productName = item.productName,
                            category = item.category,
                            lastPrice = item.unitPrice,
                            supermarketHistory = history
                        )
                        repository.saveProduct(product)
                    } else {
                        val currentHistory = existing.supermarketHistory
                        val history = currentHistory + newHistoryItem
                        val product = existing.copy(
                            lastPrice = item.unitPrice,
                            supermarketHistory = history
                        )
                        repository.saveProduct(product)
                    }
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            errorMessage = "Error al guardar: ${e.message}"
        }
    }

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
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateStr = sdf.format(java.util.Date())
            val history = listOf(SupermarketHistory(supermarket = "Manual", price = price, quantity = 1.0, date = dateStr))
            val product = ProductEntity(
                barcode = barcode,
                productName = name,
                category = category,
                lastPrice = price,
                supermarketHistory = history
            )
            repository.saveProduct(product)
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
