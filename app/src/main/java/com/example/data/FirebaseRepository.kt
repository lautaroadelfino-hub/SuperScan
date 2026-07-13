package com.example.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    val category: String = "",
    val source: String = ""
)

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
        val uid = auth.currentUser?.uid ?: return@callbackFlow
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
        if (query.isBlank()) return emptyList()
        return try {
            val snapshot = db.collection("productos")
                .whereGreaterThanOrEqualTo("descripcion", query)
                .whereLessThanOrEqualTo("descripcion", query + "\uf8ff")
                .limit(20)
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                val p = doc.toObject(ProductModel::class.java)
                p?.copy(ean = doc.id)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    suspend fun searchProductByEan(ean: String): ProductModel? {
        return try {
            val doc = db.collection("productos").document(ean).get().await()
            if (doc.exists()) {
                val p = doc.toObject(ProductModel::class.java)
                return p?.copy(ean = doc.id, source = "oficial")
            }
            val userDoc = db.collection("productos_usuarios").document(ean).get().await()
            if (userDoc.exists()) {
                val p = userDoc.toObject(ProductModel::class.java)
                return p?.copy(ean = userDoc.id, source = "usuario")
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveUserProduct(ean: String, descripcion: String, presentacion: String) {
        val prod = ProductModel(ean = ean, descripcion = "$descripcion $presentacion".trim(), source = "usuario")
        db.collection("productos_usuarios").document(ean).set(prod).await()
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
        val email = auth.currentUser?.email ?: return@callbackFlow
        
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
            category = product.category,
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

    suspend fun saveUserZipCode(zipCode: String) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("usuarios").document(uid).set(mapOf("zipCode" to zipCode), com.google.firebase.firestore.SetOptions.merge()).await()
    }

    fun getUserZipCode(): Flow<String> = callbackFlow {
        val uid = auth.currentUser?.uid ?: return@callbackFlow
        val subscription = db.collection("usuarios").document(uid).addSnapshotListener { snapshot, _ ->
            val zip = snapshot?.getString("zipCode") ?: ""
            trySend(zip)
        }
        awaitClose { subscription.remove() }
    }

    fun getObservationsCount(): Flow<Int> = callbackFlow {
        val uid = auth.currentUser?.uid ?: return@callbackFlow
        val subscription = db.collection("observaciones_precios")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.size() ?: 0)
            }
        awaitClose { subscription.remove() }
    }

    suspend fun deleteUserAccount() {
        val user = auth.currentUser ?: return
        val uid = user.uid

        // Delete user's tickets
        val tickets = db.collection("tickets").whereEqualTo("userId", uid).get().await()
        for (doc in tickets.documents) {
            doc.reference.delete().await()
        }

        // Delete user's observations
        val obs = db.collection("observaciones_precios").whereEqualTo("userId", uid).get().await()
        for (doc in obs.documents) {
            doc.reference.delete().await()
        }
        
        // Delete user doc
        db.collection("usuarios").document(uid).delete().await()

        // Delete Auth account
        user.delete().await()
    }
}
