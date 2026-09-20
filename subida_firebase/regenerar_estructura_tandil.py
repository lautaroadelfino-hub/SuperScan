#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
regenerar_estructura_tandil.py - Reconstruye catalogo_meta/estructura (el arbol
categorias -> subcategorias -> marcas que navega la app) contando SOLO los
productos visibles: en_tandil = true.

Si el arbol se genera con todo el catalogo, la grilla muestra categorias y
marcas que al entrar aparecen vacias, porque sus productos estan ocultos.

Lee de FIRESTORE, no del CSV. Antes lo armaba desde catalogo_final_firestore.csv
+ categorias_gemini.json, pero desde que hay scripts que escriben directo en la
base (actualizar_precios.py, altas_nuevas.py) ese CSV es una foto vieja:
regenerar desde ahi borraria del arbol los productos dados de alta despues.

Correr DESPUES de cualquier script que agregue productos o cambie categorias.

Uso:
    python regenerar_estructura_tandil.py            # simulacion
    python regenerar_estructura_tandil.py --aplicar
    python regenerar_estructura_tandil.py --desde-cache --aplicar

Con --desde-cache se arma el arbol desde la foto local que deja
actualizar_precios.py --cache, sin leer un solo documento de Firestore. Es lo
que hace falta para que el refresco entre en la cuota del plan Spark.
"""
import sys
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, firestore

import cache_catalogo

CARPETA = Path(__file__).resolve().parent
CREDENCIALES = CARPETA / "credenciales.json"
SIN_CLASIFICAR = "Sin clasificar"


def desde_firestore(db):
    """(precios, categoria, subcategoria, marca) de cada producto visible."""
    consulta = (db.collection("productos")
                  .where("en_tandil", "==", True)
                  .select(["categoria", "subcategoria", "marca", "precios"]))
    for doc in consulta.stream():
        d = doc.to_dict() or {}
        yield ({c: v for c, v in (d.get("precios") or {}).items() if v and v > 0},
               (d.get("categoria") or "").strip(),
               (d.get("subcategoria") or "").strip(),
               (d.get("marca") or "").strip().upper())


def desde_cache(cache):
    """Lo mismo, pero de la foto local: cero lecturas de Firestore.

    La foto la deja actualizar_precios.py con los precios ya aplicados, y trae
    estos cuatro campos porque pedirlos en el mismo select() no costaba una
    lectura mas (Firestore cobra por documento, no por campo)."""
    for precios, cat, sub, marca, en_tandil in cache["docs"].values():
        if en_tandil:
            yield precios, cat, sub, marca


def main():
    aplicar = "--aplicar" in sys.argv
    usar_cache = "--desde-cache" in sys.argv

    db = None
    if usar_cache:
        cache = cache_catalogo.cargar()
        if cache is None or not cache.get("completa"):
            print("No hay una foto completa del catalogo. Corre primero "
                  "actualizar_precios.py --cache, o sacá --desde-cache.")
            sys.exit(2)
        print(f"Leyendo los productos visibles de la foto local "
              f"({len(cache['docs'])} documentos, 0 lecturas)...")
        fuente = desde_cache(cache)
    else:
        if not firebase_admin._apps:
            firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
        db = firestore.client()
        print("Leyendo los productos visibles de Firestore (en_tandil = true)...")
        fuente = desde_firestore(db)

    arbol = {}
    incluidos = descartados = 0
    # De paso se cuenta la cobertura: la app la muestra tal cual es, y el
    # numero honesto es "productos CON PRECIO", no el total de la base (que
    # incluye lo que no se consigue en Tandil).
    visibles = con_precio = precios_totales = 0
    por_cadena = {}
    for precios, cat, sub, marca in fuente:
        visibles += 1
        if precios:
            con_precio += 1
            precios_totales += len(precios)
            for c in precios:
                por_cadena[c] = por_cadena.get(c, 0) + 1
        # Una categoria vacia o "Sin clasificar" en la grilla es una puerta a
        # una bolsa de gatos: no entra al arbol.
        if not cat or not sub or cat == SIN_CLASIFICAR or sub == SIN_CLASIFICAR:
            descartados += 1
            continue
        incluidos += 1
        marcas = arbol.setdefault(cat, {}).setdefault(sub, set())
        if marca:
            marcas.add(marca)
        if (incluidos + descartados) % 20000 == 0:
            print(f"  {incluidos + descartados} leidos...", flush=True)

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

    n_sub = sum(len(c["subcategorias"]) for c in estructura["categorias"])
    n_marcas = sum(len(s["marcas"]) for c in estructura["categorias"] for s in c["subcategorias"])
    print(f"\nProductos visibles incluidos: {incluidos}")
    print(f"Descartados por no tener categoria: {descartados}")
    print(f"Arbol: {len(estructura['categorias'])} categorias, "
          f"{n_sub} subcategorias, {n_marcas} marcas")
    for c in estructura["categorias"]:
        print(f"   {c['nombre']}: {len(c['subcategorias'])} subcategorias")

    print(f"\nCobertura (lo que la app muestra como su valor):")
    print(f"   productos visibles   : {visibles}")
    print(f"   con al menos 1 precio: {con_precio}")
    print(f"   precios cargados     : {precios_totales}")
    for c, n in sorted(por_cadena.items(), key=lambda kv: -kv[1]):
        print(f"      {c:12} {n}")

    if not aplicar:
        print("\n(simulacion: no se escribio nada. Correr con --aplicar)")
        return

    if db is None:
        if not firebase_admin._apps:
            firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
        db = firestore.client()

    db.collection("catalogo_meta").document("estructura").set(estructura)
    # merge: la fecha de los datos la escribe actualizar_precios.py en el mismo
    # documento. Cada script pone lo que sabe.
    db.collection("catalogo_meta").document("precios").set({
        "productos_visibles": visibles,
        "productos_con_precio": con_precio,
        "precios_totales": precios_totales,
        "por_cadena": por_cadena,
    }, merge=True)
    print("\ncatalogo_meta/estructura y catalogo_meta/precios actualizados.")


if __name__ == "__main__":
    main()
