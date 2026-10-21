package com.pixelone.studio.studio3d

/**
 * Retângulo sujo (dirty rect) do 3D Texture Studio — Kotlin puro.
 *
 * USO PRINCIPAL (modo Dividido, requisito da Fase 3): enquanto se pinta a
 * textura, cada traço/fase marca a região alterada e SÓ ESSA REGIÃO é
 * reenviada à GPU com glTexSubImage2D — NUNCA a textura inteira por traço.
 * O acumulador une retângulos (união) e limita-os ao documento, de modo que
 * várias atualizações rápidas do mesmo gesto coalescem numa única subida.
 */
data class DirtyRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    /** Largura em píxeis (>= 1). */
    val width: Int get() = x1 - x0 + 1
    /** Altura em píxeis (>= 1). */
    val height: Int get() = y1 - y0 + 1
    val area: Long get() = width.toLong() * height.toLong()

    init {
        require(x0 <= x1 && y0 <= y1) { "DirtyRect invertido: ($x0,$y0)-($x1,$y1)" }
    }

    /** União de dois retângulos. */
    fun union(other: DirtyRect): DirtyRect = DirtyRect(
        minOf(x0, other.x0), minOf(y0, other.y0),
        maxOf(x1, other.x1), maxOf(y1, other.y1)
    )

    /** Limita o retângulo a (0,0)-(w-1,h-1); devolve null se ficou TODO fora. */
    fun clamped(width: Int, height: Int): DirtyRect? {
        // Visibilidade ANTES do clamp: um retângulo fora do documento devolve
        // null (e nunca um retângulo degenerado no canto).
        if (x1 < 0 || y1 < 0 || x0 >= width || y0 >= height) return null
        val nx0 = x0.coerceIn(0, width - 1)
        val ny0 = y0.coerceIn(0, height - 1)
        val nx1 = x1.coerceIn(0, width - 1)
        val ny1 = y1.coerceIn(0, height - 1)
        if (nx0 > nx1 || ny0 > ny1) return null
        return DirtyRect(nx0, ny0, nx1, ny1)
    }
}

/**
 * Acumulador mutável de retângulos sujos — NÃO é thread-safe por sí só; o
 * ViewModel sincroniza no executor de mutação (mesma disciplina do editor 2D).
 */
class DirtyRectAccumulator {
    private var current: DirtyRect? = null

    /** Marca um píxel. */
    fun addPixel(x: Int, y: Int) = add(x, y, x, y)

    /** Marca um retângulo inclusivo (qualquer ordem dos cantos é aceite). */
    fun add(xa: Int, ya: Int, xb: Int, yb: Int) {
        val rect = DirtyRect(minOf(xa, xb), minOf(ya, yb), maxOf(xa, xb), maxOf(ya, yb))
        current = current?.union(rect) ?: rect
    }

    /** Marca a região de um traço com espessura de pincel (com margem). */
    fun addStroke(xa: Int, ya: Int, xb: Int, yb: Int, brushSize: Int) {
        val margin = (brushSize.coerceAtLeast(1) + 1) / 2
        add(xa - margin, ya - margin, xb + margin, yb + margin)
    }

    /** União acumulada até agora (ou null se nada sujo). */
    fun peek(): DirtyRect? = current

    /** Lê e limpa — a subida parcial "consome" a região suja. */
    fun take(width: Int, height: Int): DirtyRect? {
        val rect = current?.clamped(width, height)
        current = null
        return rect
    }

    fun reset() { current = null }
}
