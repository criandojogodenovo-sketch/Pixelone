package com.pixelone.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Testes do repositório de projetos em memória (Fase 0). */
class ProjectRepositoryTest {

    private lateinit var repository: InMemoryProjectRepository

    @Before
    fun setup() {
        repository = InMemoryProjectRepository()
    }

    @Test
    fun criar_projeto_adiciona_ao_repositorio() {
        val created = repository.createProject("Tileset caverna")
        assertEquals(1, repository.listProjects().size)
        assertEquals(created, repository.getProject(created.id))
        assertEquals("Tileset caverna", created.name)
    }

    @Test
    fun consultar_id_inexistente_devolve_null() {
        assertNull(repository.getProject("id-inexistente"))
    }

    @Test
    fun apagar_projeto_existente_remove_e_devolve_true() {
        val created = repository.createProject("Provisorio")
        assertTrue(repository.deleteProject(created.id))
        assertEquals(0, repository.listProjects().size)
        assertNull(repository.getProject(created.id))
        assertFalse(repository.deleteProject(created.id))
    }

    @Test
    fun ordem_de_criacao_e_preservada_na_listagem() {
        val a = repository.createProject("A", createdAtEpochMs = 1_000L)
        val b = repository.createProject("B", createdAtEpochMs = 2_000L)
        val c = repository.createProject("C", createdAtEpochMs = 3_000L)
        assertEquals(listOf(a.id, b.id, c.id), repository.listProjects().map { it.id })
    }

    @Test
    fun nome_vazio_lanca_excecao_e_nao_altera_repositorio() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.createProject("  ")
        }
        assertEquals(0, repository.listProjects().size)
    }
}
