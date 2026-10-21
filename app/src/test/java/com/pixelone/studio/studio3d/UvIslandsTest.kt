package com.pixelone.studio.studio3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes das ilhas UV (Fase 3): cálculo de ilhas, hit-test da seleção e
 * sobreposição (limites). Kotlin puro — sem Robolectric.
 */
class UvIslandsTest {

    /** Quad 2×2 (2 triângulos, 6 índices) — UMA ilha. */
    private val quadIndices = intArrayOf(0, 1, 2, 0, 2, 3)
    private val quadUvs = floatArrayOf(
        0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f
    )

    /** Dois quads SEPARADOS (6 vértices, 4 triângulos) — DUAS ilhas. */
    private val twoQuadsIndices = intArrayOf(
        0, 1, 2, 0, 2, 3,   // quad A (v0..v3)
        4, 5, 6, 4, 6, 7    // quad B (v4..v7)
    )
    private val twoQuadsUvs = floatArrayOf(
        0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f,      // A: (0,0)-(1,1)
        2f, 0f, 3f, 0f, 3f, 1f, 2f, 1f       // B: (2,0)-(3,1)
    )

    @Test
    fun `quad ligado produz uma única ilha`() {
        val islands = UvIslands.compute(quadIndices, quadUvs)
        assertEquals(1, islands.size)
        assertEquals(2, islands[0].triangleCount)
        assertEquals(0f, islands[0].minU, 1e-6f)
        assertEquals(1f, islands[0].maxV, 1e-6f)
    }

    @Test
    fun `dois quads separados produzem duas ilhas ordenadas`() {
        val islands = UvIslands.compute(twoQuadsIndices, twoQuadsUvs)
        assertEquals(2, islands.size)
        assertEquals(0, islands[0].id)
        assertEquals(1, islands[1].id)
        assertEquals(listOf(0, 1), islands[0].triangleIndices.toList())
        assertEquals(listOf(2, 3), islands[1].triangleIndices.toList())
        assertEquals(2f, islands[1].minU, 1e-6f)
    }

    @Test
    fun `vértices duplicados com mesma UV são soldados na mesma ilha`() {
        // Triângulos partilham a UV (0.5, 0.5) através de VÉRTICES DIFERENTES
        // (duplicados) — a soldadura por quantização deve uni-los.
        val indices = intArrayOf(0, 1, 2, 3, 4, 5)
        val uvs = floatArrayOf(
            0f, 0f, 1f, 0f, 0.5f, 0.5f,   // tri 1
            0.5f, 0.5f, 1f, 0f, 1f, 1f    // tri 2 (0.5,0.5 duplicado; 1,0 duplicado)
        )
        val islands = UvIslands.compute(indices, uvs)
        assertEquals(1, islands.size)
    }

    @Test
    fun `hit-test encontra a ilha correta dentro do triângulo`() {
        val islands = UvIslands.compute(twoQuadsIndices, twoQuadsUvs)
        val hitA = UvIslands.hitTest(islands, twoQuadsIndices, twoQuadsUvs, 0.25f, 0.25f)
        val hitB = UvIslands.hitTest(islands, twoQuadsIndices, twoQuadsUvs, 2.5f, 0.5f)
        assertNotNull(hitA)
        assertEquals(0, hitA!!.id)
        assertNotNull(hitB)
        assertEquals(1, hitB!!.id)
    }

    @Test
    fun `hit-test fora de qualquer ilha devolve null`() {
        val islands = UvIslands.compute(twoQuadsIndices, twoQuadsUvs)
        assertNull(UvIslands.hitTest(islands, twoQuadsIndices, twoQuadsUvs, 1.5f, 0.5f))
        assertNull(UvIslands.hitTest(islands, twoQuadsIndices, twoQuadsUvs, 5f, 5f))
    }

    @Test
    fun `hit-test com tolerância de borda encontra pontos no contorno`() {
        val islands = UvIslands.compute(quadIndices, quadUvs)
        // (0,0) é um vértice exato — com a tolerância, deve acertar.
        val hit = UvIslands.hitTest(islands, quadIndices, quadUvs, 0f, 0f)
        assertNotNull(hit)
    }

    @Test
    fun `lista de índices inválida é rejeitada`() {
        try {
            UvIslands.compute(intArrayOf(0, 1, 2, 3), quadUvs)
            org.junit.Assert.fail("Deveria rejeitar índices não múltiplos de 3")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("múltiplo de 3"))
        }
    }

    @Test
    fun `uma malha com quads em diagonal partilhada é uma ilha`() {
        // Quads que partilham apenas um VÉRTICE (canto) continuam uma ilha.
        val indices = intArrayOf(0, 1, 2, 0, 2, 3, 2, 4, 5, 2, 5, 6)
        val uvs = floatArrayOf(
            0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f,          // quad 1 (partilha v2)
            1f, 1f, 2f, 1f, 2f, 2f                    // v2=(1,1) duplicado no quad 2
        )
        val islands = UvIslands.compute(indices, uvs)
        assertEquals(1, islands.size)
    }
}
