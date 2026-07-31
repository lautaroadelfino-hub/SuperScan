package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EanTest {

    @Test
    fun `ean13 completo se mantiene igual`() {
        assertEquals("7792360095040", Ean.normalizar("7792360095040"))
    }

    @Test
    fun `conserva ceros a la izquierda ya presentes`() {
        assertEquals("0000040084107", Ean.normalizar("0000040084107"))
    }

    @Test
    fun `upcA de 12 digitos se rellena a 13`() {
        assertEquals("0036000291452", Ean.normalizar("036000291452"))
    }

    @Test
    fun `ean8 se rellena a 13`() {
        assertEquals("0000012345670", Ean.normalizar("12345670"))
    }

    @Test
    fun `quita caracteres no numericos antes de rellenar`() {
        assertEquals("0779236009504", Ean.normalizar(" 77-9236.00950 4"))
    }

    @Test
    fun `sin digitos devuelve null`() {
        assertNull(Ean.normalizar("ABCDEF"))
        assertNull(Ean.normalizar(""))
        assertNull(Ean.normalizar("---"))
    }

    @Test
    fun `mas de 13 digitos devuelve null`() {
        assertNull(Ean.normalizar("12345678901234"))
    }
}
