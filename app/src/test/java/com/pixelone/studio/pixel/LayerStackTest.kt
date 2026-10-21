package com.pixelone.studio.pixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da PILHA DE CAMADAS (Ronda B): composição source-over com alfa reto,
 * opacidade, visibilidade, reordenação e limites com erro recuperável.
 */
class LayerStackTest {

    @Test
    fun `nova pilha tem uma camada Base e é composta com os píxeis dados`() {
        val pixels = IntArray(16 * 16) { 0xFFFF0000.toInt() } // vermelho opaco
        val stack = LayerStack(16, 16).apply { seedSingleLayer(pixels) }
        assertEquals(1, stack.count)
        assertEquals("Base", stack.active().name)
        val composed = stack.compose()
        assertTrue(composed.all { it == 0xFFFF0000.toInt() })
    }

    @Test
    fun `composição source-over mistura com opacidade (alfa reto)`() {
        val red = IntArray(8 * 8) { 0xFFFF0000.toInt() }      // vermelho opaco (base)
        val green = IntArray(8 * 8) { 0x8000FF00.toInt() }    // verde 50% (topo)
        val stack = LayerStack(8, 8)
        stack.seedSingleLayer(red)
        stack.addLayer("Topo", green)

        val composed = stack.compose()
        val expected = composed[0]
        // Alfa final opaco (verde 50% sobre vermelho opaco).
        assertEquals(0xFF shl 24, expected and 0xFF000000.toInt())
        // Verde 50% sobre vermelho opaco: r ≈ 127 (base), g ≈ 128 (topo/2), b = 0.
        val r = (expected shr 16) and 0xFF
        val g = (expected shr 8) and 0xFF
        val b = expected and 0xFF
        assertTrue("r=$r devia ser ~127", r in 126..128)
        assertTrue("g=$g devia ser ~128", g in 127..129)
        assertEquals("b: nenhum canal azul nas duas cores", 0, b)
    }

    @Test
    fun `opacidade 100 devolve a cor do topo exatamente`() {
        val red = IntArray(4 * 4) { 0xFFFF0000.toInt() }
        val blue = IntArray(4 * 4) { 0xFF0000FF.toInt() }
        val stack = LayerStack(4, 4)
        stack.seedSingleLayer(red)
        stack.addLayer("Topo", blue)
        stack.setOpacity(1, 100)
        assertTrue(stack.compose().all { it == 0xFF0000FF.toInt() })
    }

    @Test
    fun `visibilidade desligada remove a camada da composição`() {
        val red = IntArray(4 * 4) { 0xFFFF0000.toInt() }
        val blue = IntArray(4 * 4) { 0xFF0000FF.toInt() }
        val stack = LayerStack(4, 4)
        stack.seedSingleLayer(red)
        stack.addLayer("Topo", blue)
        stack.toggleVisible(1)
        assertFalse(stack.layers[1].visible)
        assertTrue(stack.compose().all { it == 0xFFFF0000.toInt() })
    }

    @Test
    fun `reordenação muda quem fica por cima`() {
        val red = IntArray(4 * 4) { 0xFFFF0000.toInt() }
        val blue = IntArray(4 * 4) { 0xFF0000FF.toInt() }
        val stack = LayerStack(4, 4)
        stack.seedSingleLayer(red)
        stack.addLayer("Topo", blue)
        // Topo é o índice 1 (azul) — desce para baixo → vermelho fica por cima.
        stack.moveDown(1)
        assertTrue(stack.compose().all { it == 0xFFFF0000.toInt() })
        stack.moveUp(0)
        assertTrue(stack.compose().all { it == 0xFF0000FF.toInt() })
    }

    @Test
    fun `camada ativa muda com setActive e removeLayer`() {
        val stack = LayerStack(4, 4)
        stack.seedSingleLayer(IntArray(16))
        stack.addLayer("B")
        stack.addLayer("C")
        assertEquals(2, stack.activeIndex)
        stack.setActive(0)
        assertEquals(0, stack.activeIndex)
        stack.removeLayer(1)
        assertEquals(2, stack.count)
        assertEquals("ativa era 0 e continua válida — não muda", 0, stack.activeIndex)
        stack.removeLayer(0)
        assertEquals(1, stack.count)
        stack.removeLayer(0) // a última NUNCA é removida
        assertEquals(1, stack.count)
    }

    @Test
    fun `exceder o máximo de camadas lança erro recuperável`() {
        val stack = LayerStack(4, 4)
        stack.seedSingleLayer(IntArray(16))
        repeat(LayerStack.MAX_LAYERS - 1) { stack.addLayer("+$it") }
        assertEquals(LayerStack.MAX_LAYERS, stack.count)
        val ex = assertThrows(LayerStack.LayerLimitException::class.java) {
            stack.addLayer("extra")
        }
        assertTrue(ex.message!!.contains("Máximo"))
    }

    @Test
    fun `composeRegion devolve a mesma composição da região do compose completo`() {
        val w = 12; val h = 12
        val stack = LayerStack(w, h)
        stack.seedSingleLayer(IntArray(w * h) { 0xFFFF0000.toInt() })
        stack.addLayer(
            "Topo",
            IntArray(w * h) { i -> if (i % 2 == 0) 0x8000FF00.toInt() else 0x00000000 }
        )
        val full = stack.compose()
        val region = IntArray(4 * 4)
        stack.composeRegion(4, 4, 4, 4, region)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                assertEquals(
                    "composeRegion divergiu do compose em (${4 + col},${4 + row})",
                    full[(4 + row) * w + (4 + col)],
                    region[row * 4 + col]
                )
            }
        }
    }
}
