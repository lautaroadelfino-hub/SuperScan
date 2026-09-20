#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bajar_sepa.py - Baja el dataset SEPA del portal de Nacion y deja en disco una
carpeta `Datos AAAA-MM-DD/` con la misma forma que la descarga manual, pero
filtrada a las sucursales de Tandil.

Por que filtrar: el zip del dia pesa ~330 MB y los productos.csv suman 1,14 GB,
pero el pipeline se queda con UNA sucursal por cadena. Quedarse solo con esas
filas al extraer convierte 1,14 GB en ~10 MB, y de paso actualizar_precios.py y
altas_nuevas.py (que hoy releen todo, cada uno por su cuenta) pasan a tardar
segundos. La cabecera se copia byte a byte, asi que los scripts de abajo no se
enteran de nada: mismo separador, mismo BOM, mismos indices de columna.

Como publica el portal: son 7 recursos fijos, uno por dia de la semana
(sepa_lunes.zip ... sepa_domingo.zip), que se PISAN cada semana. O sea que la
URL del lunes sirve los datos del lunes pasado hasta que se publica la del
lunes nuevo, alrededor de las 13:20 hora argentina. Bajar sin mirar la fecha es
la forma facil de cargar precios de hace una semana creyendo que son de hoy;
por eso se aborta si el recurso no se actualizo hoy.

El portal ignora el header Range (contesta 200 con el archivo entero aunque
anuncie Accept-Ranges), asi que no hay forma de bajar solo la parte que
interesa: se baja todo y se filtra en el momento.

Fuente: https://datos.produccion.gob.ar/dataset/sepa-precios  (CC-BY-4.0)

Uso:
    python bajar_sepa.py --probar          # solo prueba conectividad y corta
    python bajar_sepa.py                   # baja el dia de hoy
    python bajar_sepa.py --dia martes      # baja otro dia de la semana
    python bajar_sepa.py --permitir-viejo  # acepta un recurso desactualizado
"""
import hashlib
import json
import re
import shutil
import sys
import tempfile
import time
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

from sepa_comun import COMERCIOS, COL_SUCURSAL, sucursales_de, fecha_por_cadena
from tokens_busqueda import normalizar

CARPETA = Path(__file__).resolve().parent
CKAN = "https://datos.produccion.gob.ar/api/3/action/package_show?id=sepa-precios"

# Argentina no tiene horario de verano: un offset fijo es exacto y no depende
# de que la base de zonas horarias este instalada (en Windows no viene).
ARGENTINA = timezone(timedelta(hours=-3))

# El recurso de CKAN se llama por el dia de la semana, con acentos y mayuscula
# ("Miercoles", "Sabado"). normalizar() saca los acentos y sube a mayuscula.
DIAS = ["LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO"]

ARCHIVOS = ("productos.csv", "comercio.csv", "sucursales.csv")

# Horas que puede tener el recurso sin que lo consideremos viejo. Se publica el
# mismo dia al mediodia, asi que 24 h deja margen de sobra y falla fuerte si el
# portal se salteo la actualizacion y la URL sigue sirviendo la de hace 7 dias.
MAX_HORAS = 24

# Dias que puede atrasarse una cadena antes de abortar. Una cadena que deja de
# publicar arrastra hacia atras el sello "precios al ..." de la app sin que
# salte el freno de cobertura de actualizar_precios.py, porque los precios
# siguen estando: lo que esta viejo es el dato.
MAX_DESFASAJE = 10

CHUNK = 1024 * 1024
REINTENTOS = 3


def log(msg=""):
    print(msg, flush=True)


def mb(n):
    return f"{n / 1024 / 1024:,.1f} MB".replace(",", ".")


# --------------------------------------------------------------- el recurso

def elegir_recurso(dia=None):
    """Pide el catalogo a CKAN y devuelve el recurso del dia pedido."""
    log("Consultando el catalogo de SEPA...")
    r = requests.get(CKAN, timeout=(10, 60))
    r.raise_for_status()
    recursos = r.json()["result"]["resources"]

    if dia is None:
        dia = DIAS[datetime.now(ARGENTINA).weekday()]
    dia = normalizar(dia)
    if dia not in DIAS:
        raise SystemExit(f"Dia invalido: {dia}. Opciones: {', '.join(DIAS).lower()}")

    for rec in recursos:
        if normalizar(rec.get("name") or "") == dia:
            return dia, rec
    raise SystemExit(
        f"El dataset ya no publica un recurso para {dia}. "
        f"Recursos disponibles: {[x.get('name') for x in recursos]}"
    )


def antiguedad_horas(recurso):
    """Hace cuantas horas se actualizo el recurso. CKAN lo informa en UTC y sin
    zona, asi que se compara contra un UTC naive."""
    crudo = (recurso.get("last_modified") or "").strip()
    if not crudo:
        return None
    try:
        cuando = datetime.fromisoformat(crudo)
    except ValueError:
        return None
    if cuando.tzinfo is not None:
        cuando = cuando.astimezone(timezone.utc).replace(tzinfo=None)
    ahora = datetime.now(timezone.utc).replace(tzinfo=None)
    return (ahora - cuando).total_seconds() / 3600


# --------------------------------------------------------------- la descarga

def descargar(url, destino, tope_bytes=None):
    """Baja a `destino`. Devuelve (bytes, sha256, segundos, headers).

    Con `tope_bytes` corta apenas pasa ese tamanio: asi el modo --probar mide
    si el portal nos deja bajar sin tragarse los 330 MB.
    """
    ultimo_error = None
    for intento in range(1, REINTENTOS + 1):
        try:
            arranque = time.monotonic()
            sha = hashlib.sha256()
            bajados = 0
            proximo_aviso = 50 * 1024 * 1024
            with requests.get(url, stream=True, timeout=(15, 120)) as r:
                r.raise_for_status()
                with open(destino, "wb") as f:
                    for trozo in r.iter_content(CHUNK):
                        if not trozo:
                            continue
                        f.write(trozo)
                        sha.update(trozo)
                        bajados += len(trozo)
                        if tope_bytes and bajados >= tope_bytes:
                            break
                        if bajados >= proximo_aviso:
                            seg = time.monotonic() - arranque
                            log(f"    {mb(bajados)} ({bajados / seg / 1024 / 1024:.1f} MB/s)")
                            proximo_aviso += 50 * 1024 * 1024
                cabeceras = dict(r.headers)
            return bajados, sha.hexdigest(), time.monotonic() - arranque, cabeceras
        except requests.RequestException as e:
            ultimo_error = e
            if intento < REINTENTOS:
                espera = 15 * intento
                log(f"  fallo el intento {intento} ({e}). Reintento en {espera}s...")
                time.sleep(espera)
    raise SystemExit(f"No se pudo descargar despues de {REINTENTOS} intentos: {ultimo_error}")


# ------------------------------------------------------------ el zip adentro

RE_SELLO = re.compile(r"_(\d{4}-\d{2}-\d{2})_(\d{2}-\d{2}-\d{2})")


def sello(nombre):
    """Para elegir entre dos envios del mismo comercio: gana el mas nuevo."""
    m = RE_SELLO.search(nombre)
    return m.groups() if m else ("", "")


def miembros_del_comercio(z, id_comercio):
    """Miembros del zip externo que son de este comercio.

    El portal empaqueta un zip por comercio adentro del zip del dia, pero si
    alguna vez cambia a carpetas planas esto lo soporta igual.
    """
    marca = f"comercio-sepa-{id_comercio}_"
    return [n for n in z.namelist() if marca in n.rsplit("/", 1)[-1] or marca in n]


def buscar(z, basename):
    for n in z.namelist():
        if n.rsplit("/", 1)[-1].lower() == basename:
            return n
    return None


def filtrar_productos(origen, destino, sucursales):
    """Copia solo las filas de las sucursales pedidas, byte a byte.

    Se trabaja en bytes a proposito: la cabecera trae BOM y el archivo se lee
    despues con `split("|")` e indices fijos, asi que cualquier re-codificacion
    es riesgo puro sin beneficio.
    """
    validas = {str(s).encode() for s in sucursales}
    cabecera = origen.readline()
    if not cabecera:
        return 0, 0
    destino.write(cabecera)
    guardadas = leidas = 0
    for linea in origen:
        leidas += 1
        p = linea.split(b"|", COL_SUCURSAL + 1)
        if len(p) > COL_SUCURSAL and p[COL_SUCURSAL] in validas:
            destino.write(linea)
            guardadas += 1
    return guardadas, leidas


def extraer_comercio(z, miembros, id_comercio, salida):
    """Deja `salida/<nombre del envio>/` con los 3 CSV, el de productos
    filtrado. Devuelve (nombre, filas guardadas, filas leidas)."""
    sucursales = sucursales_de(id_comercio)
    internos = [n for n in miembros if n.lower().endswith(".zip")]

    if internos:
        elegido = max(internos, key=lambda n: (sello(n), n))
        nombre = Path(elegido).name[:-4]
        with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as tmp:
            ruta_tmp = Path(tmp.name)
            # copyfileobj y no read(): el zip interno de Dia solo pesa decenas
            # de MB comprimido, pero esto tiene que andar tambien en la maquina
            # de poca RAM.
            with z.open(elegido) as src:
                shutil.copyfileobj(src, tmp, CHUNK)
        try:
            with zipfile.ZipFile(ruta_tmp) as zi:
                return (nombre,) + volcar(zi, nombre, salida, sucursales)
        finally:
            ruta_tmp.unlink(missing_ok=True)

    # Carpetas planas dentro del zip del dia (no es como publica hoy, pero si
    # cambian el empaquetado no quiero que se caiga todo por esto).
    raices = {n.split("/", 1)[0] for n in miembros if "/" in n}
    if not raices:
        raise SystemExit(f"El comercio {id_comercio} no trae ni zip ni carpeta: {miembros[:3]}")
    nombre = max(raices, key=lambda n: (sello(n), n))
    return (nombre,) + volcar(z, nombre, salida, sucursales, prefijo=nombre + "/")


def volcar(z, nombre, salida, sucursales, prefijo=""):
    destino = salida / nombre
    destino.mkdir(parents=True, exist_ok=True)
    guardadas = leidas = 0
    for archivo in ARCHIVOS:
        if prefijo:
            # Solo bajo el prefijo de ESTE comercio: un buscar() global traeria
            # el CSV de otra cadena y nadie se enteraria.
            miembro = prefijo + archivo if prefijo + archivo in z.namelist() else None
        else:
            miembro = buscar(z, archivo)
        if miembro is None:
            if archivo == "productos.csv":
                raise SystemExit(f"Falta productos.csv en {nombre}")
            log(f"    ADVERTENCIA: falta {archivo} en {nombre}")
            continue
        if archivo == "productos.csv":
            with z.open(miembro) as src, open(destino / archivo, "wb") as dst:
                guardadas, leidas = filtrar_productos(src, dst, sucursales)
        else:
            with z.open(miembro) as src, open(destino / archivo, "wb") as dst:
                shutil.copyfileobj(src, dst, CHUNK)
    return guardadas, leidas


# ------------------------------------------------------------------- guardas

def revisar_fechas(carpeta, hoy, max_desfasaje):
    fechas = fecha_por_cadena(carpeta)
    if not fechas:
        raise SystemExit("No pude leer la fecha de ninguna cadena. Descarga sospechosa.")
    log("\nFecha que declara cada cadena:")
    atrasadas = []
    for cadena in sorted(fechas):
        atraso = (hoy - datetime.strptime(fechas[cadena], "%Y-%m-%d").date()).days
        marca = ""
        if atraso > max_desfasaje:
            marca = f"  <-- ATRASADA {atraso} dias"
            atrasadas.append(cadena)
        log(f"  {cadena:12} {fechas[cadena]}{marca}")
    if atrasadas:
        raise SystemExit(
            f"\nAbortado: {', '.join(atrasadas)} publica datos de hace mas de "
            f"{max_desfasaje} dias. La app mostraria esa fecha vieja para todo "
            f"el catalogo. Revisar el portal, o correr con --max-desfasaje N."
        )
    return fechas


# ---------------------------------------------------------------------- main

def main():
    argv = sys.argv

    def opcion(flag, default=None):
        return argv[argv.index(flag) + 1] if flag in argv else default

    probar = "--probar" in argv
    permitir_viejo = "--permitir-viejo" in argv
    dia = opcion("--dia")
    max_horas = float(opcion("--max-horas", MAX_HORAS))
    max_desfasaje = int(opcion("--max-desfasaje", MAX_DESFASAJE))

    dia, recurso = elegir_recurso(dia)
    url = recurso["url"]
    horas = antiguedad_horas(recurso)
    tamanio = recurso.get("size")

    log(f"\nRecurso: {recurso.get('name')} ({dia})")
    log(f"  url:            {url}")
    log(f"  tamanio:        {mb(tamanio) if tamanio else 'desconocido'}")
    log(f"  actualizado:    {recurso.get('last_modified')} UTC"
        f"{f' (hace {horas:.1f} h)' if horas is not None else ''}")

    viejo = horas is None or horas > max_horas
    if viejo:
        log(f"  AVISO: el recurso no se actualizo en las ultimas {max_horas:g} h. "
            f"Esa URL sirve los datos de la semana pasada.")

    if probar:
        log("\n--probar: bajando los primeros 8 MB para medir...")
        bajados, _sha, seg, cabeceras = descargar(
            url, Path(tempfile.gettempdir()) / "sepa_probe.bin", tope_bytes=8 * 1024 * 1024
        )
        log(f"  OK: {mb(bajados)} en {seg:.1f}s ({bajados / seg / 1024 / 1024:.1f} MB/s)")
        log(f"  Content-Type:  {cabeceras.get('Content-Type')}")
        log(f"  Last-Modified: {cabeceras.get('Last-Modified')}")
        if tamanio:
            log(f"  Los {mb(tamanio)} completos tardarian ~{tamanio / (bajados / seg) / 60:.1f} min")
        log("\nEl portal deja bajar desde aca.")
        return 0

    if viejo and not permitir_viejo:
        raise SystemExit(
            f"\nAbortado: el recurso de {dia.lower()} no se actualizo hoy, asi que "
            f"esa URL todavia sirve los precios de hace una semana. Reintentar mas "
            f"tarde (se publica ~13:20 hora argentina) o forzar con --permitir-viejo."
        )

    hoy = datetime.now(ARGENTINA).date()
    with tempfile.TemporaryDirectory(prefix="sepa_") as tmpdir:
        zip_path = Path(tmpdir) / f"sepa_{dia.lower()}.zip"
        log(f"\nDescargando {mb(tamanio) if tamanio else ''}...")
        bajados, sha, seg, cabeceras = descargar(url, zip_path)
        log(f"  listo: {mb(bajados)} en {seg / 60:.1f} min "
            f"({bajados / seg / 1024 / 1024:.1f} MB/s)")
        if tamanio and abs(bajados - int(tamanio)) > 1024:
            log(f"  AVISO: el catalogo declaraba {mb(tamanio)} y bajaron {mb(bajados)}")

        provisoria = Path(tmpdir) / "salida"
        detalle = {}
        log("\nExtrayendo y filtrando a las sucursales de Tandil...")
        with zipfile.ZipFile(zip_path) as z:
            for id_comercio, (_sucursal, cadena) in COMERCIOS.items():
                miembros = miembros_del_comercio(z, id_comercio)
                if not miembros:
                    raise SystemExit(
                        f"Abortado: el zip no trae al comercio {id_comercio} ({cadena}). "
                        f"Sin las 4 cadenas el comparador de la app no compara nada."
                    )
                nombre, guardadas, leidas = extraer_comercio(z, miembros, id_comercio, provisoria)
                detalle[cadena] = {
                    "carpeta": nombre,
                    "sucursales": list(sucursales_de(id_comercio)),
                    "filas": guardadas,
                    "filas_en_el_pais": leidas,
                }
                log(f"  {cadena:12} {guardadas:7,} filas de {leidas:11,} "
                    f"({nombre})".replace(",", "."))
                if guardadas == 0:
                    raise SystemExit(
                        f"Abortado: {cadena} no trajo ni una fila de sus sucursales "
                        f"{sucursales_de(id_comercio)}. Puede que le hayan cambiado el "
                        f"id_sucursal: revisar sucursales.csv."
                    )

        fechas = revisar_fechas(provisoria, hoy, max_desfasaje)
        fecha_datos = min(fechas.values())

        final = CARPETA / f"Datos {fecha_datos}"
        if final.exists():
            log(f"\nYa existia {final.name}, la reemplazo.")
            shutil.rmtree(final)
        shutil.move(str(provisoria), str(final))

        (final / "descarga.json").write_text(json.dumps({
            "dia": dia,
            "url": url,
            "resource_id": recurso.get("id"),
            "last_modified": recurso.get("last_modified"),
            "http_last_modified": cabeceras.get("Last-Modified"),
            "etag": cabeceras.get("ETag"),
            "bytes": bajados,
            "sha256": sha,
            "bajado": datetime.now(ARGENTINA).isoformat(timespec="seconds"),
            "fecha_datos": fecha_datos,
            "fecha_por_cadena": fechas,
            "cadenas": detalle,
            "fuente": "https://datos.produccion.gob.ar/dataset/sepa-precios (CC-BY-4.0)",
        }, ensure_ascii=False, indent=2), encoding="utf-8")

    total = sum(f.stat().st_size for f in final.rglob("*") if f.is_file())
    log(f"\nListo: {final.name} ({mb(total)}, filtrado de {mb(bajados)} comprimidos)")
    log(f"Precios del {fecha_datos}.")
    print(final.name)          # ultima linea: la lee refrescar_sepa.py
    return 0


if __name__ == "__main__":
    sys.exit(main())
