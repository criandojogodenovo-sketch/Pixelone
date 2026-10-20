package com.pixelone.studio.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown as awaitFirstDownRaw
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pixelone.studio.editor.EditorViewModel
import com.pixelone.studio.pixel.PixelEngine
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas de pixel art do editor.
 *
 * CORREÇÕES NESTA VERSÃO:
 * - INVALIDAÇÃO CORRETA: o bloco de desenho lê [EditorViewModel.frameVersion]
 *   (e escala/offset/grelha/preview) — cada edição ou movimento de traço
 *   invalida o desenho de forma determinística. O documento e o bitmap são
 *   ESTADO observável, pelo que criar/abrir um projeto compõe o canvas
 *   imediatamente (correção do Problema 2).
 * - VIEWPORT FORA DO DRAW: as dimensões do viewport chegam via
 *   [onSizeChanged] (não se escreve estado durante o draw — evita ciclos
 *   de invalidação); o ajuste à vista (fit-to-screen) acontece quando o
 *   viewport tem dimensões válidas e/ou o documento é instalado.
 * - PREVIEW DO TRAÇO: os carimbos pendentes do gesto são desenhados por
 *   cima da imagem SEM tocar no documento (o lote vai ao motor nativo no
 *   fim do gesto, no EditorViewModel).
 * - GRELHA COM CACHE: o Path da grelha é reconstruído apenas quando
 *   dimensões/zoom-threshold mudam; o pan/zoom desenha o mesmo Path com
 *   translate — nunca milhares de drawLine por frame. O número de linhas
 *   visíveis continua limitado (proteção de performance) e a grelha é
 *   apenas VISUAL (nunca entra no PNG exportado, que lê o documento).
 * - Quadriculado de transparência independente da grelha (1 drawRect com
 *   shader tile).
 *
 * ESTRATÉGIA DE GESTOS (documentada — mantida da Fase 1):
 * - 1 dedo: DESENHA com a ferramenta ativa (ou fill/conta-gotas no fim do
 *   toque). Um único dedo NUNCA move nem faz zoom do canvas.
 * - 2 dedos: PAN + PINÇA de zoom, exclusivamente. Se um segundo dedo
 *   entrar durante um traço, o preview é CANCELADO (nada foi escrito no
 *   documento) e o gesto passa a navegação.
 * - Botões dedicados (+, −, ajustar à vista) cobrem navegação acessível.
 * - Conversão: píxel = floor((ecrã − offset) / escala), correta sob
 *   qualquer zoom/pan.
 */
@Composable
fun PixelCanvas(
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
    canvasBackground: Color = Color(0xFF0D0D0D),
    checkerLight: Color = Color(0xFF3A3A3A),
    checkerDark: Color = Color(0xFF2B2B2B),
    gridColor: Color = Color(0x66FFFFFF),
    borderColor: Color = Color(0xFFF57C00)
) {
    // Leitura dos estados em COMPOSIÇÃO garante que criar/abrir um projeto
    // compõe o canvas imediatamente (documento/bitmap são estado observável
    // — correção do Problema 2). O desenho re-observa os mesmos estados
    // dentro do bloco de draw.
    @Suppress("UNUSED_VARIABLE")
    val compositionTick = viewModel.document != null && viewModel.imageBitmap != null

    // Pincel do quadriculado (tile 32x32 px com 2 casas) — eficiente: 1 drawRect.
    val checkerBrush = remember(checkerLight, checkerDark) {
        val tile = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        tile.eraseColor(checkerDark.toArgb())
        val half = 16
        val light = checkerLight.toArgb()
        val canvas = android.graphics.Canvas(tile)
        val paint = android.graphics.Paint().apply { color = light }
        canvas.drawRect(0f, 0f, half.toFloat(), half.toFloat(), paint)
        canvas.drawRect(half.toFloat(), half.toFloat(), 32f, 32f, paint)
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    Canvas(
        modifier = modifier
            .testTag("pixelone_canvas")
            // Viewport chega AQUI (layout) — não no draw (sem escrita de estado em draw).
            .onSizeChanged { size ->
                viewModel.updateViewport(size.width.toFloat(), size.height.toFloat())
            }
            .pointerInput(viewModel.tool.value) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val document = viewModel.document
                    val tool = viewModel.tool.value
                    if (document == null) return@awaitEachGesture
                    val drawDrag = tool == PixelEngine.Tool.PENCIL || tool == PixelEngine.Tool.ERASER
                    var strokeStarted = false
                    var navigated = false

                    // O traço começa no DOWN (preview imediato, sem falhas).
                    if (drawDrag) {
                        val p0 = screenToPixel(down.position, viewModel, document.width, document.height)
                        if (p0 != null) {
                            viewModel.beginStrokePreview(p0.x, p0.y)
                            strokeStarted = true
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.isEmpty()) {
                            // Todos os dedos levantados: confirma o lote ou executa tap.
                            if (strokeStarted) {
                                viewModel.commitStroke()
                            } else if (!navigated && tapActs(tool)) {
                                val px = screenToPixel(down.position, viewModel, document.width, document.height)
                                if (px != null) viewModel.tapTool(px.x, px.y)
                            }
                            break
                        }

                        if (pressed.size >= 2) {
                            // NAVEGAÇÃO (2 dedos). Cancela o preview (nada foi escrito).
                            navigated = true
                            if (strokeStarted) {
                                viewModel.cancelStroke()
                                strokeStarted = false
                            }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            if (zoom != 1f) viewModel.applyZoom(zoom, centroid)
                            if (pan != Offset.Zero) viewModel.applyPan(pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else {
                            val change = pressed[0]
                            if (navigated) {
                                // Restou 1 dedo depois da navegação: ignora até todos subirem.
                                change.consume()
                                continue
                            }
                            if (drawDrag && strokeStarted) {
                                val px = screenToPixel(change.position, viewModel, document.width, document.height)
                                if (px != null) viewModel.continueStrokePreview(px.x, px.y)
                            }
                            change.consume()
                        }
                    }
                }
            }
    ) {
        // Leituras de estado que dirigem a invalidação do desenho:
        @Suppress("UNUSED_VARIABLE")
        val frame = viewModel.frameVersion.intValue // cada edição → redesenho
        val s = viewModel.scale.value
        val off = viewModel.offset.value
        val showGrid = viewModel.showGrid.value
        val gridThreshold = viewModel.gridThreshold.value
        val preview = viewModel.preview

        // Documento atual (referência estável durante este frame).
        val document = viewModel.document
        val imageBitmap = viewModel.imageBitmap

        drawRect(canvasBackground, size = size)

        if (document == null || imageBitmap == null) return@Canvas

        val imgW = document.width * s
        val imgH = document.height * s

        // 1) Quadriculado sob a imagem (transparência) — independente da grelha.
        drawRect(
            brush = checkerBrush,
            topLeft = off,
            size = Size(imgW, imgH)
        )

        // 2) Imagem nítida (sem interpolação). [frame] garante a re-leitura
        //    após atualizações regionais do bitmap.
        drawImage(
            image = imageBitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(document.width, document.height),
            dstOffset = IntOffset(floor(off.x).toInt(), floor(off.y).toInt()),
            dstSize = IntSize(ceil(imgW).toInt().coerceAtLeast(1), ceil(imgH).toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.None
        )

        // 3) Grelha de píxeis (acima do limiar de zoom; Path em cache + translate
        //    para documentos que caibam no limite de linhas; culling visível
        //    para documentos maiores).
        if (showGrid && s >= gridThreshold) {
            drawGridCached(document.width, document.height, s, off, size, gridColor)
        }

        // 4) PREVIEW do traço em curso (por cima da imagem; não modifica píxeis).
        preview?.let { stamp ->
            if (stamp.stampPoints.isNotEmpty()) {
                val half = (stamp.brushSize.coerceIn(1, PixelEngine.MAX_BRUSH) - 1) / 2
                val previewColor = if (stamp.eraser) {
                    Color.White.copy(alpha = 0.45f) // borracha: contorno translúcido
                } else {
                    Color(stamp.color)
                }
                for (point in stamp.stampPoints) {
                    val x0 = off.x + (point.x - half) * s
                    val y0 = off.y + (point.y - half) * s
                    drawRect(
                        color = previewColor,
                        topLeft = Offset(x0, y0),
                        size = Size(stamp.brushSize * s, stamp.brushSize * s)
                    )
                }
            }
        }

        // 5) Contorno da imagem.
        drawRect(
            color = borderColor,
            topLeft = off,
            size = Size(imgW, imgH),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
    }
}

/** awaitFirstDown com requireUnconsumed = false (o canvas aceita tudo). */
private suspend fun AwaitPointerEventScope.awaitFirstDown(): PointerInputChange =
    awaitFirstDownRaw(requireUnconsumed = false)

private fun tapActs(tool: PixelEngine.Tool): Boolean =
    tool == PixelEngine.Tool.FILL || tool == PixelEngine.Tool.EYEDROPPER

/** Converte um ponto do ecrã em píxel da imagem; null se fora dos limites. */
private fun screenToPixel(
    screen: Offset,
    viewModel: EditorViewModel,
    width: Int,
    height: Int
): IntOffset? {
    val s = viewModel.scale.value
    val off = viewModel.offset.value
    val x = floor((screen.x - off.x) / s).toInt()
    val y = floor((screen.y - off.y) / s).toInt()
    return if (x in 0 until width && y in 0 until height) IntOffset(x, y) else null
}

/**
 * Grelha com PATH EM CACHE (entrada única):
 * - Documentos com w + h + 2 ≤ GRID_LINE_LIMIT: o Path completo é
 *   reconstruído apenas quando (largura, altura, ESCALA) mudam; o pan
 *   desenha o mesmo Path com translate (zero reconstrução por frame).
 * - Documentos maiores: linhas visíveis desenhadas diretamente com culling
 *   (nunca mais que GRID_LINE_LIMIT por frame).
 * A grelha é apenas VISUAL — nunca entra no PNG exportado.
 */
private fun DrawScope.drawGridCached(
    width: Int,
    height: Int,
    scale: Float,
    off: Offset,
    viewSize: Size,
    gridColor: Color
) {
    if (width + height + 2 <= GRID_LINE_LIMIT) {
        // CACHING: mesmo Path enquanto (w, h, escala) não mudarem.
        val key = GridKey(width, height, scale.toRawBits())
        val cached = cachedGrid
        val path = if (cached != null && cached.key == key) {
            cached.path
        } else {
            Path().apply {
                for (i in 0..width) {
                    moveTo(i * scale, 0f)
                    lineTo(i * scale, height * scale)
                }
                for (j in 0..height) {
                    moveTo(0f, j * scale)
                    lineTo(width * scale, j * scale)
                }
            }.also { cachedGrid = GridCacheEntry(key, it) }
        }
        translate(off.x, off.y) {
            drawPath(path, gridColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
        }
        return
    }

    // FALLBACK com culling (documentos grandes): apenas linhas visíveis.
    val xStart = max(0, floor((-off.x) / scale).toInt())
    val xEnd = min(width, ceil((viewSize.width - off.x) / scale).toInt())
    val yStart = max(0, floor((-off.y) / scale).toInt())
    val yEnd = min(height, ceil((viewSize.height - off.y) / scale).toInt())
    if (xStart > xEnd || yStart > yEnd) return
    if (xEnd - xStart + yEnd - yStart > GRID_LINE_LIMIT) return // proteção

    val imgRight = off.x + width * scale
    val imgBottom = off.y + height * scale
    for (i in xStart..xEnd) {
        val x = off.x + i * scale
        drawLine(gridColor, Offset(x, max(0f, off.y)), Offset(x, min(viewSize.height, imgBottom)))
    }
    for (j in yStart..yEnd) {
        val y = off.y + j * scale
        drawLine(gridColor, Offset(max(0f, off.x), y), Offset(min(viewSize.width, imgRight), y))
    }
}

private data class GridKey(val width: Int, val height: Int, val scaleBits: Int)

private class GridCacheEntry(val key: GridKey, val path: Path)

/** Máximo de linhas de grelha por frame (vertical + horizontal somadas). */
private const val GRID_LINE_LIMIT = 1200

/** Cache de entrada única do Path da grelha (UI thread apenas). */
private var cachedGrid: GridCacheEntry? = null
