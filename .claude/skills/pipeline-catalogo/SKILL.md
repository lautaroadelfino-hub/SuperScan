---
name: pipeline-catalogo
description: >-
  Flujo para actualizar el catálogo de productos en Firestore y sus imágenes.
  Usá esta skill siempre que haya que subir o actualizar el CSV de productos,
  regenerar la estructura de categorías, enriquecer imágenes (Open Food Facts o
  VTEX), o deployar índices/reglas de Firestore — aunque el pedido no nombre los
  scripts (p. ej. "cargá los productos nuevos", "faltan imágenes en el catálogo",
  "no aparecen las categorías", "actualizá los precios").
---

# Pipeline del catálogo (`subida_firebase/`)

Todos los scripts usan `subida_firebase/credenciales.json` (service account,
Admin SDK → **no** pasa por las reglas de Firestore; está gitignored, nunca
commitear). Proyecto Firebase: `compras-super-18da9`.

## Subir / actualizar productos
```
cd subida_firebase && python subir_catalogo_v2.py
```
Opción 3 = borrar+subir (recomendado para recarga limpia), 2 = subir sin borrar
(retoma sin duplicar porque el ID del doc es el EAN). Qué hace:
- Normaliza el EAN a 13 dígitos como ID del documento.
- Repara filas del CSV doblemente encomilladas (sin esto se pierden ~4k productos).
- Regenera `catalogo_meta/estructura` (categorías→subcategorías→marcas), que la
  app necesita para la grilla del catálogo.
- Fusiona imágenes ya encontradas (OFF + VTEX, **VTEX gana**) para no pisarlas.
- **No abrir el CSV con Excel** antes de subir: rompe los códigos de barra.

## Imágenes (prioridad VTEX > OFF; cada doc lleva `imagen_fuente`)
- **OFF** (fallback): `python enriquecer_imagenes.py` — cruza contra el dump
  local `off_dump.csv.gz`. La API de búsqueda de OFF tira 503 constante: NO
  usarla para lote masivo, siempre el dump.
- **VTEX** (principal, foto de estudio): `python importar_imagenes_vtex.py`
  (productos con precio) o `--todos` (también sin precio, mucho más lento).
  `--check` prueba Storage, `--sample N` valida sobre N productos. La foto se
  re-hospeda en Firebase Storage (no hotlinking). Estado resumible en
  `imagenes_vtex.json`; `stop_fase2.flag` cancela la fase de productos sin precio.
- Clave: VTEX y OFF guardan los EAN **sin ceros a la izquierda**; los scripts ya
  prueban el EAN original y el limpio.

## Deploy de índices y reglas — lo corre el USUARIO
```
firebase deploy --only firestore:indexes,firestore:rules
```
El agente **no** puede ejecutarlo (es una acción sobre la nube): pedírselo al
usuario. Los índices compuestos nuevos tardan minutos en construirse; hasta
entonces la query da `FAILED_PRECONDITION` "index building" (la app ya lo maneja
con un aviso claro).

## Costos / cuota
Proyecto en Blaze. Las cargas grandes (~60k escrituras) pueden pegar contra la
cuota diaria; todos los scripts son resumibles, así que reintentar retoma sin
duplicar.
