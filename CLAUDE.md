# SuperScan

App Android de súper: escaneo de tickets (Gemini), catálogo de precios de Tandil
con comparador entre cadenas, precios colaborativos y listas compartidas.
Kotlin + Jetpack Compose (Material3) + Firebase + Room. Idioma del proyecto y de
la UI: **español**.

- App label: **SuperScan** · appId `com.aistudio.gastoscan.xqztl`
- Proyecto Firebase: `compras-super-18da9` (Firestore + Auth + Storage)

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
  `imagen`/`imagen_grande`/`imagen_fuente`, `revisar`. ~60k docs, ~25k con precio.
- `productos_usuarios/{ean13}` — altas colaborativas desde la app.
- `observaciones_precios` — precios que informan los usuarios (ticket/góndola).
- `catalogo_meta/estructura` — árbol categorías→subcategorías→marcas para la grilla
  (Firestore no tiene "distinct"; lo genera el pipeline).
- `shared_lists` (con subcolección `items`), `tickets`, `usuarios`.
- Índices en `firestore.indexes.json`, reglas en `firestore.rules`. Deploy:
  `firebase deploy --only firestore:indexes,firestore:rules` (lo corre el usuario).

## Pipeline de carga: `subida_firebase/`

Admin SDK con `credenciales.json` (service account, **no** pasa por reglas; gitignored).
- `subir_catalogo_v2.py` — sube el CSV a `productos`; repara filas doblemente
  encomilladas; regenera `catalogo_meta/estructura`; fusiona imágenes OFF+VTEX.
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
- Acciones sobre la nube (deploy, publicar) y decisiones de cuota/costos las ejecuta
  el **usuario**, no el agente.
