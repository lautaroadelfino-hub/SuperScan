package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenasTest {

    @Test
    fun `cadenas conocidas usan el nombre del diccionario`() {
        assertEquals("Vea", Cadenas.nombre("vea"))
        assertEquals("Carrefour", Cadenas.nombre("carrefour"))
        assertEquals("Cooperativa Obrera", Cadenas.nombre("coop_obrera"))
        assertEquals("Día", Cadenas.nombre("dia"))
    }

    @Test
    fun `cadena desconocida deriva un nombre legible del id`() {
        assertEquals("Super Mami", Cadenas.nombre("super_mami"))
        assertEquals("Makro", Cadenas.nombre("makro"))
        assertEquals("La Anonima", Cadenas.nombre("la_anonima"))
    }
}

class PreciosTest {

    private val precios = mapOf(
        "vea" to 1942.0,
        "carrefour" to 1909.0,
        "coop_obrera" to 2090.0,
        "dia" to 2000.0
    )

    @Test
    fun `ordena de menor a mayor`() {
        val filas = Precios.comparador(precios)
        assertEquals(listOf("carrefour", "vea", "dia", "coop_obrera"), filas.map { it.cadenaId })
    }

    @Test
    fun `marca como minimo solo la mas barata`() {
        val filas = Precios.comparador(precios)
        assertTrue(filas.first().esMinimo)
        assertTrue(filas.drop(1).none { it.esMinimo })
        assertEquals(0.0, filas.first().difPorcentaje, 0.0001)
    }

    @Test
    fun `calcula el porcentaje de diferencia contra la mas barata`() {
        val filas = Precios.comparador(mapOf("a" to 100.0, "b" to 125.0))
        assertEquals(25.0, filas[1].difPorcentaje, 0.0001)
    }

    @Test
    fun `empate en el minimo marca ambas y desempata por id`() {
        val filas = Precios.comparador(mapOf("b" to 100.0, "a" to 100.0, "c" to 110.0))
        assertEquals(listOf("a", "b", "c"), filas.map { it.cadenaId })
        assertTrue(filas[0].esMinimo)
        assertTrue(filas[1].esMinimo)
        assertFalse(filas[2].esMinimo)
    }

    @Test
    fun `descarta precios invalidos y map vacio`() {
        assertTrue(Precios.comparador(emptyMap()).isEmpty())
        assertTrue(Precios.comparador(mapOf("a" to 0.0, "b" to -5.0)).isEmpty())
        assertEquals(1, Precios.comparador(mapOf("a" to 0.0, "b" to 99.0)).size)
    }
}

class ProductModelPrecioTest {

    @Test
    fun `precio publico tiene prioridad sobre precio min`() {
        val p = ProductModel(precio_publico = 1990.0, precio_publico_n = 7, precio_min = 1909.0, cadena_min = "carrefour")
        assertEquals(DisplayPrice.PublicPrice(1990.0, 7), p.precioCatalogo())
    }

    @Test
    fun `sin precio publico cae a precio min con su cadena`() {
        val p = ProductModel(precio_min = 1909.0, cadena_min = "carrefour")
        assertEquals(DisplayPrice.MinPrice(1909.0, "carrefour"), p.precioCatalogo())
    }

    @Test
    fun `precio publico en cero no cuenta`() {
        val p = ProductModel(precio_publico = 0.0, precio_min = 1909.0, cadena_min = "carrefour")
        assertEquals(DisplayPrice.MinPrice(1909.0, "carrefour"), p.precioCatalogo())
    }

    @Test
    fun `precio min sin cadena min no rompe`() {
        val p = ProductModel(precio_min = 1909.0)
        assertEquals(DisplayPrice.MinPrice(1909.0, ""), p.precioCatalogo())
    }

    @Test
    fun `sin ningun precio devuelve None`() {
        assertEquals(DisplayPrice.None, ProductModel().precioCatalogo())
    }

    @Test
    fun `precio estimado sigue la misma prioridad`() {
        assertEquals(1990.0, ProductModel(precio_publico = 1990.0, precio_min = 1909.0).precioEstimado())
        assertEquals(1909.0, ProductModel(precio_min = 1909.0).precioEstimado())
        assertEquals(null, ProductModel().precioEstimado())
    }
}
