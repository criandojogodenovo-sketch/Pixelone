package com.pixelone.studio.studio3d

import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.pixelone.studio.perf.PerfLog
import com.pixelone.studio.pixel.HistoryManager
import com.pixelone.studio.pixel.NativeEngine
import com.pixelone.studio.pixel.PixelDocument
import com.pixelone.studio.pixel.PixelEngine
import com.pixelone.studio.project.PixelProject
import com.pixelone.studio.osp.FileSystemProjectRepository
import com.pixelone.studio.osp.MiniJson
import com.pixelone.studio.studio3d.GltfParser.GltfModel
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

/**
 * ViewModel do 3D Texture Studio (Fase 3).
 *
 * REGRAS DE ARQUITETURA (herdadas do projeto):
 *  - A textura do 3D Studio É um [PixelDocument] (buffer direto partilhado
 *    Kotlin↔C++) — a mesma base do editor 2D, com o MESMO motor de pintura
 *    ([PixelEngine]) e histórico esparso ([HistoryManager]). Nada duplicado.
 *  - TODAS as mutações de píxeis correm num executor serial único
 *    ("pixelone-3d-mutation") — sem leituras rasgadas, igual ao editor 2D.
 *  - A GPU recebe APENAS a região suja ([ModelRenderer.updateTextureRegion])
 *    enquanto se pinta (modo Dividido) — nunca a textura inteira por traço.
 *  - Modelos glTF são analisados fora da main thread; a GPU é atualizada
 *    através de [ModelRenderer] (fila de comandos, thread GL).
 *
 * PERSISTÊNCIA: o projeto continua a ser um .osp (pixels.rgba = a textura);
 * a secção studio3d.json guarda REFERÊNCIAS (modelo, bindings, câmara,
 * amostragem) com versionamento v2 — projetos antigos (v1) abrem sem 3D.
 */
class Studio3DViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = FileSystemProjectRepository(File(app.filesDir, "projects"))

    // ------------------------------------------------------------------
    // Textura (PixelDocument) e histórico
    // ------------------------------------------------------------------

    var document: PixelDocument? = null
        private set

    private var project: PixelProject? = null
    private var history = HistoryManager()
    private var strokeScratch: NativeEngine.Scratch? = null
    private val mutationExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pixelone-3d-mutation").apply { isDaemon = true }
    }

    /** Bitmap de apresentação para a superfície de pintura (2D). */
    val presentationBitmap = MutableLiveData<Bitmap?>(null)

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
    val selectedMaterial = MutableLiveData(0)

    /** Malha selecionada na vista UV (null = todas). */
    val selectedMesh = MutableLiveData<Int?>(null)

    /** Ilha selecionada (vista UV): primitiva + ilha. */
    data class IslandSelection(val renderableIndex: Int, val island: UvIsland)

    val selectedIsland = MutableLiveData<IslandSelection?>(null)

    /** Ilhas por primitiva (recalculadas quando o modelo muda). */
    var uvIslands: Map<Int, List<UvIsland>> = emptyMap()
        private set

    val camera = OrbitCamera()

    /** Renderer anexado pela UI (null até o GLSurfaceView existir). */
    private var renderer: ModelRenderer? = null

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    init {
        // Textura demo imediata: estúdio abre com modelo E textura visíveis.
        newTextureInternal(DemoGeometry.createDefaultTexture(), 64, 64, "textura-demo")
        computeIslands()
        fitCameraToModel()
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
            val doc = document ?: return
            r.replaceDocumentTexture(doc.copyPixels(), doc.width, doc.height)
            r.setScene(model, embeddedBitmaps, bindings.value ?: emptyMap(), sampling.value ?: SamplingMode.NEAREST)
            applyHighlightToRenderer()
        }
    }

    /** Reenvia o realce da ilha selecionada (após attach ou mudanças). */
    private fun applyHighlightToRenderer() {
        val sel = selectedIsland.value
        renderer?.setHighlight(sel?.renderableIndex, sel?.island?.triangleIndices)
    }

    // ------------------------------------------------------------------
    // Pintura (modo Dividido e vista UV) — MESMO motor do editor 2D
    // ------------------------------------------------------------------

    /** Início do traço (down do dedo). Um gesto = UMA entrada de histórico. */
    fun paintBegin(x: Int, y: Int) {
        val doc = document ?: return
        if (pendingFill) {
            // Balde armado: este toque preenche (operação discreta).
            pendingFill = false
            fillAt(x, y)
            return
        }
        val color = if (eraseMode) 0x00000000 else paintColor
        paintColor = color
        val brush = paintBrushSize
        if (!doc.inBounds(x, y)) return
        history.beginStroke()
        mutationExecutor.execute {
            val d = document ?: return@execute
            if (!d.inBounds(x, y)) return@execute
            PixelEngine.drawPixel(d, x, y, color, brush, history)
            markDirty(x, y, x, y, brush)
        }
    }

    /** Continuação do traço (move do dedo): segmento Bresenham ao vivo. */
    fun paintMove(x: Int, y: Int) {
        val doc = document ?: return
        if (!doc.inBounds(x, y)) return
        val color = paintColor
        val brush = paintBrushSize
        mutationExecutor.execute {
            val d = document ?: return@execute
            val lx = lastPaintX
            val ly = lastPaintY
            if (lx >= 0) {
                if (PixelEngine.drawLine(d, lx, ly, x, y, color, brush, history)) {
                    markDirty(lx, ly, x, y, brush)
                }
            } else if (PixelEngine.drawPixel(d, x, y, color, brush, history)) {
                markDirty(x, y, x, y, brush)
            }
            lastPaintX = x
            lastPaintY = y
        }
    }

    /** Fim do traço (up do dedo): fecha a entrada única do histórico. */
    fun paintEnd() {
        val doc = document ?: return
        mutationExecutor.execute {
            history.commitStroke(doc)
            hasUnsavedChangesLive = true
            lastPaintX = -1
            lastPaintY = -1
        }
    }

    /** Balde de preenchimento (toque único) — lote nativo, UMA entrada. */
    fun fillAt(x: Int, y: Int) {
        val doc = document ?: return
        if (!doc.inBounds(x, y)) return
        if (doc.get(x, y) == paintColor) return
        val color = paintColor
        val tolerance = fillTolerance
        val scratch = strokeScratch
        mutationExecutor.execute {
            val d = document ?: return@execute
            val s = scratch ?: NativeEngine.Scratch(d.pixelCount).also { strokeScratch = it }
            val result = NativeEngine.floodFill(d, x, y, color, tolerance, s)
            if (result != null && result.changedSomething) {
                history.commitDeltas(
                    s.indices, s.before, s.after, result.deltaCount,
                    result.dirtyX0, result.dirtyY0, result.dirtyX1, result.dirtyY1
                )
                markDirty(result.dirtyX0, result.dirtyY0, result.dirtyX1, result.dirtyY1, 1)
                hasUnsavedChangesLive = true
            }
        }
    }

    /** Conta-gotas (toque único): lê a cor do píxel. */
    fun pickColorAt(x: Int, y: Int): Int? {
        val doc = document ?: return null
        if (!doc.inBounds(x, y)) return null
        return doc.get(x, y)
    }

    fun undo() {
        val doc = document ?: return
        mutationExecutor.execute {
            val edit = history.undo(doc) ?: return@execute
            markDirty(edit.minX, edit.minY, edit.maxX, edit.maxY, 1)
            hasUnsavedChangesLive = true
        }
    }

    fun redo() {
        val doc = document ?: return
        mutationExecutor.execute {
            val edit = history.redo(doc) ?: return@execute
            markDirty(edit.minX, edit.minY, edit.maxX, edit.maxY, 1)
            hasUnsavedChangesLive = true
        }
    }

    fun canUndo(): Boolean = history.canUndo()
    fun canRedo(): Boolean = history.canRedo()

    // Estado da ferramenta de pintura (definido pela UI).
    var paintColor: Int = 0xFFF57C00.toInt()
    var paintBrushSize: Int = 1
    var eraseMode: Boolean = false
    var fillTolerance: Int = 0

    /** Balde armado: o próximo toque preenche (painel do modo Dividido). */
    var pendingFill: Boolean = false

    private var lastPaintX = -1
    private var lastPaintY = -1

    /** Escrita hasUnsavedChanges segura do executor → main. */
    @Volatile
    private var hasUnsavedChangesLive: Boolean = false

    /**
     * Região suja: atualiza o bitmap de apresentação (regional, nunca inteiro)
     * e enfileira a ATUALIZAÇÃO PARCIAL da GPU (glTexSubImage2D).
     * Corre no executor de mutação — o documento já está estável.
     */
    private fun markDirty(x0: Int, y0: Int, x1: Int, y1: Int, brush: Int) {
        val doc = document ?: return
        val rect = DirtyRectAccumulator()
            .apply { addStroke(x0, y0, x1, y1, brush) }
            .take(doc.width, doc.height) ?: return
        // Bitmap de apresentação: só a região (mesma técnica do editor 2D).
        val bmp = presentationBitmap.value
        if (bmp != null && bmp.width == doc.width && bmp.height == doc.height) {
            val region = IntArray(rect.width * rect.height)
            var i = 0
            for (y in rect.y0..rect.y1) {
                for (x in rect.x0..rect.x1) {
                    region[i++] = doc.get(x, y)
                }
            }
            bmp.setPixels(region, 0, rect.width, rect.x0, rect.y0, rect.width, rect.height)
        }
        // GPU: APENAS a região suja (requisito do modo Dividido).
        renderer?.updateTextureRegion(rect, { doc.getAt(it) }, doc.width)
        if (hasUnsavedChangesLive) postUnsaved()
    }

    private fun postUnsaved() {
        viewModelScope.launch(Dispatchers.Main) {
            hasUnsavedChanges.value = true
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
     * @throws nada — todos os erros viram mensagens em [statusMessage].
     */
    fun importModel(bytes: ByteArray, fileName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val startNanos = System.nanoTime()
                if (bytes.size > GltfLimits.MAX_FILE_BYTES) {
                    throw GltfParser.GltfParseException(
                        GltfLimits.tooLargeError(
                            "ficheiro de modelo", bytes.size.toLong(), GltfLimits.MAX_FILE_BYTES
                        )
                    )
                }
                val parsed = GltfParser.parse(bytes)
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
                withContext(Dispatchers.Main) {
                    statusMessage.value = e.message ?: "Falha ao importar o modelo"
                }
            }
        }
    }

    /**
     * Importação direta de um URI SAF: lê com limite rígido (nunca aloca mais
     * de [GltfLimits.MAX_FILE_BYTES]) e encaminha para [importModel].
     */
    fun importModelFromUri(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                // Nome de apresentação via cursor (sem dependência do caminho).
                var displayName = "modelo.glb"
                runCatching {
                    cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0) cursor.getString(idx)?.let { displayName = it }
                            }
                        }
                }
                // Tamanho ANTES de ler (erro rápido para ficheiros gigantes).
                val size = runCatching {
                    cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                }.getOrDefault(-1L)
                if (size > GltfLimits.MAX_FILE_BYTES) {
                    withContext(Dispatchers.Main) {
                        statusMessage.value = GltfLimits.tooLargeError(
                            "ficheiro de modelo", size, GltfLimits.MAX_FILE_BYTES
                        )
                    }
                    return@launch
                }
                val bytes = cr.openInputStream(uri)?.use { input ->
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
                } ?: throw GltfParser.GltfParseException("Não foi possível ler o ficheiro escolhido")
                importModel(bytes, displayName)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage.value = e.message ?: "Falha ao importar o modelo"
                }
            }
        }
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
        // Modo pode ter de recuar se o novo modelo não tiver UV.
        if (!model.hasUv && (mode.value == StudioMode.UV || mode.value == StudioMode.SPLIT)) {
            this.mode.value = StudioMode.THREE_D
        }
        computeIslands()
        fitCameraToModel()
        renderer?.let { r ->
            r.setScene(model, embedded, bindingsOverride, sampling.value ?: SamplingMode.NEAREST)
            r.setHighlight(null, null)
        }
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
    // Seleção de ilha UV (vista UV)
    // ------------------------------------------------------------------

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
                renderer?.setHighlight(index, hit.triangleIndices)
                selectedMaterial.value = r.materialIndex
                return
            }
        }
        // Toque fora de qualquer ilha: limpa a seleção (comportamento previsível).
        selectedIsland.value = null
        renderer?.setHighlight(null, null)
    }

    fun clearIslandSelection() {
        selectedIsland.value = null
        renderer?.setHighlight(null, null)
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
        renderer?.setScene(model, embeddedBitmaps, next, sampling.value ?: SamplingMode.NEAREST)
    }

    fun selectMaterial(index: Int) {
        if (index !in model.materials.indices) return
        selectedMaterial.value = index
        // Selecionar material mostra as UV da(s) malha(s) que o usam.
        val meshOf = model.renderables.firstOrNull { it.materialIndex == index }?.meshIndex
        selectedMesh.value = meshOf
    }

    /** Seleção de malha (vista UV): filtra as linhas UV mostradas. */
    fun selectMesh(meshIndex: Int?) {
        selectedMesh.value = meshIndex
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
    // Persistência (.osp v2 com studio3d.json)
    // ------------------------------------------------------------------

    /** Cria uma textura nova (dimensões dadas) — limpa o histórico. */
    fun newTexture(name: String, width: Int, height: Int) {
        newTextureInternal(IntArray(width * height), width, height, name)
        statusMessage.value = "Nova textura ${width}x${height}"
    }

    private fun newTextureInternal(pixels: IntArray, width: Int, height: Int, name: String) {
        val doc = PixelDocument(width, height, pixels)
        document = doc
        strokeScratch = NativeEngine.Scratch(doc.pixelCount)
        history = HistoryManager()
        project = repository.createProject(name)
        projectName.value = name
        hasUnsavedChangesLive = false
        hasUnsavedChanges.value = false
        // Bitmap de apresentação alinhado com o documento.
        presentationBitmap.value = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
        renderer?.replaceDocumentTexture(doc.copyPixels(), width, height)
        renderer?.setScene(model, embeddedBitmaps, bindings.value ?: emptyMap(), sampling.value ?: SamplingMode.NEAREST)
    }

    /** Lista de projetos guardados (diálogo "Abrir projeto"). */
    fun listProjectsSnapshot(): List<PixelProject> =
        try { repository.listProjects() } catch (e: Exception) { emptyList() }

    /** Guarda o projeto .osp (v2) com a secção 3D (referências + câmara). */
    fun saveProject() {
        val doc = document ?: return
        val proj = project ?: return
        mutationExecutor.execute {
            try {
                val state = buildStudio3DState()
                val json = MiniJson.write(state.toJson())
                repository.saveDocument(proj, doc, null, json)
                hasUnsavedChangesLive = false
                viewModelScope.launch(Dispatchers.Main) {
                    hasUnsavedChanges.value = false
                    statusMessage.value = "Projeto guardado (${doc.width}x${doc.height}) com estado 3D"
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    statusMessage.value = "Falha ao guardar: ${e.message}"
                }
            }
        }
    }

    /** Abre um projeto .osp existente (v1 sem 3D ou v2 com 3D). */
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
        document = doc
        strokeScratch = NativeEngine.Scratch(doc.pixelCount)
        history = HistoryManager()
        project = loaded.project
        projectName.value = loaded.project.name
        presentationBitmap.value = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        doc.copyPixels().let { pixels ->
            presentationBitmap.value?.setPixels(pixels, 0, doc.width, 0, 0, doc.width, doc.height)
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
        renderer?.replaceDocumentTexture(doc.copyPixels(), doc.width, doc.height)
        renderer?.setScene(model, embeddedBitmaps, bindings.value ?: emptyMap(), sampling.value ?: SamplingMode.NEAREST)
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

    /** Publica a textura atual no bus para o Texture Studio abrir. */
    fun publishTextureToStudio2D() {
        val doc = document ?: return
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
     * Chamada ao voltar ao estúdio 3D — o 3D atualiza SEM reiniciar projeto.
     */
    fun adoptFromStudio2D(): Boolean {
        val handoff = StudioSharedBus.consumeFrom2D() ?: return false
        if (handoff.width <= 0 || handoff.height <= 0) return false
        val doc = PixelDocument(handoff.width, handoff.height, handoff.pixels)
        document = doc
        strokeScratch = NativeEngine.Scratch(doc.pixelCount)
        history = HistoryManager()
        presentationBitmap.value = Bitmap.createBitmap(handoff.width, handoff.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(handoff.pixels, 0, handoff.width, 0, 0, handoff.width, handoff.height)
        }
        hasUnsavedChangesLive = true
        hasUnsavedChanges.value = true
        statusMessage.value = "Textura atualizada pelo Texture Studio (${handoff.width}x${handoff.height})"
        renderer?.replaceDocumentTexture(doc.copyPixels(), doc.width, doc.height)
        return true
    }

    /** Segmentos UV (u0,v0,u1,v1) das ilhas — guia para a pintura/overlay.
     *  @param meshFilter índice de malha para filtrar (null = todas). */
    fun uvWireframeSegments(meshFilter: Int? = null): FloatArray {
        val segments = ArrayList<Float>(4096)
        model.renderables.forEach { r ->
            if (meshFilter != null && r.meshIndex != meshFilter) return@forEach
            val uvs = r.uvs ?: return@forEach
            var i = 0
            while (i < r.indices.size) {
                val a = r.indices[i]; val b = r.indices[i + 1]; val c = r.indices[i + 2]
                segments.add(uvs[a * 2]); segments.add(uvs[a * 2 + 1])
                segments.add(uvs[b * 2]); segments.add(uvs[b * 2 + 1])
                segments.add(uvs[b * 2]); segments.add(uvs[b * 2 + 1])
                segments.add(uvs[c * 2]); segments.add(uvs[c * 2 + 1])
                segments.add(uvs[c * 2]); segments.add(uvs[c * 2 + 1])
                segments.add(uvs[a * 2]); segments.add(uvs[a * 2 + 1])
                i += 3
            }
        }
        val arr = FloatArray(segments.size)
        for (i in arr.indices) arr[i] = segments[i]
        return arr
    }

    /** Segmentos UV da ilha selecionada (realce laranja na vista UV). */
    fun islandHighlightSegments(): FloatArray? {
        val sel = selectedIsland.value ?: return null
        val r = model.renderables.getOrNull(sel.renderableIndex) ?: return null
        val uvs = r.uvs ?: return null
        val out = ArrayList<Float>(sel.island.triangleIndices.size * 6)
        for (t in sel.island.triangleIndices) {
            val a = r.indices[t * 3]; val b = r.indices[t * 3 + 1]; val c = r.indices[t * 3 + 2]
            out.add(uvs[a * 2]); out.add(uvs[a * 2 + 1])
            out.add(uvs[b * 2]); out.add(uvs[b * 2 + 1])
            out.add(uvs[b * 2]); out.add(uvs[b * 2 + 1])
            out.add(uvs[c * 2]); out.add(uvs[c * 2 + 1])
            out.add(uvs[c * 2]); out.add(uvs[c * 2 + 1])
            out.add(uvs[a * 2]); out.add(uvs[a * 2 + 1])
        }
        return FloatArray(out.size) { out[it] }
    }

    private fun sha256Of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Mensagem pendente consumida pela UI (Snackbar). */
    fun consumeStatus(): String? = statusMessage.value

    companion object {
        private const val TAG = "PixelOnePerf/3D"
    }
}
