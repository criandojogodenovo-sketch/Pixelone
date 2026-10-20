package com.pixelone.studio.ui.texture3d

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
 * 3D Texture Studio — espaço planeado da Fase 0.
 * O renderer 3D e o mapeamento UV NÃO estão implementados nesta fase.
 */
@Composable
fun Texture3DStudioScreen(onBack: () -> Unit) {
    StudioScreenScaffold(title = "3D Texture Studio", onBack = onBack) {
        StatusBadge(text = "FASE 0 - PLANEADO")
        Spacer(modifier = Modifier.height(16.dp))

        SectionTitle("Estado atual")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "O 3D Texture Studio permitirá aplicar texturas criadas no Texture Studio " +
                "a modelos 3D através dos seus UVs. Nesta fase, o espaço existe como base de " +
                "navegação: o carregamento de modelos, o mapeamento UV e a pintura de texturas " +
                "sobre modelos 3D ainda não foram implementados. A visualização futura usará " +
                "OpenGL ES.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        ReservedCanvasArea(label = "Área reservada para a vista 3D (OpenGL ES)")

        Spacer(modifier = Modifier.height(16.dp))
        PlannedPanel(
            items = listOf(
                "Importação de modelos 3D",
                "Mapeamento e edição de UVs",
                "Pintura de texturas sobre o modelo",
                "Visualização com OpenGL ES"
            )
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}
