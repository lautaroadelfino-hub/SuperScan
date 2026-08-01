#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
actualizar_precios.py - Refresca los precios del catalogo con una descarga
nueva de SEPA, sin volver a subir todo el catalogo.

Toma las carpetas que baja SEPA (una por comercio, cada una con productos.csv),
se queda con la sucursal de Tandil de cada cadena y actualiza en Firestore:
    precios (map cadena -> precio), precio_min, cadena_min, actualizado

NO toca descripcion, marca, categoria, imagenes ni tokens: de eso se ocupan
corregir_nombres.py y clasificar_categorias.py.

Que pasa con un producto que la cadena dejo de publicar: se le SACA el precio
de esa cadena. Dejar un precio viejo al lado de uno nuevo rompe el comparador
de la app, que es su razon de ser. Para que un error de la fuente no borre media
base, si una cadena trae menos del UMBRAL_SEGURIDAD de lo que tenia, se aborta.

Los EAN que no estan en el catalogo NO se dan de alta: un producto sin
categoria no se puede navegar. El script los cuenta y avisa.

Uso (simula por defecto, no escribe nada):
    python actualizar_precios.py --datos "Datos 2026-07-31"
    python actualizar_precios.py --datos "Datos 2026-07-31" --aplicar
"""
import sys
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, firestore

try:
    from google.api_core.exceptions import ResourceExhausted
except ImportError:
    ResourceExhausted = Exception

CARPETA = Path(__file__).resolve().parent
CREDENCIALES = CARPETA / "credenciales.json"
COLECCION = "productos"
LOTE = 400

# id_comercio de SEPA -> (id_sucursal de Tandil, nombre de cadena en el map
# `precios`). Las sucursales son las mismas que usa corregir_nombres.py: ver
# Id_sucursal_tandil.txt.
COMERCIOS = {
    "9": (711, "vea"),
    "10": (31, "carrefour"),
    "13": (149, "coop_obrera"),
    "15": (273, "dia"),
}

# Columnas reales del formato SEPA (el EAN esta en id_producto, no en
# productos_ean, que trae un placeholder "1"). Indices base 0:
COL_SUCURSAL, COL_EAN, COL_PRECIO = 2, 3, 9

# Si una cadena trae menos de esta fraccion de los productos que ya tenia en el
# catalogo, algo salio mal con la descarga: se aborta antes de borrar precios.
UMBRAL_SEGURIDAD = 0.5


def num(v):
    try:
        f = float((v or "").replace(",", "."))
        return f if f > 0 else None
    except ValueError:
        return None


def norm_ean(raw):
    d = "".join(c for c in (raw or "") if c.isdigit())
    return d.zfill(13) if 8 <= len(d) <= 13 else None


def carpeta_de(datos, id_comercio):
    candidatas = sorted(datos.glob(f"*comercio-sepa-{id_comercio}_*"))
    return candidatas[0] if candidatas else None


def fecha_de_los_datos(datos):
    """De cuando son los precios (no de cuando se subieron). Se toma la fecha
    MAS VIEJA entre las cadenas: es la honesta para mostrarle al usuario, que
    ve un solo numero para toda la app. Sale de comercio_ultima_actualizacion
    de cada SEPA; si no se puede leer, cae al nombre de la carpeta."""
    import re
    fechas = []
    for id_comercio in COMERCIOS:
        carpeta = carpeta_de(datos, id_comercio)
        if carpeta is None:
            continue
        archivo = carpeta / "comercio.csv"
        if not archivo.exists():
            continue
        with open(archivo, encoding="utf-8-sig", errors="replace") as f:
            next(f, None)                      # encabezado
            partes = (next(f, "") or "").split("|")
        if len(partes) > 6 and partes[6][:4].isdigit():
            fechas.append(partes[6][:10])
    if fechas:
        return min(fechas)
    m = re.search(r"(\d{4}-\d{2}-\d{2})", datos.name)
    return m.group(1) if m else None


def escribir_meta(db, datos, cadenas):
    """catalogo_meta/precios: la app lo lee para mostrar 'precios al 31/07'
    arriba de todo. Sin esto no hay forma de saber si el dato es de ayer o de
    hace tres meses."""
    fecha = fecha_de_los_datos(datos)
    if fecha is None:
        print("  ADVERTENCIA: no pude determinar la fecha de los datos, no escribo el meta")
        return None
    db.collection("catalogo_meta").document("precios").set({
        "fecha_datos": fecha,
        "origen": datos.name,
        "cadenas": sorted(cadenas),
        "actualizado": firestore.SERVER_TIMESTAMP,
    })
    return fecha


def leer_precios(datos):
    """ean -> {cadena: precio} para la sucursal de Tandil de cada cadena."""
    precios = {}
    por_cadena = {}
    for id_comercio, (sucursal, cadena) in COMERCIOS.items():
        carpeta = carpeta_de(datos, id_comercio)
        if carpeta is None:
            print(f"  ADVERTENCIA: no encontre la carpeta del comercio {id_comercio} ({cadena})")
            continue
        archivo = carpeta / "productos.csv"
        if not archivo.exists():
            print(f"  ADVERTENCIA: falta {archivo.name} en {carpeta.name}")
            continue
        print(f"  leyendo {cadena} (sucursal {sucursal}) desde {carpeta.name}...", flush=True)
        n = 0
        with open(archivo, encoding="utf-8", errors="replace") as f:
            next(f, None)
            for linea in f:
                p = linea.rstrip("\n").split("|")
                if len(p) <= COL_PRECIO or p[COL_SUCURSAL] != str(sucursal):
                    continue
                ean = norm_ean(p[COL_EAN])
                if ean is None:
                    continue
                precio = num(p[COL_PRECIO])
                if precio is None:
                    continue
                precios.setdefault(ean, {})[cadena] = precio
                n += 1
        por_cadena[cadena] = n
        print(f"    {n} productos con precio")
    return precios, por_cadena


def main():
    aplicar = "--aplicar" in sys.argv
    if "--datos" not in sys.argv:
        print("Falta --datos \"Datos AAAA-MM-DD\"")
        sys.exit(1)
    datos = CARPETA / sys.argv[sys.argv.index("--datos") + 1]
    if not datos.is_dir():
        print(f"No existe la carpeta {datos}")
        sys.exit(1)

    # Atajo para (re)escribir solo la fecha de los datos, sin releer 1,2 GB
    if "--solo-meta" in sys.argv:
        if not firebase_admin._apps:
            firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
        db = firestore.client()
        fecha = escribir_meta(db, datos, [c for _s, c in COMERCIOS.values()])
        print(f"catalogo_meta/precios actualizado: fecha_datos = {fecha}")
        return

    print(f"Leyendo SEPA desde {datos.name}...")
    nuevos, por_cadena = leer_precios(datos)
    if not nuevos:
        print("No se leyo ningun precio. Nada que hacer.")
        sys.exit(1)
    cadenas_en_esta_corrida = set(por_cadena)
    print(f"\nEAN distintos con precio nuevo: {len(nuevos)}")

    if not firebase_admin._apps:
        firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
    db = firestore.client()

    print("\nLeyendo el catalogo actual de Firestore...")
    actual = {}
    for doc in db.collection(COLECCION).select(["precios"]).stream():
        actual[doc.id] = (doc.to_dict() or {}).get("precios") or {}
        if len(actual) % 20000 == 0:
            print(f"  {len(actual)} leidos...", flush=True)
    print(f"Productos en el catalogo: {len(actual)}")

    # Guardrail: una cadena que se desploma es un error de descarga, no una
    # liquidacion. Mejor abortar que borrarle los precios a media base.
    print("\nCobertura por cadena (antes -> ahora):")
    abortar = False
    for cadena in sorted(cadenas_en_esta_corrida):
        antes = sum(1 for m in actual.values() if cadena in m)
        ahora = sum(1 for m in nuevos.values() if cadena in m and m[cadena])
        # Solo cuentan los que ya estan en el catalogo
        ahora_en_catalogo = sum(
            1 for ean, m in nuevos.items() if ean in actual and cadena in m
        )
        marca = ""
        if antes and ahora_en_catalogo < antes * UMBRAL_SEGURIDAD:
            marca = "  <-- CAIDA SOSPECHOSA"
            abortar = True
        print(f"  {cadena:12} {antes:6} -> {ahora_en_catalogo:6} "
              f"(en el pais: {ahora}){marca}")
    if abortar:
        print("\nAbortado: alguna cadena perdio mas de la mitad de sus productos. "
              "Revisa que la descarga este completa antes de insistir.")
        sys.exit(1)

    # --- Que cambia ---
    pendientes = []
    sin_cambios = quitados = 0
    altas_nuevas = sum(1 for ean in nuevos if ean not in actual)

    for ean, viejos in actual.items():
        del_ean = nuevos.get(ean, {})
        # Se conservan las cadenas que NO participaron de esta corrida
        finales = {c: p for c, p in viejos.items() if c not in cadenas_en_esta_corrida}
        quitados += sum(
            1 for c in viejos
            if c in cadenas_en_esta_corrida and c not in del_ean
        )
        finales.update(del_ean)
        finales = {c: p for c, p in finales.items() if p and p > 0}

        if finales == viejos:
            sin_cambios += 1
            continue

        if finales:
            cadena_min = min(finales, key=lambda c: (finales[c], c))
            campos = {
                "precios": finales,
                "precio_min": finales[cadena_min],
                "cadena_min": cadena_min,
                "actualizado": firestore.SERVER_TIMESTAMP,
            }
        else:
            campos = {
                "precios": {},
                "precio_min": None,
                "cadena_min": None,
                "actualizado": firestore.SERVER_TIMESTAMP,
            }
        pendientes.append((ean, campos))

    print(f"\nProductos del catalogo sin cambios: {sin_cambios}")
    print(f"Productos a actualizar: {len(pendientes)}")
    print(f"  precios de cadena que se dan de baja: {quitados}")
    print(f"EAN nuevos que NO estan en el catalogo (no se dan de alta): {altas_nuevas}")

    if pendientes:
        print("\n--- 5 ejemplos ---")
        for ean, campos in pendientes[:5]:
            antes = actual.get(ean, {})
            print(f"  {ean}")
            print(f"     antes: {antes}")
            print(f"     ahora: {campos['precios']}  (min {campos['cadena_min']})")

    if not aplicar:
        print("\n(simulacion: no se escribio nada. Correr con --aplicar)")
        return

    print(f"\nEscribiendo {len(pendientes)} productos...")
    lote = db.batch()
    n = escritos = 0
    for ean, campos in pendientes:
        lote.set(db.collection(COLECCION).document(ean), campos, merge=True)
        n += 1
        if n == LOTE:
            try:
                lote.commit()
            except ResourceExhausted:
                print(f"\nTope diario alcanzado ({escritos} escritos). "
                      f"Reintentar maniana: el script retoma donde quedo.")
                sys.exit(1)
            escritos += n
            n = 0
            lote = db.batch()
            if escritos % 4000 == 0:
                print(f"  {escritos}/{len(pendientes)}...", flush=True)
    if n:
        lote.commit()
        escritos += n

    fecha = escribir_meta(db, datos, cadenas_en_esta_corrida)
    print(f"Listo: {escritos} productos con precios al dia (datos del {fecha}).")


if __name__ == "__main__":
    main()
