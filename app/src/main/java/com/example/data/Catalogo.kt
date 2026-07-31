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

// Formato argentino para la UI: miles con punto, decimales con coma y solo
// cuando existen. Los precios son la cara de la app: "$18.240", no "$18240.00".
object Formato {

    fun precio(valor: Double): String {
        val total = kotlin.math.round(valor * 100).toLong()
        val abs = kotlin.math.abs(total)
        val entero = abs / 100
        val centavos = (abs % 100).toInt()
        val miles = entero.toString().reversed().chunked(3).joinToString(".").reversed()
        val cuerpo = if (centavos == 0) miles else "$miles,${centavos.toString().padStart(2, '0')}"
        return (if (total < 0) "-$" else "$") + cuerpo
    }

    /** "+6,8%" / "-3,1%"; sin signo para valores en cero */
    fun porcentaje(valor: Double): String {
        val cuerpo = String.format(Locale.ROOT, "%.1f", kotlin.math.abs(valor)).replace('.', ',')
        val signo = if (valor > 0.0) "+" else if (valor < 0.0) "-" else ""
        return "$signo$cuerpo%"
    }
}

// Comparador de lista: cotiza la lista de compras activa en cada cadena.
// La honestidad manda: cada cadena suma SOLO los ítems que tienen precio ahí,
// y expone la cobertura para que el usuario juzgue (un súper "barato" al que
// le faltan 3 productos no siempre gana en serio).
object ComparadorLista {

    data class TotalCadena(
        val cadenaId: String,
        val total: Double,
        val itemsConPrecio: Int,
        val esMejor: Boolean,
        /** Diferencia % contra la mejor. Solo tiene sentido si [comparable]. */
        val difPorcentaje: Double,
        /** true si cubre tantos productos como la cadena que más cubre */
        val comparable: Boolean,
        /** Productos de la lista que esta cadena NO tiene */
        val faltantes: Int
    )

    data class Resultado(
        val cadenas: List<TotalCadena>,
        val itemsTotal: Int,
        /** Cobertura de la(s) cadena(s) que más productos tienen */
        val mejorCobertura: Int,
        /** Ahorro contra la más cara ENTRE LAS COMPARABLES */
        val ahorroVsPeor: Double
    )

    /**
     * Cotiza la lista en cada cadena.
     *
     * Clave: comparar totales de cadenas con distinta cobertura es engañoso —
     * la que tiene menos productos siempre parece más barata. Por eso solo
     * compiten por el primer puesto las cadenas que cubren tantos productos
     * como la que más cubre; el resto se muestra informativamente, con cuántos
     * productos le faltan y sin un % que induzca a error.
     *
     * @param items pares (producto del catálogo o null si no se encontró, cantidad)
     */
    fun cotizar(items: List<Pair<ProductModel?, Double>>): Resultado? {
        if (items.isEmpty()) return null
        val totales = mutableMapOf<String, Double>()
        val conteos = mutableMapOf<String, Int>()
        items.forEach { (producto, cantidad) ->
            producto?.precios?.forEach { (cadena, precio) ->
                if (precio > 0.0) {
                    totales[cadena] = (totales[cadena] ?: 0.0) + precio * cantidad
                    conteos[cadena] = (conteos[cadena] ?: 0) + 1
                }
            }
        }
        if (totales.isEmpty()) return null

        val mejorCobertura = conteos.values.max()
        val comparables = totales.keys.filter { conteos[it] == mejorCobertura }
        val totalMejor = comparables.minOf { totales.getValue(it) }
        val totalPeorComparable = comparables.maxOf { totales.getValue(it) }

        // Primero las que cubren todo (de más barata a más cara), después el
        // resto ordenado por cobertura y precio.
        val orden = totales.keys.sortedWith(
            compareByDescending<String> { conteos[it] == mejorCobertura }
                .thenByDescending { conteos.getValue(it) }
                .thenBy { totales.getValue(it) }
                .thenBy { it }
        )

        val cadenas = orden.map { cadena ->
            val total = totales.getValue(cadena)
            val cubre = conteos.getValue(cadena)
            val esComparable = cubre == mejorCobertura
            TotalCadena(
                cadenaId = cadena,
                total = total,
                itemsConPrecio = cubre,
                esMejor = esComparable && total == totalMejor,
                difPorcentaje = if (esComparable && totalMejor > 0.0) {
                    (total - totalMejor) / totalMejor * 100.0
                } else 0.0,
                comparable = esComparable,
                faltantes = mejorCobertura - cubre
            )
        }
        return Resultado(
            cadenas = cadenas,
            itemsTotal = items.size,
            mejorCobertura = mejorCobertura,
            ahorroVsPeor = totalPeorComparable - totalMejor
        )
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
