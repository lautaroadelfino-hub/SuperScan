#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
subir_catalogo_v2.py - Carga el catalogo final a Firestore con la estructura nueva.

Estructura de cada documento (ID = EAN de 13 digitos):

    productos/{ean} = {
        ean, descripcion, marca, categoria, subcategoria,
        tokens: ["FIDEOS", "SIN", "GLUTEN", "500G"],       # busqueda por palabra
        precios: { vea: 1942.0, carrefour: 1909.0, ... },   # solo las cadenas con precio
        precio_min: 1909.0, cadena_min: "carrefour",
        precio_publico: None, precio_publico_n: 0,          # lo informado por usuarios
        imagen: None,                                        # a futuro
        revisar: True/False,                                 # dato dudoso (dif > 80%)
        actualizado: <timestamp del servidor>
    }

Uso: poner junto a credenciales.json y catalogo_final_firestore.csv, y correr:
    python subir_catalogo_v2.py

NO abrir el CSV con Excel antes de subirlo: rompe los codigos de barra.
"""
import csv
import sys

import firebase_admin
from firebase_admin import credentials, firestore

from tokens_busqueda import tokenizar

try:
    from google.api_core.exceptions import ResourceExhausted
except ImportError:
    ResourceExhausted = Exception

ARCHIVO = "catalogo_final_firestore.csv"
CREDENCIALES = "credenciales.json"
IMAGENES_OFF_JSON = "imagenes_off.json"    # estado de enriquecer_imagenes.py
IMAGENES_VTEX_JSON = "imagenes_vtex.json"  # estado de importar_imagenes_vtex.py
COLECCION = "productos"
LOTE = 450
DIF_SOSPECHOSA = 80.0
EAN_PRUEBA = "7791813434412"

# columnas de precio del CSV -> nombre de la cadena dentro del mapa 'precios'
CADENAS = {
    "precio_vea": "vea",
    "precio_carrefour": "carrefour",
    "precio_coop_obrera": "coop_obrera",
    "precio_dia": "dia",
}


def num(v):
    try:
        f = float(v)
        return f if f > 0 else None
    except (TypeError, ValueError):
        return None


def reparar_fila(crudo, campos):
    """Recupera filas doblemente encomilladas (toda la linea quedo dentro de
    comillas y DictReader la leyo como un unico campo). El contenido interno
    es CSV valido: se re-parsea y se mapea a las columnas."""
    try:
        valores = next(csv.reader([crudo]))
    except (csv.Error, StopIteration):
        return None
    if len(valores) != len(campos):
        return None
    return dict(zip(campos, valores))


def armar_doc(fila):
    ean = "".join(c for c in (fila.get("ean") or "") if c.isdigit()).zfill(13)
    if len(ean) != 13:
        return None, None

    precios = {}
    for col, cadena in CADENAS.items():
        p = num(fila.get(col))
        if p is not None:
            precios[cadena] = p

    descripcion = (fila.get("descripcion") or "").strip()
    marca = (fila.get("marca") or "").strip()

    doc = {
        "ean": ean,
        "descripcion": descripcion,
        "marca": marca,
        # Palabras sueltas para que la app encuentre "SIN GLUTEN" aunque este
        # en el medio del nombre (Firestore solo busca prefijos).
        "tokens": tokenizar(descripcion, marca),
        "categoria": (fila.get("categoria") or "Sin clasificar").strip(),
        "subcategoria": (fila.get("subcategoria") or "Sin clasificar").strip(),
        "precios": precios,
        "precio_min": num(fila.get("precio_min")),
        "cadena_min": (fila.get("cadena_min") or "").strip() or None,
        "precio_publico": None,      # se completa con las observaciones de usuarios
        "precio_publico_n": 0,
        "imagen": None,              # se completa desde imagenes_off/imagenes_vtex
        "imagen_grande": None,
        "imagen_fuente": None,
        "revisar": (num(fila.get("dif_pct")) or 0) > DIF_SOSPECHOSA,
        "actualizado": firestore.SERVER_TIMESTAMP,
    }
    return ean, doc


def borrar(db):
    print(f"Borrando '{COLECCION}'...")
    total = 0
    while True:
        docs = list(db.collection(COLECCION).limit(LOTE).select([]).stream())
        if not docs:
            break
        b = db.batch()
        for d in docs:
            b.delete(d.reference)
        try:
            b.commit()
        except ResourceExhausted:
            print(f"\nTope diario alcanzado ({total} borrados). Reintentar maniana o activar Blaze.")
            sys.exit(1)
        total += len(docs)
        print(f"-> {total} borrados...")
    print(f"Limpieza lista: {total}\n")


def cargar_imagenes():
    """Imagenes ya encontradas (OFF y VTEX): se incluyen en la subida para que
    un re-upload no las pise con None. VTEX (foto de estudio) tiene prioridad
    sobre OFF. Devuelve {ean: (imagen, imagen_grande, fuente)}."""
    import json
    import os
    res = {}
    if os.path.exists(IMAGENES_OFF_JSON):  # menor prioridad
        for ean, v in json.load(open(IMAGENES_OFF_JSON, encoding="utf-8")).items():
            if v[0] or v[1]:
                res[ean] = (v[0] or v[1], v[1], "off")
    if os.path.exists(IMAGENES_VTEX_JSON):  # pisa a OFF
        for ean, v in json.load(open(IMAGENES_VTEX_JSON, encoding="utf-8")).items():
            if v[0]:
                res[ean] = (v[0], v[0], v[1])
    return res


def subir(db):
    imagenes = cargar_imagenes()
    if imagenes:
        print(f"({len(imagenes)} imagenes de Open Food Facts se incluyen en la subida)")
    with open(ARCHIVO, encoding="utf-8-sig") as f:
        lector = csv.DictReader(f, delimiter=",")
        if not lector.fieldnames or "ean" not in lector.fieldnames:
            print("ERROR: no se detecto la columna 'ean'. Columnas:", lector.fieldnames)
            sys.exit(1)

        b = db.batch()
        en_lote = subidos = salteados = con_precio = reparadas = 0
        arbol = {}  # categoria -> subcategoria -> set de marcas (para catalogo_meta)
        print(f"Subiendo a '{COLECCION}' (ID del documento = EAN)...")

        for fila in lector:
            # Fila doblemente encomillada: todo quedo en 'ean' y el resto en None
            if fila.get("descripcion") is None and "," in (fila.get("ean") or ""):
                fila = reparar_fila(fila["ean"], lector.fieldnames)
                if fila is None:
                    salteados += 1
                    continue
                reparadas += 1
            ean, doc = armar_doc(fila)
            if not ean:
                salteados += 1
                continue
            if ean in imagenes:
                doc["imagen"], doc["imagen_grande"], doc["imagen_fuente"] = imagenes[ean]
            if doc["precios"]:
                con_precio += 1
            marcas = arbol.setdefault(doc["categoria"], {}).setdefault(doc["subcategoria"], set())
            if doc["marca"]:
                marcas.add(doc["marca"])
            b.set(db.collection(COLECCION).document(ean), doc)
            en_lote += 1

            if en_lote == LOTE:
                try:
                    b.commit()
                except ResourceExhausted:
                    print(f"\nTope diario alcanzado ({subidos} subidos).")
                    print("Volve a correr el script maniana: retoma sin duplicar (el ID es el EAN).")
                    sys.exit(1)
                subidos += en_lote
                print(f"-> {subidos} productos...")
                b, en_lote = db.batch(), 0

        if en_lote:
            b.commit()
            subidos += en_lote

    print(f"\nListo: {subidos} productos ({con_precio} con precios de Tandil), "
          f"{reparadas} filas reparadas, {salteados} salteados.")
    subir_estructura(db, arbol)


def subir_estructura(db, arbol):
    """catalogo_meta/estructura: el arbol categorias -> subcategorias -> marcas
    que la app usa para la grilla del catalogo. Las reglas lo dejan solo-lectura
    para los usuarios; el Admin SDK no pasa por las reglas."""
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
    db.collection("catalogo_meta").document("estructura").set(estructura)
    n_subs = sum(len(c["subcategorias"]) for c in estructura["categorias"])
    print(f"catalogo_meta/estructura actualizado: "
          f"{len(estructura['categorias'])} categorias, {n_subs} subcategorias.")


def verificar(db):
    print(f"\nVerificando {EAN_PRUEBA}...")
    d = db.collection(COLECCION).document(EAN_PRUEBA).get()
    if not d.exists:
        print("No se encontro. Revisar la carga.")
        return
    for k, v in d.to_dict().items():
        print(f"   {k}: {v}")


if __name__ == "__main__":
    firebase_admin.initialize_app(credentials.Certificate(CREDENCIALES))
    db = firestore.client()
    print("1 = borrar coleccion | 2 = subir | 3 = borrar y subir (recomendado)")
    op = input("Opcion: ").strip()
    if op in ("1", "3"):
        borrar(db)
    if op in ("2", "3"):
        subir(db)
        verificar(db)
