package com.pixelone.studio.osp

import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Testes da Fase 3 para o .osp v2: secção studio3d.json (referências do
 * 3D Studio) e COMPATIBILIDADE com projetos v1 (antigos abrem sem 3D).
 */
class OspCodecStudio3DTest {

    private fun doc16(): PixelDocument {
        val doc = PixelDocument(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) {
            doc.set(x, y, if ((x + y) % 2 == 0) 0xFFF57C00.toInt() else 0x00000000)
        }
        return doc
    }

    private fun manifesto(doc: PixelDocument) = OspManifest(
        name = "projeto-3d",
        width = doc.width,
        height = doc.height,
        createdAtEpochMs = 1_700_000_000_000L,
        updatedAtEpochMs = 1_700_000_100_000L
    )

    private val studio3dJsonExemplo =
        """{"schemaVersion":1,"model":{"kind":"IMPORTED","fileName":"cena.glb","fileBytes":999,
           "sha256":"deadbeef","triangles":1200,"meshes":2,"materials":2,"hasUv":true},
           "bindings":[[0,"DOCUMENT"],[1,"EMBEDDED"]],
           "settings":{"sampling":"LINEAR","showUv":true,"mode":"SPLIT"},
           "camera":{"yaw":35.0,"pitch":25.0,"distance":3.2,"tx":0.0,"ty":0.0,"tz":0.0}}"""

    // ------------------------------------------------------------------
    // v2: roundtrip com a secção 3D
    // ------------------------------------------------------------------

    @Test
    fun `save com secção 3D grava e relê studio3d json intacto`() {
        val doc = doc16()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc), doc, out, studio3dJsonExemplo)
        val loaded = OspCodec.load(out.toByteArray())
        assertEquals(OspCodec.FORMAT_VERSION, loaded.manifest.formatVersion)
        assertEquals(studio3dJsonExemplo, loaded.studio3dJson)
        assertTrue(doc.copyPixels().contentEquals(loaded.document.copyPixels()))
    }

    @Test
    fun `save sem secção 3D continua a funcionar (null)`() {
        val doc = doc16()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc), doc, out)
        val loaded = OspCodec.load(out.toByteArray())
        assertEquals(OspCodec.FORMAT_VERSION, loaded.manifest.formatVersion)
        assertNull(loaded.studio3dJson)
    }

    @Test
    fun `v2 relê através de readManifest (listagem de projetos não quebra)`() {
        val doc = doc16()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc), doc, out, studio3dJsonExemplo)
        val manifest = OspCodec.readManifest(out.toByteArray())
        assertEquals("projeto-3d", manifest.name)
        assertEquals(OspCodec.FORMAT_VERSION, manifest.formatVersion)
    }

    // ------------------------------------------------------------------
    // Compatibilidade: projetos v1 (Fases 0–2) abrem SEM alterações
    // ------------------------------------------------------------------

    /** Constrói um .osp v1 REAL (manifest formatVersion 1 + pixels.rgba). */
    private fun ospV1(): ByteArray {
        val doc = doc16()
        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)
        val manifestJson = """
        {"format":"pixel-one-osp","formatVersion":1,"app":"Pixel One",
         "name":"projeto-antigo","width":16,"height":16,
         "createdAtEpochMs":1700000000000,"updatedAtEpochMs":1700000100000,
         "palette":["#FF000000","#FFF57C00"]}
        """.trimIndent()
        zip.putNextEntry(ZipEntry(OspCodec.ENTRY_MANIFEST))
        zip.write(manifestJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(OspCodec.ENTRY_PIXELS))
        zip.write(doc.toRgbaBytes())
        zip.closeEntry()
        zip.finish()
        return out.toByteArray()
    }

    @Test
    fun `projeto v1 abre com píxeis intactos e secção 3D nula`() {
        val loaded = OspCodec.load(ospV1())
        assertEquals(1, loaded.manifest.formatVersion)
        assertEquals("projeto-antigo", loaded.manifest.name)
        assertNull("v1 não tem secção 3D", loaded.studio3dJson)
        assertTrue(doc16().copyPixels().contentEquals(loaded.document.copyPixels()))
    }

    @Test
    fun `v1 com secção 3D extra (tolerante) ainda abre e expõe a secção`() {
        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)
        val manifestJson = """
        {"format":"pixel-one-osp","formatVersion":1,"app":"Pixel One",
         "name":"hibrido","width":16,"height":16,
         "createdAtEpochMs":1700000000000,"updatedAtEpochMs":1700000100000}
        """.trimIndent()
        zip.putNextEntry(ZipEntry(OspCodec.ENTRY_MANIFEST))
        zip.write(manifestJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(OspCodec.ENTRY_PIXELS))
        zip.write(doc16().toRgbaBytes())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(OspCodec.ENTRY_STUDIO3D))
        zip.write(studio3dJsonExemplo.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        val loaded = OspCodec.load(out.toByteArray())
        assertEquals(1, loaded.manifest.formatVersion)
        assertNotNull(loaded.studio3dJson)
    }

    @Test
    fun `versão futura (v3+) continua rejeitada de forma controlada`() {
        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)
        val manifestJson = """
        {"format":"pixel-one-osp","formatVersion":${OspCodec.FORMAT_VERSION + 1},
         "app":"Pixel One","name":"futuro","width":16,"height":16,
         "createdAtEpochMs":1700000000000,"updatedAtEpochMs":1700000100000}
        """.trimIndent()
        zip.putNextEntry(ZipEntry(OspCodec.ENTRY_MANIFEST))
        zip.write(manifestJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(OspCodec.ENTRY_PIXELS))
        zip.write(doc16().toRgbaBytes())
        zip.closeEntry()
        zip.finish()
        try {
            OspCodec.load(out.toByteArray())
            org.junit.Assert.fail("Deveria rejeitar versão futura")
        } catch (e: OspCodec.OspUnsupportedVersionException) {
            assertEquals(OspCodec.FORMAT_VERSION + 1, e.foundVersion)
        }
    }

    @Test
    fun `entrada desconhecida no ZIP continua rejeitada (lista branca)`() {
        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry("payload.exe"))
        zip.write(ByteArray(10))
        zip.closeEntry()
        zip.finish()
        try {
            OspCodec.load(out.toByteArray())
            org.junit.Assert.fail("Deveria rejeitar entrada desconhecida")
        } catch (e: OspCodec.OspInvalidException) {
            assertTrue(e.message!!.contains("inesperada"))
        }
    }

    @Test
    fun `leitura zip v2 via ZipInputStream expõe as três entradas na ordem`() {
        val doc = doc16()
        val out = ByteArrayOutputStream()
        OspCodec.save(manifesto(doc), doc, out, "{\"schemaVersion\":1}")
        val zip = ZipInputStream(out.toByteArray().inputStream(), Charsets.UTF_8)
        val names = mutableListOf<String>()
        while (true) {
            val entry = zip.nextEntry ?: break
            names.add(entry.name)
            zip.closeEntry()
        }
        assertEquals(
            listOf(OspCodec.ENTRY_MANIFEST, OspCodec.ENTRY_PIXELS, OspCodec.ENTRY_STUDIO3D),
            names
        )
    }
}
