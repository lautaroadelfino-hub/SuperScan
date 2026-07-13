package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// --- MODELS ---

@Serializable
data class SupermarketHistory(
    val supermarket: String,
    val price: Double,
    val quantity: Double,
    val date: String
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val barcode: String,
    val productName: String,
    val category: String,
    val lastPrice: Double,
    val supermarketHistory: List<SupermarketHistory> // JSON of List<SupermarketHistory>
)

@Serializable
data class ReceiptItem(
    val productName: String,
    val quantity: Double,
    val unitPrice: Double,
    val totalPrice: Double,
    val category: String,
    val barcode: String?
)

@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val storeName: String,
    val date: String,
    val totalAmount: Double,
    val items: List<ReceiptItem> // JSON of List<ReceiptItem>
)

@Serializable
data class ShoppingListItem(
    val barcode: String?,
    val productName: String,
    val category: String,
    val targetQuantity: Double,
    val scannedQuantity: Double,
    val expectedPrice: Double,
    val scanned: Boolean
)

@Entity(tableName = "shopping_lists")
data class ShoppingListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val date: String,
    val isCompleted: Boolean,
    val items: List<ShoppingListItem>
)

// --- CONVERTERS ---

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromSupermarketHistoryList(value: List<SupermarketHistory>?): String {
        return value?.let { json.encodeToString(it) } ?: "[]"
    }

    @TypeConverter
    fun toSupermarketHistoryList(value: String): List<SupermarketHistory> {
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromReceiptItemList(value: List<ReceiptItem>?): String {
        return value?.let { json.encodeToString(it) } ?: "[]"
    }

    @TypeConverter
    fun toReceiptItemList(value: String): List<ReceiptItem> {
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromShoppingListItemList(value: List<ShoppingListItem>?): String {
        return value?.let { json.encodeToString(it) } ?: "[]"
    }

    @TypeConverter
    fun toShoppingListItemList(value: String): List<ShoppingListItem> {
        return try {
            json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// --- DAOS ---

@Dao
interface ProductDao {
    @Query("SELECT * FROM products")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun getProductByBarcode(barcode: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity)
}

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM receipts ORDER BY date DESC")
    fun getAllReceipts(): Flow<List<ReceiptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(receipt: ReceiptEntity)

    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun deleteReceipt(id: String)
}

@Dao
interface ShoppingListDao {
    @Query("SELECT * FROM shopping_lists ORDER BY date DESC")
    fun getAllShoppingLists(): Flow<List<ShoppingListEntity>>

    @Query("SELECT * FROM shopping_lists WHERE id = :id LIMIT 1")
    fun getShoppingList(id: String): Flow<ShoppingListEntity?>
    
    @Query("SELECT * FROM shopping_lists WHERE id = :id LIMIT 1")
    suspend fun getShoppingListSync(id: String): ShoppingListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoppingList(list: ShoppingListEntity)

    @Query("DELETE FROM shopping_lists WHERE id = :id")
    suspend fun deleteShoppingList(id: String)
}

// --- DATABASE ---

@TypeConverters(Converters::class)
@Database(
    entities = [ProductEntity::class, ReceiptEntity::class, ShoppingListEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun shoppingListDao(): ShoppingListDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gasto_scan_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
