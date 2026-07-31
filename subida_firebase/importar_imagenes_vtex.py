#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
importar_imagenes_vtex.py - Reemplaza las imagenes del catalogo por fotos de
estudio de las tiendas VTEX de los supermercados argentinos.

Para cada producto consulta el endpoint publico de catalogo VTEX
(sin autenticacion), elige el dominio por calidad/disponibilidad, descarga la
foto, la SUBE a nuestro propio Firebase Storage (no hotlinking) y escribe la
URL interna en productos/{ean}. Donde VTEX no encuentra nada, se conserva la
imagen de Open Food Facts o el placeholder.

Modos:
    python importar_imagenes_vtex.py --check       # prueba Storage (sube y borra un test)
    python importar_imagenes_vtex.py --sample 20   # procesa 20 productos con precio (validacion)
    python importar_imagenes_vtex.py               # productos CON precio (guiado por dominio)
    python importar_imagenes_vtex.py --todos       # incluye tambien los sin precio (lento)
    python importar_imagenes_vtex.py --reescribir  # re-sube a Firestore lo ya encontrado

Estado incremental en imagenes_vtex.json ({ean: [url_interna, fuente, subido]}):
resumible con Ctrl+C. Ritmo: pausa de 1.3 s entre requests para no gatillar los
firewalls de los supermercados; fallas de red se ignoran en silencio.
"""
import csv
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import quote, urlparse

import requests
import firebase_admin
from firebase_admin import credentials, firestore, storage
from google.api_core.exceptions import NotFound

from subir_catalogo_v2 import reparar_fila

CARPETA = Path(__file__).resolve().parent
ARCHIVO_CSV = CARPETA / "catalogo_final_firestore.csv"
ESTADO_JSON = CARPETA / "imagenes_vtex.json"
CREDENCIALES = CARPETA / "credenciales.json"
BUCKET = "compras-super-18da9.firebasestorage.app"

DELAY = 1.3          # segundos entre requests HTTP (rate limiting)
MIN_BYTES = 1500     # descargas mas chicas suelen ser paginas de error, no fotos
LOTE_FS = 300        # tanda de progreso al escribir en Firestore

# Dominios VTEX por nombre de cadena. coop_obrera NO es VTEX (plataforma propia),
# por eso no figura: su bandera de precio solo suma al criterio de "multicadena".
DOMINIOS = {
    "vea": "www.vea.com.ar",
    "jumbo": "www.jumbo.com.ar",
    "carrefour": "www.carrefour.com.ar",
    "dia": "diaonline.supermercadosdia.com.ar",
}

# navegador real: los WAF de los supers rechazan User-Agents de scripts
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"


def ranking_dominios(fila):
    """Orden en que se consultan las tiendas segun disponibilidad y calidad:
    Vea/Jumbo (mejor imagen) primero si hay precio_vea o el producto esta en
    varias cadenas; luego Carrefour; luego Dia; y por ultimo TODOS como fallback
    general para productos sin bandera de precio."""
    flags = {c for c in ("vea", "carrefour", "coop_obrera", "dia")
             if (fila.get("precio_" + c) or "").strip()}
    orden = []

    def agregar(nombre):
        if nombre in DOMINIOS and nombre not in orden:
            orden.append(nombre)

    if "vea" in flags or len(flags) >= 2:
        agregar("vea")
        agregar("jumbo")
    if "carrefour" in flags:
        agregar("carrefour")
    if "dia" in flags:
        agregar("dia")
    for nombre in DOMINIOS:  # fallback: recorrer todos
        agregar(nombre)
    return orden


def variantes_ean(ean):
    """EAN original y, si tiene ceros a la izquierda sobrantes, la version
    limpia (VTEX guarda los codigos sin relleno). Minimo 8 digitos."""
    codigos = [ean]
    limpio = ean.lstrip("0")
    if limpio != ean and len(limpio) >= 8:
        codigos.append(limpio)
    return codigos


def buscar_en_vtex(session, fila, ean):
    """Devuelve (imageUrl, cadena) del primer dominio/codigo que responde con
    imagen, o (None, None). Toda falla de red/timeout se traga en silencio."""
    for nombre in ranking_dominios(fila):
        dominio = DOMINIOS[nombre]
        for code in variantes_ean(ean):
            url = (f"https://{dominio}/api/catalog_system/pub/products/search"
                   f"?fq=alternateIds_Ean:{code}")
            try:
                r = session.get(url, timeout=20)
                if r.status_code == 200:
                    data = r.json()
                    if data:
                        img = data[0]["items"][0]["images"][0]["imageUrl"]
                        if img:
                            return img, nombre
            except Exception:
                pass
            time.sleep(DELAY)
    return None, None


def descargar(session, url):
    try:
        r = session.get(url, timeout=30)
        r.raise_for_status()
        if len(r.content) >= MIN_BYTES:
            return r.content
    except Exception:
        pass
    return None


def content_type(url):
    ext = Path(urlparse(url).path).suffix.lower()
    return {".png": "image/png", ".webp": "image/webp",
            ".jpg": "image/jpeg", ".jpeg": "image/jpeg"}.get(ext, "image/jpeg")


def subir_storage(bucket, ean, url_origen, data):
    """Sube la foto a productos/{ean} con un token de descarga de Firebase y
    devuelve la URL publica interna. El token evita depender de ACLs por objeto
    (los buckets .firebasestorage.app usan acceso uniforme y las desactivan)."""
    ext = Path(urlparse(url_origen).path).suffix.lower() or ".jpg"
    ruta = f"productos/{ean}{ext if ext in ('.jpg', '.jpeg', '.png', '.webp') else '.jpg'}"
    token = uuid.uuid4().hex
    blob = bucket.blob(ruta)
    blob.metadata = {"firebaseStorageDownloadTokens": token}
    blob.upload_from_string(data, content_type=content_type(url_origen))
    return (f"https://firebasestorage.googleapis.com/v0/b/{BUCKET}"
            f"/o/{quote(ruta, safe='')}?alt=media&token={token}")


def leer_filas():
    filas = []
    with open(ARCHIVO_CSV, encoding="utf-8-sig") as f:
        lector = csv.DictReader(f)
        for fila in lector:
            if fila.get("descripcion") is None and "," in (fila.get("ean") or ""):
                fila = reparar_fila(fila["ean"], lector.fieldnames)
                if fila is None:
                    continue
            ean = "".join(c for c in (fila.get("ean") or "") if c.isdigit()).zfill(13)
            if len(ean) == 13:
                filas.append((ean, fila))
    return filas


def cargar_estado():
    import json
    if ESTADO_JSON.exists():
        return json.loads(ESTADO_JSON.read_text(encoding="utf-8"))
    return {}


def guardar_estado(estado):
    import json
    ESTADO_JSON.write_text(json.dumps(estado), encoding="utf-8")


def check_storage(bucket):
    token = uuid.uuid4().hex
    blob = bucket.blob("productos/_check.txt")
    blob.metadata = {"firebaseStorageDownloadTokens": token}
    blob.upload_from_string(b"ok", content_type="text/plain")
    url = (f"https://firebasestorage.googleapis.com/v0/b/{BUCKET}"
           f"/o/{quote('productos/_check.txt', safe='')}?alt=media&token={token}")
    r = requests.get(url, timeout=20)
    ok = r.status_code == 200 and r.content == b"ok"
    blob.delete()
    return ok


def escribir_firestore(db, estado):
    pendientes = [(e, v) for e, v in estado.items() if v[0] and not v[2]]
    subidos = sin_doc = 0
    for n, (ean, v) in enumerate(pendientes, start=1):
        try:
            db.collection("productos").document(ean).update({
                "imagen": v[0],
                "imagen_grande": v[0],
                "imagen_fuente": v[1],
            })
            v[2] = True
            subidos += 1
        except NotFound:
            sin_doc += 1
        if n % LOTE_FS == 0:
            guardar_estado(estado)
            print(f"Firestore: {n}/{len(pendientes)} procesados...", flush=True)
    guardar_estado(estado)
    print(f"Firestore: {subidos} productos con imagen VTEX, {sin_doc} sin documento.", flush=True)


def main():
    reescribir = "--reescribir" in sys.argv
    todos = "--todos" in sys.argv
    sample = 0
    if "--sample" in sys.argv:
        sample = int(sys.argv[sys.argv.index("--sample") + 1])

    # Traba para la fase 2 (--todos): si existe el flag, se cancela sin hacer nada.
    # La fase 1 en curso no la lee (ya está en memoria); la fase 2 arranca como
    # proceso nuevo y sí. Borrar stop_fase2.flag para habilitarla más adelante.
    if todos and (CARPETA / "stop_fase2.flag").exists():
        print("Fase 2 (--todos) cancelada: existe stop_fase2.flag. "
              "Borralo para habilitarla.", flush=True)
        return

    firebase_admin.initialize_app(credentials.Certificate(str(CREDENCIALES)),
                                  {"storageBucket": BUCKET})
    bucket = storage.bucket()

    if "--check" in sys.argv:
        print("Storage OK" if check_storage(bucket) else "Storage FALLO", flush=True)
        return

    db = firestore.client()
    estado = cargar_estado()
    if reescribir:
        for v in estado.values():
            v[2] = False
        escribir_firestore(db, estado)
        return

    filas = leer_filas()
    # productos con al menos una bandera de precio (salvo --todos)
    def tiene_precio(fila):
        return any((fila.get("precio_" + c) or "").strip()
                   for c in ("vea", "carrefour", "coop_obrera", "dia"))
    candidatos = [(e, f) for e, f in filas if todos or tiene_precio(f)]
    pendientes = [(e, f) for e, f in candidatos if e not in estado]
    if sample:
        pendientes = pendientes[:sample]

    print(f"{len(filas)} EANs | {len(candidatos)} candidatos | "
          f"{len(pendientes)} a procesar en esta corrida", flush=True)

    session = requests.Session()
    session.headers["User-Agent"] = UA
    encontrados = 0
    for i, (ean, fila) in enumerate(pendientes, start=1):
        img_url, cadena = buscar_en_vtex(session, fila, ean)
        data = descargar(session, img_url) if img_url else None
        if data:
            try:
                interna = subir_storage(bucket, ean, img_url, data)
                estado[ean] = [interna, cadena, False]
                encontrados += 1
            except Exception as e:
                print(f"  {ean}: fallo al subir a Storage: {e}", flush=True)
                estado[ean] = [None, None, False]
        else:
            estado[ean] = [None, None, False]
        if i % 25 == 0 or i == len(pendientes):
            guardar_estado(estado)
            print(f"VTEX: {i}/{len(pendientes)} consultados, {encontrados} con imagen", flush=True)
        # Vuelca a Firestore de a poco: en una corrida de dias las imagenes van
        # apareciendo en la app sin esperar al final, y no se pierde trabajo.
        if i % 200 == 0:
            escribir_firestore(db, estado)
        time.sleep(DELAY)

    escribir_firestore(db, estado)
    total = sum(1 for v in estado.values() if v[0])
    print(f"\nCobertura VTEX acumulada: {total} productos con imagen de estudio.", flush=True)


if __name__ == "__main__":
    main()
