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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
 * Canvas de pixel art do editor (Fase 1).
 *
 * RENDERIZAÇÃO:
 * - O documento é desenhado com FilterQuality.None → píxeis nítidos, SEM
 *   interpolação, em qualquer zoom.
 * - Fundo QUADRICULADO (xadrez cinzento) apenas sob o retângulo da imagem,
 *   representando transparência — NUNCA faz parte do bitmap exportado
 *   (a exportação usa os píxeis do documento diretamente).
 * - GRELHA de píxeis opcional, desenhada apenas a partir de zoom ≥
 *   [EditorViewModel.gridThreshold] e apenas para as linhas visíveis.
 *
 * ESTRATÉGIA DE GESTOS (documentada — decisão da Fase 1):
 * - 1 dedo: DESENHA com a ferramenta ativa (ou ativa fill/conta-gotas no
 *   fim do toque). Um único dedo NUNCA move nem faz zoom do canvas.
 * - 2 dedos: PAN + PINÇA de zoom, exclusivamente. Se um segundo dedo
 *   entrar durante um traço, o traço é CANCELADO (restaurado) para evitar
 *   marcas acidentais e o gesto passa a navegação.
 * - Botões dedicados (+, −, ajustar à vista, 1:1) cobrem navegação
 *   acessível sem conflito com o desenho.
 * - Conversão de coordenadas: píxelImagem = floor((ecrã − offset) / escala),
 *   garantindo mapeamento correto sob qualquer zoom/pan.
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
    val image = viewModel.imageBitmap
    val doc = viewModel.document

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

    if (image == null || doc == null) {
        Canvas(modifier) {
            drawRect(canvasBackground, size = size)
        }
        return
    }

    val tool = viewModel.tool.value

    Canvas(
        modifier = modifier.pointerInput(tool) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val drawDrag = tool == PixelEngine.Tool.PENCIL || tool == PixelEngine.Tool.ERASER
                var strokeStarted = false
                var lastPixel: IntOffset? = null
                var navigated = false

                // O traço começa no DOWN (não no primeiro move): sem falhas
                // entre o toque e o primeiro evento de movimento.
                if (drawDrag) {
                    val p0 = screenToPixel(down.position, viewModel, doc.width, doc.height)
                    if (p0 != null) {
                        viewModel.beginDraw(p0.x, p0.y)
                        strokeStarted = true
                        lastPixel = p0
                    }
                }

                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }

                    if (pressed.isEmpty()) {
                        // Todos os dedos levantados: termina traço ou executa tap.
                        if (strokeStarted) {
                            viewModel.endDraw()
                        } else if (!navigated && tapActs(tool)) {
                            val px = screenToPixel(down.position, viewModel, doc.width, doc.height)
                            if (px != null) viewModel.tapTool(px.x, px.y)
                        }
                        break
                    }

                    if (pressed.size >= 2) {
                        // NAVEGAÇÃO (2 dedos). Cancela traço em curso.
                        navigated = true
                        if (strokeStarted) {
                            viewModel.cancelDraw()
                            strokeStarted = false
                            lastPixel = null
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
                        if (drawDrag) {
                            val px = screenToPixel(change.position, viewModel, doc.width, doc.height)
                            if (px != null) {
                                if (strokeStarted && lastPixel != null &&
                                    (lastPixel!!.x != px.x || lastPixel!!.y != px.y)
                                ) {
                                    viewModel.continueDraw(lastPixel!!.x, lastPixel!!.y, px.x, px.y)
                                    lastPixel = px
                                }
                            }
                        }
                        change.consume()
                    }
                }
            }
        }
    ) {
        drawRect(canvasBackground, size = size)
        viewModel.updateViewport(size.width, size.height)

        val s = viewModel.scale.value
        val off = viewModel.offset.value
        val imgW = doc.width * s
        val imgH = doc.height * s

        // 1) Quadriculado sob a imagem (transparência)
        drawRect(
            brush = checkerBrush,
            topLeft = off,
            size = Size(imgW, imgH)
        )

        // 2) Imagem nítida (sem interpolação)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(doc.width, doc.height),
            dstOffset = IntOffset(floor(off.x).toInt(), floor(off.y).toInt()),
            dstSize = IntSize(ceil(imgW).toInt().coerceAtLeast(1), ceil(imgH).toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.None
        )

        // 3) Grelha de píxeis (apenas em zoom alto e apenas linhas visíveis)
        if (viewModel.showGrid.value && s >= viewModel.gridThreshold.value) {
            drawGrid(doc.width, doc.height, s, off, size, gridColor)
        }

        // 4) Contorno da imagem
        drawRect(
            color = borderColor,
            topLeft = off,
            size = Size(imgW, imgH),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
    }
}

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
 * Grelha desenhada por linha do ecrã, CULADA ao viewport: só as linhas de
 * píxeis visíveis são desenhadas (mesmo em canvas grandes).
 */
private fun DrawScope.drawGrid(
    width: Int,
    height: Int,
    scale: Float,
    off: Offset,
    viewSize: Size,
    gridColor: Color
) {
    val xStart = max(0, floor((-off.x) / scale).toInt())
    val xEnd = min(width, ceil((viewSize.width - off.x) / scale).toInt())
    val yStart = max(0, floor((-off.y) / scale).toInt())
    val yEnd = min(height, ceil((viewSize.height - off.y) / scale).toInt())

    if (xEnd - xStart + yEnd - yStart > 1200) return // proteção de performance

    for (i in xStart..xEnd) {
        val x = off.x + i * scale
        drawLine(gridColor, Offset(x, max(0f, off.y)), Offset(x, min(viewSize.height, off.y + height * scale)))
    }
    for (j in yStart..yEnd) {
        val y = off.y + j * scale
        drawLine(gridColor, Offset(max(0f, off.x), y), Offset(min(viewSize.width, off.x + width * scale), y))
    }
}

/** awaitFirstDown com requireUnconsumed = false (o canvas aceita tudo). */
private suspend fun AwaitPointerEventScope.awaitFirstDown(): PointerInputChange =
    awaitFirstDownRaw(requireUnconsumed = false)
