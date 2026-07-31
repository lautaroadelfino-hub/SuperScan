package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.data.Cadenas
import com.example.data.DisplayPrice
import com.example.data.Formato
import com.example.data.OrdenCatalogo
import com.example.data.ProductModel
import com.example.data.SubcategoriaMeta

// Catálogo navegable sobre productos/{ean}: grilla de categorías →
// subcategorías → productos paginados de a 20 → detalle con comparador.
// El buscador por prefijo está siempre visible arriba y pisa la navegación
// mientras haya texto (salvo en el detalle, para poder abrir un resultado).
// El escaneo de producto vive en el botón central de MainScreen.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    catalogViewModel: CatalogViewModel,
    hasCurrentList: Boolean,
    onAddToList: (ProductModel) -> Unit
) {
    val destino = catalogViewModel.navStack.last()
    val busquedaActiva = catalogViewModel.searchQuery.isNotBlank() &&
        destino !is CatalogViewModel.Destino.Detalle

    val teclado = LocalSoftwareKeyboardController.current
    val foco = LocalFocusManager.current
    var buscadorEnfocado by remember { mutableStateOf(false) }

    fun cerrarTeclado() {
        teclado?.hide()
        foco.clearFocus()
    }

    // El "atrás" primero baja el teclado: si no, la pantalla navega por debajo
    // y el teclado queda tapando los resultados sin forma de cerrarlo.
    BackHandler(
        enabled = catalogViewModel.navStack.size > 1 || busquedaActiva || buscadorEnfocado
    ) {
        when {
            buscadorEnfocado -> cerrarTeclado()
            busquedaActiva -> catalogViewModel.limpiarBusqueda()
            else -> catalogViewModel.volver()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = catalogViewModel.searchQuery,
                onValueChange = { catalogViewModel.onSearchQueryChange(it) },
                placeholder = { Text("Buscar en el catálogo...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
                trailingIcon = {
                    if (catalogViewModel.isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else if (catalogViewModel.searchQuery.isNotBlank()) {
                        IconButton(onClick = {
                            catalogViewModel.limpiarBusqueda()
                            cerrarTeclado()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar búsqueda")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { buscadorEnfocado = it.isFocused },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                // La tecla del teclado dice "Buscar" y al tocarla lo baja, que
                // es lo que uno espera al terminar de escribir.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { cerrarTeclado() })
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                busquedaActiva -> SearchResultsList(
                    resultados = catalogViewModel.searchResults,
                    isSearching = catalogViewModel.isSearching,
                    hasCurrentList = hasCurrentList,
                    onAddToList = onAddToList,
                    onOpen = {
                        cerrarTeclado()
                        catalogViewModel.abrirDetalle(it)
                    },
                    onScroll = { cerrarTeclado() }
                )
                destino is CatalogViewModel.Destino.Categorias -> CategoriasGrid(
                    estado = catalogViewModel.estructura,
                    onRetry = { catalogViewModel.cargarEstructura() },
                    onCategoria = { catalogViewModel.abrirCategoria(it) }
                )
                destino is CatalogViewModel.Destino.Subcategorias -> SubcategoriasList(
                    categoria = destino.categoria,
                    subcategorias = catalogViewModel.subcategoriasDe(destino.categoria),
                    onSubcategoria = { catalogViewModel.abrirSubcategoria(destino.categoria, it) }
                )
                destino is CatalogViewModel.Destino.Productos -> ProductosPagedList(
                    catalogViewModel = catalogViewModel,
                    destino = destino,
                    hasCurrentList = hasCurrentList,
                    onAddToList = onAddToList
                )
                destino is CatalogViewModel.Destino.Detalle -> catalogViewModel.seleccionado?.let { producto ->
                    ProductDetailScreen(
                        producto = producto,
                        displayPrice = catalogViewModel.precioSeleccionado,
                        hasCurrentList = hasCurrentList,
                        onAddToList = onAddToList
                    )
                }
            }
        }
    }

    if (catalogViewModel.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { catalogViewModel.clearError() },
            title = { Text("Aviso") },
            text = { Text(catalogViewModel.errorMessage!!) },
            confirmButton = {
                TextButton(onClick = { catalogViewModel.clearError() }) { Text("OK") }
            }
        )
    }
}

// --- Nivel 1: grilla de categorías ---

@Composable
private fun CategoriasGrid(
    estado: CatalogViewModel.EstadoEstructura,
    onRetry: () -> Unit,
    onCategoria: (String) -> Unit
) {
    when (estado) {
        is CatalogViewModel.EstadoEstructura.Cargando -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(6) { SkeletonBox(modifier = Modifier.height(110.dp).fillMaxWidth()) }
            }
        }
        is CatalogViewModel.EstadoEstructura.NoDisponible -> MensajeCentrado(
            titulo = "El catálogo todavía no está disponible",
            detalle = "Falta el documento catalogo_meta/estructura en Firestore. " +
                "Generalo desde el pipeline de carga y reintentá.",
            onRetry = onRetry
        )
        is CatalogViewModel.EstadoEstructura.Error -> MensajeCentrado(
            titulo = "No se pudo cargar el catálogo",
            detalle = estado.mensaje,
            onRetry = onRetry
        )
        is CatalogViewModel.EstadoEstructura.Cargada -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(estado.estructura.categorias.size) { i ->
                    val categoria = estado.estructura.categorias[i]
                    val estilo = CategoriasUi.estilo(categoria.nombre)
                    Card(
                        onClick = { onCategoria(categoria.nombre) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(estilo.color.copy(alpha = 0.18f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(estilo.icono, contentDescription = null, tint = estilo.color)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                categoria.nombre,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Nivel 2: subcategorías de una categoría ---

@Composable
private fun SubcategoriasList(
    categoria: String,
    subcategorias: List<SubcategoriaMeta>,
    onSubcategoria: (String) -> Unit
) {
    val estilo = CategoriasUi.estilo(categoria)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(estilo.icono, contentDescription = null, tint = estilo.color)
            Spacer(modifier = Modifier.width(8.dp))
            Text(categoria, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (subcategorias.isEmpty()) {
            MensajeCentrado(titulo = "Esta categoría no tiene subcategorías", detalle = null, onRetry = null)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(subcategorias, key = { it.nombre }) { sub ->
                    Card(
                        onClick = { onSubcategoria(sub.nombre) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(sub.nombre, style = MaterialTheme.typography.titleMedium)
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Nivel 3: productos paginados con filtro de marca y orden ---

@Composable
private fun ProductosPagedList(
    catalogViewModel: CatalogViewModel,
    destino: CatalogViewModel.Destino.Productos,
    hasCurrentList: Boolean,
    onAddToList: (ProductModel) -> Unit
) {
    val productos = catalogViewModel.productos.collectAsLazyPagingItems()
    val filtro by catalogViewModel.filtro.collectAsState()
    val marcas = catalogViewModel.marcasDe(destino.categoria, destino.subcategoria)
    var ordenMenuAbierto by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                destino.subcategoria,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box {
                IconButton(onClick = { ordenMenuAbierto = true }) {
                    Icon(Icons.Default.Sort, contentDescription = "Ordenar")
                }
                DropdownMenu(expanded = ordenMenuAbierto, onDismissRequest = { ordenMenuAbierto = false }) {
                    OpcionOrden("Alfabético", OrdenCatalogo.ALFABETICO, filtro?.orden) {
                        catalogViewModel.setOrden(it); ordenMenuAbierto = false
                    }
                    OpcionOrden("Precio: menor a mayor", OrdenCatalogo.PRECIO_ASC, filtro?.orden) {
                        catalogViewModel.setOrden(it); ordenMenuAbierto = false
                    }
                    OpcionOrden("Precio: mayor a menor", OrdenCatalogo.PRECIO_DESC, filtro?.orden) {
                        catalogViewModel.setOrden(it); ordenMenuAbierto = false
                    }
                }
            }
        }

        if (marcas.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(marcas, key = { it }) { marca ->
                    FilterChip(
                        selected = filtro?.marca == marca,
                        onClick = {
                            catalogViewModel.setMarca(if (filtro?.marca == marca) null else marca)
                        },
                        label = { Text(marca) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        when {
            productos.loadState.refresh is LoadState.Loading -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(6) { SkeletonBox(modifier = Modifier.height(72.dp).fillMaxWidth()) }
                }
            }
            productos.loadState.refresh is LoadState.Error -> MensajeCentrado(
                titulo = "No se pudieron cargar los productos",
                detalle = mensajeErrorProductos((productos.loadState.refresh as LoadState.Error).error),
                onRetry = { productos.retry() }
            )
            productos.itemCount == 0 -> MensajeCentrado(
                titulo = "No hay productos acá",
                detalle = if (filtro?.marca != null) {
                    "Probá quitando el filtro de marca."
                } else if (filtro?.orden != OrdenCatalogo.ALFABETICO) {
                    "El orden por precio solo muestra productos con precio cargado. Probá el orden alfabético."
                } else {
                    null
                },
                onRetry = null
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(count = productos.itemCount, key = productos.itemKey { it.ean }) { i ->
                    productos[i]?.let { producto ->
                        ProductoCard(
                            producto = producto,
                            hasCurrentList = hasCurrentList,
                            onAddToList = onAddToList,
                            onClick = { catalogViewModel.abrirDetalle(producto) }
                        )
                    }
                }
                when (val append = productos.loadState.append) {
                    is LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    is LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { productos.retry() }) {
                                Text("Error al cargar más: reintentar")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

// El FAILED_PRECONDITION de "index ... building" aparece durante los minutos
// que Firestore tarda en construir un índice compuesto recién deployado; el
// mensaje crudo trae un link kilométrico que no aporta nada en el teléfono.
private fun mensajeErrorProductos(e: Throwable): String? {
    val msg = e.message ?: return null
    return if (msg.contains("index", ignoreCase = true) && msg.contains("building", ignoreCase = true)) {
        "Firestore todavía está construyendo los índices del catálogo. Suele tardar unos minutos: reintentá en un rato."
    } else {
        msg
    }
}

@Composable
private fun OpcionOrden(
    texto: String,
    orden: OrdenCatalogo,
    actual: OrdenCatalogo?,
    onSelect: (OrdenCatalogo) -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(texto, fontWeight = if (orden == actual) FontWeight.Bold else FontWeight.Normal)
        },
        onClick = { onSelect(orden) }
    )
}

// --- Resultados de la búsqueda por prefijo ---

@Composable
private fun SearchResultsList(
    resultados: List<ProductModel>,
    isSearching: Boolean,
    hasCurrentList: Boolean,
    onAddToList: (ProductModel) -> Unit,
    onOpen: (ProductModel) -> Unit,
    onScroll: () -> Unit = {}
) {
    if (resultados.isEmpty()) {
        if (!isSearching) {
            MensajeCentrado(
                titulo = "No se encontraron productos",
                detalle = "La búsqueda es por el comienzo de la descripción.",
                onRetry = null
            )
        }
    } else {
        val estadoGrilla = rememberLazyGridState()
        // Al arrastrar los resultados se baja el teclado: es el gesto natural
        // para "ya terminé de escribir, quiero ver".
        LaunchedEffect(estadoGrilla) {
            snapshotFlow { estadoGrilla.isScrollInProgress }
                .collect { if (it) onScroll() }
        }
        LazyVerticalGrid(
            state = estadoGrilla,
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(resultados.size, key = { resultados[it].ean }) { i ->
                val producto = resultados[i]
                ProductoCard(
                    producto = producto,
                    hasCurrentList = hasCurrentList,
                    onAddToList = onAddToList,
                    onClick = { onOpen(producto) }
                )
            }
        }
    }
}

// --- Tarjeta de producto (grilla del catálogo y de la búsqueda) ---
// Foto de estudio grande arriba (el activo que más jerarquiza la app), nombre,
// y el precio del catálogo. La observación propia del usuario, que requiere
// una consulta por producto, se resuelve recién en el detalle para no
// multiplicar lecturas en las grillas.

@Composable
private fun ProductoCard(
    producto: ProductModel,
    hasCurrentList: Boolean,
    onAddToList: (ProductModel) -> Unit,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().height(110.dp),
                contentAlignment = Alignment.Center
            ) {
                MiniaturaProducto(imagen = producto.imagen, size = 96.dp)
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 10.dp)) {
                Text(
                    producto.descripcion,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = 32.dp)
                )
                if (producto.marca.isNotBlank()) {
                    Text(
                        producto.marca,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    PrecioCompacto(producto.precioCatalogo())
                    if (hasCurrentList) {
                        IconButton(
                            onClick = { onAddToList(producto) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Añadir a lista",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// Variante alineada a la izquierda para las tarjetas de grilla
@Composable
private fun PrecioCompacto(precio: DisplayPrice) {
    Column {
        when (precio) {
            is DisplayPrice.UserObservation -> {
                Text(Formato.precio(precio.precio), style = MaterialTheme.typography.titleMedium)
                Text("tu último precio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DisplayPrice.PublicPrice -> {
                Text(Formato.precio(precio.precio), style = MaterialTheme.typography.titleMedium)
                Text("usuarios (${precio.n})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DisplayPrice.MinPrice -> {
                Text(Formato.precio(precio.precio), style = MaterialTheme.typography.titleMedium)
                Text("desde · ${Cadenas.nombre(precio.cadena)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DisplayPrice.None -> Text(
                "sin precio todavía",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is DisplayPrice.Failure -> Text(
                "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PrecioResumen(precio: DisplayPrice) {
    when (precio) {
        is DisplayPrice.UserObservation -> {
            PrecioConEtiqueta(precio.precio, "tu último precio en ${precio.comercio}")
        }
        is DisplayPrice.PublicPrice -> {
            PrecioConEtiqueta(precio.precio, "informado por usuarios (${precio.n})")
        }
        is DisplayPrice.MinPrice -> {
            PrecioConEtiqueta(precio.precio, "en ${Cadenas.nombre(precio.cadena)}", prefijo = "desde ")
        }
        is DisplayPrice.None -> Text(
            "sin precio todavía",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        is DisplayPrice.Failure -> Text(
            "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrecioConEtiqueta(precio: Double, etiqueta: String, prefijo: String = "") {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            "$prefijo${Formato.precio(precio)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Piezas compartidas ---

@Composable
fun MiniaturaProducto(imagen: String?, size: Dp) {
    if (imagen != null) {
        // Si la URL remota falla (imagen dada de baja en Open Food Facts,
        // sin conexión y sin caché), se cae al mismo placeholder de "sin imagen"
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imagen)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
            loading = { SkeletonBox(modifier = Modifier.size(size)) },
            error = { PlaceholderImagen(size) }
        )
    } else {
        PlaceholderImagen(size)
    }
}

@Composable
private fun PlaceholderImagen(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "skeleton_alpha"
    )
    Box(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            RoundedCornerShape(12.dp)
        )
    )
}

@Composable
private fun MensajeCentrado(titulo: String, detalle: String?, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (detalle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                detalle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("Reintentar") }
        }
    }
}
