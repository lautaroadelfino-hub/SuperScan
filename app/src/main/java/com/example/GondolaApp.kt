package com.example

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.initialize

/**
 * Se encarga de inicializar Firebase y App Check antes que cualquier pantalla.
 *
 * App Check es lo que reemplaza a la API key que antes viajaba dentro del APK:
 * en vez de un secreto embebido, cada llamada a Gemini (vía Firebase AI Logic)
 * lleva un token que prueba que viene de la app real instalada desde Play.
 *
 * El proveedor concreto cambia según la variante y vive en `src/debug` y
 * `src/release`: Play Integrity no puede funcionar en una build de desarrollo
 * que no salió de Play.
 */
class GondolaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(this)
        installAppCheck()
    }
}
