package com.pixelone.studio.studio3d

import com.pixelone.studio.pixel.LayerStack
import com.pixelone.studio.pixel.LayeredHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste de INTEGRAÇÃO do pipeline de pintura (Ronda B) — prova o encadeamento
 * COMPLETO sem Android:
 *
 *   toque (NDC) → raycast ([MeshPicking]) → face → UV por baricêntricas
 *   ([PaintCore.uvAtHit]) → texel ([PaintCore.uvToTexel]) → máscara da ilha
 *   ([IslandMask]) → carimbo na camada ([PaintCore.stamp]) → composição
 *   regional ([LayerStack.composeRegion]) → histórico ([LayeredHistory]).
 *
 * TAMBÉM audita a Ronda A (secção 1 do pedido): o toque acerta na face certa
 * e a ilha correspondente é a célula certa da grelha 3×2 (seleção 3D↔UV
 * consistente).
 */
class RondaBPaintPipelineTest {

    private val model = DemoGeometry.createModel()
    private val texW = 64
    private val texH = 64

    /** Câmara determinística: olhando de +X para o centro do cubo (0,0.5,0). */
    private fun testCamera(): OrbitCamera {
        val cam = OrbitCamera(initialYawDeg = 0f, initialPitchDeg = 0f, initialDistance = 3f)
        cam.restore(OrbitCamera.CameraSnapshot(0f, 0f, 3f, 0f, 0.5f, 0f))
        return cam
    }

    /** Funil completo do toque: NDC → (renderable, triângulo, uv, texel) ou null. */
    private fun touchToTexel(ndcX: Float, ndcY: Float): Quad? {
        val cam = testCamera()
        val ray = MeshPicking.rayFromNdc(cam, ndcX, ndcY, 1f)
        val hit = MeshPicking.pick(model, ray) ?: return null
        val r = model.renderables[hit.renderableIndex]
        val i = hit.triangleIndex * 3
        val uv = PaintCore.uvAtHit(
            hit.point, r.positions,
            r.indices[i], r.indices[i + 1], r.indices[i + 2], r.uvs!!
        ) ?: return null
        return Quad(
            hit.renderableIndex, hit.triangleIndex, uv,
            PaintCore.uvToTexelX(uv[0], texW), PaintCore.uvToTexelY(uv[1], texH)
        )
    }

    private data class Quad(
        val renderable: Int, val triangle: Int, val uv: FloatArray,
        val texelX: Int, val texelY: Int
    )

    // ------------------------------------------------------------------
    // Auditoria Ronda A: toque → face → ilha CERTA (3D ↔ UV consistentes)
    // ------------------------------------------------------------------

    @Test
    fun `toque no centro acerta na face +X e a ilha é a célula certa da grelha`() {
        val q = touchToTexel(0f, 0f)
        assertNotNull(q)
        assertEquals(0, q!!.renderable) // cubo
        // Face +X = face 0 do cubo → ilha 0 → célula (col 0, row 0) da grelha 3×2.
        val cube = model.renderables[0]
        val islands = UvIslands.compute(cube.indices, cube.uvs!!)
        val island = islands.firstOrNull { isl ->
            isl.triangleIndices.any { it == q.triangle }
        }
        assertNotNull("triângulo tocado tem de pertencer a uma ilha", island)
        val rect = DemoGeometry.cubeFaceUvRect(0)
        assertEquals(rect[0], island!!.minU, 1e-5f)
        assertEquals(rect[1], island.minV, 1e-5f)
        assertEquals(rect[2], island.maxU, 1e-5f)
        assertEquals(rect[3], island.maxV, 1e-5f)
        // O texel do toque está DENTRO da célula da face 0.
        assertTrue(q.texelX in 0..21)
        assertTrue(q.texelY in 0..21)
    }

    @Test
    fun `toque acima do cubo não acerta em nada`() {
        assertNull(touchToTexel(0f, 0.95f))
    }

    // ------------------------------------------------------------------
    // Pintura com máscara: nada fora da ilha muda (regra central da Ronda B)
    // ------------------------------------------------------------------

    @Test
    fun `traço com bloqueio na ilha não altera NENHUM texel fora da máscara`() {
        val stack = LayerStack(texW, texH)
        stack.seedSingleLayer(DemoGeometry.createDefaultTexture(texW, texH))
        val doc = stack.activeDoc()
        val before = doc.copyPixels()

        val cube = model.renderables[0]
        val islands = UvIslands.compute(cube.indices, cube.uvs!!)
        val face0 = islands[0]
        val mask = IslandMask.build(cube.indices, cube.uvs!!, face0, texW, texH, paddingPx = 0)
        val mb = mask.bounds()!!

        // Traço vertical de 20 texels ATRAVESSANDO a textura (linha inteira),
        // com a máscara ativa: só pode mudar DENTRO da ilha da face 0.
        val history = LayeredHistory()
        history.beginStroke(0)
        val bbox = PaintCore.drawLineSegment(
            doc, mb[0] + 1, 0, mb[0] + 1, texH - 1,
            0xFFFF00FF.toInt(), 1, mask, PaintCore.Symmetry.NONE,
            PaintCore.MirrorBase.ISLAND, history
        )
        history.commitStroke(stack)
        assertNotNull(bbox)

        val after = doc.copyPixels()
        var changedOutside = 0
        var changedInside = 0
        for (y in 0 until texH) {
            for (x in 0 until texW) {
                val i = y * texW + x
                if (before[i] != after[i]) {
                    if (mask.contains(x, y)) changedInside++ else changedOutside++
                }
            }
        }
        assertEquals("NADA pode mudar fora da máscara", 0, changedOutside)
        assertTrue("a pintura dentro da ilha aconteceu", changedInside > 0)
    }

    @Test
    fun `balde fora da ilha não faz nada com bloqueio ativo`() {
        val stack = LayerStack(texW, texH)
        stack.seedSingleLayer(DemoGeometry.createDefaultTexture(texW, texH))
        val doc = stack.activeDoc()
        val before = doc.copyPixels()

        val cube = model.renderables[0]
        val islands = UvIslands.compute(cube.indices, cube.uvs!!)
        // Ilha da face 4 (+Z, célula col 1 row 1): o toque é feito na ilha do
        // CHÃO (faixa inferior) — fora da ilha selecionada.
        val face4 = islands[4]
        val mask = IslandMask.build(cube.indices, cube.uvs!!, face4, texW, texH, paddingPx = 0)
        val indices = IntArray(texW * texH); val bef = IntArray(texW * texH)
        val aft = IntArray(texW * texH); val bbox = IntArray(4)
        // Texel (32, 50) pertence à ilha do chão (v=50/64≈0.78 ≥ 2/3).
        assertEquals(0, PaintCore.floodFillMasked(
            doc, 32, 50, 0xFFFFFFFF.toInt(), 0, mask, indices, bef, aft, bbox
        ))
        assertArrayEqualsDoc(before, doc.copyPixels())
    }

    @Test
    fun `balde dentro da ilha preenche só até à máscara`() {
        val stack = LayerStack(texW, texH)
        stack.seedSingleLayer(DemoGeometry.createDefaultTexture(texW, texH))
        val doc = stack.activeDoc()
        val before = doc.copyPixels()

        val cube = model.renderables[0]
        val islands = UvIslands.compute(cube.indices, cube.uvs!!)
        val face0 = islands[0]
        val mask = IslandMask.build(cube.indices, cube.uvs!!, face0, texW, texH, paddingPx = 0)
        val indices = IntArray(texW * texH); val bef = IntArray(texW * texH)
        val aft = IntArray(texW * texH); val bbox = IntArray(4)
        val n = PaintCore.floodFillMasked(
            doc, 10, 10, 0xFFFFFFFF.toInt(), 0, mask, indices, bef, aft, bbox
        )
        assertTrue("o balde dentro da ilha tem de preencher algo", n > 0)
        val after = doc.copyPixels()
        for (y in 0 until texH) {
            for (x in 0 until texW) {
                val i = y * texW + x
                if (before[i] != after[i]) {
                    assertTrue("texel ($x,$y) fora da ilha mudou", mask.contains(x, y))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Atualização PARCIAL: o dirty rect de um traço de 1 px é 1 px
    // ------------------------------------------------------------------

    @Test
    fun `traço de um texel produz região suja de um texel (nunca a textura inteira)`() {
        val stack = LayerStack(texW, texH)
        stack.seedSingleLayer(IntArray(texW * texH))
        val doc = stack.activeDoc()
        val bbox = PaintCore.stamp(
            doc, 20, 20, 0xFFF57C00.toInt(), 1, null,
            PaintCore.Symmetry.NONE, PaintCore.MirrorBase.TEXTURE, null
        )
        assertNotNull(bbox)
        assertEquals(1, bbox!!.maxX - bbox.minX + 1)
        assertEquals(1, bbox.maxY - bbox.minY + 1)
        // Região recomposta = região exata (composeRegion coerente com compose).
        val rw = bbox.maxX - bbox.minX + 1
        val rh = bbox.maxY - bbox.minY + 1
        val region = IntArray(rw * rh)
        stack.composeRegion(bbox.minX, bbox.minY, rw, rh, region)
        assertEquals(0xFFF57C00.toInt(), region[0])
    }

    // ------------------------------------------------------------------
    // Undo do traço restaura a composição (histórico partilhado 3D/UV)
    // ------------------------------------------------------------------

    @Test
    fun `traço numa camada e undo restaura a composição exata`() {
        val stack = LayerStack(texW, texH)
        stack.seedSingleLayer(DemoGeometry.createDefaultTexture(texW, texH))
        val doc = stack.activeDoc()
        val before = doc.copyPixels()
        val history = LayeredHistory()

        history.beginStroke(0)
        PaintCore.drawLineSegment(
            doc, 0, 0, 40, 40, 0xFF123456.toInt(), 2, null,
            PaintCore.Symmetry.NONE, PaintCore.MirrorBase.TEXTURE, history
        )
        history.commitStroke(stack)
        val during = stack.compose()
        assertTrue(during.contentEquals(stack.compose()))

        val entry = history.undo(stack)
        assertNotNull(entry)
        val after = stack.compose()
        assertFalse(after.contentEquals(during))
        assertTrue("undo devia restaurar o estado anterior", after.contentEquals(before))
    }

    // ------------------------------------------------------------------
    // Simetria pela ILHA (base ISLAND com máscara)
    // ------------------------------------------------------------------

    @Test
    fun `simetria horizontal espelha pela caixa da ilha e respeita a máscara`() {
        val stack = LayerStack(texW, texH)
        stack.seedSingleLayer(IntArray(texW * texH))
        val doc = stack.activeDoc()
        val cube = model.renderables[0]
        val islands = UvIslands.compute(cube.indices, cube.uvs!!)
        val face0 = islands[0]
        val mask = IslandMask.build(cube.indices, cube.uvs!!, face0, texW, texH, paddingPx = 0)
        val mb = mask.bounds()!!

        PaintCore.stamp(
            doc, mb[0] + 2, 10, 0xFFFF0000.toInt(), 1, mask,
            PaintCore.Symmetry.HORIZONTAL, PaintCore.MirrorBase.ISLAND, null
        )
        val mx = PaintCore.mirrorX(mb[0] + 2, texW, PaintCore.MirrorBase.ISLAND, mb)
        assertEquals(0xFFFF0000.toInt(), doc.get(mb[0] + 2, 10))
        assertEquals(0xFFFF0000.toInt(), doc.get(mx, 10))
        // O espelho está dentro da máscara (a ilha espelha dentro de si mesma).
        assertTrue(mask.contains(mx, 10))
    }

    private fun assertArrayEqualsDoc(expected: IntArray, actual: IntArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
