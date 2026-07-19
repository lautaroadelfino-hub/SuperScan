"""Genera el doc catalogo_meta/estructura a partir de la coleccion productos.

NOTA: subida_firebase/subir_catalogo_v2.py ya regenera la estructura
automaticamente en cada subida (con el Admin SDK, sin tocar las reglas).
Este script queda solo para regenerarla SIN resubir el catalogo.

La app necesita este doc para la grilla del catalogo (Firestore no tiene
"distinct" y escanear 60k docs desde el telefono no es opcion). Este script
recorre `productos` UNA vez con proyeccion (solo categoria/subcategoria/marca),
arma el arbol categorias -> subcategorias -> marcas y lo escribe en
catalogo_meta/estructura.

Uso:
  python scripts/generar_estructura.py scan     # lee productos y arma estructura.json
  python scripts/generar_estructura.py write    # sube estructura.json a Firestore
  python scripts/generar_estructura.py verify   # lee el doc subido y muestra un resumen

Autenticacion: usuario anonimo de Firebase Auth (las reglas permiten leer a
cualquier usuario autenticado). Para `write`, las reglas tienen que permitir
temporalmente la escritura al UID anonimo que imprime `scan` (ver README del
proceso en el propio repo / conversacion).
"""

import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

# API key WEB de Firebase (publica por diseno, es la misma del APK)
API_KEY = "AIzaSyD2oah4hzCHJZBv5AZiWKPkU69htG77Bbw"
PROJECT = "compras-super-18da9"
BASE = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents"

STATE_DIR = Path(__file__).resolve().parent
ESTRUCTURA_JSON = STATE_DIR / "estructura.json"
SESION_JSON = STATE_DIR / "sesion_anonima.json"  # token efimero (~1h), gitignorear

LIMITE_DOC_BYTES = 1_000_000  # limite duro de Firestore: 1 MiB por documento


def http_json(url: str, data: dict | None = None, method: str = "POST", token: str | None = None) -> dict:
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)


def sesion_anonima() -> dict:
    """Crea (o reutiliza) un usuario anonimo y devuelve {idToken, localId}."""
    if SESION_JSON.exists():
        return json.loads(SESION_JSON.read_text())
    res = http_json(
        f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={API_KEY}",
        {"returnSecureToken": True},
    )
    sesion = {"idToken": res["idToken"], "localId": res["localId"]}
    SESION_JSON.write_text(json.dumps(sesion))
    return sesion


def scan() -> None:
    sesion = sesion_anonima()
    arbol: dict[str, dict[str, set[str]]] = {}
    page_token = None
    docs = 0
    while True:
        params = {
            "pageSize": "300",
            # Proyeccion: solo los 3 campos que hacen falta, no el doc entero
            "mask.fieldPaths": ["categoria", "subcategoria", "marca"],
        }
        qs = urllib.parse.urlencode(params, doseq=True)
        if page_token:
            qs += "&pageToken=" + urllib.parse.quote(page_token)
        res = http_json(f"{BASE}/productos?{qs}", method="GET", token=sesion["idToken"])
        for doc in res.get("documents", []):
            campos = doc.get("fields", {})
            cat = campos.get("categoria", {}).get("stringValue", "")
            sub = campos.get("subcategoria", {}).get("stringValue", "")
            marca = campos.get("marca", {}).get("stringValue", "")
            if not cat or not sub:
                continue
            marcas = arbol.setdefault(cat, {}).setdefault(sub, set())
            if marca.strip():
                marcas.add(marca.strip())
            docs += 1
        page_token = res.get("nextPageToken")
        if docs and docs % 6000 < 300:
            print(f"  ... {docs} productos leidos")
        if not page_token:
            break

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
    ESTRUCTURA_JSON.write_text(json.dumps(estructura, ensure_ascii=False, indent=1), encoding="utf-8")

    tamano = len(json.dumps(estructura, ensure_ascii=False).encode())
    n_subs = sum(len(c["subcategorias"]) for c in estructura["categorias"])
    print(f"Listo: {docs} productos -> {len(estructura['categorias'])} categorias, "
          f"{n_subs} subcategorias. Tamano aprox: {tamano / 1024:.0f} KB")
    if tamano > LIMITE_DOC_BYTES * 0.8:
        print("ADVERTENCIA: cerca del limite de 1 MiB por documento de Firestore; "
              "habria que partir la estructura en un doc por categoria.")
    print(f"UID anonimo para la regla temporal de escritura: {sesion['localId']}")


def a_valor_firestore(v):
    if isinstance(v, str):
        return {"stringValue": v}
    if isinstance(v, list):
        return {"arrayValue": {"values": [a_valor_firestore(x) for x in v]}}
    if isinstance(v, dict):
        return {"mapValue": {"fields": {k: a_valor_firestore(x) for k, x in v.items()}}}
    raise TypeError(f"Tipo no soportado: {type(v)}")


def write() -> None:
    sesion = sesion_anonima()
    estructura = json.loads(ESTRUCTURA_JSON.read_text(encoding="utf-8"))
    payload = {"fields": {k: a_valor_firestore(v) for k, v in estructura.items()}}
    http_json(f"{BASE}/catalogo_meta/estructura", payload, method="PATCH", token=sesion["idToken"])
    print("catalogo_meta/estructura escrito.")


def verify() -> None:
    sesion = sesion_anonima()
    res = http_json(f"{BASE}/catalogo_meta/estructura", method="GET", token=sesion["idToken"])
    cats = res["fields"]["categorias"]["arrayValue"]["values"]
    print(f"Doc presente con {len(cats)} categorias:")
    for c in cats[:30]:
        campos = c["mapValue"]["fields"]
        nombre = campos["nombre"]["stringValue"]
        subs = campos["subcategorias"]["arrayValue"]["values"]
        print(f"  - {nombre} ({len(subs)} subcategorias)")


if __name__ == "__main__":
    modo = sys.argv[1] if len(sys.argv) > 1 else ""
    if modo == "scan":
        scan()
    elif modo == "write":
        write()
    elif modo == "verify":
        verify()
    else:
        print(__doc__)
        sys.exit(1)
