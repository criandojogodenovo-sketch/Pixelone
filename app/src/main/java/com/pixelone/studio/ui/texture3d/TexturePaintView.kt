package com.pixelone.studio.ui.texture3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Superfície 2D de textura/UV do 3D Texture Studio (Fase 3).
 *
 * Usada em DOIS modos (requisitos da Fase 3):
 *  - VISTA UV: textura com as linhas UV sobrepostas (finas, semitransparentes,
 *    interruptor na UI), zoom/deslocamento com 2 dedos e TOQUE que seleciona
 *    a ilha UV tocada ([onUvTap] → ViewModel → realce no 3D);
 *  - PINTURA (modo Dividido): 1 dedo pinta (via [PixelEngine] no ViewModel,
 *    com histórico e ATUALIZAÇÃO PARCIAL da GPU), 2 dedos navegam.
 *
 * RENDERIZAÇÃO: desenho por Canvas com Paint(isFilterBitmap=false) — os
 * píxeis da textura aparecem SEM suavização (identidade pixel art). O bitmap
 * vem do ViewModel (apresentação) e é atualizado REGIONALMENTE por traço
 * (Bitmap.setPixels da região suja) + [invalidate] — nunca recriado.
 *
 * Contorno UV e realce de ilha são desenhados de Paths EM CACHE (reconstruídos
 * só quando os dados mudam) — zero alocações por frame relevante.
 */
class TexturePaintView(context: Context) : View(context) {

    /** Modo de utilização da superfície. */
    enum class SurfaceMode { UV_SELECT, PAINT }

    // ---------------------------- dados (set pela UI) ----------------------

    var textureBitmap: Bitmap? = null
    var uvSegments: FloatArray? = null
    /** Segmentos da ilha selecionada (realce laranja). */
    var islandHighlightSegments: FloatArray? = null
    var surfaceMode: SurfaceMode = SurfaceMode.UV_SELECT

    var onUvTap: ((u: Float, v: Float) -> Unit)? = null
    var onPaintBegin: ((x: Int, y: Int) -> Unit)? = null
    var onPaintMove: ((x: Int, y: Int) -> Unit)? = null
    var onPaintEnd: (() -> Unit)? = null

    // ---------------------------- câmara 2D (zoom/pan) ---------------------

    private var scale = 1f
    private var transX = 0f
    private var transY = 0f
    private var fitDone = false

    // ---------------------------- paints (criados 1 vez) -------------------

    private val texturePaint = Paint().apply {
        isFilterBitmap = false // NEAREST: sem interpolação
        isAntiAlias = false
    }
    private val uvPaint = Paint().apply {
        color = Color.argb(150, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val islandPaint = Paint().apply {
        color = Color.argb(220, 0xFF, 0x7C, 0x00) // laranja Pixel One
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val islandFillPaint = Paint().apply {
        color = Color.argb(70, 0xFF, 0x7C, 0x00)
        style = Paint.Style.FILL
        isAntiAlias = false
    }
    private val checkerPaintA = Paint().apply { color = Color.argb(255, 34, 34, 34) }
    private val checkerPaintB = Paint().apply { color = Color.argb(255, 22, 22, 22) }

    // Paths em cache
    private var uvPath: Path? = null
    private var highlightPath: Path? = null
    private var highlightFillPath: Path? = null
    private var cachedSegmentsKey: Int = 0

    // ---------------------------- estado de gesto --------------------------

    private var painting = false
    private var multiTouch = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1
    private var gestureSpanStart = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                // Zoom ancorado no foco do gesto.
                val fx = detector.focusX
                val fy = detector.focusY
                transX = fx - (fx - transX) * (newScale / scale)
                transY = fy - (fy - transY) * (newScale / scale)
                scale = newScale
                clampTransform()
                invalidate()
                return true
            }
        }
    )

    // ---------------------------- desenho ----------------------------------

    override fun onDraw(canvas: Canvas) {
        val bmp = textureBitmap
        val w = width.toFloat()
        val h = height.toFloat()

        // Fundo quadriculado (nunca entra na textura/exportação).
        val cell = 16f
        var y = 0f
        var row = 0
        while (y < h) {
            var x = 0f
            var col = 0
            while (x < w) {
                canvas.drawRect(x, y, x + cell, y + cell, if ((row + col) % 2 == 0) checkerPaintA else checkerPaintB)
                x += cell
                col++
            }
            y += cell
            row++
        }

        if (bmp == null || bmp.isRecycled) return
        if (!fitDone && width > 0 && height > 0) {
            fitToTexture()
        }
        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scale, scale)

        canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), checkerPaintB)
        canvas.drawBitmap(bmp, 0f, 0f, texturePaint)
        canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), borderPaint())

        // Sobreposição UV (finas, semitransparentes — requisito) + realce.
        val segments = uvSegments
        if (segments != null && segments.isNotEmpty()) {
            if (uvPath == null || cachedSegmentsKey != segments.size * 31 + surfaceMode.ordinal) {
                uvPath = buildPath(segments, bmp.width.toFloat(), bmp.height.toFloat(), closed = false)
                cachedSegmentsKey = segments.size * 31 + surfaceMode.ordinal
            }
            uvPath?.let { canvas.drawPath(it, uvPaint) }
        }
        val highlight = islandHighlightSegments
        if (highlight != null && highlight.isNotEmpty()) {
            if (highlightPath == null || highlightKeyChanged(highlight)) {
                highlightPath = buildPath(highlight, bmp.width.toFloat(), bmp.height.toFloat(), closed = false)
                highlightFillPath = buildPath(highlight, bmp.width.toFloat(), bmp.height.toFloat(), closed = true)
                highlightCacheKey = highlight.size * 17 + highlight.contentHashCode()
            }
            highlightFillPath?.let { canvas.drawPath(it, islandFillPaint) }
            highlightPath?.let { canvas.drawPath(it, islandPaint) }
        }
        canvas.restore()
    }

    private var highlightCacheKey = 0
    private fun highlightKeyChanged(segments: FloatArray): Boolean =
        highlightCacheKey != segments.size * 17 + segments.contentHashCode()

    private fun borderPaint(): Paint = Paint().apply {
        color = Color.argb(120, 158, 158, 158)
        style = Paint.Style.STROKE
        strokeWidth = 1f / scale
        isAntiAlias = false
    }

    private fun buildPath(
        segments: FloatArray,
        texW: Float,
        texH: Float,
        closed: Boolean
    ): Path {
        val path = Path()
        var i = 0
        while (i + 3 < segments.size) {
            val x0 = segments[i] * texW
            val y0 = segments[i + 1] * texH
            val x1 = segments[i + 2] * texW
            val y1 = segments[i + 3] * texH
            path.moveTo(x0, y0)
            path.lineTo(x1, y1)
            if (closed && i + 7 < segments.size) {
                // Fecha triângulos em pares (0-1, 1-2, 2-0): usa o 3.º segmento.
                path.lineTo(segments[i + 4] * texW, segments[i + 5] * texH)
                path.close()
                i += 6
                continue
            }
            i += 4
        }
        return path
    }

    // ---------------------------- gestos -----------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.pointerCount) {
            1 -> {
                if (multiTouch) {
                    // Gestão de 2 dedos acabou: reinicia gesto de 1 dedo.
                    multiTouch = false
                    if (painting) {
                        painting = false
                        onPaintEnd?.invoke()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                    activePointerId = event.getPointerId(0)
                    return true
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activePointerId = event.getPointerId(0)
                        lastTouchX = event.x
                        lastTouchY = event.y
                        val (px, py) = toPixel(event.x, event.y)
                        if (surfaceMode == SurfaceMode.PAINT) {
                            painting = true
                            onPaintBegin?.invoke(px, py)
                        } else {
                            val bw = textureBitmap?.width?.toFloat() ?: 1f
                            val bh = textureBitmap?.height?.toFloat() ?: 1f
                            onUvTap?.invoke(px / bw.coerceAtLeast(1f), py / bh.coerceAtLeast(1f))
                        }
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (painting && event.findPointerIndex(activePointerId) == 0) {
                            val (px, py) = toPixel(event.x, event.y)
                            onPaintMove?.invoke(px, py)
                            lastTouchX = event.x
                            lastTouchY = event.y
                            return true
                        }
                        // Pan com 1 dedo no modo UV (navegação).
                        if (surfaceMode == SurfaceMode.UV_SELECT) {
                            transX += event.x - lastTouchX
                            transY += event.y - lastTouchY
                            lastTouchX = event.x
                            lastTouchY = event.y
                            clampTransform()
                            invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (painting) {
                            painting = false
                            onPaintEnd?.invoke()
                        }
                        return true
                    }
                }
                return true
            }
            else -> {
                // 2 dedos: zoom + pan (requisito da vista UV). Pintura cancelada.
                multiTouch = true
                if (painting) {
                    painting = false
                    onPaintEnd?.invoke()
                }
                scaleDetector.onTouchEvent(event)
                if (event.pointerCount == 2 && !scaleDetector.isInProgress) {
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    if (lastTouchX != 0f || lastTouchY != 0f) {
                        transX += cx - lastTouchX
                        transY += cy - lastTouchY
                    }
                    lastTouchX = cx
                    lastTouchY = cy
                    clampTransform()
                    invalidate()
                } else if (event.pointerCount == 2 && scaleDetector.isInProgress) {
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    lastTouchX = cx
                    lastTouchY = cy
                }
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    lastTouchX = 0f
                    lastTouchY = 0f
                }
                return true
            }
        }
    }

    // ---------------------------- utilitários -------------------------------

    private fun toPixel(sx: Float, sy: Float): Pair<Int, Int> {
        val bmp = textureBitmap ?: return Pair(-1, -1)
        val px = ((sx - transX) / scale).toInt()
        val py = ((sy - transY) / scale).toInt()
        return Pair(px, py)
    }

    /** Ajuste inicial: textura centrada (fit) na vista. */
    fun fitToTexture() {
        val bmp = textureBitmap ?: return
        if (width <= 0 || height <= 0 || bmp.width <= 0 || bmp.height <= 0) return
        scale = minOf(
            width.toFloat() / bmp.width,
            height.toFloat() / bmp.height
        ).coerceIn(MIN_SCALE, MAX_SCALE)
        transX = (width - bmp.width * scale) / 2f
        transY = (height - bmp.height * scale) / 2f
        fitDone = true
        clampTransform()
        invalidate()
    }

    private fun clampTransform() {
        val bmp = textureBitmap ?: return
        val contentW = bmp.width * scale
        val contentH = bmp.height * scale
        val marginX = (width - contentW).coerceAtLeast(0f) / 2f
        val marginY = (height - contentH).coerceAtLeast(0f) / 2f
        transX = if (contentW <= width) marginX else transX.coerceIn(width - contentW, 0f)
        transY = if (contentH <= height) marginY else transY.coerceIn(height - contentH, 0f)
    }

    /** Nova textura (dimensões diferentes): refaz o fit. */
    fun resetFit() {
        fitDone = false
    }

    companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 64f
    }
}
