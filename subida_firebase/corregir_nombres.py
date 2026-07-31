#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
corregir_nombres.py - Mejora descripcion/marca de los productos de Tandil
usando los CSV crudos de SEPA de las 4 cadenas.

Los precios ya están bien (verificado: 100% coinciden con precio_lista oficial).
Lo que estaba mal son los NOMBRES, por tres motivos:
  1. Cada cadena describe distinto y el pipeline eligió inconsistente.
  2. Vea agrega sufijos de máquina ("PAQ-52-un.") y escribe en minúsculas
     -> esas descripciones NO aparecen en la búsqueda de la app, que compara
        en MAYÚSCULAS por prefijo.
  3. Día y Coop publican descripcion y marca truncadas ("KINDE", "CERV RUBIA").

Este script elige, por cada EAN, la MEJOR descripción y la marca más completa
entre las 4 cadenas, y marca `revisar` cuando el precio de una cadena es un
outlier claro respecto de las otras (errores conocidos de la fuente oficial,
p. ej. el precio unitario cargado en el renglón de un pack).

Solo toca descripcion, marca y revisar. NO toca precios, imágenes ni categorías.

Uso:
    python corregir_nombres.py            # simulación: muestra qué cambiaría
    python corregir_nombres.py --aplicar  # escribe los cambios en Firestore
"""
import csv
import re
import statistics
import sys
from pathlib import Path

CARPETA = Path(__file__).resolve().parent
DATOS = CARPETA / "Datos 2026-07-10"
CREDENCIALES = CARPETA / "credenciales.json"

# archivo -> (id_sucursal de Tandil, nombre de cadena)
FUENTES = {
    "productos_vea.csv": (711, "vea"),
    "productos_carrefour.csv": (31, "carrefour"),
    "productos_cooperativa obrera.csv": (149, "coop_obrera"),
    "productos_dia.csv": (273, "dia"),
}

# Columnas reales del formato SEPA (¡el EAN está en id_producto, no en
# productos_ean, que trae un placeholder "1"!). Índices base 0:
COL_SUCURSAL, COL_EAN, COL_DESC, COL_MARCA, COL_PRECIO = 2, 3, 5, 8, 9

# Calidad de descripción observada: Carrefour escribe completo y prolijo,
# Vea es largo pero críptico y en minúsculas, Coop medio, Día trunca a ~17.
PRIORIDAD = {"carrefour": 3, "vea": 2, "coop_obrera": 1, "dia": 0}

# Sufijos de máquina de Vea: "PAQ-52-un.", "BAR-125-gr.", "EST-150-gr.", "PZA-1-Kg"
SUFIJO_MAQUINA = re.compile(r"\s+[A-Z]{2,4}-[\d.,]+-\w+\.?\s*$", re.IGNORECASE)
ESPACIOS = re.compile(r"\s+")


def limpiar_desc(d):
    d = SUFIJO_MAQUINA.sub("", (d or "").strip())
    return ESPACIOS.sub(" ", d).strip()


def num(v):
    try:
        f = float((v or "").replace(",", "."))
        return f if f > 0 else None
    except ValueError:
        return None


def norm_ean(raw):
    d = "".join(c for c in (raw or "") if c.isdigit())
    return d.zfill(13) if 8 <= len(d) <= 13 else None


CACHE = CARPETA / "tandil_nombres_cache.json"


def leer_fuentes():
    """Recorre los CSV nacionales una vez, quedándose solo con Tandil.
    Cachea el resultado: releer ~1 GB en cada corrida es innecesario."""
    import json
    if CACHE.exists():
        print("  usando caché (borrar tandil_nombres_cache.json para releer)")
        crudo = json.loads(CACHE.read_text(encoding="utf-8"))
        return {e: {c: tuple(v) for c, v in var.items()} for e, var in crudo.items()}
    prod = {}
    for archivo, (sucursal, cadena) in FUENTES.items():
        ruta = DATOS / archivo
        if not ruta.exists():
            print(f"  ADVERTENCIA: falta {archivo}, se omite")
            continue
        print(f"  leyendo {archivo} (sucursal {sucursal})...", flush=True)
        with open(ruta, encoding="utf-8", errors="replace") as f:
            next(f, None)
            for linea in f:
                p = linea.rstrip("\n").split("|")
                if len(p) <= COL_PRECIO or p[COL_SUCURSAL] != str(sucursal):
                    continue
                ean = norm_ean(p[COL_EAN])
                if ean is None:
                    continue
                desc = limpiar_desc(p[COL_DESC])
                marca = ESPACIOS.sub(" ", (p[COL_MARCA] or "").strip())
                precio = num(p[COL_PRECIO])
                if not desc:
                    continue
                prod.setdefault(ean, {})[cadena] = (desc, marca, precio)
    import json
    CACHE.write_text(json.dumps(prod, ensure_ascii=False), encoding="utf-8")
    return prod


def elegir_descripcion(variantes):
    """La más informativa: gana la más larga, y a igual largo la de la cadena
    que mejor escribe. Todo en MAYÚSCULAS para que la búsqueda por prefijo
    de la app (que compara en mayúsculas) las encuentre."""
    mejor = max(
        variantes.items(),
        key=lambda kv: (len(kv[1][0]), PRIORIDAD.get(kv[0], 0)),
    )
    return mejor[1][0].upper()


def elegir_marca(variantes):
    """La marca más completa: Día y Coop truncan a ~5 caracteres."""
    marcas = [v[1] for v in variantes.values() if v[1]]
    if not marcas:
        return ""
    return max(marcas, key=len).upper()


def es_outlier(variantes):
    """True si el precio de alguna cadena se despega groseramente del resto:
    típico de la fuente oficial cargando el precio unitario en el renglón de
    un pack, o del mismo EAN usado para presentaciones distintas."""
    precios = [v[2] for v in variantes.values() if v[2]]
    if len(precios) < 2:
        return False
    m = statistics.median(precios)
    return any(p < m * 0.25 or p > m * 4 for p in precios)


def main():
    aplicar = "--aplicar" in sys.argv
    print("Leyendo los CSV crudos de SEPA (filtrando Tandil)...")
    prod = leer_fuentes()
    print(f"EANs de Tandil encontrados: {len(prod)}\n")

    import firebase_admin
    from firebase_admin import credentials, firestore
    from google.api_core.exceptions import NotFound

    firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
    db = firestore.client()

    # Estado actual, desde el CSV del catálogo (más barato que leer Firestore)
    actual = {}
    with open(CARPETA / "catalogo_final_firestore.csv", encoding="utf-8-sig") as f:
        for fila in csv.DictReader(f):
            ean = norm_ean(fila.get("ean"))
            if ean:
                actual[ean] = ((fila.get("descripcion") or "").strip(),
                               (fila.get("marca") or "").strip())

    cambios = []
    solo_mayus = fuera_catalogo = 0
    for ean, variantes in prod.items():
        # Solo corregimos productos que YA existen en el catálogo. Los EAN de
        # Tandil que no están son códigos internos de balanza (frescos vendidos
        # por peso): no son códigos de barras reales, no se comparan entre
        # cadenas y no corresponde darlos de alta acá.
        if ean not in actual:
            fuera_catalogo += 1
            continue
        nueva_desc = elegir_descripcion(variantes)
        nueva_marca = elegir_marca(variantes)
        revisar = es_outlier(variantes)
        vieja_desc, vieja_marca = actual[ean]
        if nueva_desc != vieja_desc or nueva_marca != vieja_marca:
            if nueva_desc.upper() == vieja_desc.upper() and nueva_marca.upper() == vieja_marca.upper():
                solo_mayus += 1
            cambios.append((ean, vieja_desc, nueva_desc, vieja_marca, nueva_marca, revisar))

    # Segundo pase, sobre TODO el catálogo: la búsqueda de la app compara en
    # MAYÚSCULAS por prefijo, así que cualquier descripción con minúsculas es
    # invisible al buscar. Normalizarlas no necesita los CSV: es arreglar lo
    # que ya está. (Los de Tandil ya se corrigen arriba, no se duplican.)
    en_tandil = set(prod)
    solo_upper = [
        (ean, d, d.upper(), m, m.upper())
        for ean, (d, m) in actual.items()
        if ean not in en_tandil and d and d != d.upper()
    ]

    print(f"EANs de Tandil que no están en el catálogo (códigos de balanza): {fuera_catalogo}")
    print(f"Productos del catálogo con nombre a corregir: {len(cambios)}")
    print(f"  (de esos, {solo_mayus} son solo pasar a MAYÚSCULAS -> los hace "
          f"encontrables en la búsqueda)")
    print(f"  marcados para revisar por precio outlier: {sum(1 for c in cambios if c[5])}")
    print(f"Fuera de Tandil, solo normalizar a MAYÚSCULAS (para que la "
          f"búsqueda los encuentre): {len(solo_upper)}\n")

    print("--- 12 ejemplos de lo que cambiaría ---")
    for ean, vd, nd, vm, nm, rev in cambios[:12]:
        print(f"\n  EAN {ean}{'   [revisar: precio outlier]' if rev else ''}")
        print(f"     antes: {vd[:62]}   | marca: {vm}")
        print(f"     ahora: {nd[:62]}   | marca: {nm}")

    # `en_tandil` marca los productos que se consiguen en alguna de las 4
    # sucursales de Tandil. La app navega y busca SOLO esos: el resto
    # (productos de otras ciudades y altas de Open Food Facts) queda guardado
    # en Firestore para incorporarlo más adelante, pero sin ensuciar la
    # experiencia. Se escribe en TODOS los documentos porque Firestore no sabe
    # consultar "campo ausente".
    correcciones = {c[0]: (c[2], c[4], c[5]) for c in cambios}
    mayusculas = {c[0]: (c[2], c[4]) for c in solo_upper}
    total = len(actual)
    print(f"Productos a marcar: {total} en total | "
          f"{len(en_tandil & actual.keys())} con en_tandil=true (visibles en la app) | "
          f"{total - len(en_tandil & actual.keys())} ocultos por ahora")

    if not aplicar:
        print(f"\n(simulación: no se escribió nada. Correr con --aplicar)")
        return

    # set(merge=True) en vez de update(): no falla si algún documento no existe
    print(f"\nEscribiendo {total} productos en Firestore...")
    lote = db.batch()
    n = escritos = 0
    for ean, (vieja_desc, vieja_marca) in actual.items():
        campos = {"en_tandil": ean in en_tandil}
        if ean in correcciones:
            nd, nm, rev = correcciones[ean]
            campos.update({"descripcion": nd, "marca": nm, "revisar": rev})
        elif ean in mayusculas:
            nd, nm = mayusculas[ean]
            campos.update({"descripcion": nd, "marca": nm})
        lote.set(db.collection("productos").document(ean), campos, merge=True)
        n += 1
        if n == 400:
            lote.commit()
            escritos += n
            n = 0
            lote = db.batch()
            if escritos % 4000 == 0:
                print(f"  {escritos}/{total}...", flush=True)
    if n:
        lote.commit()
        escritos += n
    print(f"Listo: {escritos} productos actualizados "
          f"({len(correcciones)} con nombre corregido, "
          f"{len(mayusculas)} normalizados, todos con en_tandil).")


if __name__ == "__main__":
    main()
