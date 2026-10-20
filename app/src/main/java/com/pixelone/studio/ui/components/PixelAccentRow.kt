package com.pixelone.studio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Pequena fileira de "píxeis" usada como assinatura visual do Pixel One.
 * Apenas decorativa, com cores da paleta oficial (preto/cinzento/laranja).
 */
@Composable
fun PixelAccentRow(modifier: Modifier = Modifier) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.outline
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}
