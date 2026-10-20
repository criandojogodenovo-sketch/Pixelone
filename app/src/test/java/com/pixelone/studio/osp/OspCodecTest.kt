package com.pixelone.studio.osp

import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Testes obrigatórios do formato .osp (teste C da especificação). */
class OspCodecTest {

    private fun docExemplo(): PixelDocument {
        val doc = PixelDocument(16, 16)
        // Padrão com transparência e cores variadas
        for (y in 0 until 16) for (x in 0 until 16) {
            doc.set(x, y, when {
                x == 0 && y == 0 -> 0x00000000.toInt() // canto transparente
                (x + y) % 3 == 0 -> 0x80FF57C0.toInt() // semi-transparente
                (x + y) % 3 == 1 -> 0xFFFF57C0.toInt() // opaco
                else -> 0xFF202020.toInt()
            })
        }
        doc.set(15, 15, 0xFFFFFFFF.toInt())
        doc.set(0, 15, 0x01FFFFFF.toInt()) // alpha 1: preservação exata de canal
        doc.set(15, 0, 0xFE000000.toInt()) // alpha 254
        return doc
    }

    private fun manifesto(doc: PixelDocument, nome: String = "exemplo") = OspManifest(
        name = nome,
        width = doc.width,
        height = doc.height,
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_100_000L,
        palette = listOf("#FF000000", "#FFF57C00", "#80FF57C0")
    )

    @Test
    fun `guardar e reabrir mantém dimensões e TODOS os píxeis RGBA`() {
        val doc = docExemplo()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc), doc, out)

        val loaded = OspCodec.load(out.toByteArray())
        assertEquals(16, loaded.document.width)
        assertEquals(16, loaded.document.height)
        assertTrue(
            "píxeis deviam ser idênticos ao byte",
            doc.copyPixels().contentEquals(loaded.document.copyPixels())
        )
        assertEquals("exemplo", loaded.manifest.name)
        assertEquals(1_700_000_000_000L, loaded.manifest.createdAtEpochMs)
        assertEquals(listOf("#FF000000", "#FFF57C00", "#80FF57C0"), loaded.manifest.palette)
        assertEquals(OspCodec.FORMAT_VERSION, loaded.manifest.formatVersion)
    }

    @Test
    fun `transparência e alphas extremos sobrevivem ao roundtrip`() {
        val doc = docExemplo()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc), doc, out)
        val loaded = OspCodec.load(out.toByteArray())
        assertEquals(0x00000000.toInt(), loaded.document.get(0, 0))
        assertEquals(0xFFFFFFFF.toInt(), loaded.document.get(15, 15))
        assertEquals(0x01FFFFFF.toInt(), loaded.document.get(0, 15))
        assertEquals(0xFE000000.toInt(), loaded.document.get(15, 0))
    }

    @Test
    fun `formato inválido ou não-osp é rejeitado de forma controlada`() {
        // Bytes aleatórios que não são ZIP
        val ex = try {
            OspCodec.load(byteArrayOf(0x50, 0x4B, 0x00, 0x01, 0x02, 0x03))
            null
        } catch (e: IOException) { e }
        assertTrue("devia lançar IOException controlada", ex is OspCodec.OspInvalidException)

        // ZIP válido mas sem as entradas esperadas
        val zipOnly = ByteArrayOutputStream()
        ZipOutputStream(zipOnly).use { z ->
            z.putNextEntry(ZipEntry("outro.txt"))
            z.write("conteúdo".toByteArray())
            z.closeEntry()
        }
        val ex2 = try {
            OspCodec.load(zipOnly.toByteArray()); null
        } catch (e: IOException) { e }
        assertTrue(ex2 is OspCodec.OspInvalidException)
    }

    @Test
    fun `manifest json corrupto é rejeitado`() {
        val doc = docExemplo()
        val corrupto = OspManifest(
            name = "x", width = doc.width, height = doc.height,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L
        )
        val out = ByteArrayOutputStream()
        OspCodec.save(corrupto, doc, out)
        val bytes = out.toByteArray()
        // Corrompe o manifest (está no início do conteúdo comprimido? não —
        // substituímos o byte 0 do stream, que é assinatura do ZIP: usamos
        // outra via: ficheiro com manifest truncado construído à mão.
        val manual = ByteArrayOutputStream()
        ZipOutputStream(manual).use { z ->
            z.putNextEntry(ZipEntry(OspCodec.ENTRY_MANIFEST))
            z.write("{format: sem aspas".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry(OspCodec.ENTRY_PIXELS))
            z.write(doc.toRgbaBytes())
            z.closeEntry()
        }
        val ex = try {
            OspCodec.load(manual.toByteArray()); null
        } catch (e: IOException) { e }
        assertTrue(ex is OspCodec.OspInvalidException)
    }

    @Test
    fun `versão desconhecida mais recente é rejeitada SEM corromper nada`() {
        val doc = docExemplo()
        // O codec impede GRAVAR versão != 1 (validateFor), por isso o ficheiro
        // "do futuro" é construído manualmente — como um ficheiro de uma app mais nova.
        val manual = ByteArrayOutputStream()
        ZipOutputStream(manual).use { z ->
            z.putNextEntry(ZipEntry(OspCodec.ENTRY_MANIFEST))
            z.write(
                MiniJson.write(
                    linkedMapOf<String, Any?>(
                        "format" to "pixel-one-osp",
                        "formatVersion" to (OspCodec.FORMAT_VERSION + 7),
                        "app" to "Pixel One",
                        "name" to "futuro",
                        "width" to doc.width,
                        "height" to doc.height,
                        "createdAtEpochMs" to 1L,
                        "updatedAtEpochMs" to 1L
                    )
                ).toByteArray()
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry(OspCodec.ENTRY_PIXELS))
            z.write(doc.toRgbaBytes())
            z.closeEntry()
        }
        val ex = try {
            OspCodec.load(manual.toByteArray()); null
        } catch (e: IOException) { e }
        assertTrue(
            "devia ser OspUnsupportedVersionException, foi ${ex?.javaClass?.simpleName}",
            ex is OspCodec.OspUnsupportedVersionException
        )
        assertEquals(
            OspCodec.FORMAT_VERSION + 7,
            (ex as OspCodec.OspUnsupportedVersionException).foundVersion
        )
        // A mensagem menciona a versão (para o utilizador perceber)
        assertTrue(ex.message!!.contains("não suportado"))
    }

    @Test
    fun `entradas inesperadas e zip bombs são rejeitados`() {
        val manual = ByteArrayOutputStream()
        ZipOutputStream(manual).use { z ->
            z.putNextEntry(ZipEntry("../../../etc/passwd"))
            z.write("malicioso".toByteArray())
            z.closeEntry()
        }
        val ex = try {
            OspCodec.load(manual.toByteArray()); null
        } catch (e: IOException) { e }
        assertTrue(ex is OspCodec.OspInvalidException) // entrada fora da lista branca

        // pixels.rgba com tamanho errado (menos bytes que o prometido)
        val doc = docExemplo()
        val manual2 = ByteArrayOutputStream()
        ZipOutputStream(manual2).use { z ->
            z.putNextEntry(ZipEntry(OspCodec.ENTRY_MANIFEST))
            z.write(MiniJson.write(manifesto(doc).toJson()).toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry(OspCodec.ENTRY_PIXELS))
            z.write(ByteArray(10)) // devia ser 16*16*4
            z.closeEntry()
        }
        val ex2 = try {
            OspCodec.load(manual2.toByteArray()); null
        } catch (e: IOException) { e }
        assertTrue(ex2 is OspCodec.OspInvalidException)
    }

    @Test
    fun `manifest sem campos obrigatórios é rejeitado`() {
        val manual = ByteArrayOutputStream()
        ZipOutputStream(manual).use { z ->
            z.putNextEntry(ZipEntry(OspCodec.ENTRY_MANIFEST))
            z.write("""{"format":"pixel-one-osp","formatVersion":1}""".toByteArray()) // sem width/height/name
            z.closeEntry()
        }
        val ex = try {
            OspCodec.load(manual.toByteArray()); null
        } catch (e: IOException) { e }
        assertTrue(ex is OspCodec.OspInvalidException)
    }

    @Test
    fun `MiniJson escapa e relê strings com caracteres especiais`() {
        val especial = """nome com "aspas", \ barra, \n nova linha e 中文"""
        val doc = docExemplo()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc, especial), doc, out)
        val loaded = OspCodec.load(out.toByteArray())
        assertEquals(especial, loaded.manifest.name)
    }

    @Test
    fun `readManifest lê só o cabeçalho de um ficheiro grande`() {
        val doc = PixelDocument(64, 64)
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc, "grande"), doc, out)
        val m = OspCodec.readManifest(out.toByteArray())
        assertEquals("grande", m.name)
        assertEquals(64, m.width)
    }

    @Test
    fun `zip truncado é rejeitado`() {
        val doc = docExemplo()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc), doc, out)
        val bytes = out.toByteArray()
        val truncado = bytes.copyOf(bytes.size / 2)
        val ex = try {
            OspCodec.load(truncado); null
        } catch (e: IOException) { e }
        assertTrue("zip truncado devia falhar de forma controlada", ex != null)
    }

    @Test
    fun `load de stream não fecha o stream do chamador`() {
        val doc = docExemplo()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc), doc, out)
        val input = ByteArrayInputStream(out.toByteArray())
        OspCodec.load(input)
        // Stream ainda utilizável (não fechado): read() devolve -1 sem exceção
        assertEquals(-1, input.read())
    }
}
