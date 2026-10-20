package com.pixelone.studio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pixelone.studio.pixel.NativeEngine
import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TESTES INSTRUMENTADOS (emulador/dispositivo real) — o motor nativo C++17
 * e o buffer partilhado Kotlin↔C++.
 *
 * Estes testes PROVAM com evidência executável:
 * 1. a biblioteca nativa carrega (caminho nativo ativo);
 * 2. Kotlin e C++ operam sobre o MESMO DirectByteBuffer (soma calculada no
 *    C++ == soma calculada pela vista Kotlin; escrita pelo C++ visível ao
 *    Kotlin);
 * 3. um traço completo com múltiplos segmentos é processado numa ÚNICA
 *    chamada JNI (sem JNI por píxel/segmento) com deltas e dirty rect
 *    corretos;
 * 4. borracha = transparência real; brocha; balde;
 * 5. erros não crasham.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentedTest {

    // --------------------------------------------------------------
    // 1. Biblioteca nativa disponível
    // --------------------------------------------------------------

    @Test
    fun bibliotecaNativaCarregada() {
        assertTrue(
            "libpixelone_engine deve carregar no emulador/dispositivo",
            NativeEngine.nativeAvailable
        )
    }

    // --------------------------------------------------------------
    // 2. Buffer partilhado Kotlin↔C++
    // --------------------------------------------------------------

    @Test
    fun bufferEhDiretoComCapacidadeExata() {
        val doc = PixelDocument(64, 64)
        assertTrue(doc.sharedBuffer.isDirect)
        assertEquals(64L * 64 * 4, doc.sharedBuffer.capacity().toLong())
    }

    @Test
    fun kotlinECppLeemAMesmaMemoria() {
        val doc = PixelDocument(32, 32)
        // Escreve um padrão pela vista Kotlin (com alpha alto — prova de que
        // a comparação é sobre os MESMOS bytes, não sobre sinais iguais)
        for (i in 0 until doc.pixelCount) {
            doc.setAt(i, 0xFF000000.toInt() or (i % 251))
        }
        // O C++ soma os MESMOS dados pelo endereço direto:
        val somaNativa = NativeEngine.probeBufferSum(doc, doc.pixelCount)
        assertNotNull("probe nativo deve funcionar (lib carregada)", somaNativa)
        // A soma calculada pelo Kotlin coincide ⇔ mesma memória.
        // (unsigned em ambos os lados: o C++ lê uint32_t)
        var somaKotlin = 0L
        for (i in 0 until doc.pixelCount) somaKotlin += doc.getAt(i).toLong() and 0xFFFFFFFFL
        assertEquals("C++ e Kotlin devem ler o MESMO buffer", somaKotlin, somaNativa!!.toLong())
    }

    @Test
    fun escritaNativaEhVisivelPelaVistaKotlin() {
        val doc = PixelDocument(16, 16)
        val idx = 5 * 16 + 5
        val valor = 0xFFCAFEBABE.toInt()
        assertTrue(
            "escrita pelo C++ deve ser visível ao Kotlin",
            NativeEngine.nativeWriteProbe(doc, idx, valor)
        )
        assertEquals(valor, doc.getAt(idx))
    }

    // --------------------------------------------------------------
    // 3. Traço completo em UMA chamada JNI
    // --------------------------------------------------------------

    @Test
    fun tracoMultiSegmentoProcessaNumaUnicaChamadaNativa() {
        val doc = PixelDocument(64, 64)
        val scratch = NativeEngine.Scratch(doc.pixelCount)
        // Gesto em "N": (4,40)→(4,8)→(28,40)→(28,8), brocha 1
        val points = intArrayOf(4, 40, 4, 8, 28, 40, 28, 8)
        val result = NativeEngine.strokeBatch(
            doc, points, 4, 0xFFFF0000.toInt(), 1, scratch
        )
        assertNotNull(result)
        assertTrue("deve ter corrido no caminho NATIVO", result!!.native)
        assertTrue(result.deltaCount in 1..doc.pixelCount)
        // Continuidade: para cada y da coluna x=4 entre 8..40 existe píxel
        for (y in 8..40) {
            assertEquals("falha na linha vertical x=4, y=$y", 0xFFFF0000.toInt(), doc.get(4, y))
        }
        for (y in 8..40) {
            assertEquals("falha na linha vertical x=28, y=$y", 0xFFFF0000.toInt(), doc.get(28, y))
        }
        // Dirty rect contém todos os deltas
        val w = doc.width
        for (i in 0 until result.deltaCount) {
            val idx = scratch.indices[i]
            val x = idx % w; val y = idx / w
            assertTrue(x in result.dirtyX0..result.dirtyX1)
            assertTrue(y in result.dirtyY0..result.dirtyY1)
        }
        assertTrue(result.durationMicros >= 0)
    }

    @Test
    fun deltasNativosCorrespondemAosPixelsAlterados() {
        val doc = PixelDocument(32, 32)
        doc.setAt(2 * 32 + 2, 0xFF111111.toInt())
        val scratch = NativeEngine.Scratch(doc.pixelCount)
        // Lote de 3 pontos: (2,2) → (3,2) → (4,2)
        val result = NativeEngine.strokeBatch(
            doc, intArrayOf(2, 2, 3, 2, 4, 2), 3, 0xFF222222.toInt(), 1, scratch
        )
        assertEquals(3, result!!.deltaCount)
        val byIndex = mutableMapOf<Int, Pair<Int, Int>>()
        for (i in 0 until result.deltaCount) {
            byIndex[scratch.indices[i]] = scratch.before[i] to scratch.after[i]
        }
        assertEquals(0xFF111111.toInt(), byIndex[2 * 32 + 2]!!.first)
        assertEquals(0xFF222222.toInt(), byIndex[2 * 32 + 2]!!.second)
        // (3,2) e (4,2) ⇒ índice linear y*32+x = 2*32+3 e 2*32+4
        assertEquals(0, byIndex[2 * 32 + 3]!!.first)
        assertEquals(0xFF222222.toInt(), byIndex[2 * 32 + 4]!!.second)
    }

    @Test
    fun borrachaNativaEscreveTransparenciaReal() {
        val doc = PixelDocument(16, 16)
        for (i in 0 until doc.pixelCount) doc.setAt(i, 0xFF333333.toInt())
        val result = NativeEngine.strokeBatch(
            doc, intArrayOf(0, 0, 15, 15), 2, 0x00000000, 1, NativeEngine.Scratch(doc.pixelCount)
        )
        assertTrue(result!!.changedSomething)
        for (i in 0 until 16) assertEquals(0, doc.getAt(i * 16 + i))
    }

    @Test
    fun brochaNativaTamanho4() {
        val doc = PixelDocument(32, 32)
        NativeEngine.strokeBatch(
            doc, intArrayOf(16, 16), 1, 0xFF00FF00.toInt(), 4, NativeEngine.Scratch(doc.pixelCount)
        )
        // Carimbo 4x4 centrado (metade 1): x,y de 15..18
        var painted = 0
        for (y in 15..18) for (x in 15..18) {
            assertEquals(0xFF00FF00.toInt(), doc.get(x, y)); painted++
        }
        assertEquals(16, painted)
        assertEquals(0, doc.get(14, 14))
    }

    // --------------------------------------------------------------
    // 4. Balde nativo
    // --------------------------------------------------------------

    @Test
    fun baldeNativoPreencheRegiaoContigua() {
        val doc = PixelDocument(32, 32)
        for (y in 0 until 32) doc.set(16, y, 0xFF000000.toInt()) // muralha
        val result = NativeEngine.floodFill(
            doc, 2, 2, 0xFFFF0000.toInt(), 0, NativeEngine.Scratch(doc.pixelCount)
        )
        assertTrue(result!!.native)
        assertEquals(16 * 32, result.deltaCount)
        assertEquals(0, result.dirtyX0); assertEquals(15, result.dirtyX1)
        for (y in 0 until 32) {
            assertEquals(0xFFFF0000.toInt(), doc.get(0, y))
            assertEquals(0xFF000000.toInt(), doc.get(16, y))
        }
    }

    // --------------------------------------------------------------
    // 5. Erros sem crash
    // --------------------------------------------------------------

    @Test
    fun tracoVazioLancaExcecaoControlada() {
        val doc = PixelDocument(8, 8)
        var lanhou = false
        try {
            NativeEngine.strokeBatch(
                doc, intArrayOf(), 0, 0xFF000000.toInt(), 1, NativeEngine.Scratch(doc.pixelCount)
            )
        } catch (e: IllegalArgumentException) {
            lanhou = true
        }
        assertTrue("pointCount=0 deve ser rejeitado com exceção controlada", lanhou)
    }

    @Test
    fun pontosForaDosLimitesNaoCrashamNemSujaODocumento() {
        val doc = PixelDocument(8, 8)
        // Segmento que nunca entra nos limites do documento:
        val result = NativeEngine.strokeBatch(
            doc, intArrayOf(-10, -10, -10, 100), 2, 0xFF000000.toInt(), 1,
            NativeEngine.Scratch(doc.pixelCount)
        )
        assertNull(result)
        for (i in 0 until doc.pixelCount) assertEquals(0, doc.getAt(i))
    }

    @Test
    fun fillEmPontoForaNaoCrasha() {
        val doc = PixelDocument(8, 8)
        val result = NativeEngine.floodFill(
            doc, -1, 3, 0xFFFF0000.toInt(), 0, NativeEngine.Scratch(doc.pixelCount)
        )
        assertNull(result)
    }

    @Test
    fun fillNaMesmaCorNaoProduzDeltas() {
        val doc = PixelDocument(8, 8)
        doc.setAt(0, 0xFF101010.toInt())
        val result = NativeEngine.floodFill(
            doc, 0, 0, 0xFF101010.toInt(), 0, NativeEngine.Scratch(doc.pixelCount)
        )
        assertNull(result)
        assertFalse(result != null && result.changedSomething)
    }
}
