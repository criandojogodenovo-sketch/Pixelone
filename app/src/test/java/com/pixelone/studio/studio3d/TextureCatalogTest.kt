package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.Studio3DState.TextureRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MISSÃO UI/SELEÇÃO — testes do CATÁLOGO DE TEXTURAS com identidade estável.
 *
 * Prova o diagnóstico e a correção:
 *  - cada textura (projeto + embutidas usadas) tem UMA identidade estável;
 *  - "usado por" é derivado das bindings — dois materiais com DOCUMENT
 *    aparecem EXPLICITAMENTE na mesma textura (fim da substituição silenciosa);
 *  - materiais NONE não produzem texturas; embutidas não usadas não aparecem;
 *  - a ordem é determinística (projeto primeiro, embutidas por índice).
 */
class TextureCatalogTest {

    private fun materials(vararg info: Pair<String, Int?>) =
        info.mapIndexed { i, (name, img) -> TextureCatalog.MaterialInfo(name, img) }

    // ------------------------------------------------------------------

    @Test
    fun `catálogo com modelo demo tem só a textura do projeto`() {
        // Demo: material 0 → DOCUMENT, material 1 → NONE (bindings iniciais do VM).
        val entries = TextureCatalog.build(
            documentName = "textura-demo",
            documentWidth = 64,
            documentHeight = 64,
            materials = materials("demoA" to null, "demoB" to null),
            bindings = mapOf(0 to TextureRef.DOCUMENT, 1 to TextureRef.NONE),
            embeddedSizes = emptyMap()
        )
        assertEquals(1, entries.size)
        val doc = entries[0]
        assertEquals(TextureCatalog.DOCUMENT_ID, doc.id)
        assertTrue(doc.isDocument)
        assertEquals("textura-demo", doc.name)
        assertEquals(64, doc.width)
        assertEquals(64, doc.height)
        assertEquals(listOf(0), doc.usedByMaterials)
    }

    @Test
    fun `textura embutida usada por material aparece com identidade estável`() {
        val entries = TextureCatalog.build(
            documentName = "proj",
            documentWidth = 64,
            documentHeight = 64,
            materials = materials("casco" to 0, "metal" to 1),
            bindings = mapOf(0 to TextureRef.EMBEDDED, 1 to TextureRef.DOCUMENT),
            embeddedSizes = mapOf(0 to (256 to 256), 1 to (128 to 128))
        )
        assertEquals(2, entries.size) // projeto + embutida 0 (a 1 não é usada)
        val doc = entries[0]
        assertEquals(TextureCatalog.DOCUMENT_ID, doc.id)
        assertEquals(listOf(1), doc.usedByMaterials)
        val emb = entries[1]
        assertEquals(TextureCatalog.embeddedId(0), emb.id)
        assertEquals(TextureEntry.Kind.EMBEDDED, emb.kind)
        assertEquals("casco", emb.name)
        assertEquals(256, emb.width)
        assertEquals(256, emb.height)
        assertEquals(listOf(0), emb.usedByMaterials)
    }

    @Test
    fun `dois materiais com DOCUMENT aparecem EXPLICITAMENTE na mesma textura`() {
        // O coração do problema da missão: pintar a textura do projeto afeta
        // TODOS os materiais ligados a DOCUMENT — o catálogo mostra isso
        // abertamente (antes: substituição silenciosa).
        val entries = TextureCatalog.build(
            documentName = "proj",
            documentWidth = 32,
            documentHeight = 32,
            materials = materials("A" to null, "B" to null, "C" to 0),
            bindings = mapOf(
                0 to TextureRef.DOCUMENT,
                1 to TextureRef.DOCUMENT,
                2 to TextureRef.NONE
            ),
            embeddedSizes = emptyMap()
        )
        assertEquals(1, entries.size)
        assertEquals(listOf(0, 1), entries[0].usedByMaterials)
    }

    @Test
    fun `embutida com binding NONE ou DOCUMENT não entra no catálogo`() {
        val entries = TextureCatalog.build(
            documentName = "proj",
            documentWidth = 16,
            documentHeight = 16,
            materials = materials("A" to 0, "B" to 1),
            bindings = mapOf(0 to TextureRef.NONE, 1 to TextureRef.DOCUMENT),
            embeddedSizes = mapOf(0 to (64 to 64), 1 to (64 to 64))
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].isDocument)
    }

    @Test
    fun `ids são estáveis entre reconstruções e a ordem é determinística`() {
        val mats = materials("a" to 3, "b" to 1, "c" to 3)
        val bindings = mapOf(0 to TextureRef.EMBEDDED, 1 to TextureRef.EMBEDDED, 2 to TextureRef.EMBEDDED)
        val sizes = mapOf(1 to (8 to 8), 3 to (16 to 16))
        val first = TextureCatalog.build("p", 32, 32, mats, bindings, sizes)
        val second = TextureCatalog.build("p", 32, 32, mats, bindings, sizes)
        assertEquals(first, second)
        // Projeto primeiro; embutidas por ordem CRESCENTE de índice (1 antes de 3).
        assertEquals(TextureCatalog.DOCUMENT_ID, first[0].id)
        assertEquals(TextureCatalog.embeddedId(1), first[1].id)
        assertEquals(TextureCatalog.embeddedId(3), first[2].id)
    }

    @Test
    fun `material sem nome conhecido usa etiqueta de recurso embutido`() {
        val entries = TextureCatalog.build(
            documentName = "p",
            documentWidth = 8,
            documentHeight = 8,
            materials = materials("" to 7),
            bindings = mapOf(0 to TextureRef.EMBEDDED),
            embeddedSizes = emptyMap() // dimensão desconhecida — não bloqueia
        )
        assertEquals(2, entries.size)
        val emb = entries[1]
        assertEquals(TextureCatalog.embeddedId(7), emb.id)
        assertEquals(0, emb.width)
        assertEquals(0, emb.height)
    }

    @Test
    fun `parse de id embutido é inverso de embeddedId`() {
        assertNull(TextureCatalog.parseEmbeddedImageIndex(TextureCatalog.DOCUMENT_ID))
        assertNull(TextureCatalog.parseEmbeddedImageIndex("embedded:"))
        assertNull(TextureCatalog.parseEmbeddedImageIndex("outra"))
        assertEquals(0, TextureCatalog.parseEmbeddedImageIndex(TextureCatalog.embeddedId(0)))
        assertEquals(42, TextureCatalog.parseEmbeddedImageIndex("embedded:42"))
        assertNotNull(TextureCatalog.parseEmbeddedImageIndex("embedded:7"))
        assertFalse(TextureCatalog.embeddedId(2) == TextureCatalog.DOCUMENT_ID)
    }
}
