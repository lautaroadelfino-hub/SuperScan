package com.example.data

import kotlinx.coroutines.flow.Flow

// Repositorio del catálogo personal de productos (Room). El resto de los datos
// (tickets, listas compartidas, perfil) vive en Firestore: ver FirebaseRepository.
class LocalRepository(private val database: AppDatabase) {

    fun getProducts(): Flow<List<ProductEntity>> {
        return database.productDao().getAllProducts()
    }

    suspend fun getProduct(barcode: String): ProductEntity? {
        return database.productDao().getProductByBarcode(barcode)
    }

    suspend fun saveProduct(product: ProductEntity) {
        database.productDao().insertProduct(product)
    }
}
