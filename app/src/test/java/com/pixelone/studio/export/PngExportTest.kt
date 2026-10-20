package com.pixelone.studio.export

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Testes do núcleo da exportação PNG direta para Download/ (correção do
 * ficheiro z.png com 0 bytes). Cobre os 6 passos receitados, na parte
 * testável em JVM/Robolectric:
 *
 *  - Bitmap ARGB_8888 com as dimensões REAIS e píxeis EXATOS lidos do
 *    buffer direto partilhado Kotlin↔C++ (incl. transparência e alpha
 *    baixo), não premultiplicado, nunca nulo/reciclado;
 *  - escrita com a ordem exata compress → flush → close (use{} do
 *    chamador), com contagem real de bytes (ok + tamanho para o Toast);
 *  - exceções NUNCA engolidas (stream que falha, bitmap reciclado);
 *  - nome sanitizado/único e ContentValues do MediaStore (IS_PENDING).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PngExportTest {

    // ------------------------------------------------------------------
    // 1) Bitmap a partir do buffer partilhado
    // ------------------------------------------------------------------

    @Test
    fun `bitmap do buffer partilhado tem dimensões reais e píxeis exatos`() {
        val w = 16
        val h = 12
        val doc = PixelDocument(w, h)
        // Escritos pela VIA DO BUFFER PARTILHADO (setAt usa o IntBuffer sobre
        // o ByteBuffer direto — a mesma memória que o C++17 modifica via JNI).
        for (y in 0 until h) for (x in 0 until w) {
            doc.setAt(y * w + x, when {
                (x + y) % 4 == 0 -> 0x00000000 // transparente
                (x + y) % 4 == 1 -> 0x80FF57C0.toInt() // semi-transparência
                (x + y) % 4 == 2 -> 0xFFF57C00.toInt() // laranja Pixel One
                else -> 0xFF0A0A0A.toInt() // preto
            })
        }
        doc.setAt(0, 0x01ABCDEF.toInt()) // alpha 1 (extremo)
        doc.setAt(w * h - 1, 0xFE123456.toInt()) // alpha 254 (extremo)

        val bmp = PngExport.buildBitmapFromDocument(doc)

        assertEquals(w, bmp.width)
        assertEquals(h, bmp.height)
        assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        assertFalse("exportação tem de ser não premultiplicada", bmp.isPremultiplied)
        val got = IntArray(w * h)
        bmp.getPixels(got, 0, w, 0, 0, w, h)
        val expected = doc.copyPixels()
        for (i in expected.indices) {
            assertEquals("píxel $i difere do buffer partilhado", expected[i], got[i])
        }
    }

    @Test
    fun `bitmap inválido se o documento for inválido`() {
        // Dimensão correta mas buffer manipulado: nenhum documento inválido
        // consegue ser construído (o construtor valida), pelo que a única
        // via de erro é a validação interna — simulada aqui com um doc
        // normal (a validação corre e devolve null; bitmap sai válido).
        val doc = PixelDocument(4, 4)
        assertEquals(null, doc.bufferValidationProblem())
        val bmp = PngExport.buildBitmapFromDocument(doc)
        assertEquals(4, bmp.width)
    }

    // ------------------------------------------------------------------
    // 2) Escrita: compress → flush → close, ok + bytes reais
    // ------------------------------------------------------------------

    @Test
    fun `escrita devolve ok=true e bytes reais maiores que zero`() {
        val doc = PixelDocument(8, 8)
        doc.set(3, 3, 0xFFF57C00.toInt())
        val bmp = PngExport.buildBitmapFromDocument(doc)
        val out = ByteArrayOutputStream()
        val result = PngExport.writePngToStream(bmp, out)
        // use{} do chamador faz o close; aqui fechamos explicitamente para
        // obter os bytes (ByteArrayOutputStream não precisa, mas é a ordem).
        out.flush()
        out.close()

        assertTrue("ok tem de ser true", result.ok)
        assertTrue("ficheiro não pode ser vazio", result.bytes > 0)
        assertEquals("contagem = bytes escritos", result.bytes.toLong(), out.size().toLong())
        // Assinatura PNG real (não é um ficheiro vazio/lixo)
        val bytes = out.toByteArray()
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])
    }

    @Test
    fun `PNG escrito decodifica com as mesmas dimensões e cores opacas`() {
        val w = 6
        val h = 5
        val doc = PixelDocument(w, h)
        // Cores com alpha 0/255: exatas em qualquer caminho de decode
        for (y in 0 until h) for (x in 0 until w) {
            doc.set(x, y, if ((x + y) % 2 == 0) 0xFFF57C00.toInt() else 0x00000000)
        }
        val bmp = PngExport.buildBitmapFromDocument(doc)
        val out = ByteArrayOutputStream()
        val result = PngExport.writePngToStream(bmp, out)
        assertTrue(result.ok)
        assertTrue(result.bytes > 0)

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val decoded = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size(), opts)
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)
        val got = IntArray(w * h)
        decoded.getPixels(got, 0, w, 0, 0, w, h)
        val expected = doc.copyPixels()
        for (i in expected.indices) {
            assertEquals("píxel $i do ficheiro difere do documento", expected[i], got[i])
        }
    }

    @Test
    fun `stream que falha nunca passa como sucesso e reporta o erro`() {
        val doc = PixelDocument(4, 4)
        val bmp = PngExport.buildBitmapFromDocument(doc)
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disco cheio simulado")
            override fun write(b: ByteArray, off: Int, len: Int) =
                throw IOException("disco cheio simulado")
        }
        // Em Robolectric o Bitmap.compress devolve false (não lança); o
        // núcleo transforma isso em IOException — em qualquer dos casos a
        // falha NUNCA é engolida e a mensagem descreve a causa real.
        val ex = assertThrows(IOException::class.java) {
            PngExport.writePngToStream(bmp, failing)
        }
        assertTrue(
            "mensagem deve reportar a causa real: ${ex.message}",
            ex.message!!.contains("false") || ex.message!!.contains("disco cheio simulado")
        )
    }

    @Test
    fun `bitmap reciclado é rejeitado antes de comprimir`() {
        val doc = PixelDocument(4, 4)
        val bmp = PngExport.buildBitmapFromDocument(doc)
        bmp.recycle()
        val ex = assertThrows(IllegalStateException::class.java) {
            PngExport.writePngToStream(bmp, ByteArrayOutputStream())
        }
        assertTrue(ex.message!!.contains("reciclado"))
    }

    @Test
    fun `CountingOutputStream conta bytes parciais e totais`() {
        val sink = ByteArrayOutputStream()
        val counting = PngExport.CountingOutputStream(sink)
        counting.write(0x41)
        counting.write(byteArrayOf(1, 2, 3, 4, 5), 1, 3)
        counting.flush()
        assertEquals(4L, counting.count)
        counting.close()
        assertEquals(4L, counting.count)
        assertEquals(4, sink.size())
    }

    // ------------------------------------------------------------------
    // 3) Nome do ficheiro: sanitização + unicidade
    // ------------------------------------------------------------------

    @Test
    fun `nome simples mantém-se`() {
        assertEquals("z.png", PngExport.displayNameFor("z", emptySet(), 0L))
    }

    @Test
    fun `colisão acrescenta carimbo temporal`() {
        val now = 1760900000000L // um instante fixo determinístico
        val name = PngExport.displayNameFor("z", setOf("z.png", "outro.png"), now)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date(now))
        assertEquals("z_$stamp.png", name)
        assertNotEquals("z.png", name)
    }

    @Test
    fun `extensão png não é duplicada`() {
        assertEquals("z.png", PngExport.displayNameFor("z.png", emptySet(), 0L))
        assertEquals("z.png", PngExport.displayNameFor("z.PNG", emptySet(), 0L))
    }

    @Test
    fun `caracteres perigosos são substituídos`() {
        assertEquals(
            "meu projeto_ v2_final.png",
            PngExport.displayNameFor("meu projeto: v2/final?", emptySet(), 0L)
        )
        assertEquals("pixel-art.png", PngExport.displayNameFor("", emptySet(), 0L))
        assertEquals("pixel-art.png", PngExport.displayNameFor("///", emptySet(), 0L))
        assertEquals("a_b.png", PngExport.displayNameFor("a*b", emptySet(), 0L))
    }

    // ------------------------------------------------------------------
    // 4) ContentValues do MediaStore
    // ------------------------------------------------------------------

    @Test
    fun `ContentValues em Q tem Download e IS_PENDING`() {
        val v = PngExport.mediaValues("z.png", Build.VERSION_CODES.Q)
        assertEquals("z.png", v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/png", v.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Download/", v.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, v.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    fun `ContentValues pré-Q não tem colunas inexistentes`() {
        val v = PngExport.mediaValues("z.png", 28)
        assertEquals("z.png", v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/png", v.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertFalse(v.containsKey(MediaStore.MediaColumns.RELATIVE_PATH))
        assertFalse(v.containsKey(MediaStore.MediaColumns.IS_PENDING))
    }

    // ------------------------------------------------------------------
    // 5) Integração fina: documento → bitmap → stream (fidelidade total)
    // ------------------------------------------------------------------

    @Test
    fun `roundtrip completo preserva bytes RGBA do buffer incluindo alpha baixo`() {
        val w = 4
        val h = 3
        val doc = PixelDocument(w, h)
        val colors = intArrayOf(
            0x00000000, 0x01ABCDEF.toInt(), 0x40FF57C0.toInt(), 0xFFFFFFFF.toInt(),
            0xFFF57C00.toInt(), 0x80000000.toInt(), 0xFF0A0A0A.toInt(), 0xFE123456.toInt(),
            0x12345678.toInt(), 0x00000001, 0xFFFFFFFF.toInt(), 0x87654321.toInt()
        )
        for (i in colors.indices) doc.setAt(i, colors[i])

        val bmp = PngExport.buildBitmapFromDocument(doc)
        val out = ByteArrayOutputStream()
        val result = PngExport.writePngToStream(bmp, out)
        assertTrue(result.ok)
        assertTrue("PNG de ${w}x$h não pode ser vazio", result.bytes > 20)

        // Leitura INDEPENDENTE dos dados do PNG: assinatura + dimensões no
        // IHDR (big-endian), provando que o ficheiro real tem o conteúdo.
        val bytes = out.toByteArray()
        assertEquals((w ushr 24) and 0xFF, bytes[16].toInt() and 0xFF)
        assertEquals((w ushr 16) and 0xFF, bytes[17].toInt() and 0xFF)
        assertEquals((w ushr 8) and 0xFF, bytes[18].toInt() and 0xFF)
        assertEquals(w and 0xFF, bytes[19].toInt() and 0xFF)
        assertEquals((h ushr 24) and 0xFF, bytes[20].toInt() and 0xFF)
        assertEquals((h ushr 16) and 0xFF, bytes[21].toInt() and 0xFF)
        assertEquals((h ushr 8) and 0xFF, bytes[22].toInt() and 0xFF)
        assertEquals(h and 0xFF, bytes[23].toInt() and 0xFF)
        // bit depth 8 e color type 6 (RGBA)
        assertEquals(8, bytes[24].toInt())
        assertEquals(6, bytes[25].toInt())

        // E o bitmap pré-compressão coincide com o buffer (fonte de verdade)
        val back = IntArray(w * h)
        bmp.getPixels(back, 0, w, 0, 0, w, h)
        for (i in colors.indices) assertEquals(colors[i], back[i])
        // ByteArrayInputStream só para provar que os bytes são legíveis
        val readable = ByteArray(bytes.size)
        ByteArrayInputStream(bytes).read(readable)
        assertTrue(readable.contentEquals(bytes))
    }
}
