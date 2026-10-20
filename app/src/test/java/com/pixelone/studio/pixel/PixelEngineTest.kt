package com.pixelone.studio.pixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes obrigatórios do motor 2D (teste A da especificação). */
class PixelEngineTest {

    // ------------------------------------------------------------------
    // Lápis
    // ------------------------------------------------------------------

    @Test
    fun `lápis desenha nos cantos e no centro`() {
        val doc = PixelDocument(16, 16)
        val cor = 0xFFF57C00.toInt()
        PixelEngine.drawPixel(doc, 0, 0, cor)
        PixelEngine.drawPixel(doc, 15, 0, cor)
        PixelEngine.drawPixel(doc, 0, 15, cor)
        PixelEngine.drawPixel(doc, 15, 15, cor)
        PixelEngine.drawPixel(doc, 8, 8, cor)
        assertEquals(cor, doc.get(0, 0))
        assertEquals(cor, doc.get(15, 0))
        assertEquals(cor, doc.get(0, 15))
        assertEquals(cor, doc.get(15, 15))
        assertEquals(cor, doc.get(8, 8))
    }

    @Test
    fun `linha contínua sem falhas em todas as direções`() {
        val doc = PixelDocument(32, 32)
        val cor = 0xFFFFFFFF.toInt()

        // Diagonais íngremes e rasantes + retas
        val pares = listOf(
            0 to 0, 31 to 31,   // diagonal principal
            0 to 31, 31 to 0,   // diagonal inversa
            0 to 0, 31 to 3,    // rasante
            0 to 0, 3 to 31,    // íngreme
            0 to 5, 31 to 5,    // horizontal
            5 to 0, 5 to 31     // vertical
        )
        for (i in pares.indices step 2) {
            val (x0, y0) = pares[i]
            val (x1, y1) = pares[i + 1]
            PixelEngine.drawLine(doc, x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt(), cor)
        }

        // Verifica conectividade 8-conectada de cada traço: cada píxel
        // desenhado tem um vizinho (8-direções) também desenhado.
        val total = doc.copyPixels().count { it == cor }
        assertTrue("devia ter muitos píxeis", total > 100)
        for (y in 0 until 32) for (x in 0 until 32) {
            if (doc.get(x, y) == cor) {
                val vizinho = (-1..1).any { dy -> (-1..1).any { dx ->
                    val nx = x + dx; val ny = y + dy
                    (nx != x || ny != y) && doc.inBounds(nx, ny) && doc.get(nx, ny) == cor
                }}
                assertTrue("píxel isolado em ($x,$y)", vizinho)
            }
        }
    }

    @Test
    fun `traço incremental por movimento do dedo é contínuo`() {
        // Simula movimentos discretos do dedo: cada evento desenha do último
        // ponto para o novo — a união tem de ser contínua.
        val doc = PixelDocument(24, 24)
        val cor = 0xFF00FF00.toInt()
        val pontos = listOf(0 to 0, 3 to 7, 6 to 14, 9 to 21, 12 to 23)
        for (i in 1 until pontos.size) {
            val (x0, y0) = pontos[i - 1]
            val (x1, y1) = pontos[i]
            PixelEngine.drawLine(doc, x0, y0, x1, y1, cor)
        }
        // Amostra: cada segmento em linha reta entre pontos consecutivos do
        // resultado deve ser 8-conectado (verificado por contagem > soma mínima)
        val count = doc.copyPixels().count { it == cor }
        assertTrue("esperado traço longo contínuo, obtido $count", count >= 25)
    }

    @Test
    fun `tamanho de brocha maior que 1 é quadrado determinístico`() {
        val doc = PixelDocument(10, 10)
        PixelEngine.drawPixel(doc, 5, 5, 0xFFFF0000.toInt(), brushSize = 3)
        for (y in 4..6) for (x in 4..6) assertEquals(0xFFFF0000.toInt(), doc.get(x, y))
        assertEquals(0, doc.get(3, 3))
        assertEquals(0, doc.get(7, 7))
        // Fora dos limites: recorta, não lança
        val doc2 = PixelDocument(4, 4)
        PixelEngine.drawPixel(doc2, 0, 0, 0xFF0000FF.toInt(), brushSize = 3)
        assertEquals(0xFF0000FF.toInt(), doc2.get(0, 0))
        assertEquals(0, doc2.get(2, 2)) // metade negativa recortada (carimbo 3 estende -1..1)
    }

    // ------------------------------------------------------------------
    // Borracha
    // ------------------------------------------------------------------

    @Test
    fun `borracha produz transparência real e não pinta com cor de fundo`() {
        val doc = PixelDocument(8, 8)
        PixelEngine.drawPixel(doc, 3, 3, 0xFFFF0000.toInt())
        PixelEngine.drawPixel(doc, 4, 3, 0xFF00FF00.toInt())
        // Apagar = desenhar com 0x00000000.toInt()
        PixelEngine.drawPixel(doc, 3, 3, 0x00000000.toInt())
        assertEquals(0x00000000.toInt(), doc.get(3, 3))
        assertEquals(0xFF00FF00.toInt(), doc.get(4, 3)) // vizinho intacto
    }

    // ------------------------------------------------------------------
    // Conta-gotas
    // ------------------------------------------------------------------

    @Test
    fun `conta-gotas recupera a cor RGBA correta`() {
        val doc = PixelDocument(4, 4)
        val cores = intArrayOf(0xFF112233.toInt(), 0x80AABBCC.toInt(), 0x00000000.toInt(), 0xFFFFFFFF.toInt())
        cores.forEachIndexed { i, c -> doc.set(i, 0, c) }
        cores.forEachIndexed { i, c -> assertEquals(c, PixelEngine.pickColor(doc, i, 0)) }
        // Transparente: recupera 0 (alpha 0)
        assertEquals(0x00000000.toInt(), PixelEngine.pickColor(doc, 0, 1))
    }

    // ------------------------------------------------------------------
    // Balde de preenchimento
    // ------------------------------------------------------------------

    @Test
    fun `fill respeita região contígua e limites`() {
        val doc = PixelDocument(8, 8)
        // Quadro externo vermelho com interior azul
        for (x in 0 until 8) { doc.set(x, 0, 0xFFFF0000.toInt()); doc.set(x, 7, 0xFFFF0000.toInt()) }
        for (y in 0 until 8) { doc.set(0, y, 0xFFFF0000.toInt()); doc.set(7, y, 0xFFFF0000.toInt()) }
        for (y in 1 until 7) for (x in 1 until 7) doc.set(x, y, 0xFF0000FF.toInt())

        val region = PixelEngine.floodFill(doc, 3, 3, 0xFF00FF00.toInt())
        assertTrue(region != null)
        // Interior verde, moldura vermelha intacta
        assertEquals(0xFF00FF00.toInt(), doc.get(3, 3))
        assertEquals(0xFF00FF00.toInt(), doc.get(1, 1))
        assertEquals(0xFFFF0000.toInt(), doc.get(0, 0))
        assertEquals(0xFFFF0000.toInt(), doc.get(7, 7))
        assertEquals(0xFFFF0000.toInt(), doc.get(3, 0))
        assertEquals(0xFFFF0000.toInt(), doc.get(0, 3))
    }

    @Test
    fun `fill para em cores de fronteira fora da tolerância`() {
        val doc = PixelDocument(8, 1)
        for (x in 0 until 8) doc.set(x, 0, if (x < 4) 0xFF202020.toInt() else 0xFF404040.toInt())
        PixelEngine.floodFill(doc, 0, 0, 0xFFFF0000.toInt(), tolerance = 0)
        assertEquals(0xFFFF0000.toInt(), doc.get(0, 0))
        assertEquals(0xFFFF0000.toInt(), doc.get(3, 0))
        assertEquals(0xFF404040.toInt(), doc.get(4, 0)) // fronteira preservada
        assertEquals(0xFF404040.toInt(), doc.get(7, 0))
    }

    @Test
    fun `fill com tolerância atravessa cores próximas`() {
        val doc = PixelDocument(6, 1)
        doc.set(0, 0, 0xFF202020.toInt())
        doc.set(1, 0, 0xFF242424.toInt()) // diff 4 por canal
        doc.set(2, 0, 0xFF2A2A2A.toInt()) // diff 10 por canal
        doc.set(3, 0, 0xFF808080.toInt()) // muito diferente
        PixelEngine.floodFill(doc, 0, 0, 0xFFFF0000.toInt(), tolerance = 16)
        assertEquals(0xFFFF0000.toInt(), doc.get(0, 0))
        assertEquals(0xFFFF0000.toInt(), doc.get(1, 0))
        assertEquals(0xFF808080.toInt(), doc.get(3, 0)) // além da tolerância
    }

    @Test
    fun `fill com tolerância não re-enfileira infinitamente quando cor destino é próxima`() {
        // Caso degenerado: cor de fill dentro da tolerância da cor de origem.
        val doc = PixelDocument(8, 8)
        for (y in 0 until 8) for (x in 0 until 8) doc.set(x, y, 0xFF202020.toInt())
        val cor = 0xFF262626.toInt() // diff 6 < tolerância 10
        val region = PixelEngine.floodFill(doc, 0, 0, cor, tolerance = 10)
        assertTrue(region != null)
        // Todos os píxeis acabam com a cor de fill (não fica mistura nem loop)
        for (y in 0 until 8) for (x in 0 until 8) assertEquals(cor, doc.get(x, y))
    }

    @Test
    fun `fill sobre transparência preserva vizinhos opacos`() {
        val doc = PixelDocument(4, 1)
        doc.set(0, 0, 0x00000000.toInt())
        doc.set(1, 0, 0x00000000.toInt())
        doc.set(2, 0, 0xFFFFFFFF.toInt())
        PixelEngine.floodFill(doc, 0, 0, 0x80FF00FF.toInt())
        assertEquals(0x80FF00FF.toInt(), doc.get(0, 0))
        assertEquals(0x80FF00FF.toInt(), doc.get(1, 0))
        assertEquals(0xFFFFFFFF.toInt(), doc.get(2, 0))
    }

    @Test
    fun `fill fora dos limites ou sem mudança devolve null`() {
        val doc = PixelDocument(4, 4)
        assertNull(PixelEngine.floodFill(doc, -1, 0, 0xFFFF0000.toInt()))
        assertNull(PixelEngine.floodFill(doc, 4, 0, 0xFFFF0000.toInt()))
        assertNull(PixelEngine.floodFill(doc, 0, 0, 0x00000000.toInt())) // já é a cor
    }

    @Test
    fun `fill em documento inteiro devolve bbox completo e colorido`() {
        val doc = PixelDocument(5, 5)
        val region = PixelEngine.floodFill(doc, 0, 0, 0xFF112233.toInt())
        assertTrue(region.contentEquals(intArrayOf(0, 0, 4, 4)))
        for (y in 0 until 5) for (x in 0 until 5) assertEquals(0xFF112233.toInt(), doc.get(x, y))
    }
}
