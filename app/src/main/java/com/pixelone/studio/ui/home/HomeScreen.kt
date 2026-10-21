package com.pixelone.studio.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelone.studio.ui.components.InfoNote
import com.pixelone.studio.ui.components.SectionTitle
import com.pixelone.studio.ui.components.StatusBadge
import com.pixelone.studio.ui.components.WorkspaceCard

/**
 * Ecrã inicial do Pixel One: identidade visual e acesso aos três espaços
 * de trabalho. Conteúdo honesto da Fase 0 — nada simula funcionalidades
 * que ainda não existem.
 */
@Composable
fun HomeScreen(
    onOpenTextureStudio: () -> Unit,
    onOpenTexture3DStudio: () -> Unit,
    onOpenStudio2Dto3D: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = "PIXEL ONE",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Estúdio de pixel art e texturas 2D e 3D para Android",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                StatusBadge(text = "FASE 0 - FUNDAÇÃO", highlight = true)

                Spacer(modifier = Modifier.height(28.dp))
                SectionTitle("Espaços de trabalho")
                Spacer(modifier = Modifier.height(12.dp))

                WorkspaceCard(
                    title = "Texture Studio",
                    subtitle = "Desenho de pixel art e criação de texturas 2D.",
                    status = "BASE DA FASE 0",
                    statusHighlight = true,
                    onClick = onOpenTextureStudio
                )
                Spacer(modifier = Modifier.height(12.dp))
                WorkspaceCard(
                    title = "3D Texture Studio",
                    subtitle = "Visualizador OpenGL ES, importação glTF/GLB e texturas por UV.",
                    status = "FASE 3 - DISPONÍVEL",
                    statusHighlight = true,
                    onClick = onOpenTexture3DStudio
                )
                Spacer(modifier = Modifier.height(12.dp))
                WorkspaceCard(
                    title = "2D-to-3D Studio",
                    subtitle = "Construção geométrica assistida por desenhos e referências ortográficas.",
                    status = "PLANEADO",
                    onClick = onOpenStudio2Dto3D
                )

                Spacer(modifier = Modifier.height(24.dp))
                InfoNote(
                    title = "Sobre esta versão",
                    body = "Esta é a fundação do Pixel One (Fase 0): identidade visual preto, " +
                        "cinzento e laranja, navegação entre os três espaços de trabalho e a base " +
                        "do modelo de projetos locais. As ferramentas de edição chegam nas fases " +
                        "seguintes e aparecem sempre identificadas como planeadas. A aplicação " +
                        "funciona offline nas suas funções centrais e não pede permissões de sistema."
                )
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}
