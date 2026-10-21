package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.GltfParser.GltfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do PICKING por raio (Ronda A — seleção de face/ilha): um toque
 * devolve a face (triângulo) e a ilha CORRETAS; o raio saí da MESMA câmara
 * usada no render (NDC → mundo). Kotlin puro, sem Robolectric.
 */
class MeshPickingTest {

    private val model: GltfModel = DemoGeometry.createModel()

    /** Câmara determinística para testes: olhando de +X para o centro do cubo. */
    private fun testCamera(): OrbitCamera {
        val cam = OrbitCamera(initialYawDeg = 0f, initialPitchDeg = 0f, initialDistance = 3f)
        cam.restore(OrbitCamera.CameraSnapshot(0f, 0f, 3f, 0f, 0.5f, 0f))
        return cam
    }

    // ------------------------------------------------------------------
    // Raio a partir da câmara (NDC)
    // ------------------------------------------------------------------

    @Test
    fun `raio pelo centro do ecrã atravessa o alvo da câmara`() {
        val cam = testCamera()
        val ray = MeshPicking.rayFromNdc(cam, 0f, 0f, 1f)
        // Eye em (3, 0.5, 0), direção −X pura: o raio passa por (0.5, 0.5, 0).
        assertEquals(3f, ray.ox, 1e-4f)
        assertEquals(0.5f, ray.oy, 1e-4f)
        assertEquals(-1f, ray.dx, 1e-4f)
        assertEquals(0f, ray.dy, 1e-4f)
        assertEquals(0f, ray.dz, 1e-4f)
    }

    @Test
    fun `toque no centro do ecrã acerta no cubo`() {
        val cam = testCamera()
        val ray = MeshPicking.rayFromNdc(cam, 0f, 0f, 1f)
        val hit = MeshPicking.pick(model, ray)
        assertNotNull(hit)
        assertEquals("o centro do ecrã vê o cubo (não o chão)", 0, hit!!.renderableIndex)
        // Ponto de impacto na face +X (x=0.5), ao nível do alvo.
        assertEquals(0.5f, hit.point[0], 1e-4f)
        assertEquals(0.5f, hit.point[1], 1e-4f)
    }

    @Test
    fun `toque acima do modelo devolve null e limpa a seleção`() {
        val cam = testCamera()
        // NDC +0.9 no topo: raio passa por cima do cubo (y > 1) e do chão.
        val ray = MeshPicking.rayFromNdc(cam, 0f, 0.9f, 1f)
        val hit = MeshPicking.pick(model, ray)
        assertNull(hit)
    }

    // ------------------------------------------------------------------
    // Uma face por toque: os 6 lados do cubo
    // ------------------------------------------------------------------

    /** Ray reto de origem externa — atravessa a face cujo centro é apontado. */
    private fun pickFaceCenter(origin: FloatArray, dir: FloatArray): MeshPicking.PickHit? {
        val ray = MeshPicking.Ray(
            origin[0], origin[1], origin[2],
            dir[0], dir[1], dir[2]
        )
        return MeshPicking.pick(model, ray)
    }

    /** Ilha que contém o triângulo tocado (mesma lógica do ViewModel). */
    private fun islandContaining(model: GltfModel, islandsByRenderable: Map<Int, List<UvIslands.UvIsland>>, hit: MeshPicking.PickHit): UvIslands.UvIsland? =
        islandsByRenderable[hit.renderableIndex]?.firstOrNull { island ->
            island.triangleIndices.any { it == hit.triangleIndex }
        }

    @Test
    fun `um toque em cada face devolve a face e a ilha corretas`() {
        val cube = model.renderables[0]
        val cubeIslands = UvIslands.compute(cube.indices, cube.uvs!!)

        // (origem, direção) → índice de face esperado. Os pontos ficam no
        // INTERIOR de um dos 2 triângulos do quad (longe das diagonais).
        val cases = listOf(
            // +X (face 0): célula (col 0, row 0)
            Triple(floatArrayOf(2f, 0.25f, 0.1f), floatArrayOf(-1f, 0f, 0f), 0),
            // -X (face 1): célula (col 1, row 0)
            Triple(floatArrayOf(-2f, 0.25f, 0.1f), floatArrayOf(1f, 0f, 0f), 1),
            // +Y (face 2): célula (col 2, row 0)
            Triple(floatArrayOf(0.25f, 2f, 0.1f), floatArrayOf(0f, -1f, 0f), 2),
            // +Z (face 4): célula (col 1, row 1)
            Triple(floatArrayOf(0.25f, 0.1f, 2f), floatArrayOf(0f, 0f, -1f), 4),
            // -Z (face 5): célula (col 2, row 1)
            Triple(floatArrayOf(0.25f, 0.1f, -2f), floatArrayOf(0f, 0f, 1f), 5)
        )

        for ((origin, dir, faceIndex) in cases) {
            val hit = pickFaceCenter(origin, dir)
            assertNotNull("face $faceIndex não foi acertada", hit)
            assertEquals("toque na face $faceIndex acertou noutra primitiva", 0, hit!!.renderableIndex)
            // O triângulo tocado pertence ÀS 2 do quad da face (2f ou 2f+1).
            assertTrue(
                "triângulo ${hit.triangleIndex} não pertence à face $faceIndex",
                hit.triangleIndex == faceIndex * 2 || hit.triangleIndex == faceIndex * 2 + 1
            )
            // A ilha do triângulo tem a caixa UV da célula dessa face.
            val island = islandContaining(model, mapOf(0 to cubeIslands), hit)
            assertNotNull(island)
            val rect = DemoGeometry.cubeFaceUvRect(faceIndex)
            assertEquals("u0 da ilha da face $faceIndex", rect[0], island!!.minU, 1e-5f)
            assertEquals("v0 da ilha da face $faceIndex", rect[1], island.minV, 1e-5f)
            assertEquals("u1 da ilha da face $faceIndex", rect[2], island.maxU, 1e-5f)
            assertEquals("v1 da ilha da face $faceIndex", rect[3], island.maxV, 1e-5f)
        }
    }

    /**
     * RONDA A: o toque no CHÃO (fundo do ecrã, abaixo do cubo) seleciona a
     * ilha do chão — e não uma face do cubo.
     */
    @Test
    fun `toque no chão devolve a ilha do chão`() {
        val groundIslands = UvIslands.compute(model.renderables[1].indices, model.renderables[1].uvs!!)
        // Cai em (0, 0, 0.8): dentro do chão, FORA do cubo (z=0.8 > 0.5).
        val hit = MeshPicking.pick(model, MeshPicking.Ray(0f, 2f, 0.8f, 0f, -1f, 0f))
        assertNotNull(hit)
        assertEquals(1, hit!!.renderableIndex)
        val island = islandContaining(model, mapOf(1 to groundIslands), hit)
        assertNotNull(island)
        val rect = DemoGeometry.groundUvRect()
        assertEquals(rect[0], island!!.minU, 1e-5f)
        assertEquals(rect[1], island.minV, 1e-5f)
        assertEquals(2, groundIslands[0].triangleCount)
    }

    /**
     * RONDA A (correspondência 3D↔UV): o triângulo devolvido pelo picking 3D
     * pertence à MESMA ilha que o hitTest UV acerta no centro dessa ilha.
     */
    @Test
    fun `seleção 3D e seleção UV apontam para a mesma ilha`() {
        val cube = model.renderables[0]
        val islands = UvIslands.compute(cube.indices, cube.uvs!!)
        // Picking 3D na face +X.
        val hit = pickFaceCenter(floatArrayOf(2f, 0.5f, 0f), floatArrayOf(-1f, 0f, 0f))
        assertNotNull(hit)
        val island3D = islands.firstOrNull { isl -> isl.triangleIndices.any { it == hit!!.triangleIndex } }
        assertNotNull(island3D)
        // Seleção UV pelo centro da MESMA ilha.
        val cu = (island3D!!.minU + island3D.maxU) / 2f
        val cv = (island3D.minV + island3D.maxV) / 2f
        val islandUv = UvIslands.hitTest(islands, cube.indices, cube.uvs!!, cu, cv)
        assertNotNull(islandUv)
        assertEquals(island3D.id, islandUv!!.id)
        assertEquals(island3D.triangleIndices.toList(), islandUv.triangleIndices.toList())
    }

    // ------------------------------------------------------------------
    // Möller–Trumbore (unidade)
    // ------------------------------------------------------------------

    @Test
    fun `interseção com triângulo único - dentro fora e paralelo`() {
        val ray = MeshPicking.Ray(0f, 0f, 5f, 0f, 0f, -1f)
        // Triângulo no plano z=0 centrado na origem.
        val t = MeshPicking.rayTriangleT(
            ray,
            -1f, -1f, 0f,
            1f, -1f, 0f,
            0f, 1f, 0f
        )
        assertEquals(5f, t!!, 1e-4f)

        // Fora do triângulo.
        val rayMiss = MeshPicking.Ray(2f, 2f, 5f, 0f, 0f, -1f)
        assertNull(MeshPicking.rayTriangleT(rayMiss, -1f, -1f, 0f, 1f, -1f, 0f, 0f, 1f, 0f))

        // Raio paralelo ao plano.
        val rayParallel = MeshPicking.Ray(0f, 0f, 5f, 1f, 0f, 0f)
        assertNull(MeshPicking.rayTriangleT(rayParallel, -1f, -1f, 0f, 1f, -1f, 0f, 0f, 1f, 0f))

        // Dupla face: raio por TRÁS também acerta (picking não depende do winding).
        val rayBack = MeshPicking.Ray(0f, 0f, -5f, 0f, 0f, 1f)
        val tBack = MeshPicking.rayTriangleT(rayBack, -1f, -1f, 0f, 1f, -1f, 0f, 0f, 1f, 0f)
        assertEquals(5f, tBack!!, 1e-4f)
    }

    @Test
    fun `hit mais próximo vence quando dois objetos se sobrepõem no raio`() {
        // Raio vertical de cima para baixo em (0.25, ·, 0.1): atravessa a face
        // +Y do cubo (y=1) e DEPOIS o chão (y=0) — vence o cubo (mais perto).
        val ray = MeshPicking.Ray(0.25f, 5f, 0.1f, 0f, -1f, 0f)
        val hit = MeshPicking.pick(model, ray)
        assertNotNull(hit)
        assertEquals(0, hit!!.renderableIndex)
        assertEquals(4f, hit.distance, 1e-4f)
    }
}
