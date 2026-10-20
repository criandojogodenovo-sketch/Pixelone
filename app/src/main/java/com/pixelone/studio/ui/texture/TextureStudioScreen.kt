package com.pixelone.studio.ui.texture

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelone.studio.ui.components.PlannedPanel
import com.pixelone.studio.ui.components.ReservedCanvasArea
import com.pixelone.studio.ui.components.SectionTitle
import com.pixelone.studio.ui.components.StatusBadge
import com.pixelone.studio.ui.components.StudioScreenScaffold

/**
 * Texture Studio — base de trabalho da Fase 0.
 * O editor de pixel art NÃO está implementado nesta fase; o ecrã declara-o
 * explicitamente e reserva a área do canvas futuro.
 */
@Composable
fun TextureStudioScreen(onBack: () -> Unit) {
    StudioScreenScaffold(title = "Texture Studio", onBack = onBack) {
        StatusBadge(text = "FASE 0 - BASE DE TRABALHO", highlight = true)
        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle("Estado atual")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Este espaço é a base do futuro editor de pixel art do Pixel One. " +
                "Nesta fase existem apenas a identidade visual, a navegação e a estrutura " +
                "do projeto: nenhuma ferramenta de desenho está implementada ainda.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        ReservedCanvasArea(label = "Área reservada para o canvas de pixel art")

        Spacer(modifier = Modifier.height(16.dp))
        PlannedPanel(
            items = listOf(
                "Canvas de pixel art com zoom, pan e grelha",
                "Pincéis, balde de preenchimento e conta-gotas",
                "Paletas de cor e gestão de amostras",
                "Camadas, desfazer/refazer e exportação de texturas"
            )
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}
