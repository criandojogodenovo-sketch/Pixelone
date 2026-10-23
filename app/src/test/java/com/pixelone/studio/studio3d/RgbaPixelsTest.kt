package com.pixelone.studio.studio3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteOrder

/**
 * Testes das conversões RGBA (Fase 3): ordem exata dos canais para o OpenGL
 * (R,G,B,A) a partir de color ints ARGB, extração de SUB-RETÂNGULO para a
 * atualização parcial da GPU e preservação de alfa reto (nunca premultiplicado).
 */
class RgbaPixelsTest {

    @Test
    fun `argb converte para bytes RGBA na ordem correta`() {
        val buffer = RgbaPixels.argbToRgbaBuffer(intArrayOf(0xFF112233.toInt()))
        assertEquals(4, buffer.remaining())
        assertEquals(0x11, buffer.get().toInt() and 0xFF) // R
        assertEquals(0x22, buffer.get().toInt() and 0xFF) // G
        assertEquals(0x33, buffer.get().toInt() and 0xFF) // B
        assertEquals(0xFF, buffer.get().toInt() and 0xFF) // A
    }

    @Test
    fun `alfa baixo preserva canais SEM premultiplicação`() {
        // ARGB 0x40FF8000: alfa 0x40 — R deve continuar 0xFF (alfa reto).
        val buffer = RgbaPixels.argbToRgbaBuffer(intArrayOf(0x40FF8000))
        buffer.position(0)
        assertEquals(0xFF, buffer.get().toInt() and 0xFF)
        assertEquals(0x80, buffer.get().toInt() and 0xFF)
        assertEquals(0x00, buffer.get().toInt() and 0xFF)
        assertEquals(0x40, buffer.get().toInt() and 0xFF)
    }

    @Test
    fun `buffer direto reutilizado quando tem capacidade`() {
        val first = RgbaPixels.argbToRgbaBuffer(intArrayOf(0xFF000000.toInt()))
        val second = RgbaPixels.argbToRgbaBuffer(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), first
        )
        assertTrue(second === first || second.capacity() >= 8)
        assertEquals(8, second.remaining())
        second.position(0)
        assertEquals(0xFF, second.get().toInt() and 0xFF) // R do branco
    }

    @Test
    fun `extração de sub-retângulo para glTexSubImage2D é exata`() {
        // Documento 4×4: píxel (x,y) = 0xFF000000 or (x shl 4) or y.
        val width = 4
        val doc = IntArray(16) { i ->
            val x = i % width
            val y = i / width
            0xFF000000.toInt() or (x shl 4) or y
        }
        val rect = DirtyRect(1, 1, 2, 2) // 2×2 no centro
        val buffer = RgbaPixels.extractRectToRgba(width, { doc[it] }, rect)
        assertEquals(2 * 2 * 4, buffer.remaining())
        // Linha 1: (1,1), (2,1); linha 2: (1,2), (2,2) — lidos como ARGB.
        assertEquals(0xFF000011.toInt(), readArgb(buffer)) // (1,1)
        assertEquals(0xFF000021.toInt(), readArgb(buffer)) // (2,1)
        assertEquals(0xFF000012.toInt(), readArgb(buffer)) // (1,2)
        assertEquals(0xFF000022.toInt(), readArgb(buffer)) // (2,2)
    }

    @Test
    fun `cor única devolve bytes RGBA`() {
        val bytes = RgbaPixels.singleColorRgba(0x80FF0000.toInt())
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0x00, bytes[1].toInt() and 0xFF)
        assertEquals(0x00, bytes[2].toInt() and 0xFF)
        assertEquals(0x80, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun `extração repetida no MESMO buffer (scratch GL) é exata em cada chamada`() {
        // Contrato do novo caminho do updateTextureRegion: a extração corre na
        // thread GL reutilizando o buffer de subida — cada chamada tem de
        // começar limpa (position 0) e terminar com remaining() exato, mesmo
        // após uma extração ANTERIOR ter deixado o buffer "flipado" e com
        // dados diferentes.
        val width = 6
        val doc = IntArray(36) { i ->
            val x = i % width
            val y = i / width
            0xFF000000.toInt() or (x shl 4) or y
        }
        val scratch = java.nio.ByteBuffer.allocateDirect(24 * 4).order(ByteOrder.nativeOrder())
        // 1.ª extração: retângulo maior (3×2).
        val b1 = RgbaPixels.extractRectToRgba(width, { doc[it] }, DirtyRect(0, 0, 2, 1), scratch)
        assertEquals(3 * 2 * 4, b1.remaining())
        assertEquals(0xFF000000.toInt(), readArgb(b1)) // (0,0)
        // 2.ª extração NO MESMO buffer: retângulo menor (2×2) — clear() tem de
        // repor a posição e os dados têm de ser os da NOVA região.
        val b2 = RgbaPixels.extractRectToRgba(width, { doc[it] }, DirtyRect(3, 2, 4, 3), scratch)
        assertEquals(2 * 2 * 4, b2.remaining())
        assertTrue(b2 === scratch)
        assertEquals(0xFF000032.toInt(), readArgb(b2)) // (3,2)
        assertEquals(0xFF000042.toInt(), readArgb(b2)) // (4,2)
        assertEquals(0xFF000033.toInt(), readArgb(b2)) // (3,3)
        assertEquals(0xFF000043.toInt(), readArgb(b2)) // (4,3)
    }

    private fun readArgb(buffer: java.nio.ByteBuffer): Int {
        val r = buffer.get().toInt() and 0xFF
        val g = buffer.get().toInt() and 0xFF
        val b = buffer.get().toInt() and 0xFF
        val a = buffer.get().toInt() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
