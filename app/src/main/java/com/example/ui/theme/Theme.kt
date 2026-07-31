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

@Composable
fun MyApplicationTheme(
  // El papel claro con tarjetas blancas ES la identidad Góndola: se fuerza
  // claro. El esquema oscuro queda definido para ofrecerlo a futuro como
  // preferencia del usuario, no como default del sistema.
  darkTheme: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
