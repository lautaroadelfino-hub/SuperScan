package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.FirebaseRepository
import com.example.data.LocalRepository
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.AuthViewModel
import com.example.ui.screens.CatalogViewModel
import com.example.ui.screens.MainScreen
import com.example.ui.screens.MainViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = AppDatabase.getDatabase(this)
        val repository = LocalRepository(database)
        // Instancia única: FirebaseRepository configura firestoreSettings al
        // crearse, y eso solo puede hacerse antes del primer uso del cliente.
        val firebaseRepository = FirebaseRepository()
        val prefs = getSharedPreferences("superscan_prefs", MODE_PRIVATE)

        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    return MainViewModel(repository, firebaseRepository = firebaseRepository, prefs = prefs) as T
                }
                if (modelClass.isAssignableFrom(CatalogViewModel::class.java)) {
                    return CatalogViewModel(firebaseRepository) as T
                }
                if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                    return AuthViewModel() as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val authViewModel: AuthViewModel = viewModel(factory = factory)
                    val currentUser by authViewModel.currentUser.collectAsState()
                    
                    if (currentUser == null) {
                        AuthScreen(authViewModel)
                    } else {
                        val mainViewModel: MainViewModel = viewModel(factory = factory)
                        val catalogViewModel: CatalogViewModel = viewModel(factory = factory)
                        MainScreen(mainViewModel, catalogViewModel, onLogout = { authViewModel.logout() })
                    }
                }
            }
        }
    }
}
