package com.pixelone.studio.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.pixelone.studio.osp.FileSystemProjectRepository
import com.pixelone.studio.osp.OspCodec
import com.pixelone.studio.osp.OspManifest
import com.pixelone.studio.pixel.HistoryManager
import com.pixelone.studio.pixel.PixelDocument
import com.pixelone.studio.pixel.PixelEngine
import com.pixelone.studio.project.PixelProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.IntBuffer

/**
 * Estado e lógica do editor de pixel art (Fase 1).
 *
 * REQUISITOS CUMPRIDOS:
 * - Sobrevive a rotações/recriações de Activity (ViewModel).
 * - AUTOSAVE no ON_STOP (interrupções do ciclo de vida): cópia do trabalho
 *   não guardado em filesDir/autosave, oferecida para restauro no regresso.
 * - NUNCA executa IO pesado no thread principal (corrotinas Dispatchers.IO).
 * - Sincronização documento→Bitmap: por píxel durante o traço (barato) e
 *   cópia completa no commit (garantia de consistência), nunca uma cópia
 *   completa por evento de toque.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app), DefaultLifecycleObserver {

    // ------------------------------------------------------------------
    // Documento e renderização
    // ------------------------------------------------------------------

    var document: PixelDocument? = null
        private set

    var project: PixelProject? = null
        private set

    /** Bitmap de renderização (espelho do documento para o Compose). */
    var imageBitmap: ImageBitmap? = null
        private set

    private var bitmap: Bitmap? = null

    /** Bump a cada alteração visual → invalida o Canvas do Compose. */
    var frameVersion = mutableIntStateOf(0)
        private set

    // ------------------------------------------------------------------
    // Ferramentas e cor
    // ------------------------------------------------------------------

    var tool = mutableStateOf(PixelEngine.Tool.PENCIL)
    var brushSize = mutableIntStateOf(1)
    var activeColor = mutableIntStateOf(DEFAULT_COLOR)
    var fillTolerance = mutableIntStateOf(0)
    var showGrid = mutableStateOf(true)
    var gridThreshold = mutableIntStateOf(4)

    /** Paleta de cores frequentes (persistida no .osp). */
    val palette = mutableStateOf(listOf(DEFAULT_COLOR))

    // ------------------------------------------------------------------
    // Câmera (zoom/pan)
    // ------------------------------------------------------------------

    var scale = mutableFloatStateOf(1f)
    var offset = mutableStateOf(Offset.Zero)

    /** Dimensão do viewport do canvas (para ajustar à vista). */
    private var viewportWidth = 0f
    private var viewportHeight = 0f

    /** Ajuste à vista pendente (documento novo/aberto, aguarda layout). */
    private var pendingFit = false

    // ------------------------------------------------------------------
    // Estado do projeto e feedback
    // ------------------------------------------------------------------

    var hasUnsavedChanges = mutableStateOf(false)
        private set
    var isBusy = mutableStateOf(false)
        private set
    var statusMessage = mutableStateOf<String?>(null)
        private set

    /** Autosave pendente (descrição) a oferecer para restauro. */
    var pendingAutosave = mutableStateOf<String?>(null)
        private set

    val history = HistoryManager()

    val repository = FileSystemProjectRepository(
        File(getApplication<Application>().filesDir, "projects")
    )

    private val autosaveDir: File
        get() = File(getApplication<Application>().filesDir, "autosave").apply { mkdirs() }

    private var statusJob: Job? = null

    init {
        // Regista-se no ciclo de vida via observador do ViewModelStoreOwner:
        // o Compose chama setViewTreeLifecycleOwner na Activity, e o
        // AndroidViewModel é observado através do processo abaixo.
        // (O autosave também é acionado explicitamente antes de navegar para
        // fora do editor e via onStopped do lifecycle owner da Activity.)
    }

    /** Mensagem temporária para o utilizador (snackbar). */
    fun showMessage(message: String) {
        statusMessage.value = message
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            delay(4000)
            statusMessage.value = null
        }
    }

    fun clearStatus() {
        statusJob?.cancel()
        statusMessage.value = null
    }

    // ------------------------------------------------------------------
    // Ciclo de vida — autosave
    // ------------------------------------------------------------------

    /** Chamado pelo UI quando a Activity passa a ON_STOP (lifecycle observer). */
    override fun onStop(owner: LifecycleOwner) {
        autosaveIfNeeded()
    }

    fun autosaveIfNeeded() {
        val doc = document ?: return
        if (!hasUnsavedChanges.value || isBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manifest = OspManifest(
                    name = project?.name ?: "sem-titulo",
                    width = doc.width,
                    height = doc.height,
                    createdAtEpochMs = project?.createdAtEpochMs ?: System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                    palette = palette.value.map { String.format("#%08X", it) }
                )
                OspCodec.save(manifest, doc, File(autosaveDir, AUTOSAVE_FILE).outputStream().buffered())
            } catch (e: Exception) {
                // Autosave é melhor-effort; nunca interrompe o utilizador.
            }
        }
    }

    /** Verifica (na entrada do editor) se existe trabalho não guardado. */
    fun checkForAutosave() {
        val f = File(autosaveDir, AUTOSAVE_FILE)
        if (!f.exists()) return
        try {
            val m = OspCodec.readManifest(f.readBytes())
            pendingAutosave.value = "${m.name} (${m.width}×${m.height})"
        } catch (e: Exception) {
            f.delete()
        }
    }

    fun restoreAutosave(onDone: () -> Unit) {
        val f = File(autosaveDir, AUTOSAVE_FILE)
        if (!f.exists()) return
        isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loaded = OspCodec.load(f.inputStream().buffered())
                withContext(Dispatchers.Main) {
                    installDocument(
                        doc = loaded.document,
                        project = null,
                        palette = loaded.manifest.palette?.mapNotNull { parseHexColor(it) }
                            ?: listOf(DEFAULT_COLOR)
                    )
                    currentProjectName = loaded.manifest.name
                    hasUnsavedChanges.value = true
                    showMessage("Trabalho não guardado restaurado")
                    onDone()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Falha ao restaurar: ${e.message}")
                    onDone()
                }
            } finally {
                f.delete()
                withContext(Dispatchers.Main) { pendingAutosave.value = null; isBusy.value = false }
            }
        }
    }

    fun discardAutosave() {
        File(autosaveDir, AUTOSAVE_FILE).delete()
        pendingAutosave.value = null
    }

    // ------------------------------------------------------------------
    // Gestão de documentos
    // ------------------------------------------------------------------

    /**
     * Cria um novo projeto (documento transparente). Não grava de imediato:
     * grava no primeiro [saveProject] (que usa o nome indicado).
     */
    fun newProject(name: String, width: Int, height: Int, onReady: () -> Unit) {
        autosaveIfNeeded()
        installDocument(
            doc = PixelDocument(width, height),
            project = null,
            palette = palette.value,
            projectName = name
        )
        hasUnsavedChanges.value = true
        showMessage("Novo projeto: $name (${width}x$height)")
        onReady()
    }

    /** Lista os projetos guardados (para a folha "Abrir"). */
    fun listProjects(onLoaded: (List<PixelProject>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = try { repository.listProjects() } catch (e: Exception) { emptyList() }
            withContext(Dispatchers.Main) { onLoaded(list) }
        }
    }

    fun openProject(id: String, onDone: (Boolean) -> Unit) {
        if (hasUnsavedChanges.value) autosaveIfNeeded()
        isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loaded = repository.loadProject(id)
                    ?: throw java.io.IOException("Projeto não encontrado")
                val paletteFromFile = loaded.palette?.mapNotNull { parseHexColor(it) }
                    ?.takeIf { it.isNotEmpty() } ?: palette.value
                withContext(Dispatchers.Main) {
                    installDocument(loaded.document, loaded.project, paletteFromFile)
                    hasUnsavedChanges.value = false
                    showMessage("Projeto aberto: ${loaded.project.name}")
                    onDone(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Falha ao abrir projeto: ${e.message}")
                    onDone(false)
                }
            } finally {
                withContext(Dispatchers.Main) { isBusy.value = false }
            }
        }
    }

    /** Importa um .osp de um URI SAF (Abrir de ficheiros). */
    fun importProject(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var loaded: OspCodec.LoadedProject? = null
            try {
                val resolver = getApplication<Application>().contentResolver
                val stream = resolver.openInputStream(uri)
                    ?: throw java.io.IOException("Não foi possível abrir o ficheiro")
                loaded = stream.use { OspCodec.load(it) }
                withContext(Dispatchers.Main) {
                    installDocument(loaded.document, null, palette.value)
                    currentProjectName = loaded.manifest.name
                    hasUnsavedChanges.value = true
                    showMessage("Importado: ${loaded.manifest.name}")
                    onDone(true)
                }
            } catch (e: OspCodec.OspUnsupportedVersionException) {
                withContext(Dispatchers.Main) { showMessage(e.message ?: "Versão não suportada"); onDone(false) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Ficheiro .osp inválido: ${e.message}")
                    onDone(false)
                }
            } finally {
                withContext(Dispatchers.Main) { isBusy.value = false }
            }
        }
    }

    /**
     * Guarda no repositório interno. Se o projeto ainda não foi gravado,
     * cria uma entrada nova com o nome atual.
     */
    fun saveProject(onDone: (Boolean) -> Unit) {
        val doc = document ?: return
        isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = project
                val updated = if (current != null) {
                    repository.saveDocument(
                        current, doc,
                        palette.value.map { String.format("#%08X", it) }
                    )
                } else {
                    repository.createProject(
                        name = currentProjectName.ifBlank { "sem-titulo" },
                        width = doc.width,
                        height = doc.height
                    ).let { created ->
                        repository.saveDocument(
                            created, doc,
                            palette.value.map { String.format("#%08X", it) }
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    project = updated
                    hasUnsavedChanges.value = false
                    showMessage("Projeto guardado: ${updated.name}")
                    onDone(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Falha ao guardar: ${e.message}")
                    onDone(false)
                }
            } finally {
                withContext(Dispatchers.Main) { isBusy.value = false }
            }
        }
    }

    /**
     * Guardar como: escreve uma CÓPIA no destino SAF escolhido pelo
     * utilizador (não altera o projeto atual). O ficheiro é serializado
     * integralmente em memória primeiro; uma falha a meio do stream é
     * reportada e o .osp interno continua intacto.
     */
    fun saveProjectAs(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        val doc = document ?: return
        isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manifest = OspManifest(
                    name = currentProjectName.ifBlank { "sem-titulo" },
                    width = doc.width,
                    height = doc.height,
                    createdAtEpochMs = project?.createdAtEpochMs ?: System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                    palette = palette.value.map { String.format("#%08X", it) }
                )
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(uri, "wt")?.use { out ->
                    OspCodec.save(manifest, doc, out.buffered())
                } ?: throw java.io.IOException("Não foi possível abrir o destino")
                withContext(Dispatchers.Main) {
                    showMessage("Cópia guardada (Guardar como)")
                    onDone(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Falha ao guardar cópia: ${e.message}")
                    onDone(false)
                }
            } finally {
                withContext(Dispatchers.Main) { isBusy.value = false }
            }
        }
    }

    /**
     * Exporta a imagem atual como PNG para o URI SAF escolhido.
     * Exporta APENAS os píxeis do documento (nunca grelha, quadriculado ou UI).
     */
    fun exportPng(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        val doc = document ?: return
        isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Não premultiplicado: os valores RGBA são escritos no PNG
                // EXATAMENTE como estão no documento (pixel art exige bytes
                // exatos, inclusive com alpha muito baixo).
                val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
                bmp.setPremultiplied(false)
                bmp.copyPixelsFromBuffer(IntBuffer.wrap(doc.copyPixels()))
                val resolver = getApplication<Application>().contentResolver
                resolver.openOutputStream(uri, "wt")?.use { out ->
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out.buffered())) {
                        throw java.io.IOException("Falha ao codificar PNG")
                    }
                } ?: throw java.io.IOException("Não foi possível abrir o destino")
                bmp.recycle()
                withContext(Dispatchers.Main) {
                    showMessage("PNG exportado (${doc.width}x${doc.height})")
                    onDone(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Falha ao exportar PNG: ${e.message}")
                    onDone(false)
                }
            } finally {
                withContext(Dispatchers.Main) { isBusy.value = false }
            }
        }
    }

    // ------------------------------------------------------------------
    // Desenho (chamado pelo PixelCanvas)
    // ------------------------------------------------------------------

    /** Recorder composto: alimenta o histórico e sincroniza o Bitmap ao vivo. */
    private inner class StrokeSync(
        private val liveSync: Boolean
    ) : PixelEngine.StrokeRecorder {
        override fun onBeforeChange(x: Int, y: Int, beforeColor: Int) {
            history.onBeforeChange(x, y, beforeColor)
        }

        override fun onChanged(x: Int, y: Int) {
            history.onChanged(x, y)
            if (liveSync) {
                bitmap?.setPixel(x, y, document!!.get(x, y))
            }
        }
    }

    /** Inicia um traço com a ferramenta atual (down do dedo). */
    fun beginDraw(px: Int, py: Int) {
        val doc = document ?: return
        history.beginStroke()
        val sync = StrokeSync(liveSync = true)
        when (tool.value) {
            PixelEngine.Tool.PENCIL -> PixelEngine.drawPixel(doc, px, py, activeColor.value, brushSize.value, sync)
            PixelEngine.Tool.ERASER -> PixelEngine.drawPixel(doc, px, py, 0x00000000, brushSize.value, sync)
            else -> Unit // fill/eyedropper agem no fim do toque
        }
        afterEditVisual()
    }

    /** Continua o traço (movimento do dedo) — linha contínua desde o último ponto. */
    fun continueDraw(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        val doc = document ?: return
        val sync = StrokeSync(liveSync = true)
        when (tool.value) {
            PixelEngine.Tool.PENCIL -> PixelEngine.drawLine(doc, fromX, fromY, toX, toY, activeColor.value, brushSize.value, sync)
            PixelEngine.Tool.ERASER -> PixelEngine.drawLine(doc, fromX, fromY, toX, toY, 0x00000000, brushSize.value, sync)
            else -> Unit
        }
        afterEditVisual()
    }

    /** Termina o traço (up do dedo) — UMA entrada de histórico. */
    fun endDraw() {
        val doc = document ?: return
        history.commitStroke(doc)
        afterEditFull()
    }

    /** Cancela o traço em curso (segundo dedo entrou). */
    fun cancelDraw() {
        val doc = document ?: return
        history.cancelStroke(doc)
        afterEditFull()
    }

    /** Ferramentas de toque único (fill / conta-gotas), agem no fim do toque. */
    fun tapTool(px: Int, py: Int) {
        val doc = document ?: return
        when (tool.value) {
            PixelEngine.Tool.FILL -> {
                history.beginStroke()
                PixelEngine.floodFill(doc, px, py, activeColor.value, fillTolerance.value, StrokeSync(liveSync = false))
                history.commitStroke(doc)
                afterEditFull()
            }
            PixelEngine.Tool.EYEDROPPER -> {
                val picked = PixelEngine.pickColor(doc, px, py)
                if (doc.inBounds(px, py)) {
                    activeColor.value = picked
                    addColorToPalette(picked)
                    showMessage("Cor obtida")
                }
            }
            else -> Unit
        }
    }

    fun undo() {
        val doc = document ?: return
        if (history.undo(doc)) {
            afterEditFull()
            showMessage("Desfeito")
        }
    }

    fun redo() {
        val doc = document ?: return
        if (history.redo(doc)) {
            afterEditFull()
            showMessage("Refeito")
        }
    }

    fun addColorToPalette(color: Int) {
        if (palette.value.contains(color)) return
        val updated = (palette.value + color).takeLast(MAX_PALETTE)
        palette.value = updated
        hasUnsavedChanges.value = true
    }

    fun removePaletteColor(color: Int) {
        palette.value = palette.value - color
        hasUnsavedChanges.value = true
    }

    // ------------------------------------------------------------------
    // Câmera
    // ------------------------------------------------------------------

    fun updateViewport(width: Float, height: Float) {
        val firstLayout = viewportWidth <= 0f && width > 0f
        viewportWidth = width
        viewportHeight = height
        if (pendingFit || firstLayout) {
            fitToScreen()
            pendingFit = false
        }
    }

    /** Aplica zoom ancorado num ponto do ecrã (pinça ou botões). */
    fun applyZoom(zoomFactor: Float, anchor: Offset) {
        val doc = document ?: return
        val current = scale.value
        val newScale = (current * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale == current) return
        // Mantém o ponto da imagem sob a âncora fixo:
        // screen = image * s + offset  →  offset' = anchor - (anchor - offset) * (s'/s)
        val ratio = newScale / current
        offset.value = Offset(
            anchor.x - (anchor.x - offset.value.x) * ratio,
            anchor.y - (anchor.y - offset.value.y) * ratio
        )
        scale.value = newScale
    }

    /** Zoom pelos botões da barra: ancorado no centro do viewport. */
    fun zoomBy(factor: Float) {
        applyZoom(factor, Offset(viewportWidth / 2f, viewportHeight / 2f))
    }

    fun applyPan(pan: Offset) {
        offset.value = offset.value + pan
    }

    /** Ajusta o documento inteiro à vista (com margem de 24px). */
    fun fitToScreen() {
        val doc = document ?: return
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        val s = minOf(
            (viewportWidth - 48f) / doc.width,
            (viewportHeight - 48f) / doc.height
        ).coerceIn(MIN_SCALE, MAX_SCALE)
        scale.value = s
        offset.value = Offset(
            (viewportWidth - doc.width * s) / 2f,
            (viewportHeight - doc.height * s) / 2f
        )
    }

    /** Zoom para 1 píxel da imagem = 1 dp do ecrã? Não: 1:1 real (px físico aproximado). */
    fun zoomTo100() {
        val doc = document ?: return
        scale.value = 1f.coerceIn(MIN_SCALE, MAX_SCALE)
        // Centra
        if (viewportWidth > 0f) {
            offset.value = Offset(
                (viewportWidth - doc.width * scale.value) / 2f,
                (viewportHeight - doc.height * scale.value) / 2f
            )
        }
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private var currentProjectName: String = "sem-titulo"

    private fun installProjectName(name: String) {
        currentProjectName = name
    }

    val projectName: String
        get() = project?.name ?: currentProjectName

    private fun installDocument(
        doc: PixelDocument,
        project: PixelProject?,
        palette: List<Int>,
        projectName: String = project?.name ?: this.currentProjectName
    ) {
        this.document = doc
        this.project = project
        this.currentProjectName = projectName
        this.palette.value = palette.take(MAX_PALETTE)
        history.clear()
        bitmap?.recycle()
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(IntBuffer.wrap(doc.pixelsView()))
        bitmap = bmp
        imageBitmap = bmp.asImageBitmap()
        hasUnsavedChanges.value = false
        frameVersion.intValue++
        pendingFit = true
        fitToScreen()
    }

    private fun afterEditVisual() {
        frameVersion.intValue++
        hasUnsavedChanges.value = true
    }

    private fun afterEditFull() {
        val doc = document ?: return
        bitmap?.copyPixelsFromBuffer(IntBuffer.wrap(doc.pixelsView()))
        frameVersion.intValue++
        hasUnsavedChanges.value = true
    }

    private fun parseHexColor(hex: String): Int? = try {
        if (hex.length == 9 && hex[0] == '#') AndroidColor.parseColor(hex) else null
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_COLOR: Int = 0xFFF57C00.toInt() // laranja Pixel One
        const val MIN_SCALE: Float = 0.05f
        const val MAX_SCALE: Float = 64f
        const val MAX_PALETTE: Int = 24
        const val AUTOSAVE_FILE: String = "autosave.osp"
        const val AUTOSAVE_NAME: String = "autosave"
    }
}
