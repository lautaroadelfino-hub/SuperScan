package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// La marca «góndola», en un solo lugar. El wordmark se usa DENTRO de la app; el
// ícono de La Etiqueta solo convive con él en Auth y en el splash.
// Ver marca-visual-app/MARCA-E-ICONO.md.

/**
 * Lockup del wordmark: «góndola» + el chip de la ciudad activa pegado al lado.
 * El chip es dato (dónde estás comprando), no decoración.
 *
 * Siempre en minúsculas, Roboto Black, tracking −0.03em, verde sobre papel o
 * blanco. Nunca sobre amarillo.
 */
@Composable
fun GondolaLockup(
    modifier: Modifier = Modifier,
    ciudad: String? = "Tandil",
    tamano: TextUnit = 20.sp
) {
    Row(
        modifier = modifier.defaultMinSize(minHeight = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "góndola",
            fontSize = tamano,
            fontWeight = FontWeight.Black,
            // −0.03em: a 20sp son −0,6sp
            letterSpacing = (tamano.value * -0.03f).sp,
            color = MaterialTheme.colorScheme.primary
        )
        if (ciudad != null) {
            // Área de respiro: la altura de la «g» a cada lado
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    ciudad,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/**
 * El ícono de la app dibujado en Compose: círculo verde + la etiqueta amarilla
 * de precio con su ojal y la «g». Mismas proporciones que el launcher icon
 * (etiqueta ≈62×41 sobre 108, ojal r≈4.5, rotación −8°).
 */
@Composable
fun GondolaIconoMarca(
    modifier: Modifier = Modifier,
    tamano: Dp = 64.dp
) {
    val etiquetaAncho = tamano * 0.68f
    val etiquetaAlto = tamano * 0.45f
    val ojal = tamano * 0.10f
    Box(
        modifier = modifier
            .size(tamano)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = etiquetaAncho, height = etiquetaAlto)
                .rotate(-8f)
                .background(
                    MaterialTheme.colorScheme.tertiary,
                    RoundedCornerShape(tamano * 0.10f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(etiquetaAncho * 0.10f)
            ) {
                // El ojal por donde pasa el hilo: del color del fondo
                Box(
                    modifier = Modifier
                        .size(ojal)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Text(
                    "g",
                    fontSize = (tamano.value * 0.34f).sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier
                        .height(etiquetaAlto)
                        // La «g» se apoya en la caja de texto: subirla un pelo
                        // la deja ópticamente centrada pese a la cola
                        .offset(y = -etiquetaAlto * 0.10f)
                )
            }
        }
    }
}
