package com.example.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

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

data class PriceObservationModel(
    val id: String = "",
    val userId: String = "",
    val barcode: String? = null,
    val productName: String = "",
    val storeName: String = "",
    val price: Double = 0.0,
    val date: String = "",
    val source: String = "ticket"
)

data class ProductModel(
    val ean: String = "",
    val descripcion: String = "",
    val precio: Double = 0.0,
    val precio_referencia: Double = 0.0
) {
    // El dataset trae el valor a veces en "precio" y a veces solo en "precio_referencia"
    fun effectivePrice(): Double = if (precio > 0.0) precio else precio_referencia
}

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

class FirebaseRepository {
    private val db = FirebaseFirestore.getInstance().apply {
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
    }
    private val auth = FirebaseAuth.getInstance()

    companion object {
        // Normaliza cualquier código escaneado (UPC-A, EAN-8, etc.) a GTIN-13,
        // que es el formato de los IDs de documento en "productos".
        fun normalizeToGtin13(barcode: String): String {
            val digitsOnly = barcode.filter { it.isDigit() }
            return digitsOnly.padStart(13, '0')
        }
    }

    // Firestore limita los WriteBatch a 500 operaciones
    private suspend fun deleteInBatches(docs: List<DocumentSnapshot>) {
        docs.chunked(450).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    // ... Receipts and Observations ...
    
    suspend fun saveTicket(ticket: TicketModel) {
        val uid = auth.currentUser?.uid ?: return
        val ticketRef = db.collection("tickets").document()
        val finalTicket = ticket.copy(id = ticketRef.id, userId = uid)
        ticketRef.set(finalTicket).await()
        
        // Create observations
        finalTicket.items.forEach { item ->
            val obsRef = db.collection("observaciones_precios").document()
            val obs = PriceObservationModel(
                id = obsRef.id,
                userId = uid,
                barcode = item.barcode,
                productName = item.productName,
                storeName = finalTicket.storeName,
                price = item.unitPrice,
                date = finalTicket.date,
                source = "ticket"
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

    suspend fun searchProductsByDescription(query: String): List<ProductModel> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        // Firestore solo permite b\u00fasqueda por prefijo y es sensible a may\u00fasculas,
        // as\u00ed que se consulta la query en sus variantes de capitalizaci\u00f3n m\u00e1s
        // probables (el dataset SEPA suele estar en MAY\u00daSCULAS).
        val locale = java.util.Locale.getDefault()
        val variants = linkedSetOf(
            q,
            q.uppercase(locale),
            q.lowercase(locale),
            q.lowercase(locale).replaceFirstChar { it.titlecase(locale) }
        )

        val results = mutableListOf<ProductModel>()
        for (variant in variants) {
            val snapshot = db.collection("productos")
                .whereGreaterThanOrEqualTo("descripcion", variant)
                .whereLessThanOrEqualTo("descripcion", variant + "\uf8ff")
                .limit(20)
                .get()
                .await()

            snapshot.documents.forEach { doc ->
                val p = doc.toObject(ProductModel::class.java)?.copy(ean = doc.id)
                if (p != null && results.none { it.ean == p.ean }) {
                    results.add(p)
                }
            }
        }
        return results
    }
    
    suspend fun searchProductByEan(ean: String): ProductModel? {
        val normalizedEan = normalizeToGtin13(ean)
        return try {
            val doc = db.collection("productos").document(normalizedEan).get().await()
            if (doc.exists()) {
                val p = doc.toObject(ProductModel::class.java)
                return p?.copy(ean = doc.id)
            }
            val userDoc = db.collection("productos_usuarios").document(normalizedEan).get().await()
            if (userDoc.exists()) {
                val p = userDoc.toObject(ProductModel::class.java)
                return p?.copy(ean = userDoc.id)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveUserProduct(ean: String, descripcion: String, presentacion: String) {
        val normalizedEan = normalizeToGtin13(ean)
        val prod = ProductModel(ean = normalizedEan, descripcion = "$descripcion $presentacion".trim())
        db.collection("productos_usuarios").document(normalizedEan).set(prod).await()
    }

    suspend fun saveGondolaObservation(barcode: String, productName: String, price: Double) {
        val uid = auth.currentUser?.uid ?: return
        val obsRef = db.collection("observaciones_precios").document()
        val obs = PriceObservationModel(
            id = obsRef.id,
            userId = uid,
            barcode = barcode,
            productName = productName,
            storeName = "Modo Súper", // or prompt for supermarket
            price = price,
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()),
            source = "gondola"
        )
        obsRef.set(obs).await()
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
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        
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
    
    suspend fun addProductToList(listId: String, product: ProductEntity, quantity: Double) {
        val itemRef = db.collection("shared_lists").document(listId).collection("items").document()
        val item = SharedListItemModel(
            id = itemRef.id,
            barcode = product.barcode,
            productName = product.productName,
            category = product.category,
            targetQuantity = quantity,
            scannedQuantity = 0.0,
            expectedPrice = product.lastPrice,
            scanned = false
        )
        itemRef.set(item).await()
    }

    suspend fun addProductToList(listId: String, product: ProductModel, quantity: Double) {
        val itemRef = db.collection("shared_lists").document(listId).collection("items").document()
        val item = SharedListItemModel(
            id = itemRef.id,
            barcode = product.ean,
            productName = product.descripcion,
            category = "Varios",
            targetQuantity = quantity,
            scannedQuantity = 0.0,
            expectedPrice = 0.0,
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
                .whereEqualTo("userId", uid)
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
        val obs = db.collection("observaciones_precios").whereEqualTo("userId", uid).get().await()
        deleteInBatches(obs.documents)


        // Delete user doc
        db.collection("usuarios").document(uid).delete().await()

        // Delete Auth account
        user.delete().await()
    }
}
