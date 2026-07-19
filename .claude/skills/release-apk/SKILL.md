---
name: release-apk
description: >-
  Cómo generar e instalar el APK debug de SuperScan para verla CORRIENDO en un
  dispositivo o emulador (no solo verificar que compila). Usá esta skill cuando
  el objetivo sea ver un cambio funcionando en la app real — p. ej. "quiero ver
  las imágenes en la app", "instalá la app", "generá el APK", "probalo en el
  celular", "quiero verlo andando".
---

# Generar e instalar el APK debug

Para VER un cambio corriendo en la app real. Si solo hace falta confirmar que el
código compila, usá la skill `build-verificar` (mucho más rápida que armar el
APK). Misma máquina de poca RAM: JBR de Android Studio y lanzar en segundo plano.

## Generar el APK
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleDebug --console=plain
```
Es bastante más pesado que `compileDebugKotlin`: lanzalo con `run_in_background`.
El APK queda en:
```
app/build/outputs/apk/debug/app-debug.apk
```

## Instalar
- Ver si hay dispositivo/emulador conectado: `adb devices`.
- Si hay: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (`-r` reinstala conservando datos).
- Si no hay ninguno conectado: pasarle la ruta del `.apk` al usuario para que lo
  instale, o sugerirle abrir el proyecto en Android Studio y darle Run ▶ (es la
  vía más simple para probar en su dispositivo).

## Notas
- El build debug se firma solo con el keystore estándar de `~/.android` en un
  checkout local (ya contemplado en `app/build.gradle.kts`): no hay que
  configurar firma.
- Después de re-instalar, los cambios de datos que ya están en Firestore (p. ej.
  imágenes nuevas del pipeline) aparecen sin más: la app los lee al vuelo.
