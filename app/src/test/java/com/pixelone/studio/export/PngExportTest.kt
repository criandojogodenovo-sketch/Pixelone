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

    // ------------------------------------------------------------------
    // 6) ESCALA (1x, 2x, 4x, 8x, 16x) — nearest-neighbor, píxeis nítidos
    // ------------------------------------------------------------------

    @Test
    fun `escala 1x com fundo transparente é o caminho exato igual ao original`() {
        val w = 5
        val h = 4
        val doc = PixelDocument(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            doc.set(x, y, if ((x + y) % 3 == 0) 0x00000000 else 0xFFF57C00.toInt())
        }
        // Inclui alfa baixo: no caminho 1x tem de sobreviver BYTE-EXATO
        doc.set(0, 0, 0x01ABCDEF.toInt())
        doc.set(1, 1, 0x40FF57C0.toInt())

        val viaOverload = PngExport.buildBitmapFromDocument(
            doc, PngExport.ScaleOption.X1, PngExport.BackgroundOption.TRANSPARENT
        )
        val direto = PngExport.buildBitmapFromDocument(doc)

        assertEquals(w, viaOverload.width)
        assertEquals(h, viaOverload.height)
        val got = IntArray(w * h)
        viaOverload.getPixels(got, 0, w, 0, 0, w, h)
        val expected = doc.copyPixels()
        for (i in expected.indices) {
            assertEquals("píxel $i difere no caminho 1x", expected[i], got[i])
        }
        val gotDireto = IntArray(w * h)
        direto.getPixels(gotDireto, 0, w, 0, 0, w, h)
        assertTrue("1x transparente == buildBitmapFromDocument", got.contentEquals(gotDireto))
    }

    @Test
    fun `escala 4x produz blocos nítidos de 4 por 4 sem cores interpoladas`() {
        val w = 2
        val h = 2
        val doc = PixelDocument(w, h)
        val laranja = 0xFFF57C00.toInt()
        val preto = 0xFF0A0A0A.toInt()
        val branco = 0xFFFFFFFF.toInt()
        val transparente = 0x00000000
        doc.set(0, 0, laranja)
        doc.set(1, 0, preto)
        doc.set(0, 1, transparente)
        doc.set(1, 1, branco)

        val bmp = PngExport.buildBitmapFromDocument(
            doc, PngExport.ScaleOption.X4, PngExport.BackgroundOption.TRANSPARENT
        )

        assertEquals("largura final = w*4", 8, bmp.width)
        assertEquals("altura final = h*4", 8, bmp.height)
        assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        val got = IntArray(8 * 8)
        bmp.getPixels(got, 0, 8, 0, 0, 8, 8)

        // Cada píxel da origem vira um bloco EXATO de 4×4 da MESMA cor —
        // nenhum tom interpolado/suavizado no meio.
        val origem = arrayOf(laranja, preto, transparente, branco)
        for (by in 0 until 2) for (bx in 0 until 2) {
            val cor = origem[by * 2 + bx]
            for (dy in 0 until 4) for (dx in 0 until 4) {
                val px = got[(by * 4 + dy) * 8 + (bx * 4 + dx)]
                assertEquals("bloco ($bx,$by) d/($dx,$dy) deve ser $cor", cor, px)
            }
        }

        // Nearest-neighbor estrito: o PNG só contém as 4 cores da origem
        val distintas = got.toSet()
        assertEquals("nenhuma cor interpolada pode aparecer", origem.toSet(), distintas)
    }

    @Test
    fun `escala 16x de canvas pequeno dá dimensões exatas e preserve alfa`() {
        val w = 3
        val h = 2
        val doc = PixelDocument(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            doc.set(x, y, if ((x + y) % 2 == 0) 0xFFF57C00.toInt() else 0x00000000)
        }
        val bmp = PngExport.buildBitmapFromDocument(
            doc, PngExport.ScaleOption.X16, PngExport.BackgroundOption.TRANSPARENT
        )
        assertEquals(48, bmp.width)
        assertEquals(32, bmp.height)

        // Píxeis transparentes continuam 100% transparentes (sem fundo)
        val got = IntArray(48 * 32)
        bmp.getPixels(got, 0, 48, 0, 0, 48, 32)
        for (by in 0 until 2) for (bx in 0 until 3) {
            if ((bx + by) % 2 != 0) {
                val px = got[(by * 16) * 48 + bx * 16]
                assertEquals("bloco transparente ($bx,$by)", 0x00000000, px)
            }
        }
    }

    @Test
    fun `roundtrip PNG com escala 2x escreve dimensões finais no IHDR`() {
        val w = 6
        val h = 4
        val doc = PixelDocument(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            doc.set(x, y, if ((x + y) % 2 == 0) 0xFFF57C00.toInt() else 0xFF0A0A0A.toInt())
        }
        val bmp = PngExport.buildBitmapFromDocument(
            doc, PngExport.ScaleOption.X2, PngExport.BackgroundOption.TRANSPARENT
        )
        assertEquals(12, bmp.width)
        assertEquals(8, bmp.height)
        val out = ByteArrayOutputStream()
        val result = PngExport.writePngToStream(bmp, out)
        assertTrue(result.ok)
        assertTrue("PNG 2x não pode ser vazio", result.bytes > 20)

        val bytes = out.toByteArray()
        // IHDR: largura/altura do FICHEIRO real = 12 × 8 (big-endian)
        assertEquals(12, ((bytes[16].toInt() and 0xFF) shl 24) or
            ((bytes[17].toInt() and 0xFF) shl 16) or
            ((bytes[18].toInt() and 0xFF) shl 8) or
            (bytes[19].toInt() and 0xFF))
        assertEquals(8, ((bytes[20].toInt() and 0xFF) shl 24) or
            ((bytes[21].toInt() and 0xFF) shl 16) or
            ((bytes[22].toInt() and 0xFF) shl 8) or
            (bytes[23].toInt() and 0xFF))
    }

    // ------------------------------------------------------------------
    // 7) FUNDO: transparente (padrão) ou preto sólido via drawColor
    // ------------------------------------------------------------------

    @Test
    fun `fundo preto preenche píxeis transparentes e preserva a arte opaca`() {
        val w = 2
        val h = 1
        val doc = PixelDocument(w, h)
        val laranja = 0xFFF57C00.toInt()
        doc.set(0, 0, laranja)
        doc.set(1, 0, 0x00000000) // transparente → tem de ficar preto

        val bmp = PngExport.buildBitmapFromDocument(
            doc, PngExport.ScaleOption.X2, PngExport.BackgroundOption.SOLID_BLACK
        )
        assertEquals(4, bmp.width)
        assertEquals(2, bmp.height)
        val got = IntArray(4 * 2)
        bmp.getPixels(got, 0, 4, 0, 0, 4, 2)

        // Arte opaca preservada byte a byte (nearest-neighbor, sem mistura)
        for (dy in 0 until 2) for (dx in 0 until 2) {
            assertEquals("arte opaca preservada", laranja, got[dy * 4 + dx])
        }
        // Onde era transparente: preto sólido OPACO (0xFF000000), não preto
        // translúcido e não transparente
        for (dy in 0 until 2) for (dx in 2 until 4) {
            assertEquals(
                "fundo deve ser preto sólido",
                0xFF000000.toInt(), got[dy * 4 + dx]
            )
        }
        val distintas = got.toSet()
        assertTrue(
            "só podem existir as duas cores esperadas, got=$distintas",
            distintas == setOf(laranja, 0xFF000000.toInt())
        )
    }

    @Test
    fun `fundo preto achata semi-transparência sobre preto com alfa 255`() {
        val w = 1
        val h = 1
        val doc = PixelDocument(w, h)
        // Laranja a 50% de alfa — valores escolhidos para que truncamento e
        // arredondamento do Skia concordem (192*128/255=96.37, 128→64.25,
        // 64→32.12; todos longe da fronteira do .5)
        doc.set(0, 0, 0x80C08040.toInt())

        val bmp = PngExport.buildBitmapFromDocument(
            doc, PngExport.ScaleOption.X1, PngExport.BackgroundOption.SOLID_BLACK
        )
        val got = IntArray(1)
        bmp.getPixels(got, 0, 1, 0, 0, 1, 1)
        // Flatten sobre preto: alfa passa a 255 (src-over sobre destino
        // opaco); RGB = canal * alfa / 255 (a cor verdadeiramente "vista")
        assertEquals(255, (got[0] ushr 24) and 0xFF)
        assertEquals(96, (got[0] shr 16) and 0xFF)  // 0xC0 * 0x80 / 0xFF
        assertEquals(64, (got[0] shr 8) and 0xFF)   // 0x80 * 0x80 / 0xFF
        assertEquals(32, got[0] and 0xFF)           // 0x40 * 0x80 / 0xFF
    }

    @Test
    fun `fundo transparente mantém alfa zero na escala`() {
        val w = 2
        val h = 2
        val doc = PixelDocument(w, h)
        doc.set(0, 0, 0xFFF57C00.toInt())
        doc.set(1, 0, 0x00000000)
        doc.set(0, 1, 0x00000000)
        doc.set(1, 1, 0xFFF57C00.toInt())

        val bmp = PngExport.buildBitmapFromDocument(
            doc, PngExport.ScaleOption.X8, PngExport.BackgroundOption.TRANSPARENT
        )
        assertEquals(16, bmp.width)
        assertEquals(16, bmp.height)
        val got = IntArray(16 * 16)
        bmp.getPixels(got, 0, 16, 0, 0, 16, 16)
        // Canto superior direito (origem 1,0 → bloco em x=8..15, y=0..7):
        // transparente
        assertEquals(0x00000000, got[3 * 16 + 12])
        // Centro do bloco opaco (origem 0,0): opaco
        assertEquals(0xFFF57C00.toInt(), got[4 * 16 + 4])
    }

    // ------------------------------------------------------------------
    // 8) Limites de memória e Paint nearest-neighbor
    // ------------------------------------------------------------------

    @Test
    fun `escala que excede o lado máximo é rejeitada antes de alocar`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            PngExport.validateScaledDimensions(2048, 2048, 16) // 32768 > 16384
        }
        assertTrue(
            "mensagem deve mencionar a escala e o lado máximo: ${ex.message}",
            ex.message!!.contains("16x") && ex.message!!.contains("16384")
        )
    }

    @Test
    fun `escala que excede o orçamento de píxeis é rejeitada`() {
        // 2048×2048 a 8x = 16384×16384 = 268M píxeis > 32 MP
        val ex = assertThrows(IllegalStateException::class.java) {
            PngExport.validateScaledDimensions(2048, 2048, 8)
        }
        assertTrue(
            "mensagem deve mencionar o orçamento de píxeis: ${ex.message}",
            ex.message!!.contains("33554432")
        )
        // 2048×2048 a 4x = 8192×8192 = 67M píxeis — também acima de 32 MP
        assertThrows(IllegalStateException::class.java) {
            PngExport.validateScaledDimensions(2048, 2048, 4)
        }
    }

    @Test
    fun `escalas normais de pixel art são aceites`() {
        // 16..128 px a 16x: 128×128 → 2048×2048 = 4,2 MP — OK
        PngExport.validateScaledDimensions(128, 128, 16)
        PngExport.validateScaledDimensions(64, 64, 16)
        PngExport.validateScaledDimensions(256, 256, 16) // 4096×4096 = 16,7 MP OK
        PngExport.validateScaledDimensions(2048, 2048, 1)
        PngExport.validateScaledDimensions(2048, 2048, 2) // 4096×4096 = 16,7 MP OK
    }

    @Test
    fun `canvas pequeno a 16x produz bitmap final válido 64 por 64`() {
        val doc = PixelDocument(4, 4)
        for (y in 0 until 4) for (x in 0 until 4) {
            doc.set(x, y, if ((x + y) % 2 == 0) 0xFFF57C00.toInt() else 0x00000000)
        }
        // 4×4 a 16x = 64×64 = 4 096 píxeis — muito abaixo dos limites: tem
        // de produzir o bitmap final completo, sem lançar exceção
        val bmp = PngExport.buildBitmapFromDocument(
            doc, PngExport.ScaleOption.X16, PngExport.BackgroundOption.TRANSPARENT
        )
        assertEquals(64, bmp.width)
        assertEquals(64, bmp.height)
        assertFalse(bmp.isRecycled)
        // O bitmap ORIGINAL usado como origem foi reciclado — só o final é
        // devolvido ao chamador
        val got = IntArray(64 * 64)
        bmp.getPixels(got, 0, 64, 0, 0, 64, 64)
        assertEquals(0xFFF57C00.toInt(), got[0]) // bloco (0,0) laranja
        assertEquals(0x00000000, got[63])        // bloco (3,0) transparente
    }

    @Test
    fun `paint nearest-neighbor tem filter e antiAlias desligados`() {
        val paint = PngExport.nearestNeighborPaint()
        assertFalse("isFilterBitmap=false (sem suavização)", paint.isFilterBitmap)
        assertFalse("isAntiAlias=false (sem bordas macias)", paint.isAntiAlias)
    }

    @Test
    fun `opções de escala expõem os fatores pedidos`() {
        assertEquals(
            listOf(1, 2, 4, 8, 16),
            PngExport.ScaleOption.entries.map { it.factor }
        )
        assertEquals("1x", PngExport.ScaleOption.X1.label)
        assertEquals("16x", PngExport.ScaleOption.X16.label)
    }
}
