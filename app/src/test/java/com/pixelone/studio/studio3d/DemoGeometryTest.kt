package com.pixelone.studio.studio3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da geometria de demonstração (Fase 3): visível logo ao abrir, sem
 * ficheiros externos, com UVs completas — e da textura padrão gerada.
 */
class DemoGeometryTest {

    @Test
    fun `modelo demo tem cubo e chão com geometria consistente`() {
        val model = DemoGeometry.createModel()
        assertEquals(2, model.renderables.size)
        assertEquals(2, model.meshNames.size)
        val cube = model.renderables[0]
        val ground = model.renderables[1]
        // Cubo: 24 vértices, 36 índices (12 triângulos).
        assertEquals(24, cube.positions.size / 3)
        assertEquals(36, cube.indices.size)
        // Chão: 4 vértices, 6 índices (2 triângulos).
        assertEquals(4, ground.positions.size / 3)
        assertEquals(6, ground.indices.size)
        assertEquals(14, model.triangleCount)
    }

    @Test
    fun `modelo demo tem UVs em todos os triângulos dentro do intervalo fechado`() {
        val model = DemoGeometry.createModel()
        assertTrue(model.hasUv)
        model.renderables.forEach { r ->
            assertNotNull(r.uvs)
            val uvs = r.uvs!!
            assertEquals(r.positions.size / 3 * 2, uvs.size)
            for (i in uvs.indices) {
                assertTrue("UV[$i]=${uvs[i]} fora de [0,1]", uvs[i] in 0f..1f)
            }
        }
    }

    @Test
    fun `índices do demo referem vértices existentes`() {
        val model = DemoGeometry.createModel()
        model.renderables.forEach { r ->
            val vertexCount = r.positions.size / 3
            r.indices.forEach { idx ->
                assertTrue(idx in 0 until vertexCount)
            }
        }
    }

    @Test
    fun `demo tem dois materiais (texturizado e chão sólido)`() {
        val model = DemoGeometry.createModel()
        assertEquals(2, model.materials.size)
        // Material 0 liga-se à textura do documento (sem imagem embutida).
        assertEquals(null, model.materials[0].textureImageIndex)
        assertEquals("Textura do projeto", model.materials[0].name)
        // Material 1 é o chão com cor sólida escura.
        assertEquals(0.18f, model.materials[1].baseColorFactor[0], 1e-6f)
    }

    @Test
    fun `caixa envolvente cobre o cubo e o chão`() {
        val model = DemoGeometry.createModel()
        assertEquals(-1.5f, model.boundsMin[0], 1e-5f)
        assertEquals(0f, model.boundsMin[1], 1e-5f)
        assertEquals(1.5f, model.boundsMax[2], 1e-5f)
        assertEquals(1f, model.boundsMax[1], 1e-5f)
        assertTrue(model.radius() > 0f)
    }

    @Test
    fun `textura padrão 64 por 64 tem xadrez e laranja Pixel One`() {
        val pixels = DemoGeometry.createDefaultTexture()
        assertEquals(64 * 64, pixels.size)
        // Xadrez: (0,0) e (8,0) têm cores diferentes.
        assertFalse(pixels[0] == pixels[8])
        // Contém laranja F57C00 em algum sítio (marca "P1").
        assertTrue(pixels.contains(0xFFF57C00.toInt()))
        // Todos os píxeis são opacos.
        assertTrue(pixels.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `textura padrão aceita dimensões personalizadas e rejeita absurdas`() {
        assertEquals(16 * 16, DemoGeometry.createDefaultTexture(16, 16).size)
        try {
            DemoGeometry.createDefaultTexture(512, 512)
            org.junit.Assert.fail("Deveria rejeitar 512")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tamanho"))
        }
    }

    private fun assertNotNull(value: Any?) {
        if (value == null) org.junit.Assert.fail("Valor inesperado: null")
    }
}
