package com.example.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

// Modelos de UI para el historial de tickets. La fuente de verdad es Firestore
// (FirebaseRepository.getTickets); ya no se persisten en Room.

@Serializable
data class ReceiptItem(
    val productName: String,
    val quantity: Double,
    val unitPrice: Double,
    val totalPrice: Double,
    val category: String,
    val barcode: String?
)

@Serializable
data class ReceiptEntity(
    val id: String,
    val storeName: String,
    val date: String,
    val totalAmount: Double,
    val items: List<ReceiptItem>
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

// --- DATABASE ---

@TypeConverters(Converters::class)
@Database(
    entities = [ProductEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v2: los tickets y las listas de compras migraron a Firestore;
        // se eliminan las tablas locales que quedaron sin uso.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS receipts")
                db.execSQL("DROP TABLE IF EXISTS shopping_lists")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gasto_scan_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
