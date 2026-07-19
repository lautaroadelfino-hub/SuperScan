---
name: build-verificar
description: >-
  Cómo compilar, testear y verificar la app Android SuperScan en ESTA máquina de
  poca RAM sin agotar la memoria. Usá esta skill siempre que haya que compilar,
  buildear, correr tests o verificar que un cambio de Kotlin/Compose no rompió
  nada — aunque el pedido no diga "gradle" (p. ej. "probá que compile", "corré
  los tests", "fijate que no rompí nada", "verificá el cambio").
---

# Build y verificación (máquina de poca RAM)

Esta máquina ya sufrió un OOM del JVM compilando. El daemon de Gradle está
acotado a propósito en `gradle.properties` (`-Xmx2048m`, `parallel=false`,
`workers.max=2`). Respetá eso y usá **siempre el JBR que trae Android Studio**,
no otro JDK — es la combinación que se sabe estable acá.

## JAVA_HOME (verificado en esta máquina)
`C:\Program Files\Android\Android Studio\jbr`

## Comandos (PowerShell, que es el shell primario)

Chequeo rápido de que el Kotlin/Compose compila. Es mucho más liviano que armar
el APK, así que es lo que conviene para iterar sobre un cambio:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:compileDebugKotlin --console=plain -q
```

Tests unitarios del dominio (JUnit puro, sin Android — cubren la lógica de
precios, EAN, comparador, fuentes de cadena):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests "com.example.data.*" --console=plain
```

## Reglas
- Los builds tardan varios minutos: lanzalos con `run_in_background` y seguí
  trabajando; llega la notificación al terminar. Evitá bloquear la sesión.
- No subas `-Xmx` ni actives `parallel`: es exactamente lo que gatilló el OOM.
- Si `compileDebugKotlin` da verde, un cambio de UI/lógica está bien encaminado.
  Armar el APK completo solo hace falta para instalarlo y verlo corriendo — para
  eso está la skill `release-apk`.
- `data/Catalogo.kt` no depende de Android: sus tests corren rápido y son la red
  de seguridad de la lógica del catálogo. Si tocás precios/EAN/comparador,
  corrélos.
