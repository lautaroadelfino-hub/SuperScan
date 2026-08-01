#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
altas_nuevas.py - Da de alta en el catalogo los productos que aparecieron en
una descarga nueva de SEPA y todavia no estaban.

actualizar_precios.py deliberadamente NO los da de alta: un producto sin
categoria no se puede navegar en la app. Este script completa ese paso:

  1. saca descripcion, marca y precios de los CSV de SEPA (sucursal de Tandil),
     con los MISMOS criterios que corregir_nombres.py (mejor descripcion entre
     las cadenas, marca mas completa, deteccion de precios outlier);
  2. les pide la categoria a Gemini con la taxonomia de clasificar_categorias.py;
  3. escribe los documentos nuevos en `productos` con en_tandil=true y tokens.

Los productos que Gemini no logra clasificar NO se suben: entrarian al catalogo
como "Sin clasificar" y ensuciarian la navegacion. Quedan para la proxima.

Despues de aplicar hay que regenerar el arbol de navegacion:
    python regenerar_estructura_tandil.py

Uso (simula por defecto, no escribe nada):
    python altas_nuevas.py --datos "Datos 2026-07-31"
    python altas_nuevas.py --datos "Datos 2026-07-31" --aplicar
"""
import json
import sys
import time
from pathlib import Path

import firebase_admin
import requests
from firebase_admin import credentials, firestore

from actualizar_precios import COMERCIOS, carpeta_de, norm_ean, num
from clasificar_categorias import LOTE, PAUSA, api_key, clasificar_lote
from corregir_nombres import elegir_descripcion, elegir_marca, es_outlier, limpiar_desc, ESPACIOS
from tokens_busqueda import tokenizar

try:
    from google.api_core.exceptions import ResourceExhausted
except ImportError:
    ResourceExhausted = Exception

CARPETA = Path(__file__).resolve().parent
CREDENCIALES = CARPETA / "credenciales.json"
ESTADO = CARPETA / "categorias_altas.json"   # resumible: se puede cortar y retomar
COLECCION = "productos"
LOTE_ESCRITURA = 400

# Columnas SEPA (base 0), iguales que en el resto del pipeline
COL_SUCURSAL, COL_EAN, COL_DESC, COL_MARCA, COL_PRECIO = 2, 3, 5, 8, 9


def es_codigo_global(ean):
    """True si el codigo identifica al producto en CUALQUIER comercio.

    GS1 reserva los prefijos 20-29 ("distribucion restringida") para que cada
    comercio numere lo que vende por peso: fiambres, quesos, carne, verduras.
    Ese numero se lo inventa el super, asi que el mismo codigo es queso cremoso
    en una cadena y cualquier otra cosa en la de enfrente.

    El catalogo de Gondola es COMPARTIDO entre cadenas y se navega escaneando:
    un codigo interno ahi rompe las dos cosas. Se quedan afuera, igual que en
    corregir_nombres.py.

    Los codigos cortos rellenados con ceros (GTIN-8 de balanza) caen por lo
    mismo. Un UPC-A de 12 digitos (un solo cero de padding) si es global.
    """
    nucleo = ean.lstrip("0")
    if len(nucleo) < 11:          # GTIN-8 o mas corto: numeracion interna
        return False
    if len(nucleo) == 13 and nucleo[0] == "2":   # 200-299: restringido
        return False
    return True


def leer_sepa(datos):
    """ean -> {cadena: (descripcion, marca, precio)} para la sucursal de Tandil."""
    prod = {}
    for id_comercio, (sucursal, cadena) in COMERCIOS.items():
        carpeta = carpeta_de(datos, id_comercio)
        if carpeta is None or not (carpeta / "productos.csv").exists():
            print(f"  ADVERTENCIA: falta el productos.csv de {cadena}")
            continue
        print(f"  leyendo {cadena} (sucursal {sucursal})...", flush=True)
        with open(carpeta / "productos.csv", encoding="utf-8", errors="replace") as f:
            next(f, None)
            for linea in f:
                p = linea.rstrip("\n").split("|")
                if len(p) <= COL_PRECIO or p[COL_SUCURSAL] != str(sucursal):
                    continue
                ean = norm_ean(p[COL_EAN])
                if ean is None:
                    continue
                desc = limpiar_desc(p[COL_DESC])
                if not desc:
                    continue
                marca = ESPACIOS.sub(" ", (p[COL_MARCA] or "").strip())
                prod.setdefault(ean, {})[cadena] = (desc, marca, num(p[COL_PRECIO]))
    return prod


def main():
    aplicar = "--aplicar" in sys.argv
    if "--datos" not in sys.argv:
        print("Falta --datos \"Datos AAAA-MM-DD\"")
        sys.exit(1)
    datos = CARPETA / sys.argv[sys.argv.index("--datos") + 1]
    if not datos.is_dir():
        print(f"No existe la carpeta {datos}")
        sys.exit(1)

    print(f"Leyendo SEPA desde {datos.name}...")
    sepa = leer_sepa(datos)
    print(f"EAN de Tandil en la descarga: {len(sepa)}")

    if not firebase_admin._apps:
        firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
    db = firestore.client()

    print("\nLeyendo los EAN que ya estan en el catalogo...")
    existentes = set()
    for doc in db.collection(COLECCION).select([]).stream():
        existentes.add(doc.id)
        if len(existentes) % 20000 == 0:
            print(f"  {len(existentes)} leidos...", flush=True)
    print(f"Productos en el catalogo: {len(existentes)}")

    nuevos = {e: v for e, v in sepa.items() if e not in existentes}
    # Sin precio en ninguna cadena no aporta nada al comparador
    nuevos = {e: v for e, v in nuevos.items() if any(t[2] for t in v.values())}
    internos = {e: v for e, v in nuevos.items() if not es_codigo_global(e)}
    nuevos = {e: v for e, v in nuevos.items() if es_codigo_global(e)}
    print(f"\nProductos nuevos con codigo global: {len(nuevos)}")
    print(f"Descartados por ser codigos internos de balanza: {len(internos)}")
    if internos:
        muestra = list(internos.items())[:3]
        for ean, variantes in muestra:
            desc = next(iter(variantes.values()))[0][:44]
            print(f"    {ean}  {desc}  ({'/'.join(variantes)})")
    if not nuevos:
        print("Nada para hacer.")
        return

    # --- Nombres ---
    fichas = {}
    for ean, variantes in nuevos.items():
        fichas[ean] = {
            "descripcion": elegir_descripcion(variantes),
            "marca": elegir_marca(variantes),
            "precios": {c: t[2] for c, t in variantes.items() if t[2]},
            "revisar": es_outlier(variantes),
        }

    # --- Categorias (Gemini, resumible) ---
    estado = json.loads(ESTADO.read_text(encoding="utf-8")) if ESTADO.exists() else {}
    pendientes = [
        (ean, f["descripcion"], f["marca"])
        for ean, f in fichas.items() if ean not in estado
    ]
    print(f"Ya clasificados de corridas anteriores: {len(fichas) - len(pendientes)}")
    if pendientes:
        print(f"Clasificando {len(pendientes)} productos con Gemini...")
        key, sesion = api_key(), requests.Session()
        for i in range(0, len(pendientes), LOTE):
            lote = pendientes[i:i + LOTE]
            estado.update(clasificar_lote(sesion, key, lote))
            ESTADO.write_text(json.dumps(estado, ensure_ascii=False), encoding="utf-8")
            print(f"  {min(i + LOTE, len(pendientes))}/{len(pendientes)} "
                  f"(clasificados {len(estado)})", flush=True)
            time.sleep(PAUSA)

    # --- Documentos ---
    docs = {}
    sin_clasificar = 0
    for ean, f in fichas.items():
        valor = estado.get(ean)
        if not valor:
            sin_clasificar += 1
            continue
        categoria, subcategoria = valor.split(">", 1)
        precios = f["precios"]
        cadena_min = min(precios, key=lambda c: (precios[c], c))
        docs[ean] = {
            "ean": ean,
            "descripcion": f["descripcion"],
            "marca": f["marca"],
            "categoria": categoria,
            "subcategoria": subcategoria,
            "tokens": tokenizar(f["descripcion"], f["marca"]),
            "precios": precios,
            "precio_min": precios[cadena_min],
            "cadena_min": cadena_min,
            "precio_publico": None,
            "precio_publico_n": 0,
            "imagen": None,
            "imagen_grande": None,
            "imagen_fuente": None,
            "revisar": f["revisar"],
            "en_tandil": True,
            "actualizado": firestore.SERVER_TIMESTAMP,
        }

    print(f"\nListos para subir: {len(docs)}")
    print(f"  sin categoria (no se suben, quedan para la proxima): {sin_clasificar}")
    print(f"  marcados para revisar por precio outlier: "
          f"{sum(1 for d in docs.values() if d['revisar'])}")

    from collections import Counter
    resumen = Counter(f"{d['categoria']} > {d['subcategoria']}" for d in docs.values())
    print("\n--- Top 10 categorias de las altas ---")
    for nombre, n in resumen.most_common(10):
        print(f"  {n:5}  {nombre}")

    print("\n--- 6 ejemplos ---")
    for ean, d in list(docs.items())[:6]:
        print(f"  {ean}  {d['descripcion'][:52]}")
        print(f"     {d['categoria']} > {d['subcategoria']}  |  {d['precios']}")

    if not aplicar:
        print("\n(simulacion: no se escribio nada. Correr con --aplicar)")
        return

    print(f"\nEscribiendo {len(docs)} productos nuevos...")
    lote = db.batch()
    n = escritos = 0
    for ean, campos in docs.items():
        lote.set(db.collection(COLECCION).document(ean), campos)
        n += 1
        if n == LOTE_ESCRITURA:
            try:
                lote.commit()
            except ResourceExhausted:
                print(f"\nTope diario alcanzado ({escritos} escritos). "
                      f"Reintentar maniana: el script retoma donde quedo.")
                sys.exit(1)
            escritos += n
            n = 0
            lote = db.batch()
    if n:
        lote.commit()
        escritos += n

    print(f"Listo: {escritos} productos nuevos en el catalogo.")
    print("Ahora regenera el arbol de navegacion:")
    print("    python regenerar_estructura_tandil.py")


if __name__ == "__main__":
    main()
