package com.example.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class LocalRepository(private val database: AppDatabase) {
    val json = Json { ignoreUnknownKeys = true }
    
    // --- RECEIPTS ---
    
    fun getReceipts(): Flow<List<ReceiptEntity>> {
        return database.receiptDao().getAllReceipts()
    }
    
    suspend fun saveReceipt(receipt: ReceiptEntity) {
        database.receiptDao().insertReceipt(receipt)
    }
    
    suspend fun deleteReceipt(id: String) {
        database.receiptDao().deleteReceipt(id)
    }
    
    // --- PRODUCTS ---
    
    fun getProducts(): Flow<List<ProductEntity>> {
        return database.productDao().getAllProducts()
    }
    
    suspend fun getProduct(barcode: String): ProductEntity? {
        return database.productDao().getProductByBarcode(barcode)
    }
    
    suspend fun saveProduct(product: ProductEntity) {
        database.productDao().insertProduct(product)
    }
    
    // --- SHOPPING LISTS ---
    
    fun getShoppingLists(): Flow<List<ShoppingListEntity>> {
        return database.shoppingListDao().getAllShoppingLists()
    }
    
    fun getShoppingList(id: String): Flow<ShoppingListEntity?> {
        return database.shoppingListDao().getShoppingList(id)
    }
    
    suspend fun saveShoppingList(list: ShoppingListEntity) {
        database.shoppingListDao().insertShoppingList(list)
    }
    
    suspend fun deleteShoppingList(id: String) {
        database.shoppingListDao().deleteShoppingList(id)
    }
    
    suspend fun toggleShoppingItem(listId: String, itemBarcode: String?, itemName: String, isChecked: Boolean) {
        val list = database.shoppingListDao().getShoppingListSync(listId) ?: return
        val itemsList = list.items
        
        val updatedItems = itemsList.map { 
            if (it.barcode == itemBarcode && it.productName == itemName) {
                it.copy(scanned = isChecked, scannedQuantity = if (isChecked) it.targetQuantity else 0.0)
            } else {
                it
            }
        }
        val updatedList = list.copy(items = updatedItems)
        database.shoppingListDao().insertShoppingList(updatedList)
    }
}
