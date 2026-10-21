package com.pixelone.studio.pixel

/**
 * PILHA DE CAMADAS da Ronda B (Fase 3 · pintura) — Kotlin puro (testável em JVM).
 *
 * MODELO:
 *  - Cada camada é um [PixelDocument] próprio (buffer direto partilhado, igual
 *    ao resto do projeto) + nome + visibilidade + opacidade (0..100).
 *  - A ordem da lista é DE BAIXO PARA CIMA (índice 0 = base) — convenção dos
 *    editores de imagem; a composição aplica source-over nessa ordem.
 *  - A COMPOSIÇÃO é alfa reto (straight alpha), igual ao formato .osp e ao GL:
 *    out = src·a_src + dst·(1−a_src), com a_src escalado pela opacidade da
 *    camada. Não há premultiplicação em qualquer passo (requisito do projeto).
 *
 * ORÇAMENTO DE MEMÓRIA (telemóveis de ~4 GB RAM):
 *  - Máximo [MAX_LAYERS] camadas E máximo [MAX_TOTAL_PIXELS] píxeis somados
 *    pelas camadas (por defeito 2× o máximo de UM documento = ~32 MB diretos).
 *  - Exceder devolve erro CONTROLADO ([LayerLimitException]) com mensagem
 *    clara — nunca aloca primeiro e falha depois.
 *
 * COMPATIBILIDADE: projetos antigos (sem camadas) abrem como UMA camada
 * chamada "Base" com os píxeis do .osp (regra da Ronda B).
 */
class LayerStack(val width: Int, val height: Int) {

    /** Uma camada: píxeis próprios + apresentação. Imutável por fora. */
    class PixelLayer(
        val id: Long,
        val name: String,
        val doc: PixelDocument,
        var visible: Boolean,
        /** Opacidade 0..100 (100 = opaca). */
        var opacity: Int
    ) {
        /** Fator de opacidade 0..255 para a composição (arredondado). */
        val alpha255: Int get() = (opacity.coerceIn(0, 100) * 255 + 50) / 100
    }

    val layers = ArrayList<PixelLayer>()
    var activeIndex: Int = 0
        private set

    /** Contador de ids (estável entre reordenações — usado pela persistência). */
    private var nextId: Long = 1L

    init {
        require(width in 1..PixelDocument.MAX_DIMENSION &&
            height in 1..PixelDocument.MAX_DIMENSION &&
            width.toLong() * height <= PixelDocument.MAX_PIXELS
        ) { "Dimensões inválidas para camadas: ${width}x$height" }
    }

    // ------------------------------------------------------------------
    // Criação / consulta
    // ------------------------------------------------------------------

    /** Nº de camadas. */
    val count: Int get() = layers.size

    /** Camada ativa (nunca null — há sempre ≥ 1 camada). */
    fun active(): PixelLayer = layers[activeIndex.coerceIn(0, layers.size - 1)]

    /** Índice linear do documento da camada ativa (convenção dos chamadores). */
    fun activeDoc(): PixelDocument = active().doc

    /**
     * Cria a pilha a partir de píxeis existentes (abertura de projeto antigo
     * ou novo): UMA camada "Base" com esses píxeis.
     */
    fun seedSingleLayer(pixels: IntArray, name: String = "Base") {
        check(layers.isEmpty()) { "LayerStack já tem camadas" }
        addLayer(name, pixels, visible = true, opacity = 100, allowBudgetFailure = false)
        activeIndex = 0
    }

    /** Nova pilha vazia (1 camada transparente "Base"). */
    fun seedEmpty() = seedSingleLayer(IntArray(width * height))

    /**
     * Adiciona uma camada NO TOPO da pilha e torna-a ativa.
     * @throws LayerLimitException se exceder [MAX_LAYERS] ou o orçamento de
     *         píxeis (erro recuperável — a mensagem explica o limite).
     */
    fun addLayer(name: String = "Camada ${'A' + layers.size}", pixels: IntArray? = null) {
        addLayer(name, pixels, visible = true, opacity = 100, allowBudgetFailure = true)
        activeIndex = layers.size - 1
    }

    private fun addLayer(
        name: String,
        pixels: IntArray?,
        visible: Boolean,
        opacity: Int,
        allowBudgetFailure: Boolean
    ) {
        if (layers.size >= MAX_LAYERS) {
            throw LayerLimitException(
                "Máximo de $MAX_LAYERS camadas atingido — apague ou mescle uma camada primeiro."
            )
        }
        val addedPixels = width.toLong() * height
        if (allowBudgetFailure && totalPixels() + addedPixels > MAX_TOTAL_PIXELS) {
            throw LayerLimitException(
                "Sem memória para mais camadas nesta dimensão (limite: " +
                    "${MAX_TOTAL_PIXELS} píxeis no total; em uso: ${totalPixels()}). " +
                    "Reduza a dimensão da textura ou remova uma camada."
            )
        }
        val doc = if (pixels != null) {
            require(pixels.size == width * height) {
                "Píxeis da camada (${pixels.size}) não correspondem a ${width}x$height"
            }
            PixelDocument(width, height, pixels)
        } else {
            PixelDocument(width, height) // allocateDirect = transparente
        }
        layers.add(PixelLayer(nextId++, name, doc, visible, opacity.coerceIn(0, 100)))
    }

    /** Remove a camada [index] (nunca a última — regra: há sempre ≥ 1). */
    fun removeLayer(index: Int) {
        if (layers.size <= 1) return
        if (index !in layers.indices) return
        layers.removeAt(index)
        activeIndex = activeIndex.coerceIn(0, layers.size - 1)
    }

    // ------------------------------------------------------------------
    // Estado da camada ativa e reordenação
    // ------------------------------------------------------------------

    fun setActive(index: Int) {
        if (index in layers.indices) activeIndex = index
    }

    fun toggleVisible(index: Int) {
        layers.getOrNull(index)?.let { it.visible = !it.visible }
    }

    fun setOpacity(index: Int, opacity: Int) {
        layers.getOrNull(index)?.opacity = opacity.coerceIn(0, 100)
    }

    /** Sobe a camada [index] uma posição (na direção do topo). Devolve true se moveu. */
    fun moveUp(index: Int): Boolean = move(index, +1)

    /** Desce a camada [index] uma posição (na direção da base). Devolve true se moveu. */
    fun moveDown(index: Int): Boolean = move(index, -1)

    private fun move(index: Int, delta: Int): Boolean {
        val target = index + delta
        if (index !in layers.indices || target !in layers.indices) return false
        val l = layers.removeAt(index)
        layers.add(target, l)
        if (activeIndex == index) activeIndex = target
        return true
    }

    // ------------------------------------------------------------------
    // Composição (source-over, alfa reto)
    // ------------------------------------------------------------------

    /**
     * Composição completa para [out] (IntArray width*height) — usada em
     * operações estruturais (abrir, adicionar/remover camada, visibilidade,
     * opacidade, reordenação). Para pintura use [composeRegion] (barato).
     */
    fun compose(out: IntArray? = null): IntArray {
        val dst = out ?: IntArray(width * height)
        require(dst.size == width * height) { "Buffer de composição com tamanho errado" }
        // Base: primeira camada visível (ou transparente se nenhuma visível).
        dst.fill(0)
        for (layer in layers) {
            if (!layer.visible) continue
            blendRegion(layer, 0, 0, width, height, dst, width)
        }
        return dst
    }

    /**
     * Compõe APENAS a região [x0..x1]×[y0..y1] (inclusive, recortada aos
     * limites) para [out] (row-major da região) — caminho da PINTURA: custo
     * proporcional à região suja, nunca ao documento inteiro.
     */
    fun composeRegion(x0: Int, y0: Int, rw: Int, rh: Int, out: IntArray) {
        require(out.size >= rw * rh) { "Buffer regional pequeno" }
        val cx0 = x0.coerceIn(0, width - 1)
        val cy0 = y0.coerceIn(0, height - 1)
        val cx1 = (x0 + rw - 1).coerceIn(0, width - 1)
        val cy1 = (y0 + rh - 1).coerceIn(0, height - 1)
        val w = cx1 - cx0 + 1
        val h = cy1 - cy0 + 1
        // Preenche com a composição da base (abaixo da região): começa em 0
        // e aplica TODAS as camadas visíveis na região (simples e correto —
        // camadas abaixo também contribuem para os mesmos píxeis).
        java.util.Arrays.fill(out, 0, w * h, 0)
        val rowBuf = IntArray(w)
        for (layer in layers) {
            if (!layer.visible) continue
            for (row in 0 until h) {
                val y = cy0 + row
                // Lê a linha da camada e mistura por cima do acumulado.
                for (col in 0 until w) rowBuf[col] = layer.doc.get(cx0 + col, y)
                val dstBase = row * w
                val a255 = layer.alpha255
                for (col in 0 until w) {
                    val src = rowBuf[col]
                    val sa = (((src ushr 24) and 0xFF) * a255 + 127) / 255
                    if (sa == 0) continue
                    val di = dstBase + col
                    val d = out[di]
                    if (sa == 255) {
                        // Cobertura total: resultado = píxel da fonte (com alfa).
                        out[di] = src
                    } else {
                        val da = (d ushr 24) and 0xFF
                        val outA = sa + (da * (255 - sa) + 127) / 255
                        out[di] = blendPixel(src, sa, d, da, outA)
                    }
                }
            }
        }
    }

    private fun blendPixel(src: Int, sa: Int, dst: Int, da: Int, outA: Int): Int {
        fun ch(shift: Int): Int {
            val s = (src shr shift) and 0xFF
            val d = (dst shr shift) and 0xFF
            // c_out = c_s·a_s + c_d·a_d·(1−a_s) — alfa reto, com arredondamento.
            val v = (s * sa + d * da * (255 - sa) / 255 + 127) / maxOf(1, outA)
            return v.coerceIn(0, 255)
        }
        return (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Mistura uma camada inteira (faixa dada) sobre [dst] (row-major completo). */
    private fun blendRegion(layer: PixelLayer, x0: Int, y0: Int, rw: Int, rh: Int, dst: IntArray, dstWidth: Int) {
        val a255 = layer.alpha255
        for (row in 0 until rh) {
            val y = y0 + row
            val dstBase = y * dstWidth + x0
            for (col in 0 until rw) {
                val src = layer.doc.get(x0 + col, y)
                val sa = (((src ushr 24) and 0xFF) * a255 + 127) / 255
                if (sa == 0) continue
                val di = dstBase + col
                val d = dst[di]
                if (sa == 255) {
                    // Cobertura total: o resultado é EXATAMENTE o píxel da fonte
                    // (com o alfa da fonte — nunca descartar o canal A).
                    dst[di] = src
                } else {
                    val da = (d ushr 24) and 0xFF
                    val outA = sa + (da * (255 - sa) + 127) / 255
                    dst[di] = blendPixel(src, sa, d, da, outA)
                }
            }
        }
    }

    /** Píxeis somados de TODAS as camadas (orçamento de memória). */
    fun totalPixels(): Long = layers.size.toLong() * width * height

    /** Orçamento excedido? (diagnóstico e testes) */
    fun overBudget(): Boolean = totalPixels() > MAX_TOTAL_PIXELS || layers.size > MAX_LAYERS

    /** Erro de limite — recuperável (mensagem clara, nada corrompido). */
    class LayerLimitException(message: String) : Exception(message)

    companion object {
        /** Máximo de camadas (regra da Ronda B: ≥ 2 exigidas, 4 é seguro). */
        const val MAX_LAYERS: Int = 4

        /**
         * Máximo de píxeis SOMADOS das camadas: 2× o máximo de um documento
         * (≈ 32 MB de buffers diretos) — folga para histórico esparso (32 MB)
         * e bitmap de apresentação num aparelho de 4 GB.
         */
        const val MAX_TOTAL_PIXELS: Long = 2L * PixelDocument.MAX_PIXELS
    }
}
