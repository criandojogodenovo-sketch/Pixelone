package com.pixelone.studio.pixel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/**
 * Documento 2D do Pixel One: um buffer RGBA de 32 bits por píxel
 * (formato int ARGB 0xAARRGGBB, igual ao "color int" do Android), row-major.
 *
 * DECISÃO DE ARQUITETURA (correção de performance):
 * - Os píxeis vivem num ÚNICO ByteBuffer.allocateDirect(), partilhado entre
 *   Kotlin e C++17 via JNI (GetDirectBufferAddress no lado nativo). Este
 *   buffer direto é a FONTE DE VERDADE dos píxeis — não existe IntArray
 *   duplicado como segundo documento principal.
 * - FORMATO BINÁRIO (documentado — ver docs/buffer-formato.md):
 *     · cada píxel = 4 bytes que codificam o uint32 0xAARRGGBB;
 *     · ordem de bytes: LITTLE-ENDIAN (ByteOrder.nativeOrder() do Android,
 *       que é little-endian em ARM/x86) — em memória: B,G,R,A por píxel;
 *     · disposição: row-major, índice = y * width + x;
 *     · stride = width * 4 bytes (compacto, sem padding).
 * - O acesso Kotlin usa UMA vista IntBuffer persistente (criada uma vez no
 *   construtor) com acessos ABSOLUTOS (put/get por índice) — nunca se cria
 *   uma vista nova por píxel nem por evento de toque.
 * - VIDA ÚTIL: o buffer vive enquanto o PixelDocument viver; o lado nativo
 *   NÃO guarda ponteiros entre chamadas (o endereço é obtido por chamada
 *   JNI e a chamada é síncrona), pelo que a vida útil do buffer excede
 *   sempre o trabalho nativo que o utiliza.
 * - A classe é Kotlin puro + java.nio (sem APIs Android) — o motor e o
 *   histórico continuam totalmente testáveis em JVM.
 *
 * LIMITES E PROTEÇÃO CONTRA ALOCAÇÕES EXCESSIVAS:
 * - Dimensões válidas: 1..[MAX_DIMENSION] por eixo.
 * - Número máximo de píxeis: [MAX_PIXELS] (2048x2048 = 4 194 304), ou seja
 *   ~16 MB por buffer direto — orçamento seguro para telemóveis de ~4 GB.
 * - Qualquer violação lança [IllegalArgumentException] ANTES de qualquer
 *   alocação (validação no construtor).
 *
 * COMPATIBILIDADE: o formato .osp (bytes RGBA sem perdas) e a exportação
 * PNG mantêm-se exatamente iguais — a migração para buffer direto não
 * altera nenhum ficheiro guardado nem píxel exportado.
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

    /**
     * Buffer direto = fonte de verdade dos píxeis (partilhado com o C++
     * através de JNI). Ordem de bytes nativa (little-endian no Android),
     * capacidade exata width * height * 4.
     */
    val sharedBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 4) // ≤ 16 MB (MAX_PIXELS garantido no init)
            .order(ByteOrder.nativeOrder())

    /** Vista IntBuffer persistente sobre [sharedBuffer] — criada UMA vez. */
    private val intView: IntBuffer = sharedBuffer.asIntBuffer()

    val pixelCount: Int get() = width * height

    init {
        if (initialPixels != null) {
            require(initialPixels.size == pixelCount) {
                "Buffer inicial tem ${initialPixels.size} valores; esperado $pixelCount"
            }
            // Cópia em bloco para o buffer direto (uma operação, sem loop por píxel).
            intView.rewind()
            intView.put(initialPixels)
        }
        // allocateDirect já entrega memória zeroed = 0x00000000 (transparente).
    }

    /** Devolve true se (x, y) está dentro dos limites do documento. */
    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Devolve true se o índice linear está dentro dos limites do buffer. */
    fun inBoundsIndex(index: Int): Boolean = index in 0 until pixelCount

    /** Cor ARGB do píxel (x, y); devolve 0 se fora dos limites. */
    fun get(x: Int, y: Int): Int =
        if (inBounds(x, y)) intView.get(y * width + x) else 0

    /**
     * Define a cor ARGB do píxel (x, y). Fora dos limites é ignorado
     * silenciosamente (as ferramentas validam antes; isto é uma proteção).
     */
    fun set(x: Int, y: Int, argb: Int) {
        if (inBounds(x, y)) intView.put(y * width + x, argb)
    }

    /** Cor ARGB do píxel no índice linear [index]; 0 se fora dos limites. */
    fun getAt(index: Int): Int =
        if (inBoundsIndex(index)) intView.get(index) else 0

    /** Define a cor ARGB no índice linear [index]; fora dos limites ignora. */
    fun setAt(index: Int, argb: Int) {
        if (inBoundsIndex(index)) intView.put(index, argb)
    }

    /**
     * Validação completa usada antes de qualquer operação nativa: o buffer
     * é direto, tem a capacidade esperada e as dimensões não transbordam.
     * Devolve a razão do erro (para diagnóstico) ou null se estiver correto.
     */
    fun bufferValidationProblem(): String? {
        if (!sharedBuffer.isDirect) return "O buffer de píxeis não é direto"
        val expected = width.toLong() * height.toLong() * 4L
        val capacity = sharedBuffer.capacity().toLong()
        if (capacity != expected) {
            return "Capacidade do buffer ($capacity) != esperado ($expected)"
        }
        if (width <= 0 || height <= 0) return "Dimensões inválidas: ${width}x$height"
        return null
    }

    /** Cópia completa do documento em IntArray (snapshots, testes, exportação). */
    fun copyPixels(): IntArray {
        val out = IntArray(pixelCount)
        // Cópia em bloco: vista duplicada na posição 0 → bulk get.
        sharedBuffer.duplicate().order(sharedBuffer.order()).asIntBuffer().get(out)
        return out
    }

    /**
     * DEPRECIADO (transição): devolve uma CÓPIA integral — o caminho novo
     * opera diretamente sobre o buffer direto ([getAt]/[setAt]/[copyRegionInto]).
     * Mantido apenas para os chamadores ainda não migrados; será removido.
     */
    @Deprecated(
        message = "Use o buffer direto (sharedBuffer) ou cópias explícitas (copyPixels)",
        replaceWith = ReplaceWith("copyPixels()")
    )
    fun pixelsView(): IntArray = copyPixels()

    /** Alias semântico de [copyPixels]. */
    fun toIntArray(): IntArray = copyPixels()

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
        copyRegionInto(cx0, cy0, rw, rh, out)
        return out
    }

    /**
     * Copia a região (x0, y0, rw x rh — já recortada aos limites) para
     * [out] SEM alocação adicional — usada na atualização regional do
     * Bitmap de apresentação (evita arrays temporários por evento).
     */
    fun copyRegionInto(x0: Int, y0: Int, rw: Int, rh: Int, out: IntArray) {
        require(out.size >= rw * rh) { "Array de destino demasiado pequeno" }
        var o = 0
        for (row in 0 until rh) {
            val base = (y0 + row) * width + x0
            for (col in 0 until rw) {
                out[o++] = intView.get(base + col)
            }
        }
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
                if (inBounds(x, y)) intView.put(y * width + x, region[row * regionWidth + col])
            }
        }
    }

    /** Cria um novo documento independente com os mesmos píxeis (buffer direto novo). */
    fun deepCopy(): PixelDocument = PixelDocument(width, height, copyPixels())

    /** Codifica os píxeis em bytes RGBA (R,G,B,A) — formato usado no ficheiro .osp. */
    fun toRgbaBytes(): ByteArray {
        val pixels = copyPixels() // cópia em bloco do buffer direto
        val out = ByteArray(pixels.size * 4)
        var o = 0
        for (argb in pixels) {
            out[o] = ((argb shr 16) and 0xFF).toByte()      // R
            out[o + 1] = ((argb shr 8) and 0xFF).toByte()   // G
            out[o + 2] = (argb and 0xFF).toByte()           // B
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
