#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
refrescar_sepa.py - El refresco de precios completo, de punta a punta: baja la
descarga nueva de SEPA, actualiza los precios del catalogo, encola los
productos nuevos y regenera el arbol de navegacion.

Es lo que corre el workflow semanal (.github/workflows/precios-sepa.yml), pero
anda igual a mano en Windows. Simula por defecto, como todo el pipeline.

El orden NO es negociable:

    1. bajar_sepa.py                    -> Datos AAAA-MM-DD/ (filtrado a Tandil)
    2. actualizar_precios.py            -> precios, precio_min, cadena_min
                                           y catalogo_meta/precios.fecha_datos
    3. altas_nuevas.py --sin-ia         -> altas_pendientes.json (no da de alta)
    4. regenerar_estructura_tandil.py   -> catalogo_meta/estructura y los
                                           contadores de cobertura

El 4 va despues del 2 porque los contadores de cobertura del sello de la app
(productos_con_precio, precios_totales, por_cadena) los escribe regenerar, y
viven en el mismo documento que la fecha que escribe actualizar_precios.

Por que las altas van con --sin-ia: clasificar un producto nuevo necesita
Gemini y los creditos estan agotados. En vez de perderlos, quedan encolados en
altas_pendientes.json con nombre, marca y precios ya resueltos.

Uso:
    python refrescar_sepa.py                      # simula todo
    python refrescar_sepa.py --aplicar            # escribe en Firestore
    python refrescar_sepa.py --aplicar --si-hace-falta   # sale si ya esta al dia
    python refrescar_sepa.py --usar "Datos 2026-09-19"   # sin volver a bajar
"""
import json
import shutil
import subprocess
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

CARPETA = Path(__file__).resolve().parent

# Si el catalogo ya tiene datos mas nuevos que esto, no hay nada que refrescar.
# Sirve para programar dos disparos el mismo dia (por si el portal se atrasa)
# sin que el segundo repita el trabajo.
FRESCURA_DIAS = 1

# Cuantas descargas viejas se conservan. Solo se borran las carpetas que hizo
# bajar_sepa.py (las que tienen descarga.json): una descarga manual del usuario
# no se toca nunca.
CONSERVAR = 2

# Puente entre la etapa 2 y la 3: actualizar_precios.py escanea el catalogo
# entero para armar el diff y de paso anota que EAN de SEPA no estaban. Pasarle
# esa lista a altas_nuevas.py le evita repetir ese escaneo, que son ~60k
# lecturas de Firestore, la mitad del costo de todo el refresco.
NUEVOS = "nuevos_sepa.txt"


def log(msg=""):
    print(msg, flush=True)


def titulo(texto):
    log("\n" + "=" * 70)
    log(texto)
    log("=" * 70)


def correr(script, *args):
    """Corre un script del pipeline mostrando su salida en vivo.

    cwd fijo en subida_firebase/: hay scripts que buscan credenciales.json
    relativo al directorio actual y un job arranca con el cwd que se le antoje.
    """
    cmd = [sys.executable, script, *args]
    log(f"$ python {' '.join([script, *args])}\n")
    proceso = subprocess.Popen(
        cmd, cwd=CARPETA, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    lineas = []
    for linea in proceso.stdout:
        sys.stdout.write(linea)
        sys.stdout.flush()
        lineas.append(linea.rstrip("\n"))
    return proceso.wait(), lineas


def ya_esta_al_dia(frescura):
    """True si catalogo_meta/precios ya tiene datos lo bastante nuevos."""
    import firebase_admin
    from firebase_admin import credentials, firestore
    if not firebase_admin._apps:
        firebase_admin.initialize_app(
            credentials.Certificate(str(CARPETA / "credenciales.json")))
    doc = firestore.client().collection("catalogo_meta").document("precios").get()
    fecha = (doc.to_dict() or {}).get("fecha_datos") if doc.exists else None
    if not fecha:
        return False
    atraso = (date.today() - datetime.strptime(fecha, "%Y-%m-%d").date()).days
    log(f"El catalogo tiene precios del {fecha} (hace {atraso} dias).")
    return atraso <= frescura


def construir_cache(tope):
    """Deja la foto del catalogo lo mas completa que permita la cuota del dia.

    Armarla de cero son ~60k lecturas y el plan Spark da 50k por dia, asi que la
    primera vez lleva dos corridas. Una vez completa, esto cuesta ~62 lecturas y
    sale enseguida: por eso lo corre un cron diario, que ademas la reconstruye
    sola si algun script la invalido.
    """
    import firebase_admin
    from firebase_admin import credentials, firestore
    import cache_catalogo

    titulo("Foto del catalogo")
    if not firebase_admin._apps:
        firebase_admin.initialize_app(
            credentials.Certificate(str(CARPETA / "credenciales.json")),
            {"storageBucket": cache_catalogo.BUCKET})
    db = firestore.client()
    try:
        _cache, sirve, lecturas = cache_catalogo.asegurar(db, tope)
    except cache_catalogo.ResourceExhausted:
        log("\nSe acabo la cuota diaria de lecturas de Firestore. "
            "El cron de manana retoma donde quedo.")
        return 2
    log(f"\n{'Completa' if sirve else 'Todavia incompleta'} ({lecturas} lecturas).")
    return 0 if sirve else 2


def limpiar_viejas(conservar):
    """Borra descargas viejas, pero solo las que hicimos nosotros."""
    nuestras = sorted(
        d for d in CARPETA.glob("Datos *")
        if d.is_dir() and (d / "descarga.json").exists()
    )
    for vieja in nuestras[:-conservar] if conservar else nuestras:
        log(f"  borrando descarga vieja: {vieja.name}")
        shutil.rmtree(vieja, ignore_errors=True)


def resumen(carpeta, salidas):
    titulo("RESUMEN")
    manifiesto = json.loads((carpeta / "descarga.json").read_text(encoding="utf-8"))
    log(f"Descarga:     {carpeta.name}  ({manifiesto['dia'].lower()}, "
        f"{manifiesto['bytes'] / 1024 / 1024:.0f} MB)")
    log(f"Precios del:  {manifiesto['fecha_datos']}")
    log("Filas por cadena:")
    for cadena, d in sorted(manifiesto["cadenas"].items()):
        log(f"  {cadena:12} {d['filas']:7} filas   (fecha {manifiesto['fecha_por_cadena'].get(cadena, '?')})")

    def buscar(etapa, prefijo):
        for linea in salidas.get(etapa, []):
            if linea.startswith(prefijo):
                return linea.strip()
        return None

    log("")
    for etiqueta, etapa, prefijo in [
        ("Productos a actualizar", "precios", "Productos a actualizar:"),
        ("Precios dados de baja", "precios", "  precios de cadena que se dan de baja:"),
        ("EAN nuevos", "precios", "EAN nuevos que NO estan en el catalogo"),
        ("Altas encoladas", "altas", "Encolados "),
    ]:
        linea = buscar(etapa, prefijo)
        if linea:
            log(f"  {linea}")


def main():
    argv = sys.argv

    def opcion(flag, default=None):
        return argv[argv.index(flag) + 1] if flag in argv else default

    aplicar = "--aplicar" in argv
    si_hace_falta = "--si-hace-falta" in argv
    usar = opcion("--usar")          # re-correr sobre una descarga que ya esta
    sin_cache = "--sin-cache" in argv
    solo_cache = "--solo-cache" in argv
    tope = opcion("--tope-lecturas", "20000")   # el resto de la cuota es para la app
    conservar = int(opcion("--conservar", CONSERVAR))
    frescura = int(opcion("--frescura", FRESCURA_DIAS))

    # Lo que se le pasa tal cual a bajar_sepa.py
    args_bajada = []
    for flag in ("--dia", "--max-horas", "--max-desfasaje"):
        if flag in argv:
            args_bajada += [flag, opcion(flag)]
    if "--permitir-viejo" in argv:
        args_bajada.append("--permitir-viejo")

    log(f"Refresco de precios SEPA - {datetime.now():%Y-%m-%d %H:%M}")
    log("MODO SIMULACION (nada se escribe; agregar --aplicar)" if not aplicar
        else "MODO APLICAR (se escribe en Firestore)")

    if solo_cache:
        return construir_cache(int(tope))

    if si_hace_falta and ya_esta_al_dia(frescura):
        log("Nada que hacer: el catalogo ya esta al dia.")
        return 0

    salidas = {}

    # 1 -----------------------------------------------------------------
    if usar:
        titulo("1/4  Bajando SEPA  (salteado: --usar)")
        carpeta = CARPETA / usar
        if not carpeta.is_dir():
            log(f"No existe la carpeta {carpeta}")
            return 1
        log(f"Uso la descarga que ya estaba: {carpeta.name}")
    else:
        titulo("1/4  Bajando SEPA")
        codigo, lineas = correr("bajar_sepa.py", *args_bajada)
        if codigo != 0:
            log(f"\nLa descarga fallo (codigo {codigo}). No sigo: sin datos nuevos no "
                f"hay nada que actualizar.")
            return codigo
        carpeta = CARPETA / lineas[-1].strip()   # bajar_sepa imprime el nombre al final
        if not carpeta.is_dir():
            log(f"\nNo encuentro la carpeta que dijo la descarga: {lineas[-1]!r}")
            return 1

    # 2 -----------------------------------------------------------------
    titulo("2/4  Actualizando precios")
    codigo, salidas["precios"] = correr(
        "actualizar_precios.py", "--datos", carpeta.name,
        "--volcar-nuevos", NUEVOS,
        *(["--escaneo-completo"] if sin_cache
          else ["--cache", "--tope-lecturas", tope]),
        *(["--aplicar"] if aplicar else []))
    if codigo == 2:
        # La foto del catalogo todavia se esta armando. No es un error: son
        # 60k documentos y en Spark no entran en un dia.
        log("\nLa foto del catalogo quedo a medias; la proxima corrida "
            "retoma donde quedo. Nada que hacer hoy.")
        return 0
    if codigo != 0:
        log(f"\nactualizar_precios fallo (codigo {codigo}). No sigo: si salto el "
            f"freno de cobertura, regenerar la estructura escribiria contadores "
            f"de un catalogo a medio actualizar.")
        return codigo

    # 3 -----------------------------------------------------------------
    titulo("3/4  Encolando productos nuevos (sin IA)")
    codigo, salidas["altas"] = correr(
        "altas_nuevas.py", "--datos", carpeta.name, "--sin-ia", "--nuevos", NUEVOS,
        *(["--aplicar"] if aplicar else []))
    fallo_altas = codigo != 0
    if fallo_altas:
        # No corta la corrida: los precios ya estan escritos y la estructura
        # tiene que quedar consistente igual. Se reporta al final.
        log(f"\nAVISO: el encolado de altas fallo (codigo {codigo}). Sigo con la "
            f"estructura para no dejar el catalogo a medias.")

    # 4 -----------------------------------------------------------------
    titulo("4/4  Regenerando el arbol de navegacion")
    codigo, salidas["estructura"] = correr(
        "regenerar_estructura_tandil.py",
        *([] if sin_cache else ["--desde-cache"]),
        *(["--aplicar"] if aplicar else []))
    if codigo != 0:
        log(f"\nregenerar_estructura fallo (codigo {codigo}). El catalogo quedo con "
            f"precios nuevos pero el arbol y los contadores de cobertura viejos.")
        return codigo

    resumen(carpeta, salidas)
    if conservar:
        log("")
        limpiar_viejas(conservar)

    if fallo_altas:
        log("\nTerminado CON ERRORES: los precios se actualizaron, pero el "
            "encolado de altas nuevas fallo.")
        return 1
    log("\nListo." if aplicar else "\nListo (simulacion: no se escribio nada).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
