package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Cadenas
import com.example.data.DisplayPrice
import com.example.data.Formato
import com.example.data.Precios
import com.example.data.ProductModel

// Atribución de la imagen del detalle:
//  - Open Food Facts (o histórico sin fuente) exige el crédito por licencia CC-BY-SA.
//  - Imágenes de las cadenas (VTEX): "imagen ilustrativa", que puede no coincidir
//    exactamente con la presentación vendida. No se enumeran las cadenas: cualquier
//    fuente que no sea OFF es un súper.
private fun creditoImagen(producto: ProductModel): String? = when {
    producto.imagen == null && producto.imagen_grande == null -> null
    producto.imagen_fuente == null || producto.imagen_fuente == "off" -> "Imagen: Open Food Facts"
    else -> "Imagen ilustrativa"
}

// Detalle de producto: el precio a mostrar (con la observación propia del
// usuario como prioridad máxima) en el bloque amarillo de la etiqueta, y una
// fila por cadena del map precios con barras proporcionales.
@Composable
fun ProductDetailScreen(
    producto: ProductModel,
    displayPrice: DisplayPrice?, // null mientras se resuelve la consulta
    hasCurrentList: Boolean,
    onAddToList: (ProductModel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp)
    ) {
        // Breadcrumb: dónde estoy parado dentro del catálogo
        val ruta = listOf(producto.categoria, producto.subcategoria)
            .filter { it.isNotBlank() }
            .joinToString(" › ")
        if (ruta.isNotBlank()) {
            Text(
                ruta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            contentAlignment = Alignment.Center
        ) {
            MiniaturaProducto(imagen = producto.imagen_grande ?: producto.imagen, size = 150.dp)
        }
        // El crédito no se esconde: es la condición de la licencia
        creditoImagen(producto)?.let { credito ->
            Text(
                credito,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (producto.marca.isNotBlank()) {
            Text(
                producto.marca,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            producto.descripcion,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "EAN ${producto.ean}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )

        if (producto.revisar) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Este dato está en revisión y puede ser impreciso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- El precio, en la etiqueta amarilla ---
        BloquePrecio(
            displayPrice = displayPrice,
            hasCurrentList = hasCurrentList,
            onAddToList = { onAddToList(producto) }
        )

        val comparador = Precios.comparador(producto.precios)
        if (comparador.isNotEmpty()) {
            val maximo = comparador.maxOf { it.precio }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Precio por cadena", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            comparador.forEach { fila ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Cadenas.nombre(fila.cadenaId),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (fila.esMinimo) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.width(96.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((fila.precio / maximo).toFloat().coerceIn(0f, 1f))
                                .clip(CircleShape)
                                .background(
                                    if (fila.esMinimo) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        Formato.precio(fila.precio),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.width(58.dp)) {
                        if (fila.esMinimo) {
                            Text(
                                "la más barata",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                Formato.porcentaje(fila.difPorcentaje),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            Text(
                "Actualizado por tickets y precios de góndola de la comunidad.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Invitación al Modo Súper: el catálogo se sostiene con estos aportes
        Spacer(modifier = Modifier.height(20.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "¿Estás en el súper? Escanealo desde el Modo Súper y contá el precio que ves en la góndola.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BloquePrecio(
    displayPrice: DisplayPrice?,
    hasCurrentList: Boolean,
    onAddToList: () -> Unit
) {
    // Sin precio no hay bloque amarillo: no se pinta de fiesta un dato que falta
    if (displayPrice is DisplayPrice.None) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Sin precio todavía", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "informalo en el súper →",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (hasCurrentList) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onAddToList,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Agregar a la lista")
                    }
                }
            }
        }
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.tertiary,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                when (displayPrice) {
                    null -> SkeletonBox(modifier = Modifier.height(28.dp).fillMaxWidth(0.6f))
                    is DisplayPrice.UserObservation -> {
                        Text(
                            Formato.precio(displayPrice.precio),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                        ProcedenciaPrecio("lo informaste en ${displayPrice.comercio} · ${displayPrice.fecha}")
                    }
                    is DisplayPrice.PublicPrice -> {
                        Text(
                            Formato.precio(displayPrice.precio),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                        ProcedenciaPrecio("informado por usuarios (${displayPrice.n})")
                    }
                    is DisplayPrice.MinPrice -> {
                        Text(
                            Formato.precio(displayPrice.precio),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                        ProcedenciaPrecio("desde · ${Cadenas.nombre(displayPrice.cadena)}")
                    }
                    is DisplayPrice.Failure -> ProcedenciaPrecio(displayPrice.mensaje)
                    else -> {}
                }
            }
            if (hasCurrentList) {
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onAddToList,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onTertiary,
                        contentColor = MaterialTheme.colorScheme.tertiary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("a la lista", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// La procedencia SIEMPRE acompaña al precio: de dónde salió el número es tan
// importante como el número.
@Composable
private fun ProcedenciaPrecio(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.75f)
    )
}
