# Góndola

App Android de súper: escaneo de tickets (Gemini), catálogo de precios de Tandil
con comparador entre cadenas, precios colaborativos y listas compartidas.
Kotlin + Jetpack Compose (Material3) + Firebase + Room. Idioma del proyecto y de
la UI: **español rioplatense, con voseo**.

- App label: **Góndola** · appId `com.aistudio.gastoscan.xqztl` (el id viejo quedó
  del nombre anterior, SuperScan; no se cambia sin romper la instalación)
- Proyecto Firebase: `compras-super-18da9` (Firestore + Auth + Storage)
- Marca, ícono y rediseño: `marca-visual-app/` (paquete de handoff del diseño)

## Build en ESTA máquina (poca RAM — importante)

La máquina sufrió OOM del JVM. Compilar **con el JBR de Android Studio y sin
paralelismo** (ya fijado en `gradle.properties`: `-Xmx2048m`, `parallel=false`).

```bash
JAVA_HOME='C:\Program Files\Android\Android Studio\jbr' ./gradlew.bat :app:compileDebugKotlin --console=plain -q
JAVA_HOME='...jbr' ./gradlew.bat :app:testDebugUnitTest --tests "com.example.data.*" --console=plain
```

- Verificar cambios de UI compilando `:app:compileDebugKotlin` (rápido) antes que el APK completo.
- Los builds tardan varios minutos: lanzarlos con `run_in_background`.

## Arquitectura

- UI Compose por solapas en `MainScreen.kt`: Inicio (historial), Estadísticas,
  Catálogo, Listas, Perfil. Navegación del catálogo con stack propio en `CatalogViewModel`.
- `MainViewModel` (tickets, listas, escaneo) y `CatalogViewModel` (catálogo navegable,
  Paging 3, búsqueda). `FirebaseRepository` es **instancia única** compartida
  (configura `firestoreSettings` antes del primer uso).
- Room (`LocalDatabase`) solo para catálogo personal / fallback de escaneo; la
  fuente de verdad de tickets y listas es Firestore.
- Dominio del catálogo en `data/Catalogo.kt` (ProductModel, DisplayPrice, Cadenas,
  Precios, estructura). Sin dependencias de Android → testeable con JUnit puro.

## Datos en Firestore

- `productos/{ean13}` — **solo lectura desde la app** (reglas lo bloquean). Estructura
  rica: `precios` (map cadena→precio), `precio_min`/`cadena_min`,
  `precio_publico`/`precio_publico_n`, `marca`, `categoria`/`subcategoria`,
  `imagen`/`imagen_grande`/`imagen_fuente`, `revisar`, `en_tandil`, `tokens`.
  ~60k docs; ~25k visibles en Tandil y ~24k con precio.
- `productos_usuarios/{ean13}` — altas colaborativas desde la app (también con `tokens`).
- `observaciones_precios` — precios que informan los usuarios (ticket/góndola).
  Guarda `cadena` (id del map `precios`) además de `comercio` (nombre legible):
  un precio sin súper no se puede comparar con nada.
- `catalogo_meta/estructura` — árbol categorías→subcategorías→marcas para la grilla
  (Firestore no tiene "distinct"; lo genera el pipeline).
- `catalogo_meta/precios` — estado del catálogo de precios: `fecha_datos` (la del
  dato de SEPA, **no** la de la subida), `productos_con_precio`, `precios_totales`,
  `por_cadena`. La app lo muestra en la barra ("precios al 31/07") y en el sello de
  cobertura. **Único doc con lectura pública**: la pantalla de ingreso lo necesita
  y ahí todavía no hay sesión. Son agregados, no hay datos de nadie.
- `shared_lists` (con subcolección `items`), `tickets`, `usuarios`.
- Índices en `firestore.indexes.json`, reglas en `firestore.rules`. Deploy:
  `firebase deploy --only firestore:indexes,firestore:rules`.

## Pipeline de carga: `subida_firebase/`

Admin SDK con `credenciales.json` (service account, **no** pasa por reglas; gitignored).
Todos simulan por defecto y escriben solo con `--aplicar`.

Refresco periódico con una descarga nueva de SEPA (carpetas `Datos AAAA-MM-DD/`,
una subcarpeta por comercio con `productos.csv`; gitignored por tamaño):
- `actualizar_precios.py --datos "Datos AAAA-MM-DD"` — refresca `precios`,
  `precio_min`, `cadena_min` y escribe `catalogo_meta/precios`. Si una cadena dejó
  de publicar un producto le **saca** ese precio (mezclar precios de fechas
  distintas rompe el comparador), con un freno: aborta si una cadena cae a menos
  de la mitad de su cobertura.
- `altas_nuevas.py --datos "..."` — da de alta los EAN que aparecieron y no estaban:
  nombres con los criterios de `corregir_nombres.py` y categoría con Gemini.
- `regenerar_estructura_tandil.py` — regenera el árbol y la cobertura **leyendo
  Firestore** (no el CSV, que quedó viejo). Correr después de cualquier alta.
- `agregar_tokens.py` — rellena `tokens` en documentos viejos.

Carga completa y enriquecimiento (menos frecuente):
- `subir_catalogo_v2.py` — sube el CSV a `productos`; repara filas doblemente
  encomilladas; regenera `catalogo_meta/estructura`; fusiona imágenes OFF+VTEX.
- `corregir_nombres.py` — mejora descripción/marca cruzando las 4 cadenas.
  Ojo: apunta a `Datos 2026-07-10` y espera los CSV planos con el nombre viejo.
- `clasificar_categorias.py` — taxonomía fija + Gemini (`gemini-3.5-flash`).
- `enriquecer_imagenes.py` — imágenes de Open Food Facts vía dump local `off_dump.csv.gz`.
- `importar_imagenes_vtex.py` — imágenes de estudio desde las APIs VTEX de los súper,
  re-hospedadas en Storage. `stop_fase2.flag` cancela la fase de productos sin precio.

## Convenciones y trampas

- **EAN**: normalizar siempre a GTIN-13 con `Ean.normalizar` (rellena ceros). Ojo:
  VTEX/OFF guardan los códigos **sin** ceros a la izquierda.
- **Nunca hardcodear las cadenas** (vea/carrefour/...): iterar el map `precios` y
  resolver nombres legibles con `Cadenas.nombre`. Las cadenas van a crecer.
- **No "corregir" versiones que el usuario dice que funcionan** aunque parezcan
  posteriores al cutoff: modelo Gemini `gemini-3.5-flash`, APIs, etc.
- Atribución de imágenes: `imagen_fuente == "off"` (o null histórico) exige crédito
  CC-BY-SA; fuentes de súper → "imagen ilustrativa".
- **Búsqueda**: Firestore solo sabe prefijos, así que se busca en dos pasadas —
  prefijo sobre `descripcion` y palabra sobre el array `tokens` (array-contains).
  `Busqueda.tokenizar` (Kotlin) y `subida_firebase/tokens_busqueda.py` tienen que
  normalizar **igual**; si un lado saca los acentos y el otro no, no matchea nada.
- **Códigos de balanza**: los prefijos GS1 20-29 (y los cortos rellenados con ceros)
  son numeración interna de cada súper para lo que se vende por peso. No se dan de
  alta en `productos`: el mismo número es otra cosa en cada cadena, y el catálogo es
  compartido y se navega escaneando.
- Una sola sucursal por cadena en Tandil (Vea 711, Carrefour 31, Coop 149, Día 273);
  ver `Id_sucursal_tandil.txt`.
- **UX**: los errores de acción van a Snackbar y los de carga a una tarjeta inline
  con "Reintentar". El `AlertDialog` queda **solo** para acciones destructivas.
- Decisiones de cuota y costos son del **usuario**. Ejecutar deploys y escrituras
  masivas requiere que él lo pida; cuando lo pide, simular primero y reportar los
  números antes de aplicar.
