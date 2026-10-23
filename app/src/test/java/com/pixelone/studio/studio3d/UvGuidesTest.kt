package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.GltfParser.Renderable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MISSÃO TEXTURIZAÇÃO — testes do construtor de segmentos UV sem boxing.
 *
 * Contrato (igual ao algoritmo antigo, consumido por TexturePaintView e
 * exportUvTemplateTo): 12 floats por triângulo — arestas a→b, b→c, c→a —
 * e primitivas sem UV ou fora do filtro de malha ignoradas.
 */
class UvGuidesTest {

    private fun renderable(
        meshIndex: Int,
        uvs: FloatArray?,
        indices: IntArray
    ) = Renderable(
        meshIndex = meshIndex,
        meshName = "malha$meshIndex",
        materialIndex = 0,
        positions = FloatArray(3 * indices.distinct().size.coerceAtLeast(1)),
        uvs = uvs,
        indices = indices
    )

    @Test
    fun `um triângulo gera 12 floats na ordem a-b b-c c-a`() {
        val uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f) // a(0,0) b(1,0) c(0,1)
        val r = renderable(0, uvs, intArrayOf(0, 1, 2))
        val seg = UvGuides.segments(listOf(r), null)
        assertEquals(12, seg.size)
        // a→b
        assertEquals(0f, seg[0], 1e-6f); assertEquals(0f, seg[1], 1e-6f)
        assertEquals(1f, seg[2], 1e-6f); assertEquals(0f, seg[3], 1e-6f)
        // b→c
        assertEquals(1f, seg[4], 1e-6f); assertEquals(0f, seg[5], 1e-6f)
        assertEquals(0f, seg[6], 1e-6f); assertEquals(1f, seg[7], 1e-6f)
        // c→a
        assertEquals(0f, seg[8], 1e-6f); assertEquals(1f, seg[9], 1e-6f)
        assertEquals(0f, seg[10], 1e-6f); assertEquals(0f, seg[11], 1e-6f)
    }

    @Test
    fun `primitivas sem UV são ignoradas`() {
        val r = renderable(0, null, intArrayOf(0, 1, 2))
        assertTrue(UvGuides.segments(listOf(r), null).isEmpty())
    }

    @Test
    fun `filtro de malha mantém só a malha pedida`() {
        val uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val m0 = renderable(0, uvs, intArrayOf(0, 1, 2))
        val m1 = renderable(1, uvs, intArrayOf(0, 1, 2))
        val seg = UvGuides.segments(listOf(m0, m1), meshFilter = 1)
        assertEquals(12, seg.size)
        // Todas as malhas: 24.
        assertEquals(24, UvGuides.segments(listOf(m0, m1), null).size)
    }

    @Test
    fun `modelo grande prealoca o tamanho exato — sem overhead`() {
        // 1000 triângulos → 12 000 floats, tamanho EXATO (não capacidade).
        val tris = 1000
        val indices = IntArray(tris * 3) { it % 3 }
        val uvs = FloatArray(6) { it * 0.1f }
        val r = renderable(0, uvs, indices)
        val seg = UvGuides.segments(listOf(r), null)
        assertEquals(tris * 12, seg.size)
    }
}
