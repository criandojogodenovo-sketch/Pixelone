package com.pixelone.studio.ui.texture3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.max
import kotlin.math.min

/**
 * Superfície 2D de textura/UV do 3D Texture Studio (Fase 3).
 *
 * Usada em DOIS modos (requisitos da Fase 3):
 *  - VISTA UV: textura com as linhas UV sobrepostas (finas, semitransparentes,
 *    interruptor na UI), zoom/deslocamento com 2 dedos e TOQUE (curto, sem
 *    arrastar) que seleciona a ilha UV tocada ([onUvTap] → ViewModel → realce
 *    no 3D);
 *  - PINTURA (modo Dividido): 1 dedo pinta (via [PixelEngine] no ViewModel,
 *    com histórico e ATUALIZAÇÃO PARCIAL da GPU), 2 dedos navegam.
 *
 * RENDERIZAÇÃO: desenho por Canvas com Paint(isFilterBitmap=false) — os
 * píxeis da textura aparecem SEM suavização (identidade pixel art). O bitmap
 * vem do ViewModel (apresentação) e é atualizado REGIONALMENTE por traço
 * (Bitmap.setPixels da região suja) + [invalidate] — nunca recriado.
 *
 * CORREÇÕES RONDA A (F, G, H + Focar):
 *  - LINHAS UV FINAS (1 px DE ECRÃ): o path é desenhado dentro de um canvas
 *    escalado — o strokeWidth passa a 1f/scale, senão as linhas multiplicam
 *    pelo zoom (a 64× ficavam com 64 px). Anti-alias desligado: linha nítida
 *    de 1 px, coerente com a identidade pixel art.
 *  - REALCE POR TRIÂNGULO: os segmentos de realce chegam em TRIPLOS de
 *    vértice (ax,ay,bx,by,cx,cy) e geram UM path fechado usado para FILL e
 *    STROKE — o algoritmo antigo consumia os floats desalinhados e produzia
    *    triângulos degenerados (etiqueta "12 tri." com 2 visíveis).
 *  - GRELHA DE PÍXEIS: com zoom ≥ [GRID_MIN_SCALE] desenha-se uma grelha 1 px
 *    alinhada aos píxeis da textura (requisito do botão "Focar").
 *  - FOCAR: [focusOnIsland] faz zoom fit à caixa UV da ilha selecionada.
 *  - TOQUE vs ARRASTAR: em UV_SELECT o toque só seleciona se o dedo não se
 *    mexer mais do que o touchSlop — antes, TODO o início de pan selecionava.
 */
class TexturePaintView(context: Context) : View(context) {

    /** Modo de utilização da superfície. */
    enum class SurfaceMode { UV_SELECT, PAINT }

    // ---------------------------- dados (set pela UI) ----------------------

    var textureBitmap: Bitmap? = null
    var uvSegments: FloatArray? = null

    /**
     * Segmentos da ilha selecionada em TRIPLOS de vértice (ax,ay,bx,by,cx,cy)
     * por triângulo — 6 floats por triângulo (ver doc de classe).
     */
    var islandHighlightSegments: FloatArray? = null

    /** Caixa UV (u0,v0,u1,v1) da ilha selecionada — alvo do botão "Focar". */
    var islandUvBounds: FloatArray? = null

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

    /**
     * RONDA A (F): linhas UV FINAS e semitransparentes. O strokeWidth é
     * definido a cada onDraw como 1f/scale — 1 px DE ECRÃ em qualquer zoom.
     * Anti-alias OFF: 1 px nítido e sem escurecimento por sobreposição de AA.
     */
    private val uvPaint = Paint().apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

    private val islandStrokePaint = Paint().apply {
        color = Color.argb(230, 0xFF, 0x7C, 0x00) // laranja Pixel One
        strokeWidth = 2f // 2f/scale em onDraw — 2 px de ecrã
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

    private val islandFillPaint = Paint().apply {
        color = Color.argb(70, 0xFF, 0x7C, 0x00)
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    /** Grelha de píxeis (Focar): 1 px semitransparente, alinhada à textura. */
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f // 1f/scale em onDraw
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

    private val checkerPaintA = Paint().apply { color = Color.argb(255, 34, 34, 34) }
    private val checkerPaintB = Paint().apply { color = Color.argb(255, 22, 22, 22) }

    // OTIMIZAÇÃO (missão texturização): o fundo quadriculado era desenhado
    // CÉLULA A CÉLULA em TODO onDraw — milhares de drawRect por frame em ecrã
    // cheio, a cada invalidação do traço. Agora: padrão 2×2 células
    // pré-renderizado num bitmap 32×32 com BitmapShader REPEAT — um único
    // drawRect com o MESMO aspeto visual (células 16 px, mesmas cores).
    private var checkerTiledPaint: Paint? = null

    private fun checkerTiled(): Paint {
        checkerTiledPaint?.let { return it }
        val cell = 16
        val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), checkerPaintA)
        c.drawRect(cell.toFloat(), 0f, (cell * 2).toFloat(), cell.toFloat(), checkerPaintB)
        c.drawRect(0f, cell.toFloat(), cell.toFloat(), (cell * 2).toFloat(), checkerPaintB)
        c.drawRect(cell.toFloat(), cell.toFloat(), (cell * 2).toFloat(), (cell * 2).toFloat(), checkerPaintA)
        val paint = Paint()
        paint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        checkerTiledPaint = paint
        return paint
    }

    // Paths em cache (por IDENTIDADE do array de origem — o ViewModel devolve
    // instâncias estáveis das caches dele; contentHashCode era O(n) POR FRAME:
    // 3,6 M floats percorridos a cada traço num modelo de 300k triângulos).
    private var uvPath: Path? = null
    private var uvCacheRef: FloatArray? = null
    private var uvCacheTexW = 0f
    private var uvCacheTexH = 0f
    private var islandPath: Path? = null
    private var islandCacheRef: FloatArray? = null
    private var islandCacheTexW = 0f
    private var islandCacheTexH = 0f

    // ------------------------------------------------------------------
    // MISSÃO TEXTURIZAÇÃO (desempenho do traço em UV/Dividido):
    // as guias UV + realce da ilha eram desenhados como PATH (até 900k
    // verbos num modelo de 300k triângulos) EM CADA onDraw — e o traço
    // invalida a vista a cada evento (60–120 Hz): era a causa dos bloqueios
    // com modelos importados. AGORA: o overlay é RASTERIZADO UMA VEZ num
    // bitmap do tamanho da VISTA e o traço faz UM drawBitmap. Rebuild só
    // quando muda: segmentos/realce (identidade), zoom/pan, tamanho da
    // vista ou dimensões da textura — NUNCA durante o traço.
    // ------------------------------------------------------------------
    private var overlayBitmap: Bitmap? = null
    private var overlaySegmentsRef: FloatArray? = null
    private var overlayHighlightRef: FloatArray? = null
    private var overlayScale = -1f
    private var overlayTransX = Float.NaN
    private var overlayTransY = Float.NaN
    private var overlayViewW = 0
    private var overlayViewH = 0
    private var overlayTexW = 0
    private var overlayTexH = 0

    private fun uvPathFor(segments: FloatArray, texW: Float, texH: Float): Path {
        val cached = uvPath
        if (cached != null && uvCacheRef === segments && uvCacheTexW == texW && uvCacheTexH == texH) {
            return cached
        }
        val built = buildWirePath(segments, texW, texH)
        uvPath = built
        uvCacheRef = segments
        uvCacheTexW = texW
        uvCacheTexH = texH
        return built
    }

    private fun islandPathFor(triples: FloatArray, texW: Float, texH: Float): Path {
        val cached = islandPath
        if (cached != null && islandCacheRef === triples && islandCacheTexW == texW && islandCacheTexH == texH) {
            return cached
        }
        val built = buildIslandPath(triples, texW, texH)
        islandPath = built
        islandCacheRef = triples
        islandCacheTexW = texW
        islandCacheTexH = texH
        return built
    }

    /**
     * Devolve o overlay rasterizado (guias UV + realce) para a transformação
     * e dados atuais — reconstruindo-o SÓ quando a chave muda. null se não
     * há nada a desenhar (sem guias nem realce) ou vista sem dimensões.
     */
    private fun ensureOverlay(bmp: Bitmap): Bitmap? {
        val segments = uvSegments
        val highlight = islandHighlightSegments
        val hasContent = (segments != null && segments.isNotEmpty()) ||
            (highlight != null && highlight.isNotEmpty())
        if (!hasContent || width <= 0 || height <= 0) {
            overlaySegmentsRef = segments
            overlayHighlightRef = highlight
            return null
        }
        val sameKey =
            overlaySegmentsRef === segments &&
            overlayHighlightRef === highlight &&
            overlayScale == scale &&
            overlayTransX == transX && overlayTransY == transY &&
            overlayViewW == width && overlayViewH == height &&
            overlayTexW == bmp.width && overlayTexH == bmp.height
        var out = overlayBitmap
        if (sameKey && out != null && !out.isRecycled) return out

        if (out == null || out.width != width || out.height != height) {
            out?.recycle()
            out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            overlayBitmap = out
        }
        out.eraseColor(Color.TRANSPARENT)
        val texW = bmp.width.toFloat()
        val texH = bmp.height.toFloat()
        val c = Canvas(out)
        c.save()
        c.translate(transX, transY)
        c.scale(scale, scale)
        if (segments != null && segments.isNotEmpty()) {
            uvPaint.strokeWidth = 1f / scale
            c.drawPath(uvPathFor(segments, texW, texH), uvPaint)
        }
        if (highlight != null && highlight.isNotEmpty()) {
            islandStrokePaint.strokeWidth = 2f / scale
            val p = islandPathFor(highlight, texW, texH)
            c.drawPath(p, islandFillPaint)
            c.drawPath(p, islandStrokePaint)
        }
        c.restore()
        overlaySegmentsRef = segments
        overlayHighlightRef = highlight
        overlayScale = scale
        overlayTransX = transX
        overlayTransY = transY
        overlayViewW = width
        overlayViewH = height
        overlayTexW = bmp.width
        overlayTexH = bmp.height
        return out
    }

    // ---------------------------- estado de gesto --------------------------

    private var painting = false
    private var multiTouch = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1

    /** Deteção de TOQUE (vs arrastar) na vista UV — requisito Ronda A. */
    private var tapCandidate = false
    private var tapStartX = 0f
    private var tapStartY = 0f
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

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

        // Fundo quadriculado (nunca entra na textura/exportação) — UM drawRect
        // com padrão em tile (antes: loop célula a célula a cada frame).
        canvas.drawRect(0f, 0f, w, h, checkerTiled())

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

        // Grelha de píxeis (requisito "Focar"): só com zoom suficiente — a
        // textura de 256 px a escala 8 são 512 linhas 1 px, barato e raro.
        if (scale >= GRID_MIN_SCALE) {
            drawPixelGrid(canvas, bmp.width.toFloat(), bmp.height.toFloat())
        }

        canvas.restore()

        // Sobreposição UV + realce da ilha: UM drawBitmap do overlay
        // rasterizado (antes: drawPath de até 900k verbos POR FRAME do traço
        // — a causa dos bloqueios com modelos importados). O overlay JÁ TEM
        // a transformação incorporada (espaço da VISTA) — desenhado FORA do
        // save/translate/scale, ao contrário dos paths antigos.
        val overlay = ensureOverlay(bmp)
        if (overlay != null) {
            canvas.drawBitmap(overlay, 0f, 0f, null)
        }
    }

    /** Grelha 1 px alinhada aos píxeis da textura (limites de cada píxel). */
    private fun drawPixelGrid(canvas: Canvas, texW: Float, texH: Float) {
        var gx = 0f
        while (gx <= texW) {
            canvas.drawLine(gx, 0f, gx, texH, gridPaint)
            gx += 1f
        }
        var gy = 0f
        while (gy <= texH) {
            canvas.drawLine(0f, gy, texW, gy, gridPaint)
            gy += 1f
        }
    }

    /** Borda da textura — Paint em cache (antes: alocado em CADA onDraw). */
    private val borderStrokePaint = Paint().apply {
        color = Color.argb(120, 158, 158, 158)
        style = Paint.Style.STROKE
        isAntiAlias = false
    }

    private fun borderPaint(): Paint {
        borderStrokePaint.strokeWidth = 1f / scale
        return borderStrokePaint
    }

    /**
     * Path da sobreposição UV: pares de pontos (x0,y0,x1,y1) em UV — linhas
     * abertas (sem fechar triângulos), para não duplicar arestas partilhadas.
     */
    private fun buildWirePath(segments: FloatArray, texW: Float, texH: Float): Path {
        val path = Path()
        var i = 0
        while (i + 3 < segments.size) {
            path.moveTo(segments[i] * texW, segments[i + 1] * texH)
            path.lineTo(segments[i + 2] * texW, segments[i + 3] * texH)
            i += 4
        }
        return path
    }

    /**
     * Path de realce a partir de TRIPLOS de vértice (6 floats por triângulo):
     * moveTo(a) → lineTo(b) → lineTo(c) → close. Um só path serve o FILL e o
     * STROKE — o antigo algoritmo "closed" consumia i+=6 floats (meio
     * triângulo) e gerava a→b→b degenerado (invisível).
     */
    private fun buildIslandPath(triples: FloatArray, texW: Float, texH: Float): Path {
        val path = Path()
        var i = 0
        while (i + 5 < triples.size) {
            path.moveTo(triples[i] * texW, triples[i + 1] * texH)
            path.lineTo(triples[i + 2] * texW, triples[i + 3] * texH)
            path.lineTo(triples[i + 4] * texW, triples[i + 5] * texH)
            path.close()
            i += 6
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
                    tapCandidate = false
                    return true
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activePointerId = event.getPointerId(0)
                        lastTouchX = event.x
                        lastTouchY = event.y
                        tapCandidate = true
                        tapStartX = event.x
                        tapStartY = event.y
                        if (surfaceMode == SurfaceMode.PAINT) {
                            val (px, py) = toPixel(event.x, event.y)
                            painting = true
                            onPaintBegin?.invoke(px, py)
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
                        // RONDA A: se o dedo passou o touchSlop deixou de ser
                        // toque — passa a pan (na vista UV).
                        if (tapCandidate) {
                            val dx = event.x - tapStartX
                            val dy = event.y - tapStartY
                            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                                tapCandidate = false
                            }
                        }
                        if (surfaceMode == SurfaceMode.UV_SELECT && !tapCandidate) {
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
                        // TOQUE real: só UP curto e sem arrasto seleciona (G).
                        if (surfaceMode == SurfaceMode.UV_SELECT &&
                            tapCandidate && event.actionMasked == MotionEvent.ACTION_UP
                        ) {
                            val (px, py) = toPixel(event.x, event.y)
                            val bw = textureBitmap?.width?.toFloat() ?: 1f
                            val bh = textureBitmap?.height?.toFloat() ?: 1f
                            onUvTap?.invoke(px / bw.coerceAtLeast(1f), py / bh.coerceAtLeast(1f))
                        }
                        tapCandidate = false
                        return true
                    }
                }
                return true
            }
            else -> {
                // 2 dedos: zoom + pan (requisito da vista UV). Pintura cancelada.
                multiTouch = true
                tapCandidate = false
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

    /**
     * FOCAR (requisito Ronda A): zoom automático à caixa UV da ilha
     * selecionada (u0,v0,u1,v1), centrada com 10% de respiro. Com o zoom
     * resultante ≥ [GRID_MIN_SCALE] a grelha de píxeis fica visível.
     */
    fun focusOnIsland(u0: Float, v0: Float, u1: Float, v1: Float) {
        val bmp = textureBitmap ?: return
        if (width <= 0 || height <= 0 || bmp.width <= 0 || bmp.height <= 0) return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val padU = max(0.04f, (u1 - u0) * 0.1f)
        val padV = max(0.04f, (v1 - v0) * 0.1f)
        val boxW = max(1e-4f, (u1 - u0) + 2f * padU) * bw
        val boxH = max(1e-4f, (v1 - v0) + 2f * padV) * bh
        val fitScale = min(width / boxW, height / boxH).coerceIn(MIN_SCALE, MAX_SCALE)
        scale = fitScale
        val cxUv = (u0 + u1) / 2f * bw
        val cyUv = (v0 + v1) / 2f * bh
        transX = width / 2f - cxUv * scale
        transY = height / 2f - cyUv * scale
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

        /** Zoom mínimo para desenhar a grelha de píxeis (evita 1000s de linhas). */
        const val GRID_MIN_SCALE = 6f
    }
}
