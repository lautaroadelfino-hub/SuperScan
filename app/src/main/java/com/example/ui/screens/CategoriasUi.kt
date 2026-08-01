package com.example.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Liquor
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

// Ícono y color de cada tarjeta de la grilla de categorías. Las categorías
// vienen de los datos (catalogo_meta/estructura), así que el match es por
// palabra clave y siempre hay un fallback: una categoría nueva aparece con
// ícono genérico y un color estable derivado de su nombre.
object CategoriasUi {

    data class Estilo(val icono: ImageVector, val color: Color)

    // Orden importa: gana la primera palabra clave contenida en el nombre
    // ("SIN ALCOHOL" debe evaluarse antes que "ALCOHOL")
    private val POR_PALABRA_CLAVE: List<Pair<String, Estilo>> = listOf(
        "SIN ALCOHOL" to Estilo(Icons.Filled.LocalDrink, Color(0xFF1E88E5)),
        "ALCOHOL" to Estilo(Icons.Filled.Liquor, Color(0xFF5E35B1)),
        "DESAYUNO" to Estilo(Icons.Filled.LocalCafe, Color(0xFF8D6E63)),
        "MERIENDA" to Estilo(Icons.Filled.LocalCafe, Color(0xFF8D6E63)),
        "CUIDADO PERSONAL" to Estilo(Icons.Filled.Face, Color(0xFFD81B60)),
        "FRESCO" to Estilo(Icons.Filled.LunchDining, Color(0xFF43A047)),
        "BEBIDA" to Estilo(Icons.Filled.LocalDrink, Color(0xFF1E88E5)),
        "LACTE" to Estilo(Icons.Filled.LocalCafe, Color(0xFF8D6E63)),
        "CONGELADO" to Estilo(Icons.Filled.AcUnit, Color(0xFF00ACC1)),
        "PANADERIA" to Estilo(Icons.Filled.BakeryDining, Color(0xFFF4511E)),
        "CARNE" to Estilo(Icons.Filled.LunchDining, Color(0xFFE53935)),
        "PESCADO" to Estilo(Icons.Filled.SetMeal, Color(0xFF039BE5)),
        "FRUTA" to Estilo(Icons.Filled.ShoppingBasket, Color(0xFF43A047)),
        "VERDU" to Estilo(Icons.Filled.ShoppingBasket, Color(0xFF43A047)),
        "LIMPIEZA" to Estilo(Icons.Filled.CleaningServices, Color(0xFF3949AB)),
        "PERFUM" to Estilo(Icons.Filled.Face, Color(0xFFD81B60)),
        "CUIDADO" to Estilo(Icons.Filled.Face, Color(0xFFD81B60)),
        "FARMACIA" to Estilo(Icons.Filled.MedicalServices, Color(0xFF00897B)),
        "BEBE" to Estilo(Icons.Filled.ChildCare, Color(0xFF7CB342)),
        "MASCOTA" to Estilo(Icons.Filled.Pets, Color(0xFF6D4C41)),
        "GOLOSINA" to Estilo(Icons.Filled.Cake, Color(0xFFAB47BC)),
        "KIOSCO" to Estilo(Icons.Filled.Cake, Color(0xFFAB47BC)),
        "HOGAR" to Estilo(Icons.Filled.Home, Color(0xFF546E7A)),
        "ALMACEN" to Estilo(Icons.Filled.Kitchen, Color(0xFFFB8C00))
    )

    private val PALETA_FALLBACK = listOf(
        Color(0xFF5E35B1), Color(0xFF00838F), Color(0xFFC0CA33),
        Color(0xFF6A1B9A), Color(0xFF2E7D32), Color(0xFFEF6C00)
    )

    // Paleta única para series de gráficos (torta de Stats, curvas de precio):
    // misma familia de colores que las categorías, para que toda la app
    // comparta un solo lenguaje cromático en vez de listas sueltas por pantalla.
    // Los tonos van bien separados en el círculo cromático: dos series vecinas
    // nunca son "dos verdes" que se confunden en una torta o en dos curvas.
    val PALETA_GRAFICOS = listOf(
        Color(0xFF1E7A4E), // Verde Góndola (primary de la marca)
        Color(0xFFFB8C00), // naranja
        Color(0xFF1E88E5), // azul
        Color(0xFFD81B60), // rosa
        Color(0xFF5E35B1), // violeta
        Color(0xFF00ACC1), // cian
        Color(0xFF8D6E63), // marrón
        Color(0xFFE53935)  // rojo
    )

    fun estilo(categoria: String): Estilo {
        val nombre = sinAcentos(categoria.uppercase(Locale.ROOT))
        POR_PALABRA_CLAVE.firstOrNull { (clave, _) -> clave in nombre }?.let { return it.second }
        return Estilo(
            icono = Icons.Filled.Category,
            color = PALETA_FALLBACK[abs(nombre.hashCode()) % PALETA_FALLBACK.size]
        )
    }

    private fun sinAcentos(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
