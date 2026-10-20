package com.pixelone.studio

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pixelone.studio.navigation.PixelOneApp
import com.pixelone.studio.ui.theme.PixelOneTheme

/**
 * Ponto de entrada do Pixel One.
 *
 * - Jetpack Compose para toda a UI (decisão registada no README).
 * - enableEdgeToEdge com barras de sistema escuras e transparentes: a
 *   aplicação é sempre escura, pelo que os ícones das system bars são
 *   claros independentemente do modo do sistema.
 * - O Scaffold do Compose respeita system bars, recortes do ecrã e
 *   navegação por gestos.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            PixelOneTheme {
                PixelOneApp()
            }
        }
    }
}
