package com.pixelone.studio.ui.texture3d

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixelone.studio.studio3d.GltfLimits
import com.pixelone.studio.studio3d.PaintCore
import com.pixelone.studio.studio3d.Studio3DState.SamplingMode
import com.pixelone.studio.studio3d.Studio3DState.StudioMode
import com.pixelone.studio.studio3d.Studio3DViewModel
import com.pixelone.studio.studio3d.gl.ModelRenderer
import com.pixelone.studio.ui.theme.PixelBlack
import com.pixelone.studio.ui.theme.PixelGray
import com.pixelone.studio.ui.theme.PixelGrayBorder
import com.pixelone.studio.ui.theme.PixelGrayLight
import com.pixelone.studio.ui.theme.PixelOrange
import com.pixelone.studio.ui.theme.PixelSurface
import com.pixelone.studio.ui.theme.PixelSurfaceElevated

/**
 * 3D Texture Studio — FASE 3 (substitui o placeholder da Fase 0).
 *
 * LAYOUT vertical Android (regra do pedido):
 *  - topo (~48 dp): voltar, nome do projeto, importar, menu;
 *  - seletor de modo (~52 dp): 3D | UV | Dividido (UV/Dividido bloqueados e
 *    explicados quando o modelo não tem UV);
 *  - área principal (TODO o espaço restante): viewport 3D / vista UV / modo
 *    Dividido — no modo Dividido, 3D e textura dividem ao meio (≈40% do ecrã
 *    cada em telemóveis típicos — requisito J da Ronda A);
 *  - painel inferior (~180 dp, recolhível com um toque): muda por modo;
 *  - barra de navegação (~56 dp): Texture / 3D / 2D→3D.
 *
 * CORREÇÕES RONDA A:
 *  - (A) INSETS: com enableEdgeToEdge (MainActivity), o ecrã desenha-se por
 *    baixo das system bars — a barra superior passou a respeitar
 *    [statusBarsPadding] e a barra de navegação [navigationBarsPadding].
 *    Nenhum texto fica por baixo da hora/ícones do sistema. (As Fases 1–2
 *    usam Scaffold/TopAppBar, que já respeitava insets — não foram tocadas.)
 *  - SELEÇÃO: toque curto no viewport 3D seleciona a face/ilha por raycast
 *    ([Studio3DViewModel.selectFaceAtNdc]); a seleção aparece na UV e nos
 *    dois painéis do modo Dividido.
 *
 * Ao abrir: modelo de demonstração + textura demo JÁ visíveis (requisito —
 * sem passos escondidos). Nenhum menu cobre o viewport inteiro.
 */
@Composable
fun Texture3DStudioScreen(
    onBack: () -> Unit,
    onOpenTextureStudio: () -> Unit = {},
    onOpen2Dto3D: () -> Unit = {}
) {
    val viewModel: Studio3DViewModel = viewModel()

    // --------------------------- estado local de UI ------------------------
    var showMenu by remember { mutableStateOf(false) }
    var showNewTextureDialog by remember { mutableStateOf(false) }
    var showOpenProjectDialog by remember { mutableStateOf(false) }
    var panelExpanded by remember { mutableStateOf(true) }

    /** Referência viva à vista UV (para o botão "Focar" do painel). */
    val uvViewHost = remember { mutableStateOf<TexturePaintView?>(null) }

    val mode by viewModel.mode.observeAsLocalState()
    val projectName by viewModel.projectName.observeAsLocalState()
    val hasUnsaved by viewModel.hasUnsavedChanges.observeAsLocalState()

    val snackbarHostState = remember { SnackbarHostState() }
    // Mensagens: observação simples com consumidor único.
    var lastShownStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.statusMessage.observeForever { msg ->
            if (msg != null && msg != lastShownStatus) {
                lastShownStatus = msg
            }
        }
    }
    LaunchedEffect(lastShownStatus) {
        lastShownStatus?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.statusMessage.value = null
            lastShownStatus = null
        }
    }

    // --------------------------- importação SAF ----------------------------
    val importModelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importModelFromUri(uri)
    }

    // ---------------------- exportações (Ronda B) --------------------------
    val exportTextureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) viewModel.exportTextureTo(uri)
    }
    val exportUvTemplateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) viewModel.exportUvTemplateTo(uri)
    }

    // --------------------------- retorno com guarda ------------------------
    BackHandler {
        onBack()
    }

    // Volta do Texture Studio: adota a textura atualizada (requisito: o 3D
    // atualiza quando a textura muda, sem reiniciar o projeto).
    DisposableEffect(Unit) {
        viewModel.adoptFromStudio2D()
        onDispose { }
    }

    Box(Modifier.fillMaxSize().background(PixelBlack)) {
        Column(Modifier.fillMaxSize()) {

            // ---------------------- topo -------------------------------
            // RONDA A (A): statusBarsPadding — o conteúdo começa ABAIXO da
            // hora/ícones; a cor da barra estende-se por baixo (edge-to-edge).
            Surface(color = PixelSurface, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = PixelGrayLight)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = projectName ?: "3D Texture Studio",
                            style = MaterialTheme.typography.titleSmall,
                            color = PixelGrayLight,
                            maxLines = 1
                        )
                        Text(
                            text = if (hasUnsaved) "alterações por guardar" else "projeto 3D",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasUnsaved) PixelOrange else PixelGray
                        )
                    }
                    IconButton(
                        onClick = { importModelLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Add, "Importar modelo glTF/GLB", tint = PixelOrange)
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Menu, "Menu", tint = PixelGrayLight)
                        }
                        Studio3DMenu(
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            viewModel = viewModel,
                            onExportTexture = {
                                showMenu = false
                                exportTextureLauncher.launch("textura.png")
                            },
                            onExportUvTemplate = {
                                showMenu = false
                                exportUvTemplateLauncher.launch("gabarito-uv.png")
                            },
                            onOpenProjects = {
                                showMenu = false
                                showOpenProjectDialog = true
                            },
                            onNewTexture = {
                                showMenu = false
                                showNewTextureDialog = true
                            }
                        )
                    }
                }
            }

            // ---------------------- seletor de modo ---------------------
            ModeSelector(viewModel = viewModel, mode = mode)

            // ---------------------- barra de pintura (Ronda B) ----------
            // SEMPRE visível: Pintar/Navegar, ferramentas, cor e
            // Desfazer/Refazer — fora de qualquer painel recolhível.
            val paintState by viewModel.paint.observeAsLocalState()
            PaintToolBar(viewModel = viewModel, state = paintState)

            // ---------------------- área principal (resto do ecrã) ------
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (mode) {
                    StudioMode.THREE_D -> ThreeDMode(viewModel)
                    StudioMode.UV -> UvMode(viewModel, uvViewHost)
                    StudioMode.SPLIT -> SplitMode(viewModel)
                }
            }

            // ---------------------- painel inferior ---------------------
            BottomPanel(
                viewModel = viewModel,
                mode = mode,
                expanded = panelExpanded,
                onToggle = { panelExpanded = !panelExpanded },
                onPaintTexture = {
                    viewModel.publishTextureToStudio2D()
                    onOpenTextureStudio()
                },
                uvViewHost = uvViewHost
            )

            // ---------------------- barra de navegação ------------------
            // RONDA A (A): navigationBarsPadding — os itens ficam acima da
            // barra de gestos/botões do sistema.
            Studio3DBottomNav(
                onOpenTextureStudio = onOpenTextureStudio,
                onOpen2Dto3D = onOpen2Dto3D
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 200.dp)
        )
    }

    if (showNewTextureDialog) {
        NewTextureDialog(
            onDismiss = { showNewTextureDialog = false },
            onCreate = { name, w, h ->
                showNewTextureDialog = false
                viewModel.newTexture(name, w, h)
            }
        )
    }
    if (showOpenProjectDialog) {
        OpenProjectDialog(
            viewModel = viewModel,
            onDismiss = { showOpenProjectDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Observação de LiveData sem dependência extra (runtime-livedata não está no BOM)
// ---------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
@Composable
fun <T> MutableLiveData<T>.observeAsLocalState(): androidx.compose.runtime.State<T> {
    val state = androidx.compose.runtime.remember { mutableStateOf(value) }
    DisposableEffect(this) {
        val observer = Observer<T> { state.value = it }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state as androidx.compose.runtime.State<T>
}

// ---------------------------------------------------------------------------
// Menu (⋮): guardar, abrir, nova textura, modelo demo, textura do 2D
// ---------------------------------------------------------------------------

@Composable
private fun Studio3DMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: Studio3DViewModel,
    onOpenProjects: () -> Unit,
    onNewTexture: () -> Unit,
    onExportTexture: () -> Unit = {},
    onExportUvTemplate: () -> Unit = {}
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Exportar textura (PNG)") },
            onClick = onExportTexture
        )
        DropdownMenuItem(
            text = { Text("Exportar gabarito UV (PNG)") },
            onClick = onExportUvTemplate
        )
        DropdownMenuItem(
            text = { Text("Guardar projeto") },
            onClick = { onDismiss(); viewModel.saveProject() }
        )
        DropdownMenuItem(
            text = { Text("Abrir projeto…") },
            onClick = onOpenProjects
        )
        DropdownMenuItem(
            text = { Text("Nova textura…") },
            onClick = onNewTexture
        )
        DropdownMenuItem(
            text = { Text("Usar geometria de demonstração") },
            onClick = { onDismiss(); viewModel.useDemoModel() }
        )
        DropdownMenuItem(
            text = { Text("Aplicar textura do Texture Studio") },
            onClick = {
                onDismiss()
                if (!viewModel.adoptFromStudio2D()) {
                    viewModel.statusMessage.value =
                        "Sem textura nova do Texture Studio — pinta uma e volta ao 3D"
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Seletor de modo: 3D | UV | Dividido (com bloqueio sem UV)
// ---------------------------------------------------------------------------

@Composable
private fun ModeSelector(viewModel: Studio3DViewModel, mode: StudioMode) {
    val hasUv = viewModel.model.hasUv
    Surface(color = PixelSurfaceElevated) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeChip("3D", mode == StudioMode.THREE_D, Modifier.weight(1f)) {
                viewModel.setMode(StudioMode.THREE_D)
            }
            ModeChip("UV", mode == StudioMode.UV, Modifier.weight(1f), enabled = hasUv) {
                viewModel.setMode(StudioMode.UV)
            }
            ModeChip("Dividido", mode == StudioMode.SPLIT, Modifier.weight(1f), enabled = hasUv) {
                viewModel.setMode(StudioMode.SPLIT)
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .background(if (selected) PixelOrange else PixelSurface)
            .border(
                width = 1.dp,
                color = when {
                    selected -> PixelOrange
                    !enabled -> PixelGrayBorder
                    else -> PixelGray
                },
                shape = MaterialTheme.shapes.small
            )
            .clickable(enabled = enabled) {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                selected -> Color.Black
                !enabled -> PixelGray.copy(alpha = 0.5f)
                else -> PixelGrayLight
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Modo 3D: viewport + botão repor câmara + faixa de materiais
// ---------------------------------------------------------------------------

@Composable
private fun ThreeDMode(viewModel: Studio3DViewModel) {
    Box(Modifier.fillMaxSize()) {
        Studio3DGlViewport(viewModel, Modifier.fillMaxSize())

        // Botão "Repor câmara" (requisito) — overlay, não cobre o viewport.
        Surface(
            color = PixelSurfaceElevated.copy(alpha = 0.9f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
        ) {
            Row(
                modifier = Modifier.clickable { viewModel.resetCamera() }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = PixelOrange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Repor câmara", style = MaterialTheme.typography.labelMedium, color = PixelGrayLight)
            }
        }

        // Faixa horizontal de miniaturas de materiais (requisito do modo 3D).
        val selectedMaterial by viewModel.selectedMaterial.observeAsLocalState()
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(PixelSurfaceElevated.copy(alpha = 0.92f))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            viewModel.model.materials.forEachIndexed { index, material ->
                val selected = index == selectedMaterial
                Column(
                    modifier = Modifier
                        .width(84.dp)
                        .background(PixelSurface)
                        .border(
                            1.dp,
                            if (selected) PixelOrange else PixelGrayBorder,
                            MaterialTheme.shapes.small
                        )
                        .clickable { viewModel.selectMaterial(index) }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(
                                Color(
                                    (material.baseColorFactor[0] * 255).toInt(),
                                    (material.baseColorFactor[1] * 255).toInt(),
                                    (material.baseColorFactor[2] * 255).toInt(),
                                    (material.baseColorFactor[3] * 255).toInt()
                                )
                            )
                            .border(1.dp, PixelGrayBorder, MaterialTheme.shapes.small)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        material.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = PixelGrayLight,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Viewport GL (GLSurfaceView) + gestos: 1 dedo roda; 2 dedos zoom e deslocam;
// TOQUE curto seleciona a face/ilha por raycast (requisito da Ronda A)
// ---------------------------------------------------------------------------

@Composable
internal fun Studio3DGlViewport(viewModel: Studio3DViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = remember { ModelRenderer(viewModel.camera) }

    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3) // ES 3.0: índices uint, universal em minSdk 26
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

            var lastX = 0f
            var lastY = 0f
            var panning = false

            // RONDA A — picking por toque: toque curto (sem passar o slop)
            // converte o ponto para NDC e pede a seleção ao ViewModel.
            var touchDownX = 0f
            var touchDownY = 0f
            var touchDownTime = 0L
            var movedBeyondSlop = false
            // RONDA B: true entre DOWN (que acertou no modelo) e UP — o traço 3D.
            var painting3d = false
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
            val tapTimeoutMs = 350L

            val scaleDetector = ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        panning = false
                        return true
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        viewModel.camera.zoom(detector.scaleFactor)
                        return true
                    }
                }
            )
            setOnTouchListener { v, e ->
                // RONDA B: estado de pintura lido POR EVENTO (a barra alterna
                // Pintar/Navegar sem recriar a vista).
                val paintState = viewModel.paint.value
                val paintEnabled = paintState?.paintMode == true
                val tool = paintState?.tool ?: PaintCore.Tool.PENCIL
                val strokeTool = tool == PaintCore.Tool.PENCIL ||
                    tool == PaintCore.Tool.ERASER ||
                    tool == PaintCore.Tool.LINE ||
                    tool == PaintCore.Tool.RECT
                val discreteTool = tool == PaintCore.Tool.FILL ||
                    tool == PaintCore.Tool.EYEDROPPER

                fun ndc(ev: MotionEvent): Pair<Float, Float> =
                    Pair(2f * ev.x / width - 1f, 1f - 2f * ev.y / height)

                when (e.pointerCount) {
                    1 -> when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastX = e.x; lastY = e.y; panning = true
                            touchDownX = e.x; touchDownY = e.y
                            touchDownTime = System.currentTimeMillis()
                            movedBeyondSlop = false
                            if (paintEnabled && strokeTool) {
                                // PINTAR: 1 dedo = raycast → UV → texel.
                                val (nx, ny) = ndc(e)
                                painting3d = viewModel.paintAtNdc(
                                    nx, ny, width.toFloat() / height, isStart = true
                                )
                                if (painting3d) panning = false // não roda durante o traço
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            // O arrasto passa o slop: já não é toque.
                            val dx = e.x - touchDownX
                            val dy = e.y - touchDownY
                            if (dx * dx + dy * dy > touchSlop * touchSlop) movedBeyondSlop = true
                            if (painting3d) {
                                // Traço contínuo: interpolação entre pontos de
                                // impacto (Bresenham em UV no funil de pintura).
                                val (nx, ny) = ndc(e)
                                viewModel.paintAtNdc(
                                    nx, ny, width.toFloat() / height, isStart = false
                                )
                            } else if (panning) {
                                // 1 dedo = RODAR a câmara (requisito).
                                viewModel.camera.rotate(e.x - lastX, e.y - lastY)
                            }
                            lastX = e.x; lastY = e.y
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (painting3d) {
                                viewModel.paintEndUv()
                                painting3d = false
                            } else {
                                val dx = e.x - touchDownX
                                val dy = e.y - touchDownY
                                val quick = System.currentTimeMillis() - touchDownTime <= tapTimeoutMs
                                if (paintEnabled && discreteTool && width > 0 && height > 0) {
                                    // Balde/conta-gotas no 3D (toque único).
                                    val (nx, ny) = ndc(e)
                                    viewModel.discreteAtNdc(nx, ny, width.toFloat() / height)
                                } else if (!paintEnabled && !movedBeyondSlop && quick &&
                                    width > 0 && height > 0
                                ) {
                                    // NAVEGAR: toque curto seleciona a face/ilha (Ronda A).
                                    val (nx, ny) = ndc(e)
                                    viewModel.selectFaceAtNdc(nx, ny, width.toFloat() / height)
                                }
                            }
                            true
                        }
                        else -> true
                    }
                    else -> {
                        movedBeyondSlop = true
                        // 2 dedos = zoom (pinça) + deslocamento (requisito) —
                        // mesmo em modo Pintar (nunca pinta com 2 dedos).
                        if (painting3d) {
                            viewModel.paintEndUv()
                            painting3d = false
                        }
                        scaleDetector.onTouchEvent(e)
                        if (e.actionMasked == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress) {
                            val cx = (e.getX(0) + e.getX(1)) / 2f
                            val cy = (e.getY(0) + e.getY(1)) / 2f
                            if (panning) {
                                viewModel.camera.pan(cx - lastX, cy - lastY, height)
                            }
                            lastX = cx; lastY = cy
                        } else if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                            lastX = (e.getX(0) + e.getX(1)) / 2f
                            lastY = (e.getY(0) + e.getY(1)) / 2f
                            panning = true
                        }
                        true
                    }
                }.also { v.performClick() }
            }
        }
    }

    // Liga o renderer ao ViewModel (envia cena + textura atuais).
    LaunchedEffect(renderer) {
        viewModel.attachRenderer(renderer)
    }

    // Pausa/retoma do GL quando a app vai para segundo plano.
    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.attachRenderer(null)
            renderer.release()
            glView.onPause()
        }
    }

    AndroidView(
        factory = { glView },
        modifier = modifier
    )
}
