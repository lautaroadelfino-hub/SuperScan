#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tokens_busqueda.py - Las palabras con las que la app encuentra un producto.

Firestore solo sabe buscar por prefijo, y media gondola tiene lo importante en
el medio del nombre ("FIDEOS SIN GLUTEN", "LECHE DESLACTOSADA"). La salida es
guardar las palabras sueltas en el campo `tokens` y consultarlo con
array_contains.

La MISMA normalizacion corre del lado de la app, en
app/src/main/java/com/example/data/Catalogo.kt (object Busqueda): si un lado
saca los acentos y el otro no, no matchea nada. Cualquier cambio va en los dos.
"""
import re
import unicodedata

LARGO_MINIMO = 2   # "X", "A" no discriminan y engordan el indice
MAX_TOKENS = 30    # tope por documento

_SEPARADORES = re.compile(r"[^A-Z0-9]+")


def normalizar(texto):
    """MAYUSCULAS y sin acentos: 'Te Verde' y 'TE VERDE' tienen que ser lo mismo."""
    if not texto:
        return ""
    descompuesto = unicodedata.normalize("NFD", str(texto))
    sin_acentos = "".join(c for c in descompuesto if unicodedata.category(c) != "Mn")
    return sin_acentos.upper()


def tokenizar(*textos):
    """Las palabras de un producto (descripcion + marca), sin repetir y en orden."""
    vistos = []
    for texto in textos:
        for palabra in _SEPARADORES.split(normalizar(texto)):
            if len(palabra) >= LARGO_MINIMO and palabra not in vistos:
                vistos.append(palabra)
                if len(vistos) == MAX_TOKENS:
                    return vistos
    return vistos
