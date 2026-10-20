package com.pixelone.studio.pixel

/**
 * Documento 2D do Pixel One: um buffer RGBA de 32 bits por píxel
 * (formato ARGB, igual ao formato "color int" do Android), row-major.
 *
 * DECISÃO DE ARQUITETURA (Fases 1+2):
 * - Esta classe é Kotlin PURO (sem dependências Android) para que todo o
 *   motor de desenho seja testável em JVM sem dispositivo nem Robolectric.
 * - A cor de cada píxel é um Int ARGB: 0xAARRGGBB. Transparência real é
 *   suportada (alpha 0x00 = totalmente transparente).
 * - O tamanho LÓGICO da imagem ([width] x [height]) é independente do
 *   tamanho VISUAL do canvas no ecrã (tratado pela camada de UI/câmera).
 *
 * LIMITES E PROTEÇÃO CONTRA ALOCAÇÕES EXCESSIVAS:
 * - Dimensões válidas: 1..[MAX_DIMENSION] por eixo.
 * - Número máximo de píxeis: [MAX_PIXELS] (4 194 304 = 2048x2048), o que
 *   corresponde a ~16 MB por buffer RGBA — orçamento seguro para um
 *   telemóvel de ~4 GB de RAM mesmo com snapshots do histórico.
 * - Qualquer violação lança [IllegalArgumentException] ANTES de qualquer
 *   alocação de memória (validação no construtor).
 */
class PixelDocument(
    val width: Int,
    val height: Int,
    initialPixels: IntArray? = null
) {
    init {
        require(width in 1..MAX_DIMENSION) {
            "Largura inválida: $width (permitido 1..$MAX_DIMENSION)"
        }
        require(height in 1..MAX_DIMENSION) {
            "Altura inválida: $height (permitido 1..$MAX_DIMENSION)"
        }
        require(width.toLong() * height.toLong() <= MAX_PIXELS) {
            "Área demasiado grande: ${width}x$height = " +
                "${width.toLong() * height} píxeis (máximo $MAX_PIXELS)"
        }
    }

    /** Buffer ARGB row-major; índice = y * width + x. */
    private val buffer: IntArray =
        if (initialPixels != null) {
            require(initialPixels.size == width * height) {
                "Buffer inicial tem ${initialPixels.size} valores; esperado ${width * height}"
            }
            initialPixels.copyOf()
        } else {
            IntArray(width * height) // 0x00000000 = totalmente transparente
        }

    val pixelCount: Int get() = width * height

    /** Devolve true se (x, y) está dentro dos limites do documento. */
    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Cor ARGB do píxel (x, y); devolve 0 se fora dos limites. */
    fun get(x: Int, y: Int): Int =
        if (inBounds(x, y)) buffer[y * width + x] else 0

    /**
     * Define a cor ARGB do píxel (x, y). Fora dos limites é ignorado
     * silenciosamente (as ferramentas validam antes; isto é uma proteção).
     */
    fun set(x: Int, y: Int, argb: Int) {
        if (inBounds(x, y)) buffer[y * width + x] = argb
    }

    /**
     * Vista do buffer interno para operações regionais do motor (fill,
     * histórico, composição). Evita cópias completas do bitmap por evento
     * de toque — o acesso direto é a base da eficiência do editor.
     */
    fun pixelsView(): IntArray = buffer

    /** Cópia completa do documento (usada em testes e comparações). */
    fun copyPixels(): IntArray = buffer.copyOf()

    /** Copia a região [x0..x1] x [y0..y1] (inclusive, recortada aos limites) para um novo array. */
    fun copyRegion(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val cx0 = maxOf(0, minOf(x0, x1))
        val cy0 = maxOf(0, minOf(y0, y1))
        val cx1 = minOf(width - 1, maxOf(x0, x1))
        val cy1 = minOf(height - 1, maxOf(y0, y1))
        if (cx0 > cx1 || cy0 > cy1) return IntArray(0)
        val rw = cx1 - cx0 + 1
        val rh = cy1 - cy0 + 1
        val out = IntArray(rw * rh)
        for (row in 0 until rh) {
            System.arraycopy(buffer, (cy0 + row) * width + cx0, out, row * rw, rw)
        }
        return out
    }

    /** Escreve [region] (row-major) começando em (x0, y0), com recorte aos limites. */
    fun writeRegion(x0: Int, y0: Int, regionWidth: Int, region: IntArray) {
        require(regionWidth >= 1) { "Largura de região inválida" }
        val rows = region.size / regionWidth
        require(rows * regionWidth == region.size) { "Região inconsistente" }
        for (row in 0 until rows) {
            for (col in 0 until regionWidth) {
                val x = x0 + col
                val y = y0 + row
                if (inBounds(x, y)) buffer[y * width + x] = region[row * regionWidth + col]
            }
        }
    }

    /** Cria um novo documento independente com os mesmos píxeis. */
    fun deepCopy(): PixelDocument = PixelDocument(width, height, buffer)

    /** Codifica os píxeis em bytes RGBA (R,G,B,A) — formato usado no ficheiro .osp. */
    fun toRgbaBytes(): ByteArray {
        val out = ByteArray(pixelCount * 4)
        var o = 0
        for (argb in buffer) {
            out[o] = ((argb shr 16) and 0xFF).toByte() // R
            out[o + 1] = ((argb shr 8) and 0xFF).toByte() // G
            out[o + 2] = (argb and 0xFF).toByte() // B
            out[o + 3] = ((argb ushr 24) and 0xFF).toByte() // A
            o += 4
        }
        return out
    }

    companion object {
        /** Dimensão máxima por eixo (proteção contra alocações excessivas). */
        const val MAX_DIMENSION: Int = 2048

        /**
         * Número máximo de píxeis por documento: 2048x2048 = 4 194 304
         * (~16 MB em RGBA). Regra de memória para telemóveis de ~4 GB RAM.
         */
        const val MAX_PIXELS: Int = MAX_DIMENSION * MAX_DIMENSION

        /** Reconstrói um documento a partir de bytes RGBA (formato .osp). */
        fun fromRgbaBytes(width: Int, height: Int, rgba: ByteArray): PixelDocument {
            require(width >= 1 && height >= 1) { "Dimensões inválidas: ${width}x$height" }
            require(width.toLong() * height.toLong() <= MAX_PIXELS) { "Área demasiado grande" }
            val expected = width.toLong() * height.toLong() * 4L
            require(rgba.size.toLong() == expected) {
                "Dados RGBA têm ${rgba.size} bytes; esperado $expected"
            }
            val pixels = IntArray(width * height)
            var i = 0
            var o = 0
            while (i < pixels.size) {
                val r = rgba[o].toInt() and 0xFF
                val g = rgba[o + 1].toInt() and 0xFF
                val b = rgba[o + 2].toInt() and 0xFF
                val a = rgba[o + 3].toInt() and 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                i++
                o += 4
            }
            return PixelDocument(width, height, pixels)
        }
    }
}
