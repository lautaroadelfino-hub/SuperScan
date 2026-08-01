package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Paleta Góndola: verde de ahorro/frescura como primario, amarillo etiqueta
// como terciario (precios, botón de escanear), tinta casi negra y papel de
// fondo. El lenguaje sale del súper, no del Material genérico.

// --- Tema claro ("papel") ---
val gondola_light_primary = Color(0xFF1E7A4E)        // Verde Góndola
val gondola_light_onPrimary = Color(0xFFFFFFFF)
val gondola_light_primaryContainer = Color(0xFFBFE8D2)
val gondola_light_onPrimaryContainer = Color(0xFF0B2E1D)

val gondola_light_secondary = Color(0xFF4C6357)
val gondola_light_onSecondary = Color(0xFFFFFFFF)
val gondola_light_secondaryContainer = Color(0xFFCFE9DB)
val gondola_light_onSecondaryContainer = Color(0xFF0A1F15)

val gondola_light_tertiary = Color(0xFFFFD23F)       // Amarillo Etiqueta
val gondola_light_onTertiary = Color(0xFF14201A)     // Tinta
val gondola_light_tertiaryContainer = Color(0xFFFFE590)
val gondola_light_onTertiaryContainer = Color(0xFF241A00)

val gondola_light_error = Color(0xFFBA1A1A)
val gondola_light_onError = Color(0xFFFFFFFF)
val gondola_light_errorContainer = Color(0xFFFFDAD6)
val gondola_light_onErrorContainer = Color(0xFF410002)

val gondola_light_background = Color(0xFFF4F7F2)     // Papel
val gondola_light_onBackground = Color(0xFF14201A)
val gondola_light_surface = Color(0xFFFFFFFF)
val gondola_light_onSurface = Color(0xFF14201A)
val gondola_light_surfaceVariant = Color(0xFFE3EAE2)
val gondola_light_onSurfaceVariant = Color(0xFF465248)
val gondola_light_outline = Color(0xFFC2CCC2)

// --- Variante opcional "Almacén" (azul de almacén de barrio, papel más cálido).
// Mismo amarillo y mismo rojo de error: lo que cambia es la familia fría.
// Se activa con VarianteGondola.ALMACEN en MyApplicationTheme.
val almacen_primary = Color(0xFF2F55A4)
val almacen_primaryContainer = Color(0xFFC9D8F4)
val almacen_onPrimaryContainer = Color(0xFF0C224E)
val almacen_secondary = Color(0xFF4A5A78)
val almacen_secondaryContainer = Color(0xFFD7E1F3)
val almacen_onSecondaryContainer = Color(0xFF101F3C)
val almacen_background = Color(0xFFF6F4EE)
val almacen_surfaceVariant = Color(0xFFE9E6DC)
val almacen_onSurfaceVariant = Color(0xFF4C5148)
val almacen_outline = Color(0xFFCCC8BB)
val almacen_ink = Color(0xFF181B22)

// --- Tema oscuro ("noche") ---
val gondola_dark_primary = Color(0xFF4CC38A)
val gondola_dark_onPrimary = Color(0xFF06301C)
val gondola_dark_primaryContainer = Color(0xFF14563A)
val gondola_dark_onPrimaryContainer = Color(0xFFBFE8D2)

val gondola_dark_secondary = Color(0xFFB4CCBC)
val gondola_dark_onSecondary = Color(0xFF1F352A)
val gondola_dark_secondaryContainer = Color(0xFF354B3F)
val gondola_dark_onSecondaryContainer = Color(0xFFCFE9DB)

val gondola_dark_tertiary = Color(0xFFFFD23F)        // el amarillo funciona igual de noche
val gondola_dark_onTertiary = Color(0xFF3A2E00)
val gondola_dark_tertiaryContainer = Color(0xFF574400)
val gondola_dark_onTertiaryContainer = Color(0xFFFFE590)

val gondola_dark_error = Color(0xFFFFB4AB)
val gondola_dark_onError = Color(0xFF690005)
val gondola_dark_errorContainer = Color(0xFF93000A)
val gondola_dark_onErrorContainer = Color(0xFFFFDAD6)

val gondola_dark_background = Color(0xFF0E1613)      // Noche
val gondola_dark_onBackground = Color(0xFFE8EFE9)
val gondola_dark_surface = Color(0xFF121B16)
val gondola_dark_onSurface = Color(0xFFE8EFE9)
val gondola_dark_surfaceVariant = Color(0xFF1A2820)
val gondola_dark_onSurfaceVariant = Color(0xFFA9B8AD)
val gondola_dark_outline = Color(0xFF55645A)
