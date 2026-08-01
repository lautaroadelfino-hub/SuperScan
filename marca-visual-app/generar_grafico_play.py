# -*- coding: utf-8 -*-
"""
generar_grafico_play.py — Gráfico de funciones para la ficha de Google Play
(1024x500), armado con el arte oficial de La Etiqueta y las reglas de
MARCA-E-ICONO.md.

Composición: fondo Papel, disco verde con la etiqueta a la izquierda y el
wordmark «góndola» + bajada a la derecha. El wordmark va verde sobre papel, que
es como manda la marca (nunca sobre amarillo).

Uso:  python marca-visual-app/generar_grafico_play.py
Salida: marca-visual-app/assets/play-feature-graphic-1024x500.png
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from generar_iconos import recortar, silueta_de_la_etiqueta

RAIZ = Path(__file__).resolve().parent
FUENTE_ARTE = RAIZ / "assets" / "gondola-icon-512.png"
SALIDA = RAIZ / "assets" / "play-feature-graphic-1024x500.png"

ANCHO, ALTO = 1024, 500

VERDE = (30, 122, 78)        # #1E7A4E
PAPEL = (244, 247, 242)      # #F4F7F2
TINTA = (20, 32, 26)         # #14201A
CONTAINER = (191, 232, 210)  # #BFE8D2
ON_CONTAINER = (11, 46, 29)  # #0B2E1D

CANDIDATAS_BLACK = [
    Path("C:/Program Files/Android/Android Studio/plugins/design-tools/resources/layoutlib/data/fonts/RobotoFlex-Regular.ttf"),
    Path("C:/Windows/Fonts/ariblk.ttf"),
    Path("C:/Windows/Fonts/seguibl.ttf"),
]
CANDIDATAS_REGULAR = [
    Path("C:/Program Files/Android/Android Studio/plugins/design-tools/resources/layoutlib/data/fonts/Roboto-Regular.ttf"),
    Path("C:/Windows/Fonts/segoeui.ttf"),
    Path("C:/Windows/Fonts/arial.ttf"),
]


def cargar_fuente(candidatas, tamano, peso=None):
    """Primera fuente que exista. Si es variable y se pide peso, lo aplica.

    Ojo: `set_variation_by_axes` recibe TODOS los ejes en orden, no solo el que
    a uno le interesa. En RobotoFlex el peso es el eje 1 (el 0 es Optical Size),
    así que hay que armar la lista completa a partir de los valores por defecto.
    """
    for ruta in candidatas:
        if not ruta.exists():
            continue
        fuente = ImageFont.truetype(str(ruta), tamano)
        if peso is not None:
            try:
                ejes = fuente.get_variation_axes()
                valores = [eje["default"] for eje in ejes]
                for i, eje in enumerate(ejes):
                    nombre = eje["name"]
                    nombre = nombre.decode() if isinstance(nombre, bytes) else nombre
                    if nombre == "Weight":
                        valores[i] = peso
                    elif nombre == "Optical Size":
                        valores[i] = eje["maximum"]  # tamaño de display
                fuente.set_variation_by_axes(valores)
            except Exception:
                pass  # no es variable: se usa tal cual
        return fuente
    raise SystemExit("No encontré ninguna fuente utilizable")


def ancho_de(draw, texto, fuente):
    caja = draw.textbbox((0, 0), texto, font=fuente)
    return caja[2] - caja[0]


def main():
    if not FUENTE_ARTE.exists():
        raise SystemExit(f"No encuentro el arte fuente: {FUENTE_ARTE}")

    lienzo = Image.new("RGB", (ANCHO, ALTO), PAPEL)
    draw = ImageDraw.Draw(lienzo)

    # --- Disco verde con la etiqueta, a la izquierda ---
    diametro = 300
    cx, cy = 210, ALTO // 2
    draw.ellipse(
        (cx - diametro // 2, cy - diametro // 2, cx + diametro // 2, cy + diametro // 2),
        fill=VERDE,
    )

    original = Image.open(FUENTE_ARTE).convert("RGB")
    etiqueta = original.convert("RGBA")
    etiqueta.putalpha(silueta_de_la_etiqueta(original))
    etiqueta = recortar(etiqueta)

    ancho_etiqueta = 196
    escala = ancho_etiqueta / etiqueta.width
    etiqueta = etiqueta.resize(
        (ancho_etiqueta, max(1, round(etiqueta.height * escala))), Image.LANCZOS
    )
    lienzo.paste(etiqueta, (cx - etiqueta.width // 2, cy - etiqueta.height // 2), etiqueta)

    # --- Wordmark y bajada, a la derecha ---
    x = 420
    wordmark = cargar_fuente(CANDIDATAS_BLACK, 116, peso=900)
    bajada = cargar_fuente(CANDIDATAS_REGULAR, 38)
    chip_fuente = cargar_fuente(CANDIDATAS_REGULAR, 26)

    draw.text((x, 150), "góndola", font=wordmark, fill=VERDE)
    draw.text((x + 4, 288), "Precios de súper en Tandil", font=bajada, fill=TINTA)

    # Chip «Tandil»: es dato, no decoración (ver MARCA-E-ICONO.md)
    texto_chip = "Comparás · Escaneás · Ahorrás"
    ancho_chip = ancho_de(draw, texto_chip, chip_fuente)
    cx0, cy0 = x + 4, 344
    draw.rounded_rectangle(
        (cx0, cy0, cx0 + ancho_chip + 40, cy0 + 48), radius=24, fill=CONTAINER
    )
    draw.text((cx0 + 20, cy0 + 10), texto_chip, font=chip_fuente, fill=ON_CONTAINER)

    lienzo.save(SALIDA, "PNG")
    print(f"Listo: {SALIDA} ({ANCHO}x{ALTO})")


if __name__ == "__main__":
    main()
