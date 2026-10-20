package com.pixelone.studio.pixel

/**
 * Histórico de edições (desfazer/refazer) do Pixel One.
 *
 * ESTRATÉGIA (documentada):
 * - Cada entrada é uma DIFERENÇA REGIONAL: o retângulo delimitador dos
 *   píxeis alterados numa operação lógica, com os valores "antes" e
 *   "depois" apenas dessa região. NUNCA se copia a imagem inteira por
 *   movimento do dedo (evita cópias completas do bitmap por evento de toque).
 * - Um TRAÇO contínuo (arrastar o dedo) é UMA operação lógica: a UI chama
 *   [beginStroke] no down, as ferramentas desenham com o histórico ligado
 *   como [PixelEngine.StrokeRecorder] e a UI termina com [commitStroke].
 *   Não existe uma entrada de histórico por píxel.
 * - ORÇAMENTO DE MEMÓRIA: [memoryBudgetBytes] limita o total dos arrays
 *   "antes"+"depois" das entradas (por defeito 32 MB, configurável). Ao
 *   exceder, as entradas MAIS ANTIGAS são libertadas primeiro (o GC recolhe
 *   os buffers removidos). Uma entrada única maior que o orçamento é mantida
 *   sozinha (uma operação é indivisível) — comportamento explícito e
 *   documentado em vez de estourar a RAM.
 * - REDO: uma nova edição após um undo limpa toda a pilha de redo.
 *
 * SEGURANÇA: as pilhas são sincronizadas; o traço corre no thread de UI.
 */
class HistoryManager(
    /** Orçamento de memória para o histórico, em bytes. */
    val memoryBudgetBytes: Long = DEFAULT_BUDGET_BYTES
) : PixelEngine.StrokeRecorder {

    /**
     * Uma edição regional: retângulo [x0..x1] x [y0..y1] (inclusive) com os
     * valores ARGB "antes" e "depois" em row-major da região.
     */
    class RegionEdit(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val before: IntArray,
        val after: IntArray
    ) {
        init {
            require(x0 >= 0 && y0 >= 0 && x1 >= x0 && y1 >= y0) { "Região inválida" }
            val w = x1 - x0 + 1
            val h = y1 - y0 + 1
            require(before.size == w * h && after.size == w * h) {
                "Arrays da região não correspondem ao retângulo"
            }
        }

        /** Custo de memória desta entrada (antes + depois, 4 bytes/valor). */
        val memoryBytes: Long = (before.size.toLong() + after.size) * 4L

        /** Aplica os valores "antes" (desfazer) ou "depois" (refazer) ao documento. */
        private fun apply(document: PixelDocument, source: IntArray) {
            document.writeRegion(x0, y0, x1 - x0 + 1, source)
        }

        fun undo(document: PixelDocument) = apply(document, before)
        fun redo(document: PixelDocument) = apply(document, after)
    }

    private val lock = Any()
    private val undoStack = ArrayDeque<RegionEdit>()
    private val redoStack = ArrayDeque<RegionEdit>()

    /** Memória total = soma das entradas em AMBAS as pilhas (cada entrada
     *  vive em exatamente uma pilha; move-se entre elas sem custo extra). */
    private var currentMemoryBytes = 0L

    // Estado do traço em curso (apenas no thread de UI; com lock por segurança).
    private var strokeActive = false
    private var strokeBefore = HashMap<Int, Int>() // índice -> cor original
    private var strokeMinX = 0; private var strokeMaxX = -1
    private var strokeMinY = 0; private var strokeMaxY = -1

    /**
     * Começa um traço (uma operação lógica). Chamado no down do dedo para
     * ferramentas de traço (lápis/borracha) e antes de um fill (operação
     * atómica usa o mesmo mecanismo).
     */
    fun beginStroke() {
        synchronized(lock) {
            check(!strokeActive) { "Traço já ativo — termina com commitStroke ou cancelStroke" }
            strokeActive = true
            strokeBefore = HashMap()
            strokeMinX = 0; strokeMaxX = -1
            strokeMinY = 0; strokeMaxY = -1
        }
    }

    /** Implementação do [PixelEngine.StrokeRecorder]: guarda o estado ANTES. */
    override fun onBeforeChange(x: Int, y: Int, beforeColor: Int) {
        synchronized(lock) {
            if (!strokeActive) return
            // Só a PRIMEIRA cor original de cada píxel (deduplicação por traço).
            strokeBefore.putIfAbsent(y shl 16 or x, beforeColor)
        }
    }

    /** Implementação do [PixelEngine.StrokeRecorder]: atualiza o bbox alterado. */
    override fun onChanged(x: Int, y: Int) {
        synchronized(lock) {
            if (!strokeActive) return
            if (strokeMaxX < strokeMinX) { // primeira alteração
                strokeMinX = x; strokeMaxX = x
                strokeMinY = y; strokeMaxY = y
            } else {
                if (x < strokeMinX) strokeMinX = x
                if (x > strokeMaxX) strokeMaxX = x
                if (y < strokeMinY) strokeMinY = y
                if (y > strokeMaxY) strokeMaxY = y
            }
        }
    }

    /**
     * Termina o traço e cria UMA entrada de histórico com a região alterada.
     * Não faz nada se o traço não alterou píxeis (evita entradas vazias).
     */
    fun commitStroke(document: PixelDocument) {
        synchronized(lock) {
            if (!strokeActive) return
            strokeActive = false
            if (strokeBefore.isEmpty()) return
            val region = buildRegionEdit(document, strokeBefore)
            if (region != null) pushUndoLocked(region)
            strokeBefore = HashMap()
        }
    }

    /**
     * Cancela o traço em curso e RESTAURA os píxeis registados (usado quando
     * um segundo dedo entra no ecrã a meio do traço — evita traços acidentais).
     */
    fun cancelStroke(document: PixelDocument) {
        synchronized(lock) {
            if (!strokeActive) return
            strokeActive = false
            for ((idx, color) in strokeBefore) {
                document.set(idx shr 16, idx and 0xFFFF, color)
            }
            strokeBefore = HashMap()
        }
    }

    /** Devolve true se existe alguma edição para desfazer. */
    fun canUndo(): Boolean = synchronized(lock) { undoStack.isNotEmpty() }

    /** Devolve true se existe alguma edição para refazer. */
    fun canRedo(): Boolean = synchronized(lock) { redoStack.isNotEmpty() }

    /** Desfaz a última edição. Devolve true se fez undo. */
    fun undo(document: PixelDocument): Boolean = synchronized(lock) {
        check(!strokeActive) { "Undo durante um traço não é permitido" }
        val edit = undoStack.removeLastOrNull() ?: return false
        edit.undo(document)
        redoStack.addLast(edit) // a entrada apenas MUDA de pilha (memória constante)
        true
    }

    /** Refaz a última edição desfeita. Devolve true se fez redo. */
    fun redo(document: PixelDocument): Boolean = synchronized(lock) {
        check(!strokeActive) { "Redo durante um traço não é permitido" }
        val edit = redoStack.removeLastOrNull() ?: return false
        edit.redo(document)
        undoStack.addLast(edit) // a entrada apenas MUDA de pilha
        evictOldestToFitBudgetLocked() // pode libertar as mais antigas
        true
    }

    /** Número de entradas no undo (diagnóstico e testes). */
    fun undoCount(): Int = synchronized(lock) { undoStack.size }

    /** Número de entradas no redo (diagnóstico e testes). */
    fun redoCount(): Int = synchronized(lock) { redoStack.size }

    /** Memória atualmente ocupada pelo histórico (diagnóstico e testes). */
    fun memoryBytesUsed(): Long = synchronized(lock) { currentMemoryBytes }

    /** Limpa todo o histórico (ex.: ao abrir/criar documento novo). */
    fun clear() {
        synchronized(lock) {
            undoStack.clear()
            redoStack.clear()
            currentMemoryBytes = 0L
            strokeActive = false
            strokeBefore = HashMap()
        }
    }

    // ------------------------------------------------------------------
    // Internos (com lock assumido)
    // ------------------------------------------------------------------

    private fun pushUndoLocked(edit: RegionEdit) {
        // NOVA edição: a pilha de redo é limpa e a sua memória abatida.
        while (redoStack.isNotEmpty()) {
            currentMemoryBytes -= redoStack.removeFirst().memoryBytes
        }
        undoStack.addLast(edit)
        currentMemoryBytes += edit.memoryBytes
        evictOldestToFitBudgetLocked()
    }

    /**
     * Liberta as entradas mais antigas até caber no orçamento. Se a entrada
     * mais recente exceder sozinha o orçamento, mantém-na (uma operação é
     * indivisível) e descarta todas as restantes.
     */
    private fun evictOldestToFitBudgetLocked() {
        while (currentMemoryBytes > memoryBudgetBytes && undoStack.size > 1) {
            val oldest = undoStack.removeFirst()
            currentMemoryBytes -= oldest.memoryBytes
        }
    }

    /**
     * Constrói um RegionEdit a partir dos valores "antes" capturados pelo
     * recorder, comparando com o estado atual do documento.
     * Devolve null se a região não mudou (proteção defensiva).
     */
    private fun buildRegionEdit(
        document: PixelDocument,
        beforeByIndex: HashMap<Int, Int>
    ): RegionEdit? {
        if (beforeByIndex.isEmpty()) return null
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (idx in beforeByIndex.keys) {
            val x = idx shr 16
            val y = idx and 0xFFFF
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val after = document.copyRegion(minX, minY, maxX, maxY)
        require(after.size == w * h) { "Região recortada inconsistente" }
        val before = IntArray(w * h)
        var changed = false
        for (row in 0 until h) {
            for (col in 0 until w) {
                val x = minX + col
                val y = minY + row
                val outIdx = row * w + col
                val original = beforeByIndex[y shl 16 or x] ?: document.get(x, y)
                before[outIdx] = original
                if (original != after[outIdx]) changed = true
            }
        }
        if (!changed) return null
        return RegionEdit(minX, minY, maxX, maxY, before, after)
    }

    companion object {
        /**
         * Orçamento por defeito: 32 MB — para um telemóvel de ~4 GB RAM com
         * heap de app típica (192-512 MB), deixa folga para o documento
         * (máx. 16 MB) e o Bitmap de renderização.
         */
        const val DEFAULT_BUDGET_BYTES: Long = 32L * 1024 * 1024
    }
}
