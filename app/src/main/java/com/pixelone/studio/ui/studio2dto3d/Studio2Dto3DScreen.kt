package com.pixelone.studio.ui.studio2dto3d

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
 * 2D-to-3D Studio — espaço planeado da Fase 0.
 * A construção geométrica assistida NÃO está implementada nesta fase.
 */
@Composable
fun Studio2Dto3DScreen(onBack: () -> Unit) {
    StudioScreenScaffold(title = "2D-to-3D Studio", onBack = onBack) {
        StatusBadge(text = "FASE 0 - PLANEADO")
        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle("Estado atual")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "O 2D-to-3D Studio permitirá construir geometria 3D assistida por desenhos " +
                "e referências ortográficas (frente, lado e topo). Nesta fase, o espaço existe " +
                "como base de navegação: a construção geométrica e a conversão 2D-para-3D ainda " +
                "não foram implementadas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        ReservedCanvasArea(label = "Área reservada para a construção assistida 2D-para-3D")

        Spacer(modifier = Modifier.height(16.dp))
        PlannedPanel(
            items = listOf(
                "Referências ortográficas (frente, lado, topo)",
                "Construção geométrica assistida",
                "Conversão 2D-para-3D com pré-visualização",
                "Integração com Texture Studio e 3D Texture Studio"
            )
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}
