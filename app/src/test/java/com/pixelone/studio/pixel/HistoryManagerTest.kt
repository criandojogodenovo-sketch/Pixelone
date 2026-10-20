package com.pixelone.studio.pixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes obrigatórios do histórico (teste B da especificação). */
class HistoryManagerTest {

    private fun docPreenchido(): PixelDocument {
        val doc = PixelDocument(8, 8)
        for (y in 0 until 8) for (x in 0 until 8) doc.set(x, y, 0xFF202020.toInt())
        return doc
    }

    @Test
    fun `undo restaura o estado anterior e redo reaplica`() {
        val doc = docPreenchido()
        val hist = HistoryManager()
        val antes = doc.copyPixels()

        // Traço: um pixel vermelho
        hist.beginStroke()
        PixelEngine.drawPixel(doc, 2, 2, 0xFFFF0000.toInt(), recorder = hist)
        hist.commitStroke(doc)

        assertEquals(0xFFFF0000.toInt(), doc.get(2, 2))
        assertNotNull(hist.undo(doc))
        assertEquals(0xFF202020.toInt(), doc.get(2, 2))
        assertEquals(antes[2 * 8 + 2], doc.get(2, 2))
        assertNotNull(hist.redo(doc))
        assertEquals(0xFFFF0000.toInt(), doc.get(2, 2))
    }

    @Test
    fun `uma nova edição após undo limpa a pilha redo`() {
        val doc = docPreenchido()
        val hist = HistoryManager()

        hist.beginStroke()
        PixelEngine.drawPixel(doc, 1, 1, 0xFFFF0000.toInt(), recorder = hist)
        hist.commitStroke(doc)

        hist.beginStroke()
        PixelEngine.drawPixel(doc, 2, 2, 0xFF00FF00.toInt(), recorder = hist)
        hist.commitStroke(doc)

        assertNotNull(hist.undo(doc)) // remove o verde
        assertEquals(1, hist.redoCount())

        // Nova edição → redo limpo
        hist.beginStroke()
        PixelEngine.drawPixel(doc, 3, 3, 0xFF0000FF.toInt(), recorder = hist)
        hist.commitStroke(doc)
        assertEquals(0, hist.redoCount())
        assertFalse(hist.canRedo())
        assertNotNull(hist.undo(doc))
        assertEquals(0xFF202020.toInt(), doc.get(3, 3))
        // O verde NÃO voltou: a edição nova limpa a pilha redo (comportamento padrão)
        assertEquals(0xFF202020.toInt(), doc.get(2, 2))
        // O vermelho (entrada 1) permanece aplicado no documento
        assertEquals(0xFFFF0000.toInt(), doc.get(1, 1))
    }

    @Test
    fun `um traço contínuo é UMA operação lógica e não uma por píxel`() {
        val doc = docPreenchido()
        val hist = HistoryManager()

        hist.beginStroke()
        // Traço longo: dezenas de píxeis num único "arrastar de dedo"
        PixelEngine.drawLine(doc, 0, 0, 7, 7, 0xFFFF0000.toInt(), recorder = hist)
        PixelEngine.drawLine(doc, 7, 0, 0, 7, 0xFF0000FF.toInt(), recorder = hist)
        hist.commitStroke(doc)

        assertEquals("traço inteiro devia ser 1 entrada", 1, hist.undoCount())
        assertNotNull(hist.undo(doc))
        // Tudo volta ao estado original com UM undo
        for (y in 0 until 8) for (x in 0 until 8) assertEquals(0xFF202020.toInt(), doc.get(x, y))
    }

    @Test
    fun `fill é uma entrada atómica`() {
        val doc = docPreenchido()
        val hist = HistoryManager()

        hist.beginStroke()
        PixelEngine.floodFill(doc, 4, 4, 0xFFFF0000.toInt(), recorder = hist)
        hist.commitStroke(doc)

        assertEquals(1, hist.undoCount())
        assertNotNull(hist.undo(doc))
        for (y in 0 until 8) for (x in 0 until 8) assertEquals(0xFF202020.toInt(), doc.get(x, y))
    }

    @Test
    fun `o histórico respeita o orçamento de memória`() {
        val doc = PixelDocument(64, 64)
        val hist = HistoryManager(memoryBudgetBytes = 8_192L) // 8 KB: ~4 traços de 32x32

        // Cada traço altera uma área 32x32 → (32*32*2)*4 = 8192 bytes por entrada
        for (i in 0 until 6) {
            hist.beginStroke()
            val x0 = (i * 3) % 32
            PixelEngine.drawLine(doc, x0, 0, x0 + 31, 31, 0xFF000000.toInt() or i, recorder = hist)
            hist.commitStroke(doc)
        }
        // Com orçamento 8192 e entradas de ~8192 bytes: mantém-se perto de 1 entrada
        assertTrue(
            "histórico devia respeitar o orçamento: ${hist.memoryBytesUsed()}",
            hist.memoryBytesUsed() <= 8_192L + 8_192L // tolerância de uma entrada indivisível
        )
        // E o undo continua funcional
        assertTrue(hist.canUndo())
        assertNotNull(hist.undo(doc))
    }

    @Test
    fun `entrada maior que o orçamento é mantida sozinha`() {
        val doc = PixelDocument(128, 128)
        val hist = HistoryManager(memoryBudgetBytes = 1024L) // 1 KB
        hist.beginStroke()
        PixelEngine.floodFill(doc, 0, 0, 0xFF123456.toInt(), recorder = hist)
        hist.commitStroke(doc)
        assertEquals(1, hist.undoCount())
        assertTrue(hist.memoryBytesUsed() > 1024L) // mantida apesar de exceder
        assertNotNull(hist.undo(doc))
        assertEquals(0x00000000.toInt(), doc.get(0, 0))
    }

    @Test
    fun `cancelStroke restaura píxeis e não cria entrada`() {
        val doc = docPreenchido()
        val hist = HistoryManager()
        hist.beginStroke()
        PixelEngine.drawLine(doc, 0, 0, 7, 7, 0xFFFF0000.toInt(), recorder = hist)
        hist.cancelStroke(doc)
        assertEquals(0, hist.undoCount())
        for (y in 0 until 8) for (x in 0 until 8) assertEquals(0xFF202020.toInt(), doc.get(x, y))
    }

    @Test
    fun `traço sem alterações não cria entrada`() {
        val doc = docPreenchido()
        val hist = HistoryManager()
        hist.beginStroke()
        // Desenha com a cor já existente → nenhum píxel muda
        PixelEngine.drawLine(doc, 0, 0, 7, 7, 0xFF202020.toInt(), recorder = hist)
        hist.commitStroke(doc)
        assertEquals(0, hist.undoCount())
        assertFalse(hist.canUndo())
    }

    @Test
    fun `undo e redo sem entradas devolvem false`() {
        val doc = docPreenchido()
        val hist = HistoryManager()
        assertNull(hist.undo(doc))
        assertNull(hist.redo(doc))
    }

    @Test
    fun `clear limpa tudo`() {
        val doc = docPreenchido()
        val hist = HistoryManager()
        hist.beginStroke()
        PixelEngine.drawPixel(doc, 1, 1, 0xFFFF0000.toInt(), recorder = hist)
        hist.commitStroke(doc)
        hist.clear()
        assertFalse(hist.canUndo())
        assertFalse(hist.canRedo())
        assertEquals(0L, hist.memoryBytesUsed())
    }

    @Test
    fun `beginStroke duplanca lança erro de programação`() {
        val hist = HistoryManager()
        hist.beginStroke()
        assertThrows(IllegalStateException::class.java) { hist.beginStroke() }
    }

    @Test
    fun `undo restaura região parcial e preserva o resto`() {
        val doc = docPreenchido()
        val hist = HistoryManager()
        hist.beginStroke()
        PixelEngine.drawPixel(doc, 0, 0, 0xFFFF0000.toInt(), recorder = hist)
        PixelEngine.drawPixel(doc, 7, 7, 0xFF00FF00.toInt(), recorder = hist)
        hist.commitStroke(doc)

        assertNotNull(hist.undo(doc))
        assertEquals(0xFF202020.toInt(), doc.get(0, 0))
        assertEquals(0xFF202020.toInt(), doc.get(7, 7))
        // píxeis médios intactos
        assertEquals(0xFF202020.toInt(), doc.get(3, 4))
    }
}
