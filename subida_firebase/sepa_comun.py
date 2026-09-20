#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sepa_comun.py - Lo que todos los scripts que leen SEPA tienen que compartir:
que comercio es cada cadena, en que sucursal miramos, donde estan las columnas
y como se normaliza un EAN o un precio.

Vive aparte de actualizar_precios.py porque bajar_sepa.py necesita estas
constantes ANTES de tener credenciales de Firebase, y no tiene por que arrastrar
firebase_admin para eso. actualizar_precios.py re-exporta todo, asi que los
imports que ya existian (`from actualizar_precios import COMERCIOS, ...`) siguen
funcionando igual.

Sin dependencias: solo stdlib.
"""

# id_comercio de SEPA -> (id_sucursal de Tandil, nombre de cadena en el map
# `precios` de Firestore). Una sola sucursal por cadena: ver Id_sucursal_tandil.txt.
# NUNCA hardcodear las cadenas en otro lado: se resuelven desde aca.
COMERCIOS = {
    "9": (711, "vea"),
    "10": (31, "carrefour"),
    "13": (149, "coop_obrera"),
    "15": (273, "dia"),
}

# Las otras sucursales de Tandil que SEPA publica y hoy no usamos (Carrefour
# tiene tres en la ciudad). bajar_sepa.py las conserva igual porque pesan unos
# KB: si algun dia se suman, no hay que volver a bajar 330 MB.
SUCURSALES_EXTRA = {
    "10": (134, 57),
}

# Columnas reales del formato SEPA (base 0). El EAN esta en id_producto, NO en
# productos_ean, que trae un placeholder ("0" o "1" segun la cadena).
# Cabecera: id_comercio|id_bandera|id_sucursal|id_producto|productos_ean|
#           productos_descripcion|...|productos_marca|productos_precio_lista|...
COL_SUCURSAL, COL_EAN, COL_DESC, COL_MARCA, COL_PRECIO = 2, 3, 5, 8, 9


def sucursales_de(id_comercio):
    """Todas las sucursales de Tandil de un comercio: la que usa el pipeline
    primero, despues las de repuesto."""
    sucursal, _cadena = COMERCIOS[id_comercio]
    return (sucursal,) + tuple(SUCURSALES_EXTRA.get(id_comercio, ()))


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
    """La subcarpeta de un comercio dentro de una descarga de SEPA.

    El nombre es `sepa_{envio}_comercio-sepa-{id}_{fecha}_{hora}`: el numero de
    envio no es fijo (el 31/07 Carrefour vino en el 2 y el resto en el 1), asi
    que se busca por el id. Si hay mas de una, gana la del timestamp mas nuevo:
    son reenvios del mismo dia y el ultimo es el bueno.
    """
    candidatas = sorted(datos.glob(f"*comercio-sepa-{id_comercio}_*"))
    return candidatas[-1] if candidatas else None


def fecha_por_cadena(datos):
    """{cadena: 'AAAA-MM-DD'} con la fecha que cada cadena declara en su
    comercio.csv (columna comercio_ultima_actualizacion).

    Es de cuando son los PRECIOS, no de cuando se subio el archivo: es la unica
    que se le puede mostrar al usuario sin mentirle. Ojo que comercio.csv
    termina con una linea suelta de texto libre ("Ultima Actualizacion: ...")
    que no es CSV, por eso se lee solo la primera fila de datos.
    """
    fechas = {}
    for id_comercio, (_sucursal, cadena) in COMERCIOS.items():
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
            fechas[cadena] = partes[6][:10]
    return fechas
