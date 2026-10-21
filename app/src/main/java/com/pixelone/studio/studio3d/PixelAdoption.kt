package com.pixelone.studio.studio3d

import com.pixelone.studio.pixel.PixelDocument

/**
 * MISSÃO UI/SELEÇÃO — adoção de píxeis externos (Texture Studio 2D, textura
 * embutida do GLB) na pilha de camadas do projeto.
 *
 * Regras (as MESMAS que o [Studio3DViewModel.adoptFromStudio2D] já aplicava —
 * agora extraídas para uma função PURA, partilhada e testável em JVM):
 *  - MESMAS dimensões da textura do projeto: funde os píxeis na camada ATIVA
 *    como UMA entrada de histórico (deltas esparsos — só texels diferentes);
 *  - dimensões DIFERENTES: substituição da pilha por UMA camada (decisão
 *    documentada; nunca silenciosa — os chamadores anunciam via statusMessage).
 */
object PixelAdoption {

    /** Deltas esparsos da fusão (prontos para [com.pixelone.studio.pixel.LayeredHistory.commitDeltas]). */
    class MergeResult(
        val indices: IntArray,
        val before: IntArray,
        val after: IntArray,
        val count: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    /** true se os píxeis cabem na MESMA dimensão da textura do projeto. */
    fun sameDimensions(width: Int, height: Int, docWidth: Int, docHeight: Int): Boolean =
        width == docWidth && height == docHeight

    /**
     * Calcula (sem escrever) os deltas entre a camada dada e os píxeis novos.
     * Devolve null se NADA mudar (entrada de histórico vazia é proibida).
     *
     * @param pixels píxeis ARGB, linha a linha, exatamente width×height.
     */
    fun mergeIntoLayer(
        layer: PixelDocument,
        pixels: IntArray,
        width: Int,
        height: Int
    ): MergeResult? {
        require(pixels.size == width * height) {
            "píxeis (${pixels.size}) não correspondem a ${width}x${height}"
        }
        val n = width * height
        val indices = IntArray(n)
        val before = IntArray(n)
        val after = IntArray(n)
        var count = 0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val newColor = pixels[i]
                val old = layer.getAt(i)
                if (old == newColor) continue
                indices[count] = i
                before[count] = old
                after[count] = newColor
                layer.setAt(i, newColor)
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (count == 0) return null
        return MergeResult(indices, before, after, count, minX, minY, maxX, maxY)
    }
}
