package com.pixelone.studio.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Ícones vetoriais do editor do Pixel One.
 *
 * DECISÃO: ícones próprios (caminhos estilo Material) em vez da dependência
 * material-icons-extended (~30 MB não minificados) — a especificação pede
 * evitar dependências desnecessárias e a minificação release está desligada.
 */
object PixelIcons {

    private val fillType = PathFillType.NonZero

    fun pencil(): ImageVector = vector("Pencil") {
        pathData(
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34 " +
                "c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
        )
    }

    fun eraser(): ImageVector = vector("Eraser") {
        pathData(
            "M15.54,5.46L18.54,8.46 9.92,17.08 6.92,14.08 15.54,5.46zM17.66,3.34c-0.51,0 -1.02,0.2 -1.41,0.59l-2.12,2.12 " +
                "3.54,3.54 2.12,-2.12c0.78,-0.78 0.78,-2.05 0,-2.83l-1.3,-1.3c-0.39,-0.39 -0.9,-0.59 -1.41,-0.59zM5.51,13.67 " +
                "l-2.12,2.12c-0.78,0.78 -0.78,2.05 0,2.83L6.87,21.5H12v-1.41L7.63,15.79 5.51,13.67z"
        )
    }

    fun fill(): ImageVector = vector("Fill") {
        pathData(
            "M16.56,8.94L7.62,0 6.21,1.41l2.38,2.38 -5.15,5.15c-0.59,0.59 -0.59,1.54 0,2.12l5.5,5.5c0.29,0.29 0.68,0.44 " +
                "1.06,0.44s0.77,-0.15 1.06,-0.44l5.5,-5.5c0.59,-0.58 0.59,-1.54 0,-2.12zM5.21,10L10,5.21 14.79,10 5.21,10z"
        )
        pathData("M19,11.5s-2,2.17 -2,3.5c0,1.1 0.9,2 2,2s2,-0.9 2,-2c0,-1.33 -2,-3.5 -2,-3.5z")
    }

    fun eyedropper(): ImageVector = vector("Eyedropper") {
        pathData(
            "M20.71,5.63l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-3.12,3.12 -1.93,-1.91 -1.41,1.41 1.42,1.42L3,16.25V21h4.75 " +
                "l9.92,-9.92 1.42,1.42 1.41,-1.41 -1.92,-1.92 3.12,-3.12c0.4,-0.4 0.4,-1.03 0.01,-1.42zM6.92,19L5,17.08l8.06,-8.06 " +
                "1.92,1.92L6.92,19z"
        )
    }

    fun pan(): ImageVector = vector("Pan") {
        pathData(
            "M12,2c-0.55,0 -1,0.45 -1,1v4.17l-1.59,-1.59L8,6.99 11.99,11 16,6.99l-1.41,-1.41L13,7.17V3c0,-0.55 -0.45,-1 -1,-1z"
        )
        pathData("M6.99,8L5.58,9.41 7.17,11H3c-0.55,0 -1,0.45 -1,1s0.45,1 1,1h4.17l-1.59,1.59L6.99,16 11,11.99 6.99,8z")
        pathData("M21,11h-4.17l1.59,-1.59L17.01,8 13,11.99 17.01,16l1.41,-1.41L16.83,13H21c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1z")
        pathData("M11.99,13L8,16.99l1.41,1.41L11,16.83V21c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-4.17l1.59,1.57L15.99,17 11.99,13z")
    }

    fun undo(): ImageVector = vector("Undo") {
        pathData(
            "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 " +
                "7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z"
        )
    }

    fun redo(): ImageVector = vector("Redo") {
        pathData(
            "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 " +
                "3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z"
        )
    }

    fun zoomIn(): ImageVector = vector("ZoomIn") {
        pathData(
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 " +
                "3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zm-6,0C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 " +
                "14,9.5 11.99,14 9.5,14z"
        )
        pathData("M12,10h-2v2H9v-2H7V9h2V7h1v2h2v1z")
    }

    fun zoomOut(): ImageVector = vector("ZoomOut") {
        pathData(
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 " +
                "3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zm-6,0C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 " +
                "14,9.5 11.99,14 9.5,14z"
        )
        pathData("M7,9h5v1H7z")
    }

    fun fitScreen(): ImageVector = vector("FitScreen") {
        pathData("M5,5h4V3H3v6h2V5zm14,-2v2h4v-4h-4v2zM19,19v2h2v0h2v-6h-2v4h-2zM3,21h6v-2H5v-4H3v6z")
        pathData("M3,11h18v2H3z")
    }

    fun grid(): ImageVector = vector("Grid") {
        pathData(
            "M20,2H4c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM8,20H4v-4h4v4zM8,14H4v-4h4v4z " +
                "M8,8H4V4h4v4zM14,20h-4v-4h4v4zM14,14h-4v-4h4v4zM14,8h-4V4h4v4zM20,20h-4v-4h4v4zM20,14h-4v-4h4v4zM20,8h-4V4h4v4z"
        )
    }

    fun palette(): ImageVector = vector("Palette") {
        pathData(
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 " +
                "-0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5H16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 " +
                "-1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 " +
                "1.5,1.5S10.33,8 9.5,8zm5,0c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zm3,4c-0.83,0 " +
                "-1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z"
        )
    }

    fun folder(): ImageVector = vector("Folder") {
        pathData(
            "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"
        )
    }

    fun save(): ImageVector = vector("Save") {
        pathData(
            "M17,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4zm-5,16c-1.66,0 -3,-1.34 -3,-3s1.34,-3 " +
                "3,-3 3,1.34 3,3 -1.34,3 -3,3zm3,-10H5V5h10v4z"
        )
    }

    fun export(): ImageVector = vector("Export") {
        pathData(
            "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"
        )
    }

    fun more(): ImageVector = vector("More") {
        pathData("M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zm0,2c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zm0,6c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    }

    fun brushSize(): ImageVector = vector("BrushSize") {
        pathData(
            "M9.06,16.47c1.04,0 1.88,-0.84 1.88,-1.88V4.59c0,-1.04 -0.84,-1.88 -1.88,-1.88S7.18,3.55 7.18,4.59v10c0,1.04 0.84,1.88 " +
                "1.88,1.88zM14.94,8.47c-1.04,0 -1.88,0.84 -1.88,1.88v9.06c0,1.04 0.84,1.88 1.88,1.88s1.88,-0.84 1.88,-1.88v-9.06c0,-1.04 " +
                "-0.84,-1.88 -1.88,-1.88z"
        )
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private inline fun vector(
        name: String,
        paths: ImageVector.Builder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(paths).build()

    private fun ImageVector.Builder.pathData(pathData: String) {
        addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
            pathFillType = fillType
        )
    }
}
