#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ajustar_taxonomia.py - Parte la categoría "Hogar y bazar" (quedó con 8.130
productos, 1 de cada 3 del catálogo) en tres categorías navegables, y separa
la ropa de los textiles de hogar.

Cinco de las seis subcategorías solo cambian de categoría madre (remapeo
mecánico, gratis). La única que necesita a Gemini es "Textil hogar", donde
hoy conviven alfombras y sábanas con buzos y medias, porque el árbol anterior
no tenía dónde poner la indumentaria.

Uso:
    python ajustar_taxonomia.py     # remapea y reclasifica textiles
"""
import csv
import json
import sys
import time
from pathlib import Path

import requests

CARPETA = Path(__file__).resolve().parent
ESTADO = CARPETA / "categorias_gemini.json"
CATALOGO = CARPETA / "catalogo_final_firestore.csv"

sys.path.insert(0, str(CARPETA))
from clasificar_categorias import api_key, MODELO, norm_ean  # noqa: E402

REMAPEO = {
    "Hogar y bazar>Ferretería y electricidad": "Ferretería y electro>Ferretería y electricidad",
    "Hogar y bazar>Electro y pilas": "Ferretería y electro>Electro y pilas",
    "Hogar y bazar>Librería": "Librería y juguetería>Librería",
    "Hogar y bazar>Juguetería": "Librería y juguetería>Juguetería",
}
LOTE = 25


def reclasificar_textiles(sesion, key, lote):
    """Separa ropa (Indumentaria) de textiles de hogar (sábanas, toallas,
    alfombras, cortinas)."""
    listado = "\n".join(f"{i+1}. {d}" for i, (_e, d) in enumerate(lote))
    prompt = f"""Clasificá cada producto de supermercado en UNA de estas dos opciones:

- "Textil hogar": sábanas, toallas, alfombras, cortinas, manteles, almohadas,
  acolchados, repasadores y similares para la casa.
- "Indumentaria": ropa y calzado para personas — buzos, remeras, pantalones,
  medias, ropa interior, camperas, pijamas, zapatillas, gorras.

Productos:
{listado}

Respondé SOLO un JSON con el número como clave y la opción como valor.
Ejemplo: {{"1": "Indumentaria", "2": "Textil hogar"}}"""
    url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
           f"{MODELO}:generateContent?key={key}")
    cuerpo = {"contents": [{"parts": [{"text": prompt}]}],
              "generationConfig": {"temperature": 0, "maxOutputTokens": 32768}}
    import re
    for _ in range(3):
        try:
            r = sesion.post(url, json=cuerpo, timeout=180)
            if r.status_code != 200:
                time.sleep(5)
                continue
            t = r.json()["candidates"][0]["content"]["parts"][0]["text"]
            t = re.sub(r"^```(?:json)?|```$", "", t.strip(), flags=re.MULTILINE).strip()
            return json.loads(t)
        except Exception:
            time.sleep(5)
    return None


def main():
    estado = json.loads(ESTADO.read_text(encoding="utf-8"))

    # 1) Remapeo mecánico
    movidos = 0
    for ean, valor in list(estado.items()):
        if valor in REMAPEO:
            estado[ean] = REMAPEO[valor]
            movidos += 1
    print(f"Remapeados a categoría propia: {movidos}")

    # 2) Reclasificar los textiles para separar la ropa
    descripciones = {}
    with open(CATALOGO, encoding="utf-8-sig") as f:
        for fila in csv.DictReader(f):
            e = norm_ean(fila.get("ean"))
            if e:
                descripciones[e] = (fila.get("descripcion") or "").strip()

    textiles = [(e, descripciones.get(e, "")) for e, v in estado.items()
                if v == "Hogar y bazar>Textil hogar" and descripciones.get(e)]
    print(f"Textiles a revisar (ropa vs hogar): {len(textiles)}")

    key, sesion = api_key(), requests.Session()
    a_indumentaria = 0
    for i in range(0, len(textiles), LOTE):
        lote = textiles[i:i + LOTE]
        resp = reclasificar_textiles(sesion, key, lote)
        if resp is None:
            print(f"  lote {i} sin respuesta, se deja como estaba")
            continue
        for n, (ean, _d) in enumerate(lote, start=1):
            opcion = (resp.get(str(n)) or "").strip()
            if opcion == "Indumentaria":
                estado[ean] = "Hogar y bazar>Indumentaria"
                a_indumentaria += 1
            elif opcion == "Textil hogar":
                estado[ean] = "Hogar y bazar>Textil hogar"
        ESTADO.write_text(json.dumps(estado, ensure_ascii=False), encoding="utf-8")
        print(f"  {min(i+LOTE, len(textiles))}/{len(textiles)} "
              f"(a Indumentaria: {a_indumentaria})", flush=True)
        time.sleep(1)

    from collections import Counter
    dist = Counter(v.split(">")[0] for v in estado.values())
    print("\nDistribución final por categoría:")
    for c, n in dist.most_common():
        print(f"   {c}: {n}")


if __name__ == "__main__":
    main()
