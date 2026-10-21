package com.pixelone.studio.editor

import android.app.Application
import android.content.ContentValues
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import com.pixelone.studio.export.PngExport
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.pixelone.studio.osp.FileSystemProjectRepository
import com.pixelone.studio.osp.OspCodec
import com.pixelone.studio.osp.OspManifest
import com.pixelone.studio.perf.PerfLog
import com.pixelone.studio.pixel.HistoryManager
import com.pixelone.studio.pixel.NativeEngine
import com.pixelone.studio.pixel.PixelDocument
import com.pixelone.studio.pixel.PixelEngine
import com.pixelone.studio.project.PixelProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.IntBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import com.pixelone.studio.studio3d.StudioSharedBus

/**
 * Estado e lógica do editor de pixel art (Fase 1 + correções de
 * performance/canvas/UX).
 *
 * CORREÇÕES DE ARQUITETURA NESTA VERSÃO:
 *
 * 1. ESTADO OBSERVÁVEL (Problema 2 — canvas não aparecia):
 *    [document] e [imageBitmap] são agora `mutableStateOf` — criar/abrir um
 *    projeto notifica o Compose IMEDIATAMENTE (antes eram campos simples e
 *    a gate `doc == null` do ecrã não recomposta de forma fiável).
 *
 * 2. TRAÇO EM LOTE + PREVIEW AO VIVO (Problema 1 — atraso de 3-5 s):
 *    - Durante o gesto, os pontos são recolhidos e o traço é desenhado
 *      como PREVIEW (carimbos no Canvas), SEM escrever no documento —
 *      custo por evento de movimento é proporcional aos pontos, nunca ao
 *      tamanho da imagem.
 *    - No fim do gesto, o lote completo é enviado ao motor nativo (C++17
 *      via JNI) em UMA chamada; sem lib nativa, fallback Kotlin idêntico.
 *    - O resultado traz deltas esparsos + dirty rect → o bitmap de
 *      apresentação é atualizado APENAS na região alterada com
 *      [Bitmap.setPixels] (nunca uma cópia integral por traço, nunca
 *      recriação do bitmap, nunca setPixel por píxel).
 *
 * 3. THREADS: TODAS as mutações do documento (traço/fill/undo/redo) correm
 *    num executor serial dedicado (paralelismo 1 — sem concorrência entre
 *    desenho, buffer, histórico e apresentação). As atualizações do bitmap
 *    de apresentação são devolvidas à main thread. Snapshots para
 *    guardar/exportar são tirados no executor (sem leituras rasgadas).
 *
 * 4. INSTRUMENTAÇÃO: tags PixelOnePerf/Touch, /JNI e /Render — dimensões,
 *    pontos, píxeis alterados, dirty rect, durações e caminho
 *    (nativo/fallback), UMA linha por operação lógica.
 *
 * MANTÉM-SE: autosave ON_STOP, .osp, PNG/spritesheet/atlas, undo/redo com
 * orçamento de memória, câmera zoom/pan e identidade visual.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app), DefaultLifecycleObserver {

    // ------------------------------------------------------------------
    // Documento e renderização — ESTADO OBSERVÁVEL pelo Compose
    // ------------------------------------------------------------------

    /** Documento ativo (fonte de verdade: PixelDocument.sharedBuffer). */
    var document: PixelDocument? by mutableStateOf(null)
        private set

    var project: PixelProject? by mutableStateOf(null)
        private set

    /** Bitmap de apresentação (cache visual — NUNCA segunda fonte de verdade). */
    var imageBitmap: ImageBitmap? by mutableStateOf(null)
        private set

    private var bitmap: Bitmap? = null

    /** Bump a cada alteração visual → invalida o Canvas do Compose. */
    var frameVersion = mutableIntStateOf(0)
        private set

    /** Geração do documento — invalida resultados de operações assíncronas antigas. */
    private var generation = 0

    /** Scratch buffers do motor (1 alocação por documento, reutilizados). */
    private var strokeScratch: NativeEngine.Scratch? = null

    // ------------------------------------------------------------------
    // Executor serial de mutações do documento (traço/fill/undo/redo/snapshots)
    // ------------------------------------------------------------------

    private val mutationExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pixelone-doc-mutation")
    }
    private val mutationDispatcher = mutationExecutor.asCoroutineDispatcher()

    // ------------------------------------------------------------------
    // Ferramentas e cor
    // ------------------------------------------------------------------

    var tool = mutableStateOf(PixelEngine.Tool.PENCIL)
    var brushSize = mutableIntStateOf(1)
    var activeColor = mutableIntStateOf(DEFAULT_COLOR)
    var secondaryColor = mutableIntStateOf(SECONDARY_DEFAULT)

    /** Cor secundária: comportamento definido — troca com [swapColors]. */
    fun swapColors() {
        val a = activeColor.intValue
        activeColor.intValue = secondaryColor.intValue
        secondaryColor.intValue = a
    }

    var fillTolerance = mutableIntStateOf(0)
    var showGrid = mutableStateOf(true)
    var gridThreshold = mutableIntStateOf(4)

    /** Paleta de cores frequentes (persistida no .osp). */
    val palette = mutableStateOf(listOf(DEFAULT_COLOR))

    // ------------------------------------------------------------------
    // Preview do traço ao vivo (não modifica o documento!)
    // ------------------------------------------------------------------

    /**
     * Carimbos pendentes do traço em curso, desenhados por cima da imagem
     * no Canvas. Ao confirmar, o lote vai ao motor nativo e o preview é
     * limpo — os píxeis nunca são modificados duas vezes nem contaminam a
     * exportação (que lê o documento diretamente).
     */
    class StrokePreview(
        val color: Int,
        val brushSize: Int,
        val eraser: Boolean
    ) {
        val stampPoints = mutableStateListOf<IntOffset>()
    }

    var preview: StrokePreview? by mutableStateOf(null)
        private set

    private val rawPoints = ArrayList<Int>(64) // x,y intercalado (lote do gesto)
    private var strokeStartNanos = 0L

    // ------------------------------------------------------------------
    // Câmera (zoom/pan)
    // ------------------------------------------------------------------

    var scale = mutableFloatStateOf(1f)
    var offset = mutableStateOf(Offset.Zero)

    /** Dimensão do viewport do canvas (recebida via onSizeChanged — NUNCA no draw). */
    private var viewportWidth = 0f
    private var viewportHeight = 0f

    /** Ajuste à vista pendente (documento novo/aberto, aguarda layout válido). */
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
        viewModelScope.launch {
            // Snapshot no executor de mutação (sem leitura rasgada do buffer).
            val snapshot = withContext(mutationDispatcher) {
                if (hasUnsavedChanges.value) doc.copyPixels() else null
            } ?: return@launch
            withContext(Dispatchers.IO) {
                try {
                    val manifest = OspManifest(
                        name = project?.name ?: currentProjectName,
                        width = doc.width,
                        height = doc.height,
                        createdAtEpochMs = project?.createdAtEpochMs ?: System.currentTimeMillis(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                        palette = palette.value.map { String.format("#%08X", it) }
                    )
                    OspCodec.save(
                        manifest,
                        PixelDocument(doc.width, doc.height, snapshot),
                        File(autosaveDir, AUTOSAVE_FILE).outputStream().buffered()
                    )
                } catch (e: Exception) {
                    // Autosave é melhor-effort; nunca interrompe o utilizador.
                }
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
     * Cria um novo projeto (documento transparente). O canvas aparece
     * imediatamente: [document]/[imageBitmap] são estado Compose e o
     * ajuste à vista ocorre quando o viewport tiver dimensões válidas.
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

    /**
     * FASE 3 — "Pintar esta textura": adota a textura publicada pelo 3D
     * Texture Studio (StudioSharedBus) como documento atual. O conteúdo é
     * cópia própria (sem referências vivas entre ViewModels); fica marcado
     * com alterações não guardadas — o utilizador decide guardar.
     */
    fun adoptSharedTexture(handoff: StudioSharedBus.TextureHandoff, onReady: () -> Unit) {
        autosaveIfNeeded()
        installDocument(
            doc = PixelDocument(handoff.width, handoff.height, handoff.pixels),
            project = null,
            palette = palette.value,
            projectName = handoff.name
        )
        hasUnsavedChanges.value = true
        showMessage(
            "Textura do 3D Studio: ${handoff.name} (${handoff.width}x${handoff.height})"
        )
        onReady()
    }

    /**
     * FASE 3 — publica a textura atual no bus para o 3D Texture Studio
     * adotar ao voltar (chamada no onDispose do ecrã e no autosave).
     * Não altera o estado do editor — apenas copia píxeis para o bus.
     */
    fun publishCurrentTextureToBus() {
        val d = document ?: return
        try {
            StudioSharedBus.publishFrom2D(
                StudioSharedBus.TextureHandoff(
                    name = currentProjectName,
                    width = d.width,
                    height = d.height,
                    pixels = d.copyPixels(),
                    uvSegments = null,
                    version = System.nanoTime()
                )
            )
        } catch (_: Exception) {
            // O bus é um canal best-effort: nunca pode quebrar o editor 2D.
        }
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
     * Guarda no repositório interno. Snapshot tirado no executor de
     * mutação; a gravação (IO) usa o snapshot — nunca o buffer vivo.
     */
    fun saveProject(onDone: (Boolean) -> Unit) {
        val doc = document ?: return
        isBusy.value = true
        viewModelScope.launch {
            try {
                val snapshot = withContext(mutationDispatcher) { doc.copyPixels() }
                val working = PixelDocument(doc.width, doc.height, snapshot)
                val updated = withContext(Dispatchers.IO) {
                    val current = project
                    if (current != null) {
                        repository.saveDocument(
                            current, working,
                            palette.value.map { String.format("#%08X", it) }
                        )
                    } else {
                        repository.createProject(
                            name = currentProjectName.ifBlank { "sem-titulo" },
                            width = doc.width,
                            height = doc.height
                        ).let { created ->
                            repository.saveDocument(
                                created, working,
                                palette.value.map { String.format("#%08X", it) }
                            )
                        }
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
     * utilizador (não altera o projeto atual), a partir de um snapshot.
     */
    fun saveProjectAs(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        val doc = document ?: return
        isBusy.value = true
        viewModelScope.launch {
            try {
                val snapshot = withContext(mutationDispatcher) { doc.copyPixels() }
                val working = PixelDocument(doc.width, doc.height, snapshot)
                withContext(Dispatchers.IO) {
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
                        OspCodec.save(manifest, working, out.buffered())
                    } ?: throw java.io.IOException("Não foi possível abrir o destino")
                }
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
     * Exporta a imagem atual como PNG para o URI SAF escolhido (caminho
     * LEGADO, usado apenas em Android < 10; em Android 10+ o ecrã usa
     * [exportPngToDownloads], direto para Download/ via MediaStore).
     *
     * Exporta APENAS os píxeis do documento (nunca grelha, quadriculado,
     * preview do traço ou UI) — o snapshot é tirado no executor de mutação.
     * Aceita escala (1x–16x, nearest-neighbor) e fundo (transparente/preto).
     *
     * CORREÇÕES vs. versão anterior (bug do ficheiro 0 bytes):
     * - SEM retorno silencioso: sem documento aberto avisa com Toast e
     *   remove o ficheiro que o seletor SAF já criou vazio;
     * - em qualquer falha remove o destino (nunca fica um ficheiro vazio
     *   ou truncado em Download/) e mostra Toast com a mensagem real;
     * - usa o mesmo núcleo testável [PngExport.buildBitmapFromDocument]
     *   (bitmap verificado: não nulo, não reciclado, píxeis exatos).
     */
    fun exportPng(
        uri: Uri,
        scale: PngExport.ScaleOption = PngExport.ScaleOption.X1,
        background: PngExport.BackgroundOption = PngExport.BackgroundOption.TRANSPARENT,
        onDone: (Boolean) -> Unit
    ) {
        val doc = document
        if (doc == null) {
            // O seletor SAF criou o ficheiro vazio ao confirmar; removê-lo
            // evita o órfão 0 bytes que motivou esta correção.
            runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
            toast("Falha ao exportar PNG: não há projeto aberto (ok=false)")
            onDone(false)
            return
        }
        isBusy.value = true
        viewModelScope.launch {
            try {
                val bmp = withContext(mutationDispatcher) {
                    PngExport.buildBitmapFromDocument(doc, scale, background)
                }
                val finalW = bmp.width
                val finalH = bmp.height
                try {
                    withContext(Dispatchers.IO) {
                        val resolver = getApplication<Application>().contentResolver
                        resolver.openOutputStream(uri, "wt")?.use { out ->
                            // compress → flush → close (use{}) — ordem exata,
                            // bytes contados para o feedback.
                            PngExport.writePngToStream(bmp, out)
                        } ?: throw java.io.IOException("Não foi possível abrir o destino")
                    }
                } finally {
                    bmp.recycle()
                }
                withContext(Dispatchers.Main) {
                    showMessage("PNG exportado (${finalW}x${finalH})")
                    onDone(true)
                }
            } catch (e: Exception) {
                // Falha: remove o destino parcial/vazio — nunca fica um
                // ficheiro quebrado em Download/.
                runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) }
                withContext(Dispatchers.Main) {
                    toast("Falha ao exportar PNG: ${e.message ?: e.javaClass.simpleName} (ok=false)")
                    onDone(false)
                }
            } finally {
                withContext(Dispatchers.Main) { isBusy.value = false }
            }
        }
    }

    // ------------------------------------------------------------------
    // Exportação PNG DIRETA para Download/ (MediaStore + IS_PENDING)
    // ------------------------------------------------------------------

    /**
     * Exporta a imagem atual como PNG diretamente para Download/, SEM
     * seletor de ficheiros, via MediaStore com ciclo IS_PENDING. Ordem
     * exata dos passos (todos com feedback obrigatório num Toast):
     *
     *  1) Bitmap ARGB_8888 com a largura/altura REAIS da tela de pixel
     *     art, píxeis copiados do buffer C++ (buffer direto partilhado
     *     Kotlin↔C++); bitmap verificado: não nulo, não reciclado.
     *     ACEITA ESCALA (1x/2x/4x/8x/16x, nearest-neighbor — filter=false,
     *     píxeis nítidos) e FUNDO (transparente ou preto sólido preenchido
     *     com canvas.drawColor antes de desenhar).
     *  2) ContentValues: DISPLAY_NAME (único), MIME_TYPE=image/png,
     *     RELATIVE_PATH=Download/, IS_PENDING=1.
     *  3) contentResolver.openOutputStream(uri) → `val ok =
     *     bitmap.compress(PNG, 100, out)` → out.flush() → close (use{}).
     *  4) IS_PENDING=0 — o ficheiro só se torna visível com conteúdo.
     *  5) Toast de sucesso com ok=true, LARGURA×ALTURA FINAIS e o tamanho
     *     real em bytes.
     *  6) Qualquer exceção: Toast com e.message (ok=false) e REMOÇÃO da
     *     entrada pendente — nunca fica um ficheiro 0 bytes em Download/.
     */
    fun exportPngToDownloads(
        scale: PngExport.ScaleOption = PngExport.ScaleOption.X1,
        background: PngExport.BackgroundOption = PngExport.BackgroundOption.TRANSPARENT
    ) {
        val doc = document
        if (doc == null) {
            toast("Falha ao exportar PNG: não há projeto aberto (ok=false)")
            return
        }
        if (isBusy.value) return // evita inserções duplicadas por duplo toque
        isBusy.value = true
        viewModelScope.launch {
            var pendingUri: Uri? = null
            try {
                // 1) Bitmap verificado a partir do buffer partilhado C++/Kotlin
                //    (original w×h real → final w*escala × h*escala)
                val bmp = withContext(mutationDispatcher) {
                    PngExport.buildBitmapFromDocument(doc, scale, background)
                }
                // Dimensões FINAIS capturadas antes do recycle (para o Toast)
                val finalW = bmp.width
                val finalH = bmp.height
                try {
                    withContext(Dispatchers.IO) {
                        val resolver = getApplication<Application>().contentResolver
                        val name = PngExport.displayNameFor(
                            projectName,
                            existingDownloadNames(resolver),
                            System.currentTimeMillis()
                        )
                        // 2) Entrada pendente no MediaStore (Download/)
                        val collection =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI
                            } else {
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            }
                        val uri = resolver.insert(
                            collection,
                            PngExport.mediaValues(name, Build.VERSION.SDK_INT)
                        ) ?: throw java.io.IOException("MediaStore não criou a entrada para \"$name\"")
                        pendingUri = uri

                        val startNanos = System.nanoTime()
                        // 3) compress → flush → close dentro de use{}
                        val result = resolver.openOutputStream(uri)?.use { out ->
                            PngExport.writePngToStream(bmp, out)
                        } ?: throw java.io.IOException("openOutputStream devolveu null para \"$name\"")

                        // 4) Publica: IS_PENDING=0 só DEPOIS do conteúdo escrito
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            resolver.update(
                                uri,
                                ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                },
                                null,
                                null
                            )
                        }
                        pendingUri = null // sucesso — nada a limpar

                        val ms = (System.nanoTime() - startNanos) / 1_000_000.0
                        val finalName = queryDisplayName(resolver, uri) ?: name
                        PerfLog.i(
                            TAG_EXPORT,
                            "PNG Download/ docs=${doc.width}x${doc.height} " +
                                "escala=${scale.factor}x " +
                                "fundo=${background.name.lowercase()} " +
                                "final=${finalW}x${finalH} " +
                                "bytes=${result.bytes} codificar=$ms ms"
                        )
                        // 5) Toast obrigatório: ok + largura×altura + bytes reais
                        withContext(Dispatchers.Main.immediate) {
                            toast(
                                "PNG exportado: Download/$finalName — " +
                                    "ok=${result.ok}, ${finalW}x${finalH} px, " +
                                    "${result.bytes} bytes"
                            )
                        }
                    }
                } finally {
                    bmp.recycle()
                }
            } catch (e: Exception) {
                // 6) Falha: remove a entrada PENDENTE (nunca fica um 0 bytes
                //     órfão em Download/) e mostra o erro SEM engolir.
                pendingUri?.let { uri ->
                    runCatching {
                        getApplication<Application>().contentResolver.delete(uri, null, null)
                    }
                }
                PerfLog.e(TAG_EXPORT, "falha: ${e.message ?: e.javaClass.simpleName}")
                withContext(Dispatchers.Main.immediate) {
                    toast("Falha ao exportar PNG: ${e.message ?: e.javaClass.simpleName} (ok=false)")
                }
            } finally {
                withContext(Dispatchers.Main.immediate) { isBusy.value = false }
            }
        }
    }

    /** Toast na main thread — todo o desfecho da exportação é visível. */
    private fun toast(message: String) {
        try {
            Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            // JVM de testes / contexto sem Looper: sem crash, o estado
            // continua a ser reportado por [statusMessage].
            showMessage(message)
        }
    }

    /** Nomes de ficheiros já presentes na coleção de downloads do MediaStore. */
    private fun existingDownloadNames(resolver: ContentResolver): Set<String> = runCatching {
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        val names = mutableSetOf<String>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) names.add(c.getString(idx) ?: "")
        }
        names
    }.getOrDefault(emptySet())

    /** Nome real atribuído pelo provider (pode ter ajustado colisões). */
    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Traço: preview ao vivo + lote único no fim do gesto
    // ------------------------------------------------------------------

    /**
     * Início do gesto (down). Recolhe o primeiro ponto e prepara o preview.
     * NÃO escreve no documento.
     */
    fun beginStrokePreview(px: Int, py: Int) {
        val d = document ?: return
        if (!d.inBounds(px, py)) return
        rawPoints.clear()
        rawPoints.add(px)
        rawPoints.add(py)
        strokeStartNanos = System.nanoTime()
        val eraser = tool.value == PixelEngine.Tool.ERASER
        preview = StrokePreview(
            color = if (eraser) 0x00000000 else activeColor.intValue,
            brushSize = brushSize.intValue,
            eraser = eraser
        ).also { it.stampPoints.add(IntOffset(px, py)) }
        PerfLog.i(TAG_TOUCH, "down píxel=($px,$py) docs=${d.width}x${d.height}")
    }

    /**
     * Movimento do dedo: interpola visualmente (Bresenham) os carimbos do
     * preview entre o último ponto e o novo — o traço ao vivo não tem
     * falhas mesmo com eventos de movimento espaçados.
     */
    fun continueStrokePreview(toX: Int, toY: Int) {
        val d = document ?: return
        val p = preview ?: return
        if (!d.inBounds(toX, toY)) return
        val last = rawPoints
        if (last.size >= 2) {
            val fromX = last[last.size - 2]
            val fromY = last[last.size - 1]
            appendBresenhamStamps(p, fromX, fromY, toX, toY)
        }
        rawPoints.add(toX)
        rawPoints.add(toY)
    }

    /** Carimbos do preview (Bresenham — mesma geometria do motor). */
    private fun appendBresenhamStamps(p: StrokePreview, x0: Int, y0: Int, x1: Int, y1: Int) {
        val dx = Math.abs(x1 - x0)
        val dy = -Math.abs(y1 - y0)
        val stepX = if (x0 < x1) 1 else -1
        val stepY = if (y0 < y1) 1 else -1
        var err = dx + dy
        var x = x0
        var y = y0
        while (true) {
            if (x != x0 || y != y0) { // o primeiro ponto já está no preview
                p.stampPoints.add(IntOffset(x, y))
            }
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += stepX }
            if (e2 <= dx) { err += dx; y += stepY }
        }
    }

    /**
     * Fim do gesto: envia o LOTE COMPLETO ao motor nativo em UMA chamada
     * (executor de mutação), commita os deltas no histórico e atualiza o
     * bitmap de apresentação APENAS na região suja. O gesto continua a ter
     * feedback visual (preview) durante todo o traço.
     */
    fun commitStroke() {
        val p = preview ?: return
        preview = null
        val d = document ?: return
        val gen = generation
        val points = rawPoints.toIntArray()
        val pointCount = points.size / 2
        val scratch = strokeScratch
        val gestureMs = (System.nanoTime() - strokeStartNanos) / 1_000_000.0
        rawPoints.clear()
        if (pointCount < 1 || scratch == null) return
        PerfLog.i(TAG_TOUCH, "traço completo pontos=$pointCount gesto=$gestureMs ms")
        viewModelScope.launch(mutationDispatcher) {
            val result = NativeEngine.strokeBatch(d, points, pointCount, p.color, p.brushSize, scratch)
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext // documento trocado entretanto
                if (result != null && result.changedSomething) {
                    history.commitDeltas(
                        scratch.indices, scratch.before, scratch.after, result.deltaCount,
                        result.dirtyX0, result.dirtyY0, result.dirtyX1, result.dirtyY1
                    )
                    updateBitmapRegion(
                        result.dirtyX0, result.dirtyY0, result.dirtyX1, result.dirtyY1
                    )
                    hasUnsavedChanges.value = true
                }
            }
        }
    }

    /** Cancela o gesto (2.º dedo entrou): limpa o preview — nada foi escrito. */
    fun cancelStroke() {
        preview = null
        rawPoints.clear()
    }

    // ------------------------------------------------------------------
    // Ferramentas de toque único (fill / conta-gotas)
    // ------------------------------------------------------------------

    fun tapTool(px: Int, py: Int) {
        val d = document ?: return
        when (tool.value) {
            PixelEngine.Tool.FILL -> {
                if (!d.inBounds(px, py)) return
                if (d.get(px, py) == activeColor.intValue) return // no-op
                val gen = generation
                val scratch = strokeScratch ?: return
                viewModelScope.launch(mutationDispatcher) {
                    val result = NativeEngine.floodFill(
                        d, px, py, activeColor.intValue, fillTolerance.intValue, scratch
                    )
                    withContext(Dispatchers.Main) {
                        if (gen != generation) return@withContext
                        if (result != null && result.changedSomething) {
                            history.commitDeltas(
                                scratch.indices, scratch.before, scratch.after, result.deltaCount,
                                result.dirtyX0, result.dirtyY0, result.dirtyX1, result.dirtyY1
                            )
                            updateBitmapRegion(
                                result.dirtyX0, result.dirtyY0, result.dirtyX1, result.dirtyY1
                            )
                            hasUnsavedChanges.value = true
                        }
                    }
                }
            }
            PixelEngine.Tool.EYEDROPPER -> {
                if (!d.inBounds(px, py)) return
                val picked = PixelEngine.pickColor(d, px, py)
                activeColor.intValue = picked
                addColorToPalette(picked)
                showMessage("Cor obtida")
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Undo / redo — aplica deltas no executor, atualiza bitmap regionalmente
    // ------------------------------------------------------------------

    fun undo() {
        val d = document ?: return
        val gen = generation
        viewModelScope.launch(mutationDispatcher) {
            val start = System.nanoTime()
            val edit = history.undo(d) ?: return@launch
            val ms = (System.nanoTime() - start) / 1_000_000.0
            PerfLog.i(TAG_RENDER, "undo deltas=${edit.size} aplicar=$ms ms")
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                updateBitmapRegion(edit.minX, edit.minY, edit.maxX, edit.maxY)
                showMessage("Desfeito")
            }
        }
    }

    fun redo() {
        val d = document ?: return
        val gen = generation
        viewModelScope.launch(mutationDispatcher) {
            val start = System.nanoTime()
            val edit = history.redo(d) ?: return@launch
            val ms = (System.nanoTime() - start) / 1_000_000.0
            PerfLog.i(TAG_RENDER, "redo deltas=${edit.size} aplicar=$ms ms")
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                updateBitmapRegion(edit.minX, edit.minY, edit.maxX, edit.maxY)
                showMessage("Refeito")
            }
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

    /** Toque longo numa amostra da paleta define a cor SECUNDÁRIA. */
    fun setSecondaryColor(color: Int) {
        secondaryColor.intValue = color
        showMessage("Cor secundária definida")
    }

    // ------------------------------------------------------------------
    // Câmera — viewport recebido via onSizeChanged (NUNCA durante o draw)
    // ------------------------------------------------------------------

    fun updateViewport(width: Float, height: Float) {
        val firstValid = viewportWidth <= 0f && width > 0f && height > 0f
        viewportWidth = width
        viewportHeight = height
        if ((pendingFit || firstValid) && width > 0f && height > 0f && document != null) {
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

    /** Ajusta o documento inteiro à vista (com margem de 24px), centrado. */
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

    /** Zoom 1:1 (1 píxel da imagem = 1 dp real do ecrã), centrado. */
    fun zoomTo100() {
        val doc = document ?: return
        scale.value = 1f.coerceIn(MIN_SCALE, MAX_SCALE)
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

    val projectName: String
        get() = project?.name ?: currentProjectName

    /**
     * Instala um documento (novo/aberto/importado/restaurado):
     * - cria a cache visual UMA vez (nunca por toque/frame);
     * - limpa o histórico e os scratch;
     * - marca ajuste à vista pendente e tenta aplicá-lo já (se o viewport
     *   é válido) — senão, aplica quando o layout chegar ([updateViewport]).
     */
    private fun installDocument(
        doc: PixelDocument,
        project: PixelProject?,
        palette: List<Int>,
        projectName: String = project?.name ?: this.currentProjectName
    ) {
        generation++
        this.document = doc
        this.project = project
        this.currentProjectName = projectName
        this.palette.value = palette.take(MAX_PALETTE)
        history.clear()
        preview = null
        rawPoints.clear()
        strokeScratch = NativeEngine.Scratch(doc.pixelCount)
        bitmap?.recycle()
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(IntBuffer.wrap(doc.copyPixels()))
        bitmap = bmp
        imageBitmap = bmp.asImageBitmap()
        hasUnsavedChanges.value = false
        frameVersion.intValue++
        pendingFit = true
        fitToScreen() // aplica já se o viewport já é válido
    }

    /**
     * ATUALIZAÇÃO REGIONAL do bitmap de apresentação: copia apenas o
     * retângulo sujo do buffer direto e aplica com [Bitmap.setPixels].
     * - nunca uma cópia integral por evento;
     * - nunca recria o Bitmap;
     * - não toca na imagem quando nenhum píxel mudou (chamadores verificam).
     */
    private fun updateBitmapRegion(x0: Int, y0: Int, x1: Int, y1: Int) {
        val bmp = bitmap ?: return
        val d = document ?: return
        val rx0 = max(0, min(x0, x1))
        val ry0 = max(0, min(y0, y1))
        val rx1 = min(d.width - 1, max(x0, x1))
        val ry1 = min(d.height - 1, max(y0, y1))
        if (rx0 > rx1 || ry0 > ry1) return
        val start = System.nanoTime()
        val rw = rx1 - rx0 + 1
        val rh = ry1 - ry0 + 1
        val region = IntArray(rw * rh) // proporcional à REGIÃO, não ao documento
        d.copyRegionInto(rx0, ry0, rw, rh, region)
        bmp.setPixels(region, 0, rw, rx0, ry0, rw, rh)
        frameVersion.intValue++
        val ms = (System.nanoTime() - start) / 1_000_000.0
        PerfLog.i(TAG_RENDER, "região ${rw}x${rh} em ($rx0,$ry0) atualizar=$ms ms")
    }

    private fun parseHexColor(hex: String): Int? = try {
        if (hex.length == 9 && hex[0] == '#') AndroidColor.parseColor(hex) else null
    } catch (e: Exception) {
        null
    }

    override fun onCleared() {
        mutationDispatcher.close()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_COLOR: Int = 0xFFF57C00.toInt() // laranja Pixel One
        const val SECONDARY_DEFAULT: Int = 0xFF212121.toInt()
        const val MIN_SCALE: Float = 0.05f
        const val MAX_SCALE: Float = 64f
        const val MAX_PALETTE: Int = 24
        const val AUTOSAVE_FILE: String = "autosave.osp"
        const val AUTOSAVE_NAME: String = "autosave"

        private const val TAG_TOUCH = "PixelOnePerf/Touch"
        private const val TAG_RENDER = "PixelOnePerf/Render"
        private const val TAG_EXPORT = "PixelOnePerf/Export"
    }
}
