package com.pixelone.studio.pixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes do documento 2D (obrigatórios — teste A do motor 2D). */
class PixelDocumentTest {

    @Test
    fun `documento novo é totalmente transparente`() {
        val doc = PixelDocument(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) {
            assertEquals(0x00000000.toInt(), doc.get(x, y))
        }
    }

    @Test
    fun `set e get nos quatro cantos e no centro`() {
        val doc = PixelDocument(9, 9)
        val cores = listOf(
            0 to 0, 8 to 0, 0 to 8, 8 to 8, 4 to 4
        )
        val valores = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0x80FFFFFF.toInt(), 0x00ABCDEF.toInt())
        cores.forEachIndexed { i, (x, y) -> doc.set(x, y, valores[i]) }
        cores.forEachIndexed { i, (x, y) -> assertEquals(valores[i], doc.get(x, y)) }
    }

    @Test
    fun `fora dos limites get devolve 0 e set é ignorado`() {
        val doc = PixelDocument(4, 4)
        doc.set(-1, 0, 0xFFFF0000.toInt())
        doc.set(4, 0, 0xFFFF0000.toInt())
        doc.set(0, -1, 0xFFFF0000.toInt())
        doc.set(0, 4, 0xFFFF0000.toInt())
        for (y in 0 until 4) for (x in 0 until 4) assertEquals(0, doc.get(x, y))
        assertEquals(0, doc.get(-1, 0))
        assertEquals(0, doc.get(4, 4))
    }

    @Test
    fun `transparência real é preservada`() {
        val doc = PixelDocument(2, 2)
        doc.set(0, 0, 0x00000000.toInt()) // transparente
        doc.set(1, 0, 0x80FF57C0.toInt()) // semi-transparente
        doc.set(0, 1, 0xFFFFFFFF.toInt()) // opaco
        assertEquals(0x00000000.toInt(), doc.get(0, 0))
        assertEquals(0x80FF57C0.toInt(), doc.get(1, 0))
        assertEquals(0xFFFFFFFF.toInt(), doc.get(0, 1))
    }

    @Test
    fun `validação rejeita dimensões excessivas antes de alocar`() {
        assertThrows(IllegalArgumentException::class.java) { PixelDocument(0, 10) }
        assertThrows(IllegalArgumentException::class.java) { PixelDocument(10, 0) }
        assertThrows(IllegalArgumentException::class.java) { PixelDocument(-5, 5) }
        assertThrows(IllegalArgumentException::class.java) { PixelDocument(PixelDocument.MAX_DIMENSION + 1, 1) }
        // O limite máximo é válido (não lança):
        PixelDocument(PixelDocument.MAX_DIMENSION, PixelDocument.MAX_DIMENSION)
        // Uma linha a mais excede o máximo de píxeis → lança ANTES de alocar:
        assertThrows(IllegalArgumentException::class.java) {
            PixelDocument(PixelDocument.MAX_DIMENSION, PixelDocument.MAX_DIMENSION + 1)
        }
    }

    @Test
    fun `conversão RGBA bytes é sem perdas`() {
        val doc = PixelDocument(3, 2)
        val cores = intArrayOf(
            0x00000000.toInt(), 0x11223344.toInt(), 0xFF57C0FF.toInt(),
            0x80FF0000.toInt(), 0x00FFFFFF.toInt(), 0xFF0A0A0A.toInt()
        )
        for (i in cores.indices) doc.set(i % 3, i / 3, cores[i])
        val rgba = doc.toRgbaBytes()
        assertEquals(3 * 2 * 4, rgba.size)
        // Ordem de bytes R,G,B,A para o primeiro píxel (transparente → 0,0,0,0)
        assertEquals(0.toByte(), rgba[0]); assertEquals(0.toByte(), rgba[1])
        assertEquals(0.toByte(), rgba[2]); assertEquals(0.toByte(), rgba[3])
        // Roundtrip
        val recriado = PixelDocument.fromRgbaBytes(3, 2, rgba)
        assertTrue(doc.copyPixels().contentEquals(recriado.copyPixels()))
    }

    @Test
    fun `fromRgbaBytes rejeita buffers de tamanho errado`() {
        assertThrows(IllegalArgumentException::class.java) {
            PixelDocument.fromRgbaBytes(2, 2, ByteArray(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PixelDocument.fromRgbaBytes(2, 2, ByteArray(17))
        }
    }

    @Test
    fun `copyRegion recorta aos limites e writeRegion respeita os limites`() {
        val doc = PixelDocument(4, 4)
        doc.set(2, 2, 0xFFFFFFFF.toInt())
        // Região pedida (2,2)-(10,10): recortada para (2,2)-(3,3) → 2x2 = 4 valores
        val reg = doc.copyRegion(2, 2, 10, 10)
        assertEquals(4, reg.size)
        assertEquals(0xFFFFFFFF.toInt(), reg[0]) // (2,2)
        assertEquals(0, reg[3])                  // (3,3)

        val doc2 = PixelDocument(4, 4)
        doc2.writeRegion(3, 3, 2, intArrayOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(), 0x00000000))
        // Região 2x2 a partir de (3,3): só (3,3) é in-bounds
        assertEquals(0xFF0000FF.toInt(), doc2.get(3, 3))
        assertEquals(0, doc2.get(0, 0))
    }

    @Test
    fun `deepCopy é independente`() {
        val a = PixelDocument(2, 2)
        a.set(0, 0, 0xFFFF0000.toInt())
        val b = a.deepCopy()
        b.set(0, 0, 0xFF00FF00.toInt())
        assertEquals(0xFFFF0000.toInt(), a.get(0, 0))
        assertEquals(0xFF00FF00.toInt(), b.get(0, 0))
        assertFalse(a.copyPixels().contentEquals(b.copyPixels()))
    }
}
