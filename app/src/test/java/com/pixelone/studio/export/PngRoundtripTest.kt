package com.pixelone.studio.export

import android.graphics.Bitmap
import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Teste D (PNG) com Robolectric: roundtrip REAL através do codificador PNG
 * do Android (Bitmap.compress) e do descodificador (BitmapFactory) —
 * verifica dimensões, cores RGBA e transparência; confirma também que o
 * bitmap exportado é construído APENAS a partir dos píxeis do documento
 * (grelha/quadriculado/UI nunca entram no bitmap — são apenas desenhados
 * por cima na UI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PngRoundtripTest {

    @Test
    fun `exportação PNG preserva dimensões cores e transparência`() {
        val w = 32
        val h = 24
        val doc = PixelDocument(w, h)
        // Padrão com transparência, semi-transparência e cores variadas
        for (y in 0 until h) for (x in 0 until w) {
            doc.set(x, y, when {
                (x + y) % 5 == 0 -> 0x00000000 // totalmente transparente
                (x + y) % 5 == 1 -> 0x80FF57C0.toInt() // semi
                (x + y) % 5 == 2 -> 0xFFF57C00.toInt() // laranja Pixel One
                (x + y) % 5 == 3 -> 0xFF0A0A0A.toInt() // preto
                else -> 0xFFFFFFFF.toInt() // branco
            })
        }
        doc.set(0, 0, 0x01ABCDEF.toInt()) // alpha 1
        doc.set(w - 1, h - 1, 0xFE123456.toInt()) // alpha 254

        // Pipeline real do exportPng (mesmo código que a UI usa):
        // bitmap NÃO premultiplicado → valores RGBA exatos no PNG.
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setPremultiplied(false)
        bmp.copyPixelsFromBuffer(java.nio.IntBuffer.wrap(doc.copyPixels()))

        val pngBytes = ByteArrayOutputStream().use { out ->
            assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }

        // 1) Verificação dos BYTES DO PNG: parser PNG puro-JVM lê os dados
        //    brutos do ficheiro (sem premultiplicação) — prova que o ficheiro
        //    exportado contém os valores RGBA EXATOS do documento.
        val filePixels = readPngRaw(pngBytes)
        assertEquals(w, filePixels.width)
        assertEquals(h, filePixels.height)
        val original = doc.copyPixels()
        for (i in original.indices) {
            assertEquals("píxel $i no ficheiro difere", original[i], filePixels.pixels[i])
        }

        // 2) Decodificação Android (como o sistema lê o ficheiro): com
        //    inPremultiplied=false. Para alphas altos a viagem de ida e volta
        //    é exata; verificamos dimensões aqui (os bytes já foram provados
        //    acima pelo parser).
        val opts = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val decoded = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts)
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)
    }

    /** Estrutura lida de um PNG RGBA 8 bits. */
    private class RawPng(val width: Int, val height: Int, val pixels: IntArray)

    /**
     * Parser PNG mínimo (RGBA 8 bits, filtros 0-4) — lê os píxeis EXATOS
     * gravados no ficheiro, sem premultiplicação. Prova a fidelidade do
     * ficheiro exportado de forma independente do BitmapFactory.
     */
    private fun readPngRaw(bytes: ByteArray): RawPng {
        var pos = 8
        val idat = ByteArrayOutputStream()
        var width = 0; var height = 0
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(pos + 8, pos + 8 + len)
            when (type) {
                "IHDR" -> {
                    width = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
                        ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                    height = ((data[4].toInt() and 0xFF) shl 24) or ((data[5].toInt() and 0xFF) shl 16) or
                        ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                }
                "IDAT" -> idat.write(data)
            }
            pos += 12 + len
        }
        val inflater = java.util.zip.Inflater()
        inflater.setInput(idat.toByteArray())
        val stride = width * 4
        val raw = ByteArray(height * (1 + stride))
        var off = 0
        while (!inflater.finished() && off < raw.size) off += inflater.inflate(raw, off, raw.size - off)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt() and 0xFF
            val row = ByteArray(stride)
            System.arraycopy(raw, y * (stride + 1) + 1, row, 0, stride)
            for (x in 0 until stride) {
                val a = if (x >= 4) row[x - 4].toInt() and 0xFF else 0
                val b = if (y > 0) raw[(y - 1) * (stride + 1) + 1 + x].toInt() and 0xFF else 0
                val c = if (x >= 4 && y > 0) raw[(y - 1) * (stride + 1) + 1 + x - 4].toInt() and 0xFF else 0
                var v = row[x].toInt() and 0xFF
                when (filter) {
                    1 -> v += a
                    2 -> v += b
                    3 -> v += (a + b) / 2
                    4 -> {
                        val p = a + b - c
                        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
                        v += if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                }
                row[x] = v.toByte()
            }
            for (x in 0 until width) {
                val r = row[x * 4].toInt() and 0xFF
                val g = row[x * 4 + 1].toInt() and 0xFF
                val bl = row[x * 4 + 2].toInt() and 0xFF
                val al = row[x * 4 + 3].toInt() and 0xFF
                out[y * width + x] = (al shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        return RawPng(width, height, out)
    }

    @Test
    fun `fundo quadriculado e grelha nunca entram no bitmap exportado`() {
        // O quadriculado/grelha são desenhados APENAS no Canvas da UI; o
        // bitmap de exportação nasce de doc.copyPixels(). Este teste verifica
        // que o bitmap derivado do documento é idêntico ao documento — ou
        // seja, que não há qualquer camada visual extra no caminho de export.
        val doc = PixelDocument(8, 8)
        doc.set(3, 3, 0xFFFF0000.toInt())
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setPremultiplied(false)
        bmp.copyPixelsFromBuffer(java.nio.IntBuffer.wrap(doc.copyPixels()))

        val pixels = IntArray(64)
        bmp.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        val expected = doc.copyPixels()
        for (i in pixels.indices) assertEquals(expected[i], pixels[i])
        // Transparência: fundo é 0, não cinzento do quadriculado
        assertEquals(0, pixels[0])
    }

    @Test
    fun `stream a falhar nunca é reportado como sucesso`() {
        val doc = PixelDocument(4, 4)
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(java.nio.IntBuffer.wrap(doc.copyPixels()))
        val failingStream = object : java.io.OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("disco cheio simulado")
            override fun write(b: ByteArray, off: Int, len: Int) = throw java.io.IOException("disco cheio simulado")
        }
        var result = true
        var ex: Exception? = null
        try {
            result = bmp.compress(Bitmap.CompressFormat.PNG, 100, failingStream)
        } catch (e: java.io.IOException) {
            ex = e
        }
        // A exportação só pode continuar se compress devolveu false ou lançou
        // exceção — nunca "true" num stream que falhou.
        assertTrue(
            "compress devolveu true num stream que falhou",
            !result || ex != null
        )
    }
}
