package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.data.CatalogoEstructura
import com.example.data.DisplayPrice
import com.example.data.FiltroProductos
import com.example.data.FirebaseRepository
import com.example.data.OrdenCatalogo
import com.example.data.ProductModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Estado del catálogo navegable: grilla de categorías → subcategorías →
// productos paginados → detalle. La navegación es un stack propio (no
// navigation-compose): las rutas serían nombres de categoría que vienen de los
// datos y podrían contener cualquier carácter.
class CatalogViewModel(
    private val firebaseRepository: FirebaseRepository = FirebaseRepository()
) : ViewModel() {

    sealed interface Destino {
        data object Categorias : Destino
        data class Subcategorias(val categoria: String) : Destino
        data class Productos(val categoria: String, val subcategoria: String) : Destino
        /** Muestra el producto en [seleccionado] */
        data object Detalle : Destino
    }

    val navStack = mutableStateListOf<Destino>(Destino.Categorias)

    fun volver() {
        if (navStack.size > 1) navStack.removeAt(navStack.lastIndex)
    }

    fun abrirCategoria(categoria: String) {
        navStack.add(Destino.Subcategorias(categoria))
    }

    // --- Estructura del catálogo (catalogo_meta/estructura) ---

    sealed interface EstadoEstructura {
        data object Cargando : EstadoEstructura
        data class Cargada(val estructura: CatalogoEstructura) : EstadoEstructura
        /** El doc de metadata todavía no existe en Firestore */
        data object NoDisponible : EstadoEstructura
        data class Error(val mensaje: String) : EstadoEstructura
    }

    var estructura by mutableStateOf<EstadoEstructura>(EstadoEstructura.Cargando)
        private set

    init {
        cargarEstructura()
    }

    fun cargarEstructura() {
        viewModelScope.launch {
            estructura = EstadoEstructura.Cargando
            estructura = try {
                val e = firebaseRepository.getCatalogoEstructura()
                if (e == null || e.categorias.isEmpty()) {
                    EstadoEstructura.NoDisponible
                } else {
                    EstadoEstructura.Cargada(e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoEstructura.Error(e.message ?: "No se pudo cargar el catálogo")
            }
        }
    }

    fun subcategoriasDe(categoria: String) =
        (estructura as? EstadoEstructura.Cargada)?.estructura
            ?.categorias?.firstOrNull { it.nombre == categoria }
            ?.subcategorias.orEmpty()

    // Marcas para los chips de filtro. El pipeline no debería incluir marcas
    // vacías, pero se filtran igual: "sin marca" no es una opción de filtro.
    fun marcasDe(categoria: String, subcategoria: String): List<String> =
        subcategoriasDe(categoria)
            .firstOrNull { it.nombre == subcategoria }
            ?.marcas.orEmpty()
            .filter { it.isNotBlank() }

    // --- Productos de la subcategoría actual, paginados ---

    private val _filtro = MutableStateFlow<FiltroProductos?>(null)
    val filtro: StateFlow<FiltroProductos?> = _filtro

    @OptIn(ExperimentalCoroutinesApi::class)
    val productos: Flow<PagingData<ProductModel>> = _filtro
        .filterNotNull()
        .flatMapLatest { firebaseRepository.getProductosPaginados(it) }
        .cachedIn(viewModelScope)

    fun abrirSubcategoria(categoria: String, subcategoria: String) {
        _filtro.value = FiltroProductos(categoria, subcategoria)
        navStack.add(Destino.Productos(categoria, subcategoria))
    }

    fun setMarca(marca: String?) {
        _filtro.update { it?.copy(marca = marca) }
    }

    fun setOrden(orden: OrdenCatalogo) {
        _filtro.update { it?.copy(orden = orden) }
    }

    // --- Búsqueda por prefijo (siempre visible arriba del catálogo) ---

    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<ProductModel>>(emptyList())
        private set
    var isSearching by mutableStateOf(false)
        private set

    private var searchJob: Job? = null

    fun onSearchQueryChange(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = emptyList()
            isSearching = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(400) // Debounce
            isSearching = true
            try {
                searchResults = firebaseRepository.searchProductsByDescription(query)
            } catch (e: CancellationException) {
                // Una búsqueda más nueva canceló a esta: no pisar resultados ni mostrar error
                throw e
            } catch (e: Exception) {
                errorMessage = "Error en la búsqueda: ${e.message}"
            } finally {
                isSearching = false
            }
        }
    }

    fun limpiarBusqueda() = onSearchQueryChange("")

    // --- Detalle de producto ---

    var seleccionado by mutableStateOf<ProductModel?>(null)
        private set
    var precioSeleccionado by mutableStateOf<DisplayPrice?>(null)
        private set

    fun abrirDetalle(producto: ProductModel) {
        seleccionado = producto
        precioSeleccionado = null
        navStack.add(Destino.Detalle)
        viewModelScope.launch {
            // getDisplayPrice no lanza: los fallos llegan como DisplayPrice.Failure
            precioSeleccionado = firebaseRepository.getDisplayPrice(producto.ean, producto)
        }
    }

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        errorMessage = null
    }
}
