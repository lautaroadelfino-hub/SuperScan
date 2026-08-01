package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme =
  lightColorScheme(
    primary = gondola_light_primary,
    onPrimary = gondola_light_onPrimary,
    primaryContainer = gondola_light_primaryContainer,
    onPrimaryContainer = gondola_light_onPrimaryContainer,
    secondary = gondola_light_secondary,
    onSecondary = gondola_light_onSecondary,
    secondaryContainer = gondola_light_secondaryContainer,
    onSecondaryContainer = gondola_light_onSecondaryContainer,
    tertiary = gondola_light_tertiary,
    onTertiary = gondola_light_onTertiary,
    tertiaryContainer = gondola_light_tertiaryContainer,
    onTertiaryContainer = gondola_light_onTertiaryContainer,
    error = gondola_light_error,
    onError = gondola_light_onError,
    errorContainer = gondola_light_errorContainer,
    onErrorContainer = gondola_light_onErrorContainer,
    background = gondola_light_background,
    onBackground = gondola_light_onBackground,
    surface = gondola_light_surface,
    onSurface = gondola_light_onSurface,
    surfaceVariant = gondola_light_surfaceVariant,
    onSurfaceVariant = gondola_light_onSurfaceVariant,
    outline = gondola_light_outline,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = gondola_dark_primary,
    onPrimary = gondola_dark_onPrimary,
    primaryContainer = gondola_dark_primaryContainer,
    onPrimaryContainer = gondola_dark_onPrimaryContainer,
    secondary = gondola_dark_secondary,
    onSecondary = gondola_dark_onSecondary,
    secondaryContainer = gondola_dark_secondaryContainer,
    onSecondaryContainer = gondola_dark_onSecondaryContainer,
    tertiary = gondola_dark_tertiary,
    onTertiary = gondola_dark_onTertiary,
    tertiaryContainer = gondola_dark_tertiaryContainer,
    onTertiaryContainer = gondola_dark_onTertiaryContainer,
    error = gondola_dark_error,
    onError = gondola_dark_onError,
    errorContainer = gondola_dark_errorContainer,
    onErrorContainer = gondola_dark_onErrorContainer,
    background = gondola_dark_background,
    onBackground = gondola_dark_onBackground,
    surface = gondola_dark_surface,
    onSurface = gondola_dark_onSurface,
    surfaceVariant = gondola_dark_surfaceVariant,
    onSurfaceVariant = gondola_dark_onSurfaceVariant,
    outline = gondola_dark_outline,
  )

// Variante cromática de la marca. "Almacén" es una exploración azul del mismo
// sistema (mismo amarillo, mismo error): vive detrás de este flag para poder
// probarla sin tocar una sola pantalla.
enum class VarianteGondola { GONDOLA, ALMACEN }

private val AlmacenColorScheme =
  lightColorScheme(
    primary = almacen_primary,
    onPrimary = gondola_light_onPrimary,
    primaryContainer = almacen_primaryContainer,
    onPrimaryContainer = almacen_onPrimaryContainer,
    secondary = almacen_secondary,
    onSecondary = gondola_light_onSecondary,
    secondaryContainer = almacen_secondaryContainer,
    onSecondaryContainer = almacen_onSecondaryContainer,
    // El amarillo de la etiqueta no se toca: es la marca
    tertiary = gondola_light_tertiary,
    onTertiary = gondola_light_onTertiary,
    tertiaryContainer = gondola_light_tertiaryContainer,
    onTertiaryContainer = gondola_light_onTertiaryContainer,
    error = gondola_light_error,
    onError = gondola_light_onError,
    errorContainer = gondola_light_errorContainer,
    onErrorContainer = gondola_light_onErrorContainer,
    background = almacen_background,
    onBackground = almacen_ink,
    surface = gondola_light_surface,
    onSurface = almacen_ink,
    surfaceVariant = almacen_surfaceVariant,
    onSurfaceVariant = almacen_onSurfaceVariant,
    outline = almacen_outline,
  )

@Composable
fun MyApplicationTheme(
  // El papel claro con tarjetas blancas ES la identidad Góndola: se fuerza
  // claro. El esquema oscuro queda definido para ofrecerlo a futuro como
  // preferencia del usuario, no como default del sistema.
  darkTheme: Boolean = false,
  variante: VarianteGondola = VarianteGondola.GONDOLA,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    darkTheme -> DarkColorScheme
    variante == VarianteGondola.ALMACEN -> AlmacenColorScheme
    else -> LightColorScheme
  }
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
