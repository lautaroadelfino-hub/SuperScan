---
name: sistema-diseno
description: >-
  El lenguaje visual real de la app SuperScan (Material3 forzado en claro):
  paleta, tipografía y los patrones de componentes que ya se usan (cards, chips,
  skeletons, colores por categoría). Usá esta skill siempre que diseñes o
  implementes UI —una pantalla nueva, un componente, un rediseño como el Inicio,
  ajustes de color o layout— para que quede consistente con lo existente y no
  reinventar estilos ni introducir colores sueltos.
---

# Sistema de diseño de SuperScan

Material3, **forzado en modo claro** (`ui/theme/Theme.kt`: `dynamicColor=false`,
siempre `LightColorScheme`). Toda UI nueva usa tokens de
`MaterialTheme.colorScheme` / `typography`, no colores hardcodeados — salvo los
mapas de categoría/gráficos que ya existen y se explican abajo.

## Paleta (definida en `ui/theme/Color.kt`)
- `primary` **#435993** (índigo apagado) · `onPrimary` blanco
- `primaryContainer` **#D9E2FF** · `onPrimaryContainer` #1B1B1F
- `background` **#F7F9FC** (gris muy claro y frío) · `surface` blanco
- `surfaceVariant` **#E1E2EC** (gris azulado) · `onSurfaceVariant` #44474E
- `outline` #C5C6D0

**Deuda de diseño a tener presente**: `secondary`, `tertiary` y `error` NO están
definidos en el esquema, así que hoy caen en los **defaults morados de Material3**,
que desentonan con el índigo. Varias pantallas los usan (tarjetas de Stats con
`secondaryContainer`/`tertiaryContainer`, precios y borrar con `error`). Definir
estos tokens en `Color.kt`/`Theme.kt` es la mejora de consistencia de mayor
impacto — proponerla al tocar diseño.

## Tipografía (`ui/theme/Type.kt`)
Material3 de fábrica (`FontFamily.Default`); solo `bodyLarge` está ajustado. Los
títulos usan `titleLarge`/`headlineMedium` con `FontWeight.Bold`. No introducir
fuentes nuevas sin una decisión de marca explícita.

## Patrones de componente ya establecidos (reusalos)
- **Tarjeta estándar**: `Card(containerColor = surfaceVariant)`,
  `RoundedCornerShape` (8–24 dp), `padding(16.dp)`. Es el contenedor de casi todo.
- **Chip de categoría/estado**: `Surface`/`Box` con `primaryContainer` o
  `secondaryContainer`, `RoundedCornerShape(4.dp)`, texto `labelSmall`.
- **Skeleton de carga**: `SkeletonBox` (en `CatalogScreen.kt`, alpha animado sobre
  surfaceVariant). Reusar para cualquier estado de carga.
- **Placeholder de imagen**: `MiniaturaProducto` (ícono sobre surface, o la foto).
- **Colores/íconos por categoría**: `CategoriasUi.estilo()` (por palabra clave, con
  fallback estable). Los charts de Stats tienen su propia lista de colores viva —
  unificar ambos mapas sería otra mejora de consistencia.

## Al diseñar UI nueva
- Espaciado en múltiplos de 4/8 dp (los que ya se usan: 4, 8, 12, 16, 24).
- Estados cuidados: skeleton al cargar, mensaje útil en vacío, aviso claro en error
  (ya existe `MensajeCentrado` en `CatalogScreen.kt`, reusalo).
- Prototipá primero con la skill `prototipo-pantalla` antes de escribir Compose.
