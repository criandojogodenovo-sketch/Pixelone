package com.pixelone.studio.studio3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da geometria de demonstração (Fase 3 + Ronda A): cubo FECHADO e
 * sólido de qualquer ângulo (problema B), layout UV sem sobreposição com as
 * 7 ilhas separadas (problema D), chão visível de cima (winding) e cor do
 * chão distinta da textura (problema E).
 */
class DemoGeometryTest {

    @Test
    fun `modelo demo tem cubo e chão com geometria consistente`() {
        val model = DemoGeometry.createModel()
        assertEquals(2, model.renderables.size)
        assertEquals(2, model.meshNames.size)
        val cube = model.renderables[0]
        val ground = model.renderables[1]
        // Cubo: 24 vértices, 36 índices (12 triângulos) — 6 faces × 4 vértices.
        assertEquals(24, cube.positions.size / 3)
        assertEquals(36, cube.indices.size)
        // Chão: 4 vértices, 6 índices (2 triângulos).
        assertEquals(4, ground.positions.size / 3)
        assertEquals(6, ground.indices.size)
        assertEquals(14, model.triangleCount)
    }

    /**
     * RONDA A (B): o cubo é FECHADO — cada face fica no seu plano correto e
     * todas as arestas dos quads coincidem exatamente (sem peças soltas).
     */
    @Test
    fun `cubo é fechado - faces nos planos corretos e arestas coincidentes`() {
        val model = DemoGeometry.createModel()
        val cube = model.renderables[0]
        val pos = cube.positions
        val vertexCount = 24

        // 1) Todos os vértices dentro dos limites do cubo (±0.5 em XZ, 0..1 em Y).
        for (v in 0 until vertexCount) {
            val x = pos[v * 3]; val y = pos[v * 3 + 1]; val z = pos[v * 3 + 2]
            assertTrue("x=$x fora de ±0.5", x in -0.5f..0.5f)
            assertTrue("y=$y fora de 0..1", y in 0f..1f)
            assertTrue("z=$z fora de ±0.5", z in -0.5f..0.5f)
        }

        // 2) Cada face tem exatamente 4 vértices no SEU plano (normal×0.5).
        data class Plane(val normal: FloatArray, val d: Float)
        val planes = listOf(
            Plane(floatArrayOf(1f, 0f, 0f), 0.5f),   // +X
            Plane(floatArrayOf(-1f, 0f, 0f), 0.5f),  // -X (x = -0.5)
            Plane(floatArrayOf(0f, 1f, 0f), 1f),     // +Y (y = 1)
            Plane(floatArrayOf(0f, -1f, 0f), 0f),    // -Y (y = 0)
            Plane(floatArrayOf(0f, 0f, 1f), 0.5f),   // +Z
            Plane(floatArrayOf(0f, 0f, -1f), 0.5f)   // -Z (z = -0.5)
        )
        for (f in 0 until 6) {
            val plane = planes[f]
            for (k in 0 until 4) {
                val v = f * 4 + k
                val x = pos[v * 3]; val y = pos[v * 3 + 1]; val z = pos[v * 3 + 2]
                val dist = plane.normal[0] * x + plane.normal[1] * y + plane.normal[2] * z
                assertEquals("face $f vértice $k fora do plano", plane.d, dist, 1e-5f)
            }
        }

        // 3) As 24 arestas do cubo (perímetro de cada face) coincidem par a
        //    par com as arestas das faces vizinhas — prova de fecho (sem
        //    "peças soltas").
        data class Edge(val ax: Float, val ay: Float, val az: Float, val bx: Float, val by: Float, val bz: Float)
        val edges = ArrayList<Edge>()
        for (f in 0 until 6) {
            // Perímetro do quad: índices 0→1, 1→2, 2→3, 3→0 (vértices da face).
            val base = f * 4
            val ring = intArrayOf(base, base + 1, base + 1, base + 2, base + 2, base + 3, base + 3, base)
            for (k in ring.indices step 2) {
                val a = ring[k]; val b = ring[k + 1]
                edges.add(
                    Edge(
                        pos[a * 3], pos[a * 3 + 1], pos[a * 3 + 2],
                        pos[b * 3], pos[b * 3 + 1], pos[b * 3 + 2]
                    )
                )
            }
        }
        // Cada aresta deve ter uma aresta REVERSA igual (partilhada com a face vizinha).
        fun sameEdge(e1: Edge, e2: Edge, eps: Float = 1e-5f): Boolean =
            kotlin.math.abs(e1.ax - e2.bx) < eps && kotlin.math.abs(e1.ay - e2.by) < eps &&
                kotlin.math.abs(e1.az - e2.bz) < eps && kotlin.math.abs(e1.bx - e2.ax) < eps &&
                kotlin.math.abs(e1.by - e2.ay) < eps && kotlin.math.abs(e1.bz - e2.az) < eps
        for (e in edges) {
            val partner = edges.any { other -> sameEdge(e, other) }
            assertTrue("Aresta (${e.ax},${e.ay},${e.az})→(${e.bx},${e.by},${e.bz}) sem parceiro — cubo aberto", partner)
        }
    }

    /**
     * RONDA A (B): winding CCW visto de FORA em TODAS as faces — com
     * GL_CULL_FACE(BACK) o cubo fica sólido de qualquer ângulo e o chão
     * visível de cima (o "sem fundo" do APK era o chão culled + faces soltas).
     */
    @Test
    fun `winding CCW visto de fora em todas as faces e no chão`() {
        val model = DemoGeometry.createModel()
        val cubeCenter = floatArrayOf(0f, 0.5f, 0f)

        fun crossOutward(
            rIndex: Int, tri: Int, expectDotPositive: Boolean
        ) {
            val r = model.renderables[rIndex]
            val a = r.indices[tri * 3] * 3
            val b = r.indices[tri * 3 + 1] * 3
            val c = r.indices[tri * 3 + 2] * 3
            // Normal geométrica = (b-a) × (c-a).
            val e1x = r.positions[b] - r.positions[a]
            val e1y = r.positions[b + 1] - r.positions[a + 1]
            val e1z = r.positions[b + 2] - r.positions[a + 2]
            val e2x = r.positions[c] - r.positions[a]
            val e2y = r.positions[c + 1] - r.positions[a + 1]
            val e2z = r.positions[c + 2] - r.positions[a + 2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            // Dot com o vetor centro→baricentro (aponta para fora se CCW de fora).
            val gx = (r.positions[a] + r.positions[b] + r.positions[c]) / 3f - cubeCenter[0]
            val gy = (r.positions[a + 1] + r.positions[b + 1] + r.positions[c + 1]) / 3f - cubeCenter[1]
            val gz = (r.positions[a + 2] + r.positions[b + 2] + r.positions[c + 2]) / 3f - cubeCenter[2]
            val dot = nx * gx + ny * gy + nz * gz
            if (expectDotPositive) {
                assertTrue("triângulo $tri da primitiva $rIndex culled de fora (dot=$dot)", dot > 0f)
            } else {
                assertTrue("triângulo $tri da primitiva $rIndex com normal errada (dot=$dot)", dot < 0f)
            }
        }

        // Cubo: normal exterior = baricentro − centro (dot > 0).
        for (tri in 0 until 12) crossOutward(0, tri, true)
        // Chão (renderable 1): normal +Y — visto de CIMA é frontal (dot com +Y > 0).
        val ground = model.renderables[1]
        for (tri in 0 until 2) {
            val a = ground.indices[tri * 3] * 3
            val b = ground.indices[tri * 3 + 1] * 3
            val c = ground.indices[tri * 3 + 2] * 3
            val e1x = ground.positions[b] - ground.positions[a]
            val e1y = ground.positions[b + 1] - ground.positions[a + 1]
            val e1z = ground.positions[b + 2] - ground.positions[a + 2]
            val e2x = ground.positions[c] - ground.positions[a]
            val e2y = ground.positions[c + 1] - ground.positions[a + 1]
            val e2z = ground.positions[c + 2] - ground.positions[a + 2]
            val ny = e1z * e2x - e1x * e2z
            assertTrue("chão culled de cima (ny=$ny)", ny > 0f)
        }
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

    /**
     * RONDA A (D): 7 ilhas (6 faces do cubo + 1 chão), SEM sobreposição de
     * caixas UV e com o cubo visível na vista UV (não colapsa numa região).
     */
    @Test
    fun `layout UV tem 7 ilhas separadas sem sobreposição`() {
        val model = DemoGeometry.createModel()
        val cubeIslands = UvIslands.compute(model.renderables[0].indices, model.renderables[0].uvs!!)
        val groundIslands = UvIslands.compute(model.renderables[1].indices, model.renderables[1].uvs!!)

        // 6 ilhas no cubo (1 por face) e 1 no chão.
        assertEquals(6, cubeIslands.size)
        assertEquals(1, groundIslands.size)
        cubeIslands.forEach { assertEquals("cada face = 2 triângulos", 2, it.triangleCount) }
        assertEquals(2, groundIslands[0].triangleCount)

        // Todas as caixas dentro de [0,1]².
        (cubeIslands + groundIslands).forEach { island ->
            assertTrue(island.minU >= 0f && island.maxU <= 1f)
            assertTrue(island.minV >= 0f && island.maxV <= 1f)
        }

        // Sem sobreposição par a par (com folga de 1e-3 acima da margem).
        val all = cubeIslands + groundIslands
        for (i in all.indices) {
            for (j in i + 1 until all.size) {
                val a = all[i]; val b = all[j]
                val overlap = a.minU < b.maxU - 1e-3f && b.minU < a.maxU - 1e-3f &&
                    a.minV < b.maxV - 1e-3f && b.minV < a.maxV - 1e-3f
                assertFalse("ilhas $i e $j sobrepõem-se", overlap)
            }
        }

        // As 6 faces do cubo distribuem-se pelas 2 metades verticais (grelha 3×2)
        // e o chão fica na faixa inferior — a vista UV distingue cubo e chão.
        val cubeVMin = cubeIslands.minOf { it.minV }
        val cubeVMax = cubeIslands.maxOf { it.maxV }
        assertTrue(cubeVMax <= DemoGeometry.GROUND_UV_V0)
        assertEquals(0f, cubeVMin, 0.02f)
        val ground = groundIslands[0]
        assertTrue(ground.minV >= DemoGeometry.GROUND_UV_V0)
    }

    /**
     * RONDA A (H): cada ilha tem contagem de triângulos IGUAL ao que é
     * desenhado na vista UV (2 triângulos por face — o "12 tri." colapsado
     * desaparece com o layout UV separado).
     */
    @Test
    fun `contagem de triângulos da ilha coincide com os desenhados`() {
        val model = DemoGeometry.createModel()
        model.renderables.forEachIndexed { index, r ->
            val islands = UvIslands.compute(r.indices, r.uvs!!)
            val totalDesenhado = islands.sumOf { it.triangleCount }
            assertEquals(r.indices.size / 3, totalDesenhado)
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
        // RONDA A (E): a cor sólida do chão é DISTINTA das células do xadrez
        // (0x2E=0.18 e 0x1A=0.10) — mudar o material torna-se VISÍVEL.
        assertEquals(0.35f, model.materials[1].baseColorFactor[0], 1e-6f)
        assertTrue(model.materials[1].baseColorFactor[0] > 0.25f)
    }

    @Test
    fun `caixa envolvente cobre o cubo e o chão`() {
        val model = DemoGeometry.createModel()
        assertEquals(-1.5f, model.boundsMin[0], 1e-5f)
        assertEquals(0f, model.boundsMin[1], 1e-5f)
        assertEquals(1.5f, model.boundsMax[2], 1e-5f)
        assertEquals(1f, model.boundsMax[1], 1e-5f)
        assertTrue(model.radius() > 0f)

        // RONDA A (B): a geometria REAL cabe na caixa (antes, faces a y=-0.5
        // e y=1.5 escapavam aos limites declarados).
        model.renderables.forEach { r ->
            for (v in 0 until r.positions.size / 3) {
                assertTrue(r.positions[v * 3] >= model.boundsMin[0] - 1e-5f)
                assertTrue(r.positions[v * 3] <= model.boundsMax[0] + 1e-5f)
                assertTrue(r.positions[v * 3 + 1] >= model.boundsMin[1] - 1e-5f)
                assertTrue(r.positions[v * 3 + 1] <= model.boundsMax[1] + 1e-5f)
                assertTrue(r.positions[v * 3 + 2] >= model.boundsMin[2] - 1e-5f)
                assertTrue(r.positions[v * 3 + 2] <= model.boundsMax[2] + 1e-5f)
            }
        }
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

    /**
     * RONDA A (correspondência 3D↔UV): para cada face do cubo, o centro UV da
     * ilha acerta (hitTest) na MESMA ilha a que o triângulo pertence — a
     * seleção pela UV e pelo 3D apontam sempre para a mesma parte.
     */
    @Test
    fun `cada ilha do cubo é encontrada pelo toque UV no seu centro`() {
        val model = DemoGeometry.createModel()
        val cube = model.renderables[0]
        val islands = UvIslands.compute(cube.indices, cube.uvs!!)
        assertEquals(6, islands.size)
        islands.forEach { island ->
            val cu = (island.minU + island.maxU) / 2f
            val cv = (island.minV + island.maxV) / 2f
            val hit = UvIslands.hitTest(islands, cube.indices, cube.uvs!!, cu, cv)
            assertNotNull("ilha ${island.id} não acertada pelo próprio centro", hit)
            assertEquals(island.id, hit!!.id)
        }
        // Ponto na MARGEM entre ilhas (nenhuma caixa o contém): null → limpa.
        val miss = UvIslands.hitTest(islands, cube.indices, cube.uvs!!, 0.345f, 0.345f)
        assertNull(miss)
    }
}
