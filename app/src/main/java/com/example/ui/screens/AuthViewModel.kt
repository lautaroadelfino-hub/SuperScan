package com.example.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AuthViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var isLogin by mutableStateOf(true)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    
    private val _currentUser = MutableStateFlow(auth.currentUser)
    val currentUser: StateFlow<com.google.firebase.auth.FirebaseUser?> = _currentUser
    
    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
        }
    }
    
    fun authenticate() {
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Completa todos los campos"
            return
        }
        isLoading = true
        errorMessage = null
        
        if (isLogin) {
            auth.signInWithEmailAndPassword(email.trim(), password)
                .addOnFailureListener { errorMessage = it.message }
                .addOnCompleteListener { isLoading = false }
        } else {
            auth.createUserWithEmailAndPassword(email.trim(), password)
                .addOnFailureListener { errorMessage = it.message }
                .addOnCompleteListener { isLoading = false }
        }
    }
    
    fun logout() {
        auth.signOut()
    }
}
