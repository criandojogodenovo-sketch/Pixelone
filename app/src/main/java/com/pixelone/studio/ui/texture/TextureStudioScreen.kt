package com.pixelone.studio.ui.texture

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixelone.studio.editor.EditorViewModel
import com.pixelone.studio.editor.PixelIcons
import com.pixelone.studio.pixel.PixelEngine
import com.pixelone.studio.ui.editor.ColorPickerDialog
import com.pixelone.studio.ui.editor.ConfirmDiscardDialog
import com.pixelone.studio.ui.editor.NewProjectDialog
import com.pixelone.studio.ui.editor.OpenProjectSheet
import com.pixelone.studio.ui.editor.PixelCanvas
import com.pixelone.studio.ui.editor.AtlasScreen
import com.pixelone.studio.ui.editor.SpritesheetScreen
import com.pixelone.studio.ui.editor.checkerPattern
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Texture Studio — EDITOR DE PIXEL ART REAL (Fases 1+2).
 *
 * Integra: canvas com zoom/pan (1 dedo desenha; 2 dedos navegam), lápis,
 * borracha, balde, conta-gotas, paleta, undo/redo, projetos .osp (novo,
 * abrir, guardar, guardar como, importar), exportação PNG e os construtores
 * de spritesheet/atlas. Substitui o placeholder da Fase 0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextureStudioScreen(onBack: () -> Unit) {
    val viewModel: EditorViewModel = viewModel()

    // --------------------------- estado local de UI -------------------------
    var showNewDialog by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showOpenSheet by remember { mutableStateOf(false) }
    var showConfirmDiscard by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // --------------------------- SAF launchers ------------------------------
    // Exportação PNG: em Android 10+ (Q) é DIRETA para Download/ via
    // MediaStore com IS_PENDING (sem seletor; ver exportPngToDownloads).
    // Este launcher SAF fica apenas como caminho legado de Android < 10.
    val exportPngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> if (uri != null) viewModel.exportPng(uri) {} }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) viewModel.saveProjectAs(uri) {} }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importProject(uri) {} }

    // --------------------------- ciclo de vida ------------------------------
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(viewModel)
        viewModel.checkForAutosave()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(viewModel)
            viewModel.autosaveIfNeeded()
        }
    }

    // --------------------------- feedback -----------------------------------
    LaunchedEffect(viewModel.statusMessage.value) {
        viewModel.statusMessage.value?.let { snackbarHostState.showSnackbar(it) }
    }

    // --------------------------- navegação/guarda ---------------------------
    fun guardThen(action: () -> Unit) {
        if (viewModel.hasUnsavedChanges.value) {
            pendingAction = action
            showConfirmDiscard = true
        } else {
            action()
        }
    }

    fun handleBack() {
        when {
            overlay != Overlay.None -> overlay = Overlay.None
            showOpenSheet -> showOpenSheet = false
            viewModel.hasUnsavedChanges.value -> showConfirmDiscard = true
            else -> onBack()
        }
    }

    BackHandler(enabled = true) { handleBack() }

    val doc = viewModel.document
    val tool by viewModel.tool

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (overlay == Overlay.Spritesheet) "Spritesheet"
                                else if (overlay == Overlay.Atlas) "Atlas de texturas"
                                else viewModel.projectName,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { handleBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        if (viewModel.hasUnsavedChanges.value) {
                            Text(
                                "•",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        IconButton(onClick = { guardThen { showNewDialog = true } }) {
                            Icon(Icons.Filled.Add, contentDescription = "Novo projeto", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { guardThen { showOpenSheet = true } }) {
                            Icon(PixelIcons.folder(), contentDescription = "Abrir projeto", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(
                            enabled = doc != null,
                            onClick = { viewModel.saveProject {} }
                        ) {
                            Icon(
                                PixelIcons.save(),
                                contentDescription = "Guardar",
                                tint = if (doc != null) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(
                            enabled = doc != null,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    // Direto para Download/ (MediaStore, IS_PENDING):
                                    // cria o ficheiro só quando o conteúdo está
                                    // escrito — nunca mais um PNG 0 bytes.
                                    viewModel.exportPngToDownloads()
                                } else {
                                    exportPngLauncher.launch("${viewModel.projectName}.png")
                                }
                            }
                        ) {
                            Icon(PixelIcons.export(), contentDescription = "Exportar PNG", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Box {
                            IconButton(onClick = { showMenu = !showMenu }) {
                                Icon(PixelIcons.more(), contentDescription = "Mais ações", tint = MaterialTheme.colorScheme.onSurface)
                            }
                            if (showMenu) {
                                EditorDropdownMenu(
                                    expanded = true,
                                    onDismiss = { showMenu = false },
                                    onNew = { showMenu = false; guardThen { showNewDialog = true } },
                                    onOpen = { showMenu = false; guardThen { showOpenSheet = true } },
                                    onSave = { showMenu = false; viewModel.saveProject {} },
                                    onSaveAs = {
                                        showMenu = false
                                        saveAsLauncher.launch("${viewModel.projectName}.osp")
                                    },
                                    onImport = { showMenu = false; importLauncher.launch(arrayOf("*/*")) },
                                    onSpritesheet = { showMenu = false; overlay = Overlay.Spritesheet },
                                    onAtlas = { showMenu = false; overlay = Overlay.Atlas }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                if (viewModel.isBusy.value) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        bottomBar = {
            if (overlay == Overlay.None) {
                EditorBottomToolbar(
                    viewModel = viewModel,
                    onOpenColorPicker = { showColorPicker = true }
                )
            }
        }
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (overlay) {
                Overlay.None -> {
                    PixelCanvas(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (doc == null) {
                        EmptyEditorHint(
                            onNew = { showNewDialog = true },
                            onOpen = { showOpenSheet = true },
                            onNewSpritesheet = { overlay = Overlay.Spritesheet },
                            onRestore = if (viewModel.pendingAutosave.value != null) {
                                { viewModel.restoreAutosave {} }
                            } else null
                        )
                    }
                }
                Overlay.Spritesheet -> SpritesheetScreen(onClose = { overlay = Overlay.None })
                Overlay.Atlas -> AtlasScreen(onClose = { overlay = Overlay.None })
            }
        }
    }

    // --------------------------- diálogos -----------------------------------
    if (showNewDialog) {
        NewProjectDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { name, w, h ->
                showNewDialog = false
                viewModel.newProject(name, w, h) {}
            }
        )
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = viewModel.activeColor.value,
            palette = viewModel.palette.value,
            onDismiss = { showColorPicker = false },
            onPick = { viewModel.activeColor.value = it },
            onAddToPalette = { viewModel.addColorToPalette(it) },
            onRemoveFromPalette = { viewModel.removePaletteColor(it) }
        )
    }

    if (showOpenSheet) {
        OpenProjectSheet(
            viewModel = viewModel,
            onDismiss = { showOpenSheet = false },
            onOpenProject = { id ->
                showOpenSheet = false
                viewModel.openProject(id) {}
            },
            onImport = {
                showOpenSheet = false
                importLauncher.launch(arrayOf("*/*"))
            }
        )
    }

    if (showConfirmDiscard) {
        ConfirmDiscardDialog(
            onDismiss = { showConfirmDiscard = false; pendingAction = null },
            onDiscard = {
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            },
            onSaveFirst = {
                val action = pendingAction
                pendingAction = null
                viewModel.saveProject { action?.invoke() }
            }
        )
    }
}

/** Overlays internos do editor. */
private enum class Overlay { None, Spritesheet, Atlas }

// ---------------------------------------------------------------------------
// Menu de ações
// ---------------------------------------------------------------------------

@Composable
private fun EditorDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onImport: () -> Unit,
    onSpritesheet: () -> Unit,
    onAtlas: () -> Unit
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Novo projeto") },
            onClick = onNew
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Abrir projeto…") },
            onClick = onOpen
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Guardar") },
            onClick = onSave
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Guardar como… (.osp)") },
            onClick = onSaveAs
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Importar .osp…") },
            onClick = onImport
        )
        HorizontalDivider()
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Spritesheet…") },
            onClick = onSpritesheet
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Atlas de texturas…") },
            onClick = onAtlas
        )
    }
}

// ---------------------------------------------------------------------------
// Barra inferior de ferramentas
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorBottomToolbar(
    viewModel: EditorViewModel,
    onOpenColorPicker: () -> Unit
) {
    val tool by viewModel.tool
    val activeColor by viewModel.activeColor
    val secondaryColor by viewModel.secondaryColor
    val history = viewModel.history
    val doc = viewModel.document
    // Leitura do contador de frames garante recomposição em cada edição
    // (para os botões undo/redo refletirem a disponibilidade).
    @Suppress("UNUSED_VARIABLE")
    val frameTick = viewModel.frameVersion.intValue

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Linha 1: ferramentas + undo/redo — scroll horizontal em ecrãs estreitos
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            ToolButton(PixelIcons.pencil(), "Lápis", tool == PixelEngine.Tool.PENCIL) {
                viewModel.tool.value = PixelEngine.Tool.PENCIL
            }
            ToolButton(PixelIcons.eraser(), "Borracha", tool == PixelEngine.Tool.ERASER) {
                viewModel.tool.value = PixelEngine.Tool.ERASER
            }
            ToolButton(PixelIcons.fill(), "Balde", tool == PixelEngine.Tool.FILL) {
                viewModel.tool.value = PixelEngine.Tool.FILL
            }
            ToolButton(PixelIcons.eyedropper(), "Conta-gotas", tool == PixelEngine.Tool.EYEDROPPER) {
                viewModel.tool.value = PixelEngine.Tool.EYEDROPPER
            }

            // Undo / redo
            IconButton(onClick = { viewModel.undo() }, enabled = history.canUndo() || viewModel.frameVersion.intValue >= 0) {
                Icon(
                    PixelIcons.undo(), contentDescription = "Desfazer",
                    tint = if (history.canUndo()) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = { viewModel.redo() }, enabled = history.canRedo() || viewModel.frameVersion.intValue >= 0) {
                Icon(
                    PixelIcons.redo(), contentDescription = "Refazer",
                    tint = if (history.canRedo()) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline
                )
            }
        }

        // Linha 2: cores primária/secundária + paleta + pincel + zoom + grelha
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            // Cor secundária (atrás) + primária (à frente) — estilo Aseprite.
            Box(Modifier.size(40.dp)) {
                // Secundária (canto superior direito, atrás)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp)
                        .background(checkerPattern())
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .clickable { viewModel.swapColors() }
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(secondaryColor))
                    )
                }
                // Primária (canto inferior esquerdo, à frente)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .size(28.dp)
                        .background(checkerPattern())
                        .border(2.dp, MaterialTheme.colorScheme.primary)
                        .clickable { onOpenColorPicker() }
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(activeColor))
                    )
                }
            }
            // Trocar primária ↔ secundária
            IconButton(onClick = { viewModel.swapColors() }) {
                Icon(
                    PixelIcons.swapColors(),
                    contentDescription = "Trocar cor primária e secundária",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onOpenColorPicker) {
                Icon(PixelIcons.palette(), contentDescription = "Paleta de cores", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))

            // Cores da paleta do projeto: toque escolhe a PRIMÁRIA; toque longo
            // define a SECUNDÁRIA. Sem interromper a edição.
            viewModel.palette.value.forEach { color ->
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(28.dp)
                        .background(checkerPattern())
                        .border(
                            width = if (color == activeColor) 2.dp else 1.dp,
                            color = if (color == activeColor) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                        .combinedClickable(
                            onClick = { viewModel.activeColor.intValue = color },
                            onLongClick = { viewModel.setSecondaryColor(color) }
                        )
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(color))
                    )
                }
            }
            Spacer(Modifier.width(4.dp))

            // Pincel (só para lápis/borracha)
            if (tool == PixelEngine.Tool.PENCIL || tool == PixelEngine.Tool.ERASER) {
                Text(
                    "Pincel ${viewModel.brushSize.intValue}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    viewModel.brushSize.intValue =
                        if (viewModel.brushSize.intValue >= PixelEngine.MAX_BRUSH) 1
                        else viewModel.brushSize.intValue + 1
                }) { Text("+") }
            }
            // Tolerância (só para o balde)
            if (tool == PixelEngine.Tool.FILL) {
                Text(
                    "Tol. ${viewModel.fillTolerance.intValue}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    viewModel.fillTolerance.intValue =
                        if (viewModel.fillTolerance.intValue >= 64) 0
                        else viewModel.fillTolerance.intValue + 8
                }) { Text("+") }
            }

            // Zoom
            IconButton(onClick = { viewModel.zoomBy(1 / 1.5f) }) {
                Icon(PixelIcons.zoomOut(), contentDescription = "Reduzir zoom", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { viewModel.fitToScreen() }) {
                Icon(PixelIcons.fitScreen(), contentDescription = "Ajustar à vista", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { viewModel.zoomBy(1.5f) }) {
                Icon(PixelIcons.zoomIn(), contentDescription = "Aumentar zoom", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { viewModel.showGrid.value = !viewModel.showGrid.value }) {
                Icon(
                    PixelIcons.grid(), contentDescription = "Grelha de píxeis",
                    tint = if (viewModel.showGrid.value) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.width(8.dp))
            Text(
                doc?.let { "${it.width}×${it.height}  ${(viewModel.scale.value * 100).toInt()}%" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(width = 58.dp, height = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Rótulo visível (o ícone sozinho não identifica a ferramenta).
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Box(
                Modifier
                    .padding(top = 1.dp)
                    .size(width = 28.dp, height = 2.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Ecrã vazio (sem documento)
// ---------------------------------------------------------------------------

/**
 * Ecrã vazio (sem documento): as TRÊS ações principais são botões grandes e
 * sempre visíveis — nunca escondidas apenas num menu de ícones.
 */
@Composable
private fun EmptyEditorHint(
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onNewSpritesheet: () -> Unit,
    onRestore: (() -> Unit)?
) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag("empty_editor_hint")
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Editor de pixel art",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Escolhe uma ação para começar.\n" +
                    "1 dedo desenha · 2 dedos movem e fazem zoom.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onRestore != null) {
                OutlinedButton(onClick = onRestore) {
                    Text("Restaurar trabalho não guardado")
                }
            }
            androidx.compose.material3.Button(onClick = onNew, modifier = Modifier.testTag("action_new_project")) {
                Text("Novo projeto")
            }
            androidx.compose.material3.Button(onClick = onOpen, modifier = Modifier.testTag("action_open_recent")) {
                Text("Abrir recente")
            }
            androidx.compose.material3.Button(onClick = onNewSpritesheet, modifier = Modifier.testTag("action_new_spritesheet")) {
                Text("Novo spritesheet")
            }
        }
    }
}
