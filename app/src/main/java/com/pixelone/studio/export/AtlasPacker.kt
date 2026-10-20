package com.pixelone.studio.export

/**
 * Empacotador de ATLAS de texturas do Pixel One (Fase 2) — Kotlin puro.
 *
 * Um atlas combina imagens de TAMANHOS DIFERENTES numa imagem maior, com
 * metadados de localização por região. Distinto do spritesheet (quadros
 * regulares numa grelha) — ver [SpriteSheetPlanner].
 *
 * ALGORITMO DETERMINÍSTICO (documentado):
 * - Shelf packing (prateleiras), SEM rotação: preservar os dados originais
 *   de cada textura é prioritário.
 * - Ordenação determinística: altura desc → largura desc → nome asc
 *   (estável para as mesmas entradas, em qualquer ordem de seleção).
 * - As texturas são colocadas da esquerda para a direita; quando não cabe,
 *   abre-se uma nova prateleira por baixo.
 * - A altura final do atlas é a ALTURA REAL USADA (compacta), não o limite.
 *
 * SE NÃO CABER: erro claro explicando qual textura excedeu os limites
 * (nome, dimensões e limite) — a UI mostra-o ao utilizador.
 *
 * LIMITES: eixos ≤ [MAX_ATLAS_DIMENSION] (4096), total ≤ [MAX_ATLAS_PIXELS]
 * (8 388 608 píxeis = 32 MB RGBA) — seguro num telemóvel de ~4 GB RAM.
 */
object AtlasPacker {

    /** Uma textura de entrada (píxeis ARGB row-major). */
    class TextureSource(
        val name: String,
        val width: Int,
        val height: Int,
        val pixels: IntArray
    ) {
        init {
            require(width >= 1 && height >= 1) { "'$name': dimensões inválidas ${width}x$height" }
            require(pixels.size == width * height) { "'$name': buffer não corresponde às dimensões" }
        }
    }

    /** Região de uma textura no atlas final. */
    data class Region(
        val name: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    /** Plano de composição do atlas (determinístico e validado). */
    class AtlasPlan(
        val width: Int,
        val height: Int,
        val regions: List<Region>
    ) {
        init {
            require(width in 1..MAX_ATLAS_DIMENSION && height in 1..MAX_ATLAS_DIMENSION) {
                "Dimensões do atlas fora dos limites: ${width}x$height"
            }
            require(width.toLong() * height.toLong() <= MAX_ATLAS_PIXELS) {
                "Atlas demasiado grande: ${width}x$height (máximo $MAX_ATLAS_PIXELS píxeis)"
            }
        }

        /**
         * Componho os píxeis do atlas: fundo transparente com cada textura
         * copiada por linhas (transparência preservada). Sem sobreposições
         * por construção (garantido pelo algoritmo e verificado nos testes).
         */
        fun compose(sources: List<TextureSource>): IntArray {
            val byName = sources.associateBy { it.name }
            val out = IntArray(width * height) // 0x00000000 = transparente
            for (r in regions) {
                val src = byName[r.name]
                    ?: throw IllegalArgumentException("Textura ausente: ${r.name}")
                for (row in 0 until r.height) {
                    val srcStart = row * src.width
                    val dstStart = (r.y + row) * width + r.x
                    System.arraycopy(src.pixels, srcStart, out, dstStart, r.width)
                }
            }
            return out
        }

        /** Metadados JSON (esquema documentado em docs/spritesheet-atlas-json.md). */
        fun metadataJson(): String {
            val regionsJson = regions.map { r ->
                linkedMapOf(
                    "name" to r.name,
                    "x" to r.x,
                    "y" to r.y,
                    "width" to r.width,
                    "height" to r.height
                )
            }
            val root = linkedMapOf<String, Any?>(
                "schemaVersion" to SCHEMA_VERSION,
                "type" to "pixel-one-atlas",
                "app" to APP,
                "atlas" to linkedMapOf("width" to width, "height" to height),
                "regionCount" to regions.size,
                "regions" to regionsJson
            )
            return com.pixelone.studio.osp.MiniJson.write(root)
        }
    }

    /** Entrada não cabe nos limites (erro controlado com explicação útil). */
    class DoesNotFitException(message: String) : IllegalArgumentException(message)

    /**
     * Calcula o plano do atlas.
     *
     * @param textures texturas de entrada (tamanhos podem variar).
     * @param spacing espaçamento entre regiões (≥ 0).
     * @param margin margem exterior (≥ 0).
     * @param maxWidth/maxHeight limites máximos do atlas (1..[MAX_ATLAS_DIMENSION]).
     * @throws DoesNotFitException se as entradas não couberem nos limites.
     */
    fun pack(
        textures: List<TextureSource>,
        spacing: Int,
        margin: Int,
        maxWidth: Int,
        maxHeight: Int
    ): AtlasPlan {
        require(textures.isNotEmpty()) { "Nenhuma textura selecionada" }
        require(spacing >= 0) { "Espaçamento negativo: $spacing" }
        require(margin >= 0) { "Margem negativa: $margin" }
        require(maxWidth in 1..MAX_ATLAS_DIMENSION && maxHeight in 1..MAX_ATLAS_DIMENSION) {
            "Limites de atlas inválidos: ${maxWidth}x$maxHeight " +
                "(permitido 1..$MAX_ATLAS_DIMENSION)"
        }
        require(maxWidth.toLong() * maxHeight.toLong() <= MAX_ATLAS_PIXELS) {
            "Limites de atlas excedem o orçamento de memória " +
                "($MAX_ATLAS_PIXELS píxeis): ${maxWidth}x$maxHeight"
        }

        // Cada textura tem de caber isoladamente nos limites.
        for (t in textures) {
            if (t.width > maxWidth - margin * 2 || t.height > maxHeight - margin * 2) {
                throw DoesNotFitException(
                    "'${t.name}' (${t.width}x${t.height}) não cabe nos limites " +
                        "${maxWidth}x$maxHeight com margem $margin. Aumente os limites ou reduza a margem."
                )
            }
        }

        // Ordenação determinística: altura desc, largura desc, nome asc.
        val ordered = textures.sortedWith(
            compareByDescending<TextureSource> { it.height }
                .thenByDescending { it.width }
                .thenBy { it.name }
        )

        val usableWidth = maxWidth - margin * 2
        val placements = ArrayList<Region>(ordered.size)
        var shelfX = margin
        var shelfY = margin
        var shelfHeight = 0

        for (t in ordered) {
            if (shelfX + t.width > maxWidth - margin) {
                // Nova prateleira.
                shelfY += shelfHeight + spacing
                shelfX = margin
                shelfHeight = 0
            }
            if (shelfY + t.height > maxHeight - margin) {
                throw DoesNotFitException(
                    "As texturas não cabem nos limites ${maxWidth}x$maxHeight " +
                        "(excedido ao colocar '${t.name}' em y=$shelfY). " +
                        "Aumente os limites, reduza o espaçamento ou remova texturas."
                )
            }
            placements.add(Region(t.name, shelfX, shelfY, t.width, t.height))
            shelfX += t.width + spacing
            if (t.height > shelfHeight) shelfHeight = t.height
        }

        // Altura real usada: fundo da última prateleira + margem.
        val usedHeight = if (placements.isEmpty()) margin * 2
        else placements.maxOf { it.y + it.height } + margin
        val usedWidth = margin * 2 + (placements.maxOfOrNull { it.x + it.width } ?: margin)

        return AtlasPlan(
            width = usedWidth,
            height = usedHeight,
            regions = placements
        )
    }

    const val SCHEMA_VERSION: Int = 1
    const val APP: String = "Pixel One"
    const val MAX_ATLAS_DIMENSION: Int = 4096
    const val MAX_ATLAS_PIXELS: Int = 8_388_608 // 8M píxeis = 32 MB RGBA
}
