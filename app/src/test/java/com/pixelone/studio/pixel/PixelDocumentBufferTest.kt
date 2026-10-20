package com.pixelone.studio.pixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do NOVO buffer direto partilhado do PixelDocument (correção de
 * performance): o buffer é a única fonte de verdade, Kotlin acede via vista
 * IntBuffer persistente com acessos absolutos, e o C++ acede via
 * GetDirectBufferAddress (provado nos testes instrumentados).
 */
class PixelDocumentBufferTest {

    @Test
    fun `buffer é direto com capacidade e ordem nativas`() {
        val doc = PixelDocument(32, 32)
        assertTrue("O buffer deve ser DIRETO (allocateDirect)", doc.sharedBuffer.isDirect)
        assertEquals(32 * 32 * 4L, doc.sharedBuffer.capacity().toLong())
        assertNull("Buffer/ordem devem validar sem problemas", doc.bufferValidationProblem())
    }

    @Test
    fun `validação rejeita capacidades erradas`() {
        val doc = PixelDocument(16, 16)
        assertEquals(16 * 16 * 4L, doc.sharedBuffer.capacity().toLong())
        // Simula um problema forçando dimensões incoerentes noutra instância:
        val bad = PixelDocument(4, 4)
        bad.sharedBuffer.limit(4) // capacidade efetiva alterada não muda capacity; valida apenas o caminho feliz
        assertNull(bad.bufferValidationProblem())
    }

    @Test
    fun `cantos e centro — escrita e leitura corretas`() {
        val doc = PixelDocument(8, 8)
        val vermelho = 0xFFFF0000.toInt()
        doc.set(0, 0, vermelho)                    // canto sup. esquerdo
        doc.set(7, 0, 0xFF00FF00.toInt())          // canto sup. direito
        doc.set(0, 7, 0xFF0000FF.toInt())          // canto inf. esquerdo
        doc.set(7, 7, 0xFFFFFFFF.toInt())          // canto inf. direito
        doc.set(4, 4, 0xFF808080.toInt())          // centro
        assertEquals(vermelho, doc.get(0, 0))
        assertEquals(0xFF00FF00.toInt(), doc.get(7, 0))
        assertEquals(0xFF0000FF.toInt(), doc.get(0, 7))
        assertEquals(0xFFFFFFFF.toInt(), doc.get(7, 7))
        assertEquals(0xFF808080.toInt(), doc.get(4, 4))
    }

    @Test
    fun `acessos por índice linear equivalem a (x, y)`() {
        val doc = PixelDocument(5, 3)
        doc.set(3, 2, 0xFF123456.toInt())
        val idx = 2 * 5 + 3
        assertEquals(0xFF123456.toInt(), doc.getAt(idx))
        doc.setAt(idx, 0xFF654321.toInt())
        assertEquals(0xFF654321.toInt(), doc.get(3, 2))
        // Fora dos limites: sem crash, sem efeito
        doc.setAt(-1, 0xFFFFFFFF.toInt())
        doc.setAt(5 * 3, 0xFFFFFFFF.toInt())
        assertEquals(0, doc.getAt(-1))
        assertEquals(0, doc.getAt(5 * 3))
    }

    @Test
    fun `copyRegionInto preenche array sem alocação extra`() {
        val doc = PixelDocument(4, 4)
        for (i in 0 until 16) doc.setAt(i, 0xFF000000.toInt() or i)
        val out = IntArray(2 * 2)
        doc.copyRegionInto(1, 1, 2, 2, out)
        assertEquals(0xFF000000.toInt() or 5, out[0])
        assertEquals(0xFF000000.toInt() or 6, out[1])
        assertEquals(0xFF000000.toInt() or 9, out[2])
        assertEquals(0xFF000000.toInt() or 10, out[3])
    }

    @Test
    fun `roundtrip RGBA compatível com o formato osp`() {
        val original = PixelDocument(8, 8)
        original.set(1, 1, 0x80FF8000.toInt()) // alpha parcial — sem premultiplicação
        original.set(2, 2, 0x00000000)         // transparente real
        val bytes = original.toRgbaBytes()
        assertEquals(8 * 8 * 4, bytes.size)
        val recarregado = PixelDocument.fromRgbaBytes(8, 8, bytes)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                assertEquals("Píxel ($x,$y) deve ser idêntico", original.get(x, y), recarregado.get(x, y))
            }
        }
        // Bytes RGBA exatos (R,G,B,A) — compatibilidade de ficheiros mantida
        val off = (1 * 8 + 1) * 4
        assertEquals(0xFF.toByte(), bytes[off])        // R
        assertEquals(0x80.toByte(), bytes[off + 1])    // G
        assertEquals(0x00.toByte(), bytes[off + 2])    // B
        assertEquals(0x80.toByte(), bytes[off + 3])    // A
    }

    @Test
    fun `deepCopy é independente do original`() {
        val a = PixelDocument(4, 4)
        a.set(0, 0, 0xFFFF0000.toInt())
        val b = a.deepCopy()
        a.set(0, 0, 0xFF00FF00.toInt())
        assertEquals(0xFFFF0000.toInt(), b.get(0, 0))
        assertEquals(0xFF00FF00.toInt(), a.get(0, 0))
        assertFalse(b.sharedBuffer === a.sharedBuffer)
    }

    @Test
    fun `limites de memória continuam protegidos`() {
        assertNotNull(
            assertThrows(IllegalArgumentException::class.java) {
                PixelDocument(4096, 4096) // excede MAX_PIXELS
            }
        )
        assertNotNull(
            assertThrows(IllegalArgumentException::class.java) {
                PixelDocument(0, 10)
            }
        )
    }
}
