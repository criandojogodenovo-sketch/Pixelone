package com.pixelone.studio.pixel

/**
 * HISTÓRICO GLOBAL (desfazer/refazer) ENTRE CAMADAS — Ronda B.
 *
 * Estratégia (mantém as decisões do [HistoryManager], agora multi-camada):
 *  - Cada entrada é (índice da camada, [HistoryManager.SparseEdit]) — deltas
 *    esparsos por píxel, NUNCA snapshots integrais; memória proporcional aos
 *    píxeis realmente alterados.
 *  - O histórico é PARTILHADO entre pintura 3D e UV (é o mesmo funil — as
 *    duas fontes chamam as mesmas funções) e entre CAMADAS: um undo/remove
 *    devolve a edição e diz em QUE camada aplicar ([UndoEntry.layerIndex]).
 *  - UM traço contínuo = UMA entrada; um balde/forma = UMA entrada.
 *  - NOVA edição limpa a pilha de refazer (comportamento padrão).
 *  - Orçamento de memória: [memoryBudgetBytes] nas duas pilhas; as entradas
 *    mais antigas são libertadas primeiro (por defeito 32 MB — aparelhos 4 GB).
 *
 * THREADS: as pilhas são sincronizadas; o ViewModel garante que as chamadas
 * chegam em série (executor de mutação único).
 */
class LayeredHistory(
    val memoryBudgetBytes: Long = DEFAULT_BUDGET_BYTES
) : PixelEngine.StrokeRecorder {

    /** Entrada de undo: camada + edição esparso. */
    class UndoEntry(val layerIndex: Int, val edit: HistoryManager.SparseEdit) {
        val minX: Int get() = edit.minX
        val minY: Int get() = edit.minY
        val maxX: Int get() = edit.maxX
        val maxY: Int get() = edit.maxY
    }

    private val lock = Any()
    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    private var currentMemoryBytes = 0L

    // Traço em curso (recorder)
    private var strokeLayer = -1
    private var strokeActive = false
    private var strokeBefore = HashMap<Int, Int>() // y<<16|x -> cor original
    private var strokeMinX = 0; private var strokeMaxX = -1
    private var strokeMinY = 0; private var strokeMaxY = -1

    // ------------------------------------------------------------------
    // Caminho recorder (traço: lápis/borracha/formas)
    // ------------------------------------------------------------------

    /** Começa um traço na camada [layerIndex] (uma operação lógica). */
    fun beginStroke(layerIndex: Int) {
        synchronized(lock) {
            check(!strokeActive) { "Traço já ativo — termine com commitStroke" }
            strokeActive = true
            strokeLayer = layerIndex
            strokeBefore = HashMap()
            strokeMinX = 0; strokeMaxX = -1
            strokeMinY = 0; strokeMaxY = -1
        }
    }

    /** [PixelEngine.StrokeRecorder] — guarda a cor original (1.ª vez por píxel). */
    override fun onBeforeChange(x: Int, y: Int, beforeColor: Int) {
        synchronized(lock) {
            if (!strokeActive) return
            strokeBefore.putIfAbsent(y shl 16 or x, beforeColor)
        }
    }

    /** [PixelEngine.StrokeRecorder] — atualiza o bbox alterado. */
    override fun onChanged(x: Int, y: Int) {
        synchronized(lock) {
            if (!strokeActive) return
            if (strokeMaxX < strokeMinX) {
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
     * Termina o traço e cria UMA entrada esparsa comparando com o estado
     * atual da camada. No-op se o traço não alterou píxeis.
     */
    fun commitStroke(stack: LayerStack) {
        synchronized(lock) {
            if (!strokeActive) return
            strokeActive = false
            val layerIndex = strokeLayer
            val doc = stack.layers.getOrNull(layerIndex)?.doc
            if (doc == null) { strokeBefore = HashMap(); return }
            val edit = buildSparseEdit(doc, strokeBefore)
            if (edit != null) pushUndoLocked(UndoEntry(layerIndex, edit))
            strokeBefore = HashMap()
        }
    }

    /** Cancela o traço em curso e RESTAURA os píxeis registados. */
    fun cancelStroke(stack: LayerStack) {
        synchronized(lock) {
            if (!strokeActive) return
            strokeActive = false
            val doc = stack.layers.getOrNull(strokeLayer)?.doc
            if (doc != null) {
                for ((packed, color) in strokeBefore) {
                    doc.set(packed and 0xFFFF, packed shr 16, color)
                }
            }
            strokeBefore = HashMap()
        }
    }

    // ------------------------------------------------------------------
    // Caminho de deltas prontos (balde/formas em lote)
    // ------------------------------------------------------------------

    /** Registra UMA entrada a partir de deltas esparsos já calculados. */
    fun commitDeltas(
        layerIndex: Int,
        indices: IntArray, before: IntArray, after: IntArray, count: Int,
        minX: Int, minY: Int, maxX: Int, maxY: Int
    ) {
        require(count >= 0 && count <= indices.size &&
            count <= before.size && count <= after.size) { "Contagem de deltas inválida" }
        if (count == 0) return
        val edit = HistoryManager.SparseEdit(
            indices.copyOf(count), before.copyOf(count), after.copyOf(count),
            minX, minY, maxX, maxY
        )
        synchronized(lock) { pushUndoLocked(UndoEntry(layerIndex, edit)) }
    }

    // ------------------------------------------------------------------
    // Undo / redo
    // ------------------------------------------------------------------

    /**
     * Desfaz a última edição (em QUALQUER camada). Aplica os valores "antes"
     * no documento da camada registada e devolve a entrada (com a camada e o
     * retângulo sujo para recomposição regional) — null se não há nada.
     */
    fun undo(stack: LayerStack): UndoEntry? = synchronized(lock) {
        check(!strokeActive) { "Undo durante um traço não é permitido" }
        val entry = undoStack.removeLastOrNull() ?: return null
        val doc = stack.layers.getOrNull(entry.layerIndex)?.doc
        if (doc != null) entry.edit.undo(doc)
        redoStack.addLast(entry)
        entry
    }

    /** Refaz a última edição desfeita (aplica "depois" na camada registada). */
    fun redo(stack: LayerStack): UndoEntry? = synchronized(lock) {
        check(!strokeActive) { "Redo durante um traço não é permitido" }
        val entry = redoStack.removeLastOrNull() ?: return null
        val doc = stack.layers.getOrNull(entry.layerIndex)?.doc
        if (doc != null) entry.edit.redo(doc)
        undoStack.addLast(entry)
        evictOldestToFitBudgetLocked()
        entry
    }

    fun canUndo(): Boolean = synchronized(lock) { undoStack.isNotEmpty() }
    fun canRedo(): Boolean = synchronized(lock) { redoStack.isNotEmpty() }
    fun undoCount(): Int = synchronized(lock) { undoStack.size }
    fun redoCount(): Int = synchronized(lock) { redoStack.size }
    fun memoryBytesUsed(): Long = synchronized(lock) { currentMemoryBytes }

    /** Limpa tudo (abrir/criar documento novo). */
    fun clear() {
        synchronized(lock) {
            undoStack.clear(); redoStack.clear(); currentMemoryBytes = 0L
            strokeActive = false; strokeBefore = HashMap()
        }
    }

    // ------------------------------------------------------------------
    // Internos (lock assumido)
    // ------------------------------------------------------------------

    private fun pushUndoLocked(entry: UndoEntry) {
        while (redoStack.isNotEmpty()) {
            currentMemoryBytes -= redoStack.removeFirst().edit.memoryBytes
        }
        undoStack.addLast(entry)
        currentMemoryBytes += entry.edit.memoryBytes
        evictOldestToFitBudgetLocked()
    }

    private fun evictOldestToFitBudgetLocked() {
        while (currentMemoryBytes > memoryBudgetBytes && undoStack.size > 1) {
            val oldest = undoStack.removeFirst()
            currentMemoryBytes -= oldest.edit.memoryBytes
        }
    }

    private fun buildSparseEdit(doc: PixelDocument, beforeByIndex: HashMap<Int, Int>): HistoryManager.SparseEdit? {
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
            if (x >= doc.width || y >= doc.height) continue
            val linear = y * doc.width + x
            val current = doc.getAt(linear)
            if (current == original) continue
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
        return HistoryManager.SparseEdit(
            indices.copyOf(n), before.copyOf(n), after.copyOf(n), minX, minY, maxX, maxY
        )
    }

    companion object {
        /** 32 MB — igual ao [HistoryManager]; seguro em aparelhos de 4 GB. */
        const val DEFAULT_BUDGET_BYTES: Long = 32L * 1024 * 1024
    }
}
