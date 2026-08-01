#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
agregar_tokens.py - Rellena el campo `tokens` de los productos que ya estan en
Firestore, para que la busqueda de la app encuentre palabras que van en el
medio del nombre ("sin gluten", "sin tacc", "deslactosada").

No necesita los CSV: lee lo que ya hay en Firestore, calcula los tokens con la
misma normalizacion que usa la app y los escribe con merge=True. Solo escribe
los documentos cuyos tokens cambiaron, asi correrlo dos veces no cuesta el doble.

Uso (simula por defecto, no escribe nada):
    python agregar_tokens.py
    python agregar_tokens.py --aplicar
    python agregar_tokens.py --aplicar --coleccion productos_usuarios

Despues de correrlo hay que tener deployado el indice
(en_tandil ASC, tokens CONTAINS) de firestore.indexes.json:
    firebase deploy --only firestore:indexes
"""
import sys

import firebase_admin
from firebase_admin import credentials, firestore

from tokens_busqueda import tokenizar

try:
    from google.api_core.exceptions import ResourceExhausted
except ImportError:
    ResourceExhausted = Exception

CREDENCIALES = "credenciales.json"
LOTE = 400


def conectar():
    if not firebase_admin._apps:
        firebase_admin.initialize_app(credentials.Certificate(CREDENCIALES))
    return firestore.client()


def main():
    aplicar = "--aplicar" in sys.argv
    coleccion = "productos"
    if "--coleccion" in sys.argv:
        coleccion = sys.argv[sys.argv.index("--coleccion") + 1]

    db = conectar()
    print(f"Leyendo '{coleccion}'...")

    pendientes = []
    total = ya_estaban = 0
    for doc in db.collection(coleccion).select(["descripcion", "marca", "tokens"]).stream():
        total += 1
        datos = doc.to_dict() or {}
        nuevos = tokenizar(datos.get("descripcion"), datos.get("marca"))
        if not nuevos:
            continue
        if datos.get("tokens") == nuevos:
            ya_estaban += 1
            continue
        pendientes.append((doc.id, nuevos))
        if total % 10000 == 0:
            print(f"  {total} leidos...", flush=True)

    print(f"\nProductos leidos: {total}")
    print(f"  ya tenian los tokens al dia: {ya_estaban}")
    print(f"  a escribir: {len(pendientes)}")

    if pendientes:
        print("\n--- 5 ejemplos ---")
        for ean, tokens in pendientes[:5]:
            print(f"  {ean}: {tokens}")

    if not aplicar:
        print("\n(simulacion: no se escribio nada. Correr con --aplicar)")
        return

    print(f"\nEscribiendo {len(pendientes)} documentos...")
    lote = db.batch()
    n = escritos = 0
    for ean, tokens in pendientes:
        lote.set(db.collection(coleccion).document(ean), {"tokens": tokens}, merge=True)
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

    print(f"Listo: {escritos} productos con tokens.")
    print("Acordate de deployar el indice: firebase deploy --only firestore:indexes")


if __name__ == "__main__":
    main()
