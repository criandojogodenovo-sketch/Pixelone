package com.pixelone.studio.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pixelone.studio.ui.home.HomeScreen
import com.pixelone.studio.ui.studio2dto3d.Studio2Dto3DScreen
import com.pixelone.studio.ui.texture.TextureStudioScreen
import com.pixelone.studio.ui.texture3d.Texture3DStudioScreen

/** Rotas de navegação da aplicação. */
object Routes {
    const val HOME = "home"
    const val TEXTURE_STUDIO = "texture_studio"
    const val TEXTURE_3D_STUDIO = "texture_3d_studio"
    const val STUDIO_2D_TO_3D = "studio_2d_to_3d"
}

/**
 * Grafo de navegação do Pixel One.
 *
 * O comportamento de retorno do Android (gesto/botão) é coerente com a
 * pilha do NavController: de qualquer espaço de trabalho, o retorno leva ao
 * ecrã inicial; no ecrã inicial, o retorno sai da aplicação.
 */
@Composable
fun PixelOneApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenTextureStudio = { navController.navigate(Routes.TEXTURE_STUDIO) },
                onOpenTexture3DStudio = { navController.navigate(Routes.TEXTURE_3D_STUDIO) },
                onOpenStudio2Dto3D = { navController.navigate(Routes.STUDIO_2D_TO_3D) }
            )
        }
        composable(Routes.TEXTURE_STUDIO) {
            TextureStudioScreen(onBack = { navController.navigateUp() })
        }
        composable(Routes.TEXTURE_3D_STUDIO) {
            Texture3DStudioScreen(onBack = { navController.navigateUp() })
        }
        composable(Routes.STUDIO_2D_TO_3D) {
            Studio2Dto3DScreen(onBack = { navController.navigateUp() })
        }
    }
}
