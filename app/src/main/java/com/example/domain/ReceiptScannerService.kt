package com.example.domain

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExtractedReceipt(
    @SerialName("comercio") val storeName: String = "Desconocido",
    @SerialName("fecha") val date: String = "", // YYYY-MM-DD
    @SerialName("total") val totalAmount: Double = 0.0,
    val items: List<ExtractedItem> = emptyList(),
    @SerialName("confianza") val confidence: Double = 1.0
)

@Serializable
data class ExtractedItem(
    @SerialName("descripcion") val productName: String = "Producto",
    val category: String = "Varios",
    @SerialName("precioUnitario") val unitPrice: Double = 0.0,
    @SerialName("precioTotal") val totalPrice: Double = 0.0,
    @SerialName("cantidad") val quantity: Double = 1.0,
    val barcode: String? = null
)

class ReceiptScannerService {

    companion object {
        // Un tag propio y buscable: `adb logcat -s GondolaScanner` alcanza para
        // ver por qué falló una lectura. printStackTrace se pierde entre el ruido
        // del sistema y no dice de dónde salió.
        private const val TAG = "GondolaScanner"

        val PRESET_CATEGORIES = listOf(
            "Lácteos y Quesos", "Bebidas", "Limpieza y Hogar", "Panadería y Dulces",
            "Frutas y Verduras", "Carnicería y Pescadería", "Almacén y Comestibles",
            "Cuidado Personal", "Mascotas", "Otros"
        )
    }

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    
    // Firebase AI Logic: la llamada a Gemini se autentica con el proyecto de
    // Firebase + App Check, no con una API key. Por eso no hay ninguna key en el
    // APK que alguien pueda extraer del bundle publicado en Play.
    private val generativeModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.5-flash",
            systemInstruction = content { text("Eres un asistente experto en extracción de datos. Tu única tarea es extraer la información solicitada de los documentos proporcionados y devolver SIEMPRE un único objeto JSON válido. NO debes incluir ningún texto introductorio, ni bloques de código markdown, ni explicaciones adicionales, sólo el JSON crudo.") },
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            }
        )
    }

    private val prompt = """
        Extrae la información de este ticket o factura de compra.
        IMPORTANTE: Devuelve ÚNICAMENTE un objeto JSON válido, sin formato markdown (no uses ```json ni ```).
        El JSON debe tener exactamente la siguiente estructura:
        {
          "comercio": "String (nombre del supermercado)",
          "fecha": "String (YYYY-MM-DD)",
          "total": Number (monto total),
          "confianza": Number (nivel de confianza de 0 a 1),
          "items": [
            {
              "descripcion": "String (producto)",
              "category": "String (categoría del producto)",
              "cantidad": Number (cantidad),
              "precioUnitario": Number (precio unitario),
              "precioTotal": Number (precio total)
            }
          ]
        }
        Para el campo "category" de cada ítem elige EXACTAMENTE una de las siguientes opciones
        (la que mejor describa al producto; si ninguna aplica usa "Otros"):
        ${PRESET_CATEGORIES.joinToString(", ") { "\"$it\"" }}
    """.trimIndent()

    /**
     * Manda la foto como JPEG.
     *
     * Antes se envolvía el bitmap en un PdfDocument, que lo serializa SIN
     * comprimir: una foto de 2000 px se iba a decenas de MB, cerca del límite de
     * inlineData, y el modelo termina reescalando la página igual. Un JPEG al 85%
     * de la misma foto pesa unos 200 KB y se lee igual de bien.
     */
    suspend fun analyzeReceiptImage(bitmap: Bitmap): Result<ExtractedReceipt> = withContext(Dispatchers.IO) {
        try {
            val salida = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, salida)
            val jpegBytes = salida.toByteArray()
            Log.i(TAG, "Ticket ${bitmap.width}x${bitmap.height} -> ${jpegBytes.size / 1024} KB de JPEG")
            analizar(jpegBytes, "image/jpeg")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo comprimir la foto del ticket", e)
            Result.failure(e)
        }
    }

    suspend fun analyzeReceiptPdf(pdfBytes: ByteArray): Result<ExtractedReceipt> =
        analizar(pdfBytes, "application/pdf")

    /** El envío en sí. Lo comparten la foto (JPEG) y el PDF subido a mano. */
    private suspend fun analizar(datos: ByteArray, mimeType: String): Result<ExtractedReceipt> = withContext(Dispatchers.IO) {
        try {
            val response = generativeModel.generateContent(
                content {
                    inlineData(datos, mimeType)
                    text(prompt)
                }
            )
            val result = parseResponse(response.text)
            if (result != null) {
                Result.success(result)
            } else {
                Log.e(TAG, "El modelo respondió algo que no es el JSON esperado: ${response.text?.take(300)}")
                Result.failure(Exception("JSON parser returned null. Response: ${response.text}"))
            }
        } catch (e: Exception) {
            // Acá caen los errores del proveedor: cuota agotada (429), App Check
            // rechazado (401), modelo inexistente (404). El mensaje del proveedor
            // es lo único que permite distinguirlos, así que va entero al log.
            Log.e(TAG, "Falló la lectura del ticket: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseResponse(jsonText: String?): ExtractedReceipt? {
        if (jsonText.isNullOrBlank()) return null
        
        var cleanJson = jsonText.trim()
        if (cleanJson.startsWith("```json")) {
            cleanJson = cleanJson.removePrefix("```json").removeSuffix("```").trim()
        } else if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson.removePrefix("```").removeSuffix("```").trim()
        }
        
        return try {
            jsonParser.decodeFromString<ExtractedReceipt>(cleanJson)
        } catch (e: Exception) {
            Log.e(TAG, "El JSON del modelo no se pudo parsear: ${cleanJson.take(300)}", e)
            null
        }
    }
}

