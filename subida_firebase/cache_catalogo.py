#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cache_catalogo.py - Una foto local del catalogo, para no releer los 60k
documentos de Firestore en cada corrida.

POR QUE EXISTE
--------------
El proyecto esta en el plan Spark: 50.000 lecturas por dia, compartidas con la
app de los usuarios. Un refresco de precios escaneaba el catalogo entero dos
veces (actualizar_precios para armar el diff, regenerar_estructura para el
arbol): ~85.000 lecturas, o sea que no entraba, y encima dejaba a los usuarios
sin cuota. Con esta foto, una corrida en regimen cuesta ~60 lecturas.

QUE GUARDA
----------
Por cada documento de `productos`: el map de precios y los cuatro campos que
necesita el arbol de navegacion (categoria, subcategoria, marca, en_tandil).
Pedirlos todos en el mismo select() no cuesta una lectura mas: Firestore cobra
por documento devuelto, no por campo.

COMO SE SABE QUE SIGUE SIENDO VERDAD
------------------------------------
Dos chequeos baratos antes de confiar en ella:

  1. `catalogo_meta/precios.cache_token` tiene que coincidir con el token de la
     foto (1 lectura). Cualquier script que escriba `productos` por afuera de
     este camino borra ese campo con invalidar(), y la proxima corrida se da
     cuenta.
  2. La cantidad de documentos tiene que coincidir (~61 lecturas: la agregacion
     count() cobra 1 cada 1000, no 1 por documento). Esto agarra altas y bajas
     aunque nadie se haya acordado de invalidar.

Si algo no cuadra, se vuelve a escanear. Como un escaneo completo (60k) no entra
en la cuota diaria, se hace por tramos con un tope de lecturas y se guarda el
cursor: la corrida siguiente retoma donde quedo. Bootstrapear cuesta dos dias y
despues no se vuelve a pagar.

DONDE VIVE
----------
En disco, comprimida, y ademas en Firebase Storage, porque el runner de GitHub
Actions es efimero y sin eso cada corrida empezaria de cero. Storage no consume
cuota de Firestore.
"""
import gzip
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

# Si estamos hablando con el emulador de Firestore, NO se toca el Storage real:
# subir ahi una foto de prueba pisaria la de produccion.
EMULADOR = bool(os.environ.get("FIRESTORE_EMULATOR_HOST"))

try:
    from google.api_core.exceptions import ResourceExhausted
except ImportError:
    ResourceExhausted = Exception

CARPETA = Path(__file__).resolve().parent
ARCHIVO = CARPETA / "cache_catalogo.json.gz"
BUCKET = "compras-super-18da9.firebasestorage.app"
RUTA_STORAGE = "pipeline/cache_catalogo.json.gz"
COLECCION = "productos"
VERSION = 1

# Todo lo que el pipeline necesita saber de un documento. Los cuatro ultimos son
# para regenerar_estructura_tandil.py, que asi no tiene que leer nada.
CAMPOS = ["precios", "categoria", "subcategoria", "marca", "en_tandil"]

# Cuantos documentos se leen por pagina al escanear. Nada critico: solo acota la
# memoria y hace que el progreso se vea.
PAGINA = 5000

# Cuantas lecturas puede gastar el pipeline por dia. La cuota del plan Spark son
# 50.000 y las COMPARTE con la app: si el pipeline se las come, los usuarios se
# quedan sin catalogo hasta la medianoche del Pacifico. 20.000 deja 30.000 para
# la gente. Armar la foto de cero tarda asi 4 dias en vez de 2, que es el precio
# correcto: la app importa mas que la velocidad del bootstrap.
TOPE_DIARIO = 20000


def ahora():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def nuevo_token():
    return uuid.uuid4().hex


def vacia():
    return {"version": VERSION, "token": None, "completa": False, "cursor": None,
            "n_docs": 0, "generado": None, "docs": {}}


def fila(d):
    """Un documento de Firestore -> la fila compacta que guardamos."""
    return [
        {c: v for c, v in (d.get("precios") or {}).items() if v and v > 0},
        (d.get("categoria") or "").strip(),
        (d.get("subcategoria") or "").strip(),
        (d.get("marca") or "").strip().upper(),
        bool(d.get("en_tandil")),
    ]


# ------------------------------------------------------------------ en disco

def cargar(ruta=ARCHIVO):
    ruta = Path(ruta)
    if not ruta.exists():
        return None
    try:
        with gzip.open(ruta, "rt", encoding="utf-8") as f:
            cache = json.load(f)
    except (OSError, ValueError) as e:
        print(f"  cache ilegible ({e}); se descarta")
        return None
    if cache.get("version") != VERSION:
        print(f"  cache de una version vieja ({cache.get('version')}); se descarta")
        return None
    return cache


def guardar(cache, ruta=ARCHIVO):
    cache["generado"] = ahora()
    tmp = Path(str(ruta) + ".tmp")
    with gzip.open(tmp, "wt", encoding="utf-8") as f:
        json.dump(cache, f, separators=(",", ":"))
    tmp.replace(ruta)
    return Path(ruta).stat().st_size


# --------------------------------------------------------------- en Storage

def _bucket():
    from firebase_admin import storage
    return storage.bucket(BUCKET)


def bajar_de_storage(ruta=ARCHIVO):
    """True si trajo algo. Que no haya nada no es un error: la primera vez no
    existe."""
    if EMULADOR:
        print("  (emulador: no toco Storage)")
        return False
    try:
        blob = _bucket().blob(RUTA_STORAGE)
        if not blob.exists():
            print("  no hay cache en Storage todavia")
            return False
        blob.download_to_filename(str(ruta))
        print(f"  cache bajada de Storage ({blob.size / 1024 / 1024:.1f} MB)")
        return True
    except Exception as e:
        print(f"  no pude bajar la cache de Storage: {e}")
        return False


def subir_a_storage(ruta=ARCHIVO):
    if EMULADOR:
        print("  (emulador: no toco Storage)")
        return False
    try:
        blob = _bucket().blob(RUTA_STORAGE)
        blob.upload_from_filename(str(ruta), content_type="application/gzip")
        print(f"  cache subida a Storage ({RUTA_STORAGE})")
        return True
    except Exception as e:
        print(f"  no pude subir la cache a Storage: {e}")
        return False


# ------------------------------------------------------------- validaciones

def contar_documentos(db, coleccion=COLECCION):
    """count() cobra 1 lectura cada 1000 documentos, no 1 por documento: contar
    60k sale ~61 lecturas."""
    resultado = db.collection(coleccion).count().get()
    return int(resultado[0][0].value)


def token_remoto(db):
    doc = db.collection("catalogo_meta").document("precios").get()
    return (doc.to_dict() or {}).get("cache_token") if doc.exists else None


def invalidar(db):
    """La llama cualquier script que escriba `productos` por afuera del refresco.
    Sin esto la foto quedaria mintiendo y el diff de precios saltearia escrituras
    que hacen falta."""
    db.collection("catalogo_meta").document("precios").set(
        {"cache_token": None}, merge=True)


def validar(db, cache, coleccion=COLECCION):
    """(sirve, motivo). Cuesta ~62 lecturas."""
    if cache is None:
        return False, "no hay cache en disco"
    if not cache.get("completa"):
        faltan = "desde " + str(cache.get("cursor")) if cache.get("cursor") else "desde cero"
        return False, f"la cache quedo a medias ({faltan})"
    if not cache.get("token"):
        return False, "la cache no tiene token"

    try:
        remoto = token_remoto(db)
    except ResourceExhausted:
        return False, "no queda cuota de lecturas para validar la foto"
    if remoto != cache["token"]:
        return False, (f"el token no coincide (Firestore: {remoto}, "
                       f"cache: {cache['token']}): alguien escribio el catalogo "
                       f"por afuera del refresco")

    try:
        n = contar_documentos(db, coleccion)
    except ResourceExhausted:
        return False, "no queda cuota de lecturas para contar el catalogo"
    if n != len(cache["docs"]):
        return False, (f"el catalogo tiene {n} documentos y la cache {len(cache['docs'])}: "
                       f"hubo altas o bajas")
    return True, f"cache al dia ({n} documentos)"


# --------------------------------------------------------------- el escaneo

def _start_after(db, coleccion, cursor):
    """El cursor se guarda como id de documento; para ordenar por __name__ hay
    que pasarlo como referencia."""
    return {"__name__": db.collection(coleccion).document(cursor)}


def escanear(db, cache, tope, coleccion=COLECCION):
    """Sigue llenando la cache hasta `tope` lecturas. Devuelve cuantas hizo.

    Se ordena por __name__ y se guarda el ultimo id leido, asi una corrida que
    se queda sin cuota retoma manana en el mismo lugar en vez de empezar de nuevo.
    """
    leidas = 0
    while leidas < tope:
        pagina = min(PAGINA, tope - leidas)
        q = (db.collection(coleccion).select(CAMPOS)
               .order_by("__name__").limit(pagina))
        if cache.get("cursor"):
            q = q.start_after(_start_after(db, coleccion, cache["cursor"]))
        n = 0
        try:
            for doc in q.stream():
                cache["docs"][doc.id] = fila(doc.to_dict() or {})
                cache["cursor"] = doc.id
                n += 1
        except ResourceExhausted:
            # Se acabo la cuota del dia en el medio. El cursor ya apunta al
            # ultimo documento que si entro, asi que lo leido no se pierde:
            # quien llama guarda y manana se retoma desde ahi.
            leidas += n
            print(f"  se acabo la cuota de lecturas con {len(cache['docs'])} "
                  f"documentos en la cache")
            return leidas
        leidas += n
        print(f"  {len(cache['docs'])} documentos en la cache "
              f"({leidas} lecturas esta corrida)...", flush=True)
        if n < pagina:                 # se acabo la coleccion
            cache["completa"] = True
            cache["cursor"] = None
            cache["n_docs"] = len(cache["docs"])
            break
    return leidas


def asegurar(db, tope, ruta=ARCHIVO, coleccion=COLECCION, usar_storage=True):
    """Devuelve (cache, sirve, lecturas). Si no sirve, la deja lo mas avanzada
    que la cuota permita y lo dice."""
    cache = cargar(ruta)
    if cache is None and usar_storage and bajar_de_storage(ruta):
        cache = cargar(ruta)

    sirve, motivo = validar(db, cache, coleccion)
    lecturas = 62 if cache is not None and cache.get("completa") else 0
    print(f"  {motivo}")
    if sirve:
        return cache, True, lecturas

    if cache is None or not cache.get("cursor"):
        cache = vacia()               # de cero
    print(f"  escaneando el catalogo (tope {tope} lecturas)...")
    lecturas += escanear(db, cache, tope, coleccion)
    guardar(cache, ruta)
    # Se sube siempre: el runner de CI es efimero, y si el escaneo quedo a
    # medias lo que importa justamente es no perder el avance.
    if usar_storage:
        subir_a_storage(ruta)
    if not cache["completa"]:
        print(f"  la cache quedo a medias: {len(cache['docs'])} documentos. "
              f"La proxima corrida retoma desde {cache['cursor']}.")
        return cache, False, lecturas
    return cache, True, lecturas


if __name__ == "__main__":
    # Utilidad suelta: ver el estado de la cache sin tocar Firestore.
    c = cargar(sys.argv[1] if len(sys.argv) > 1 else ARCHIVO)
    if c is None:
        print("No hay cache en disco.")
        sys.exit(1)
    print(f"documentos: {len(c['docs'])}")
    print(f"completa:   {c['completa']}")
    print(f"cursor:     {c['cursor']}")
    print(f"token:      {c['token']}")
    print(f"generado:   {c['generado']}")
    con_precio = sum(1 for f in c["docs"].values() if f[0])
    print(f"con precio: {con_precio}")
