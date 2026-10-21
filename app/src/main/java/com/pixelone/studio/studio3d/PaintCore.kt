package com.pixelone.studio.studio3d

import com.pixelone.studio.pixel.PixelDocument
import com.pixelone.studio.pixel.PixelEngine
import com.pixelone.studio.pixel.PixelEngine.StrokeRecorder

/**
 * NÚCLEO DE PINTURA da Ronda B (Fase 3 · pintura no 3D e na UV) — Kotlin puro
 * (testável em JVM, sem APIs Android).
 *
 * RESPONSABILIDADES:
 *  - Mapeamento UV → texel (a MESMA convenção da vista UV: u→x, v→y, v para
 *    baixo; texel (x,y) cobre [x,x+1)×[y,y+1) no espaço da textura);
 *  - UV no ponto de impacto 3D (baricêntricas — pintar no 3D dá EXATAMENTE o
 *    mesmo resultado de pintar na UV);
 *  - Carimbo do pincel com MÁSCARA DE ILHA (nada fora da máscara muda) e
 *    SIMETRIA horizontal/vertical/ambas (espelhada pela ILHA quando há
 *    seleção, ou pela TEXTURA — o chamador indica e a UI mostra qual);
 *  - Linha (Bresenham, sem falhas) e retângulo (contorno);
 *  - Balde de preenchimento com limite de máscara (BFS 4-conectado).
 *
 * MISTURA: substituição exata do valor do píxel (igual ao motor 2D do projeto
 * — decisão documentada no [PixelEngine]); a borracha é a cor 0x00000000.
 */
object PaintCore {

    /** Ferramentas da Ronda B. */
    enum class Tool { PENCIL, ERASER, FILL, EYEDROPPER, LINE, RECT }

    /** Eixo(s) de simetria. */
    enum class Symmetry { NONE, HORIZONTAL, VERTICAL, BOTH }

    /**
     * Base do espelho: pela ILHA (caixa da máscara ativa — só faz sentido com
     * seleção) ou pela TEXTURA inteira. A UI indica qual está em uso.
     */
    enum class MirrorBase { ISLAND, TEXTURE }

    /** Caixa suja mutável (bbox dos píxeis efetivamente alterados). */
    class BBox {
        var minX = 0; var maxX = -1; var minY = 0; var maxY = -1
        val isEmpty: Boolean get() = maxX < minX

        fun add(x: Int, y: Int) {
            if (isEmpty) { minX = x; maxX = x; minY = y; maxY = y } else {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        fun union(o: BBox) { if (!o.isEmpty) { add(o.minX, o.minY); add(o.maxX, o.maxY) } }
    }

    // ------------------------------------------------------------------
    // UV → texel
    // ------------------------------------------------------------------

    /**
     * Texel que contém a coordenada u (coluna): piso de u·w, recortado aos
     * limites. u=1.0 mapeia para o último texel (w-1) — a borda direita.
     */
    fun uvToTexelX(u: Float, texWidth: Int): Int =
        Math.floor(u * texWidth.toDouble()).toInt().coerceIn(0, texWidth - 1)

    /** Texel que contém a coordenada v (linha). */
    fun uvToTexelY(v: Float, texHeight: Int): Int =
        Math.floor(v * texHeight.toDouble()).toInt().coerceIn(0, texHeight - 1)

    /** Par (x, y) em texels para (u, v). */
    fun uvToTexel(u: Float, v: Float, texWidth: Int, texHeight: Int): IntArray =
        intArrayOf(uvToTexelX(u, texWidth), uvToTexelY(v, texHeight))

    /**
     * UV interpolado (baricêntricas) no ponto de impacto 3D [p] sobre o
     * triângulo (a, b, c) — é isto que faz pintar NO 3D coincidir com pintar
     * NA UV. Devolve (u, v) ou null se o triângulo for degenerado.
     */
    fun uvAtHit(
        p: FloatArray,
        positions: FloatArray, idxA: Int, idxB: Int, idxC: Int,
        uvs: FloatArray
    ): FloatArray? {
        val ax = positions[idxA * 3]; val ay = positions[idxA * 3 + 1]; val az = positions[idxA * 3 + 2]
        val bx = positions[idxB * 3]; val by = positions[idxB * 3 + 1]; val bz = positions[idxB * 3 + 2]
        val cx = positions[idxC * 3]; val cy = positions[idxC * 3 + 1]; val cz = positions[idxC * 3 + 2]
        // Baricêntricas clássicas por produtos internos (exatas para p no
        // plano do triângulo — o ponto de impacto está nesse plano):
        //   v0 = b−a, v1 = c−a, v2 = p−a
        //   β = (d11·d20 − d01·d21)/denom, γ = (d00·d21 − d01·d20)/denom
        val v0x = bx - ax; val v0y = by - ay; val v0z = bz - az
        val v1x = cx - ax; val v1y = cy - ay; val v1z = cz - az
        val v2x = p[0] - ax; val v2y = p[1] - ay; val v2z = p[2] - az
        fun dot(ax1: Float, ay1: Float, az1: Float, bx1: Float, by1: Float, bz1: Float): Float =
            ax1 * bx1 + ay1 * by1 + az1 * bz1
        val d00 = dot(v0x, v0y, v0z, v0x, v0y, v0z)
        val d01 = dot(v0x, v0y, v0z, v1x, v1y, v1z)
        val d11 = dot(v1x, v1y, v1z, v1x, v1y, v1z)
        val d20 = dot(v2x, v2y, v2z, v0x, v0y, v0z)
        val d21 = dot(v2x, v2y, v2z, v1x, v1y, v1z)
        val denom = d00 * d11 - d01 * d01
        if (Math.abs(denom) <= 1e-20f) return null // triângulo degenerado
        val beta = (d11 * d20 - d01 * d21) / denom
        val gamma = (d00 * d21 - d01 * d20) / denom
        val alpha = 1f - beta - gamma
        if (alpha < -1e-3f || beta < -1e-3f || gamma < -1e-3f) return null
        val u = alpha * uvs[idxA * 2] + beta * uvs[idxB * 2] + gamma * uvs[idxC * 2]
        val v = alpha * uvs[idxA * 2 + 1] + beta * uvs[idxB * 2 + 1] + gamma * uvs[idxC * 2 + 1]
        return floatArrayOf(u, v)
    }

    // ------------------------------------------------------------------
    // Simetria
    // ------------------------------------------------------------------

    /**
     * Coordenada x espelhada. Base TEXTURE: espelho pelo centro da textura
     * (x' = w−1−x). Base ISLAND: espelho pela caixa da máscara
     * (x' = (x0+x1)−x) — [islandBounds] = (x0,y0,x1,y1) de [IslandMask.bounds].
     */
    fun mirrorX(x: Int, texWidth: Int, base: MirrorBase, islandBounds: IntArray?): Int =
        if (base == MirrorBase.ISLAND && islandBounds != null) {
            (islandBounds[0] + islandBounds[2]) - x
        } else {
            (texWidth - 1) - x
        }

    /** Coordenada y espelhada (mesma regra de [mirrorX]). */
    fun mirrorY(y: Int, texHeight: Int, base: MirrorBase, islandBounds: IntArray?): Int =
        if (base == MirrorBase.ISLAND && islandBounds != null) {
            (islandBounds[1] + islandBounds[3]) - y
        } else {
            (texHeight - 1) - y
        }

    // ------------------------------------------------------------------
    // Carimbo (com máscara + simetria)
    // ------------------------------------------------------------------

    /**
     * Carimba o pincel em (x, y) — e nas posições espelhadas conforme
     * [symmetry] — respeitando a máscara ([islandMask] null = pintura livre).
     *
     * @return bbox dos píxeis alterados ou null se nada mudou.
     */
    fun stamp(
        doc: PixelDocument,
        x: Int, y: Int,
        color: Int,
        brushSize: Int,
        islandMask: IslandMask?,
        symmetry: Symmetry,
        mirrorBase: MirrorBase,
        recorder: StrokeRecorder?
    ): BBox? {
        val w = doc.width; val h = doc.height
        val size = brushSize.coerceIn(1, PixelEngine.MAX_BRUSH)
        val half = (size - 1) / 2
        val bbox = BBox()

        val islandBounds = islandMask?.bounds()
        fun stampOne(px: Int, py: Int) {
            for (dy in 0 until size) {
                for (dx in 0 until size) {
                    val tx = px - half + dx
                    val ty = py - half + dy
                    if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue
                    if (islandMask != null && !islandMask.contains(tx, ty)) continue
                    val before = doc.get(tx, ty)
                    if (before == color) continue
                    recorder?.onBeforeChange(tx, ty, before)
                    doc.set(tx, ty, color)
                    recorder?.onChanged(tx, ty)
                    bbox.add(tx, ty)
                }
            }
        }

        stampOne(x, y)
        if (symmetry != Symmetry.NONE) {
            val doH = symmetry == Symmetry.HORIZONTAL || symmetry == Symmetry.BOTH
            val doV = symmetry == Symmetry.VERTICAL || symmetry == Symmetry.BOTH
            val mx = if (doH) mirrorX(x, w, mirrorBase, islandBounds) else x
            val my = if (doV) mirrorY(y, h, mirrorBase, islandBounds) else y
            if (doH && mx != x) stampOne(mx, y)
            if (doV && my != y) stampOne(x, my)
            if (doH && doV && (mx != x || my != y)) stampOne(mx, my)
        }
        return if (bbox.isEmpty) null else bbox
    }

    // ------------------------------------------------------------------
    // Linha e retângulo
    // ------------------------------------------------------------------

    /**
     * Linha contínua (Bresenham) de (x0,y0) a (x1,y1) com máscara + simetria.
     * Cada ponto do caminho é carimbado — sem falhas em diagonais íngremes
     * (mesma garantia do lápis do motor 2D).
     *
     * @return bbox alterado ou null.
     */
    fun drawLineSegment(
        doc: PixelDocument,
        x0: Int, y0: Int, x1: Int, y1: Int,
        color: Int, brushSize: Int,
        islandMask: IslandMask?, symmetry: Symmetry, mirrorBase: MirrorBase,
        recorder: StrokeRecorder?
    ): BBox? {
        val total = BBox()
        var changed = false
        val dx = Math.abs(x1 - x0)
        val dy = -Math.abs(y1 - y0)
        val stepX = if (x0 < x1) 1 else -1
        val stepY = if (y0 < y1) 1 else -1
        var err = dx + dy
        var x = x0; var y = y0
        while (true) {
            stamp(doc, x, y, color, brushSize, islandMask, symmetry, mirrorBase, recorder)?.let {
                total.union(it); changed = true
            }
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += stepX }
            if (e2 <= dx) { err += dx; y += stepY }
        }
        return if (changed) total else null
    }

    /**
     * Retângulo (CONTOUR) de canto (x0,y0) a (x1,y1) — 4 lados por Bresenham.
     * Com máscara + simetria por ponto (as posições espelhadas também são
     * desenhadas — o retângulo espelhado segue o eixo de simetria).
     *
     * @return bbox alterado ou null.
     */
    fun drawRectOutline(
        doc: PixelDocument,
        x0: Int, y0: Int, x1: Int, y1: Int,
        color: Int, brushSize: Int,
        islandMask: IslandMask?, symmetry: Symmetry, mirrorBase: MirrorBase,
        recorder: StrokeRecorder?
    ): BBox? {
        val total = BBox()
        var changed = false
        fun side(ax: Int, ay: Int, bx: Int, by: Int) {
            drawLineSegment(doc, ax, ay, bx, by, color, brushSize, islandMask, symmetry, mirrorBase, recorder)?.let {
                total.union(it); changed = true
            }
        }
        val lx = minOf(x0, x1); val rx = maxOf(x0, x1)
        val ty = minOf(y0, y1); val by = maxOf(y0, y1)
        side(lx, ty, rx, ty)
        if (by != ty) side(lx, by, rx, by)
        if (rx != lx) { side(lx, ty, lx, by); side(rx, ty, rx, by) }
        return if (changed) total else null
    }

    // ------------------------------------------------------------------
    // Balde com máscara
    // ------------------------------------------------------------------

    /**
     * Preenchimento BFS 4-conectado limitado pela máscara de ilha.
     * - Com máscara: o toque FORA da máscara não faz NADA (devolve 0 — regra
     *   "balde só dentro da ilha, se houver seleção"); dentro, o preenchimento
     *   nunca ultrapassa a máscara (nem por tolerância).
     * - Sem máscara: comportamento igual ao [PixelEngine.floodFill].
     *
     * @param indices/before/after buffers de saída (tamanho ≥ nº de píxeis).
     * @return nº de deltas (0 = nada mudou) — o bbox é escrito em [outBBox]
     *         (IntArray(4): x0,y0,x1,y1) quando devolve > 0.
     */
    fun floodFillMasked(
        doc: PixelDocument,
        startX: Int, startY: Int,
        color: Int, tolerance: Int,
        islandMask: IslandMask?,
        indices: IntArray, before: IntArray, after: IntArray,
        outBBox: IntArray
    ): Int {
        val w = doc.width; val h = doc.height
        if (startX !in 0 until w || startY !in 0 until h) return 0
        if (islandMask != null && !islandMask.contains(startX, startY)) return 0
        val tol = tolerance.coerceIn(0, 255)
        val target = doc.get(startX, startY)
        if (target == color) return 0

        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        val ta = (target ushr 24) and 0xFF

        fun matches(argb: Int): Boolean {
            if (argb == color) return false
            if (tol == 0) return argb == target
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val a = (argb ushr 24) and 0xFF
            return Math.abs(r - tr) <= tol && Math.abs(g - tg) <= tol &&
                Math.abs(b - tb) <= tol && Math.abs(a - ta) <= tol
        }

        val total = w * h
        val queue = IntArray(total)
        var head = 0; var tail = 0
        var minX = startX; var maxX = startX
        var minY = startY; var maxY = startY
        var n = 0

        fun enqueue(idx: Int) {
            queue[tail] = idx
            tail = (tail + 1) % total
            val x = idx % w; val y = idx / w
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        fun paint(idx: Int) {
            val b = doc.getAt(idx)
            if (b == color) return
            val x = idx % w; val y = idx / w
            indices[n] = idx; before[n] = b
            doc.setAt(idx, color)
            after[n] = color
            n++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val start = startY * w + startX
        paint(start)
        enqueue(start)

        while (head != tail) {
            val idx = queue[head]
            head = (head + 1) % total
            val x = idx % w; val y = idx / w
            // Vizinhos 4-conectados, sempre dentro da máscara (se ativa).
            val left = idx - 1
            if (x > 0 && matches(doc.getAt(left)) && (islandMask == null || islandMask.contains(x - 1, y))) {
                paint(left); enqueue(left)
            }
            val right = idx + 1
            if (x < w - 1 && matches(doc.getAt(right)) && (islandMask == null || islandMask.contains(x + 1, y))) {
                paint(right); enqueue(right)
            }
            val up = idx - w
            if (y > 0 && matches(doc.getAt(up)) && (islandMask == null || islandMask.contains(x, y - 1))) {
                paint(up); enqueue(up)
            }
            val down = idx + w
            if (y < h - 1 && matches(doc.getAt(down)) && (islandMask == null || islandMask.contains(x, y + 1))) {
                paint(down); enqueue(down)
            }
        }
        if (n > 0) {
            outBBox[0] = minX; outBBox[1] = minY; outBBox[2] = maxX; outBBox[3] = maxY
        }
        return n
    }
}
