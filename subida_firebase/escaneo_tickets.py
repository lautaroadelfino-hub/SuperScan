# -*- coding: utf-8 -*-
"""
escaneo_tickets.py - Prende o apaga el lector de tickets de la app, sin publicar
una version nueva en Play.

El lector depende de un proveedor de IA externo. Cuando ese proveedor se cae o se
queda sin cuota, no tiene sentido que el usuario saque la foto, espere y recien
ahi se coma un error: mejor avisarle antes de abrir la camara.

Escribe el documento catalogo_meta/escaneo, que la app lee al arrancar:

    habilitado : bool   -> si es False, el boton TICKET avisa en vez de abrir la camara
    mensaje    : str    -> que decirle al usuario (si va vacio, la app usa un texto propio)

Ojo: el ESCANEO DE CODIGOS DE BARRAS no se toca con esto. Ese corre con ML Kit en
el propio telefono, no depende de ningun servicio externo y nunca se apaga.

Uso:
    python escaneo_tickets.py                         # muestra el estado actual
    python escaneo_tickets.py --apagar --aplicar
    python escaneo_tickets.py --apagar --mensaje "Volvemos el lunes" --aplicar
    python escaneo_tickets.py --prender --aplicar

Como todos los scripts de este pipeline, simula por defecto y solo escribe con --aplicar.
"""
import sys

import firebase_admin
from firebase_admin import credentials, firestore

CREDENCIALES = "credenciales.json"
DOC = ("catalogo_meta", "escaneo")

# Este texto lo lee el usuario final, asi que va con acentos y en voseo. Vive
# aca adentro (archivo UTF-8) y no se pasa por linea de comandos, porque la
# consola de Windows rompe los acentos.
MENSAJE_DEFECTO = (
    "El lector de tickets está en mantenimiento: estamos cambiando de proveedor. "
    "Mientras tanto podés usar el catálogo, el comparador de precios y tus listas "
    "con normalidad."
)


def conectar():
    if not firebase_admin._apps:
        firebase_admin.initialize_app(credentials.Certificate(CREDENCIALES))
    return firestore.client()


def leer_argumento(bandera, defecto=None):
    """Devuelve el valor que sigue a `bandera` en argv, o `defecto`."""
    if bandera not in sys.argv:
        return defecto
    i = sys.argv.index(bandera)
    return sys.argv[i + 1] if i + 1 < len(sys.argv) else defecto


def main():
    aplicar = "--aplicar" in sys.argv
    apagar = "--apagar" in sys.argv
    prender = "--prender" in sys.argv

    if apagar and prender:
        raise SystemExit("Elegi una sola: --apagar o --prender")

    db = conectar()
    ref = db.collection(DOC[0]).document(DOC[1])
    actual = ref.get()
    estado = actual.to_dict() if actual.exists else None

    if estado is None:
        print(f"{DOC[0]}/{DOC[1]}: no existe todavia.")
        print("  (la app interpreta eso como HABILITADO: falla hacia que el lector ande)")
    else:
        print(f"{DOC[0]}/{DOC[1]}:")
        print(f"  habilitado = {estado.get('habilitado')}")
        print(f"  mensaje    = {estado.get('mensaje') or '(vacio)'}")

    if not (apagar or prender):
        print("\nNada que cambiar. Pasa --apagar o --prender.")
        return

    nuevo = {
        "habilitado": bool(prender),
        "mensaje": "" if prender else leer_argumento("--mensaje", MENSAJE_DEFECTO),
    }

    print("\nQuedaria asi:")
    print(f"  habilitado = {nuevo['habilitado']}")
    print(f"  mensaje    = {nuevo['mensaje'] or '(vacio)'}")

    if not aplicar:
        print("\n(simulacion: no se escribio nada. Correr con --aplicar)")
        return

    ref.set(nuevo, merge=True)
    print("\nListo: 1 documento escrito.")
    print("La app lo toma al abrirse. Los que ya la tengan abierta lo ven al reiniciarla.")


if __name__ == "__main__":
    main()
