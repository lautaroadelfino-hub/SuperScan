package com.example.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import java.util.Locale

data class TicketModel(
    val id: String = "",
    val userId: String = "",
    val storeName: String = "",
    val date: String = "",
    val totalAmount: Double = 0.0,
    val items: List<TicketItemModel> = emptyList()
)

data class TicketItemModel(
    val productName: String = "",
    val category: String = "Varios",
    val quantity: Double = 0.0,
    val unitPrice: Double = 0.0,
    val totalPrice: Double = 0.0,
    val barcode: String? = null
)

// Refleja la estructura real de observaciones_precios:
// { ean, descripcionCruda, comercio, cadena, precio, fecha, fuente, uid }
data class ObservacionPrecio(
    val id: String = "",
    val ean: String = "",
    val descripcionCruda: String = "",
    /** Nombre legible del comercio, para mostrar */
    val comercio: String = "",
    /** Id de cadena (clave del map `precios`), para agregar del lado del pipeline */
    val cadena: String = "",
    val precio: Double = 0.0,
    val fecha: String = "",        // YYYY-MM-DD
    val fuente: String = "ticket", // "ticket" | "gondola"
    val uid: String = ""
)

// ProductModel, DisplayPrice y el resto del dominio del catálogo están en Catalogo.kt

data class SharedListModel(
    val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val members: List<String> = emptyList(),
    val date: String = "",
    val isCompleted: Boolean = false
)

data class SharedListItemModel(
    val id: String = "",
    val barcode: String? = null,
    val productName: String = "",
    val category: String = "",
    val targetQuantity: Double = 0.0,
    val scannedQuantity: Double = 0.0,
    val expectedPrice: Double = 0.0,
    val scanned: Boolean = false
)

// Resultado de la búsqueda por código de barras (Modo Súper / Catálogo)
sealed interface EanLookupResult {
    data class Found(val producto: ProductModel) : EanLookupResult
    /** No está ni en productos ni en productos_usuarios; ean ya normalizado para el alta manual */
    data class NotFound(val ean: String) : EanLookupResult
    data class InvalidEan(val raw: String) : EanLookupResult
    /** Sin conexión y el documento no está en la caché local */
    data class Offline(val ean: String) : EanLookupResult
    data class Failure(val mensaje: String) : EanLookupResult
}

class FirebaseRepository {
    private val db = FirebaseFirestore.getInstance().apply {
        // Persistencia offline: las lecturas directas por ID y las queries
        // resuelven desde caché cuando no hay conexión.
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
    }
    private val auth = FirebaseAuth.getInstance()

    // Firestore limita los WriteBatch a 500 operaciones
    private suspend fun deleteInBatches(docs: List<DocumentSnapshot>) {
        docs.chunked(450).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private fun DocumentSnapshot.toProductModel(): ProductModel? =
        toObject(ProductModel::class.java)?.copy(ean = id)

    // ... Receipts and Observations ...

    suspend fun saveTicket(ticket: TicketModel) {
        val uid = auth.currentUser?.uid ?: return
        val ticketRef = db.collection("tickets").document()
        val finalTicket = ticket.copy(id = ticketRef.id, userId = uid)
        ticketRef.set(finalTicket).await()

        // Crea observaciones de precio solo para ítems con código de barras
        // válido: las consultas de precio e historial se hacen por ean.
        finalTicket.items.forEach { item ->
            val ean = item.barcode?.let { Ean.normalizar(it) } ?: return@forEach
            val obsRef = db.collection("observaciones_precios").document()
            val obs = ObservacionPrecio(
                id = obsRef.id,
                ean = ean,
                descripcionCruda = item.productName,
                comercio = finalTicket.storeName,
                precio = item.unitPrice,
                fecha = finalTicket.date,
                fuente = "ticket",
                uid = uid
            )
            obsRef.set(obs).await()
        }
    }

    fun getTickets(): Flow<List<TicketModel>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val subscription = db.collection("tickets")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val tickets = snapshot.documents.mapNotNull { it.toObject(TicketModel::class.java) }
                    trySend(tickets)
                }
            }
        awaitClose { subscription.remove() }
    }

    suspend fun deleteTicket(ticketId: String) {
        db.collection("tickets").document(ticketId).delete().await()
    }

    // --- CONSULTA 1: búsqueda por código de barras ---
    // Lectura DIRECTA de productos/{ean13} (nunca una query); si no existe,
    // productos_usuarios/{ean13}. Funciona offline si el documento está en caché.
    suspend fun lookupProductByEan(rawBarcode: String): EanLookupResult {
        val ean = Ean.normalizar(rawBarcode)
            ?: return EanLookupResult.InvalidEan(rawBarcode)
        return try {
            val doc = db.collection("productos").document(ean).get().await()
            if (doc.exists()) {
                doc.toProductModel()?.let { return EanLookupResult.Found(it) }
            }
            val userDoc = db.collection("productos_usuarios").document(ean).get().await()
            if (userDoc.exists()) {
                userDoc.toProductModel()?.let {
                    return EanLookupResult.Found(it.copy(fuente = "usuario"))
                }
            }
            EanLookupResult.NotFound(ean)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                EanLookupResult.Offline(ean)
            } else {
                EanLookupResult.Failure(e.message ?: "Error de Firestore")
            }
        } catch (e: Exception) {
            EanLookupResult.Failure(e.message ?: "Error inesperado")
        }
    }

    // --- CONSULTA 2: búsqueda por texto (prefijo) ---
    // El catálogo está en MAYÚSCULAS y Firestore solo busca prefijos:
    // orderBy(descripcion) + startAt/endAt sobre ambas colecciones,
    // uniendo sin duplicar por ean. Puede lanzar: el ViewModel captura.
    suspend fun searchProductsByDescription(query: String): List<ProductModel> {
        val q = Busqueda.normalizar(query).trim()
        if (q.isEmpty()) return emptyList()

        val resultados = LinkedHashMap<String, ProductModel>()

        // --- Pasada 1: por PREFIJO de la descripci\u00f3n ---
        // Es lo que uno espera al escribir el comienzo del nombre.
        for (coleccion in listOf("productos", "productos_usuarios")) {
            val snapshot = soloVisibles(coleccion)
                .orderBy("descripcion")
                .startAt(q)
                .endAt(q + "\uf8ff")
                .limit(20)
                .get()
                .await()

            snapshot.documents.forEach { doc ->
                val p = doc.toProductModel()
                if (p != null && !resultados.containsKey(p.ean)) {
                    resultados[p.ean] = p
                }
            }
        }

        // --- Pasada 2: por PALABRA sobre el array `tokens` ---
        // Encuentra "SIN GLUTEN" o "SIN TACC" aunque est\u00e9n en el medio o al
        // final del nombre, que es donde suelen estar. Los documentos que
        // todav\u00eda no tienen `tokens` (falta correr el pipeline) simplemente no
        // aparecen ac\u00e1: la b\u00fasqueda por prefijo sigue funcionando igual.
        val palabras = Busqueda.palabrasDeConsulta(q)
        if (palabras.isNotEmpty()) {
            // Firestore admite UN solo array-contains por consulta: se manda la
            // palabra m\u00e1s discriminante y el resto se filtra en el tel\u00e9fono.
            val principal = palabras.first()
            val resto = palabras.drop(1)
            for (coleccion in listOf("productos", "productos_usuarios")) {
                try {
                    val snapshot = soloVisibles(coleccion)
                        .whereArrayContains("tokens", principal)
                        .limit(40)
                        .get()
                        .await()

                    snapshot.documents
                        .mapNotNull { it.toProductModel() }
                        .filter { producto -> resto.all { it in producto.tokens } }
                        .forEach { producto ->
                            if (!resultados.containsKey(producto.ean)) {
                                resultados[producto.ean] = producto
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Los minutos que Firestore tarda en construir el índice
                    // nuevo, esta consulta falla. No puede llevarse puesta la
                    // búsqueda por prefijo, que ya trajo resultados válidos.
                    continue
                }
            }
        }
        return resultados.values.toList()
    }

    // El cat\u00e1logo global trae productos de todo el pa\u00eds y altas de Open Food
    // Facts que no se consiguen en Tandil: se filtran para no ensuciar la
    // b\u00fasqueda. productos_usuarios no lleva el flag.
    private fun soloVisibles(coleccion: String): Query =
        db.collection(coleccion).let {
            if (coleccion == "productos") it.whereEqualTo("en_tandil", true) else it
        }

    // --- CONSULTA 3: precio a mostrar ---
    // Prioridad: 1) observación más reciente del propio usuario,
    // 2) precio_publico, 3) precio_min (ver ProductModel.precioCatalogo).
    // Requiere el índice compuesto (ean, uid, fecha desc) de firestore.indexes.json.
    // Si el caller ya tiene el ProductModel, pasarlo evita releer el documento.
    suspend fun getDisplayPrice(ean: String, producto: ProductModel? = null): DisplayPrice {
        return try {
            val uid = auth.currentUser?.uid
            if (uid != null) {
                val obs = db.collection("observaciones_precios")
                    .whereEqualTo("ean", ean)
                    .whereEqualTo("uid", uid)
                    .orderBy("fecha", Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .await()
                    .documents.firstOrNull()
                    ?.toObject(ObservacionPrecio::class.java)
                if (obs != null) {
                    return DisplayPrice.UserObservation(obs.precio, obs.comercio, obs.fecha)
                }
            }

            val p = producto
                ?: db.collection("productos").document(ean).get().await().toProductModel()
                ?: db.collection("productos_usuarios").document(ean).get().await().toProductModel()

            p?.precioCatalogo() ?: DisplayPrice.None
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                DisplayPrice.Failure("Sin conexión y sin datos en caché para este producto.")
            } else {
                DisplayPrice.Failure(e.message ?: "Error de Firestore")
            }
        } catch (e: Exception) {
            DisplayPrice.Failure(e.message ?: "Error inesperado")
        }
    }

    // --- CONSULTA 4: historial de precios de un producto ---
    // Requiere el índice compuesto (ean, fecha asc) de firestore.indexes.json.
    // Si el índice no existe, Firestore lanza FAILED_PRECONDITION con el link
    // de creación en el mensaje; el ViewModel lo captura y lo muestra.
    suspend fun getPriceHistory(ean: String): List<ObservacionPrecio> {
        return db.collection("observaciones_precios")
            .whereEqualTo("ean", ean)
            .orderBy("fecha", Query.Direction.ASCENDING)
            .get()
            .await()
            .documents.mapNotNull { doc ->
                doc.toObject(ObservacionPrecio::class.java)?.copy(id = doc.id)
            }
    }

    // --- CONSULTA 5: estructura del catálogo (grilla de categorías) ---
    // Doc único catalogo_meta/estructura generado por el pipeline de carga:
    // categorías, subcategorías y marcas por subcategoría. Devuelve null si
    // todavía no fue generado. Queda en la caché offline tras la primera lectura.
    // catalogo_meta/precios: de cuándo son los precios del catálogo (la fecha
    // del dato de SEPA, no la de la subida). Un precio sin fecha no se puede
    // juzgar: la app lo muestra arriba de todo. Lo escribe actualizar_precios.py.
    suspend fun getEstadoPrecios(): EstadoPrecios? =
        db.collection("catalogo_meta").document("precios").get().await()
            .toObject(EstadoPrecios::class.java)

    suspend fun getCatalogoEstructura(): CatalogoEstructura? =
        db.collection("catalogo_meta").document("estructura").get().await()
            .toObject(CatalogoEstructura::class.java)

    // --- CONSULTA 6: productos de una subcategoría, paginados de a 20 ---
    // Índices compuestos requeridos en firestore.indexes.json:
    //   (categoria, subcategoria, descripcion) y (categoria, subcategoria, precio_min),
    //   más las variantes con marca cuando hay filtro.
    // Al ordenar por precio se filtra precio_min > 0: en Firestore los null
    // ordenan antes que los números y los ~35k productos sin precio taparían
    // la lista. Esos productos solo aparecen en el orden alfabético.
    fun getProductosPaginados(filtro: FiltroProductos): Flow<PagingData<ProductModel>> {
        var q: Query = db.collection("productos")
            // Solo lo que se consigue en Tandil (ver en_tandil en el pipeline)
            .whereEqualTo("en_tandil", true)
            .whereEqualTo("categoria", filtro.categoria)
            .whereEqualTo("subcategoria", filtro.subcategoria)
        filtro.marca?.let { q = q.whereEqualTo("marca", it) }
        q = when (filtro.orden) {
            OrdenCatalogo.ALFABETICO -> q.orderBy("descripcion")
            OrdenCatalogo.PRECIO_ASC -> q.whereGreaterThan("precio_min", 0)
                .orderBy("precio_min")
            OrdenCatalogo.PRECIO_DESC -> q.whereGreaterThan("precio_min", 0)
                .orderBy("precio_min", Query.Direction.DESCENDING)
        }
        val query = q
        return Pager(
            PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false)
        ) { ProductosPagingSource(query) }.flow
    }

    // --- CONSULTA 7: productos por lote de EANs (comparador de lista) ---
    // whereIn sobre el ID del documento en tandas de 10 (límite seguro en
    // cualquier versión del SDK). Las listas tienen decenas de ítems: 1-3 queries.
    suspend fun getProductosPorEans(eans: List<String>): Map<String, ProductModel> {
        val resultado = HashMap<String, ProductModel>()
        eans.distinct().chunked(10).forEach { tanda ->
            db.collection("productos")
                .whereIn(com.google.firebase.firestore.FieldPath.documentId(), tanda)
                .get()
                .await()
                .documents.forEach { doc ->
                    doc.toProductModel()?.let { resultado[it.ean] = it }
                }
        }
        return resultado
    }

    // El alta manual escribe SIEMPRE en productos_usuarios: las reglas de
    // seguridad bloquean toda escritura en productos desde la app.
    // Descripción en MAYÚSCULAS para que la búsqueda por prefijo la encuentre.
    suspend fun saveUserProduct(ean: String, descripcion: String, presentacion: String): ProductModel {
        val normalizedEan = Ean.normalizar(ean)
            ?: throw IllegalArgumentException("Código de barras inválido: $ean")
        val nombre = "$descripcion $presentacion".trim().uppercase(Locale.ROOT)
        val prod = ProductModel(
            ean = normalizedEan,
            descripcion = nombre,
            // Sin tokens el alta no aparecería en la búsqueda por palabra
            tokens = Busqueda.tokenizar(nombre),
            fuente = "usuario"
        )
        db.collection("productos_usuarios").document(normalizedEan).set(prod).await()
        return prod
    }

    // El precio de góndola guarda SIEMPRE en qué cadena se vio: un precio sin
    // súper no se puede comparar con nada. La cadena la elige el usuario al
    // entrar al Modo Súper.
    suspend fun saveGondolaObservation(
        barcode: String,
        productName: String,
        price: Double,
        cadenaId: String
    ) {
        val uid = auth.currentUser?.uid ?: return
        val ean = Ean.normalizar(barcode)
            ?: throw IllegalArgumentException("Código de barras inválido: $barcode")
        val obsRef = db.collection("observaciones_precios").document()
        val obs = ObservacionPrecio(
            id = obsRef.id,
            ean = ean,
            descripcionCruda = productName,
            comercio = Cadenas.nombre(cadenaId),
            cadena = cadenaId,
            precio = price,
            fecha = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()),
            fuente = "gondola",
            uid = uid
        )
        obsRef.set(obs).await()
    }

    // --- CONSULTA 8: qué cadenas existen (para "¿en qué súper estás?") ---
    // Firestore no tiene "distinct". Se toma una muestra de productos con precio
    // y se unen las claves de sus maps `precios`: cuando el pipeline sume una
    // cadena nueva, aparece sola en la app sin tocar el código. Ordenar por
    // precio_min descendente garantiza filas con precios y no necesita índice
    // compuesto. La lectura queda en la caché offline.
    suspend fun cadenasConocidas(): List<String> {
        val muestra = db.collection("productos")
            .orderBy("precio_min", Query.Direction.DESCENDING)
            .limit(40)
            .get()
            .await()
        val ids = muestra.documents
            .mapNotNull { it.toProductModel() }
            .flatMap { producto -> producto.precios.filterValues { it > 0.0 }.keys }
            .toMutableSet()
        // Sin red y sin caché todavía: el diccionario de nombres es el último recurso
        if (ids.isEmpty()) ids += Cadenas.conocidas()
        return ids.sortedBy { Cadenas.nombre(it) }
    }

    // ... Shared Lists ...
    fun getSharedLists(): Flow<List<SharedListModel>> = callbackFlow {
        val email = auth.currentUser?.email
        if (email == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val subscription = db.collection("shared_lists")
            .whereArrayContains("members", email)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val lists = snapshot.documents.mapNotNull { it.toObject(SharedListModel::class.java) }
                    trySend(lists)
                }
            }

        awaitClose { subscription.remove() }
    }

    fun getSharedListItems(listId: String): Flow<List<SharedListItemModel>> = callbackFlow {
        val subscription = db.collection("shared_lists").document(listId).collection("items")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val items = snapshot.documents.mapNotNull { it.toObject(SharedListItemModel::class.java) }
                    trySend(items)
                }
            }
        awaitClose { subscription.remove() }
    }

    suspend fun createSharedList(name: String) {
        val uid = auth.currentUser?.uid ?: return
        val email = auth.currentUser?.email ?: return
        val listRef = db.collection("shared_lists").document()
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())

        val list = SharedListModel(
            id = listRef.id,
            name = name,
            ownerId = uid,
            members = listOf(email),
            date = dateStr,
            isCompleted = false
        )
        listRef.set(list).await()
    }

    suspend fun addMemberToList(listId: String, emailToAdd: String) {
        val listRef = db.collection("shared_lists").document(listId)
        val snapshot = listRef.get().await()
        val list = snapshot.toObject(SharedListModel::class.java) ?: return

        if (!list.members.contains(emailToAdd)) {
            val newMembers = list.members + emailToAdd
            listRef.update("members", newMembers).await()
        }
    }

    suspend fun addProductToList(listId: String, product: ProductModel, quantity: Double) {
        val itemRef = db.collection("shared_lists").document(listId).collection("items").document()
        val item = SharedListItemModel(
            id = itemRef.id,
            barcode = product.ean,
            productName = product.descripcion,
            category = product.categoria.ifBlank { "Varios" },
            targetQuantity = quantity,
            scannedQuantity = 0.0,
            expectedPrice = product.precioEstimado() ?: 0.0,
            scanned = false
        )
        itemRef.set(item).await()
    }

    suspend fun toggleItemScanned(listId: String, itemId: String, scanned: Boolean) {
        val itemRef = db.collection("shared_lists").document(listId).collection("items").document(itemId)
        itemRef.update("scanned", scanned).await()
    }

    suspend fun updateItemQuantity(listId: String, itemId: String, quantity: Double) {
        val itemRef = db.collection("shared_lists").document(listId).collection("items").document(itemId)
        itemRef.update("targetQuantity", quantity).await()
    }

    suspend fun removeItemFromList(listId: String, itemId: String) {
        db.collection("shared_lists").document(listId).collection("items").document(itemId).delete().await()
    }

    suspend fun clearListItems(listId: String) {
        val items = db.collection("shared_lists").document(listId).collection("items").get().await()
        deleteInBatches(items.documents)
    }

    suspend fun deleteSharedList(listId: String) {
        // First clear items
        clearListItems(listId)
        // Then delete the list itself
        db.collection("shared_lists").document(listId).delete().await()
    }

    suspend fun saveUserCity(city: String) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("usuarios").document(uid).set(mapOf("city" to city), com.google.firebase.firestore.SetOptions.merge()).await()
    }

    fun getUserCity(): Flow<String> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend("")
            close()
            return@callbackFlow
        }
        val subscription = db.collection("usuarios").document(uid).addSnapshotListener { snapshot, _ ->
            val city = snapshot?.getString("city") ?: ""
            trySend(city)
        }
        awaitClose { subscription.remove() }
    }

    suspend fun saveUserName(name: String) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("usuarios").document(uid).set(mapOf("name" to name), com.google.firebase.firestore.SetOptions.merge()).await()
    }

    fun getUserName(): Flow<String> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend("")
            close()
            return@callbackFlow
        }
        val subscription = db.collection("usuarios").document(uid).addSnapshotListener { snapshot, _ ->
            val name = snapshot?.getString("name") ?: ""
            trySend(name)
        }
        awaitClose { subscription.remove() }
    }

    fun getObservationsCount(): Flow<Int> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(0)
            return@flow
        }
        // Agregación count(): evita descargar todos los documentos solo para contarlos
        val count = try {
            db.collection("observaciones_precios")
                .whereEqualTo("uid", uid)
                .count()
                .get(AggregateSource.SERVER)
                .await()
                .count.toInt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
        emit(count)
    }

    suspend fun deleteUserAccount() {
        val user = auth.currentUser ?: return
        val uid = user.uid

        // Delete user's tickets
        val tickets = db.collection("tickets").whereEqualTo("userId", uid).get().await()
        deleteInBatches(tickets.documents)

        // Delete user's observations
        val obs = db.collection("observaciones_precios").whereEqualTo("uid", uid).get().await()
        deleteInBatches(obs.documents)

        // Delete user doc
        db.collection("usuarios").document(uid).delete().await()

        // Delete Auth account
        user.delete().await()
    }
}
