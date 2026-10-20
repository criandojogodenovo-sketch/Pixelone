package com.pixelone.studio.pixel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes obrigatórios do motor e do buffer (teste A da especificação) —
 * processamento de TRAÇOS EM LOTE (caminho fallback Kotlin, semântica
 * idêntica ao C++17 nativo). O caminho nativo é provado nos testes
 * instrumentados (androidTest) em emulador/dispositivo.
 *
 * Estes testes confirmam:
 * - cantos e centro; linhas horizontais, verticais e diagonais CONTÍNUAS
 *   (sem falhas entre pontos do lote);
 * - brocha e borracha (transparência real);
 * - deltas = píxeis alterados; dirty rect contém todas as alterações;
 * - nenhum acesso fora dos limites;
 * - erros e dimensões inválidas sem crash.
 */
class StrokeBatchTest {

    private fun doc(w: Int = 16, h: Int = 16): PixelDocument = PixelDocument(w, h)

    private fun newScratch(d: PixelDocument): NativeEngine.Scratch = NativeEngine.Scratch(d.pixelCount)

    // ------------------------------------------------------------------
    // Pontos isolados (cantos e centro)
    // ------------------------------------------------------------------

    @Test
    fun `traço num canto superior esquerdo respeita os limites`() {
        val d = doc()
        val result = NativeEngine.strokeBatch(
            d, intArrayOf(0, 0), 1, 0xFFFF0000.toInt(), 1, newScratch(d)
        )
        assertTrue(result != null && result.changedSomething)
        assertEquals(0xFFFF0000.toInt(), d.get(0, 0))
        assertEquals(1, result!!.deltaCount)
        assertEquals(0, result.dirtyX0); assertEquals(0, result.dirtyY0)
        assertEquals(0, result.dirtyX1); assertEquals(0, result.dirtyY1)
    }

    @Test
    fun `traço no canto inferior direito e no centro`() {
        val d = doc()
        NativeEngine.strokeBatch(
            d, intArrayOf(15, 15), 1, 0xFF00FF00.toInt(), 1, newScratch(d)
        )
        NativeEngine.strokeBatch(
            d, intArrayOf(8, 8), 1, 0xFF0000FF.toInt(), 1, newScratch(d)
        )
        assertEquals(0xFF00FF00.toInt(), d.get(15, 15))
        assertEquals(0xFF0000FF.toInt(), d.get(8, 8))
    }

    // ------------------------------------------------------------------
    // Continuidade de linhas em lote (H, V, diagonais)
    // ------------------------------------------------------------------

    @Test
    fun `linha horizontal contínua entre pontos do lote`() {
        val d = doc()
        // Dois pontos distantes: o motor interpola (Bresenham) ENTRE eles.
        val r = NativeEngine.strokeBatch(
            d, intArrayOf(0, 3, 10, 3), 2, 0xFFFF0000.toInt(), 1, newScratch(d)
        )
        assertEquals(11, r!!.deltaCount) // x = 0..10
        for (x in 0..10) assertEquals("x=$x", 0xFFFF0000.toInt(), d.get(x, 3))
        assertEquals(3, r.dirtyY0); assertEquals(3, r.dirtyY1)
        assertEquals(0, r.dirtyX0); assertEquals(10, r.dirtyX1)
    }

    @Test
    fun `linha vertical contínua`() {
        val d = doc()
        val r = NativeEngine.strokeBatch(
            d, intArrayOf(5, 0, 5, 12), 2, 0xFF00FF00.toInt(), 1, newScratch(d)
        )
        assertEquals(13, r!!.deltaCount)
        for (y in 0..12) assertEquals("y=$y", 0xFF00FF00.toInt(), d.get(5, y))
    }

    @Test
    fun `diagonal íngreme sem falhas (Bresenham)`() {
        val d = doc()
        NativeEngine.strokeBatch(
            d, intArrayOf(0, 0, 5, 15), 2, 0xFFFFFFFF.toInt(), 1, newScratch(d)
        )
        // Para cada y (0..15) deve existir EXATAMENTE um píxel pintado na coluna vizinha
        // (conectividade 8 sem saltos): verifica que nenhum y ficou vazio.
        for (y in 0..15) {
            var painted = false
            for (x in 0..5) if (d.get(x, y) == 0xFFFFFFFF.toInt()) painted = true
            assertTrue("Linha com falha em y=$y", painted)
        }
    }

    @Test
    fun `lote com múltiplos segmentos é uma única operação`() {
        val d = doc()
        // Gesto em forma de "L": (2,2)→(2,10)→(10,10)
        val r = NativeEngine.strokeBatch(
            d, intArrayOf(2, 2, 2, 10, 10, 10), 3, 0xFF0000FF.toInt(), 1, newScratch(d)
        )
        // 9 (vertical) + 9 (horizontal, sem duplicar o canto) = 17
        assertEquals(17, r!!.deltaCount)
        for (y in 2..10) assertEquals(0xFF0000FF.toInt(), d.get(2, y))
        for (x in 2..10) assertEquals(0xFF0000FF.toInt(), d.get(x, 10))
        assertEquals(2, r.dirtyX0); assertEquals(2, r.dirtyY0)
        assertEquals(10, r.dirtyX1); assertEquals(10, r.dirtyY1)
    }

    // ------------------------------------------------------------------
    // Brocha e borracha
    // ------------------------------------------------------------------

    @Test
    fun `brocha de tamanho 3 carimbo centrado`() {
        val d = doc()
        NativeEngine.strokeBatch(d, intArrayOf(8, 8), 1, 0xFFFF0000.toInt(), 3, newScratch(d))
        // Carimbo 3x3 centrado em (8,8): x,y de 7..9
        for (y in 7..9) for (x in 7..9) assertEquals(0xFFFF0000.toInt(), d.get(x, y))
        assertEquals(0, d.get(6, 6))
        assertEquals(9, countPainted(d, 0xFFFF0000.toInt()))
    }

    @Test
    fun `borracha escreve transparência real`() {
        val d = doc()
        // Preenche tudo, depois apaga com borracha (color = 0x00000000)
        for (i in 0 until d.pixelCount) d.setAt(i, 0xFF444444.toInt())
        val r = NativeEngine.strokeBatch(
            d, intArrayOf(0, 0, 15, 15), 2, 0x00000000, 1, newScratch(d)
        )
        assertTrue(r != null && r.changedSomething)
        for (i in 0 until 16) {
            val idx = i * 16 + i
            assertEquals("Diagonal deve ficar transparente (idx=$idx)", 0, d.getAt(idx))
        }
    }

    // ------------------------------------------------------------------
    // Deltas e dirty rect
    // ------------------------------------------------------------------

    @Test
    fun `deltas correspondem exatamente aos píxeis alterados`() {
        val d = doc()
        d.setAt(3 * 16 + 3, 0xFF111111.toInt()) // já tem uma cor
        val scratch = newScratch(d)
        // Lote com 3 pontos: (3,3) → (4,3) → (5,3)
        NativeEngine.strokeBatch(
            d, intArrayOf(3, 3, 4, 3, 5, 3), 3, 0xFF222222.toInt(), 1, scratch
        )
        // Os 3 píxeis mudam (cores diferentes da aplicada)
        val changed = mutableSetOf<Int>()
        for (i in 0 until 3) {
            val idx = scratch.indices[i]
            val before = scratch.before[i]
            val after = scratch.after[i]
            assertEquals("after deve ser a cor aplicada", 0xFF222222.toInt(), after)
            changed.add(idx)
            // before deve ser a cor que estava ANTES
            val expectedBefore = when (idx) {
                3 * 16 + 3 -> 0xFF111111.toInt()
                else -> 0 // resto transparente
            }
            assertEquals("before do idx=$idx", expectedBefore, before)
        }
        // Traço horizontal: (3,3)→(4,3)→(5,3) ⇒ índices lineares y*16+x
        assertEquals(setOf(3 * 16 + 3, 3 * 16 + 4, 3 * 16 + 5), changed)
    }

    @Test
    fun `dirty rect contém todas as alterações do traço em L`() {
        val d = doc(32, 32)
        val scratch = newScratch(d)
        val r = NativeEngine.strokeBatch(
            d, intArrayOf(1, 1, 20, 1, 20, 30), 3, 0xFFABCDEF.toInt(), 1, scratch
        )
        assertTrue(r != null)
        for (i in 0 until r!!.deltaCount) {
            val idx = scratch.indices[i]
            val x = idx % 32; val y = idx / 32
            assertTrue("x=$x fora do dirty rect", x in r.dirtyX0..r.dirtyX1)
            assertTrue("y=$y fora do dirty rect", y in r.dirtyY0..r.dirtyY1)
        }
    }

    @Test
    fun `traço sobre a mesma cor não produz deltas (idempotente)`() {
        val d = doc()
        NativeEngine.strokeBatch(d, intArrayOf(0, 0, 5, 0), 2, 0xFF999999.toInt(), 1, newScratch(d))
        val r2 = NativeEngine.strokeBatch(d, intArrayOf(0, 0, 5, 0), 2, 0xFF999999.toInt(), 1, newScratch(d))
        assertNull("Segunda passada igual: nada muda", r2)
    }

    // ------------------------------------------------------------------
    // Erros e dimensões inválidas — SEM crash
    // ------------------------------------------------------------------

    @Test
    fun `pontos fora dos limites são ignorados sem crash`() {
        val d = doc()
        // Segmento inteiro FORA do documento (nunca entra nos limites):
        // nada deve crashar e nenhum píxel muda.
        val r = NativeEngine.strokeBatch(
            d, intArrayOf(-5, -5, -5, 100), 2, 0xFF000000.toInt(), 1, newScratch(d)
        )
        assertNull(r) // nenhum píxel dentro dos limites foi alterado
        for (i in 0 until d.pixelCount) assertEquals(0, d.getAt(i))
    }

    @Test
    fun `traço parcialmente fora recorta aos limites`() {
        val d = doc()
        val r = NativeEngine.strokeBatch(
            d, intArrayOf(0, 0, 20, 20), 2, 0xFFABCDEF.toInt(), 1, newScratch(d)
        )
        assertTrue(r != null)
        // Diagonal recortada ao documento 16x16: 16 píxeis, todos dentro dos limites
        assertEquals(16, r!!.deltaCount)
        for (i in 0 until 16) assertEquals(0xFFABCDEF.toInt(), d.get(i, i))
        // Fora do documento não há nada a verificar — mas todos os deltas
        // escritos correspondem a píxeis dentro dos limites (garantido acima).
    }

    // ------------------------------------------------------------------
    // Preenchimento com balde (lote discreto) — orçamento e limites
    // ------------------------------------------------------------------

    @Test
    fun `balde preenche região contígua e devolve deltas`() {
        val d = doc()
        // Muralha que separa o documento em duas regiões
        for (y in 0 until 16) d.set(8, y, 0xFF000000.toInt())
        val r = NativeEngine.floodFill(d, 2, 2, 0xFFFF0000.toInt(), 0, newScratch(d))
        assertTrue(r != null && r.changedSomething)
        // Lado esquerdo todo vermelho; lado direito intacto
        for (y in 0 until 16) {
            for (x in 0 until 8) assertEquals("x=$x,y=$y", 0xFFFF0000.toInt(), d.get(x, y))
            for (x in 9 until 16) assertEquals("x=$x,y=$y", 0, d.get(x, y))
        }
        assertEquals(0, r!!.dirtyX0)
        assertEquals(7, r.dirtyX1)
        // A muralha permanece intacta (não faz parte da região preenchida)
        assertEquals(0xFF000000.toInt(), d.get(8, 5))
        assertEquals(8 * 16, r.deltaCount)
    }

    @Test
    fun `balde com tolerância respeita fronteiras e não ultrapassa limites`() {
        val d = doc()
        d.setAt(0, 0xFF101010.toInt())
        d.setAt(1, 0xFF181818.toInt()) // dentro da tolerância 8
        d.setAt(2, 0xFF404040.toInt()) // fora da tolerância — fronteira
        val r = NativeEngine.floodFill(d, 0, 0, 0xFFFF0000.toInt(), 8, newScratch(d))
        assertTrue(r != null)
        assertEquals(0xFFFF0000.toInt(), d.getAt(0))
        assertEquals(0xFFFF0000.toInt(), d.getAt(1))
        assertEquals(0xFF404040.toInt(), d.getAt(2))
        assertEquals(2, r!!.deltaCount)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun countPainted(d: PixelDocument, color: Int): Int {
        var n = 0
        for (i in 0 until d.pixelCount) if (d.getAt(i) == color) n++
        return n
    }
}
