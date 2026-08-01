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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

// Un mensaje efímero para el usuario: va a un Snackbar, no a un diálogo.
// Los diálogos quedan SOLO para acciones destructivas (fix 1 del rediseño).
data class MensajeUi(
    val texto: String,
    val accion: String? = null,
    val duracionLarga: Boolean = false,
    val onAccion: (() -> Unit)? = null
)

// Estado del escaneo de un ticket. La lectura NO bloquea la app: se muestra en
// una píldora flotante que muta a éxito o error (fix 2 del rediseño).
sealed interface EstadoEscaneo {
    data object Inactivo : EstadoEscaneo
    data object Leyendo : EstadoEscaneo
    /** Ticket leído, esperando que el usuario toque "Revisar" */
    data class Listo(val receipt: ExtractedReceipt) : EstadoEscaneo
}

class MainViewModel(
    private val repository: LocalRepository,
    private val scannerService: ReceiptScannerService = ReceiptScannerService(),
    private val firebaseRepository: FirebaseRepository = FirebaseRepository(),
    private val prefs: SharedPreferences? = null
) : ViewModel() {

    private companion object {
        const val BUDGET_PREF_KEY = "budget"
        /** Ventana para deshacer el borrado de un ticket, igual que el Snackbar */
        const val DESHACER_MS = 5_000L
    }

    // Presupuesto del usuario, persistido en SharedPreferences
    private val _budget = MutableStateFlow(prefs?.getFloat(BUDGET_PREF_KEY, 0f)?.toDouble() ?: 0.0)
    val budget: StateFlow<Double> = _budget

    fun updateBudget(newBudget: Double) {
        _budget.value = newBudget
        prefs?.edit()?.putFloat(BUDGET_PREF_KEY, newBudget.toFloat())?.apply()
    }

    // Mensajes efímeros (Snackbar). Channel y no StateFlow: son eventos de una
    // sola vez, no estado que deba re-emitirse al rotar la pantalla.
    private val _mensajes = Channel<MensajeUi>(Channel.BUFFERED)
    val mensajes = _mensajes.receiveAsFlow()

    fun mostrarMensaje(mensaje: MensajeUi) {
        _mensajes.trySend(mensaje)
    }

    // Tickets que el usuario borró pero todavía puede deshacer: se ocultan de la
    // UI al instante y recién se borran de Firestore cuando vence el Snackbar.
    private val _ticketsOcultos = MutableStateFlow<Set<String>>(emptySet())

    // Reintento de las cargas: un error de red no puede dejar la pantalla muerta.
    private val _reintentoTickets = MutableStateFlow(0)
    private val _reintentoListas = MutableStateFlow(0)

    /** Causa de que el historial no haya cargado, para la tarjeta inline con "Reintentar" */
    var errorCargaTickets by mutableStateOf<String?>(null)
        private set
    var errorCargaListas by mutableStateOf<String?>(null)
        private set

    /** false hasta la primera respuesta de Firestore: distingue "cargando" de "vacío" */
    var ticketsCargados by mutableStateOf(false)
        private set
    var listasCargadas by mutableStateOf(false)
        private set

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ticketsRemotos: StateFlow<List<ReceiptEntity>> = _reintentoTickets
        .flatMapLatest {
            firebaseRepository.getTickets()
                .map { tickets ->
                    errorCargaTickets = null
                    ticketsCargados = true
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
                .catch { e ->
                    errorCargaTickets = mensajeHumano(e)
                    ticketsCargados = true
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val receipts: StateFlow<List<ReceiptEntity>> =
        combine(ticketsRemotos, _ticketsOcultos) { tickets, ocultos ->
            tickets.filterNot { it.id in ocultos }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val shoppingLists: StateFlow<List<com.example.data.SharedListModel>> = _reintentoListas
        .flatMapLatest {
            firebaseRepository.getSharedLists()
                .map { listas ->
                    errorCargaListas = null
                    listasCargadas = true
                    listas
                }
                .catch { e ->
                    errorCargaListas = mensajeHumano(e)
                    listasCargadas = true
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun reintentarTickets() {
        errorCargaTickets = null
        ticketsCargados = false
        _reintentoTickets.update { it + 1 }
    }

    fun reintentarListas() {
        errorCargaListas = null
        listasCargadas = false
        _reintentoListas.update { it + 1 }
    }

    // La causa en lenguaje humano: el usuario no tiene por qué leer un stack de
    // Firestore para entender que se quedó sin señal.
    private fun mensajeHumano(e: Throwable): String {
        val texto = e.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            "unavailable" in texto || "network" in texto || "offline" in texto || "host" in texto ->
                "Parece que estás sin conexión."
            // Cuota del proveedor de IA agotada. Es un problema nuestro, no del
            // usuario: no tiene sentido ofrecerle "reintentar" como si fuera a
            // andar la próxima.
            "resource_exhausted" in texto || "quota" in texto || "credits" in texto ||
                "429" in texto || "exhausted" in texto ->
                "El lector de tickets está sin servicio por ahora. No es culpa tuya: lo estamos resolviendo."
            // App Check rechazó la llamada. Suele ser una build vieja o una app
            // instalada desde fuera de Play.
            "app check" in texto || "unauthenticated" in texto || "401" in texto ->
                "No pudimos validar la app. Si la instalaste por fuera de Google Play, probá con la versión de la tienda."
            "permission" in texto ->
                "Tu sesión no tiene permiso para ver esto. Probá cerrar sesión y volver a entrar."
            else -> "No pudimos traer los datos."
        }
    }

    private val _currentListId = MutableStateFlow<String?>(null)
    val currentListId: StateFlow<String?> = _currentListId

    /** Estado del catálogo de precios: fecha y cobertura, para la barra superior */
    var estadoPrecios by mutableStateOf<com.example.data.EstadoPrecios?>(null)
        private set

    /**
     * Interruptor remoto del lector de tickets (catalogo_meta/escaneo). Arranca
     * habilitado para que la UI no parpadee mientras se lee Firestore.
     */
    var estadoEscaneoTicket by mutableStateOf(com.example.data.EstadoEscaneoTicket())
        private set

    init {
        // De cuándo son los precios que está viendo el usuario. Se lee una vez
        // y queda en la caché offline de Firestore.
        viewModelScope.launch {
            estadoPrecios = try {
                firebaseRepository.getEstadoPrecios()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // sin dato no se muestra nada; nunca una fecha inventada
            }
        }

        // ¿El lector de tickets está operativo? Depende de un proveedor de IA
        // externo, así que la respuesta puede cambiar sin que salga una versión
        // nueva. getEstadoEscaneoTicket ya falla hacia "habilitado".
        viewModelScope.launch {
            estadoEscaneoTicket = firebaseRepository.getEstadoEscaneoTicket()
        }

        // Lista activa automática: el hero "conviene ir a…" de la portada
        // necesita una lista sin que el usuario tenga que entrar a ninguna.
        viewModelScope.launch {
            shoppingLists.collect { lists ->
                if (_currentListId.value == null && lists.isNotEmpty()) {
                    _currentListId.value = lists.first().id
                }
            }
        }
    }

    // Cantidad de precios que este usuario informó a la comunidad
    val misAportes: StateFlow<Int> = firebaseRepository.getObservationsCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    // Lectura del ticket: nunca bloquea la app, vive en la píldora flotante.
    var estadoEscaneo by mutableStateOf<EstadoEscaneo>(EstadoEscaneo.Inactivo)
        private set

    /** Operación breve en curso (guardar ticket, vaciar lista): también va a la píldora */
    var procesoEnCurso by mutableStateOf<String?>(null)
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
                showError("No se pudo tildar el producto. ${mensajeHumano(e)}")
            }
        }
    }

    fun removeShoppingItem(itemId: String) {
        val listId = _currentListId.value ?: return
        viewModelScope.launch {
            try {
                firebaseRepository.removeItemFromList(listId, itemId)
            } catch (e: Exception) {
                showError("No se pudo sacar el producto. ${mensajeHumano(e)}")
            }
        }
    }

    fun updateShoppingItemQuantity(itemId: String, quantity: Double) {
        val listId = _currentListId.value ?: return
        viewModelScope.launch {
            try {
                firebaseRepository.updateItemQuantity(listId, itemId, quantity)
            } catch (e: Exception) {
                showError("No se pudo cambiar la cantidad. ${mensajeHumano(e)}")
            }
        }
    }

    fun clearShoppingListItems() {
        val listId = _currentListId.value ?: return
        viewModelScope.launch {
            try {
                procesoEnCurso = "Vaciando la lista…"
                firebaseRepository.clearListItems(listId)
            } catch (e: Exception) {
                showError("No se pudo vaciar la lista. ${mensajeHumano(e)}")
            } finally {
                procesoEnCurso = null
            }
        }
    }

    fun deleteShoppingList(listId: String) {
        viewModelScope.launch {
            try {
                procesoEnCurso = "Eliminando la lista…"
                firebaseRepository.deleteSharedList(listId)
                if (_currentListId.value == listId) {
                    _currentListId.value = null
                }
                mostrarMensaje(MensajeUi("Lista eliminada"))
            } catch (e: Exception) {
                showError("No se pudo eliminar la lista. ${mensajeHumano(e)}")
            } finally {
                procesoEnCurso = null
            }
        }
    }

    /** Error de una acción: Snackbar, nunca diálogo (fix 1 del rediseño) */
    fun showError(message: String) {
        mostrarMensaje(MensajeUi(message))
    }

    // Borrado de ticket con red de seguridad: desaparece de la UI al instante,
    // pero Firestore recién se toca cuando vence el DESHACER. Si el usuario
    // deshace, no hubo viaje a la nube ni precios perdidos.
    private val borradosPendientes = mutableMapOf<String, Job>()

    fun deleteReceipt(receiptId: String) {
        if (borradosPendientes.containsKey(receiptId)) return
        _ticketsOcultos.update { it + receiptId }
        borradosPendientes[receiptId] = viewModelScope.launch {
            try {
                delay(DESHACER_MS)
                firebaseRepository.deleteTicket(receiptId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ticketsOcultos.update { it - receiptId }
                showError("No se pudo eliminar el ticket. ${mensajeHumano(e)}")
            } finally {
                borradosPendientes.remove(receiptId)
            }
        }
        mostrarMensaje(
            MensajeUi(
                texto = "Ticket eliminado",
                accion = "DESHACER",
                onAccion = { deshacerBorradoDeTicket(receiptId) }
            )
        )
    }

    fun deshacerBorradoDeTicket(receiptId: String) {
        borradosPendientes.remove(receiptId)?.cancel()
        _ticketsOcultos.update { it - receiptId }
    }

    var pendingReceipt by mutableStateOf<ExtractedReceipt?>(null)
        private set

    fun confirmReceipt(receipt: ExtractedReceipt) {
        viewModelScope.launch {
            try {
                procesoEnCurso = "Guardando el ticket…"
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
                estadoEscaneo = EstadoEscaneo.Inactivo
                val n = receipt.items.size
                mostrarMensaje(
                    MensajeUi("Ticket guardado · aportaste $n ${if (n == 1) "precio" else "precios"}")
                )
            } catch (e: Exception) {
                showError("No se pudo guardar el ticket. ${mensajeHumano(e)}")
            } finally {
                procesoEnCurso = null
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
        estadoEscaneo = EstadoEscaneo.Inactivo
    }

    /** Abre la revisión del ticket que terminó de leerse (botón "Revisar" de la píldora) */
    fun abrirTicketLeido() {
        val estado = estadoEscaneo
        if (estado is EstadoEscaneo.Listo) {
            pendingReceipt = estado.receipt
        }
    }

    fun descartarEscaneo() {
        estadoEscaneo = EstadoEscaneo.Inactivo
    }

    // Última lectura pedida, para que el "Reintentar" del Snackbar no obligue a
    // sacar la foto de nuevo.
    private var ultimoEscaneo: (suspend () -> Result<ExtractedReceipt>)? = null

    fun processImage(bitmap: Bitmap) {
        leerTicket { scannerService.analyzeReceiptImage(bitmap) }
    }

    fun processPdf(uri: Uri, context: Context) {
        leerTicket {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) Result.failure(IllegalStateException("No se pudo abrir el PDF"))
            else scannerService.analyzeReceiptPdf(bytes)
        }
    }

    fun reintentarEscaneo() {
        ultimoEscaneo?.let { leerTicket(it) }
    }

    // La lectura corre en segundo plano: la app sigue usable mientras Gemini
    // trabaja (fix 2). El progreso vive en la píldora, no en un velo.
    private fun leerTicket(lectura: suspend () -> Result<ExtractedReceipt>) {
        ultimoEscaneo = lectura
        viewModelScope.launch {
            estadoEscaneo = EstadoEscaneo.Leyendo
            val result = try {
                lectura()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onSuccess { extracted ->
                estadoEscaneo = EstadoEscaneo.Listo(extracted)
            }.onFailure { e ->
                estadoEscaneo = EstadoEscaneo.Inactivo
                mostrarMensaje(
                    MensajeUi(
                        texto = "No pudimos leer el ticket. ${mensajeHumano(e)}",
                        accion = "REINTENTAR",
                        duracionLarga = true,
                        onAccion = { reintentarEscaneo() }
                    )
                )
            }
        }
    }

    suspend fun searchProducts(query: String): List<ProductModel> = try {
        firebaseRepository.searchProductsByDescription(query)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        showError("No pudimos buscar. ${mensajeHumano(e)}")
        emptyList()
    }

    // Precio a mostrar: observación propia más reciente, o precio de referencia
    /**
     * Precio a mostrar. Si hay una cadena activa —o sea, si estás comprando en un
     * súper concreto— se acota la observación a esa cadena: un precio que
     * informaste en Vea no dice nada de lo que vale en Carrefour.
     */
    suspend fun getDisplayPrice(ean: String): DisplayPrice =
        firebaseRepository.getDisplayPrice(ean, cadena = cadenaActiva)

    suspend fun getPriceHistory(ean: String): List<ObservacionPrecio> = try {
        firebaseRepository.getPriceHistory(ean)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Si falta el índice compuesto, el mensaje de Firestore trae el link de creación
        showError("No pudimos traer el historial de precios. ${mensajeHumano(e)}")
        emptyList()
    }

    suspend fun saveUserProduct(ean: String, desc: String, pres: String): ProductModel? = try {
        firebaseRepository.saveUserProduct(ean, desc, pres)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        showError("No se pudo guardar el producto. ${mensajeHumano(e)}")
        null
    }

    // Cadena en la que está comprando el usuario ahora mismo. El Modo Súper la
    // pregunta al entrar y la recuerda por visita: un precio de góndola sin
    // súper no le sirve a nadie (fix 4 del rediseño).
    var cadenaActiva by mutableStateOf<String?>(null)
        private set

    fun elegirCadenaActiva(cadenaId: String) {
        cadenaActiva = cadenaId
    }

    suspend fun saveGondolaObservation(
        ean: String,
        name: String,
        price: Double,
        cadenaId: String
    ): Boolean = try {
        firebaseRepository.saveGondolaObservation(ean, name, price, cadenaId)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        showError("No se pudo guardar el precio. ${mensajeHumano(e)}")
        false
    }

    // --- La compra en curso (Modo Súper) ---

    /**
     * Un producto entró al changuito. Vive en Firestore y no acá: una compra dura
     * media hora y perder el carrito porque Android mató la app sería
     * insoportable. Además la lista es compartida, así que el otro que está
     * comprando lo ve en el momento.
     */
    fun sumarAlChanguito(listId: String, itemId: String, precio: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.sumarAlChanguito(listId, itemId, precio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("No pudimos sumarlo al changuito. ${mensajeHumano(e)}")
            }
        }
    }

    fun restarDelChanguito(listId: String, itemId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.restarDelChanguito(listId, itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("No pudimos sacarlo del changuito. ${mensajeHumano(e)}")
            }
        }
    }

    /**
     * Cierra la compra con el total que salió en la caja y la guarda como ticket.
     * Devuelve true si se guardó: la pantalla recién ahí se cierra.
     */
    suspend fun cerrarCompra(listId: String, totalReal: Double): Boolean {
        val cadena = cadenaActiva ?: run {
            showError("Antes de cerrar la compra decinos en qué súper estás.")
            return false
        }
        return try {
            val hoy = java.time.LocalDate.now().toString()
            val ticket = firebaseRepository.cerrarCompra(
                listId = listId,
                cadena = cadena,
                nombreComercio = com.example.data.Cadenas.nombre(cadena),
                totalReal = totalReal,
                fecha = hoy
            )
            if (ticket == null) {
                showError("No escaneaste nada todavía.")
                false
            } else {
                val n = ticket.items.size
                mostrarMensaje(
                    MensajeUi("Compra guardada · $n ${if (n == 1) "producto" else "productos"}")
                )
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError("No pudimos guardar la compra. ${mensajeHumano(e)}")
            false
        }
    }

    /** Cadenas conocidas para elegir dónde estoy: las del catálogo, nunca una lista fija */
    suspend fun cadenasConocidas(): List<String> = try {
        firebaseRepository.cadenasConocidas()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    // Funciones para Listas de Compras
    fun createShoppingList(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.createSharedList(name)
            } catch(e: Exception) {
                showError("No se pudo crear la lista. ${mensajeHumano(e)}")
            }
        }
    }

    fun addProductToShoppingList(listId: String, product: ProductModel, quantity: Double, nombreLista: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.addProductToList(listId, product, quantity)
                if (nombreLista != null) {
                    mostrarMensaje(MensajeUi("Agregado a $nombreLista"))
                }
            } catch(e: Exception) {
                showError("No se pudo agregar el producto. ${mensajeHumano(e)}")
            }
        }
    }

    fun addManualProductToList(listId: String, product: ProductModel) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.addProductToList(listId, product, 1.0)
            } catch(e: Exception) {
                showError("No se pudo agregar el producto. ${mensajeHumano(e)}")
            }
        }
    }

    fun addMemberToList(email: String) {
        val listId = _currentListId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                firebaseRepository.addMemberToList(listId, email)
                mostrarMensaje(MensajeUi("Invitación enviada a $email"))
            } catch (e: Exception) {
                showError("No se pudo invitar a $email. ${mensajeHumano(e)}")
            }
        }
    }
}
