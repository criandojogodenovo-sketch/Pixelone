package com.pixelone.studio.studio3d

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conversões de píxeis do 3D Texture Studio — Kotlin puro (testável em JVM).
 *
 * O [com.pixelone.studio.pixel.PixelDocument] guarda color ints ARGB
 * (0xAARRGGBB) num ByteBuffer direto little-endian (bytes B,G,R,A). O OpenGL
 * ES espera, com o formato GL_RGBA, bytes R,G,B,A — por ordem de canal.
 * Estas funções fazem essa conversão UMA VEZ por subida de textura, incluindo
 * a variante SUB-RETÂNGULO usada na atualização parcial da GPU (glTexSubImage2D):
 * apenas os bytes da região suja são escritos — nunca a textura inteira.
 *
 * PRESERVAÇÃO RGBA (requisito Fase 3): os valores NÃO são premultiplicados —
 * o OpenGL blending clássico (SRC_ALPHA/ONE_MINUS_SRC_ALPHA) e o glTF esperam
 * alfa reto; premultiplicar alteraria cores com alfa parcial.
 */
object RgbaPixels {

    /**
     * Converte color ints ARGB para bytes RGBA num ByteBuffer DIRETO (ordem
     * nativa — o GLES20.glTexImage2D aceita ByteBuffer direto com GL_RGBA).
     * Reutiliza [out] quando tem capacidade; cresce (novo buffer direto) quando
     * não tem — o chamador pode guardar a referência devolvida.
     */
    fun argbToRgbaBuffer(pixels: IntArray, out: ByteBuffer? = null): ByteBuffer {
        val needed = pixels.size * 4
        val buffer = (if (out != null && out.isDirect && out.capacity() >= needed) {
            out
        } else {
            ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
        })
        require(buffer.isDirect) { "O buffer RGBA de saída tem de ser direto" }
        require(buffer.capacity() >= needed) {
            "Buffer de saída pequeno: ${buffer.capacity()} < $needed"
        }
        buffer.clear()
        for (argb in pixels) {
            buffer.put(((argb shr 16) and 0xFF).toByte()) // R
            buffer.put(((argb shr 8) and 0xFF).toByte())  // G
            buffer.put((argb and 0xFF).toByte())          // B
            buffer.put(((argb shr 24) and 0xFF).toByte()) // A
        }
        buffer.flip()
        return buffer
    }

    /**
     * Extrai um SUB-RETÂNGULO do documento (color ints ARGB) para bytes RGBA —
     * o formato exato que glTexSubImage2D consome para atualizar APENAS a
     * região alterada. Reutiliza [out] quando possível.
     *
     * @param getPixel função de acesso ao píxel (document.getAt) — evita duplicar
     *        o buffer do documento; testável em JVM com uma lista simples.
     */
    fun extractRectToRgba(
        docWidth: Int,
        getPixel: (index: Int) -> Int,
        rect: DirtyRect,
        out: ByteBuffer? = null
    ): ByteBuffer {
        require(rect.x1 < docWidth) { "Rect fora do documento: ${rect.x1} >= $docWidth" }
        val pixels = rect.width * rect.height
        val buffer = (out ?: ByteBuffer.allocateDirect(pixels * 4))
            .order(ByteOrder.nativeOrder())
        require(buffer.capacity() >= pixels * 4) {
            "Buffer de subida pequeno: ${buffer.capacity()} < ${pixels * 4}"
        }
        buffer.clear()
        for (y in rect.y0..rect.y1) {
            val rowStart = y * docWidth
            for (x in rect.x0..rect.x1) {
                val argb = getPixel(rowStart + x)
                buffer.put(((argb shr 16) and 0xFF).toByte())
                buffer.put(((argb shr 8) and 0xFF).toByte())
                buffer.put((argb and 0xFF).toByte())
                buffer.put(((argb shr 24) and 0xFF).toByte())
            }
        }
        buffer.flip()
        return buffer
    }

    /**
     * Recria uma única cor em bytes RGBA (útil para testes e para fundos).
     */
    fun singleColorRgba(argb: Int): ByteArray = byteArrayOf(
        ((argb shr 16) and 0xFF).toByte(),
        ((argb shr 8) and 0xFF).toByte(),
        (argb and 0xFF).toByte(),
        ((argb shr 24) and 0xFF).toByte()
    )
}
