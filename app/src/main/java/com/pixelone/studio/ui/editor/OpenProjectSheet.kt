package com.pixelone.studio.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixelone.studio.editor.EditorViewModel
import com.pixelone.studio.project.PixelProject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Folha "Abrir projeto": lista os projetos guardados no armazenamento
 * privado (mais recentes primeiro) com nome, dimensões e data, permite
 * apagar, e oferece a importação de .osp do armazenamento do utilizador.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenProjectSheet(
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
    onOpenProject: (id: String) -> Unit,
    onImport: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var projects by remember { mutableStateOf<List<PixelProject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        viewModel.listProjects { list ->
            projects = list
            loading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text("Abrir projeto", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onImport) { Text("Importar .osp…") }
            }

            if (loading) {
                Text(
                    "A carregar…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else if (projects.isEmpty()) {
                Text(
                    "Ainda não existem projetos guardados.\nCria um projeto novo no menu (+) e usa Guardar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                LazyColumn {
                    items(projects, key = { it.id }) { project ->
                        ListItem(
                            headlineContent = { Text(project.name) },
                            supportingContent = {
                                Text(
                                    "Formato v${project.formatVersion} · " +
                                        dateFormat.format(Date(project.updatedAtEpochMs))
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = { viewModel.repository.deleteProject(project.id) }) {
                                    Text("Apagar", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            modifier = Modifier.clickable { onOpenProject(project.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
