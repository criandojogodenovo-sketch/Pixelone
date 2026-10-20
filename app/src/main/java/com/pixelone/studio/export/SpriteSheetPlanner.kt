package com.pixelone.studio.export

/**
 * Planeador de SPRITESHEETS do Pixel One (Fase 2) — Kotlin puro.
 *
 * Um spritesheet combina quadros REGULARES (todos com as mesmas dimensões)
 * numa grelha de colunas/linhas, com espaçamento e margem configuráveis.
 * Distinto do ATLAS (regiões de tamanhos diferentes) — ver [AtlasPacker].
 *
 * POSICIONAMENTO DETERMINÍSTICO (documentado):
 * - A ordem dos quadros de entrada é PRESERVADA (importante para animações).
 * - O quadro i é colocado em: coluna = i % colunas, linha = i / colunas.
 * - Origem do quadro: x = margem + coluna*(frameWidth+espaçamento),
 *   y = margem + linha*(frameHeight+espaçamento).
 * - Modo automático de colunas: colunas = ceil(sqrt(n)) — grelha o mais
 *   quadrada possível, estável para o mesmo n.
 *
 * REGRAS DE VALIDAÇÃO:
 * - Todos os quadros têm de ter EXATAMENTE frameWidth x frameHeight; quadros
 *   incompativeis são rejeitados com lista dos ofensores (os dados nunca são
 *   transformados/escalonados sem escolha explícita do utilizador).
 * - Limites: cada eixo ≤ [MAX_SHEET_DIMENSION] (4096) e total ≤
 *   [MAX_SHEET_PIXELS] (8 388 608 píxeis = 32 MB RGBA) — orçamento seguro
 *   para um telemóvel de ~4 GB de RAM.
 */
object SpriteSheetPlanner {

    /** Uma imagem de entrada para composição (píxeis ARGB row-major). */
    class ImageSource(
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

    /** Posição de um quadro na grelha final. */
    data class Placement(
        val name: String,
        val index: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val row: Int,
        val column: Int
    )

    /** Plano de composição do spritesheet (determinístico e validado). */
    class SheetPlan(
        val width: Int,
        val height: Int,
        val frameWidth: Int,
        val frameHeight: Int,
        val columns: Int,
        val rows: Int,
        val spacing: Int,
        val margin: Int,
        val placements: List<Placement>
    ) {
        init {
            require(width in 1..MAX_SHEET_DIMENSION && height in 1..MAX_SHEET_DIMENSION) {
                "Dimensões da folga fora dos limites: ${width}x$height"
            }
            require(width.toLong() * height.toLong() <= MAX_SHEET_PIXELS) {
                "Folga demasiado grande: ${width}x$height (${width.toLong() * height} píxeis, " +
                    "máximo $MAX_SHEET_PIXELS)"
            }
        }

        /**
         * Componho os píxeis do spritesheet: fundo totalmente transparente
         * (0x00000000) com cada quadro copiado para a sua posição.
         * A transparência dos quadros é PRESERVADA (cópia direta ARGB).
         */
        fun compose(sources: List<ImageSource>): IntArray {
            val out = IntArray(width * height) // 0x00000000 = transparente
            for (i in placements.indices) {
                val src = sources[i]
                val p = placements[i]
                for (row in 0 until p.height) {
                    val srcStart = row * src.width
                    val dstStart = (p.y + row) * width + p.x
                    System.arraycopy(src.pixels, srcStart, out, dstStart, p.width)
                }
            }
            return out
        }

        /** Metadados JSON (esquema documentado em docs/spritesheet-atlas-json.md). */
        fun metadataJson(): String {
            val frames = placements.map { p ->
                linkedMapOf(
                    "index" to p.index,
                    "name" to p.name,
                    "x" to p.x,
                    "y" to p.y,
                    "width" to p.width,
                    "height" to p.height,
                    "row" to p.row,
                    "column" to p.column
                )
            }
            val root = linkedMapOf<String, Any?>(
                "schemaVersion" to SCHEMA_VERSION,
                "type" to "pixel-one-spritesheet",
                "app" to APP,
                "sheet" to linkedMapOf("width" to width, "height" to height),
                "frameWidth" to frameWidth,
                "frameHeight" to frameHeight,
                "columns" to columns,
                "rows" to rows,
                "spacing" to spacing,
                "margin" to margin,
                "frameCount" to placements.size,
                "frames" to frames
            )
            return com.pixelone.studio.osp.MiniJson.write(root)
        }
    }

    /**
     * Calcula o plano do spritesheet.
     *
     * @param sources quadros, na ordem a preservar.
     * @param frameWidth/frameHeight dimensões do quadro (devem coincidir com
     *        TODOS os quadros; 0 = deduzir do primeiro quadro).
     * @param columns número de colunas (0 = automático: ceil(sqrt(n))).
     * @param spacing espaçamento entre quadros (≥ 0, em píxeis).
     * @param margin margem exterior (≥ 0, em píxeis).
     * @throws IllegalArgumentException com mensagem clara e útil se entradas
     *         forem inválidas (dimensões incompativeis, limites excedidos).
     */
    fun plan(
        sources: List<ImageSource>,
        frameWidth: Int,
        frameHeight: Int,
        columns: Int,
        spacing: Int,
        margin: Int
    ): SheetPlan {
        require(sources.isNotEmpty()) { "Nenhum quadro selecionado" }
        require(spacing >= 0) { "Espaçamento negativo: $spacing" }
        require(margin >= 0) { "Margem negativa: $margin" }

        val fw = if (frameWidth > 0) frameWidth else sources[0].width
        val fh = if (frameHeight > 0) frameHeight else sources[0].height
        require(fw >= 1 && fh >= 1) { "Dimensões de quadro inválidas: ${fw}x$fh" }
        require(fw <= MAX_SHEET_DIMENSION && fh <= MAX_SHEET_DIMENSION) {
            "Dimensões de quadro excessivas: ${fw}x$fh"
        }

        // Todos os quadros têm de ter exatamente as dimensões do quadro.
        val incompativeis = sources.filter { it.width != fw || it.height != fh }
        require(incompativeis.isEmpty()) {
            "Quadros com dimensões incompativeis (${fw}x${fh} esperado): " +
                incompativeis.joinToString(limit = 5) { "${it.name} (${it.width}x${it.height})" }
        }

        val n = sources.size
        val cols = if (columns > 0) {
            require(columns in 1..MAX_COLUMNS) { "Colunas fora dos limites: $columns (1..$MAX_COLUMNS)" }
            columns
        } else {
            // Regra automática determinística: grelha o mais quadrada possível.
            Math.ceil(Math.sqrt(n.toDouble())).toInt().coerceIn(1, MAX_COLUMNS)
        }
        val rows = (n + cols - 1) / cols

        val width = margin * 2 + cols * fw + (cols - 1) * spacing
        val height = margin * 2 + rows * fh + (rows - 1) * spacing
        require(width <= MAX_SHEET_DIMENSION && height <= MAX_SHEET_DIMENSION) {
            "Folga excede o limite de ${MAX_SHEET_DIMENSION}px por eixo: seria ${width}x$height. " +
                "Reduza colunas, espaçamento ou dimensões dos quadros."
        }
        require(width.toLong() * height.toLong() <= MAX_SHEET_PIXELS) {
            "Folga excede o limite de memória ($MAX_SHEET_PIXELS píxeis): ${width}x$height"
        }

        val placements = sources.mapIndexed { i, src ->
            val col = i % cols
            val row = i / cols
            Placement(
                name = src.name,
                index = i,
                x = margin + col * (fw + spacing),
                y = margin + row * (fh + spacing),
                width = src.width,
                height = src.height,
                row = row,
                column = col
            )
        }

        return SheetPlan(
            width = width,
            height = height,
            frameWidth = fw,
            frameHeight = fh,
            columns = cols,
            rows = rows,
            spacing = spacing,
            margin = margin,
            placements = placements
        )
    }

    const val SCHEMA_VERSION: Int = 1
    const val APP: String = "Pixel One"
    const val MAX_SHEET_DIMENSION: Int = 4096
    const val MAX_SHEET_PIXELS: Int = 8_388_608 // 8M píxeis = 32 MB RGBA
    const val MAX_COLUMNS: Int = 64
}
