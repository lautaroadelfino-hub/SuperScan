package com.example.domain

import android.graphics.Bitmap
import com.example.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
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
    
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    
    private val generativeModel by lazy {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            throw IllegalArgumentException("Falta la API Key de Gemini. Por favor, configúrala en el panel de Secrets de AI Studio.")
        }
        GenerativeModel(
            modelName = "gemini-3.5-flash",
            apiKey = apiKey,
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
              "cantidad": Number (cantidad),
              "precioUnitario": Number (precio unitario),
              "precioTotal": Number (precio total)
            }
          ]
        }
    """.trimIndent()

    suspend fun analyzeReceiptImage(bitmap: Bitmap): Result<ExtractedReceipt> = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = android.graphics.pdf.PdfDocument()
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdfDocument.finishPage(page)
            
            val outputStream = java.io.ByteArrayOutputStream()
            pdfDocument.writeTo(outputStream)
            pdfDocument.close()
            
            val pdfBytes = outputStream.toByteArray()
            analyzeReceiptPdf(pdfBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun analyzeReceiptPdf(pdfBytes: ByteArray): Result<ExtractedReceipt> = withContext(Dispatchers.IO) {
        try {
            val response = generativeModel.generateContent(
                content {
                    blob("application/pdf", pdfBytes)
                    text(prompt)
                }
            )
            val result = parseResponse(response.text)
            if (result != null) Result.success(result) else Result.failure(Exception("JSON parser returned null. Response: ${response.text}"))
        } catch (e: Exception) {
            e.printStackTrace()
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
            e.printStackTrace()
            null
        }
    }
}

