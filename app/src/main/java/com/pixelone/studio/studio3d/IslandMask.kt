package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.UvIslands.UvIsland

/**
 * MÁSCARA DE ILHA para pintura com bloqueio (Ronda B) — Kotlin puro.
 *
 * Rasteriza os TRIÂNGULOS da ilha UV numa grelha booleana do tamanho da
 * textura (amostragem no CENTRO de cada texel — consistente com o mapeamento
 * uvToTexel de [PaintCore] e com a vista UV do estúdio) e aplica DILATAÇÃO
 * (padding) para a pintura cobrir ligeiramente além da aresta — evita
 * "linhas de costura" nas arestas do 3D por amostragem Nearest entre ilhas.
 *
 * CONVENÇÃO DE EIXOS: u → coluna (x), v → linha (y), v cresce para BAIXO —
 * a mesma usada em toda a Fase 3 (glTF + vista UV do estúdio).
 *
 * MEMÓRIA: 1 byte por texel (textura 2048² = 4 MB no pior caso, só enquanto
 * houver seleção) — seguro para aparelhos de 4 GB.
 */
class IslandMask private constructor(
    val width: Int,
    val height: Int,
    private val mask: ByteArray,
    val paddingUsed: Int
) {
    /** true se o texel (x, y) está dentro da ilha (com padding aplicado). */
    fun contains(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height &&
            mask[y * width + x].toInt() != 0

    /** Nº de texels ativos (diagnóstico/testes). */
    fun activeCount(): Int {
        var n = 0
        for (b in mask) if (b.toInt() != 0) n++
        return n
    }

    /** Caixa delimitadora dos texels ativos (x0,y0,x1,y1) ou null se vazia. */
    fun bounds(): IntArray? {
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (mask[base + x].toInt() != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < minX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    companion object {

        /** Padding máximo (px) — protege contra dilatações absurdas. */
        const val MAX_PADDING: Int = 8

        /**
         * Constrói a máscara da ilha. Padding 0 = apenas os texels cujo
         * centro cai dentro dos triângulos.
         */
        fun build(
            indices: IntArray,
            uvs: FloatArray,
            island: UvIsland,
            texWidth: Int,
            texHeight: Int,
            paddingPx: Int
        ): IslandMask {
            require(texWidth >= 1 && texHeight >= 1) { "Textura inválida" }
            val mask = ByteArray(texWidth * texHeight)

            for (t in island.triangleIndices) {
                val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
                val ax = uvs[a * 2]; val ay = uvs[a * 2 + 1]
                val bx = uvs[b * 2]; val by = uvs[b * 2 + 1]
                val cx = uvs[c * 2]; val cy = uvs[c * 2 + 1]
                // BBox em texels (centro do texel (x) está em (x+0.5)/w).
                val minU = minOf(ax, bx, cx); val maxU = maxOf(ax, bx, cx)
                val minV = minOf(ay, by, cy); val maxV = maxOf(ay, by, cy)
                val x0 = ((minU * texWidth).toInt()).coerceIn(0, texWidth - 1)
                val x1 = ((maxU * texWidth).toInt()).coerceIn(0, texWidth - 1)
                val y0 = ((minV * texHeight).toInt()).coerceIn(0, texHeight - 1)
                val y1 = ((maxV * texHeight).toInt()).coerceIn(0, texHeight - 1)
                val d1 = sign(bx, by, cx, cy, ax, ay)
                val d2 = sign(cx, cy, ax, ay, bx, by)
                val d3 = sign(ax, ay, bx, by, cx, cy)
                val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
                val hasPos = d1 > 0 || d2 > 0 || d3 > 0
                val degenerate = !hasNeg && !hasPos
                for (y in y0..y1) {
                    val py = (y + 0.5f) / texHeight
                    val base = y * texWidth
                    for (x in x0..x1) {
                        val px = (x + 0.5f) / texWidth
                        if (degenerate) continue
                        val e1 = sign(bx, by, cx, cy, px, py)
                        val e2 = sign(cx, cy, ax, ay, px, py)
                        val e3 = sign(ax, ay, bx, by, px, py)
                        val neg = e1 < 0 || e2 < 0 || e3 < 0
                        val pos = e1 > 0 || e2 > 0 || e3 > 0
                        if (!(neg && pos)) mask[base + x] = 1
                    }
                }
            }

            val padding = paddingPx.coerceIn(0, MAX_PADDING)
            if (padding > 0) dilate(mask, texWidth, texHeight, padding)
            return IslandMask(texWidth, texHeight, mask, padding)
        }

        /**
         * Dilatação por distância de Chebyshev ≤ padding (bola quadrada):
         * duas passagens separáveis (horizontal, depois vertical) — O(n).
         */
        private fun dilate(mask: ByteArray, w: Int, h: Int, padding: Int) {
            val tmp = ByteArray(mask.size)
            // Horizontal
            for (y in 0 until h) {
                val base = y * w
                var last = -1
                val dist = IntArray(w) { Int.MAX_VALUE } // distância à esquerda mais próxima
                for (x in 0 until w) {
                    if (mask[base + x].toInt() != 0) last = x
                    dist[x] = if (last >= 0) x - last else Int.MAX_VALUE
                }
                last = -1
                for (x in w - 1 downTo 0) {
                    if (mask[base + x].toInt() != 0) last = x
                    val dr = if (last >= 0) last - x else Int.MAX_VALUE
                    val d = minOf(dist[x], dr)
                    if (d <= padding) tmp[base + x] = 1
                }
            }
            // Vertical
            for (x in 0 until w) {
                var last = -1
                val dist = IntArray(h) { Int.MAX_VALUE }
                for (y in 0 until h) {
                    if (tmp[y * w + x].toInt() != 0) last = y
                    dist[y] = if (last >= 0) y - last else Int.MAX_VALUE
                }
                last = -1
                for (y in h - 1 downTo 0) {
                    if (tmp[y * w + x].toInt() != 0) last = y
                    val dd = if (last >= 0) last - y else Int.MAX_VALUE
                    if (minOf(dist[y], dd) <= padding) mask[y * w + x] = 1
                }
            }
        }

        /** Produto vetorial 2D com sinal (aresta ab→p). */
        private fun sign(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float =
            (bx - ax) * (py - ay) - (by - ay) * (px - ax)
    }
}
