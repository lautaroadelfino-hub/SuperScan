# -*- coding: utf-8 -*-
"""
generar_iconos.py - Genera los recursos del launcher de Góndola a partir del
arte oficial `assets/gondola-icon-512.png` (La Etiqueta), siguiendo las
proporciones de MARCA-E-ICONO.md.

Produce, dentro de app/src/main/res/:
  mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png   capa foreground del
      adaptive icon: la etiqueta con su ojal, centrada en el lienzo de 108dp y
      escalada a ~62x41dp (el resto transparente).
  mipmap-{...}/ic_launcher_monochrome.png   silueta de la etiqueta con el ojal
      y la «g» calados, para los íconos temáticos de Android 13.
  mipmap-{...}/ic_launcher.webp             ícono legacy (API 24-25).
  mipmap-{...}/ic_launcher_round.webp       idem, con máscara circular.

El fondo del adaptive icon es verde pleno y vive en drawable/ic_launcher_background.xml.

Uso:  python marca-visual-app/generar_iconos.py
"""

from pathlib import Path
from PIL import Image, ImageDraw

RAIZ = Path(__file__).resolve().parent
FUENTE = RAIZ / "assets" / "gondola-icon-512.png"
RES = RAIZ.parent / "app" / "src" / "main" / "res"

VERDE = (30, 122, 78)      # #1E7A4E - fondo de marca (y color del ojal)
AMARILLO = (255, 210, 63)  # #FFD23F - la etiqueta

# Densidades: factor sobre el lienzo base de 108dp y el legacy de 48dp
DENSIDADES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# Proporciones de MARCA-E-ICONO.md: la etiqueta mide 62x41dp rotada -8°, así que
# sus esquinas quedan a 37,2dp del centro. La zona segura del adaptive icon es un
# círculo de 66dp (radio 33): a 62x41 las esquinas se las come la máscara redonda
# del launcher. Se baja al 88,7% para que entre entera con cualquier máscara,
# manteniendo la proporción del diseño.
ESCALA_ZONA_SEGURA = 33.0 / 37.2
ANCHO_ETIQUETA_DP = 62 * ESCALA_ZONA_SEGURA
ALTO_ETIQUETA_DP = 41 * ESCALA_ZONA_SEGURA
ANCHO_CAJA_DP = ANCHO_ETIQUETA_DP * 0.99027 + ALTO_ETIQUETA_DP * 0.13917  # cos8° / sin8°
LIENZO_DP = 108.0

# Splash de Android 12+: el lienzo del ícono es de 240dp y el arte tiene que
# entrar en un círculo interior de 160dp, si no el sistema lo recorta.
SPLASH_LADO_PX = 960          # 240dp @xxxhdpi
SPLASH_CIRCULO_PX = 640       # 160dp: el disco verde
SPLASH_CAJA_ETIQUETA_PX = 400 # caja de la etiqueta adentro del disco


def cerca(pixel, color, tolerancia=60):
    return all(abs(pixel[i] - color[i]) <= tolerancia for i in range(3))


def silueta_de_la_etiqueta(img):
    """Máscara de la etiqueta (incluye el ojal y la «g»): todo lo que no sea
    el verde de fondo CONECTADO con el borde. El ojal es verde pero está
    encerrado por el amarillo, así que queda adentro de la silueta."""
    ancho, alto = img.size
    mapa = Image.new("L", img.size, 255)
    px_src = img.load()
    px_mapa = mapa.load()
    for y in range(alto):
        for x in range(ancho):
            if cerca(px_src[x, y], VERDE):
                px_mapa[x, y] = 128  # candidato a "fondo"
    # Solo el verde que se toca con el borde es fondo: el ojal no se alcanza
    for semilla in ((0, 0), (ancho - 1, 0), (0, alto - 1), (ancho - 1, alto - 1)):
        if px_mapa[semilla] == 128:
            ImageDraw.floodfill(mapa, semilla, 0, thresh=0)
    return mapa.point(lambda v: 0 if v == 0 else 255)


def mascara_amarilla(img):
    """Solo el cuerpo amarillo: deja calados el ojal y la «g»."""
    mascara = Image.new("L", img.size, 0)
    px_src = img.load()
    px_m = mascara.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            if cerca(px_src[x, y], AMARILLO, tolerancia=70):
                px_m[x, y] = 255
    return mascara


def recortar(imagen_rgba):
    caja = imagen_rgba.getbbox()
    return imagen_rgba.crop(caja)


def componer_capa(recorte, lado_px):
    """Centra el recorte en un lienzo cuadrado, escalado a las proporciones
    de la etiqueta sobre el lienzo de 108dp."""
    objetivo = int(round(lado_px * ANCHO_CAJA_DP / LIENZO_DP))
    escala = objetivo / recorte.width
    escalado = recorte.resize(
        (objetivo, max(1, int(round(recorte.height * escala)))),
        Image.LANCZOS,
    )
    lienzo = Image.new("RGBA", (lado_px, lado_px), (0, 0, 0, 0))
    lienzo.paste(
        escalado,
        ((lado_px - escalado.width) // 2, (lado_px - escalado.height) // 2),
        escalado,
    )
    return lienzo


def icono_splash(etiqueta):
    """Disco verde con la etiqueta adentro, con el aire que pide Android 12+:
    el arte entra en el círculo interior de 160dp sobre un lienzo de 240dp."""
    lienzo = Image.new("RGBA", (SPLASH_LADO_PX, SPLASH_LADO_PX), (0, 0, 0, 0))
    margen = (SPLASH_LADO_PX - SPLASH_CIRCULO_PX) // 2
    ImageDraw.Draw(lienzo).ellipse(
        (margen, margen, margen + SPLASH_CIRCULO_PX, margen + SPLASH_CIRCULO_PX),
        fill=VERDE + (255,),
    )
    escala = SPLASH_CAJA_ETIQUETA_PX / etiqueta.width
    escalado = etiqueta.resize(
        (SPLASH_CAJA_ETIQUETA_PX, max(1, int(round(etiqueta.height * escala)))),
        Image.LANCZOS,
    )
    lienzo.paste(
        escalado,
        ((SPLASH_LADO_PX - escalado.width) // 2, (SPLASH_LADO_PX - escalado.height) // 2),
        escalado,
    )
    return lienzo


def circular(imagen):
    mascara = Image.new("L", imagen.size, 0)
    ImageDraw.Draw(mascara).ellipse((0, 0, imagen.size[0] - 1, imagen.size[1] - 1), fill=255)
    salida = imagen.convert("RGBA")
    salida.putalpha(mascara)
    return salida


def main():
    if not FUENTE.exists():
        raise SystemExit(f"No encuentro el arte fuente: {FUENTE}")

    original = Image.open(FUENTE).convert("RGB")

    # --- foreground: la etiqueta completa, con el ojal en verde ---
    silueta = silueta_de_la_etiqueta(original)
    foreground = original.convert("RGBA")
    foreground.putalpha(silueta)
    foreground = recortar(foreground)

    # --- monochrome: la silueta amarilla, con ojal y «g» calados ---
    amarillo = mascara_amarilla(original)
    mono = Image.new("RGBA", original.size, (0, 0, 0, 0))
    mono.putalpha(amarillo)
    mono = recortar(mono)

    for densidad, factor in DENSIDADES.items():
        carpeta = RES / f"mipmap-{densidad}"
        carpeta.mkdir(parents=True, exist_ok=True)

        lado = int(round(108 * factor))
        componer_capa(foreground, lado).save(carpeta / "ic_launcher_foreground.png")
        componer_capa(mono, lado).save(carpeta / "ic_launcher_monochrome.png")

        # Legacy (API 24-25): el arte full-bleed, cuadrado y redondo
        legacy = int(round(48 * factor))
        cuadrado = original.resize((legacy, legacy), Image.LANCZOS)
        cuadrado.save(carpeta / "ic_launcher.webp", "WEBP", lossless=True)
        circular(cuadrado).save(carpeta / "ic_launcher_round.webp", "WEBP", lossless=True)

        print(f"mipmap-{densidad}: foreground/monochrome {lado}px · legacy {legacy}px")

    # Ícono del splash: uno solo, en drawable-nodpi, para todas las densidades
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    icono_splash(foreground).save(nodpi / "ic_splash_gondola.png")
    print(f"drawable-nodpi/ic_splash_gondola.png: {SPLASH_LADO_PX}px")

    print("Listo. Recordá revisar el ícono en el launcher tras instalar.")


if __name__ == "__main__":
    main()
