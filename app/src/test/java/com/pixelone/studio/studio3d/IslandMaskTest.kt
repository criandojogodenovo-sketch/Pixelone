package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.UvIslands.UvIsland
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da MÁSCARA DE ILHA (Ronda B): rasterização dos triângulos da ilha,
 * dilatação (padding) e exclusão do resto da textura. A máscara é o mecanismo
 * que garante "pintar só dentro da ilha" — nada fora pode mudar.
 */
class IslandMaskTest {

    /** Ilha sintética: 2 triângulos cobrindo o retângulo UV [0.25..0.75]². */
    private fun squareIsland(): Pair<Pair<IntArray, FloatArray>, UvIsland> {
        // 4 vértices (0..3), 2 triângulos (0,1,2)(0,2,3)
        val uvs = floatArrayOf(
            0.25f, 0.25f, // 0
            0.75f, 0.25f, // 1
            0.75f, 0.75f, // 2
            0.25f, 0.75f  // 3
        )
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        val island = UvIsland(id = 0, triangleIndices = intArrayOf(0, 1), minU = 0.25f, minV = 0.25f, maxU = 0.75f, maxV = 0.75f)
        return Pair(indices, uvs) to island
    }

    @Test
    fun `máscara cobre os texels dentro da ilha e nada fora`() {
        val (geo, island) = squareIsland()
        val mask = IslandMask.build(geo.first, geo.second, island, 64, 64, paddingPx = 0)
        // Texel central da ilha (32,32 → uv 0.5,0.5) está dentro.
        assertTrue(mask.contains(32, 32))
        assertTrue(mask.contains(20, 20)) // 20.5/64 = 0.32 ∈ [0.25..0.75]
        // Texel fora (u 0.1 → x 6.4 → centro 6.5/64 = 0.101 < 0.25).
        assertFalse(mask.contains(6, 6))
        assertFalse(mask.contains(63, 63))
        // Caixa dentro de [16..47] (0.25×64=16; 0.75×64=48).
        val b = mask.bounds()
        assertNotNull(b)
        assertTrue(b!![0] >= 16 && b[0] <= 17)
        assertTrue(b[2] >= 46 && b[2] <= 47)
    }

    @Test
    fun `padding dilata a máscara para além da aresta`() {
        val (geo, island) = squareIsland()
        val sem = IslandMask.build(geo.first, geo.second, island, 64, 64, paddingPx = 0)
        val com = IslandMask.build(geo.first, geo.second, island, 64, 64, paddingPx = 2)
        assertTrue(com.activeCount() > sem.activeCount())
        // Texel imediatamente fora da aresta esquerda (x 15 centro 0.242) fica
        // dentro com padding 2.
        assertTrue(com.contains(15, 32))
        assertFalse(sem.contains(15, 32))
        // E ainda não cobre a textura inteira.
        assertFalse(com.contains(0, 0))
        assertFalse(com.contains(63, 0))
    }

    @Test
    fun `padding 0 é aceites e o máximo é limitado`() {
        val (geo, island) = squareIsland()
        val mask = IslandMask.build(geo.first, geo.second, island, 64, 64, paddingPx = 999)
        assertEquals(IslandMask.MAX_PADDING, mask.paddingUsed)
    }

    // ------------------------------------------------------------------
    // Ilha REAL do demo (face 0 do cubo): mapeamento com a grelha 3×2
    // ------------------------------------------------------------------

    @Test
    fun `máscara da face 0 do cubo fica confinada à sua célula da grelha`() {
        val model = DemoGeometry.createModel()
        val cube = model.renderables[0]
        val islands = UvIslands.compute(cube.indices, cube.uvs!!)
        assertEquals(6, islands.size)
        val face0 = islands[0]
        val rect = DemoGeometry.cubeFaceUvRect(0)
        assertEquals(rect[0], face0.minU, 1e-5f)
        assertEquals(rect[1], face0.minV, 1e-5f)
        val mask = IslandMask.build(cube.indices, cube.uvs!!, face0, 64, 64, paddingPx = 0)
        val b = mask.bounds()!!
        // x/y máximos não podem ultrapassar a célula (1/3 de 64 = 21.33).
        assertTrue("x1=${b[2]} fora da célula", b[2] <= 21)
        assertTrue("y1=${b[3]} fora da célula", b[3] <= 21)
        // E o texel central da célula está coberto.
        assertTrue(mask.contains(10, 10))
        // Não invade a ilha do chão (v ≥ 2/3 → y ≥ 42).
        assertFalse(mask.contains(32, 45))
    }
}
