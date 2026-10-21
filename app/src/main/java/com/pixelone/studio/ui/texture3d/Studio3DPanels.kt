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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pixelone.studio.editor.PixelIcons
import com.pixelone.studio.studio3d.GltfLimits
import com.pixelone.studio.studio3d.Studio3DState.SamplingMode
import com.pixelone.studio.studio3d.Studio3DState.StudioMode
import com.pixelone.studio.studio3d.Studio3DState.TextureRef
import com.pixelone.studio.studio3d.Studio3DViewModel
import com.pixelone.studio.ui.editor.ColorPickerDialog
import com.pixelone.studio.ui.theme.PixelGray
import com.pixelone.studio.ui.theme.PixelGrayBorder
import com.pixelone.studio.ui.theme.PixelGrayLight
import com.pixelone.studio.ui.theme.PixelOrange
import com.pixelone.studio.ui.theme.PixelSurface
import com.pixelone.studio.ui.theme.PixelSurfaceElevated

/**
 * Modo UV: textura com sobreposição UV, seleção de ilha por TOQUE (curto,
 * sem arrastar), escolha de malha/material e botão "Pintar esta textura".
 * Modelos sem UV nunca chegam aqui (o seletor bloqueia — ver [ModeSelector]).
 *
 * CORREÇÕES RONDA A (G): NENHUM overlay sobre a textura — o interruptor
 * "Linhas UV" existe UMA VEZ (painel inferior) e a informação da ilha +
 * botões "Focar"/"Limpar seleção" ficam no painel, nunca por cima da vista.
 */
@Composable
internal fun UvMode(
    viewModel: Studio3DViewModel,
    uvViewHost: androidx.compose.runtime.MutableState<TexturePaintView?>
) {
    Box(Modifier.fillMaxWidth().fillMaxHeight()) {
        val showUv by viewModel.showUvOverlay.observeAsLocalState()
        val selectedMesh by viewModel.selectedMesh.observeAsLocalState()
        val bmp by viewModel.presentationBitmap.observeAsLocalState()
        val islands by viewModel.selectedIsland.observeAsLocalState()
        val paintVersion by viewModel.paintVersion.observeAsLocalState()

        AndroidView(
            factory = { ctx ->
                TexturePaintView(ctx).apply {
                    surfaceMode = TexturePaintView.SurfaceMode.UV_SELECT
                    onUvTap = { u, v -> viewModel.selectIslandAt(u, v) }
                    uvViewHost.value = this
                }
            },
            update = { view ->
                view.textureBitmap = bmp
                view.uvSegments = if (showUv) viewModel.uvWireframeSegments(selectedMesh) else null
                view.islandHighlightSegments = viewModel.islandHighlightSegments()
                view.islandUvBounds = viewModel.selectedIslandUvBounds()
                view.invalidate()
            }
        )

        // Redesenho ao vivo durante a pintura/undo (região suja já aplicada).
        LaunchedEffect(paintVersion) {
            uvViewHost.value?.invalidate()
        }

        // Realce da ilha: reage também a mudanças de seleção.
        LaunchedEffect(islands) {
            uvViewHost.value?.invalidate()
        }

        DisposableEffect(Unit) {
            onDispose { uvViewHost.value = null }
        }
    }
}

/**
 * Modo Dividido: 3D em cima e textura/UV em baixo (onde se pinta), CADA UM
 * com metade da área principal (≈40% do ecrã em telemóveis típicos — requisito
 * J da Ronda A). O 3D atualiza AO VIVO durante a pintura — com ATUALIZAÇÃO
 * PARCIAL da GPU (glTexSubImage2D da região suja, via [Studio3DViewModel.markDirty]).
 * A SELEÇÃO aparece nos DOIS painéis em simultâneo (requisito da Ronda A).
 */
@Composable
internal fun SplitMode(viewModel: Studio3DViewModel) {
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        // 3D em cima (metade da área principal) — toque seleciona a face/ilha.
        Studio3DGlViewport(viewModel, Modifier.fillMaxWidth().weight(1f))
        // Superfície de pintura em baixo — mostra a mesma seleção (ilhas).
        val bmp by viewModel.presentationBitmap.observeAsLocalState()
        val paintVersion by viewModel.paintVersion.observeAsLocalState()
        val showUv by viewModel.showUvOverlay.observeAsLocalState()
        val islands by viewModel.selectedIsland.observeAsLocalState()
        var paintView by remember { mutableStateOf<TexturePaintView?>(null) }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                TexturePaintView(ctx).apply {
                    surfaceMode = TexturePaintView.SurfaceMode.PAINT
                    onPaintBegin = { x, y -> viewModel.paintBegin(x, y) }
                    onPaintMove = { x, y -> viewModel.paintMove(x, y) }
                    onPaintEnd = { viewModel.paintEnd() }
                    paintView = this
                }
            },
            update = { view ->
                view.textureBitmap = bmp
                // RONDA A (F): o modo Dividido RESPEITA o interruptor "Linhas UV".
                view.uvSegments = if (showUv) viewModel.uvWireframeSegments() else null
                view.islandHighlightSegments = viewModel.islandHighlightSegments()
                view.invalidate()
            }
        )
        // Redesenho ao vivo durante a pintura (o 3D já recebeu a região suja)
        // e quando a seleção muda (aparece nos dois painéis em simultâneo).
        LaunchedEffect(paintVersion, islands) {
            paintView?.invalidate()
        }
    }
}

// ---------------------------------------------------------------------------
// Painel inferior (~20%), recolhível com um toque; conteúdo por modo
// ---------------------------------------------------------------------------

@Composable
internal fun BottomPanel(
    viewModel: Studio3DViewModel,
    mode: StudioMode,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPaintTexture: () -> Unit,
    uvViewHost: androidx.compose.runtime.MutableState<TexturePaintView?>
) {
    Surface(color = PixelSurface, tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth()) {
            // RONDA A (I): o texto de depuração "Painel — Textura | Amostragem
            // | Info" foi removido. A linha de recolha mantém só a seta.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clickable { onToggle() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    if (expanded) "▾" else "▸",
                    color = PixelGray,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (expanded) {
                Box(Modifier.height(150.dp)) {
                    when (mode) {
                        StudioMode.THREE_D -> Panel3D(viewModel, onPaintTexture)
                        StudioMode.UV -> PanelUv(viewModel, onPaintTexture, uvViewHost)
                        StudioMode.SPLIT -> PanelSplit(viewModel)
                    }
                }
            }
        }
    }
}

// ------------------------------ Modo 3D: Textura | Amostragem | Info --------

@Composable
private fun Panel3D(viewModel: Studio3DViewModel, onPaintTexture: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    // RONDA A (I): rolagem vertical — nenhum texto fica cortado em ecrãs baixos.
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Textura", "Amostragem", "Info").forEachIndexed { index, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (tab == index) PixelOrange else PixelGray,
                    modifier = Modifier
                        .clickable { tab = index }
                        .background(if (tab == index) PixelSurfaceElevated else Color.Transparent)
                        .border(1.dp, if (tab == index) PixelOrange else PixelGrayBorder, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> Panel3DTexture(viewModel, onPaintTexture)
            1 -> Panel3DSampling(viewModel)
            else -> Panel3DInfo(viewModel)
        }
    }
}

@Composable
private fun Panel3DTexture(viewModel: Studio3DViewModel, onPaintTexture: () -> Unit) {
    val selectedMaterial by viewModel.selectedMaterial.observeAsLocalState()
    val bindings by viewModel.bindings.observeAsLocalState()
    val material = viewModel.model.materials.getOrNull(selectedMaterial)
    Column {
        Text(
            "Material: ${material?.name ?: "—"}",
            style = MaterialTheme.typography.labelMedium,
            color = PixelGrayLight
        )
        Spacer(Modifier.height(6.dp))
        // RONDA A (I): chips com rolagem horizontal — "Textura do projeto"
        // nunca fica cortado em ecrãs estreitos.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BindingChip("Textura do projeto", TextureRef.DOCUMENT, bindings, selectedMaterial, viewModel)
            BindingChip("Embutida", TextureRef.EMBEDDED, bindings, selectedMaterial, viewModel)
            BindingChip("Sem textura", TextureRef.NONE, bindings, selectedMaterial, viewModel)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onPaintTexture) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = PixelOrange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pintar esta textura", color = PixelOrange)
        }
    }
}

@Composable
private fun BindingChip(
    label: String,
    ref: TextureRef,
    bindings: Map<Int, TextureRef>,
    materialIndex: Int,
    viewModel: Studio3DViewModel
) {
    val selected = bindings[materialIndex] == ref
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color.Black else PixelGrayLight,
        modifier = Modifier
            .background(if (selected) PixelOrange else PixelSurfaceElevated)
            .border(1.dp, if (selected) PixelOrange else PixelGrayBorder, MaterialTheme.shapes.small)
            .clickable { viewModel.bindMaterial(materialIndex, ref) }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun Panel3DSampling(viewModel: Studio3DViewModel) {
    val sampling by viewModel.sampling.observeAsLocalState()
    Column {
        Text(
            "Amostragem da textura (regra: pixel art nítida)",
            style = MaterialTheme.typography.labelMedium,
            color = PixelGrayLight
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Nearest (nítida)",
                style = MaterialTheme.typography.labelMedium,
                color = if (sampling == SamplingMode.NEAREST) Color.Black else PixelGrayLight,
                modifier = Modifier
                    .background(if (sampling == SamplingMode.NEAREST) PixelOrange else PixelSurfaceElevated)
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable { viewModel.setSampling(SamplingMode.NEAREST) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Text(
                "Linear (suave)",
                style = MaterialTheme.typography.labelMedium,
                color = if (sampling == SamplingMode.LINEAR) Color.Black else PixelGrayLight,
                modifier = Modifier
                    .background(if (sampling == SamplingMode.LINEAR) PixelOrange else PixelSurfaceElevated)
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable { viewModel.setSampling(SamplingMode.LINEAR) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        // RONDA A (I): descrição curta — não corta no painel de 150 dp.
        Text(
            "Nearest: nítida (pixel art) · Linear: suave",
            style = MaterialTheme.typography.labelSmall,
            color = PixelGray
        )
    }
}

@Composable
private fun Panel3DInfo(viewModel: Studio3DViewModel) {
    val model = viewModel.model
    Column {
        InfoLine("Modelo", when (viewModel.modelKind) {
            com.pixelone.studio.studio3d.Studio3DState.ModelKind.IMPORTED -> viewModel.modelFileName ?: "importado"
            else -> "Geometria de demonstração"
        })
        InfoLine("Malhas", "${model.meshNames.size}")
        InfoLine("Materiais", "${model.materials.size}")
        InfoLine("Triângulos", "${model.triangleCount}")
        InfoLine("UV", if (model.hasUv) "presente (TEXCOORD_0)" else "AUSENTE — modos UV/Dividido bloqueados")
        InfoLine("Limites", "ficheiro ≤ ${GltfLimits.MAX_FILE_BYTES / (1024 * 1024)} MB, " +
            "≤ ${GltfLimits.MAX_TRIANGLES} triângulos, texturas ≤ ${GltfLimits.MAX_TEXTURE_DIMENSION}px")
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = PixelGray,
            modifier = Modifier.width(110.dp)
        )
        Text(value, style = MaterialTheme.typography.labelSmall, color = PixelGrayLight)
    }
}

// ------------------------------ Modo UV: seleção + interruptor + malha -------

/**
 * RONDA A (G + nova funcionalidade): UMA linha de seleção com etiqueta da
 * ilha, "Focar" (zoom automático com grelha de píxeis) e "Limpar seleção",
 * todos com alvo ≥ 48 dp — nada sobrepõe a textura.
 */
@Composable
private fun PanelUv(
    viewModel: Studio3DViewModel,
    onPaintTexture: () -> Unit,
    uvViewHost: androidx.compose.runtime.MutableState<TexturePaintView?>
) {
    val showUv by viewModel.showUvOverlay.observeAsLocalState()
    val selectedMesh by viewModel.selectedMesh.observeAsLocalState()
    val selection by viewModel.selectedIsland.observeAsLocalState()
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (selection != null) {
                    "Ilha #${selection!!.island.id} (${selection!!.island.triangleCount} tri.)"
                } else {
                    "Toque numa face ou ilha"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (selection != null) PixelOrange else PixelGray,
                modifier = Modifier.weight(1f)
            )
            // Botão "Focar": zoom automático à ilha com grelha de píxeis.
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 48.dp)
                    .background(if (selection != null) PixelSurfaceElevated else Color.Transparent)
                    .border(
                        1.dp,
                        if (selection != null) PixelOrange else PixelGrayBorder,
                        MaterialTheme.shapes.small
                    )
                    .clickable(enabled = selection != null) {
                        val bounds = viewModel.selectedIslandUvBounds()
                        if (bounds != null) {
                            uvViewHost.value?.focusOnIsland(bounds[0], bounds[1], bounds[2], bounds[3])
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("Focar", style = MaterialTheme.typography.labelMedium, color = PixelOrange)
            }
            Spacer(Modifier.width(6.dp))
            // Botão "Limpar seleção" (requisito da Ronda A).
            Box(
                modifier = Modifier
                    .size(width = 76.dp, height = 48.dp)
                    .background(if (selection != null) PixelSurfaceElevated else Color.Transparent)
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable(enabled = selection != null) { viewModel.clearIslandSelection() },
                contentAlignment = Alignment.Center
            ) {
                Text("Limpar", style = MaterialTheme.typography.labelMedium, color = PixelGrayLight)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Interruptor ÚNICO de linhas UV (requisito G) — no painel inferior.
            Text("Linhas UV", style = MaterialTheme.typography.labelMedium, color = PixelGrayLight)
            Spacer(Modifier.width(6.dp))
            Switch(checked = showUv, onCheckedChange = { viewModel.setShowUv(it) }, modifier = Modifier.size(40.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPaintTexture) {
                Text("Pintar esta textura", color = PixelOrange)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Malha (UV mostrada)", style = MaterialTheme.typography.labelSmall, color = PixelGray)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Todas",
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedMesh == null) Color.Black else PixelGrayLight,
                modifier = Modifier
                    .background(if (selectedMesh == null) PixelOrange else PixelSurfaceElevated)
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable { viewModel.selectMesh(null) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
            viewModel.model.meshNames.forEachIndexed { index, name ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectedMesh == index) Color.Black else PixelGrayLight,
                    modifier = Modifier
                        .background(if (selectedMesh == index) PixelOrange else PixelSurfaceElevated)
                        .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                        .clickable { viewModel.selectMesh(index) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Materiais", style = MaterialTheme.typography.labelSmall, color = PixelGray)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val selectedMaterial by viewModel.selectedMaterial.observeAsLocalState()
            viewModel.model.materials.forEachIndexed { index, material ->
                Text(
                    material.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectedMaterial == index) Color.Black else PixelGrayLight,
                    modifier = Modifier
                        .background(if (selectedMaterial == index) PixelOrange else PixelSurfaceElevated)
                        .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                        .clickable { viewModel.selectMaterial(index) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

// ------------------------------ Modo Dividido: cor, pincel, desfazer ---------

@Composable
private fun PanelSplit(viewModel: Studio3DViewModel) {
    var showColorPicker by remember { mutableStateOf(false) }
    var brush by remember { mutableIntStateOf(viewModel.paintBrushSize) }
    var erasing by remember { mutableStateOf(viewModel.eraseMode) }
    var color by remember { mutableIntStateOf(viewModel.paintColor) }
    val showUv by viewModel.showUvOverlay.observeAsLocalState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // RONDA A (J): Desfazer/Refazer/Balde numa fila FIXA — SEMPRE visíveis,
        // nunca empurrados para fora do ecrã pela fila rolável de pincéis.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Cor atual (abre o seletor).
            Box(
                Modifier
                    .size(48.dp)
                    .background(Color(color))
                    .border(2.dp, if (erasing) PixelGrayBorder else PixelOrange, MaterialTheme.shapes.small)
                    .clickable { showColorPicker = true }
            )
            Text(
                if (erasing) "Borracha" else "Pincel",
                style = MaterialTheme.typography.labelMedium,
                color = PixelGrayLight,
                modifier = Modifier
                    .clickable {
                        erasing = !erasing
                        viewModel.eraseMode = erasing
                        viewModel.paintColor = if (erasing) 0x00000000 else color
                    }
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            )
            Spacer(Modifier.weight(1f))
            // Desfazer / refazer — alvos de 48 dp, fora de qualquer rolagem.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(PixelSurfaceElevated)
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable { viewModel.undo() },
                contentAlignment = Alignment.Center
            ) {
                Text("↶", style = MaterialTheme.typography.titleLarge, color = PixelGrayLight)
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(PixelSurfaceElevated)
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable { viewModel.redo() },
                contentAlignment = Alignment.Center
            ) {
                Text("↷", style = MaterialTheme.typography.titleLarge, color = PixelGrayLight)
            }
            // Balde: aplica no próximo toque (define modo fill pendente).
            Icon(
                PixelIcons.fill(),
                contentDescription = "Balde no próximo toque",
                tint = PixelOrange,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (viewModel.pendingFill) PixelOrange.copy(alpha = 0.25f) else PixelSurfaceElevated
                    )
                    .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    .clickable { viewModel.pendingFill = !viewModel.pendingFill }
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        // Fila rolável de pincéis + interruptor de linhas UV (afeta a área de
        // baixo; estado partilhado com os outros modos — um só interruptor).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pincel", style = MaterialTheme.typography.labelSmall, color = PixelGray)
            Spacer(Modifier.width(6.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..8).forEach { size ->
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(if (brush == size) PixelOrange else PixelSurfaceElevated)
                            .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                            .clickable {
                                brush = size
                                viewModel.paintBrushSize = size
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size((size * 2).coerceIn(4, 16).dp)
                                .background(if (brush == size) Color.Black else PixelGrayLight)
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("Linhas UV", style = MaterialTheme.typography.labelSmall, color = PixelGray)
            Spacer(Modifier.width(4.dp))
            Switch(checked = showUv, onCheckedChange = { viewModel.setShowUv(it) }, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(4.dp))
        // RONDA A (J): dica curta e dispensável (a nota longa foi removida).
        Text(
            "1 dedo pinta · 2 dedos navegam · toque no 3D seleciona a face",
            style = MaterialTheme.typography.labelSmall,
            color = PixelGray
        )
    }

    if (showColorPicker) {
        val palette = listOf(
            0xFF0A0A0A.toInt(), 0xFF2E2E2E.toInt(), 0xFF9E9E9E.toInt(), 0xFFE6E6E6.toInt(),
            0xFFF57C00.toInt(), 0xFFFFA726.toInt(), 0xFFE65100.toInt(), 0xFFFFFFFF.toInt(),
            0xFFD32F2F.toInt(), 0xFF388E3C.toInt(), 0xFF1976D2.toInt(), 0xFF00000000.toInt()
        )
        ColorPickerDialog(
            initialColor = color,
            palette = palette,
            onDismiss = { showColorPicker = false },
            onPick = { picked ->
                color = picked
                erasing = false
                viewModel.eraseMode = false
                viewModel.paintColor = picked
                showColorPicker = false
            },
            onAddToPalette = { },
            onRemoveFromPalette = { }
        )
    }
}

// ---------------------------------------------------------------------------
// Barra de navegação inferior: Texture / 3D / 2D→3D
// ---------------------------------------------------------------------------

@Composable
internal fun Studio3DBottomNav(
    onOpenTextureStudio: () -> Unit,
    onOpen2Dto3D: () -> Unit
) {
    Surface(color = PixelSurfaceElevated, tonalElevation = 6.dp) {
        // RONDA A (A): navigationBarsPadding — os itens ficam ACIMA da barra
        // de gestos/botões do sistema (edge-to-edge preservado; o comentário
        // do chamador já o anunciava, mas o padding não estava aplicado).
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            NavItem("Texture", false, Modifier.weight(1f), onOpenTextureStudio)
            NavItem("3D", true, Modifier.weight(1f), null)
            NavItem("2D→3D", false, Modifier.weight(1f), onOpen2Dto3D)
        }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (active) PixelOrange.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) PixelOrange else PixelGray
        )
    }
}

// ---------------------------------------------------------------------------
// Diálogos: nova textura e abrir projeto
// ---------------------------------------------------------------------------

@Composable
internal fun NewTextureDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, width: Int, height: Int) -> Unit
) {
    var name by remember { mutableStateOf("textura-3d") }
    var size by remember { mutableIntStateOf(64) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova textura", color = PixelGrayLight) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome") },
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(16, 32, 64, 128, 256).forEach { s ->
                        Text(
                            "${s}px",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (size == s) Color.Black else PixelGrayLight,
                            modifier = Modifier
                                .background(if (size == s) PixelOrange else PixelSurfaceElevated)
                                .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                                .clickable { size = s }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { "textura-3d" }, size, size) }) {
                Text("Criar", color = PixelOrange)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = PixelGray) }
        },
        containerColor = PixelSurface
    )
}

@Composable
internal fun OpenProjectDialog(viewModel: Studio3DViewModel, onDismiss: () -> Unit) {
    val projects = remember { viewModel.listProjectsSnapshot() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Abrir projeto", color = PixelGrayLight) },
        text = {
            if (projects.isEmpty()) {
                Text("Nenhum projeto guardado.", color = PixelGray)
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    projects.forEach { project ->
                        Text(
                            project.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PixelGrayLight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PixelSurfaceElevated)
                                .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                                .clickable {
                                    onDismiss()
                                    viewModel.openProject(project.id)
                                }
                                .padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar", color = PixelGray) }
        },
        containerColor = PixelSurface
    )
}
