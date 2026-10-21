package com.pixelone.studio.studio3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testes do retângulo sujo (Fase 3): união, clamping e consumo — a base da
 * ATUALIZAÇÃO PARCIAL da textura na GPU (glTexSubImage2D só da região suja).
 */
class DirtyRectTest {

    @Test
    fun `acumulador une traços consecutivos`() {
        val acc = DirtyRectAccumulator()
        acc.addStroke(2, 3, 4, 5, 1)
        acc.addStroke(10, 12, 11, 13, 1)
        val rect = acc.peek()
        assertEquals(1, rect!!.x0) // margem do pincel
        assertEquals(2, rect.y0)
        assertEquals(12, rect.x1)
        assertEquals(14, rect.y1)
    }

    @Test
    fun `take devolve a união e limpa o acumulador`() {
        val acc = DirtyRectAccumulator()
        acc.addPixel(5, 5)
        acc.addPixel(7, 9)
        val rect = acc.take(64, 64)
        assertEquals(5, rect!!.x0)
        assertEquals(5, rect.y0)
        assertEquals(7, rect.x1)
        assertEquals(9, rect.y1)
        assertNull(acc.peek())
    }

    @Test
    fun `clamping limita ao documento e descarta fora`() {
        val acc = DirtyRectAccumulator()
        acc.add(-5, -5, 3, 2000)
        val rect = acc.take(64, 64)
        assertEquals(0, rect!!.x0)
        assertEquals(0, rect.y0)
        assertEquals(3, rect.x1)
        assertEquals(63, rect.y1)

        val acc2 = DirtyRectAccumulator()
        acc2.add(100, 100, 110, 110)
        assertNull(acc2.take(64, 64))
    }

    @Test
    fun `união de retângulos independente funciona`() {
        val a = DirtyRect(0, 0, 1, 1)
        val b = DirtyRect(5, 5, 6, 6)
        val u = a.union(b)
        assertEquals(0, u.x0)
        assertEquals(0, u.y0)
        assertEquals(6, u.x1)
        assertEquals(6, u.y1)
        assertEquals(7, u.width)
        assertEquals(7, u.height)
    }

    @Test
    fun `addStroke com pincel grande inclui a margem`() {
        val acc = DirtyRectAccumulator()
        acc.addStroke(10, 10, 10, 10, 4) // pincel 4 → margem 2
        val rect = acc.take(64, 64)!!
        assertEquals(8, rect.x0)
        assertEquals(12, rect.x1)
    }
}
