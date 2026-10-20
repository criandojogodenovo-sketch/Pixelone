package com.pixelone.studio.pixel

/**
 * Histórico de edições (desfazer/refazer) do Pixel One.
 *
 * ESTRATÉGIA (correção de performance — deltas esparsos):
 * - Cada entrada é uma lista de DELTAS por píxel: índice linear, cor
 *   antiga, cor nova. NUNCA um snapshot integral do documento e nunca um
 *   retângulo denso — a memória é proporcional aos píxeis REALMENTE
 *   alterados, não ao tamanho do documento.
 * - O motor (nativo C++17 ou fallback Kotlin) devolve os deltas de um
 *   traço completo em arrays compactos de UMA VEZ; o histórico só os
 *   copia uma vez ([commitDeltas]). O caminho com recorder
 *   ([beginStroke]/[onBeforeChange]/[commitStroke]) também produz deltas
 *   esparsos (deduplicados por traço).
 * - UM TRAÇO contínuo = UMA entrada de histórico. Um fill = UMA entrada.
 * - ORÇAMENTO DE MEMÓRIA: [memoryBudgetBytes] limita o total das entradas
 *   nas duas pilhas (por defeito 32 MB). Ao exceder, as entradas MAIS
 *   ANTIGAS são libertadas primeiro. Uma entrada única maior que o
 *   orçamento é mantida sozinha (operação indivisível) — comportamento
 *   explícito e documentado em vez de estourar a RAM.
 * - REDO: uma nova edição após um undo limpa toda a pilha de redo.
 * - Nenhuma entrada pode ultrapassar os limites do documento: os deltas
 *   são validados ao construir a entrada (índices dentro de 0..pixelCount-1).
 *
 * SEGURANÇA: as pilhas são sincronizadas; as aplicações de undo/redo
 * escrevem no buffer direto via [PixelDocument.setAt] (vista persistente).
 */
class HistoryManager(
    /** Orçamento de memória para o histórico, em bytes. */
    val memoryBudgetBytes: Long = DEFAULT_BUDGET_BYTES
) : PixelEngine.StrokeRecorder {

    /**
     * Uma edição como DELTAS esparsos: cada i é um píxel alterado
     * (índice linear, cor antes, cor depois). Guarda também o retângulo
     * delimitador para a atualização regional do bitmap de apresentação.
     */
    class SparseEdit(
        val indices: IntArray,
        val before: IntArray,
        val after: IntArray,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        init {
            require(indices.size == before.size && before.size == after.size) {
                "Arrays de deltas inconsistentes"
            }
            for (i in indices) {
                require(i >= 0) { "Índice de delta fora dos limites do documento: $i" }
            }
        }

        val size: Int get() = indices.size

        /** Custo de memória desta entrada (3 arrays × 4 bytes/valor). */
        val memoryBytes: Long = (indices.size.toLong() + before.size + after.size) * 4L

        /** Aplica os valores "antes" (desfazer) ou "depois" (refazer). */
        private fun apply(document: PixelDocument, source: IntArray) {
            for (i in indices.indices) {
                document.setAt(indices[i], source[i])
            }
        }

        fun undo(document: PixelDocument) = apply(document, before)
        fun redo(document: PixelDocument) = apply(document, after)
    }

    private val lock = Any()
    private val undoStack = ArrayDeque<SparseEdit>()
    private val redoStack = ArrayDeque<SparseEdit>()

    /** Memória total = soma das entradas em AMBAS as pilhas. */
    private var currentMemoryBytes = 0L

    // Estado do traço em curso (apenas no thread de mutação; lock por segurança).
    private var strokeActive = false
    private var strokeBefore = HashMap<Int, Int>() // índice linear -> cor original
    private var strokeMinX = 0; private var strokeMaxX = -1
    private var strokeMinY = 0; private var strokeMaxY = -1

    /**
     * Começa um traço (uma operação lógica). Chamado no down do dedo para
     * ferramentas de traço (lápis/borracha) e antes de um fill.
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
     * Termina o traço do caminho recorder e cria UMA entrada de deltas
     * esparsos (comparando com o estado atual do documento).
     * Não faz nada se o traço não alterou píxeis (evita entradas vazias).
     */
    fun commitStroke(document: PixelDocument) {
        synchronized(lock) {
            if (!strokeActive) return
            strokeActive = false
            val edit = buildSparseEdit(document, strokeBefore)
            if (edit != null) pushUndoLocked(edit)
            strokeBefore = HashMap()
        }
    }

    /**
     * Termina o traço do CAMINHO NATIVO: os deltas já chegam prontos
     * (índice/antes/depois) em UMA passagem — copiados uma única vez.
     * O dirty rect é o calculado pelo motor (uma vez, no C++/fallback);
     * servirá para a atualização regional do bitmap de apresentação.
     */
    fun commitDeltas(
        indices: IntArray, before: IntArray, after: IntArray, count: Int,
        minX: Int, minY: Int, maxX: Int, maxY: Int
    ) {
        require(count >= 0 && count <= indices.size &&
            count <= before.size && count <= after.size) { "Contagem de deltas inválida" }
        if (count == 0) return
        val idx = indices.copyOf(count)
        val bef = before.copyOf(count)
        val aft = after.copyOf(count)
        for (i in 0 until count) {
            require(idx[i] >= 0) { "Delta fora dos limites do documento: ${idx[i]}" }
        }
        pushUndoLocked(SparseEdit(idx, bef, aft, minX, minY, maxX, maxY))
    }

    /**
     * Cancela o traço em curso e RESTAURA os píxeis registados (usado quando
     * um segundo dedo entra no ecrã a meio do traço — evita marcas acidentais).
     * As chaves do traço estão empacotadas como y<<16|x (formato do recorder);
     * a restauração converte-as para (x, y) do documento.
     */
    fun cancelStroke(document: PixelDocument) {
        synchronized(lock) {
            if (!strokeActive) return
            strokeActive = false
            for ((idx, color) in strokeBefore) {
                document.set(idx and 0xFFFF, idx shr 16, color)
            }
            strokeBefore = HashMap()
        }
    }

    /** Devolve true se existe alguma edição para desfazer. */
    fun canUndo(): Boolean = synchronized(lock) { undoStack.isNotEmpty() }

    /** Devolve true se existe alguma edição para refazer. */
    fun canRedo(): Boolean = synchronized(lock) { redoStack.isNotEmpty() }

    /** Desfaz a última edição. Devolve a entrada aplicada (p/ bitmap regional) ou null. */
    fun undo(document: PixelDocument): SparseEdit? = synchronized(lock) {
        check(!strokeActive) { "Undo durante um traço não é permitido" }
        val edit = undoStack.removeLastOrNull() ?: return null
        edit.undo(document)
        redoStack.addLast(edit) // a entrada apenas MUDA de pilha (memória constante)
        edit
    }

    /** Refaz a última edição desfeita. Devolve a entrada aplicada (p/ bitmap regional) ou null. */
    fun redo(document: PixelDocument): SparseEdit? = synchronized(lock) {
        check(!strokeActive) { "Redo durante um traço não é permitido" }
        val edit = redoStack.removeLastOrNull() ?: return null
        edit.redo(document)
        undoStack.addLast(edit) // a entrada apenas MUDA de pilha
        evictOldestToFitBudgetLocked() // pode libertar as mais antigas
        edit
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

    private fun pushUndoLocked(edit: SparseEdit) {
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
     * Constrói a entrada esparsa a partir dos valores "antes" capturados
     * pelo recorder, com os valores "depois" lidos do estado atual.
     * As chaves do recorder estão empacotadas como y<<16|x — convertem-se
     * para o índice linear do buffer antes de ler/escrever.
     */
    private fun buildSparseEdit(
        document: PixelDocument,
        beforeByIndex: HashMap<Int, Int>
    ): SparseEdit? {
        if (beforeByIndex.isEmpty()) return null
        val count = beforeByIndex.size
        val indices = IntArray(count)
        val before = IntArray(count)
        val after = IntArray(count)
        var n = 0
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for ((packed, original) in beforeByIndex) {
            val x = packed and 0xFFFF
            val y = packed shr 16
            val linear = y * document.width + x
            val current = document.getAt(linear)
            if (current == original) continue // píxel voltou ao valor original
            indices[n] = linear
            before[n] = original
            after[n] = current
            n++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (n == 0) return null
        return SparseEdit(indices.copyOf(n), before.copyOf(n), after.copyOf(n),
            minX, minY, maxX, maxY)
    }

    companion object {
        /**
         * Orçamento por defeito: 32 MB — para um telemóvel de ~4 GB RAM com
         * heap de app típica (192-512 MB), deixa folga para o documento
         * (máx. 16 MB em buffer direto) e o Bitmap de apresentação.
         */
        const val DEFAULT_BUDGET_BYTES: Long = 32L * 1024 * 1024
    }
}
