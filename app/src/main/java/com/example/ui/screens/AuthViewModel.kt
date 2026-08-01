package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FirebaseRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Las tres pantallas de la puerta de entrada. "Recuperar" es una pantalla
// propia y no un diálogo: quien se olvidó la clave está frustrado y necesita
// un lugar que le explique qué va a pasar.
enum class ModoAuth { LOGIN, REGISTRO, RECUPERAR }

class AuthViewModel(
    private val repository: FirebaseRepository = FirebaseRepository()
) : ViewModel() {
    private val auth = FirebaseAuth.getInstance()

    companion object {
        const val MINIMO_CLAVE = 8
        val CIUDADES = listOf("Tandil")
    }

    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var ciudad by mutableStateOf(CIUDADES.first())
    var modo by mutableStateOf(ModoAuth.LOGIN)
        private set
    var isLoading by mutableStateOf(false)
        private set

    /** Error bajo los campos, en lenguaje humano. Nunca un diálogo. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Email al que se mandó el enlace de recuperación; null mientras no se mandó */
    var enlaceEnviadoA by mutableStateOf<String?>(null)
        private set

    /** Mensaje de éxito para el Snackbar (cuenta creada) */
    var mensajeExito by mutableStateOf<String?>(null)
        private set

    /** Cobertura del catálogo: lo que Góndola ofrece antes de que te registres */
    var estadoPrecios by mutableStateOf<com.example.data.EstadoPrecios?>(null)
        private set

    private val _currentUser = MutableStateFlow(auth.currentUser)
    val currentUser: StateFlow<com.google.firebase.auth.FirebaseUser?> = _currentUser

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _currentUser.value = firebaseAuth.currentUser
    }

    init {
        auth.addAuthStateListener(authStateListener)
        viewModelScope.launch {
            estadoPrecios = try {
                repository.getEstadoPrecios()
            } catch (e: Exception) {
                null // sin dato, no se muestra: nunca un número inventado
            }
        }
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    fun cambiarModo(nuevo: ModoAuth) {
        modo = nuevo
        errorMessage = null
        enlaceEnviadoA = null
    }

    fun consumirMensajeExito() {
        mensajeExito = null
    }

    fun authenticate() {
        val correo = email.trim()
        if (correo.isBlank() || password.isBlank()) {
            errorMessage = "Completá el email y la contraseña."
            return
        }
        if (modo == ModoAuth.REGISTRO && password.length < MINIMO_CLAVE) {
            errorMessage = "La contraseña necesita al menos $MINIMO_CLAVE caracteres."
            return
        }
        isLoading = true
        errorMessage = null

        if (modo == ModoAuth.LOGIN) {
            auth.signInWithEmailAndPassword(correo, password)
                .addOnFailureListener { errorMessage = mensajeDeFirebase(it) }
                .addOnCompleteListener { isLoading = false }
        } else {
            auth.createUserWithEmailAndPassword(correo, password)
                .addOnSuccessListener {
                    // La ciudad decide qué precios ve: se guarda de entrada
                    viewModelScope.launch {
                        try {
                            repository.saveUserCity(ciudad)
                        } catch (e: Exception) {
                            // Que no se pueda guardar la ciudad no invalida la cuenta;
                            // se puede cambiar después desde Perfil.
                        }
                    }
                    mensajeExito = "Cuenta creada · arrancás con rango Pionero"
                }
                .addOnFailureListener { errorMessage = mensajeDeFirebase(it) }
                .addOnCompleteListener { isLoading = false }
        }
    }

    fun recuperarClave() {
        val correo = email.trim()
        if (correo.isBlank()) {
            errorMessage = "Escribí tu email y te mandamos el enlace."
            return
        }
        isLoading = true
        errorMessage = null
        auth.sendPasswordResetEmail(correo)
            .addOnSuccessListener { enlaceEnviadoA = correo }
            .addOnFailureListener { errorMessage = mensajeDeFirebase(it) }
            .addOnCompleteListener { isLoading = false }
    }

    fun logout() {
        auth.signOut()
    }

    // Los códigos de Firebase no son para leer: "ERROR_INVALID_CREDENTIAL" no le
    // dice a nadie que se equivocó de contraseña.
    private fun mensajeDeFirebase(e: Exception): String {
        val codigo = (e as? FirebaseAuthException)?.errorCode
        return when (codigo) {
            "ERROR_INVALID_EMAIL" -> "Ese email no parece válido."
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" ->
                "El email o la contraseña no coinciden."
            "ERROR_USER_NOT_FOUND" -> "No encontramos ninguna cuenta con ese email."
            "ERROR_USER_DISABLED" -> "Esa cuenta está deshabilitada."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "Ese email ya tiene cuenta. Probá entrar."
            "ERROR_WEAK_PASSWORD" -> "Esa contraseña es muy corta: mínimo $MINIMO_CLAVE caracteres."
            "ERROR_TOO_MANY_REQUESTS" -> "Demasiados intentos. Esperá un momento y probá de nuevo."
            else -> {
                val texto = e.message.orEmpty().lowercase()
                if ("network" in texto || "host" in texto || "offline" in texto) {
                    "Parece que estás sin conexión."
                } else {
                    "No pudimos completar la operación. Probá de nuevo."
                }
            }
        }
    }
}
