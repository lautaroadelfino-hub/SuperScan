#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
enriquecer_imagenes.py - Completa productos/{ean}.imagen con Open Food Facts.

Consulta la API de busqueda de OFF en lotes de 50 EANs, guarda lo encontrado en
imagenes_off.json (estado local, resumible: se puede cortar con Ctrl+C y
retomar) y escribe imagen / imagen_grande en Firestore con el Admin SDK.

Como OFF guarda los codigos tal como se escanearon (UPC de 12, EAN-8, etc.),
cada EAN se consulta tambien sin ceros a la izquierda.

Uso:
    python enriquecer_imagenes.py               # consulta lo pendiente y escribe
    python enriquecer_imagenes.py --reescribir  # re-sube TODO lo ya encontrado
                                                # (usar despues de resubir el catalogo)

Si en la carpeta existe el dump de OFF (off_dump.csv.gz, descargable de
https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz),
se usa ESE en lugar de la API: cruza los ~60k EANs contra los ~3.7M de
productos del dump en minutos y sin rate limits (es lo que OFF recomienda
para uso masivo). Sin dump, cae a la API de busqueda (~2 h y fragil: el
endpoint tira 503 seguido).

Las imagenes quedan hospedadas en OFF (licencia CC-BY-SA: la app muestra la
atribucion en el detalle del producto).
"""
import csv
import gzip
import json
import sys
import time
from pathlib import Path

import requests
import firebase_admin
from firebase_admin import credentials, firestore
from google.api_core.exceptions import NotFound

from subir_catalogo_v2 import reparar_fila

CARPETA = Path(__file__).resolve().parent
ARCHIVO_CSV = CARPETA / "catalogo_final_firestore.csv"
ESTADO_JSON = CARPETA / "imagenes_off.json"  # {ean: [imagen, imagen_grande, subido]}
CREDENCIALES = CARPETA / "credenciales.json"
DUMP_OFF = CARPETA / "off_dump.csv.gz"

API_BUSQUEDA = "https://world.openfoodfacts.org/api/v2/search"
USER_AGENT = "SuperScan/1.0 (lautaroadelfino@gmail.com)"
LOTE_OFF = 50        # EANs por consulta (x2 variantes sin ceros = <=100 codigos)
PAUSA_SEG = 6.5      # limite OFF: ~10 busquedas/min
LOTE_FS = 400        # escrituras por tanda de progreso


def eans_del_csv():
    eans = []
    with open(ARCHIVO_CSV, encoding="utf-8-sig") as f:
        lector = csv.DictReader(f)
        for fila in lector:
            if fila.get("descripcion") is None and "," in (fila.get("ean") or ""):
                fila = reparar_fila(fila["ean"], lector.fieldnames)
                if fila is None:
                    continue
            ean = "".join(c for c in (fila.get("ean") or "") if c.isdigit()).zfill(13)
            if len(ean) == 13:
                eans.append(ean)
    return eans


def cargar_estado():
    if ESTADO_JSON.exists():
        return json.loads(ESTADO_JSON.read_text(encoding="utf-8"))
    return {}


def guardar_estado(estado):
    ESTADO_JSON.write_text(json.dumps(estado), encoding="utf-8")


def consultar_off(estado, pendientes):
    sesion = requests.Session()
    sesion.headers["User-Agent"] = USER_AGENT
    total = len(pendientes)
    encontrados_corrida = 0

    for i in range(0, total, LOTE_OFF):
        lote = pendientes[i:i + LOTE_OFF]
        # variante sin ceros a la izquierda para matchear como lo guarda OFF
        codigos = set(lote) | {e.lstrip("0") for e in lote}
        params = {
            "code": ",".join(sorted(codigos)),
            "fields": "code,image_front_small_url,image_front_url",
            "page_size": 100,
        }
        respuesta = None
        for intento in range(3):
            try:
                r = sesion.get(API_BUSQUEDA, params=params, timeout=30)
                r.raise_for_status()
                respuesta = r.json()
                break
            except Exception as e:
                print(f"  intento {intento + 1} fallo: {e}", flush=True)
                time.sleep(15 * (intento + 1))
        if respuesta is None:
            print("Sin respuesta de OFF tras 3 intentos; corto aca. "
                  "Volver a correr para retomar.", flush=True)
            break

        con_imagen = {}
        for p in respuesta.get("products", []):
            codigo = str(p.get("code", "")).zfill(13)
            chica = p.get("image_front_small_url") or None
            grande = p.get("image_front_url") or None
            if chica or grande:
                con_imagen[codigo] = (chica, grande)

        for ean in lote:
            chica, grande = con_imagen.get(ean, (None, None))
            estado[ean] = [chica, grande, False]
            if chica or grande:
                encontrados_corrida += 1

        guardar_estado(estado)
        hechos = min(i + LOTE_OFF, total)
        print(f"OFF: {hechos}/{total} consultados, {encontrados_corrida} con imagen en esta corrida", flush=True)
        if hechos < total:
            time.sleep(PAUSA_SEG)

    return encontrados_corrida


def consultar_dump(estado, pendientes):
    """Cruza los EANs pendientes contra el dump completo de OFF (TSV gzip,
    ~3.7M filas) en streaming: nunca carga el archivo entero en memoria.
    El dump es la foto completa de OFF, asi que todo pendiente queda marcado
    como consultado (con imagen o sin ella)."""
    # cada EAN se busca tal cual (13 digitos) y sin ceros a la izquierda,
    # que es como OFF guarda los UPC-12 / EAN-8
    buscados = {}
    for ean in pendientes:
        buscados[ean] = ean
        buscados[ean.lstrip("0")] = ean

    encontrados = {}
    with gzip.open(DUMP_OFF, "rt", encoding="utf-8", errors="replace") as f:
        encabezado = f.readline().rstrip("\n").split("\t")
        try:
            i_code = encabezado.index("code")
            i_chica = encabezado.index("image_small_url")
            i_grande = encabezado.index("image_url")
        except ValueError:
            print(f"El dump no tiene las columnas esperadas: {encabezado[:8]}...", flush=True)
            return
        max_i = max(i_code, i_chica, i_grande)

        for n, linea in enumerate(f, start=1):
            codigo = linea[:linea.find("\t")]
            ean = buscados.get(codigo)
            if ean is not None and ean not in encontrados:
                campos = linea.rstrip("\n").split("\t")
                if len(campos) > max_i:
                    chica = campos[i_chica].strip() or None
                    grande = campos[i_grande].strip() or None
                    if chica or grande:
                        encontrados[ean] = [chica, grande]
            if n % 500000 == 0:
                print(f"dump: {n} filas revisadas, {len(encontrados)} con imagen", flush=True)

    for ean in pendientes:
        chica, grande = encontrados.get(ean, (None, None))
        estado[ean] = [chica, grande, False]
    guardar_estado(estado)
    print(f"dump: listo. {len(encontrados)}/{len(pendientes)} pendientes con imagen", flush=True)


def escribir_firestore(db, estado):
    """Sube a Firestore lo encontrado y todavia no subido. update() no crea
    documentos: los EANs que aun no estan en `productos` (filas reparadas sin
    resubir) quedan pendientes y entran en la proxima corrida con --reescribir."""
    pendientes = [(e, v) for e, v in estado.items() if (v[0] or v[1]) and not v[2]]
    subidos = sin_doc = 0
    for n, (ean, v) in enumerate(pendientes, start=1):
        try:
            db.collection("productos").document(ean).update({
                "imagen": v[0] or v[1],
                "imagen_grande": v[1],
                "imagen_fuente": "off",
            })
            v[2] = True
            subidos += 1
        except NotFound:
            sin_doc += 1
        if n % LOTE_FS == 0:
            guardar_estado(estado)
            print(f"Firestore: {n}/{len(pendientes)} procesados...", flush=True)
    guardar_estado(estado)
    print(f"Firestore: {subidos} productos actualizados con imagen, "
          f"{sin_doc} sin documento todavia (resubir catalogo y usar --reescribir).", flush=True)


def main():
    reescribir = "--reescribir" in sys.argv
    estado = cargar_estado()
    if reescribir:
        for v in estado.values():
            v[2] = False

    eans = eans_del_csv()
    pendientes = [e for e in eans if e not in estado]
    con_imagen = sum(1 for v in estado.values() if v[0] or v[1])
    print(f"{len(eans)} EANs en el CSV | {len(estado)} ya consultados "
          f"({con_imagen} con imagen) | {len(pendientes)} pendientes", flush=True)

    if pendientes:
        if DUMP_OFF.exists():
            print(f"Usando dump local ({DUMP_OFF.stat().st_size // 1_000_000} MB)", flush=True)
            consultar_dump(estado, pendientes)
        else:
            consultar_off(estado, pendientes)

    firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)))
    escribir_firestore(firestore.client(), estado)

    con_imagen = sum(1 for v in estado.values() if v[0] or v[1])
    print(f"\nCobertura total: {con_imagen}/{len(estado)} EANs con imagen "
          f"({100.0 * con_imagen / max(len(estado), 1):.1f}%)", flush=True)


if __name__ == "__main__":
    main()
