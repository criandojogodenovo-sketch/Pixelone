package com.pixelone.studio.studio3d

import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MISSÃO UI/SELEÇÃO — testes da ADOÇÃO de píxeis externos na pilha do projeto
 * ([PixelAdoption]) — a função pura extraída de adoptFromStudio2D e usada por
 * copyEmbeddedToDocument (textura embutida → projeto).
 *
 * Regras provadas:
 *  - MESMAS dimensões: funde na camada como deltas ESPARSOS (só texels
 *    diferentes), com bbox exata — UMA entrada de histórico;
 *  - píxeis idênticos → null (entrada de histórico vazia é proibida);
 *  - dimensões diferentes → decisão de substituição (a tratar pelo chamador);
 *  - tamanho de array errado → IllegalArgumentException (erro cedo, claro).
 */
class PixelAdoptionTest {

    @Test
    fun `mesmas dimensões é decidido por w e h`() {
        assertTrue(PixelAdoption.sameDimensions(64, 64, 64, 64))
        assertTrue(PixelAdoption.sameDimensions(256, 128, 256, 128))
        assertTrue(!PixelAdoption.sameDimensions(64, 64, 64, 32))
        assertTrue(!PixelAdoption.sameDimensions(128, 64, 64, 64))
    }

    @Test
    fun `fusão só conta texels diferentes e devolve bbox exata`() {
        val w = 4
        val h = 4
        val layer = PixelDocument(w, h)
        // Fundo opaco constante.
        for (i in 0 until w * h) layer.setAt(i, 0xFF222222.toInt())

        // Novos píxeis: iguais exceto (1,1) e (3,0).
        val pixels = IntArray(w * h) { 0xFF222222.toInt() }
        pixels[1 * w + 1] = 0xFFF57C00.toInt() // laranja
        pixels[0 * w + 3] = 0xFF00FF00.toInt() // verde

        val result = PixelAdoption.mergeIntoLayer(layer, pixels, w, h)
        assertNotNull(result)
        val r = result!!
        assertEquals(2, r.count)
        assertEquals(1, r.minX)
        assertEquals(0, r.minY)
        assertEquals(3, r.maxX)
        assertEquals(1, r.maxY)
        // A camada FOI escrita nos texels alterados.
        assertEquals(0xFFF57C00.toInt(), layer.get(1, 1))
        assertEquals(0xFF00FF00.toInt(), layer.get(3, 0))
        // ...e PRESERVADA onde nada mudou.
        assertEquals(0xFF222222.toInt(), layer.get(0, 0))
        assertEquals(0xFF222222.toInt(), layer.get(2, 2))
    }

    @Test
    fun `píxeis idênticos devolvem null (sem entrada de histórico vazia)`() {
        val w = 3
        val h = 2
        val layer = PixelDocument(w, h)
        val pixels = IntArray(w * h) { 0xFF101010.toInt() }
        for (i in 0 until w * h) layer.setAt(i, 0xFF101010.toInt())
        assertNull(PixelAdoption.mergeIntoLayer(layer, pixels, w, h))
    }

    @Test
    fun `tamanho de array errado é erro claro e imediato`() {
        val layer = PixelDocument(2, 2)
        assertThrows(IllegalArgumentException::class.java) {
            PixelAdoption.mergeIntoLayer(layer, IntArray(3), 2, 2)
        }
    }

    @Test
    fun `fusão com transparencia preserva o valor ARGB exato`() {
        val layer = PixelDocument(2, 1)
        layer.setAt(0, 0xFF000000.toInt())
        layer.setAt(1, 0xFF000000.toInt())
        val pixels = intArrayOf(0x00000000, 0x80F57C00.toInt())
        val result = PixelAdoption.mergeIntoLayer(layer, pixels, 2, 1)
        assertNotNull(result)
        assertEquals(2, result!!.count)
        assertEquals(0x00000000, layer.getAt(0))
        assertEquals(0x80F57C00.toInt(), layer.getAt(1))
    }
}
