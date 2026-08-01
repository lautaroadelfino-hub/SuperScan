package com.example.domain

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lee el texto de la foto de un ticket **en el propio teléfono**, con ML Kit.
 *
 * Por qué acá y no en el servidor: el OCR on-device no tiene cuota, no depende de
 * ningún proveedor que pueda caerse o recortar su plan gratuito, y sobre todo la
 * foto nunca sale del dispositivo. Lo que se manda después a un modelo es texto,
 * que además pesa una décima parte en tokens.
 *
 * Ojo con el orden de lectura: ML Kit devuelve bloques en un orden que no es el
 * de lectura humana. En un ticket eso importa muchísimo, porque una línea es
 * "descripción … precio" y si se mezclan las filas los precios terminan pegados
 * al producto equivocado. Por eso se reordena por posición vertical.
 */
object TicketOcr {

    private const val TAG = "GondolaScanner"

    /**
     * Dos líneas se consideran de la misma fila del ticket si sus centros
     * verticales están más cerca que esta fracción de la altura de la línea.
     * En papel térmico las líneas quedan levemente inclinadas si la foto sale
     * torcida, así que hace falta algo de tolerancia.
     */
    private const val TOLERANCIA_FILA = 0.6f

    /** Texto plano del ticket, una línea por renglón, en orden de lectura. */
    suspend fun extraerTexto(bitmap: Bitmap): Result<String> = try {
        val texto = reconocer(bitmap)
        val renglones = ordenarEnRenglones(texto)
        Log.i(TAG, "OCR on-device: ${renglones.size} renglones, ${renglones.sumOf { it.length }} caracteres")
        Result.success(renglones.joinToString("\n"))
    } catch (e: Exception) {
        Log.e(TAG, "Falló el OCR on-device", e)
        Result.failure(e)
    }

    private suspend fun reconocer(bitmap: Bitmap) = suspendCancellableCoroutine { cont ->
        val reconocedor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        reconocedor.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
            .addOnCompleteListener { reconocedor.close() }
        cont.invokeOnCancellation { reconocedor.close() }
    }

    /**
     * Reconstruye los renglones del ticket a partir de las líneas sueltas que
     * devuelve ML Kit: agrupa por altura (Y) y dentro de cada grupo ordena de
     * izquierda a derecha (X). Así "LECHE ENTERA 1L" y "$1.890" vuelven a quedar
     * en el mismo renglón, que es lo que después le da sentido al precio.
     */
    private fun ordenarEnRenglones(texto: com.google.mlkit.vision.text.Text): List<String> {
        data class Linea(val texto: String, val centroY: Int, val izquierda: Int, val alto: Int)

        val lineas = texto.textBlocks
            .flatMap { it.lines }
            .mapNotNull { linea ->
                val caja = linea.boundingBox ?: return@mapNotNull null
                Linea(linea.text, caja.centerY(), caja.left, caja.height())
            }
            .sortedBy { it.centroY }

        if (lineas.isEmpty()) return emptyList()

        val filas = mutableListOf<MutableList<Linea>>()
        for (linea in lineas) {
            val fila = filas.lastOrNull()
            val referencia = fila?.firstOrNull()
            val mismaFila = referencia != null &&
                kotlin.math.abs(linea.centroY - referencia.centroY) < referencia.alto * TOLERANCIA_FILA
            if (mismaFila) fila!!.add(linea) else filas.add(mutableListOf(linea))
        }

        return filas.map { fila ->
            fila.sortedBy { it.izquierda }.joinToString(" ") { it.texto }
        }
    }
}
