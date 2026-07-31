#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
regenerar_estructura_tandil.py - Reconstruye catalogo_meta/estructura contando
SOLO los productos visibles en la app (en_tandil = true).

Si el árbol se genera con todo el catálogo, la grilla muestra categorías,
subcategorías y marcas que al entrar aparecen vacías, porque sus productos
están ocultos. Este script lo alinea con lo que la app realmente muestra.

Correr DESPUÉS de corregir_nombres.py --aplicar.
"""
import json
from pathlib import Path

CARPETA = Path(__file__).resolve().parent
CREDENCIALES = CARPETA / "credenciales.json"
CACHE = CARPETA / "tandil_nombres_cache.json"
CATALOGO = CARPETA / "catalogo_final_firestore.csv"


def norm(raw):
    d = "".join(c for c in (raw or "") if c.isdigit())
    return d.zfill(13) if 8 <= len(d) <= 13 else None


CATEGORIAS = CARPETA / "categorias_gemini.json"


def main():
    import csv
    if not CACHE.exists():
        raise SystemExit("Falta tandil_nombres_cache.json: correr antes corregir_nombres.py")
    en_tandil = set(json.loads(CACHE.read_text(encoding="utf-8")))
    # Las categorías buenas son las de la reclasificación con Gemini; el CSV
    # todavía tiene el árbol viejo (hecho por palabras clave).
    nuevas = json.loads(CATEGORIAS.read_text(encoding="utf-8")) if CATEGORIAS.exists() else {}

    arbol = {}
    incluidos = 0
    with open(CATALOGO, encoding="utf-8-sig") as f:
        for fila in csv.DictReader(f):
            ean = norm(fila.get("ean"))
            if ean is None or ean not in en_tandil:
                continue
            if ean in nuevas:
                cat, sub = nuevas[ean].split(">", 1)
            elif nuevas:
                continue  # sin clasificar por Gemini: no se muestra en el árbol
            else:
                cat = (fila.get("categoria") or "Sin clasificar").strip()
                sub = (fila.get("subcategoria") or "Sin clasificar").strip()
            incluidos += 1
            marca = (fila.get("marca") or "").strip().upper()
            marcas = arbol.setdefault(cat, {}).setdefault(sub, set())
            if marca:
                marcas.add(marca)

    estructura = {
        "categorias": [
            {
                "nombre": cat,
                "subcategorias": [
                    {"nombre": sub, "marcas": sorted(marcas)}
                    for sub, marcas in sorted(subs.items())
                ],
            }
            for cat, subs in sorted(arbol.items())
        ]
    }

    import firebase_admin
    from firebase_admin import credentials, firestore
    firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
    db = firestore.client()
    db.collection("catalogo_meta").document("estructura").set(estructura)

    n_sub = sum(len(c["subcategorias"]) for c in estructura["categorias"])
    print(f"estructura regenerada con {incluidos} productos de Tandil: "
          f"{len(estructura['categorias'])} categorías, {n_sub} subcategorías")
    for c in estructura["categorias"]:
        print(f"   {c['nombre']}: {len(c['subcategorias'])} subcategorías")


if __name__ == "__main__":
    main()
