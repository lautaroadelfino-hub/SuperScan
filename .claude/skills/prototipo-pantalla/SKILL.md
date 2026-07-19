---
name: prototipo-pantalla
description: >-
  Cómo prototipar rápido una pantalla nueva o un rediseño de SuperScan como
  mockup visual antes de escribir Compose, usando los colores y la tipografía
  reales de la app para que el mockup se parezca al producto. Usá esta skill cada
  vez que haya que diseñar, proyectar o mostrar cómo quedaría una UI —rediseñar el
  Inicio, mockear el comparador de carrito, explorar variantes de una pantalla—
  antes de implementarla en Kotlin.
---

# Prototipar una pantalla antes de codearla en Compose

Compilar Compose en esta máquina tarda minutos (ver `build-verificar`), así que
iterar diseño directo en Kotlin es caro. Conviene prototipar primero en
HTML/artifact con los tokens reales de la app, conseguir el OK visual del usuario,
y recién entonces traducir a Compose.

## Paso 1 — Mockup con la identidad real
Cargá primero la skill `artifact-design` (bases de diseño de artifacts) y aplicá
los tokens de la skill `sistema-diseno` para que el mockup se vea como SuperScan
y no como un genérico: primary #435993, fondo #F7F9FC, tarjetas #E1E2EC, esquinas
redondeadas, componentes tipo Material. Simulá un marco de teléfono (ancho
~390 px) para que se lea como app móvil real.

## Paso 2 — Iterar con el usuario
Mostrá 1–2 variantes y pedí feedback concreto: jerarquía, qué va arriba, qué
sobra, qué es el héroe de la pantalla. El objetivo es acordar **layout y
contenido**, no pixel-perfect. Es barato tirar variantes acá.

## Paso 3 — Traducir a Compose con los idioms del proyecto
Al pasar a Kotlin, reusá los patrones ya establecidos (`Scaffold`, `Card` sobre
`surfaceVariant`, `LazyColumn`/`LazyVerticalGrid`, `FilterChip`, `SkeletonBox`,
`MensajeCentrado`). No inventes un componente si ya existe el equivalente.
Verificá con `build-verificar` y, si querés verlo corriendo, `release-apk`.

## Si la pantalla lleva gráficos o datos
Para charts (Stats, el comparador de precios/carrito) cargá también la skill
`dataviz`: diseña la visualización con criterio (color, ejes, jerarquía) en vez
de improvisar el Canvas.
