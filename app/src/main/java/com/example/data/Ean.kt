package com.example.data

// Normalización de códigos de barras. Función pura, sin dependencias de
// Android ni Firebase, para poder testearla con JUnit común.
object Ean {

    const val LONGITUD_GTIN13 = 13

    /**
     * Normaliza el rawValue del escáner (EAN-13, EAN-8, UPC-A, UPC-E) a GTIN-13,
     * el formato de los IDs de documento en `productos` y `productos_usuarios`:
     * se quitan todos los caracteres no numéricos y se rellena con ceros a la
     * izquierda hasta 13 dígitos.
     *
     * Devuelve null si el código es inválido (sin dígitos, o más de 13).
     */
    fun normalizar(raw: String): String? {
        val digitos = raw.filter { it.isDigit() }
        if (digitos.isEmpty() || digitos.length > LONGITUD_GTIN13) return null
        return digitos.padStart(LONGITUD_GTIN13, '0')
    }
}
