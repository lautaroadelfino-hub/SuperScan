#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
clasificar_categorias.py - Reclasifica el catálogo con Gemini.

La categorización vieja se hizo buscando palabras sueltas en la descripción,
sin entender el producto: "ALFAJOR DE ARROZ" caía en Arroz y legumbres, un
plumero "FLEX AND CATCH" caía en Mascotas, y 8.303 productos (34%) quedaron
en "Sin clasificar" porque ninguna palabra les pegó.

Este script manda los productos a Gemini en lotes, con la taxonomía fija de
abajo, y le pide que entienda QUÉ ES cada producto. Guarda el resultado en
categorias_gemini.json (resumible: se puede cortar y retomar).

Uso:
    python clasificar_categorias.py --muestra 60   # prueba: no escribe nada
    python clasificar_categorias.py                # clasifica todo (no escribe)
    python clasificar_categorias.py --aplicar      # además, escribe en Firestore
"""
import json
import os
import re
import sys
import time
from pathlib import Path

import requests

CARPETA = Path(__file__).resolve().parent
RAIZ = CARPETA.parent
CREDENCIALES = CARPETA / "credenciales.json"
CACHE_TANDIL = CARPETA / "tandil_nombres_cache.json"
CATALOGO = CARPETA / "catalogo_final_firestore.csv"
ESTADO = CARPETA / "categorias_gemini.json"

MODELO = "gemini-3.5-flash"   # el que usa la app; NO cambiar
LOTE = 40                     # productos por pedido
PAUSA = 1.0                   # segundos entre pedidos
# El modelo gasta parte del presupuesto de salida razonando antes de responder;
# con 8192 el JSON volvía cortado a la mitad. Holgado para que nunca trunque.
MAX_TOKENS = 32768

TAXONOMIA = {
    "Almacén": ["Aceites y vinagres", "Arroz y legumbres", "Pastas secas",
                "Harinas y repostería", "Conservas", "Salsas y aderezos",
                "Condimentos y especias", "Sopas y caldos"],
    "Desayuno y merienda": ["Yerba mate", "Café", "Té e infusiones", "Galletitas",
                            "Cereales y barras", "Mermeladas y dulces", "Endulzantes"],
    "Kiosco": ["Golosinas", "Chocolates", "Alfajores", "Snacks"],
    "Bebidas sin alcohol": ["Gaseosas", "Aguas", "Jugos", "Isotónicas y energizantes"],
    "Bebidas con alcohol": ["Vinos", "Cervezas", "Espirituosas", "Aperitivos y espumantes"],
    "Frescos": ["Lácteos", "Quesos", "Fiambres", "Huevos", "Carnes y pescados",
                "Frutas y verduras", "Pan y panificados"],
    "Congelados": ["Congelados salados", "Helados"],
    "Limpieza": ["Ropa", "Cocina y vajilla", "Baño y hogar", "Papeles",
                 "Insecticidas y aromatizantes"],
    "Cuidado personal": ["Cabello", "Cuidado corporal", "Higiene bucal", "Afeitado",
                         "Higiene femenina", "Farmacia y botiquín"],
    "Bebés": ["Pañales", "Alimentación infantil", "Higiene del bebé"],
    "Mascotas": ["Perros", "Gatos", "Otras mascotas"],
    # El bloque no-alimenticio del hipermercado es enorme (1 de cada 3
    # productos): se reparte en tres categorías navegables en vez de una sola.
    "Hogar y bazar": ["Bazar y cocina", "Textil hogar", "Indumentaria"],
    "Ferretería y electro": ["Ferretería y electricidad", "Electro y pilas"],
    "Librería y juguetería": ["Librería", "Juguetería"],
}
VALIDAS = {f"{c}>{s}" for c, subs in TAXONOMIA.items() for s in subs}


def api_key():
    for linea in (RAIZ / ".env").read_text(encoding="utf-8").splitlines():
        if linea.startswith("GEMINI_API_KEY"):
            return linea.split("=", 1)[1].strip().strip('"').strip("'")
    raise SystemExit("Falta GEMINI_API_KEY en .env")


def norm_ean(raw):
    d = "".join(c for c in (raw or "") if c.isdigit())
    return d.zfill(13) if 8 <= len(d) <= 13 else None


def cargar_productos():
    import csv
    en_tandil = set(json.loads(CACHE_TANDIL.read_text(encoding="utf-8")))
    productos = []
    with open(CATALOGO, encoding="utf-8-sig") as f:
        for fila in csv.DictReader(f):
            ean = norm_ean(fila.get("ean"))
            if ean and ean in en_tandil:
                productos.append((ean,
                                  (fila.get("descripcion") or "").strip(),
                                  (fila.get("marca") or "").strip()))
    return productos


def armar_prompt(lote):
    arbol = "\n".join(f"- {c}: {' | '.join(subs)}" for c, subs in TAXONOMIA.items())
    listado = "\n".join(
        f"{i+1}. {desc}" + (f"  [marca: {marca}]" if marca else "")
        for i, (_ean, desc, marca) in enumerate(lote)
    )
    return f"""Sos un clasificador de productos de supermercado argentino.

Categorías y subcategorías permitidas:
{arbol}

Clasificá cada producto entendiendo QUÉ ES, no por las palabras que contiene.
Ejemplos de la trampa a evitar:
- "ALFAJOR DE ARROZ DULCE DE LECHE" es un alfajor (Kiosco > Alfajores), NO arroz.
- "ARROZ CON LECHE POTE" es un postre lácteo (Frescos > Lácteos), NO arroz.
- "PLUMERO FLEX AND CATCH" es un utensilio de limpieza, NO algo de mascotas.
- "ALIMENTO A BASE DE VEGETALES HOT DOG" es comida para personas, NO para mascotas.

Productos:
{listado}

Respondé SOLO un objeto JSON, sin explicaciones ni markdown, con el número de
cada producto como clave y "Categoría>Subcategoría" como valor. Ejemplo:
{{"1": "Kiosco>Alfajores", "2": "Frescos>Lácteos"}}
Usá exactamente los nombres de la lista permitida. Clasificá los {len(lote)} productos."""


def pedir(sesion, key, lote):
    url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
           f"{MODELO}:generateContent?key={key}")
    cuerpo = {
        "contents": [{"parts": [{"text": armar_prompt(lote)}]}],
        "generationConfig": {"temperature": 0, "maxOutputTokens": MAX_TOKENS},
    }
    for intento in range(3):
        try:
            r = sesion.post(url, json=cuerpo, timeout=180)
            if r.status_code != 200:
                print(f"    HTTP {r.status_code}: {r.text[:120]}", flush=True)
                time.sleep(5 * (intento + 1))
                continue
            texto = r.json()["candidates"][0]["content"]["parts"][0]["text"]
            texto = re.sub(r"^```(?:json)?|```$", "", texto.strip(), flags=re.MULTILINE).strip()
            return json.loads(texto)
        except Exception as e:
            print(f"    intento {intento+1} falló: {type(e).__name__} {str(e)[:90]}", flush=True)
            time.sleep(5 * (intento + 1))
    return None


def clasificar_lote(sesion, key, lote, profundidad=0):
    """Devuelve {ean: 'Cat>Sub'}. Si la respuesta viene rota (el modelo a veces
    trunca), parte el lote al medio y reintenta: mejor perder unos pocos
    productos que abortar toda la corrida."""
    respuesta = pedir(sesion, key, lote)
    if respuesta is None:
        if len(lote) > 5 and profundidad < 3:
            mitad = len(lote) // 2
            print(f"    partiendo lote de {len(lote)} en dos y reintentando", flush=True)
            return {**clasificar_lote(sesion, key, lote[:mitad], profundidad + 1),
                    **clasificar_lote(sesion, key, lote[mitad:], profundidad + 1)}
        print(f"    se descartan {len(lote)} productos de este lote", flush=True)
        return {}
    salida = {}
    for n, (ean, _d, _m) in enumerate(lote, start=1):
        valor = (respuesta.get(str(n)) or "").strip()
        if valor in VALIDAS:
            salida[ean] = valor
    return salida


def main():
    global LOTE
    aplicar = "--aplicar" in sys.argv
    muestra = 0
    if "--muestra" in sys.argv:
        muestra = int(sys.argv[sys.argv.index("--muestra") + 1])
    # Lotes más chicos para las pasadas de recuperación: cuanto más corta la
    # respuesta, menos probable que el modelo la trunque.
    if "--lote" in sys.argv:
        LOTE = int(sys.argv[sys.argv.index("--lote") + 1])

    productos = cargar_productos()
    estado = json.loads(ESTADO.read_text(encoding="utf-8")) if ESTADO.exists() else {}
    pendientes = [p for p in productos if p[0] not in estado]
    if muestra:
        pendientes = pendientes[:muestra]
    print(f"{len(productos)} productos visibles | {len(estado)} ya clasificados | "
          f"{len(pendientes)} en esta corrida")

    if pendientes:
        key, sesion = api_key(), requests.Session()
        descartados = 0
        for i in range(0, len(pendientes), LOTE):
            lote = pendientes[i:i + LOTE]
            obtenidos = clasificar_lote(sesion, key, lote)
            estado.update(obtenidos)
            descartados += len(lote) - len(obtenidos)
            ESTADO.write_text(json.dumps(estado, ensure_ascii=False), encoding="utf-8")
            print(f"  {min(i+LOTE, len(pendientes))}/{len(pendientes)} "
                  f"(clasificados {len(estado)}, descartados {descartados})", flush=True)
            time.sleep(PAUSA)

    # Resumen por categoría
    from collections import Counter
    dist = Counter(v.split(">")[0] for v in estado.values())
    print("\nDistribución resultante:")
    for c, n in dist.most_common():
        print(f"   {c}: {n}")

    if muestra or not aplicar:
        print("\n--- 15 ejemplos ---")
        por_ean = {e: (d, m) for e, d, m in productos}
        for ean, valor in list(estado.items())[-15:]:
            d = por_ean.get(ean, ("", ""))[0]
            print(f"   {d[:52]:<54} -> {valor}")
        print("\n(no se escribió en Firestore; usar --aplicar)")
        return

    import firebase_admin
    from firebase_admin import credentials, firestore
    firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
    db = firestore.client()
    print(f"\nEscribiendo {len(estado)} productos en Firestore...")
    lote_fs, n, escritos = db.batch(), 0, 0
    for ean, valor in estado.items():
        cat, sub = valor.split(">", 1)
        lote_fs.set(db.collection("productos").document(ean),
                    {"categoria": cat, "subcategoria": sub}, merge=True)
        n += 1
        if n == 400:
            lote_fs.commit()
            escritos += n
            n, lote_fs = 0, db.batch()
            if escritos % 4000 == 0:
                print(f"  {escritos}/{len(estado)}...", flush=True)
    if n:
        lote_fs.commit()
        escritos += n
    print(f"Listo: {escritos} productos reclasificados.")


if __name__ == "__main__":
    main()
