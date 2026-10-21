package com.pixelone.studio.osp

import com.pixelone.studio.pixel.LayerStack
import com.pixelone.studio.pixel.PixelDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da PERSISTÊNCIA DE CAMADAS no .osp v3 (Ronda B):
 *  - guardar/abrir com 2+ camadas (píxeis, nomes, visibilidade, opacidade,
 *    ativa) — roundtrip exato;
 *  - pixels.rgba guarda a COMPOSIÇÃO (compatível com versões antigas);
 *  - projetos v1/v2 (sem camadas) abrem como UMA camada;
 *  - secção de camadas incoerente → erro controlado.
 */
class OspLayersTest {

    private fun manifest(w: Int, h: Int) = OspManifest(
        name = "projeto-camadas",
        width = w, height = h,
        createdAtEpochMs = 1730000000000L,
        updatedAtEpochMs = 1730000000000L
    )

    @Test
    fun `roundtrip v3 com duas camadas preserva píxeis e metadados`() {
        val w = 8; val h = 8
        val stack = LayerStack(w, h)
        stack.seedSingleLayer(IntArray(w * h) { 0xFFFF0000.toInt() }) // base vermelha
        stack.addLayer("Sobreposição", IntArray(w * h) { i -> if (i < 8) 0xFF00FF00.toInt() else 0x00000000 })
        stack.setOpacity(1, 60)
        stack.setActive(1)

        val composite = PixelDocument(w, h, stack.compose())
        val bytes = ByteArrayOutputStream()
        OspCodec.save(
            manifest(w, h), composite, bytes,
            studio3dJson = null,
            layers = OspCodec.LayersSection(LayersCodec.toJson(stack), stack.layers.map { it.doc.toRgbaBytes() })
        )

        val loaded = OspCodec.load(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(3, loaded.manifest.formatVersion)
        assertNotNull(loaded.layers)
        assertEquals(2, loaded.layers!!.layerPixels.size)

        val restored = LayersCodec.fromRgbaList(w, h, loaded.layers.layerPixels, loaded.layers.json)
        assertEquals(2, restored.count)
        assertEquals("Base", restored.layers[0].name)
        assertEquals("Sobreposição", restored.layers[1].name)
        assertTrue(restored.layers[0].visible)
        assertEquals(60, restored.layers[1].opacity)
        assertEquals(1, restored.activeIndex)

        // Píxeis de cada camada EXATOS.
        assertArrayEquals(
            stack.layers[0].doc.copyPixels(), restored.layers[0].doc.copyPixels()
        )
        assertArrayEquals(
            stack.layers[1].doc.copyPixels(), restored.layers[1].doc.copyPixels()
        )
        // A composição reconstruída é igual à guardada em pixels.rgba.
        assertArrayEquals(composite.copyPixels(), restored.compose())
    }

    @Test
    fun `pixels_rgba do v3 guarda a composição das camadas visíveis`() {
        val w = 4; val h = 4
        val stack = LayerStack(w, h)
        stack.seedSingleLayer(IntArray(w * h) { 0xFFFF0000.toInt() })
        stack.addLayer("Topo", IntArray(w * h) { 0xFF00FF00.toInt() })
        stack.toggleVisible(1) // topo oculto
        val composite = stack.compose()
        val bytes = ByteArrayOutputStream()
        OspCodec.save(
            manifest(w, h), PixelDocument(w, h, composite), bytes,
            layers = OspCodec.LayersSection(LayersCodec.toJson(stack), stack.layers.map { it.doc.toRgbaBytes() })
        )
        val loaded = OspCodec.load(ByteArrayInputStream(bytes.toByteArray()))
        // pixels.rgba = composição (vermelho — o topo está oculto), NÃO a camada.
        assertTrue(loaded.document.copyPixels().all { it == 0xFFFF0000.toInt() })
    }

    @Test
    fun `projeto v2 sem camadas abre como uma camada`() {
        val w = 4; val h = 4
        val pixels = IntArray(w * h) { 0xFF112233.toInt() }
        val bytes = ByteArrayOutputStream()
        OspCodec.save(manifest(w, h), PixelDocument(w, h, pixels), bytes, studio3dJson = null)
        val loaded = OspCodec.load(ByteArrayInputStream(bytes.toByteArray()))
        assertNull("v2 não devia ter secção de camadas", loaded.layers)
        // Semântica de abertura (igual ao ViewModel): 1 camada com os píxeis.
        val stack = LayerStack(w, h).apply { seedSingleLayer(loaded.document.copyPixels()) }
        assertEquals(1, stack.count)
        assertArrayEquals(pixels, stack.activeDoc().copyPixels())
    }

    @Test
    fun `layers_json presente sem layer-N_rgba é rejeitado`() {
        val w = 4; val h = 4
        // Constrói o ZIP à mão: manifest + pixels + layers.json SEM camadas.
        val out = ByteArrayOutputStream()
        val z = java.util.zip.ZipOutputStream(out)
        z.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
        z.write(MiniJson.write(manifest(w, h).toJson()).toByteArray())
        z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("pixels.rgba"))
        z.write(ByteArray(w * h * 4))
        z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("layers.json"))
        z.write("{\"version\":1,\"active\":0,\"layers\":[]}".toByteArray())
        z.closeEntry()
        z.finish()
        try {
            OspCodec.load(ByteArrayInputStream(out.toByteArray()))
            throw AssertionError("devia rejeitar layers.json sem camadas")
        } catch (e: OspCodec.OspInvalidException) {
            assertTrue(e.message!!.contains("sem entradas layer"))
        }
    }

    @Test
    fun `entradas de camada descontínuas são rejeitadas`() {
        val w = 4; val h = 4
        val out = ByteArrayOutputStream()
        val z = java.util.zip.ZipOutputStream(out)
        z.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
        z.write(MiniJson.write(manifest(w, h).toJson()).toByteArray())
        z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("pixels.rgba"))
        z.write(ByteArray(w * h * 4))
        z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("layers.json"))
        z.write("{\"version\":1,\"active\":0,\"layers\":[{\"name\":\"A\",\"visible\":1,\"opacity\":100},{\"name\":\"B\",\"visible\":1,\"opacity\":100}]}".toByteArray())
        z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("layer-0.rgba"))
        z.write(ByteArray(w * h * 4))
        z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("layer-2.rgba")) // FALTA layer-1
        z.write(ByteArray(w * h * 4))
        z.closeEntry()
        z.finish()
        try {
            OspCodec.load(ByteArrayInputStream(out.toByteArray()))
            throw AssertionError("devia rejeitar entradas descontínuas")
        } catch (e: OspCodec.OspInvalidException) {
            assertTrue(e.message!!.contains("falta") || e.message!!.contains("descontínuas"))
        }
    }

    @Test
    fun `nome de entrada de camada reconhecido e validado`() {
        assertEquals("layer-0.rgba", OspCodec.layerEntryName(0))
        assertEquals("layer-3.rgba", OspCodec.layerEntryName(3))
        assertEquals(0, OspCodec.parseLayerEntryName("layer-0.rgba"))
        assertEquals(3, OspCodec.parseLayerEntryName("layer-3.rgba"))
        assertNull(OspCodec.parseLayerEntryName("layer-9.rgba")) // > MAX_LAYERS
        assertNull(OspCodec.parseLayerEntryName("layer-x.rgba"))
        assertNull(OspCodec.parseLayerEntryName("pixels.rgba"))
        assertFalse(OspCodec.parseLayerEntryName("layer-0.zip") != null)
    }
}
