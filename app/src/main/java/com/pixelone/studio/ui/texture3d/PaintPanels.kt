package com.pixelone.studio.ui.texture3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pixelone.studio.editor.PixelIcons
import com.pixelone.studio.studio3d.PaintCore.MirrorBase
import com.pixelone.studio.studio3d.PaintCore.Symmetry
import com.pixelone.studio.studio3d.PaintCore.Tool
import com.pixelone.studio.studio3d.Studio3DViewModel
import com.pixelone.studio.ui.editor.ColorPickerDialog
import com.pixelone.studio.ui.theme.PixelGray
import com.pixelone.studio.ui.theme.PixelGrayBorder
import com.pixelone.studio.ui.theme.PixelGrayLight
import com.pixelone.studio.ui.theme.PixelOrange
import com.pixelone.studio.ui.theme.PixelSurface
import com.pixelone.studio.ui.theme.PixelSurfaceElevated

/**
 * BARRA DE FERRAMENTAS de pintura (Ronda B) — SEMPRE visível (fora de qualquer
 * painel recolhível), alvos ≥ 48 dp:
 *   [Pintar/Navegar] [lápis] [borracha] [balde] [conta-gotas] [linha] [retângulo]
 *   [cor] [desfazer] [refazer]
 *
 * O toggle Pintar/Navegar existe para NÃO pintar sem querer: em "Navegar", o
 * toque no 3D roda a câmara e o toque curto seleciona face/ilha (Ronda A); em
 * "Pintar", o toque pinta (raycast → UV → texel) e 2 dedos continuam a navegar.
 */
@Composable
internal fun PaintToolBar(
    viewModel: Studio3DViewModel,
    state: Studio3DViewModel.PaintState
) {
    var showColorPicker by remember { mutableStateOf(false) }
    Surface(color = PixelSurface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // CORREÇÃO DA MISSÃO (área segura / diferentes dimensões de ecrã):
            // a barra tem ~530dp de conteúdo fixo — num ecrã de 360dp os
            // DESFAZER/REFAZER ficavam FORA do ecrã (Row sem rolagem). O
            // segmento de ferramentas agora RODA; Desfazer/Refazer ficam FIXOS
            // à direita, sempre visíveis e acessíveis (requisito Ronda B).
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Pintar / Navegar (requisito: alterna e é sempre visível).
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .background(if (state.paintMode) PixelOrange else PixelSurfaceElevated)
                        .border(
                            1.dp,
                            if (state.paintMode) PixelOrange else PixelGrayBorder,
                            MaterialTheme.shapes.small
                        )
                        .clickable { viewModel.setPaintMode(!state.paintMode) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.paintMode) PixelIcons.pan() else PixelIcons.pencil(),
                            contentDescription = null,
                            tint = if (state.paintMode) Color.Black else PixelGrayLight,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (state.paintMode) "Pintar" else "Navegar",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.paintMode) Color.Black else PixelGrayLight
                        )
                    }
                }
                // Ferramentas (48 dp).
                ToolButton(PixelIcons.pencil(), "Lápis", state.tool == Tool.PENCIL) {
                    viewModel.setTool(Tool.PENCIL)
                }
                ToolButton(PixelIcons.eraser(), "Borracha", state.tool == Tool.ERASER) {
                    viewModel.setTool(Tool.ERASER)
                }
                ToolButton(PixelIcons.fill(), "Balde", state.tool == Tool.FILL) {
                    viewModel.setTool(Tool.FILL)
                }
                ToolButton(PixelIcons.eyedropper(), "Conta-gotas", state.tool == Tool.EYEDROPPER) {
                    viewModel.setTool(Tool.EYEDROPPER)
                }
                ToolButton(PixelIcons.line(), "Linha", state.tool == Tool.LINE) {
                    viewModel.setTool(Tool.LINE)
                }
                ToolButton(PixelIcons.rect(), "Retângulo", state.tool == Tool.RECT) {
                    viewModel.setTool(Tool.RECT)
                }
                Spacer(Modifier.width(2.dp))
                // Cor ativa (48 dp) — abre o seletor (hex + paleta).
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(state.color))
                        .border(2.dp, PixelOrange, MaterialTheme.shapes.small)
                        .clickable { showColorPicker = true }
                )
            }
            // Desfazer/Refazer — SEMPRE visíveis (partilhados entre 3D e UV),
            // FIXOS fora do segmento rolável.
            ToolButton(PixelIcons.undo(), "Desfazer", false, enabled = state.canUndo) {
                viewModel.undo()
            }
            ToolButton(PixelIcons.redo(), "Refazer", false, enabled = state.canRedo) {
                viewModel.redo()
            }
        }
    }
    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = state.color,
            palette = state.recentColors,
            onDismiss = { showColorPicker = false },
            onPick = { picked ->
                viewModel.setColor(picked)
                if (state.tool == Tool.ERASER) viewModel.setTool(Tool.PENCIL)
                showColorPicker = false
            },
            onAddToPalette = { picked -> viewModel.setColor(picked) },
            onRemoveFromPalette = { }
        )
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                when {
                    active -> PixelOrange
                    !enabled -> PixelSurface
                    else -> PixelSurfaceElevated
                }
            )
            .border(
                1.dp,
                if (active) PixelOrange else PixelGrayBorder,
                MaterialTheme.shapes.small
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                active -> Color.Black
                !enabled -> PixelGray.copy(alpha = 0.4f)
                else -> PixelGrayLight
            },
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Linha de PINCÉIS (1, 2, 3, 4, 8 px — requisito) + BLOQUEIO NA ILHA +
 * SIMETRIA + MARGEM (padding) da ilha. Usada nos painéis dos modos Dividido
 * e UV (a pintura nas duas vistas parte do mesmo estado).
 */
@Composable
internal fun PaintOptionsRow(
    viewModel: Studio3DViewModel,
    state: Studio3DViewModel.PaintState,
    selectionActive: Boolean
) {
    var brush by remember(state.brush) { mutableIntStateOf(state.brush) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pincel", style = MaterialTheme.typography.labelSmall, color = PixelGray)
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Studio3DViewModel.BRUSH_SIZES.forEach { size ->
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(if (brush == size) PixelOrange else PixelSurfaceElevated)
                            .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                            .clickable {
                                brush = size
                                viewModel.setBrush(size)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$size",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (brush == size) Color.Black else PixelGrayLight
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Bloquear na ilha",
                style = MaterialTheme.typography.labelSmall,
                color = if (selectionActive) PixelGrayLight else PixelGray.copy(alpha = 0.5f)
            )
            Switch(
                checked = state.lockToIsland,
                onCheckedChange = { viewModel.setLockToIsland(it) },
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(PixelIcons.flip(), contentDescription = null, tint = PixelGray, modifier = Modifier.size(16.dp))
            Text("Simetria", style = MaterialTheme.typography.labelSmall, color = PixelGray)
            SymmetryChip("Off", Symmetry.NONE, state.symmetry) { viewModel.setSymmetry(Symmetry.NONE) }
            SymmetryChip("H", Symmetry.HORIZONTAL, state.symmetry) { viewModel.setSymmetry(Symmetry.HORIZONTAL) }
            SymmetryChip("V", Symmetry.VERTICAL, state.symmetry) { viewModel.setSymmetry(Symmetry.VERTICAL) }
            SymmetryChip("H+V", Symmetry.BOTH, state.symmetry) { viewModel.setSymmetry(Symmetry.BOTH) }
            Spacer(Modifier.width(6.dp))
            // Indica SEMPRE qual é a base do espelho (requisito).
            Text(
                if (state.mirrorBase == MirrorBase.ISLAND) "Espelho: ilha" else "Espelho: textura",
                style = MaterialTheme.typography.labelSmall,
                color = PixelOrange
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Margem: ${state.islandPadding}px",
                style = MaterialTheme.typography.labelSmall,
                color = PixelGray
            )
            Box(
                Modifier
                    .size(36.dp)
                    .background(PixelSurfaceElevated)
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable { viewModel.setIslandPadding(state.islandPadding - 1) },
                contentAlignment = Alignment.Center
            ) { Text("−", color = PixelGrayLight) }
            Box(
                Modifier
                    .size(36.dp)
                    .background(PixelSurfaceElevated)
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable { viewModel.setIslandPadding(state.islandPadding + 1) },
                contentAlignment = Alignment.Center
            ) { Text("+", color = PixelGrayLight) }
        }
    }
}

@Composable
private fun SymmetryChip(label: String, value: Symmetry, current: Symmetry, onClick: () -> Unit) {
    val selected = current == value
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) Color.Black else PixelGrayLight,
        modifier = Modifier
            .background(if (selected) PixelOrange else PixelSurfaceElevated)
            .border(1.dp, if (selected) PixelOrange else PixelGrayBorder, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    )
}

/**
 * Painel de CAMADAS (Ronda B): visibilidade, opacidade (±), reordenação
 * simples (↑/↓), ativação e remoção. Linhas de 48 dp — nada cobre a vista.
 */
@Composable
internal fun LayersPanel(
    viewModel: Studio3DViewModel,
    rows: List<Studio3DViewModel.LayerRow>
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PixelIcons.layers(), contentDescription = null, tint = PixelOrange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Camadas (${rows.size}/${LayerStackMax})",
                style = MaterialTheme.typography.labelMedium,
                color = PixelGrayLight,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .size(48.dp)
                    .background(PixelSurfaceElevated)
                    .border(1.dp, PixelOrange, MaterialTheme.shapes.small)
                    .clickable { viewModel.addLayer() },
                contentAlignment = Alignment.Center
            ) { Text("+ Camada", style = MaterialTheme.typography.labelSmall, color = PixelOrange) }
        }
        Spacer(Modifier.height(4.dp))
        rows.asReversed().forEach { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(if (row.active) PixelSurfaceElevated else PixelSurface)
                    .border(
                        1.dp,
                        if (row.active) PixelOrange else PixelGrayBorder,
                        MaterialTheme.shapes.small
                    )
                    .clickable { viewModel.selectLayer(row.index) }
                    .padding(horizontal = 6.dp)
            ) {
                // Visibilidade (quadrado cheio = visível; vazio = oculta).
                Box(
                    Modifier
                        .size(32.dp)
                        .background(if (row.visible) PixelOrange else Color.Transparent)
                        .border(1.dp, PixelOrange, MaterialTheme.shapes.small)
                        .clickable { viewModel.toggleLayerVisible(row.index) }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    row.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = PixelGrayLight,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(
                    "Op. ${row.opacity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PixelGray
                )
                SmallButton("−") { viewModel.setLayerOpacity(row.index, row.opacity - 10) }
                SmallButton("+") { viewModel.setLayerOpacity(row.index, row.opacity + 10) }
                SmallButton("↑") { viewModel.moveLayerUp(row.index) }
                SmallButton("↓") { viewModel.moveLayerDown(row.index) }
                if (rows.size > 1) {
                    SmallButton("×") { viewModel.removeLayer(row.index) }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

private const val LayerStackMax = 4

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .background(PixelSurfaceElevated)
            .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = PixelGrayLight)
    }
}

/**
 * Painel de PALETA (Ronda B): cores recentes (persistidas no projeto) + botão
 * que abre o seletor completo (sliders + entrada hexadecimal).
 */
@Composable
internal fun PalettePanel(
    viewModel: Studio3DViewModel,
    state: Studio3DViewModel.PaintState
) {
    var showColorPicker by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Recentes", style = MaterialTheme.typography.labelSmall, color = PixelGray)
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            state.recentColors.forEach { c ->
                Box(
                    Modifier
                        .size(48.dp)
                        .background(Color(c))
                        .border(
                            1.dp,
                            if (c == state.color) PixelOrange else PixelGrayBorder,
                            MaterialTheme.shapes.small
                        )
                        .clickable {
                            viewModel.setColor(c)
                            if (state.tool == Tool.ERASER) viewModel.setTool(Tool.PENCIL)
                        }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .height(48.dp)
                .background(PixelSurfaceElevated)
                .border(1.dp, PixelOrange, MaterialTheme.shapes.small)
                .clickable { showColorPicker = true }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Cor… (hex)", style = MaterialTheme.typography.labelSmall, color = PixelOrange)
        }
    }
    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = state.color,
            palette = state.recentColors,
            onDismiss = { showColorPicker = false },
            onPick = { picked ->
                viewModel.setColor(picked)
                if (state.tool == Tool.ERASER) viewModel.setTool(Tool.PENCIL)
                showColorPicker = false
            },
            onAddToPalette = { picked -> viewModel.setColor(picked) },
            onRemoveFromPalette = { }
        )
    }
}

/** Linha de visualização (Ronda B): wireframe, xadrez de teste e nota unlit. */
@Composable
internal fun ViewOptionsRow(viewModel: Studio3DViewModel, showWireframe: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Wireframe", style = MaterialTheme.typography.labelSmall, color = PixelGrayLight)
        Switch(
            checked = showWireframe,
            onCheckedChange = { viewModel.setShowWireframe(it) },
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .height(48.dp)
                .background(PixelSurfaceElevated)
                .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                .clickable { viewModel.fillWithTestChecker() }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Xadrez de teste",
                style = MaterialTheme.typography.labelSmall,
                color = PixelGrayLight
            )
        }
        Spacer(Modifier.weight(1f))
        Text("Render: sem luz (unlit)", style = MaterialTheme.typography.labelSmall, color = PixelGray)
    }
}
