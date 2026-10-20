package com.pixelone.studio.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Testes do estado do EditorViewModel (Robolectric — Problema 2):
 * - criar um projeto torna o documento/bitmap NÃO NULOS de imediato
 *   (estado observável pelo Compose — o canvas aparece sem 2.º toque);
 * - o ajuste à vista acontece quando o viewport recebe dimensões válidas
 *   (pendingFit), mesmo que o documento seja criado ANTES do layout;
 * - os limites de zoom são respeitados;
 * - cor primária/secundária com troca consistente;
 * - preview do traço NÃO modifica píxeis do documento.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorViewModelStateTest {

    private fun vm(): EditorViewModel =
        EditorViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `novo projeto instala documento e bitmap imediatamente`() {
        val v = vm()
        assertNull(v.document)
        assertNull(v.imageBitmap)

        v.newProject("teste", 32, 32) {}

        assertNotNull("document deve ser estado não nulo logo após criar", v.document)
        assertEquals(32, v.document!!.width)
        assertEquals(32, v.document!!.height)
        assertNotNull("imageBitmap deve existir de imediato (canvas visível)", v.imageBitmap)
        assertTrue(v.hasUnsavedChanges.value)
        // O buffer direto é a fonte de verdade e valida:
        assertNull(v.document!!.bufferValidationProblem())
    }

    @Test
    fun `fit-to-screen aplica quando o viewport chega DEPOIS do documento`() {
        val v = vm()
        v.newProject("teste", 64, 64) {}
        // Documento criado ANTES de o layout existir (viewport ainda a 0):
        v.updateViewport(0f, 0f)
        // Zoom/offset não devem ter valores absurdos com viewport inválido.
        v.updateViewport(800f, 600f) // layout chega → pendingFit aplica

        val s = v.scale.value
        val expected = minOf((800f - 48f) / 64, (600f - 48f) / 64)
        assertEquals("escala deve ajustar a imagem ao espaço útil", expected, s, 0.001f)
        // Centrado no viewport:
        assertEquals((800f - 64f * s) / 2f, v.offset.value.x, 0.5f)
        assertEquals((600f - 64f * s) / 2f, v.offset.value.y, 0.5f)
    }

    @Test
    fun `documento aberto por cima de viewport existente re-ajusta à vista`() {
        val v = vm()
        v.newProject("a", 16, 16) {}
        v.updateViewport(800f, 600f)
        val s16 = v.scale.value
        // Abrir um documento maior deve re-ajustar (pendingFit depois de installDocument)
        v.newProject("b", 128, 128) {}
        val s128 = v.scale.value
        assertTrue("documento maior deve ter escala menor (fit)", s128 < s16)
    }

    @Test
    fun `limites de zoom respeitados (aplicarZoom coerced)`() {
        val v = vm()
        v.newProject("t", 32, 32) {}
        v.updateViewport(400f, 400f)
        repeat(30) { v.applyZoom(2f, androidx.compose.ui.geometry.Offset(200f, 200f)) }
        assertEquals(EditorViewModel.MAX_SCALE, v.scale.value, 0.0001f)
        repeat(40) { v.applyZoom(0.5f, androidx.compose.ui.geometry.Offset(200f, 200f)) }
        assertEquals(EditorViewModel.MIN_SCALE, v.scale.value, 0.0001f)
    }

    @Test
    fun `cor primária e secundária trocam de forma consistente`() {
        val v = vm()
        val primaria = v.activeColor.intValue
        val secundaria = v.secondaryColor.intValue
        assertTrue(primaria != secundaria)

        v.swapColors()
        assertEquals(secundaria, v.activeColor.intValue)
        assertEquals(primaria, v.secondaryColor.intValue)

        v.swapColors()
        assertEquals(primaria, v.activeColor.intValue)
        assertEquals(secundaria, v.secondaryColor.intValue)
    }

    @Test
    fun `toque longo na paleta define a cor secundária`() {
        val v = vm()
        val nova = 0xFF00FF00.toInt()
        v.setSecondaryColor(nova)
        assertEquals(nova, v.secondaryColor.intValue)
        // A primária não muda:
        assertEquals(EditorViewModel.DEFAULT_COLOR, v.activeColor.intValue)
    }

    @Test
    fun `preview do traço não modifica píxeis do documento`() {
        val v = vm()
        v.newProject("t", 16, 16) {}
        val antes = v.document!!.copyPixels()

        v.beginStrokePreview(2, 2)
        v.continueStrokePreview(10, 10)
        v.continueStrokePreview(12, 2)

        assertNotNull(v.preview)
        assertTrue(v.preview!!.stampPoints.isNotEmpty())
        // NENHUM píxel mudou durante o preview:
        assertEquals(antes.toList(), v.document!!.copyPixels().toList())

        v.cancelStroke()
        assertNull(v.preview)
        assertEquals(antes.toList(), v.document!!.copyPixels().toList())
    }

    @Test
    fun `preview com interpolação cobre a diagonal sem falhas`() {
        val v = vm()
        v.newProject("t", 16, 16) {}
        v.beginStrokePreview(0, 0)
        v.continueStrokePreview(15, 15)
        // Bresenham de (0,0) a (15,15): 16 pontos, um por y
        val ys = v.preview!!.stampPoints.map { it.y }.toSet()
        assertEquals((0..15).toSet(), ys)
        v.cancelStroke()
    }

    @Test
    fun `beginStrokePreview fora dos limites é ignorado`() {
        val v = vm()
        v.newProject("t", 8, 8) {}
        v.beginStrokePreview(-1, 0)
        assertNull("ponto fora dos limites não deve iniciar traço", v.preview)
    }
}
