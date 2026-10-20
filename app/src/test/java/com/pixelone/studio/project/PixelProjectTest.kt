package com.pixelone.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes do modelo mínimo de projeto (Fase 0). */
class PixelProjectTest {

    @Test
    fun projeto_novo_usa_a_versao_de_formato_atual() {
        val project = PixelProject(name = "Sprite heroi")
        assertEquals(1, PixelProject.FORMAT_VERSION)
        assertEquals(PixelProject.FORMAT_VERSION, project.formatVersion)
    }

    @Test
    fun ids_gerados_sao_unicos_e_nao_vazios() {
        val a = PixelProject(name = "A")
        val b = PixelProject(name = "B")
        assertTrue(a.id.isNotBlank())
        assertTrue(b.id.isNotBlank())
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun nome_vazio_e_rejeitado() {
        assertThrows(IllegalArgumentException::class.java) {
            PixelProject(name = "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PixelProject(name = "")
        }
    }

    @Test
    fun carimbos_temporais_sao_preenchidos_por_omissao() {
        val before = System.currentTimeMillis()
        val project = PixelProject(name = "T")
        val after = System.currentTimeMillis()
        assertTrue(project.createdAtEpochMs in before..after)
        assertEquals(project.createdAtEpochMs, project.updatedAtEpochMs)
    }
}
