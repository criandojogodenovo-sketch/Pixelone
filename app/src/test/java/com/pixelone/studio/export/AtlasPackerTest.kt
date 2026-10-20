package com.pixelone.studio.export

import com.pixelone.studio.osp.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes obrigatórios do atlas de texturas (teste E da especificação). */
class AtlasPackerTest {

    private fun textura(nome: String, w: Int, h: Int, cor: Int): AtlasPacker.TextureSource {
        val px = IntArray(w * h) { cor }
        px[0] = 0x00000000.toInt() // canto transparente para testes de alpha
        return AtlasPacker.TextureSource(nome, w, h, px)
    }

    @Test
    fun `packing determinístico com ordenação altura desc largura desc nome asc`() {
        val t1 = textura("b", 4, 8, 0xFF0000FF.toInt())
        val t2 = textura("a", 4, 8, 0xFF00FF00.toInt())
        val t3 = textura("c", 10, 4, 0xFFFF0000.toInt())
        val t4 = textura("d", 6, 4, 0xFFFFFFFF.toInt())

        val planA = AtlasPacker.pack(
            listOf(t1, t2, t3, t4), spacing = 1, margin = 1, maxWidth = 64, maxHeight = 64
        )
        val planB = AtlasPacker.pack(
            listOf(t4, t3, t2, t1), spacing = 1, margin = 1, maxWidth = 64, maxHeight = 64
        )
        // Mesmas entradas em ordem diferente → MESMO layout (determinístico)
        assertEquals(planA.regions, planB.regions)
        assertEquals(planA.width, planB.width)
        assertEquals(planA.height, planB.height)

        // Ordenação: as de altura 8 primeiro (t2 'a' antes de t1 'b' por nome)
        assertEquals("a", planA.regions[0].name)
        assertEquals("b", planA.regions[1].name)
        // Depois as de altura 4: 'c' (largura 10) antes de 'd' (largura 6)
        assertEquals("c", planA.regions[2].name)
        assertEquals("d", planA.regions[3].name)
    }

    @Test
    fun `sem sobreposições e sem sair dos limites`() {
        val srcs = listOf(
            textura("t1", 16, 16, 0xFFFF0000.toInt()),
            textura("t2", 8, 32, 0xFF00FF00.toInt()),
            textura("t3", 12, 8, 0xFF0000FF.toInt()),
            textura("t4", 24, 6, 0xFFFFFFFF.toInt()),
            textura("t5", 5, 5, 0xFF112233.toInt())
        )
        val plan = AtlasPacker.pack(srcs, spacing = 2, margin = 2, maxWidth = 64, maxHeight = 64)
        assertTrue(plan.width <= 64)
        assertTrue(plan.height <= 64)

        // Grellha de ocupação
        val ocup = Array(64) { IntArray(64) }
        for (r in plan.regions) {
            for (row in 0 until r.height) {
                for (col in 0 until r.width) {
                    val x = r.x + col
                    val y = r.y + row
                    assertEquals("sobreposição em ($x,$y)", 0, ocup[y][x])
                    ocup[y][x] = 1
                }
            }
        }
    }

    @Test
    fun `compose preserva dados originais e transparência`() {
        val srcs = listOf(
            textura("azul", 4, 4, 0xFF0000FF.toInt()),
            textura("verde", 4, 2, 0xFF00FF00.toInt())
        )
        val plan = AtlasPacker.pack(srcs, spacing = 0, margin = 0, maxWidth = 16, maxHeight = 16)
        val out = plan.compose(srcs)
        for (r in plan.regions) {
            val src = srcs.first { it.name == r.name }
            for (row in 0 until r.height) {
                for (col in 0 until r.width) {
                    val esperado = src.pixels[row * src.width + col]
                    val obtido = out[(r.y + row) * plan.width + r.x + col]
                    assertEquals("píxel (${r.x + col},${r.y + row}) de ${r.name}", esperado, obtido)
                }
            }
        }
        // Fora das regiões: transparente
        assertEquals(0x00000000.toInt(), out[0])
    }

    @Test
    fun `metadados JSON correspondem ao plano`() {
        val srcs = listOf(
            textura("heroi", 8, 8, 0xFF0000FF.toInt()),
            textura("inimigo", 4, 4, 0xFFFF0000.toInt())
        )
        val plan = AtlasPacker.pack(srcs, spacing = 1, margin = 1, maxWidth = 32, maxHeight = 32)
        val meta = MiniJson.parse(plan.metadataJson()) as Map<*, *>
        assertEquals(1L, (meta["schemaVersion"] as Number).toLong())
        assertEquals("pixel-one-atlas", meta["type"])
        val atlas = meta["atlas"] as Map<*, *>
        assertEquals(plan.width.toLong(), (atlas["width"] as Number).toLong())
        assertEquals(plan.height.toLong(), (atlas["height"] as Number).toLong())
        assertEquals(2L, (meta["regionCount"] as Number).toLong())
        val regions = meta["regions"] as List<*>
        val r0 = regions[0] as Map<*, *>
        assertEquals(plan.regions[0].name, r0["name"])
        assertEquals(plan.regions[0].x.toLong(), (r0["x"] as Number).toLong())
        assertEquals(plan.regions[0].width.toLong(), (r0["width"] as Number).toLong())
    }

    @Test
    fun `entradas que não cabem produzem erro explicativo`() {
        val grande = textura("enorme", 60, 60, 0xFF000000.toInt())
        val ex = try {
            AtlasPacker.pack(listOf(grande), spacing = 0, margin = 2, maxWidth = 32, maxHeight = 32)
            null
        } catch (e: AtlasPacker.DoesNotFitException) { e }
        assertTrue(ex != null)
        val msg = ex!!.message!!
        assertTrue(msg.contains("enorme") && msg.contains("60x60"))

        // Várias pequenas que somadas excedem a altura
        val muitos = (0 until 10).map { textura("m$it", 20, 10, 0xFF000000.toInt()) }
        val ex2 = try {
            AtlasPacker.pack(muitos, spacing = 0, margin = 0, maxWidth = 40, maxHeight = 20)
            null
        } catch (e: AtlasPacker.DoesNotFitException) { e }
        assertTrue(ex2 != null)
    }

    @Test
    fun `limites inválidos são rejeitados`() {
        val t = textura("t", 4, 4, 0xFF000000.toInt())
        val ex = try {
            AtlasPacker.pack(listOf(t), 0, 0, maxWidth = AtlasPacker.MAX_ATLAS_DIMENSION + 1, maxHeight = 64)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex != null)
        val ex2 = try {
            AtlasPacker.pack(listOf(t), 0, 0, maxWidth = 16, maxHeight = 16).let { plan ->
                plan.compose(listOf(t)); plan
            }
            null
        } catch (e: Exception) { e }
        assertTrue(ex2 == null) // caso normal funciona
        val ex3 = try {
            AtlasPacker.pack(emptyList(), 0, 0, 64, 64)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex3 != null)
    }

    @Test
    fun `atlas altura final é compacta altura real usada`() {
        val t1 = textura("a", 8, 8, 0xFF000000.toInt())
        val t2 = textura("b", 8, 4, 0xFF000000.toInt())
        val plan = AtlasPacker.pack(listOf(t1, t2), spacing = 0, margin = 0, maxWidth = 16, maxHeight = 64)
        // t1 (h8) na prateleira 0; t2 (w8) cabe ao lado? 8+8=16 ≤ 16 sim →
        // prateleira única, altura = 8
        assertEquals(8, plan.height)
        assertEquals(16, plan.width)
    }
}
