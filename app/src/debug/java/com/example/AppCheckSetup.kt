package com.example

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug: proveedor de depuración. La primera vez que corras la app imprime en
 * Logcat un token con el tag `DebugAppCheckProvider`; hay que pegarlo en
 * Firebase Console → App Check → Apps → Administrar tokens de depuración para
 * que las builds locales sigan pudiendo escanear tickets.
 */
internal fun Application.installAppCheck() {
    Firebase.appCheck.installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
    )
}
