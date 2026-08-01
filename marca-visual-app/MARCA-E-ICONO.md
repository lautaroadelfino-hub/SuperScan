# MARCA-E-ICONO.md — góndola, identidad y ícono de app

Concepto elegido: **A «La Etiqueta»** — la etiqueta amarilla de precio de góndola con la «g» del wordmark. Mismo amarillo que el FAB de escanear: ícono y acción principal son la misma idea.

## Colores de marca
- Verde Góndola `#1E7A4E` (primary) · container `#BFE8D2` (on: `#0B2E1D`)
- Amarillo Etiqueta `#FFD23F` (tertiary; onTertiary tinta `#14201A`)
- Tinta `#14201A` · Papel `#F4F7F2`

## Ícono launcher (adaptive icon)
Reemplaza el `ic_launcher` por defecto (androide genérico) en `app/src/main/res/`.

**Geometría (proporciones sobre lienzo 108×108dp):**
- `background`: verde pleno `#1E7A4E` (color, sin dibujo).
- `foreground` (VectorDrawable, centrado en zona segura de 66dp):
  - Etiqueta: rect redondeado ~62×41 dp, radio ~9dp, rotado **−8°**, fill `#FFD23F`.
  - Agujero: círculo r≈4.5dp, centrado vertical, a ~7dp del borde izquierdo de la etiqueta, fill `#1E7A4E` (= color del fondo).
  - «g»: Roboto **Black (900)** minúscula, alto ~30dp, fill `#14201A`, centrada en la etiqueta con leve corrimiento a la derecha (compensa el agujero). Convertir a trazado en el vector (no texto).
- `monochrome` (Android 13 temáticos): misma silueta etiqueta+agujero+«g» en un solo color.
- A tamaños ≤28px la «g» se pierde: no pasa nada, la etiqueta sola ya identifica.

**Archivos:** `mipmap-anydpi-v26/ic_launcher.xml` y `ic_launcher_round.xml` con `<adaptive-icon>` (background/foreground/monochrome) + regenerar los webp legacy por densidad.

## Splash (core-splashscreen)
- Fondo `#F4F7F2`, ícono centrado (círculo verde + etiqueta), sin animación custom.
- Variante con marca: wordmark «góndola» + chip Tandil abajo (solo si se usa `windowSplashScreenBrandingImage`).

## Play Store
- 512×512 PNG, esquinas cuadradas (Play aplica su máscara), mismo arte full-bleed: fondo verde + etiqueta. Ver `assets/gondola-icon-512.png`.

## Lockup del wordmark (en la app)
- «góndola» siempre minúsculas, Roboto **Black**, tracking −0.03em (≈ −0.5sp a 20sp), color `#1E7A4E` sobre papel/blanco. Nunca sobre amarillo.
- Chip «Tandil»: píldora `#BFE8D2`, texto `#0B2E1D`, labelSmall bold. Es dato (ciudad activa), no decoración: queda pegado al wordmark en la TopAppBar.
- Área de respiro: la altura de la «g» a cada lado. Alto mínimo del wordmark: 24dp.
- Dentro de la app se usa el wordmark, no el ícono. En Auth y Splash conviven: ícono arriba, lockup abajo.

## Auth (dónde aparece la marca)
Pantalla de login/registro: ícono (64dp círculo verde con etiqueta) + lockup centrados arriba del formulario. Ver IMPLEMENTACION-REDISENO.md §Auth.
