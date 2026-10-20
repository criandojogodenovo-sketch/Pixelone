package com.pixelone.studio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tema do Pixel One.
 *
 * DECISÃO REGISTADA: o tema é escuro de forma FIXA (não segue o modo do
 * sistema), porque a identidade visual do produto é preto, cinzento e
 * laranja — um editor de pixel art ganha precisão de cor sobre fundo escuro.
 */
private val PixelOneDarkColorScheme = darkColorScheme(
    primary = PixelOrange,
    onPrimary = Color.Black,
    primaryContainer = PixelOrangeContainer,
    onPrimaryContainer = PixelOrangeBright,
    secondary = PixelGray,
    onSecondary = Color.Black,
    secondaryContainer = PixelSurfaceElevated,
    onSecondaryContainer = PixelGrayLight,
    tertiary = PixelOrangeDeep,
    onTertiary = Color.Black,
    background = PixelBlack,
    onBackground = PixelGrayLight,
    surface = PixelSurface,
    onSurface = PixelGrayLight,
    surfaceVariant = PixelSurfaceElevated,
    onSurfaceVariant = PixelGray,
    outline = PixelGrayBorder,
    outlineVariant = PixelGrayBorderDim,
    error = Color(0xFFCF6679),
    onError = Color.Black
)

@Composable
fun PixelOneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PixelOneDarkColorScheme,
        typography = PixelOneTypography,
        content = content
    )
}
