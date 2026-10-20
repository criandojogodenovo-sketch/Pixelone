package com.pixelone.studio.export

import com.pixelone.studio.osp.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes obrigatórios de spritesheets e atlas (teste E da especificação). */
class SpriteSheetPlannerTest {

    private fun quadro(nome: String, w: Int, h: Int, cor: Int): SpriteSheetPlanner.ImageSource {
        val px = IntArray(w * h) { cor }
        // Marca o canto superior esquerdo com transparência para testar alpha
        px[0] = 0x00000000.toInt()
        return SpriteSheetPlanner.ImageSource(nome, w, h, px)
    }

    @Test
    fun `coordenadas dos quadros corretas com colunas espaçamento e margem`() {
        val srcs = listOf(
            quadro("a", 8, 8, 0xFFFF0000.toInt()),
            quadro("b", 8, 8, 0xFF00FF00.toInt()),
            quadro("c", 8, 8, 0xFF0000FF.toInt()),
            quadro("d", 8, 8, 0xFFFFFFFF.toInt()),
            quadro("e", 8, 8, 0xFF112233.toInt())
        )
        val plan = SpriteSheetPlanner.plan(srcs, 8, 8, columns = 3, spacing = 2, margin = 4)
        // 3 colunas, 2 linhas: largura = 4*2 + 3*8 + 2*2 = 36; altura = 4*2 + 2*8 + 1*2 = 26
        assertEquals(36, plan.width)
        assertEquals(26, plan.height)

        val p0 = plan.placements[0]
        assertEquals(4, p0.x); assertEquals(4, p0.y); assertEquals(0, p0.row); assertEquals(0, p0.column)
        val p1 = plan.placements[1]
        assertEquals(4 + 8 + 2, p1.x); assertEquals(4, p1.y); assertEquals(1, p1.column)
        val p3 = plan.placements[3]
        assertEquals(4, p3.x); assertEquals(4 + 8 + 2, p3.y); assertEquals(1, p3.row); assertEquals(0, p3.column)
        val p4 = plan.placements[4]
        assertEquals(4 + 8 + 2, p4.x); assertEquals(14, p4.y)
    }

    @Test
    fun `colunas automáticas são determinísticas ceil sqrt`() {
        val srcs = (0 until 9).map { quadro("f$it", 4, 4, 0xFF000000.toInt()) }
        val plan = SpriteSheetPlanner.plan(srcs, 0, 0, columns = 0, spacing = 0, margin = 0)
        assertEquals(3, plan.columns)
        assertEquals(3, plan.rows)
        val srcs7 = (0 until 7).map { quadro("g$it", 4, 4, 0xFF000000.toInt()) }
        val plan7 = SpriteSheetPlanner.plan(srcs7, 0, 0, columns = 0, spacing = 0, margin = 0)
        assertEquals(3, plan7.columns) // ceil(sqrt(7)) = 3
        assertEquals(3, plan7.rows)
    }

    @Test
    fun `compose sem sobreposições e metadados JSON correspondem ao PNG gerado`() {
        val srcs = listOf(
            quadro("andar_0", 4, 4, 0xFFFF0000.toInt()),
            quadro("andar_1", 4, 4, 0xFF00FF00.toInt()),
            quadro("andar_2", 4, 4, 0xFF0000FF.toInt()),
            quadro("andar_3", 4, 4, 0xFFFFFFFF.toInt())
        )
        val plan = SpriteSheetPlanner.plan(srcs, 4, 4, columns = 2, spacing = 1, margin = 1)
        val out = plan.compose(srcs)

        // Sem sobreposições: cada píxel ocupado corresponde a exatamente um quadro
        val ocupacoes = HashMap<Pair<Int, Int>, String>()
        for (p in plan.placements) {
            for (row in 0 until p.height) {
                for (col in 0 until p.width) {
                    val key = (p.x + col) to (p.y + row)
                    assertTrue("sobreposição em $key", ocupacoes.put(key, p.name) == null)
                }
            }
        }
        // O píxel composto em cada região tem a cor do quadro (canto = transparente)
        for (p in plan.placements) {
            val corQuadro = srcs[p.index].pixels[1] // píxel (1,0)
            assertEquals(corQuadro, out[p.y * plan.width + p.x + 1])
            // Canto transparente preservado
            assertEquals(0x00000000.toInt(), out[p.y * plan.width + p.x])
        }
        // Fundo (espaçamento/margem) transparente
        assertEquals(0x00000000.toInt(), out[0])

        // Metadados JSON coerentes
        val meta = MiniJson.parse(plan.metadataJson()) as Map<*, *>
        assertEquals(1L, (meta["schemaVersion"] as Number).toLong())
        assertEquals("pixel-one-spritesheet", meta["type"])
        val sheet = meta["sheet"] as Map<*, *>
        assertEquals(plan.width.toLong(), (sheet["width"] as Number).toLong())
        assertEquals(plan.height.toLong(), (sheet["height"] as Number).toLong())
        val frames = meta["frames"] as List<*>
        assertEquals(4, frames.size)
        val f0 = frames[0] as Map<*, *>
        assertEquals("andar_0", f0["name"])
        assertEquals(plan.placements[0].x.toLong(), (f0["x"] as Number).toLong())
        assertEquals(plan.placements[0].y.toLong(), (f0["y"] as Number).toLong())
        assertEquals(0L, (f0["index"] as Number).toLong())
        assertEquals(0L, (f0["row"] as Number).toLong())
        assertEquals(0L, (f0["column"] as Number).toLong())
        // frameCount
        assertEquals(4L, (meta["frameCount"] as Number).toLong())
    }

    @Test
    fun `ordem e nomes dos quadros de animação preservados`() {
        val srcs = (0 until 5).map { quadro("frame_${String.format("%02d", it)}", 2, 2, 0xFF000000.toInt() or it) }
        val plan = SpriteSheetPlanner.plan(srcs, 2, 2, columns = 5, spacing = 0, margin = 0)
        plan.placements.forEachIndexed { i, p ->
            assertEquals("frame_${String.format("%02d", i)}", p.name)
            assertEquals(i, p.index)
        }
        // JSON preserva a ordem da lista
        val meta = MiniJson.parse(plan.metadataJson()) as Map<*, *>
        val frames = meta["frames"] as List<*>
        assertEquals("frame_00", (frames[0] as Map<*, *>)["name"])
        assertEquals("frame_04", (frames[4] as Map<*, *>)["name"])
    }

    @Test
    fun `dimensões incompatíveis são rejeitadas com lista dos ofensores`() {
        val srcs = listOf(
            quadro("ok", 8, 8, 0xFF000000.toInt()),
            quadro("grande", 16, 8, 0xFF000000.toInt()),
            quadro("outro", 8, 8, 0xFF000000.toInt())
        )
        val ex = try {
            SpriteSheetPlanner.plan(srcs, 8, 8, columns = 1, spacing = 0, margin = 0)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex != null)
        assertTrue(ex!!.message!!.contains("grande (16x8)"))
    }

    @Test
    fun `limites de dimensão e memória são rejeitados`() {
        val muitos = (0 until 64).map { quadro("q$it", 64, 64, 0xFF000000.toInt()) }
        // 64 quadros de 64x64 em 64 colunas: 4096 + margem 0 → largura ok (4096)
        // mas 64x64 com spacing 1 em 64 colunas → 4096+63 = 4159 > 4096
        val ex = try {
            SpriteSheetPlanner.plan(muitos, 64, 64, columns = 64, spacing = 1, margin = 0)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex != null)
        assertTrue(ex!!.message!!.contains("4096"))

        // Colunas inválidas
        val ex2 = try {
            SpriteSheetPlanner.plan(muitos.take(1), 64, 64, columns = 100, spacing = 0, margin = 0)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex2 != null)

        // Entrada vazia
        val ex3 = try {
            SpriteSheetPlanner.plan(emptyList(), 8, 8, columns = 1, spacing = 0, margin = 0)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex3 != null)
    }

    @Test
    fun `transparência de fundo e dos quadros preservada`() {
        val transparente = SpriteSheetPlanner.ImageSource(
            "t", 2, 2, intArrayOf(0x00000000.toInt(), 0x00000000.toInt(), 0x00000000.toInt(), 0x00000000.toInt())
        )
        val plan = SpriteSheetPlanner.plan(listOf(transparente), 2, 2, columns = 1, spacing = 0, margin = 0)
        val out = plan.compose(listOf(transparente))
        assertTrue(out.all { it == 0x00000000.toInt() })
    }
}
