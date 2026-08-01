package com.example.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

// Góndola funciona sin señal (Firestore sirve desde su caché), pero tiene que
// DECIRLO: mostrar precios guardados como si fueran de ahora es mentir. Este
// estado alimenta el banner de la barra superior y el modo "total estimado".
@Composable
fun rememberEstaOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(hayRed(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = hayRed(context)
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (e: SecurityException) {
            // Sin permiso para observar la red: se asume conectada antes que
            // acusar de offline a alguien que no lo está.
            online = true
        }
        onDispose {
            try {
                cm?.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // El callback no llegó a registrarse
            }
        }
    }
    return online
}

private fun hayRed(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
