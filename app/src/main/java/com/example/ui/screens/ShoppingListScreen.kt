package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Cadenas
import com.example.data.ComparadorLista
import com.example.data.Formato
import com.example.data.ProductModel
import com.example.data.SharedListItemModel
import com.example.data.SharedListModel
import kotlinx.coroutines.delay

@Composable
fun ShoppingListsScreen(
    viewModel: MainViewModel,
    lists: List<SharedListModel>,
    currentItems: List<SharedListItemModel>,
    activeListId: String?,
    gastoDelMes: Double,
    aportes: Int,
    estaOnline: Boolean,
    cargando: Boolean,
    errorCarga: String?,
    onReintentar: () -> Unit,
    onVerGastos: () -> Unit,
    onVerPerfil: () -> Unit,
    onAbrirProducto: (ProductModel) -> Unit,
    onBuscarEnCatalogo: (String) -> Unit,
    onListSelected: (String) -> Unit,
    onItemToggled: (String, Boolean) -> Unit,
    onItemDeleted: (String) -> Unit,
    onAddMember: (String) -> Unit,
    onCreateList: (String) -> Unit
) {
    var selectedListId by rememberSaveable { mutableStateOf<String?>(null) }
    val comparacion by viewModel.comparacionLista.collectAsState()
    var superMode by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var showCreateListDialog by remember { mutableStateOf(false) }
    var showDeleteListDialog by remember { mutableStateOf<String?>(null) }
    var showClearItemsDialog by remember { mutableStateOf(false) }
    var editingQuantityItem by remember { mutableStateOf<SharedListItemModel?>(null) }

    // Entrar a una lista es navegar: "atrás" tiene que volver a la portada, no
    // cerrar la app. (El Modo Súper maneja el suyo.)
    BackHandler(enabled = selectedListId != null && !superMode) {
        selectedListId = null
    }

    if (superMode && selectedListId != null) {
        val lista = lists.find { it.id == selectedListId }
        SuperModeScreen(
            viewModel = viewModel,
            listId = selectedListId!!,
            nombreLista = lista?.name ?: "tu lista",
            itemsDeLaLista = currentItems,
            onClose = { superMode = false }
        )
        return
    }

    if (showCreateListDialog) {
        DialogoTexto(
            titulo = "Nueva lista",
            etiqueta = "Nombre de la lista",
            textoBoton = "Crear",
            onConfirmar = { onCreateList(it) },
            onDismiss = { showCreateListDialog = false }
        )
    }

    if (showAddMemberDialog) {
        DialogoTexto(
            titulo = "Invitar a la lista",
            detalle = "Quien reciba la invitación va a ver y editar esta lista.",
            etiqueta = "Email",
            textoBoton = "Invitar",
            onConfirmar = { onAddMember(it.trim()) },
            onDismiss = { showAddMemberDialog = false }
        )
    }

    if (editingQuantityItem != null) {
        val item = editingQuantityItem!!
        DialogoTexto(
            titulo = "Cantidad",
            detalle = item.productName,
            etiqueta = "Cantidad",
            valorInicial = Formato.cantidad(item.targetQuantity),
            soloNumeros = true,
            textoBoton = "Guardar",
            onConfirmar = { viewModel.updateShoppingItemQuantity(item.id, it.toDoubleOrNull() ?: 1.0) },
            onDismiss = { editingQuantityItem = null }
        )
    }

    // Destructivas: acá sí van diálogos, y dicen qué se pierde.
    showDeleteListDialog?.let { listId ->
        val lista = lists.find { it.id == listId }
        AlertDialog(
            onDismissRequest = { showDeleteListDialog = null },
            title = { Text("¿Eliminar «${lista?.name ?: "la lista"}»?") },
            text = {
                Text(
                    if (lista != null && lista.members.size > 1) {
                        "Se elimina para vos y para las otras ${lista.members.size - 1} personas de la lista. No se puede deshacer."
                    } else {
                        "Se borran todos sus productos. No se puede deshacer."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteShoppingList(listId)
                        if (selectedListId == listId) selectedListId = null
                        showDeleteListDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteListDialog = null }) { Text("Cancelar") }
            }
        )
    }

    if (showClearItemsDialog) {
        AlertDialog(
            onDismissRequest = { showClearItemsDialog = false },
            title = { Text("¿Vaciar la lista?") },
            text = { Text("Se sacan los ${currentItems.size} productos. La lista queda, los productos no.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearShoppingListItems()
                        showClearItemsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Vaciar") }
            },
            dismissButton = {
                TextButton(onClick = { showClearItemsDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (selectedListId == null) {
        PortadaListas(
            viewModel = viewModel,
            lists = lists,
            currentItems = currentItems,
            activeListId = activeListId,
            comparacion = comparacion,
            gastoDelMes = gastoDelMes,
            aportes = aportes,
            estaOnline = estaOnline,
            cargando = cargando,
            errorCarga = errorCarga,
            onReintentar = onReintentar,
            onVerGastos = onVerGastos,
            onVerPerfil = onVerPerfil,
            onAbrirProducto = onAbrirProducto,
            onBuscarEnCatalogo = onBuscarEnCatalogo,
            onNuevaLista = { showCreateListDialog = true },
            onAbrirLista = { id ->
                selectedListId = id
                onListSelected(id)
            }
        )
    } else {
        DetalleDeLista(
            lista = lists.find { it.id == selectedListId },
            items = currentItems,
            comparacion = comparacion,
            estaOnline = estaOnline,
            onVolver = { selectedListId = null },
            onModoSuper = { superMode = true },
            onInvitar = { showAddMemberDialog = true },
            onVaciar = { showClearItemsDialog = true },
            onEliminarLista = { showDeleteListDialog = selectedListId },
            onItemToggled = onItemToggled,
            onItemDeleted = onItemDeleted,
            onEditarCantidad = { editingQuantityItem = it }
        )
    }
}

// ---------------------------------------------------------------- Portada ---

@Composable
private fun PortadaListas(
    viewModel: MainViewModel,
    lists: List<SharedListModel>,
    currentItems: List<SharedListItemModel>,
    activeListId: String?,
    comparacion: ComparadorLista.Resultado?,
    gastoDelMes: Double,
    aportes: Int,
    estaOnline: Boolean,
    cargando: Boolean,
    errorCarga: String?,
    onReintentar: () -> Unit,
    onVerGastos: () -> Unit,
    onVerPerfil: () -> Unit,
    onAbrirProducto: (ProductModel) -> Unit,
    onBuscarEnCatalogo: (String) -> Unit,
    onNuevaLista: () -> Unit,
    onAbrirLista: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BuscadorPortada(
            buscar = { viewModel.searchProducts(it) },
            onAbrirProducto = onAbrirProducto,
            onVerTodos = onBuscarEnCatalogo
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            val listaActiva = lists.find { it.id == activeListId }
            when {
                errorCarga != null -> item(key = "error") {
                    ErrorDeCarga(
                        titulo = "No pudimos traer tus listas",
                        causa = errorCarga,
                        onReintentar = onReintentar
                    )
                }
                // Mientras carga se muestra el hueco del hero, nunca un "vacío"
                cargando -> item(key = "hero_skeleton") {
                    SkeletonBox(modifier = Modifier.fillMaxWidth().height(220.dp))
                }
                listaActiva != null && !estaOnline && currentItems.isNotEmpty() -> item(key = "hero_offline") {
                    TotalEstimadoCard(
                        items = currentItems,
                        onClick = { onAbrirLista(listaActiva.id) }
                    )
                }
                listaActiva != null && comparacion != null -> item(key = "hero") {
                    HeroComparador(
                        nombreLista = listaActiva.name,
                        comparacion = comparacion,
                        onClick = { onAbrirLista(listaActiva.id) }
                    )
                }
                listaActiva != null && currentItems.isNotEmpty() -> item(key = "hero_sin_precios") {
                    TotalEstimadoCard(
                        items = currentItems,
                        onClick = { onAbrirLista(listaActiva.id) },
                        detalle = "Todavía no tenemos precios de catálogo para estos productos. " +
                            "Informalos desde el Modo Súper y aparece el comparador."
                    )
                }
            }

            item(key = "resumen") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniResumenCard(
                        titulo = "Gasto del mes",
                        valor = Formato.precio(gastoDelMes),
                        detalle = "ver mis compras",
                        onClick = onVerGastos,
                        modifier = Modifier.weight(1f)
                    )
                    MiniResumenCard(
                        titulo = "Tu aporte",
                        valor = "$aportes ${if (aportes == 1) "precio" else "precios"}",
                        detalle = "informados a la comunidad",
                        onClick = onVerPerfil,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item(key = "titulo_listas") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tus listas", style = MaterialTheme.typography.titleLarge)
                    FilledTonalIconButton(
                        onClick = onNuevaLista,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Nueva lista", modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (cargando) {
                items(2) { SkeletonBox(modifier = Modifier.fillMaxWidth().height(72.dp)) }
            } else if (lists.isEmpty()) {
                item {
                    Text(
                        "Creá tu primera lista con el botón + y Góndola te dice a qué súper conviene ir.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            items(lists, key = { it.id }) { lista ->
                TarjetaLista(
                    lista = lista,
                    // Solo la lista activa tiene sus ítems cargados: mostrar
                    // progreso de las otras sería inventarlo.
                    items = if (lista.id == activeListId) currentItems else null,
                    onClick = { onAbrirLista(lista.id) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

// Fix 6: el buscador de la portada BUSCA. Es por prefijo de la descripción
// (la misma consulta del catálogo) y hay que decirlo, no dejar que el usuario
// crea que la app no tiene el producto.
@Composable
private fun BuscadorPortada(
    buscar: suspend (String) -> List<ProductModel>,
    onAbrirProducto: (ProductModel) -> Unit,
    onVerTodos: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var resultados by remember { mutableStateOf<List<ProductModel>>(emptyList()) }
    var buscando by remember { mutableStateOf(false) }
    val teclado = LocalSoftwareKeyboardController.current
    val foco = LocalFocusManager.current

    LaunchedEffect(query) {
        val termino = query.trim()
        if (termino.length < 2) {
            resultados = emptyList()
            buscando = false
            return@LaunchedEffect
        }
        buscando = true
        delay(300) // no dispara una consulta por tecla
        resultados = buscar(termino)
        buscando = false
    }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Buscá un producto") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    buscando -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    query.isNotBlank() -> IconButton(onClick = {
                        query = ""
                        teclado?.hide()
                        foco.clearFocus()
                    }) { Icon(Icons.Default.Clear, contentDescription = "Limpiar") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = MaterialTheme.colorScheme.surface
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // Enter = "mostrame todo": el desplegable es un atajo, la grilla del
            // catálogo es donde se ve la lista completa.
            keyboardActions = KeyboardActions(onSearch = {
                val termino = query.trim()
                teclado?.hide()
                foco.clearFocus()
                if (termino.isNotEmpty()) {
                    onVerTodos(termino)
                    query = ""
                }
            })
        )

        AnimatedVisibility(
            visible = query.trim().length >= 2 && !buscando,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                if (resultados.isEmpty()) {
                    Text(
                        "No encontramos nada con “${query.trim()}”. Probá con otra palabra del nombre.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    // Scrollea: cortar en 6 escondía resultados sin avisar
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(resultados, key = { it.ean }) { producto ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        teclado?.hide()
                                        foco.clearFocus()
                                        query = ""
                                        onAbrirProducto(producto)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MiniaturaProducto(imagen = producto.imagen, size = 36.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        producto.descripcion,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (producto.marca.isNotBlank()) {
                                        Text(
                                            producto.marca,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                PrecioResumen(producto.precioCatalogo())
                            }
                        }
                        item(key = "ver_todos") {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                            Text(
                                "Ver todos los resultados en el catálogo →",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val termino = query.trim()
                                        teclado?.hide()
                                        foco.clearFocus()
                                        query = ""
                                        onVerTodos(termino)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// El corazón de Góndola: la lista cotizada en cada súper. Honestidad primero —
// solo compiten las cadenas que consiguen tantos productos como la que más
// consigue; las demás se muestran apagadas, sin barra y sin %.
@Composable
private fun HeroComparador(
    nombreLista: String,
    comparacion: ComparadorLista.Resultado,
    onClick: () -> Unit
) {
    val mejor = comparacion.cadenas.first()
    val maximoComparable = comparacion.cadenas.filter { it.comparable }.maxOfOrNull { it.total } ?: mejor.total
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "CONVIENE IR A",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        "$nombreLista · ${comparacion.itemsTotal} ítems",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    Cadenas.nombre(mejor.cadenaId),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                // El total, en el amarillo de la etiqueta de góndola
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(10.dp),
                    shadowElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            Formato.precio(mejor.total),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                        if (comparacion.ahorroVsPeor > 0.0) {
                            Text(
                                "ahorrás ${Formato.precio(comparacion.ahorroVsPeor)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiary
                            )
                        }
                    }
                }
            }

            // Cobertura de la ganadora: SIEMPRE visible
            Text(
                "tiene ${mejor.itemsConPrecio} de tus ${comparacion.itemsTotal}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            LinearProgressIndicator(
                progress = {
                    if (comparacion.itemsTotal == 0) 0f
                    else mejor.itemsConPrecio.toFloat() / comparacion.itemsTotal
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(top = 3.dp)
                    .clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {}
            )

            Spacer(modifier = Modifier.height(10.dp))
            comparacion.cadenas.forEach { cadena ->
                FilaCadena(
                    cadena = cadena,
                    maximoComparable = maximoComparable
                )
            }

            Text(
                if (comparacion.mejorCobertura < comparacion.itemsTotal) {
                    "Ninguna cadena tiene toda la lista: se compara sobre los " +
                        "${comparacion.mejorCobertura} productos que sí consigue la ganadora. " +
                        "Las cadenas a las que les faltan productos no compiten por el primer puesto ni llevan %."
                } else {
                    "Las cadenas a las que les faltan productos no compiten por el primer puesto ni llevan %."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

// Una fila por cadena: barra proporcional al total (solo si compite), total y
// el % contra la ganadora. Las que cubren menos van apagadas y con "le faltan N".
@Composable
private fun FilaCadena(
    cadena: ComparadorLista.TotalCadena,
    maximoComparable: Double
) {
    val fraccion by animateFloatAsState(
        targetValue = if (!cadena.comparable || maximoComparable <= 0.0) 0f
        else (cadena.total / maximoComparable).toFloat().coerceIn(0f, 1f),
        label = "barra_${cadena.cadenaId}"
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            Cadenas.nombre(cadena.cadenaId),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (cadena.esMejor) FontWeight.Bold else FontWeight.Medium,
            color = if (cadena.comparable) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // El nombre manda: "Cooperativa Obrera" no puede quedar cortado.
            // La barra va a ancho fijo, que además mantiene la misma escala en
            // todas las filas.
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (cadena.comparable) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraccion)
                        .clip(CircleShape)
                        .background(
                            if (cadena.esMejor) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            Formato.precio(cadena.total),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (cadena.comparable) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(modifier = Modifier.width(66.dp)) {
            when {
                // Comparar el total de una cadena a la que le faltan productos
                // contra la ganadora es hacer trampa: no lleva %.
                !cadena.comparable -> Text(
                    "le faltan ${cadena.faltantes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                cadena.esMejor -> Text(
                    "la más barata",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                else -> Text(
                    Formato.porcentaje(cadena.difPorcentaje),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// Sin conexión (o sin precios de catálogo) no hay comparación posible: se dice
// que el número es estimado, en vez de disfrazarlo de recomendación.
@Composable
private fun TotalEstimadoCard(
    items: List<SharedListItemModel>,
    onClick: () -> Unit,
    detalle: String = "Sin conexión no podemos comparar súperes. Este total sale de los últimos precios guardados."
) {
    val total = items.sumOf { it.expectedPrice * it.targetQuantity }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "TOTAL ESTIMADO",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                Formato.precio(total),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                detalle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun MiniResumenCard(
    titulo: String,
    valor: String,
    detalle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                titulo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(valor, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                detalle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TarjetaLista(
    lista: SharedListModel,
    items: List<SharedListItemModel>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colaborativa = lista.members.size > 1
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (colaborativa) Icons.Default.Share else Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                tint = if (colaborativa) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(lista.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (colaborativa) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            if (colaborativa) "Colaborativa · ${lista.members.size}" else "Personal",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = if (colaborativa) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (items != null && items.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${items.count { it.scanned }} de ${items.size} en el changuito",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------- Detalle de lista ---

@Composable
private fun DetalleDeLista(
    lista: SharedListModel?,
    items: List<SharedListItemModel>,
    comparacion: ComparadorLista.Resultado?,
    estaOnline: Boolean,
    onVolver: () -> Unit,
    onModoSuper: () -> Unit,
    onInvitar: () -> Unit,
    onVaciar: () -> Unit,
    onEliminarLista: () -> Unit,
    onItemToggled: (String, Boolean) -> Unit,
    onItemDeleted: (String) -> Unit,
    onEditarCantidad: (SharedListItemModel) -> Unit
) {
    var menuAbierto by remember { mutableStateOf(false) }
    val pendientes = items.filterNot { it.scanned }
    val enChanguito = items.filter { it.scanned }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onVolver) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    lista?.name ?: "Lista",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val otros = lista?.members?.size?.minus(1) ?: 0
                Text(
                    buildString {
                        if (otros > 0) append("con $otros ${if (otros == 1) "persona más" else "personas más"} · ")
                        append("${items.size} ${if (items.size == 1) "producto" else "productos"}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onInvitar) {
                Icon(Icons.Default.PersonAdd, contentDescription = "Invitar", tint = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menuAbierto = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Más opciones")
                }
                DropdownMenu(expanded = menuAbierto, onDismissRequest = { menuAbierto = false }) {
                    DropdownMenuItem(
                        text = { Text("Vaciar la lista") },
                        onClick = {
                            menuAbierto = false
                            onVaciar()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Eliminar la lista", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuAbierto = false
                            onEliminarLista()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Comparador compacto + la puerta al Modo Súper
        if (comparacion != null && estaOnline) {
            ComparadorCompacto(comparacion = comparacion, onModoSuper = onModoSuper)
        } else if (items.isNotEmpty()) {
            Column {
                TotalEstimadoCard(items = items, onClick = {})
                Spacer(modifier = Modifier.height(8.dp))
                BotonModoSuper(onModoSuper)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (items.isEmpty()) {
            Text(
                "La lista está vacía. Agregá productos desde el Catálogo o escaneándolos en el súper.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                pendientes.groupBy { it.category }.forEach { (categoria, delGrupo) ->
                    item(key = "header_$categoria") {
                        Text(
                            text = categoria.ifBlank { "Otros" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp)
                        )
                    }
                    items(delGrupo, key = { it.id }) { item ->
                        FilaItem(
                            item = item,
                            onToggle = { onItemToggled(item.id, it) },
                            onEditarCantidad = { onEditarCantidad(item) },
                            onEliminar = { onItemDeleted(item.id) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                // Lo tildado se va al fondo: lo que importa es lo que falta
                if (enChanguito.isNotEmpty()) {
                    item(key = "header_changuito") {
                        Text(
                            "EN EL CHANGUITO (${enChanguito.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }
                    items(enChanguito, key = { it.id }) { item ->
                        FilaItem(
                            item = item,
                            onToggle = { onItemToggled(item.id, it) },
                            onEditarCantidad = { onEditarCantidad(item) },
                            onEliminar = { onItemDeleted(item.id) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparadorCompacto(
    comparacion: ComparadorLista.Resultado,
    onModoSuper: () -> Unit
) {
    val mejor = comparacion.cadenas.first()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CONVIENE IR A",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(Cadenas.nombre(mejor.cadenaId), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "tiene ${mejor.itemsConPrecio} de tus ${comparacion.itemsTotal}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        Formato.precio(mejor.total),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            BotonModoSuper(onModoSuper)
        }
    }
}

@Composable
private fun BotonModoSuper(onModoSuper: () -> Unit) {
    Button(
        onClick = onModoSuper,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(999.dp)
    ) {
        Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Modo Súper", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FilaItem(
    item: SharedListItemModel,
    onToggle: (Boolean) -> Unit,
    onEditarCantidad: () -> Unit,
    onEliminar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val fondo by animateColorAsState(
        targetValue = if (item.scanned) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
        label = "fondo_item"
    )
    Surface(
        color = fondo,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle(!item.scanned)
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CheckboxChanguito(
                marcado = item.scanned,
                onCheck = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(it)
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.productName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (item.scanned) FontWeight.Normal else FontWeight.SemiBold,
                        textDecoration = if (item.scanned) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = if (item.scanned) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.expectedPrice <= 0.0) {
                    // "Sin precio" es un estado de primera clase, con salida
                    Text(
                        "sin precio todavía · informalo en el súper",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (item.targetQuantity != 1.0) {
                    Text(
                        "${Formato.cantidad(item.targetQuantity)} × ${Formato.precio(item.expectedPrice)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                Formato.cantidad(item.targetQuantity),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onEditarCantidad() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            if (item.expectedPrice > 0.0) {
                Text(
                    Formato.precio(item.expectedPrice * item.targetQuantity),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (item.scanned) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onEliminar, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Sacar de la lista",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// Checkbox del changuito: 22dp, radio 6. El de Material es redondo y chico para
// el pulgar de alguien que está empujando un carrito.
@Composable
private fun CheckboxChanguito(marcado: Boolean, onCheck: (Boolean) -> Unit) {
    val forma = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable { onCheck(!marcado) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(forma)
                .background(if (marcado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                .border(
                    width = 2.dp,
                    color = if (marcado) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = forma
                ),
            contentAlignment = Alignment.Center
        ) {
            if (marcado) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

// Diálogo de un solo campo (crear lista, invitar, cantidad). No es destructivo,
// pero pide un dato puntual y vuelve: el diálogo es la forma correcta acá.
@Composable
private fun DialogoTexto(
    titulo: String,
    etiqueta: String,
    textoBoton: String,
    onConfirmar: (String) -> Unit,
    onDismiss: () -> Unit,
    detalle: String? = null,
    valorInicial: String = "",
    soloNumeros: Boolean = false
) {
    var texto by remember { mutableStateOf(valorInicial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(titulo) },
        text = {
            Column {
                if (detalle != null) {
                    Text(detalle, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = texto,
                    onValueChange = {
                        texto = if (soloNumeros) it.filter { c -> c.isDigit() || c == '.' } else it
                    },
                    label = { Text(etiqueta) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = if (soloNumeros) {
                        KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    } else {
                        KeyboardOptions.Default
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = texto.isNotBlank(),
                onClick = {
                    onConfirmar(texto)
                    onDismiss()
                }
            ) { Text(textoBoton) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
