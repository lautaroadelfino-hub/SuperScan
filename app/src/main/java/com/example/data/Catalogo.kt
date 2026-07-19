package com.example.data

import com.google.firebase.Timestamp
import java.util.Locale

// Dominio del catálogo de productos. Salvo el Timestamp del modelo, este
// archivo no depende de Android ni de Firestore: la prioridad de precios,
// el comparador y los nombres de cadena se testean con JUnit común.

// productos/{ean13} (solo lectura desde la app) y productos_usuarios/{ean13}.
// Los precios por cadena viven en el map `precios` (id de cadena -> precio);
// el map solo incluye las cadenas que tienen precio. ~35.000 productos todavía
// no tienen precios: todos los campos de precio son nullable.
data class ProductModel(
    val ean: String = "",
    val descripcion: String = "",
    val marca: String = "",
    val categoria: String = "",
    val subcategoria: String = "",
    val precios: Map<String, Double> = emptyMap(),
    val precio_min: Double? = null,
    val cadena_min: String? = null,
    val precio_publico: Double? = null,
    val precio_publico_n: Int = 0,
    val imagen: String? = null,
    /** Variante en mayor resolución para el detalle; puede faltar aunque haya imagen */
    val imagen_grande: String? = null,
    /** Origen de la imagen: "off" (Open Food Facts), o la cadena VTEX ("vea", "carrefour"...).
     *  null en imágenes históricas se trata como "off". Define la atribución del detalle. */
    val imagen_fuente: String? = null,
    val revisar: Boolean = false,
    val actualizado: Timestamp? = null,
    // Solo de la app (no viene en los documentos de `productos`): marca el
    // origen del dato, p. ej. "usuario" para altas en productos_usuarios.
    val fuente: String = ""
) {
    // Precio del catálogo según prioridad: precio_publico > precio_min.
    // La observación propia del usuario (prioridad máxima) requiere una
    // consulta aparte: ver FirebaseRepository.getDisplayPrice.
    fun precioCatalogo(): DisplayPrice =
        precio_publico?.takeIf { it > 0.0 }?.let { DisplayPrice.PublicPrice(it, precio_publico_n) }
            ?: precio_min?.takeIf { it > 0.0 }?.let { DisplayPrice.MinPrice(it, cadena_min ?: "") }
            ?: DisplayPrice.None

    fun precioEstimado(): Double? =
        precio_publico?.takeIf { it > 0.0 } ?: precio_min?.takeIf { it > 0.0 }
}

// Precio a mostrar para un producto, en orden de prioridad
sealed interface DisplayPrice {
    /** La observación más reciente del propio usuario */
    data class UserObservation(val precio: Double, val comercio: String, val fecha: String) : DisplayPrice
    /** precio_publico: etiquetar "informado por usuarios (N)" */
    data class PublicPrice(val precio: Double, val n: Int) : DisplayPrice
    /** precio_min: etiquetar "desde $X en {cadena}" */
    data class MinPrice(val precio: Double, val cadena: String) : DisplayPrice
    /** Sin precio todavía: invitar a informarlo */
    data object None : DisplayPrice
    data class Failure(val mensaje: String) : DisplayPrice
}

// Diccionario ÚNICO de nombres legibles por cadena. Las cadenas van a crecer:
// nunca iterar una lista fija de cadenas; siempre las claves del map precios.
// Para ids desconocidos se deriva un nombre legible del propio id.
object Cadenas {
    private val NOMBRES = mapOf(
        "vea" to "Vea",
        "carrefour" to "Carrefour",
        "coop_obrera" to "Cooperativa Obrera",
        "dia" to "Día"
    )

    fun nombre(id: String): String =
        NOMBRES[id] ?: id.split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { palabra ->
                palabra.lowercase(Locale.ROOT)
                    .replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
}

object Precios {
    // Fila del comparador de precios del detalle de producto
    data class FilaComparador(
        val cadenaId: String,
        val precio: Double,
        val esMinimo: Boolean,
        /** Diferencia porcentual contra la cadena más barata (0.0 para la más barata) */
        val difPorcentaje: Double
    )

    // Una fila por cadena del map precios, de menor a mayor. Precios <= 0 se
    // descartan; a igual precio desempata el id de cadena para dar orden estable.
    fun comparador(precios: Map<String, Double>): List<FilaComparador> {
        val validos = precios.filterValues { it > 0.0 }
        val minimo = validos.values.minOrNull() ?: return emptyList()
        return validos.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .map { (cadena, precio) ->
                FilaComparador(
                    cadenaId = cadena,
                    precio = precio,
                    esMinimo = precio == minimo,
                    difPorcentaje = (precio - minimo) / minimo * 100.0
                )
            }
    }
}

// catalogo_meta/estructura: doc único generado por el pipeline de carga con el
// árbol de navegación (Firestore no tiene "distinct", y escanear 60k documentos
// para derivarlo no es opción). La app lo lee una vez y queda en caché offline.
data class SubcategoriaMeta(
    val nombre: String = "",
    val marcas: List<String> = emptyList()
)

data class CategoriaMeta(
    val nombre: String = "",
    val subcategorias: List<SubcategoriaMeta> = emptyList()
)

data class CatalogoEstructura(
    val categorias: List<CategoriaMeta> = emptyList()
)

enum class OrdenCatalogo { ALFABETICO, PRECIO_ASC, PRECIO_DESC }

// Página de productos dentro de una subcategoría, con filtro de marca opcional
data class FiltroProductos(
    val categoria: String,
    val subcategoria: String,
    val marca: String? = null,
    val orden: OrdenCatalogo = OrdenCatalogo.ALFABETICO
)
