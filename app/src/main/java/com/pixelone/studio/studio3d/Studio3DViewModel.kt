package com.pixelone.studio.studio3d

import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.pixelone.studio.perf.PerfLog
import com.pixelone.studio.pixel.HistoryManager
import com.pixelone.studio.pixel.LayerStack
import com.pixelone.studio.pixel.LayeredHistory
import com.pixelone.studio.pixel.NativeEngine
import com.pixelone.studio.pixel.PixelDocument
import com.pixelone.studio.project.PixelProject
import com.pixelone.studio.osp.FileSystemProjectRepository
import com.pixelone.studio.osp.LayersCodec
import com.pixelone.studio.osp.MiniJson
import com.pixelone.studio.studio3d.GltfParser.GltfModel
import com.pixelone.studio.studio3d.PaintCore.MirrorBase
import com.pixelone.studio.studio3d.PaintCore.Symmetry
import com.pixelone.studio.studio3d.PaintCore.Tool
import com.pixelone.studio.studio3d.Studio3DState.ModelKind
import com.pixelone.studio.studio3d.Studio3DState.SamplingMode
import com.pixelone.studio.studio3d.Studio3DState.StudioMode
import com.pixelone.studio.studio3d.Studio3DState.TextureRef
import com.pixelone.studio.studio3d.gl.ModelRenderer
import com.pixelone.studio.studio3d.UvIslands.UvIsland
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TAG dos registos do caminho de importação 3D (visível no Logcat e no CI). */
private const val TAG_IMPORT = "PixelOneImport"

/**
 * ViewModel do 3D Texture Studio (Fase 3, Ronda B — PINTURA).
 *
 * REGRAS DE ARQUITETURA (herdadas do projeto):
 *  - A textura do 3D Studio vive numa PILHA DE CAMADAS ([LayerStack]) — cada
 *    camada é um [PixelDocument] (buffer direto partilhado Kotlin↔C++); a
 *    COMPOSIÇÃO ([compositeDoc]) é o que a GPU, a vista UV e o Texture
 *    Studio 2D veem. Projetos antigos abrem como UMA camada.
 *  - TODAS as mutações de píxeis correm num executor serial único
 *    ("pixelone-3d-mutation") — sem leituras rasgadas, igual ao editor 2D.
 *  - A GPU recebe APENAS a região suja ([ModelRenderer.updateTextureRegion])
 *    enquanto se pinta — nunca a textura inteira por traço.
 *  - Pintar NO 3D e NA UV usa o MESMO funil ([PaintCore]): raycast → UV
 *    (baricêntricas) → texel → carimbo com máscara de ilha e simetria.
 *  - O histórico ([LayeredHistory]) é GLOBAL (3D/UV/camadas partilham) com
 *    deltas esparsos e orçamento de memória para aparelhos de 4 GB.
 *
 * PERSISTÊNCIA: .osp v3 — pixels.rgba = composição; layers.json + layer-N.rgba
 * = camadas; studio3d.json = referências 3D. Projetos v1/v2 abrem como 1 camada.
 */
class Studio3DViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = FileSystemProjectRepository(File(app.filesDir, "projects"))

    // ------------------------------------------------------------------
    // Camadas + composição + histórico (Ronda B)
    // ------------------------------------------------------------------

    var layerStack: LayerStack = LayerStack(64, 64)
        private set

    /** Composição das camadas visíveis — fonte para GPU/UV/2D/exportação. */
    var compositeDoc: PixelDocument = PixelDocument(64, 64)
        private set

    private val layerHistory = LayeredHistory()
    private var strokeScratch: NativeEngine.Scratch? = null

    private val mutationExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pixelone-3d-mutation").apply { isDaemon = true }
    }

    /** Bitmap de apresentação (COMPOSIÇÃO) para a superfície de pintura (2D). */
    val presentationBitmap = MutableLiveData<Bitmap?>(null)

    /**
     * Incrementado a cada região suja aplicada — a superfície de pintura
     * observa e redesenha (invalidate) SEM recompor a árvore inteira.
     */
    val paintVersion = MutableLiveData(0)

    val hasUnsavedChanges = MutableLiveData(false)
    val statusMessage = MutableLiveData<String?>(null)

    // ------------------------------------------------------------------
    // Cena 3D
    // ------------------------------------------------------------------

    /** Modelo atual (demo por defeito — requisito: visível logo ao abrir). */
    var model: GltfModel = DemoGeometry.createModel()
        private set

    var modelKind: ModelKind = ModelKind.DEMO
        private set
    var modelFileName: String? = null
        private set
    var modelFileBytes: Long? = null
        private set
    var modelSha256: String? = null
        private set

    /** Nome do projeto (topo do ecrã). */
    val projectName = MutableLiveData("textura-demo")

    /** Bitmaps descodificados das imagens embutidas do GLB (por índice). */
    var embeddedBitmaps: Map<Int, Bitmap> = emptyMap()
        private set

    /** Associação material → textura (DOCUMENT/EMBEDDED/NONE). */
    val bindings = MutableLiveData<Map<Int, TextureRef>>(
        mapOf(0 to TextureRef.DOCUMENT, 1 to TextureRef.NONE)
    )

    val sampling = MutableLiveData(SamplingMode.NEAREST)
    val mode = MutableLiveData(StudioMode.THREE_D)
    val showUvOverlay = MutableLiveData(true)
    val showWireframe = MutableLiveData(false)
    val selectedMaterial = MutableLiveData(0)

    /** Malha selecionada na vista UV (null = todas). */
    val selectedMesh = MutableLiveData<Int?>(null)

    // ------------------------------------------------------------------
    // MISSÃO UI/SELEÇÃO — texturas como objetos selecionáveis
    // ------------------------------------------------------------------

    /**
     * Textura SELECIONADA (identidade estável de [TextureCatalog]):
     * "document" (a do projeto — a única editável) ou "embedded:<i>"
     * (embutida do GLB — só leitura). Distinta da seleção de material,
     * malha/objeto e face/ilhas (níveis separados, sincronizados onde faz
     * sentido — ver [selectTexture]/[selectMaterial]).
     */
    val selectedTexture = MutableLiveData(TextureCatalog.DOCUMENT_ID)

    /** Catálogo das texturas do modelo (projeto + embutidas usadas). */
    val textureCatalog = MutableLiveData<List<TextureEntry>>(emptyList())

    /**
     * Bitmap mostrado nas vistas UV/Dividido: a textura SELECIONADA —
     * composição do projeto OU a embutida (só leitura).
     */
    val viewTexture = MutableLiveData<Bitmap?>(null)

    /**
     * Seleção partilhada pelos painéis 3D e UV (requisito Ronda A: no modo
     * Dividido aparece nos DOIS painéis em simultâneo).
     */
    data class IslandSelection(
        val renderableIndex: Int,
        val island: UvIsland,
        val faceTriangle: Int? = null
    )

    val selectedIsland = MutableLiveData<IslandSelection?>(null)

    /** Ilhas por primitiva (recalculadas quando o modelo muda). */
    var uvIslands: Map<Int, List<UvIsland>> = emptyMap()
        private set

    val camera = OrbitCamera()

    /** Renderer anexado pela UI (null até o GLSurfaceView existir). */
    private var renderer: ModelRenderer? = null

    // ------------------------------------------------------------------
    // Estado de pintura (Ronda B)
    // ------------------------------------------------------------------

    /** Estado observável da barra de pintura (snapshot — UI reage como um todo). */
    data class PaintState(
        val tool: Tool = Tool.PENCIL,
        val brush: Int = 1,
        val color: Int = DEFAULT_COLOR,
        val paintMode: Boolean = false,       // false = Navegar (não pinta sem querer)
        val lockToIsland: Boolean = true,     // Bloquear na ilha (default c/ seleção)
        val symmetry: Symmetry = Symmetry.NONE,
        val mirrorBase: MirrorBase = MirrorBase.TEXTURE,
        val fillTolerance: Int = 0,
        val islandPadding: Int = DEFAULT_PADDING,
        val recentColors: List<Int> = DEFAULT_RECENTS,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false
    )

    data class LayerRow(
        val index: Int,
        val id: Long,
        val name: String,
        val visible: Boolean,
        val opacity: Int,
        val active: Boolean
    )

    private var paintState = PaintState()
    val paint = MutableLiveData(paintState)

    private var layersState: List<LayerRow> = emptyList()
    val layers = MutableLiveData<List<LayerRow>>(emptyList())

    private var lastPaintX = -1
    private var lastPaintY = -1

    /** Traço de forma (linha/retângulo): texel inicial (fixado no down). */
    private var shapeStartX = -1
    private var shapeStartY = -1

    /** Balde armado: o próximo toque preenche (mantido por compatibilidade). */
    var pendingFill: Boolean
        get() = paintState.tool == Tool.FILL
        set(_) { setTool(Tool.FILL) }

    /** Escrita hasUnsavedChanges segura do executor → main. */
    @Volatile
    private var hasUnsavedChangesLive: Boolean = false

    // Máscara de ilha (cache — construída no executor na 1.ª utilização)
    private var cachedMask: IslandMask? = null
    private var maskKey: Long = -1 // (renderable, islandId, padding, w, h) empacotado

    /** Último triângulo tocado (otimização do raycast por movimento). */
    @Volatile
    private var lastPickTriangle: Int = -1
    @Volatile
    private var lastPickRenderable: Int = -1

    init {
        // Textura demo imediata: estúdio abre com modelo E textura visíveis.
        newTextureInternal(DemoGeometry.createDefaultTexture(), 64, 64, "textura-demo")
        computeIslands()
        fitCameraToModel()
        postPaintState()
        postTextureCatalog()
        postViewTexture()
    }

    override fun onCleared() {
        mutationExecutor.shutdown()
        renderer?.release()
        super.onCleared()
    }

    /** Anexa o renderer (chamado pela UI quando o GLSurfaceView é criado). */
    fun attachRenderer(r: ModelRenderer?) {
        renderer = r
        if (r != null) {
            val doc = compositeDoc
            r.replaceDocumentTexture(doc.copyPixels(), doc.width, doc.height)
            r.setScene(model, embeddedBitmaps, bindings.value ?: emptyMap(), sampling.value ?: SamplingMode.NEAREST)
            r.setWireframe(showWireframe.value ?: false)
            applyHighlightToRenderer()
        }
    }

    /** Reenvia o realce da ilha selecionada (após attach ou mudanças). */
    private fun applyHighlightToRenderer() {
        val sel = selectedIsland.value
        renderer?.setHighlight(sel?.renderableIndex, sel?.island?.triangleIndices)
    }

    // ------------------------------------------------------------------
    // Estado da barra de pintura (UI → ViewModel)
    // ------------------------------------------------------------------

    private fun postPaintState() {
        val maskActive = selectedIsland.value != null
        paintState = paintState.copy(
            mirrorBase = if (maskActive) MirrorBase.ISLAND else MirrorBase.TEXTURE,
            canUndo = layerHistory.canUndo(),
            canRedo = layerHistory.canRedo()
        )
        paint.postValue(paintState)
    }

    fun setTool(tool: Tool) {
        paintState = paintState.copy(tool = tool)
        paint.value = paintState
    }

    fun setBrush(size: Int) {
        paintState = paintState.copy(brush = size.coerceIn(1, PixelBrushMax))
        paint.value = paintState
    }

    fun setPaintMode(enabled: Boolean) {
        paintState = paintState.copy(paintMode = enabled)
        paint.value = paintState
    }

    fun setLockToIsland(enabled: Boolean) {
        paintState = paintState.copy(lockToIsland = enabled)
        paint.value = paintState
    }

    fun setSymmetry(symmetry: Symmetry) {
        paintState = paintState.copy(symmetry = symmetry)
        paint.value = paintState
    }

    fun setIslandPadding(px: Int) {
        val p = px.coerceIn(0, IslandMask.MAX_PADDING)
        paintState = paintState.copy(islandPadding = p)
        paint.value = paintState
        invalidateMask()
    }

    fun setFillTolerance(tolerance: Int) {
        paintState = paintState.copy(fillTolerance = tolerance.coerceIn(0, 255))
        paint.value = paintState
    }

    /** Escolhe uma cor (paleta/conta-gotas/hex) — entra nas recentes. */
    fun setColor(color: Int) {
        val recents = (listOf(color) + paintState.recentColors.filter { it != color })
            .take(MAX_RECENTS)
        paintState = paintState.copy(color = color, recentColors = recents)
        paint.value = paintState
        hasUnsavedChangesLive = true
        postUnsaved()
    }

    fun setShowWireframe(show: Boolean) {
        showWireframe.value = show
        renderer?.setWireframe(show)
    }

    // ------------------------------------------------------------------
    // PINTURA — funil único (3D e UV)
    //
    // Fontes de toque:
    //  - Vista UV 2D (modo Dividido/UV): paintBegin/Move/End em TEXELS.
    //  - Viewport 3D: raycast → uvAtHit (baricêntricas) → paintBeginUv/MoveUv
    //    (UV) — mesma conversão uvToTexel, MESMO resultado (regra da Ronda B).
    // ------------------------------------------------------------------

    /** Início do traço na vista 2D/UV (texel). Um gesto = UMA entrada. */
    fun paintBegin(x: Int, y: Int) {
        if (paintBlockedByTextureSelection()) return
        // MISSÃO TEXTURIZAÇÃO: a pintura do projeto tem de ficar VISÍVEL no
        // modelo — materiais que apresentam a textura embutida passam a
        // apresentar a do projeto (flip ANUNCIADO; as embutidas ficam no
        // catálogo). Idempotente: sem material EMBEDDED é um no-op barato.
        ensurePaintVisibleOnModel()
        val doc = compositeDoc
        if (!doc.inBounds(x, y)) return
        lastPaintX = x; lastPaintY = y
        val tool = paintState.tool
        if (tool == Tool.FILL) { fillAt(x, y); return }
        if (tool == Tool.EYEDROPPER) { pickColorTexel(x, y); return }
        // Formas: só fixa o ponto inicial (aplicadas no fim do gesto).
        if (tool == Tool.LINE || tool == Tool.RECT) {
            shapeStartX = x; shapeStartY = y
            layerHistory.beginStroke(activeLayerIndex())
            return
        }
        val color = activePaintColor()
        layerHistory.beginStroke(activeLayerIndex())
        val start = System.nanoTime()
        mutationExecutor.execute {
            val d = activeLayerDoc() ?: return@execute
            ensureMask()
            val bbox = PaintCore.stamp(
                d, x, y, color, paintState.brush,
                effectiveMask(), paintState.symmetry, effectiveMirrorBase(), layerHistory
            )
            if (bbox != null) applyEdit(bbox.minX, bbox.minY, bbox.maxX, bbox.maxY)
            val micros = (System.nanoTime() - start) / 1000L
            PerfLog.i(TAG, "traço(down) aplicado em $micros µs")
        }
    }

    /** Continuação do traço na vista 2D/UV (texel): segmento Bresenham ao vivo. */
    fun paintMove(x: Int, y: Int) {
        val doc = compositeDoc
        if (!doc.inBounds(x, y)) return
        val tool = paintState.tool
        if (tool == Tool.LINE || tool == Tool.RECT) { lastPaintX = x; lastPaintY = y; return }
        if (tool == Tool.FILL || tool == Tool.EYEDROPPER) return
        val color = activePaintColor()
        val lx = lastPaintX; val ly = lastPaintY
        if (lx < 0) return
        lastPaintX = x; lastPaintY = y
        mutationExecutor.execute {
            val d = activeLayerDoc() ?: return@execute
            ensureMask()
            val bbox = PaintCore.drawLineSegment(
                d, lx, ly, x, y, color, paintState.brush,
                effectiveMask(), paintState.symmetry, effectiveMirrorBase(), layerHistory
            )
            if (bbox != null) applyEdit(bbox.minX, bbox.minY, bbox.maxX, bbox.maxY)
        }
    }

    /** Fim do traço na vista 2D/UV: fecha a entrada única do histórico. */
    fun paintEnd() {
        if (paintBlockedByTextureSelection()) {
            lastPaintX = -1; lastPaintY = -1
            shapeStartX = -1; shapeStartY = -1
            return
        }
        val tool = paintState.tool
        val ex = lastPaintX; val ey = lastPaintY
        val sx = shapeStartX; val sy = shapeStartY
        mutationExecutor.execute {
            val d = activeLayerDoc()
            if (d == null) {
                lastPaintX = -1; lastPaintY = -1
                return@execute
            }
            if (tool == Tool.LINE || tool == Tool.RECT) {
                if (sx >= 0 && ex >= 0) {
                    ensureMask()
                    val bbox = if (tool == Tool.LINE) {
                        PaintCore.drawLineSegment(
                            d, sx, sy, ex, ey, activePaintColor(), paintState.brush,
                            effectiveMask(), paintState.symmetry, effectiveMirrorBase(), layerHistory
                        )
                    } else {
                        PaintCore.drawRectOutline(
                            d, sx, sy, ex, ey, activePaintColor(), paintState.brush,
                            effectiveMask(), paintState.symmetry, effectiveMirrorBase(), layerHistory
                        )
                    }
                    if (bbox != null) applyEdit(bbox.minX, bbox.minY, bbox.maxX, bbox.maxY)
                }
                shapeStartX = -1; shapeStartY = -1
            }
            runCatching { layerHistory.commitStroke(layerStack) }
                .onFailure { PerfLog.w(TAG, "commitStroke: ${it.message}") }
            hasUnsavedChangesLive = true
            postUnsaved()
            postPaintState()
            lastPaintX = -1; lastPaintY = -1
        }
    }

    // ----- Fonte 3D: raycast → UV → o MESMO funil -----

    /** Pintura 3D — início (u, v do ponto de impacto). */
    fun paintBeginUv(u: Float, v: Float) {
        val doc = compositeDoc
        val x = PaintCore.uvToTexelX(u, doc.width)
        val y = PaintCore.uvToTexelY(v, doc.height)
        paintBegin(x, y)
    }

    /** Pintura 3D — movimento. */
    fun paintMoveUv(u: Float, v: Float) {
        val doc = compositeDoc
        val x = PaintCore.uvToTexelX(u, doc.width)
        val y = PaintCore.uvToTexelY(v, doc.height)
        paintMove(x, y)
    }

    /** Pintura 3D — fim (igual à 2D). */
    fun paintEndUv() = paintEnd()

    // ----- Operações discretas -----

    /** Balde de preenchimento (toque único) — limitado pela máscara da ilha. */
    fun fillAt(x: Int, y: Int) {
        val doc = compositeDoc
        if (!doc.inBounds(x, y)) return
        // Mesma regra do traço: o balde edita o projeto — torna-o visível.
        ensurePaintVisibleOnModel()
        val color = if (paintState.tool == Tool.ERASER) 0x00000000 else paintState.color
        val tolerance = paintState.fillTolerance
        val scratch = strokeScratch
        mutationExecutor.execute {
            val d = activeLayerDoc() ?: return@execute
            ensureMask()
            val s = scratch ?: NativeEngine.Scratch(d.pixelCount).also { strokeScratch = it }
            val mask = effectiveMask()
            val bbox = IntArray(4)
            val n = PaintCore.floodFillMasked(
                d, x, y, color, tolerance, mask,
                s.indices, s.before, s.after, bbox
            )
            if (n > 0) {
                layerHistory.commitDeltas(
                    activeLayerIndex(), s.indices, s.before, s.after, n,
                    bbox[0], bbox[1], bbox[2], bbox[3]
                )
                applyEdit(bbox[0], bbox[1], bbox[2], bbox[3])
                hasUnsavedChangesLive = true
                postUnsaved()
                postPaintState()
            }
        }
    }

    /** Balde via UV (3D). */
    fun fillAtUv(u: Float, v: Float) {
        val doc = compositeDoc
        fillAt(PaintCore.uvToTexelX(u, doc.width), PaintCore.uvToTexelY(v, doc.height))
    }

    /** Conta-gotas em texel (lê a COMPOSIÇÃO — o que o utilizador vê). */
    fun pickColorTexel(x: Int, y: Int): Int? {
        val doc = compositeDoc
        if (!doc.inBounds(x, y)) return null
        val c = doc.get(x, y)
        if ((c ushr 24) and 0xFF != 0) setColor(c)
        return c
    }

    /** Conta-gotas via UV (3D e UV partilham). */
    fun pickColorUv(u: Float, v: Float): Int? {
        val doc = compositeDoc
        return pickColorTexel(
            PaintCore.uvToTexelX(u, doc.width), PaintCore.uvToTexelY(v, doc.height)
        )
    }

    fun undo() {
        mutationExecutor.execute {
            runCatching {
                val entry = layerHistory.undo(layerStack) ?: return@execute
                applyEdit(entry.minX, entry.minY, entry.maxX, entry.maxY)
                hasUnsavedChangesLive = true
                postUnsaved()
            }.onFailure { PerfLog.w(TAG, "undo: ${it.message}") }
            postPaintState()
        }
    }

    fun redo() {
        mutationExecutor.execute {
            runCatching {
                val entry = layerHistory.redo(layerStack) ?: return@execute
                applyEdit(entry.minX, entry.minY, entry.maxX, entry.maxY)
                hasUnsavedChangesLive = true
                postUnsaved()
            }.onFailure { PerfLog.w(TAG, "redo: ${it.message}") }
            postPaintState()
        }
    }

    fun canUndo(): Boolean = layerHistory.canUndo()
    fun canRedo(): Boolean = layerHistory.canRedo()

    private fun activePaintColor(): Int =
        if (paintState.tool == Tool.ERASER) 0x00000000 else paintState.color

    private fun activeLayerIndex(): Int = layerStack.activeIndex

    private fun activeLayerDoc(): PixelDocument? =
        layerStack.layers.getOrNull(layerStack.activeIndex)?.doc

    /**
     * Máscara EFETIVA do traço: só bloqueia quando há ilha selecionada E o
     * bloqueio está ligado (regra da Ronda B: sem seleção pinta em qualquer
     * ilha).
     */
    private fun effectiveMask(): IslandMask? {
        if (!paintState.lockToIsland) return null
        return cachedMask
    }

    private fun effectiveMirrorBase(): MirrorBase =
        if (effectiveMask() != null) MirrorBase.ISLAND else MirrorBase.TEXTURE

    /** Constrói/reconstrói a máscara se necessário (thread de mutação). */
    private fun ensureMask() {
        val sel = selectedIsland.value
        if (!paintState.lockToIsland || sel == null) { cachedMask = null; return }
        val key = sel.renderableIndex.toLong() * 1_000_007L +
            sel.island.id * 97L + paintState.islandPadding * 7L +
            compositeDoc.width.toLong() * 1_000_003L + compositeDoc.height.toLong()
        if (cachedMask != null && key == maskKey) return
        val r = model.renderables.getOrNull(sel.renderableIndex)
        val uvs = r?.uvs
        if (r == null || uvs == null) { cachedMask = null; return }
        val startNanos = System.nanoTime()
        cachedMask = IslandMask.build(
            r.indices, uvs, sel.island,
            compositeDoc.width, compositeDoc.height, paintState.islandPadding
        )
        maskKey = key
        val micros = (System.nanoTime() - startNanos) / 1000L
        PerfLog.i(TAG, "máscara da ilha ${sel.island.id} (${cachedMask?.activeCount()} texels) em $micros ms")
    }

    private fun invalidateMask() {
        cachedMask = null
        maskKey = -1
    }

    /**
     * MISSÃO TEXTURIZAÇÃO (pintura invisível em modelos importados):
     *
     * CAUSA RAIZ: na importação, materiais com textura embutida ficam com
     * binding EMBEDDED — o modelo apresenta a textura ORIGINAL do ficheiro,
     * e a pintura (na textura do PROJETO) não aparecia em lado nenhum do
     * modelo 3D. O utilizador pintava e nada mudava.
     *
     * CORREÇÃO: no início de cada traço/balde, materiais que APRESENTAM
     * textura embutida passam a apresentar a textura do projeto — flip
     * ANUNCIADO (nunca silencioso), via [ModelRenderer.setBindings] (sem
     * rebuild da cena). As bitmaps embutidas NÃO são alteradas nem
     * descartadas: continuam no catálogo "Texturas do modelo" e os chips
     * de material as repostas. Sem material EMBEDDED: no-op barato.
     */
    private fun ensurePaintVisibleOnModel() {
        val current = bindings.value ?: return
        val plan = PaintBinding.ensureDocumentVisible(
            materials = model.materials.map {
                TextureCatalog.MaterialInfo(it.name, it.textureImageIndex)
            },
            bindings = current
        )
        if (plan.isEmpty) return
        val next = current.toMutableMap().apply { putAll(plan.flips) }
        bindings.value = next
        hasUnsavedChanges.value = true
        renderer?.setBindings(next)
        postTextureCatalog()
        val shown = plan.materialNames.take(3).joinToString(", ")
        val extra = plan.materialNames.size - minOf(3, plan.materialNames.size)
        statusMessage.value =
            "Pintura agora visível no modelo: $shown" +
                (if (extra > 0) " e mais $extra material(is)" else "") +
                " passam a apresentar a textura do projeto " +
                "(as texturas originais ficam em \"Texturas do modelo\")"
    }

    /**
     * Região suja: recompose REGIONAL da composição, bitmap de apresentação
     * (regional, nunca inteiro) e ATUALIZAÇÃO PARCIAL da GPU (glTexSubImage2D).
     * Corre no executor de mutação — os documentos já estão estáveis.
     */
    private fun applyEdit(x0: Int, y0: Int, x1: Int, y1: Int) {
        val doc = compositeDoc
        val rect = DirtyRectAccumulator()
            .apply { add(x0 - 1, y0 - 1, x1 + 1, y1 + 1) }
            .take(doc.width, doc.height) ?: return
        val w = rect.width
        val h = rect.height
        val region = IntArray(w * h)
        layerStack.composeRegion(rect.x0, rect.y0, w, h, region)
        doc.writeRegion(rect.x0, rect.y0, w, region)
        // Bitmap de apresentação: só a região (mesma técnica do editor 2D).
        val bmp = presentationBitmap.value
        if (bmp != null && bmp.width == doc.width && bmp.height == doc.height) {
            bmp.setPixels(region, 0, w, rect.x0, rect.y0, w, h)
        }
        // GPU: APENAS a região suja (requisito do modo Dividido / Ronda B).
        renderer?.updateTextureRegion(rect, { doc.getAt(it) }, doc.width)
        // Redesenho da superfície 2D (bitmap já foi atualizado regionalmente).
        // OTIMIZAÇÃO (missão texturização): postValue em vez de launch+Dispatchers.Main
        // por evento de movimento — sem alocação de corutina por evento e com
        // coalescência natural de invalidações rápidas.
        paintVersion.postValue((paintVersion.value ?: 0) + 1)
        if (hasUnsavedChangesLive) postUnsaved()
    }

    /** Recomposição TOTAL (operações estruturais de camadas / abrir / novo). */
    private fun recomposeAll() {
        val doc = compositeDoc
        val full = layerStack.compose()
        doc.writeRegion(0, 0, doc.width, full)
        val bmp = presentationBitmap.value
        if (bmp != null && bmp.width == doc.width && bmp.height == doc.height) {
            bmp.setPixels(full, 0, doc.width, 0, 0, doc.width, doc.height)
        }
        renderer?.updateTextureRegion(DirtyRect(0, 0, doc.width - 1, doc.height - 1), { doc.getAt(it) }, doc.width)
        paintVersion.postValue((paintVersion.value ?: 0) + 1)
        postLayersState()
        invalidateMask()
    }

    private fun postUnsaved() {
        viewModelScope.launch(Dispatchers.Main) {
            hasUnsavedChanges.value = true
        }
    }

    private fun postLayersState() {
        layersState = layerStack.layers.mapIndexed { i, l ->
            LayerRow(i, l.id, l.name, l.visible, l.opacity, i == layerStack.activeIndex)
        }
        layers.postValue(layersState)
    }

    // ------------------------------------------------------------------
    // CAMADAS (Ronda B) — operações estruturais (executor; recomposição total)
    // ------------------------------------------------------------------

    /** Adiciona uma camada NO TOPO (erro recuperável se exceder limites). */
    fun addLayer() {
        mutationExecutor.execute {
            try {
                layerStack.addLayer()
                hasUnsavedChangesLive = true
                postUnsaved()
                statusMessage.postValue("Camada adicionada (${layerStack.count}/${LayerStack.MAX_LAYERS})")
            } catch (e: LayerStack.LayerLimitException) {
                statusMessage.postValue(e.message ?: "Limite de camadas atingido")
            }
            recomposeAll()
        }
    }

    /** Remove a camada [index] (a última não é removível). */
    fun removeLayer(index: Int) {
        mutationExecutor.execute {
            if (layerStack.count <= 1) {
                statusMessage.postValue("Não é possível remover a última camada")
                return@execute
            }
            layerStack.removeLayer(index)
            hasUnsavedChangesLive = true
            postUnsaved()
            statusMessage.postValue("Camada removida")
            recomposeAll()
        }
    }

    fun selectLayer(index: Int) {
        mutationExecutor.execute {
            layerStack.setActive(index)
            postLayersState()
        }
    }

    fun toggleLayerVisible(index: Int) {
        mutationExecutor.execute {
            layerStack.toggleVisible(index)
            hasUnsavedChangesLive = true
            postUnsaved()
            recomposeAll()
        }
    }

    fun setLayerOpacity(index: Int, opacity: Int) {
        mutationExecutor.execute {
            layerStack.setOpacity(index, opacity)
            hasUnsavedChangesLive = true
            recomposeAll()
        }
    }

    fun moveLayerUp(index: Int) {
        mutationExecutor.execute {
            if (layerStack.moveUp(index)) {
                hasUnsavedChangesLive = true
                postUnsaved()
            }
            recomposeAll()
        }
    }

    fun moveLayerDown(index: Int) {
        mutationExecutor.execute {
            if (layerStack.moveDown(index)) {
                hasUnsavedChangesLive = true
                postUnsaved()
            }
            recomposeAll()
        }
    }

    /**
     * Preenche a camada ATIVA com o xadrez de TESTE (visibilidade de UVs e
     * costuras). Uma entrada de histórico (desfazível). Limitado a docs
     * pequenos para não estourar o orçamento de histórico em 4 GB.
     */
    fun fillWithTestChecker() {
        val doc = compositeDoc
        if (doc.width * doc.height > 512 * 512) {
            statusMessage.value = "Xadrez de teste disponível até 512×512 (esta textura é " +
                "${doc.width}×${doc.height})"
            return
        }
        mutationExecutor.execute {
            val d = activeLayerDoc() ?: return@execute
            val n = d.pixelCount
            val indices = IntArray(n); val before = IntArray(n); val after = IntArray(n)
            var count = 0
            var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
            for (y in 0 until d.height) {
                for (x in 0 until d.width) {
                    val idx = y * d.width + x
                    val checker = ((x / 8) + (y / 8)) % 2 == 0
                    val newColor = if (checker) TEST_CHECKER_A else TEST_CHECKER_B
                    val old = d.getAt(idx)
                    if (old == newColor) continue
                    indices[count] = idx; before[count] = old; after[count] = newColor
                    d.setAt(idx, newColor)
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (count > 0) {
                layerHistory.commitDeltas(
                    activeLayerIndex(), indices, before, after, count, minX, minY, maxX, maxY
                )
                applyEdit(minX, minY, maxX, maxY)
                hasUnsavedChangesLive = true
                postUnsaved()
                statusMessage.postValue("Xadrez de teste aplicado à camada ativa")
                postPaintState()
            }
        }
    }

    // ------------------------------------------------------------------
    // Modelo 3D: demo, importação, fit
    // ------------------------------------------------------------------

    /** Repor a geometria de demonstração. */
    fun useDemoModel() {
        applyModel(
            model = DemoGeometry.createModel(),
            kind = ModelKind.DEMO,
            fileName = null,
            fileBytes = null,
            sha256 = null,
            embedded = emptyMap(),
            bindingsOverride = mapOf(0 to TextureRef.DOCUMENT, 1 to TextureRef.NONE)
        )
        statusMessage.value = "Modelo de demonstração ativo"
    }

    /**
     * Importa um modelo glTF/GLB REAL (análise fora da main thread).
     * @param bytes conteúdo lido (a UI lê com o limite de [GltfLimits.MAX_FILE_BYTES]).
     * @param externalFiles ficheiros de apoio (.bin/imagens externas) selecionados junto com o modelo.
     * @throws nada — todos os erros viram mensagens em [statusMessage].
     */
    fun importModel(
        bytes: ByteArray,
        fileName: String,
        externalFiles: Map<String, ByteArray> = emptyMap()
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val startNanos = System.nanoTime()
                Log.d(TAG_IMPORT, "parse início: $fileName · ${bytes.size} bytes · " +
                    "extras=${externalFiles.size} (${externalFiles.keys.joinToString(", ")})")
                if (bytes.size > GltfLimits.MAX_FILE_BYTES) {
                    throw GltfParser.GltfParseException(
                        GltfLimits.tooLargeError(
                            "ficheiro de modelo", bytes.size.toLong(), GltfLimits.MAX_FILE_BYTES
                        )
                    )
                }
                val parsed = GltfParser.parse(bytes, fileName, externalFiles)
                // Descodificar imagens embutidas (PNG/JPEG) com limites.
                val bitmaps = decodeImages(parsed)
                // Associação inicial: material com textura embutida → EMBEDDED;
                // sem textura → DOCUMENT (textura do Texture Studio).
                val initialBindings = HashMap<Int, TextureRef>()
                parsed.materials.forEachIndexed { i, m ->
                    initialBindings[i] = if (m.textureImageIndex != null) TextureRef.EMBEDDED else TextureRef.DOCUMENT
                }
                val sha = sha256Of(bytes)
                withContext(Dispatchers.Main) {
                    applyModel(
                        model = parsed,
                        kind = ModelKind.IMPORTED,
                        fileName = fileName,
                        fileBytes = bytes.size.toLong(),
                        sha256 = sha,
                        embedded = bitmaps,
                        bindingsOverride = initialBindings
                    )
                    val ms = (System.nanoTime() - startNanos) / 1_000_000L
                    Log.d(
                        TAG_IMPORT,
                        "parse OK: $fileName — ${parsed.meshNames.size} malhas, " +
                            "${parsed.materials.size} materiais, ${parsed.triangleCount} triângulos, " +
                            "${parsed.images.size} imagens, $ms ms"
                    )
                    statusMessage.value = if (parsed.hasUv) {
                        "Modelo carregado: ${parsed.meshNames.size} malhas, " +
                            "${parsed.materials.size} materiais, " +
                            "${parsed.triangleCount} triângulos ($ms ms)"
                    } else {
                        "Modelo carregado SEM UV — modos UV e Dividido bloqueados. " +
                            "O modo 3D continua funcional."
                    }
                    hasUnsavedChanges.value = true
                }
            } catch (e: Exception) {
                Log.d(TAG_IMPORT, "parse FALHOU: ${e.message}")
                withContext(Dispatchers.Main) {
                    statusMessage.value = e.message ?: "Falha ao importar o modelo"
                }
            }
        }
    }

    /**
     * Importação direta de um URI SAF (atalho compatível para um só ficheiro).
     */
    fun importModelFromUri(uri: android.net.Uri) {
        importModelFromUris(listOf(uri))
    }

    /**
     * Importação SAF de UM OU MAIS URIs: o modelo principal é detetado pelo
     * CONTEÚDO (magia GLB "glTF" ou JSON — nunca pela extensão nem pelo MIME)
     * e os restantes ficheiros ficam disponíveis como recursos externos
     * (.bin / imagens). Tudo registado com Log.d para diagnóstico no Logcat/CI.
     */
    fun importModelFromUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                Log.d(TAG_IMPORT, "Importação iniciada: ${uris.size} ficheiro(s) — $uris")
                val lidos = ArrayList<Triple<String, ByteArray, android.net.Uri>>()
                for (uri in uris) {
                    val name = displayNameOf(cr, uri)
                    val bytes = readModelBytes(cr, uri, name)
                    val kind = when {
                        GltfParser.isGlb(bytes) -> "GLB (magia glTF)"
                        GltfParser.looksLikeModelJson(bytes) -> "glTF JSON"
                        else -> "conteúdo desconhecido"
                    }
                    Log.d(TAG_IMPORT, "lido: ${GltfParser.diagnose(bytes, name)} → $kind")
                    lidos.add(Triple(name, bytes, uri))
                }
                val main = lidos.firstOrNull { GltfParser.looksLikeModel(it.second) }
                    ?: throw GltfParser.GltfParseException(
                        "Nenhum dos ficheiros selecionados é um modelo glTF 2.0 (.gltf) ou GLB (.glb) — " +
                            GltfParser.diagnose(lidos.first().second, lidos.first().first)
                    )
                val externals = lidos.filter { it !== main }
                    .associate { it.first to it.second }
                Log.d(
                    TAG_IMPORT,
                    "modelo principal: ${main.first}; apoio: " +
                        (if (externals.isEmpty()) "(nenhum)" else externals.keys.joinToString(", "))
                )
                importModel(main.second, main.first, externals)
            } catch (e: Exception) {
                Log.d(TAG_IMPORT, "importação FALHOU", e)
                withContext(Dispatchers.Main) {
                    statusMessage.value = e.message ?: "Falha ao importar o modelo"
                }
            }
        }
    }

    /** Nome real do ficheiro via OpenableColumns.DISPLAY_NAME (nunca pelo caminho do URI). */
    private fun displayNameOf(cr: android.content.ContentResolver, uri: android.net.Uri): String {
        var displayName = "modelo"
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx)?.let { displayName = it }
                    }
                }
        }
        return displayName
    }

    /** Lê o URI com limite rígido (nunca aloca mais de [GltfLimits.MAX_FILE_BYTES]). */
    private fun readModelBytes(
        cr: android.content.ContentResolver,
        uri: android.net.Uri,
        name: String
    ): ByteArray {
        // Tamanho ANTES de ler (erro rápido para ficheiros gigantes).
        val size = runCatching {
            cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        if (size > GltfLimits.MAX_FILE_BYTES) {
            throw GltfParser.GltfParseException(
                GltfLimits.tooLargeError("ficheiro de modelo", size, GltfLimits.MAX_FILE_BYTES)
            )
        }
        return cr.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > GltfLimits.MAX_FILE_BYTES) {
                    throw GltfParser.GltfParseException(
                        GltfLimits.tooLargeError(
                            "ficheiro de modelo", total, GltfLimits.MAX_FILE_BYTES
                        )
                    )
                }
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        } ?: throw GltfParser.GltfParseException(
            "Não foi possível ler o ficheiro escolhido ($name)"
        )
    }

    private fun decodeImages(model: GltfModel): Map<Int, Bitmap> {
        val out = HashMap<Int, Bitmap>()
        model.materials.forEach { material ->
            val imgIndex = material.textureImageIndex ?: return@forEach
            if (out.containsKey(imgIndex)) return@forEach
            val image = model.images.getOrNull(imgIndex) ?: return@forEach
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@forEach
            if (bounds.outWidth > GltfLimits.MAX_TEXTURE_DIMENSION ||
                bounds.outHeight > GltfLimits.MAX_TEXTURE_DIMENSION
            ) {
                throw GltfParser.GltfParseException(
                    GltfLimits.tooLargeError(
                        "textura embutida ${bounds.outWidth}x${bounds.outHeight}",
                        bounds.outWidth.toLong() * bounds.outHeight,
                        GltfLimits.MAX_TEXTURE_DIMENSION.toLong() * GltfLimits.MAX_TEXTURE_DIMENSION
                    )
                )
            }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            if (bmp != null) out[imgIndex] = bmp
        }
        return out
    }

    private fun applyModel(
        model: GltfModel,
        kind: ModelKind,
        fileName: String?,
        fileBytes: Long?,
        sha256: String?,
        embedded: Map<Int, Bitmap>,
        bindingsOverride: Map<Int, TextureRef>
    ) {
        this.model = model
        this.modelKind = kind
        this.modelFileName = fileName
        this.modelFileBytes = fileBytes
        this.modelSha256 = sha256
        this.embeddedBitmaps = embedded
        this.bindings.value = bindingsOverride
        this.selectedMaterial.value = 0
        this.selectedIsland.value = null
        // MISSÃO: novo modelo → catálogo de texturas novo; seleção volta ao
        // projeto (as embutidas antigas deixaram de existir).
        this.selectedTexture.value = TextureCatalog.DOCUMENT_ID
        lastPickTriangle = -1
        lastPickRenderable = -1
        // OTIMIZAÇÃO (missão texturização): guias UV em cache morrem com o modelo.
        invalidateUvGuides()
        // Modo pode ter de recuar se o novo modelo não tiver UV.
        if (!model.hasUv && (mode.value == StudioMode.UV || mode.value == StudioMode.SPLIT)) {
            this.mode.value = StudioMode.THREE_D
        }
        computeIslands()
        fitCameraToModel()
        invalidateMask()
        renderer?.let { r ->
            r.setScene(model, embedded, bindingsOverride, sampling.value ?: SamplingMode.NEAREST)
            r.setHighlight(null, null)
        }
        postTextureCatalog()
        postViewTexture()
    }

    /** Recalcula as ilhas UV por primitiva (apenas primitivas com UV). */
    private fun computeIslands() {
        val startNanos = System.nanoTime()
        val map = HashMap<Int, List<UvIsland>>()
        model.renderables.forEachIndexed { index, r ->
            val uvs = r.uvs ?: return@forEachIndexed
            map[index] = UvIslands.compute(r.indices, uvs)
        }
        uvIslands = map
        val ms = (System.nanoTime() - startNanos) / 1_000_000.0
        PerfLog.i("PixelOnePerf/3D", "ilhas UV: ${map.values.sumOf { it.size }} em $ms ms")
    }

    /** Enquadra a câmara no modelo (target = centro, distância pelo raio). */
    private fun fitCameraToModel() {
        val center = model.center()
        val radius = model.radius().coerceAtLeast(0.1f)
        camera.reset()
        // Ajusta o alvo e a distância ao tamanho do modelo (demo ou importado).
        camera.restore(
            OrbitCamera.CameraSnapshot(
                yawDeg = 35f, pitchDeg = 25f,
                distance = (radius * 2.9f).coerceIn(camera.minDistance, camera.maxDistance),
                targetX = center[0], targetY = center[1], targetZ = center[2]
            )
        )
    }

    /** Botão "Repor câmara" (requisito do pedido). */
    fun resetCamera() {
        fitCameraToModel()
    }

    // ------------------------------------------------------------------
    // Seleção de face/ilha — toque no 3D (raycast) e na vista UV
    // ------------------------------------------------------------------

    /**
     * TOQUE NO MODELO 3D (requisito Ronda A): converte o ponto NDC num raio da
     * câmara atual, encontra o triângulo mais próximo ([MeshPicking]) e
     * seleciona a FACE tocada + a ILHA UV que a contém. O realce laranja
     * aparece no 3D (ilha da face) e na vista UV (mesma ilha) — nada simulado.
     */
    fun selectFaceAtNdc(ndcX: Float, ndcY: Float, aspect: Float) {
        val ray = MeshPicking.rayFromNdc(camera, ndcX, ndcY, aspect)
        val hit = pickCached(model, ray)
        if (hit == null) {
            // Toque fora do modelo: limpa a seleção (comportamento previsível).
            clearIslandSelection()
            return
        }
        val island = islandContaining(hit.renderableIndex, hit.triangleIndex)
        if (island == null) {
            clearIslandSelection()
            return
        }
        selectedIsland.value = IslandSelection(hit.renderableIndex, island, hit.triangleIndex)
        selectedMaterial.value = model.renderables.getOrNull(hit.renderableIndex)?.materialIndex
        invalidateMask()
        postPaintState()
        applyHighlightToRenderer()
    }

    /**
     * Raycast com cache do último triângulo (Ronda B — pintura por movimento):
     * testa PRIMEIRO o último triângulo tocado (traços concentram-se numa
     * face); se falhar, varre o modelo completo. Corretude idêntica ao
     * [MeshPicking.pick]; apenas evita re-testar tudo quando o dedo fica na
     * mesma face.
     */
    private fun pickCached(m: GltfModel, ray: MeshPicking.Ray): MeshPicking.PickHit? {
        val lr = lastPickRenderable
        val lt = lastPickTriangle
        if (lr >= 0 && lt >= 0) {
            val r = m.renderables.getOrNull(lr)
            if (r != null) {
                val i = lt * 3
                if (i + 2 < r.indices.size) {
                    val pos = r.positions
                    val a = r.indices[i] * 3; val b = r.indices[i + 1] * 3; val c = r.indices[i + 2] * 3
                    val t = MeshPicking.rayTriangleT(
                        ray, pos[a], pos[a + 1], pos[a + 2],
                        pos[b], pos[b + 1], pos[b + 2],
                        pos[c], pos[c + 1], pos[c + 2]
                    )
                    if (t != null) {
                        return MeshPicking.PickHit(
                            renderableIndex = lr, triangleIndex = lt, distance = t,
                            point = floatArrayOf(
                                ray.ox + ray.dx * t, ray.oy + ray.dy * t, ray.oz + ray.dz * t
                            )
                        )
                    }
                }
            }
        }
        val hit = MeshPicking.pick(m, ray)
        if (hit != null) {
            lastPickRenderable = hit.renderableIndex
            lastPickTriangle = hit.triangleIndex
        } else {
            lastPickRenderable = -1
            lastPickTriangle = -1
        }
        return hit
    }

    /**
     * TOQUE/ARRASTO NO MODELO 3D durante a PINTURA (Ronda B): raycast →
     * UV no ponto de impacto (baricêntricas) → funil de pintura. Devolve
     * true se acertou no modelo (para a UI saber se o traço começou).
     */
    fun paintAtNdc(ndcX: Float, ndcY: Float, aspect: Float, isStart: Boolean): Boolean {
        val ray = MeshPicking.rayFromNdc(camera, ndcX, ndcY, aspect)
        val hit = pickCached(model, ray)
        if (hit == null) return false
        val r = model.renderables.getOrNull(hit.renderableIndex)
        val uvs = r?.uvs ?: return false
        val i = hit.triangleIndex * 3
        if (i + 2 >= r.indices.size) return false
        val uv = PaintCore.uvAtHit(
            hit.point, r.positions,
            r.indices[i], r.indices[i + 1], r.indices[i + 2], uvs
        ) ?: return false
        if (isStart) paintBeginUv(uv[0], uv[1]) else paintMoveUv(uv[0], uv[1])
        return true
    }

    /** Operação discreta (balde/conta-gotas) por raio 3D (Ronda B). */
    fun discreteAtNdc(ndcX: Float, ndcY: Float, aspect: Float): Boolean {
        if (paintBlockedByTextureSelection()) return false
        val ray = MeshPicking.rayFromNdc(camera, ndcX, ndcY, aspect)
        val hit = pickCached(model, ray)
        if (hit == null) return false
        val r = model.renderables.getOrNull(hit.renderableIndex)
        val uvs = r?.uvs ?: return false
        val i = hit.triangleIndex * 3
        if (i + 2 >= r.indices.size) return false
        val uv = PaintCore.uvAtHit(
            hit.point, r.positions,
            r.indices[i], r.indices[i + 1], r.indices[i + 2], uvs
        ) ?: return false
        when (paintState.tool) {
            Tool.FILL -> fillAtUv(uv[0], uv[1])
            Tool.EYEDROPPER -> pickColorUv(uv[0], uv[1])
            else -> return false
        }
        return true
    }

    /** Ilha de uma primitiva que contém o triângulo dado (busca linear — só no toque). */
    private fun islandContaining(renderableIndex: Int, triangleIndex: Int): UvIsland? {
        val islands = uvIslands[renderableIndex] ?: return null
        return islands.firstOrNull { island -> island.triangleIndices.any { it == triangleIndex } }
    }

    /**
     * Toque na vista UV: converte (u, v) e procura a ilha tocada; destaca-a
     * NA VISTA UV e NO MODELO 3D (requisito Fase 3).
     */
    fun selectIslandAt(u: Float, v: Float) {
        model.renderables.forEachIndexed { index, r ->
            val uvs = r.uvs ?: return@forEachIndexed
            val islands = uvIslands[index] ?: return@forEachIndexed
            val hit = UvIslands.hitTest(islands, r.indices, uvs, u, v)
            if (hit != null) {
                selectedIsland.value = IslandSelection(index, hit)
                applyHighlightToRenderer()
                selectedMaterial.value = r.materialIndex
                invalidateMask()
                postPaintState()
                return
            }
        }
        // Toque fora de qualquer ilha: limpa a seleção (comportamento previsível).
        clearIslandSelection()
    }

    fun clearIslandSelection() {
        selectedIsland.value = null
        renderer?.setHighlight(null, null)
        invalidateMask()
        postPaintState()
    }

    /**
     * Caixa UV (u0, v0, u1, v1) da ilha selecionada — alvo do botão "Focar"
     * (zoom automático da vista UV na ilha, requisito Ronda A).
     */
    fun selectedIslandUvBounds(): FloatArray? {
        val sel = selectedIsland.value ?: return null
        val island = sel.island
        return floatArrayOf(island.minU, island.minV, island.maxU, island.maxV)
    }

    // ------------------------------------------------------------------
    // Materiais e amostragem
    // ------------------------------------------------------------------

    /** Associa uma textura a um material (chips do painel). */
    fun bindMaterial(materialIndex: Int, ref: TextureRef) {
        if (materialIndex !in model.materials.indices) {
            statusMessage.value = "Material incompatível: índice $materialIndex fora do modelo"
            return
        }
        if (ref == TextureRef.EMBEDDED && model.materials[materialIndex].textureImageIndex == null) {
            statusMessage.value = "Material incompatível: \"${model.materials[materialIndex].name}\" " +
                "não tem textura embutida no modelo"
            return
        }
        val next = (bindings.value ?: emptyMap()).toMutableMap()
        next[materialIndex] = ref
        bindings.value = next
        hasUnsavedChanges.value = true
        // MISSÃO TEXTURIZAÇÃO: setBindings (só ligações, sem rebuild de cena)
        // em vez de setScene — os chips respondem sem hitch de geometria.
        renderer?.setBindings(next)
        // MISSÃO: catálogo (usado-por) e vista seguem a nova binding.
        postTextureCatalog()
        syncTextureSelectionWithMaterial(materialIndex)
    }

    fun selectMaterial(index: Int) {
        if (index !in model.materials.indices) return
        selectedMaterial.value = index
        // Selecionar material mostra as UV da(s) malha(s) que o usam.
        val meshOf = model.renderables.firstOrNull { it.materialIndex == index }?.meshIndex
        selectedMesh.value = meshOf
        // MISSÃO: a vista de textura segue o material (vê-se o que se liga).
        syncTextureSelectionWithMaterial(index)
    }

    /** Seleção de malha (vista UV): filtra as linhas UV mostradas. */
    fun selectMesh(meshIndex: Int?) {
        selectedMesh.value = meshIndex
    }

    // ------------------------------------------------------------------
    // MISSÃO UI/SELEÇÃO — catálogo, seleção de textura, cópia para projeto
    // ------------------------------------------------------------------

    /**
     * Reconstrói o catálogo de texturas (modelo/bindings/nome mudaram) e
     * valida a seleção atual (seleção órfã volta à textura do projeto).
     */
    private fun postTextureCatalog() {
        val sizes = embeddedBitmaps.mapValues { (_, bmp) -> bmp.width to bmp.height }
        val entries = TextureCatalog.build(
            documentName = projectName.value ?: "textura",
            documentWidth = compositeDoc.width,
            documentHeight = compositeDoc.height,
            materials = model.materials.map {
                TextureCatalog.MaterialInfo(it.name, it.textureImageIndex)
            },
            bindings = bindings.value ?: emptyMap(),
            embeddedSizes = sizes
        )
        textureCatalog.postValue(entries)
        val sel = selectedTexture.value ?: TextureCatalog.DOCUMENT_ID
        if (entries.none { it.id == sel }) {
            selectedTexture.postValue(TextureCatalog.DOCUMENT_ID)
            postViewTexture()
        }
    }

    /** Alinha o bitmap das vistas UV/Dividido com a textura SELECIONADA. */
    private fun postViewTexture() {
        val id = selectedTexture.value ?: TextureCatalog.DOCUMENT_ID
        val bmp = TextureCatalog.parseEmbeddedImageIndex(id)?.let { embeddedBitmaps[it] }
            ?: presentationBitmap.value
        viewTexture.postValue(bmp)
    }

    /**
     * A vista segue o material selecionado: material com EMBEDDED mostra a
     * embutida; caso contrário, a textura do projeto. (MISSÃO: a seleção de
     * textura é DISTINTA, mas coerente com a de material.)
     */
    private fun syncTextureSelectionWithMaterial(materialIndex: Int) {
        val ref = bindings.value?.get(materialIndex)
        val imageIndex = model.materials.getOrNull(materialIndex)?.textureImageIndex
        val next = if (ref == TextureRef.EMBEDDED && imageIndex != null) {
            TextureCatalog.embeddedId(imageIndex)
        } else {
            TextureCatalog.DOCUMENT_ID
        }
        if (selectedTexture.value != next) {
            selectedTexture.value = next
            postViewTexture()
        }
    }

    /**
     * Seleciona UMA textura do catálogo (requisito da missão: seleção de
     * textura DISTINTA de material/objeto/face):
     *  - "document": pintura edita a textura do projeto (comportamento atual);
     *  - "embedded:<i>": vista só de leitura na UV/Dividido, material que a
     *    usa fica selecionado; a pintura fica BLOQUEADA COM EXPLICAÇÃO (a
     *    pintura altera APENAS o recurso selecionado — nunca substitui em
     *    silêncio outro material/recurso).
     */
    fun selectTexture(id: String) {
        val entries = textureCatalog.value ?: emptyList()
        val entry = entries.firstOrNull { it.id == id }
        if (entry == null) {
            statusMessage.value = "Textura desconhecida: $id"
            return
        }
        selectedTexture.value = entry.id
        if (entry.kind == TextureEntry.Kind.EMBEDDED) {
            entry.usedByMaterials.firstOrNull()?.let { selectedMaterial.value = it }
            statusMessage.value = "Textura embutida \"${entry.name}\" " +
                "(${entry.width}x${entry.height}) — só leitura. " +
                "Usa \"Copiar para o projeto\" para a editar."
        } else {
            statusMessage.value = "A pintar em: ${entry.name} (${entry.width}x${entry.height})"
        }
        postViewTexture()
    }

    /**
     * GUARDA da pintura (missão: "a pintura deve alterar apenas o recurso
     * selecionado, sem substituir silenciosamente outros materiais"): pintar
     * edita a textura do PROJETO. Com uma embutida selecionada (só leitura),
     * a pintura é recusada COM EXPLICAÇÃO — nunca substitui em silêncio.
     */
    private fun paintBlockedByTextureSelection(): Boolean {
        if (selectedTexture.value == TextureCatalog.DOCUMENT_ID) return false
        statusMessage.value =
            "A pintura edita a textura do projeto. Seleciona \"Textura do projeto\" " +
                "nas Texturas do modelo (ou copia a embutida para o projeto) para pintar."
        return true
    }

    /**
     * COPIA a textura embutida [imageIndex] para o projeto — a ÚNICA forma de
     * a editar (limitação documentada: embutidas são só de leitura).
     *  - MESMAS dimensões: funde na camada ATIVA (UMA entrada de histórico);
     *  - dimensões DIFERENTES: substitui a pilha por UMA camada (anunciado —
     *    mesma regra do adoptFromStudio2D, nunca silencioso).
     * O material selecionado passa a APRESENTAR a textura do projeto (binding
     * explícita e anunciada) para a edição ficar visível no modelo.
     */
    fun copyEmbeddedToDocument(imageIndex: Int) {
        val bmp = embeddedBitmaps[imageIndex] ?: return
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val materialIndex = selectedMaterial.value ?: 0
        mutationExecutor.execute {
            var replaced = false
            if (PixelAdoption.sameDimensions(w, h, compositeDoc.width, compositeDoc.height)) {
                val layer = layerStack.active()
                val result = PixelAdoption.mergeIntoLayer(layer.doc, pixels, w, h)
                if (result != null) {
                    layerHistory.commitDeltas(
                        layerStack.activeIndex, result.indices, result.before, result.after,
                        result.count, result.minX, result.minY, result.maxX, result.maxY
                    )
                    applyEdit(result.minX, result.minY, result.maxX, result.maxY)
                }
                statusMessage.postValue(
                    "Textura embutida copiada para a camada ativa (${w}x${h})"
                )
            } else {
                val stack = LayerStack(w, h)
                stack.seedSingleLayer(pixels.copyOf())
                layerStack = stack
                layerHistory.clear()
                strokeScratch = NativeEngine.Scratch(w * h)
                compositeDoc = PixelDocument(w, h, pixels.copyOf())
                presentationBitmap.postValue(
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                        setPixels(pixels.copyOf(), 0, w, 0, 0, w, h)
                    }
                )
                renderer?.replaceDocumentTexture(compositeDoc.copyPixels(), w, h)
                renderer?.setScene(
                    model, embeddedBitmaps, bindings.value ?: emptyMap(),
                    sampling.value ?: SamplingMode.NEAREST
                )
                replaced = true
                statusMessage.postValue(
                    "Textura embutida substituiu a textura do projeto (${w}x${h}) — 1 camada"
                )
            }
            hasUnsavedChangesLive = true
            postUnsaved()
            postLayersState()
            postPaintState()
            invalidateMask()
            postTextureCatalog()
            viewModelScope.launch(Dispatchers.Main) {
                // Binding EXPLÍCITA do material selecionado → textura do
                // projeto (a edição passa a ver-se no modelo; os chips mostram
                // a mudança — nunca silenciosa).
                bindings.value = (bindings.value ?: emptyMap()).toMutableMap().apply {
                    put(materialIndex, TextureRef.DOCUMENT)
                }
                hasUnsavedChanges.value = true
                selectedTexture.value = TextureCatalog.DOCUMENT_ID
                postTextureCatalog()
                postViewTexture()
                renderer?.setScene(
                    model, embeddedBitmaps, bindings.value ?: emptyMap(),
                    sampling.value ?: SamplingMode.NEAREST
                )
                val materialName = model.materials.getOrNull(materialIndex)?.name
                    ?: materialIndex.toString()
                statusMessage.value =
                    "Material \"$materialName\" passa a apresentar a textura do projeto " +
                        "(edição visível no modelo)"
            }
        }
    }

    /** Amostragem Nearest (padrão) ou Linear (opcional) — requisito Fase 3. */
    fun setSampling(mode: SamplingMode) {
        sampling.value = mode
        hasUnsavedChanges.value = true
        renderer?.setSampling(mode)
    }

    fun setShowUv(show: Boolean) {
        showUvOverlay.value = show
    }

    /**
     * Muda o modo do estúdio. Modelos SEM UV: UV e Dividido são BLOQUEADOS
     * com explicação clara (requisito) — o modo 3D permanece funcional.
     */
    fun setMode(newMode: StudioMode) {
        if ((newMode == StudioMode.UV || newMode == StudioMode.SPLIT) && !model.hasUv) {
            statusMessage.value =
                "Este modelo não tem coordenadas UV: os modos UV e Dividido não se aplicam. " +
                    "Importa um modelo com UVs (TEXCOORD_0) para os usar."
            return
        }
        mode.value = newMode
    }

    // ------------------------------------------------------------------
    // Persistência (.osp v3: composição + camadas + studio3d.json)
    // ------------------------------------------------------------------

    /**
     * Cria uma textura nova (dimensões dadas) — UMA camada "Base" transparente,
     * histórico limpo.
     */
    fun newTexture(name: String, width: Int, height: Int) {
        newTextureInternal(IntArray(width * height), width, height, name)
        statusMessage.value = "Nova textura ${width}x${height}"
    }

    private fun newTextureInternal(pixels: IntArray, width: Int, height: Int, name: String) {
        val stack = LayerStack(width, height)
        stack.seedSingleLayer(pixels)
        layerStack = stack
        layerHistory.clear()
        compositeDoc = PixelDocument(width, height, pixels.copyOf())
        strokeScratch = NativeEngine.Scratch(compositeDoc.pixelCount)
        project = repository.createProject(name)
        projectName.value = name
        hasUnsavedChangesLive = false
        hasUnsavedChanges.value = false
        // Bitmap de apresentação alinhado com a composição.
        presentationBitmap.value = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(compositeDoc.copyPixels(), 0, width, 0, 0, width, height)
        }
        postLayersState()
        postPaintState()
        invalidateMask()
        renderer?.replaceDocumentTexture(compositeDoc.copyPixels(), width, height)
        renderer?.setScene(model, embeddedBitmaps, bindings.value ?: emptyMap(), sampling.value ?: SamplingMode.NEAREST)
        postTextureCatalog()
        postViewTexture()
    }

    private var project: PixelProject? = null

    /** Lista de projetos guardados (diálogo "Abrir projeto"). */
    fun listProjectsSnapshot(): List<PixelProject> =
        try { repository.listProjects() } catch (e: Exception) { emptyList() }

    /**
     * Guarda o projeto .osp (v3): pixels.rgba = COMPOSIÇÃO; layers.json +
     * layer-N.rgba = camadas; studio3d.json = referências 3D (v2).
     */
    fun saveProject() {
        val stack = layerStack
        val proj = project ?: return
        mutationExecutor.execute {
            try {
                val state = buildStudio3DState()
                val json = MiniJson.write(state.toJson())
                val layersJson = LayersCodec.toJson(stack)
                val layerPixels = stack.layers.map { it.doc.toRgbaBytes() }
                val section = OspCodecLayersSection(layersJson, layerPixels)
                // Paleta guardada = cores recentes (hex ARGB) — reuse do manifesto.
                val palette = paintState.recentColors.map { "#%08X".format(it) }
                repository.saveDocument(proj, compositeDoc, palette, json, section)
                hasUnsavedChangesLive = false
                viewModelScope.launch(Dispatchers.Main) {
                    hasUnsavedChanges.value = false
                    statusMessage.value =
                        "Projeto guardado (${compositeDoc.width}x${compositeDoc.height}) " +
                            "com ${stack.count} camada(s) e estado 3D"
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    statusMessage.value = "Falha ao guardar: ${e.message}"
                }
            }
        }
    }

    private fun OspCodecLayersSection(json: String, pixels: List<ByteArray>) =
        com.pixelone.studio.osp.OspCodec.LayersSection(json, pixels)

    /** Abre um projeto .osp existente (v1/v2 sem camadas; v3 com camadas). */
    fun openProject(id: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val loaded = repository.loadProject(id)
                    ?: throw IllegalArgumentException("Projeto não encontrado")
                withContext(Dispatchers.Main) { applyLoadedProject(loaded) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage.value = "Falha ao abrir: ${e.message}"
                }
            }
        }
    }

    private fun applyLoadedProject(loaded: FileSystemProjectRepository.LoadedProjectData) {
        val doc = loaded.document
        val stack: LayerStack = if (loaded.layers != null) {
            try {
                LayersCodec.fromRgbaList(doc.width, doc.height, loaded.layers.layerPixels, loaded.layers.json)
            } catch (e: Exception) {
                // Camadas inválidas: abre com a composição como 1 camada (seguro).
                statusMessage.value = "Camadas ignoradas (${e.message}) — aberto como 1 camada"
                LayerStack(doc.width, doc.height).apply { seedSingleLayer(doc.copyPixels()) }
            }
        } else {
            // Projetos v1/v2: UMA camada (regra da Ronda B).
            LayerStack(doc.width, doc.height).apply { seedSingleLayer(doc.copyPixels()) }
        }
        layerStack = stack
        layerHistory.clear()
        strokeScratch = NativeEngine.Scratch(doc.pixelCount)
        project = loaded.project
        projectName.value = loaded.project.name
        val composed = stack.compose()
        compositeDoc = PixelDocument(doc.width, doc.height, composed)
        presentationBitmap.value = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(composed, 0, doc.width, 0, 0, doc.width, doc.height)
        }
        // Paleta guardada (cores recentes) volta do manifesto.
        loaded.palette?.takeIf { it.isNotEmpty() }?.let { hexes ->
            val colors = hexes.mapNotNull { hex ->
                hex.removePrefix("#").toLongOrNull(16)?.toInt()
            }.take(MAX_RECENTS)
            if (colors.isNotEmpty()) {
                paintState = paintState.copy(recentColors = colors)
                paint.value = paintState
            }
        }
        // Estado 3D (opcional — v1 abre sem estado).
        var appliedMode: StudioMode? = null
        loaded.studio3dJson?.let { json ->
            try {
                val parsed = MiniJson.parse(json)
                if (parsed is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val state = Studio3DState.fromJson(parsed as Map<String, Any?>)
                    camera.restore(state.camera ?: return@let)
                    sampling.value = state.sampling
                    showUvOverlay.value = state.showUvOverlay
                    appliedMode = state.mode
                    renderer?.setSampling(state.sampling)
                    if (state.modelKind == ModelKind.IMPORTED) {
                        // Modelo NÃO é reembutido no .osp (referência apenas):
                        // pedir reimportação, manter demo ativo.
                        statusMessage.value =
                            "Projeto guarda referência ao modelo \"${state.modelFileName}\". " +
                                "Reimporta o .glb para o ver novamente (as definições 3D foram mantidas)."
                    }
                }
            } catch (e: Exception) {
                statusMessage.value = "Secção 3D ignorada (${e.message})"
            }
        }
        hasUnsavedChangesLive = false
        hasUnsavedChanges.value = false
        postLayersState()
        postPaintState()
        invalidateMask()
        renderer?.replaceDocumentTexture(compositeDoc.copyPixels(), doc.width, doc.height)
        renderer?.setScene(model, embeddedBitmaps, bindings.value ?: emptyMap(), sampling.value ?: SamplingMode.NEAREST)
        postTextureCatalog()
        postViewTexture()
        // Modo guardado só se o modelo atual permitir.
        appliedMode?.let { if (model.hasUv) mode.value = it }
    }

    private fun buildStudio3DState(): Studio3DState {
        return Studio3DState(
            modelKind = modelKind,
            modelFileName = modelFileName,
            modelFileBytes = modelFileBytes,
            modelSha256 = modelSha256,
            modelTriangleCount = model.triangleCount,
            modelMeshCount = model.meshNames.size,
            modelMaterialCount = model.materials.size,
            modelHasUv = model.hasUv,
            bindings = bindings.value ?: emptyMap(),
            sampling = sampling.value ?: SamplingMode.NEAREST,
            showUvOverlay = showUvOverlay.value ?: true,
            mode = mode.value ?: StudioMode.THREE_D,
            camera = camera.snapshot()
        )
    }

    // ------------------------------------------------------------------
    // Partilha com o Texture Studio ("Pintar esta textura")
    // ------------------------------------------------------------------

    /** Publica a COMPOSIÇÃO atual no bus para o Texture Studio abrir. */
    fun publishTextureToStudio2D() {
        val doc = compositeDoc
        StudioSharedBus.publishTo2D(
            StudioSharedBus.TextureHandoff(
                name = project?.name ?: "textura-3d",
                width = doc.width,
                height = doc.height,
                pixels = doc.copyPixels(),
                uvSegments = uvWireframeSegments(),
                version = System.nanoTime()
            )
        )
    }

    /**
     * Adota a textura mais recente do Texture Studio (se existir).
     * RONDA B: se as dimensões coincidirem, os píxeis entram NA CAMADA ATIVA
     * (UMA entrada de histórico — as outras camadas e o histórico sobrevivem);
     * se as dimensões diferirem, a pilha é substituída por UMA camada.
     */
    fun adoptFromStudio2D(): Boolean {
        val handoff = StudioSharedBus.consumeFrom2D() ?: return false
        if (handoff.width <= 0 || handoff.height <= 0) return false
        if (handoff.width == compositeDoc.width && handoff.height == compositeDoc.height) {
            // MISSÃO: fusão extraída para [PixelAdoption] (pura, partilhada com
            // copyEmbeddedToDocument e testável em JVM) — comportamento igual.
            val layer = layerStack.active()
            val result = PixelAdoption.mergeIntoLayer(
                layer.doc, handoff.pixels, handoff.width, handoff.height
            )
            if (result != null) {
                layerHistory.commitDeltas(
                    layerStack.activeIndex, result.indices, result.before, result.after,
                    result.count, result.minX, result.minY, result.maxX, result.maxY
                )
                applyEdit(result.minX, result.minY, result.maxX, result.maxY)
            }
            statusMessage.value = "Textura do Texture Studio aplicada à camada ativa " +
                "(${handoff.width}x${handoff.height})"
        } else {
            // Dimensões diferentes: substitui a pilha (documentado no relatório).
            val stack = LayerStack(handoff.width, handoff.height)
            stack.seedSingleLayer(handoff.pixels.copyOf())
            layerStack = stack
            layerHistory.clear()
            strokeScratch = NativeEngine.Scratch(handoff.width * handoff.height)
            compositeDoc = PixelDocument(handoff.width, handoff.height, handoff.pixels.copyOf())
            presentationBitmap.value = Bitmap.createBitmap(
                handoff.width, handoff.height, Bitmap.Config.ARGB_8888
            ).apply {
                setPixels(handoff.pixels, 0, handoff.width, 0, 0, handoff.width, handoff.height)
            }
            statusMessage.value = "Textura substituída pelo Texture Studio " +
                "(${handoff.width}x${handoff.height}) — 1 camada"
            renderer?.replaceDocumentTexture(compositeDoc.copyPixels(), handoff.width, handoff.height)
        }
        hasUnsavedChangesLive = true
        hasUnsavedChanges.value = true
        postLayersState()
        postPaintState()
        invalidateMask()
        postTextureCatalog()
        postViewTexture()
        return true
    }

    // ------------------------------------------------------------------
    // Guias UV e realce de ilha (consumidos pelas vistas)
    // ------------------------------------------------------------------

    // OTIMIZAÇÃO (missão texturização): os segmentos UV e o realce da ilha
    // eram RECONSTRUÍDOS a cada recomposição dos painéis UV/Dividido — e o
    // painel recompose a cada evento de movimento do dedo (paintVersion).
    // Para modelos com muitos triângulos isso significava centenas de KB de
    // FloatArray + boxing em ArrayList na THREAD PRINCIPAL por evento — a
    // causa dos bloqueios durante o traço. Agora: cache simples, invalidado
    // quando o modelo muda (applyModel) e recalculado quando a seleção muda.
    private var uvSegmentsCache: FloatArray? = null
    private var uvSegmentsCacheFilter: Int? = null
    private var uvSegmentsCacheValid = false

    private fun invalidateUvGuides() {
        uvSegmentsCacheValid = false
        uvSegmentsCache = null
        uvSegmentsCacheFilter = null
        highlightCacheSelection = null
        highlightCache = null
    }

    /** Segmentos UV (u0,v0,u1,v1) das ilhas — guia para a pintura/overlay.
     *  @param meshFilter índice de malha para filtrar (null = todas). */
    fun uvWireframeSegments(meshFilter: Int? = null): FloatArray {
        if (uvSegmentsCacheValid && uvSegmentsCacheFilter == meshFilter) {
            return uvSegmentsCache ?: EMPTY_UV_SEGMENTS
        }
        // MISSÃO TEXTURIZAÇÃO: construção SEM boxing (UvGuides prealoca o
        // FloatArray exato — antes eram milhões de Float encaixotado na
        // thread principal para modelos grandes).
        val arr = UvGuides.segments(model.renderables, meshFilter)
        uvSegmentsCache = arr
        uvSegmentsCacheFilter = meshFilter
        uvSegmentsCacheValid = true
        return arr
    }

    private val EMPTY_UV_SEGMENTS = FloatArray(0)

    /** Cache do realce por INSTÂNCIA da seleção (nova seleção → recalcula). */
    private var highlightCacheSelection: Any? = null
    private var highlightCache: FloatArray? = null

    /**
     * Triângulos da ilha selecionada em TRIPLOS de vértice (ax,ay,bx,by,cx,cy)
     * — 6 floats por triângulo. Formato consumido por
     * [com.pixelone.studio.ui.texture3d.TexturePaintView] para o path fechado
     * de realce (fill + stroke).
     */
    fun islandHighlightSegments(): FloatArray? {
        val sel = selectedIsland.value
        if (sel === highlightCacheSelection) return highlightCache
        if (sel == null) {
            highlightCacheSelection = null
            highlightCache = null
            return null
        }
        val r = model.renderables.getOrNull(sel.renderableIndex)
        val uvs = r?.uvs
        if (r == null || uvs == null) {
            highlightCacheSelection = sel
            highlightCache = null
            return null
        }
        val out = ArrayList<Float>(sel.island.triangleIndices.size * 6)
        for (t in sel.island.triangleIndices) {
            val a = r.indices[t * 3]; val b = r.indices[t * 3 + 1]; val c = r.indices[t * 3 + 2]
            out.add(uvs[a * 2]); out.add(uvs[a * 2 + 1])
            out.add(uvs[b * 2]); out.add(uvs[b * 2 + 1])
            out.add(uvs[c * 2]); out.add(uvs[c * 2 + 1])
        }
        val arr = FloatArray(out.size) { out[it] }
        highlightCacheSelection = sel
        highlightCache = arr
        return arr
    }

    /** Píxeis da composição (exportações PNG — textura e gabarito UV). */
    fun compositePixels(): IntArray? = compositeDoc.copyPixels()

    /**
     * EXPORTAR TEXTURA (Ronda B): grava a COMPOSIÇÃO das camadas visíveis
     * como PNG no destino SAF escolhido (sem permissões, sem MediaStore).
     */
    fun exportTextureTo(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val doc = compositeDoc
                val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                bmp.setPixels(doc.copyPixels(), 0, doc.width, 0, 0, doc.width, doc.height)
                val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Não foi possível abrir o destino")
                val ok = out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                if (!ok) throw java.io.IOException("Falha ao codificar o PNG")
                withContext(Dispatchers.Main) {
                    statusMessage.value = "Textura exportada (${doc.width}x${doc.height}, PNG)"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage.value = "Falha ao exportar textura: ${e.message}"
                }
            }
        }
    }

    /**
     * EXPORTAR GABARITO UV (Ronda B): PNG com as linhas UV (1 px, finas) sobre
     * fundo TRANSPARENTE — para uso como guia de pintura externa.
     */
    fun exportUvTemplateTo(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val doc = compositeDoc
                val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                // Fundo transparente (allocateDirect-like: createBitmap já vem a 0).
                val canvas = android.graphics.Canvas(bmp)
                val linePaint = android.graphics.Paint().apply {
                    color = 0xFFFFFFFF.toInt()
                    strokeWidth = 1f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = false
                }
                val segments = uvWireframeSegments(null)
                canvas.scale(doc.width.toFloat(), doc.height.toFloat())
                linePaint.strokeWidth = 1f / doc.width
                var i = 0
                while (i + 3 < segments.size) {
                    canvas.drawLine(
                        segments[i], segments[i + 1], segments[i + 2], segments[i + 3], linePaint
                    )
                    i += 4
                }
                val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Não foi possível abrir o destino")
                val ok = out.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                if (!ok) throw java.io.IOException("Falha ao codificar o PNG")
                withContext(Dispatchers.Main) {
                    statusMessage.value = "Gabarito UV exportado (${doc.width}x${doc.height}, PNG)"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage.value = "Falha ao exportar gabarito: ${e.message}"
                }
            }
        }
    }

    private fun sha256Of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Mensagem pendente consumida pela UI (Snackbar). */
    fun consumeStatus(): String? = statusMessage.value

    companion object {
        private const val TAG = "PixelOnePerf/3D"

        /** Tamanhos de pincel da Ronda B (requisito: 1, 2, 3, 4 e 8 px). */
        val BRUSH_SIZES: List<Int> = listOf(1, 2, 3, 4, 8)
        const val PixelBrushMax: Int = 8

        /** Laranja Pixel One por defeito da pintura. */
        const val DEFAULT_COLOR: Int = 0xFFF57C00.toInt()

        /** Máximo de cores recentes (persistem na paleta do manifesto). */
        const val MAX_RECENTS: Int = 12

        /** Padding/dilatação por defeito à volta das ilhas (regra da Ronda B). */
        const val DEFAULT_PADDING: Int = 2

        /** Cores do xadrez de teste (iguais às da textura demo). */
        const val TEST_CHECKER_A: Int = 0xFF2E2E2E.toInt()
        const val TEST_CHECKER_B: Int = 0xFF1A1A1A.toInt()

        val DEFAULT_RECENTS: List<Int> = listOf(
            0xFFF57C00.toInt(), 0xFFE6E6E6.toInt(), 0xFF2E2E2E.toInt(), 0xFF000000.toInt()
        )
    }
}
