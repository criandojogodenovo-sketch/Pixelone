package com.pixelone.studio.pixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do HISTÓRICO GLOBAL ENTRE CAMADAS (Ronda B): um traço por camada
 * diferente, desfazer/refazer na camada CERTA, redo limpo por nova edição e
 * orçamento de memória.
 */
class LayeredHistoryTest {

    private fun twoLayerStack(): LayerStack {
        val stack = LayerStack(8, 8)
        stack.seedSingleLayer(IntArray(64) { 0xFFFF0000.toInt() })
        stack.addLayer("Topo")
        return stack
    }

    @Test
    fun `traço numa camada e depois noutra - undo devolve na ordem inversa`() {
        val stack = twoLayerStack()
        val history = LayeredHistory()
        val recorder0: PixelEngine.StrokeRecorder = history

        // Traço 1: camada 0 (base), píxel (1,1) → azul.
        history.beginStroke(0)
        val before = stack.layers[0].doc.get(1, 1)
        recorder0.onBeforeChange(1, 1, before)
        stack.layers[0].doc.set(1, 1, 0xFF0000FF.toInt())
        recorder0.onChanged(1, 1)
        history.commitStroke(stack)

        // Traço 2: camada 1 (topo), píxel (2,2) → verde.
        history.beginStroke(1)
        recorder0.onBeforeChange(2, 2, 0x00000000)
        stack.layers[1].doc.set(2, 2, 0xFF00FF00.toInt())
        recorder0.onChanged(2, 2)
        history.commitStroke(stack)

        assertEquals(2, history.undoCount())

        // Undo 1: reverte o traço da camada 1 (topo).
        val e1 = history.undo(stack)
        assertEquals(1, e1!!.layerIndex)
        assertEquals(0x00000000, stack.layers[1].doc.get(2, 2))
        // A camada 0 continua com o traço 1 aplicado (só se desfaz no próximo).
        assertEquals(0xFF0000FF.toInt(), stack.layers[0].doc.get(1, 1))

        // Undo 2: reverte o traço da camada 0 → volta ao vermelho original.
        val e2 = history.undo(stack)
        assertEquals(0, e2!!.layerIndex)
        assertEquals(0xFFFF0000.toInt(), stack.layers[0].doc.get(1, 1))

        // Refazer repõe AMBOS na ordem correta.
        val r1 = history.redo(stack)
        assertEquals(0, r1!!.layerIndex)
        assertEquals(0xFF0000FF.toInt(), stack.layers[0].doc.get(1, 1))
        val r2 = history.redo(stack)
        assertEquals(1, r2!!.layerIndex)
        assertEquals(0xFF00FF00.toInt(), stack.layers[1].doc.get(2, 2))
    }

    @Test
    fun `nova edição limpa a pilha de redo`() {
        val stack = LayerStack(4, 4)
        stack.seedSingleLayer(IntArray(16))
        val history = LayeredHistory()
        history.beginStroke(0)
        history.onBeforeChange(0, 0, 0x00000000)
        stack.activeDoc().set(0, 0, 0xFFFFFFFF.toInt())
        history.onChanged(0, 0)
        history.commitStroke(stack)

        history.undo(stack)
        assertTrue(history.canRedo())

        history.beginStroke(0)
        history.onBeforeChange(1, 1, 0x00000000)
        stack.activeDoc().set(1, 1, 0xFF112233.toInt())
        history.onChanged(1, 1)
        history.commitStroke(stack)

        assertFalse("nova edição devia limpar o redo", history.canRedo())
    }

    @Test
    fun `commitDeltas registra uma entrada com bbox correto`() {
        val stack = LayerStack(8, 8)
        stack.seedSingleLayer(IntArray(64))
        val history = LayeredHistory()
        val indices = intArrayOf(9, 10)
        val before = intArrayOf(0x00000000, 0x00000000)
        val after = intArrayOf(0xFF010101.toInt(), 0xFF020202.toInt())
        stack.layers[0].doc.setAt(9, after[0])
        stack.layers[0].doc.setAt(10, after[1])
        history.commitDeltas(0, indices, before, after, 2, 1, 1, 2, 1)
        val entry = history.undo(stack)
        assertEquals(0, entry!!.layerIndex)
        assertEquals(1, entry.minX)
        assertEquals(1, entry.minY)
        assertEquals(2, entry.maxX)
        assertEquals(1, entry.maxY)
        assertEquals(0x00000000, stack.layers[0].doc.getAt(9))
    }

    @Test
    fun `orçamento de memória liberta as entradas mais antigas (mantém ≥1)`() {
        val stack = LayerStack(16, 16)
        stack.seedSingleLayer(IntArray(256))
        // Orçamento pequeno: ~3 entradas de 16 píxeis ×12 bytes = 192 bytes.
        val history = LayeredHistory(memoryBudgetBytes = 500)
        for (stroke in 0 until 10) {
            history.beginStroke(0)
            for (i in 0 until 16) {
                val idx = stroke * 16 + i
                history.onBeforeChange(idx % 16, idx / 16, 0x00000000)
                stack.activeDoc().setAt(idx, 0xFF000000.toInt() or stroke)
                history.onChanged(idx % 16, idx / 16)
            }
            history.commitStroke(stack)
        }
        assertTrue(history.undoCount() >= 1)
        assertTrue(history.undoCount() < 10)
        // As mais antigas foram libertadas — undo ainda funciona.
        val last = history.undo(stack)
        assertTrue(last != null)
    }

    @Test
    fun `undo sem entradas devolve null`() {
        val stack = LayerStack(4, 4)
        stack.seedSingleLayer(IntArray(16))
        val history = LayeredHistory()
        assertNull(history.undo(stack))
        assertNull(history.redo(stack))
    }
}
