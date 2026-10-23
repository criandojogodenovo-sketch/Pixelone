package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.Studio3DState.TextureRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MISSÃO TEXTURIZAÇÃO — testes do plano de visibilidade da pintura.
 *
 * Prova a causa raiz e a correção do "eu texturizo e não aparece" em
 * modelos importados: materiais que APRESENTAM textura embutida (binding
 * EMBEDDED explícita ou por omissão) têm de passar a DOCUMENT quando o
 * utilizador pinta a textura do projeto; materiais NONE/DOCUMENT ficam
 * como estão; nada é descartado (o plano só liga, nunca apaga).
 */
class PaintBindingTest {

    private fun materials(vararg info: Pair<String, Int?>) =
        info.mapIndexed { i, (name, img) -> TextureCatalog.MaterialInfo(name, img) }

    @Test
    fun `importação típica — materiais com textura embutida são planeados para DOCUMENT`() {
        // Estado real após importModel: materiais com textura → EMBEDDED.
        val mats = materials("Corpo" to 0, "Meta" to 1, "Luz" to null)
        val bindings = mapOf(
            0 to TextureRef.EMBEDDED,
            1 to TextureRef.EMBEDDED,
            2 to TextureRef.DOCUMENT
        )
        val plan = PaintBinding.ensureDocumentVisible(mats, bindings)
        assertEquals(setOf(0, 1), plan.flips.keys)
        assertTrue(plan.flips.values.all { it == TextureRef.DOCUMENT })
        assertEquals(listOf("Corpo", "Meta"), plan.materialNames)
    }

    @Test
    fun `binding por omissão — material com textura sem entrada no mapa conta como EMBEDDED`() {
        // Mesma regra de fallback do renderer (bindingOf): sem entrada +
        // textureImageIndex != null → apresenta a embutida → pintura invisível.
        val mats = materials("A" to 0, "B" to null)
        val plan = PaintBinding.ensureDocumentVisible(mats, emptyMap())
        assertEquals(setOf(0), plan.flips.keys)
    }

    @Test
    fun `materiais NONE e DOCUMENT não são mexidos`() {
        val mats = materials("A" to null, "B" to 0)
        val bindings = mapOf(0 to TextureRef.NONE, 1 to TextureRef.DOCUMENT)
        val plan = PaintBinding.ensureDocumentVisible(mats, bindings)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.materialNames.size)
    }

    @Test
    fun `idempotente — depois do flip já não há nada a planear`() {
        val mats = materials("Corpo" to 0)
        val first = PaintBinding.ensureDocumentVisible(mats, mapOf(0 to TextureRef.EMBEDDED))
        assertEquals(setOf(0), first.flips.keys)
        // Estado DEPOIS de aplicar o plano:
        val second = PaintBinding.ensureDocumentVisible(mats, first.flips)
        assertTrue(second.isEmpty)
    }

    @Test
    fun `nome em branco recebe etiqueta Material N`() {
        val mats = materials("" to 0)
        val plan = PaintBinding.ensureDocumentVisible(mats, mapOf(0 to TextureRef.EMBEDDED))
        assertEquals(listOf("Material 0"), plan.materialNames)
    }

    @Test
    fun `modelo demo (sem texturas embutidas) nunca é flipado`() {
        // DemoGeometry: materiais sem texturaImageIndex e bindings
        // {0: DOCUMENT, 1: NONE} — o comportamento atual da demo preserva-se.
        val mats = materials("demoA" to null, "demoB" to null)
        val bindings = mapOf(0 to TextureRef.DOCUMENT, 1 to TextureRef.NONE)
        val plan = PaintBinding.ensureDocumentVisible(mats, bindings)
        assertTrue(plan.isEmpty)
    }
}
