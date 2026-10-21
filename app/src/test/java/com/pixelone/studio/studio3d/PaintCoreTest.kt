package com.pixelone.studio.studio3d

import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do NÚCLEO DE PINTURA (Ronda B): mapeamento toque→UV→texel, UV no
 * ponto de impacto 3D (baricêntricas), carimbo com máscara/simetria, linha
 * sem falhas, contorno de retângulo e balde limitado por máscara.
 * Kotlin puro — JVM, sem Robolectric.
 */
class PaintCoreTest {

    // ------------------------------------------------------------------
    // UV → texel
    // ------------------------------------------------------------------

    @Test
    fun `uv 0 mapeia para o primeiro texel e uv 1 para o último`() {
        assertEquals(0, PaintCore.uvToTexelX(0f, 64))
        assertEquals(63, PaintCore.uvToTexelX(1f, 64))
        assertEquals(0, PaintCore.uvToTexelY(0f, 64))
        assertEquals(63, PaintCore.uvToTexelY(1f, 64))
    }

    @Test
    fun `uv no meio do texel 10 mapeia para o texel 10`() {
        // Centro do texel 10 em 64 = (10.5/64).
        assertEquals(10, PaintCore.uvToTexelX(10.5f / 64f, 64))
    }

    @Test
    fun `uv fora do intervalo é recortado aos limites`() {
        assertEquals(0, PaintCore.uvToTexelX(-0.5f, 64))
        assertEquals(63, PaintCore.uvToTexelX(1.5f, 64))
    }

    // ------------------------------------------------------------------
    // UV no ponto de impacto 3D (baricêntricas) — pintar no 3D == pintar na UV
    // ------------------------------------------------------------------

    @Test
    fun `uvAtHit interpola as UV por baricêntricas num triângulo conhecido`() {
        val positions = floatArrayOf(
            0f, 0f, 0f,   // a
            1f, 0f, 0f,   // b
            0f, 1f, 0f    // c
        )
        val uvs = floatArrayOf(0.1f, 0.1f, 0.5f, 0.1f, 0.1f, 0.5f)
        // p = 0.5·a + 0.25·b + 0.25·c → uv = 0.5·(0.1,0.1)+0.25·(0.5,0.1)+0.25·(0.1,0.5)
        val p = floatArrayOf(0.25f, 0.25f, 0f)
        val uv = PaintCore.uvAtHit(p, positions, 0, 1, 2, uvs)
        assertNotNull(uv)
        // u = 0.5·0.1 + 0.25·0.5 + 0.25·0.1 = 0.2
        assertEquals(0.2f, uv!![0], 1e-4f)
        assertEquals(0.2f, uv[1], 1e-4f)
    }

    @Test
    fun `uvAtHit nos vértices devolve as UV exatas dos vértices`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val uvs = floatArrayOf(0.2f, 0.3f, 0.6f, 0.3f, 0.2f, 0.7f)
        val uvA = PaintCore.uvAtHit(floatArrayOf(0f, 0f, 0f), positions, 0, 1, 2, uvs)
        assertEquals(0.2f, uvA!![0], 1e-4f)
        assertEquals(0.3f, uvA[1], 1e-4f)
        val uvC = PaintCore.uvAtHit(floatArrayOf(0f, 1f, 0f), positions, 0, 1, 2, uvs)
        assertEquals(0.2f, uvC!![0], 1e-4f)
        assertEquals(0.7f, uvC[1], 1e-4f)
    }

    @Test
    fun `uvAtHit devolve null para triângulo degenerado`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f)
        val uvs = FloatArray(6)
        assertNull(PaintCore.uvAtHit(floatArrayOf(0.5f, 0f, 0f), positions, 0, 1, 2, uvs))
    }

    // ------------------------------------------------------------------
    // Simetria
    // ------------------------------------------------------------------

    @Test
    fun `espelho pela textura reflete no centro da textura`() {
        assertEquals(63, PaintCore.mirrorX(0, 64, PaintCore.MirrorBase.TEXTURE, null))
        assertEquals(20, PaintCore.mirrorX(43, 64, PaintCore.MirrorBase.TEXTURE, null))
        assertEquals(31, PaintCore.mirrorY(32, 64, PaintCore.MirrorBase.TEXTURE, null))
    }

    @Test
    fun `espelho pela ilha reflete no centro da caixa da ilha`() {
        val bounds = intArrayOf(10, 20, 30, 40) // x0,y0,x1,y1
        assertEquals(30, PaintCore.mirrorX(10, 64, PaintCore.MirrorBase.ISLAND, bounds)) // (10+30)−10
        assertEquals(10, PaintCore.mirrorX(30, 64, PaintCore.MirrorBase.ISLAND, bounds))
        assertEquals(40, PaintCore.mirrorY(20, 64, PaintCore.MirrorBase.ISLAND, bounds)) // (20+40)−20
    }

    @Test
    fun `simetria horizontal carimbo em dois pontos espelhados`() {
        val doc = PixelDocument(32, 32)
        val bbox = PaintCore.stamp(
            doc, 8, 16, 0xFFFF0000.toInt(), 1,
            islandMask = null, symmetry = PaintCore.Symmetry.HORIZONTAL,
            mirrorBase = PaintCore.MirrorBase.TEXTURE, recorder = null
        )
        assertNotNull(bbox)
        assertEquals(0xFFFF0000.toInt(), doc.get(8, 16))
        assertEquals(0xFFFF0000.toInt(), doc.get(23, 16)) // 31−8
        assertEquals(0x00000000, doc.get(8, 17))
        // bbox cobre AMBOS os pontos (para o dirty rect da GPU).
        assertEquals(8, bbox!!.minX); assertEquals(23, bbox.maxX)
        assertEquals(16, bbox.minY); assertEquals(16, bbox.maxY)
    }

    @Test
    fun `simetria H+V carimbo nos 4 quadrantes`() {
        val doc = PixelDocument(32, 32)
        PaintCore.stamp(
            doc, 5, 7, 0xFF00FF00.toInt(), 1, null,
            PaintCore.Symmetry.BOTH, PaintCore.MirrorBase.TEXTURE, null
        )
        assertEquals(0xFF00FF00.toInt(), doc.get(5, 7))
        assertEquals(0xFF00FF00.toInt(), doc.get(26, 7))
        assertEquals(0xFF00FF00.toInt(), doc.get(5, 24))
        assertEquals(0xFF00FF00.toInt(), doc.get(26, 24))
        assertEquals(4, doc.copyPixels().count { it == 0xFF00FF00.toInt() })
    }

    // ------------------------------------------------------------------
    // Linha e retângulo
    // ------------------------------------------------------------------

    @Test
    fun `linha horizontal contínua sem falhas`() {
        val doc = PixelDocument(32, 32)
        val bbox = PaintCore.drawLineSegment(
            doc, 2, 10, 12, 10, 0xFFFFFFFF.toInt(), 1, null,
            PaintCore.Symmetry.NONE, PaintCore.MirrorBase.TEXTURE, null
        )
        assertNotNull(bbox)
        for (x in 2..12) {
            assertEquals("texel ($x,10) devia estar pintado", 0xFFFFFFFF.toInt(), doc.get(x, 10))
        }
        assertEquals(0x00000000, doc.get(13, 10))
    }

    @Test
    fun `linha diagonal não deixa falhas (Bresenham)`() {
        val doc = PixelDocument(32, 32)
        PaintCore.drawLineSegment(
            doc, 2, 2, 9, 9, 0xFFFFFFFF.toInt(), 1, null,
            PaintCore.Symmetry.NONE, PaintCore.MirrorBase.TEXTURE, null
        )
        // Diagonal perfeita: um píxel por coluna, sem saltos.
        for (x in 2..9) {
            assertEquals(0xFFFFFFFF.toInt(), doc.get(x, x))
        }
    }

    @Test
    fun `retângulo pinta o contorno e preserva o interior`() {
        val doc = PixelDocument(32, 32)
        val bbox = PaintCore.drawRectOutline(
            doc, 3, 3, 8, 8, 0xFF0000FF.toInt(), 1, null,
            PaintCore.Symmetry.NONE, PaintCore.MirrorBase.TEXTURE, null
        )
        assertNotNull(bbox)
        // Contorno pintado.
        for (x in 3..8) {
            assertEquals(0xFF0000FF.toInt(), doc.get(x, 3))
            assertEquals(0xFF0000FF.toInt(), doc.get(x, 8))
        }
        for (y in 3..8) {
            assertEquals(0xFF0000FF.toInt(), doc.get(3, y))
            assertEquals(0xFF0000FF.toInt(), doc.get(8, y))
        }
        // Interior intacto.
        assertEquals(0x00000000, doc.get(5, 5))
    }

    // ------------------------------------------------------------------
    // Balde com máscara
    // ------------------------------------------------------------------

    @Test
    fun `balde preenche a região 4-conectada e devolve deltas e bbox`() {
        val doc = PixelDocument(16, 16)
        val indices = IntArray(256); val before = IntArray(256); val after = IntArray(256)
        val bbox = IntArray(4)
        val n = PaintCore.floodFillMasked(
            doc, 0, 0, 0xFF00FFFF.toInt(), 0, null, indices, before, after, bbox
        )
        assertEquals(256, n) // 16×16 tudo da mesma cor (transparente)
        assertTrue(doc.copyPixels().all { it == 0xFF00FFFF.toInt() })
        assertEquals(0, bbox[0]); assertEquals(0, bbox[1])
        assertEquals(15, bbox[2]); assertEquals(15, bbox[3])
    }

    @Test
    fun `balde não faz nada quando a cor destino é igual`() {
        val doc = PixelDocument(8, 8)
        doc.set(0, 0, 0xFF112233.toInt())
        val n = PaintCore.floodFillMasked(
            doc, 0, 0, 0xFF112233.toInt(), 0, null,
            IntArray(64), IntArray(64), IntArray(64), IntArray(4)
        )
        assertEquals(0, n)
    }
}
