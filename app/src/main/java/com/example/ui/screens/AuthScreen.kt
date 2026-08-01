package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// La puerta de entrada a góndola. Marca arriba, voseo, y los errores donde
// pasaron: debajo del campo, en lenguaje humano. Nada de velos ni de diálogos.
@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.mensajeExito) {
        viewModel.mensajeExito?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumirMensajeExito()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.background
                ) { Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            if (viewModel.modo == ModoAuth.RECUPERAR) {
                RecuperarClave(viewModel)
            } else {
                LoginORegistro(viewModel)
            }
        }
    }
}

@Composable
private fun LoginORegistro(viewModel: AuthViewModel) {
    val esRegistro = viewModel.modo == ModoAuth.REGISTRO
    var verClave by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(48.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GondolaIconoMarca(tamano = 64.dp)
        Spacer(modifier = Modifier.height(12.dp))
        GondolaLockup(ciudad = null, tamano = 26.sp)
        // Qué hay del otro lado, antes de pedirle una cuenta a nadie
        SelloCobertura(estado = viewModel.estadoPrecios)
    }
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        if (esRegistro) "Creá tu cuenta" else "¡Hola de nuevo!",
        style = MaterialTheme.typography.headlineMedium
    )
    Text(
        if (esRegistro) "Tus listas y tus aportes te siguen a cualquier teléfono."
        else "Entrá para ver tus listas y tu historial.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )

    Spacer(modifier = Modifier.height(24.dp))

    CampoAuth(
        etiqueta = "Email",
        valor = viewModel.email,
        onValor = { viewModel.email = it },
        keyboardType = KeyboardType.Email
    )
    Spacer(modifier = Modifier.height(12.dp))
    CampoAuth(
        etiqueta = "Contraseña",
        valor = viewModel.password,
        onValor = { viewModel.password = it },
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        visualTransformation = if (verClave) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconButton(onClick = { verClave = !verClave }) {
                Icon(
                    if (verClave) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (verClave) "Ocultar contraseña" else "Ver contraseña",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        ayuda = if (esRegistro) "Mínimo ${AuthViewModel.MINIMO_CLAVE} caracteres" else null
    )

    if (esRegistro) {
        Spacer(modifier = Modifier.height(16.dp))
        Text("¿De dónde comprás?", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthViewModel.CIUDADES.forEach { ciudad ->
                FilterChip(
                    selected = viewModel.ciudad == ciudad,
                    onClick = { viewModel.ciudad = ciudad },
                    label = { Text(ciudad) }
                )
            }
            AssistChip(
                onClick = { },
                enabled = false,
                label = { Text("Otra ciudad…") }
            )
        }
        Text(
            "Por ahora Góndola tiene el catálogo de Tandil. Estamos sumando ciudades.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }

    val error = viewModel.errorMessage
    if (error != null) {
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 10.dp)
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = { viewModel.authenticate() },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(999.dp),
        enabled = !viewModel.isLoading
    ) {
        // Spinner adentro del botón: la pantalla no se congela ni se tapa
        if (viewModel.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(if (esRegistro) "Crear cuenta" else "Entrar", fontWeight = FontWeight.Bold)
        }
    }

    if (esRegistro) {
        Text(
            "Al crear la cuenta aceptás que los precios que aportás se comparten anónimos con la comunidad.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    TextButton(
        onClick = { viewModel.cambiarModo(if (esRegistro) ModoAuth.LOGIN else ModoAuth.REGISTRO) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (esRegistro) "¿Ya tenés cuenta? Entrá" else "¿No tenés cuenta? Registrate")
    }

    if (!esRegistro) {
        TextButton(
            onClick = { viewModel.cambiarModo(ModoAuth.RECUPERAR) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "¿Te olvidaste la contraseña? Recuperala",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(modifier = Modifier.height(32.dp))
}

@Composable
private fun RecuperarClave(viewModel: AuthViewModel) {
    val enviadoA = viewModel.enlaceEnviadoA

    Spacer(modifier = Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { viewModel.cambiarModo(ModoAuth.LOGIN) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (enviadoA == null) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.LockReset,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("Recuperá tu clave", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Te mandamos un enlace por email para crear una nueva.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        CampoAuth(
            etiqueta = "Email",
            valor = viewModel.email,
            onValor = { viewModel.email = it },
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done
        )
        val error = viewModel.errorMessage
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { viewModel.recuperarClave() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(999.dp),
            enabled = !viewModel.isLoading
        ) {
            if (viewModel.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Enviarme el enlace", fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Icon(
                    Icons.Default.MarkEmailRead,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Listo: revisá $enviadoA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "El enlace vence en 30 minutos. Si no aparece, mirá en correo no deseado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = { viewModel.recuperarClave() },
                    enabled = !viewModel.isLoading
                ) { Text("Reenviar el enlace") }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = { viewModel.cambiarModo(ModoAuth.LOGIN) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(999.dp)
        ) { Text("Volver a ingresar") }
    }
    Spacer(modifier = Modifier.height(32.dp))
}

// Campo en tarjeta blanca con la etiqueta chiquita arriba, como en el mockup.
@Composable
private fun CampoAuth(
    etiqueta: String,
    valor: String,
    onValor: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    ayuda: String? = null
) {
    Column {
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = valor,
                onValueChange = onValor,
                singleLine = true,
                visualTransformation = visualTransformation,
                trailingIcon = trailing,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                )
            )
        }
        if (ayuda != null) {
            Text(
                ayuda,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}
