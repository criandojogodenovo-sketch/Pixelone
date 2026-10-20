package com.pixelone.studio.osp

import com.pixelone.studio.pixel.PixelDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Testes obrigatórios de persistência real (teste C): criar, guardar,
 * fechar, reabrir e comparar — incluindo a garantia de que uma falha de
 * gravação NÃO destrói o ficheiro anterior.
 */
class FileSystemProjectRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pastaProjetos(): File = File(tmp.root, "projetos").apply { mkdirs() }

    private fun repo(): FileSystemProjectRepository =
        FileSystemProjectRepository(pastaProjetos())

    @Test
    fun `criar guardar fechar reabrir e comparar sem perda de dados`() {
        val r = repo()
        val projeto = r.createProject("meu-sprite", 32, 24)

        val docOriginal = PixelDocument(32, 24)
        for (y in 0 until 24) for (x in 0 until 32) {
            docOriginal.set(x, y, if ((x * 7 + y * 13) % 5 == 0) 0x00000000.toInt() else 0xFF112233.toInt())
        }
        r.saveDocument(projeto, docOriginal)

        // "Fechar e reabrir": nova instância do repositório sobre a mesma pasta
        val r2 = FileSystemProjectRepository(pastaProjetos())
        val reaberto = r2.loadProject(projeto.id)
        assertNotNull(reaberto)
        val (proj, doc) = reaberto!!
        assertEquals(projeto.id, proj.id)
        assertEquals("meu-sprite", proj.name)
        assertEquals(32, doc.width)
        assertEquals(24, doc.height)
        assertTrue(docOriginal.copyPixels().contentEquals(doc.copyPixels()))
    }

    @Test
    fun `listProjects ordena por atualização e mostra metadados corretos`() {
        val r = repo()
        val a = r.createProject("alfa", 16, 16)
        Thread.sleep(5)
        val b = r.createProject("bravo", 16, 16)
        Thread.sleep(5)
        r.saveDocument(a, PixelDocument(16, 16)) // alfa fica o mais recente

        val lista = r.listProjects()
        assertEquals(2, lista.size)
        assertEquals("alfa", lista[0].name) // mais recente primeiro
        assertEquals("bravo", lista[1].name)
        assertNotNull(r.getProject(b.id))
    }

    @Test
    fun `falha ao guardar NÃO destrói o ficheiro anterior`() {
        val r = repo()
        val projeto = r.createProject("protegido", 16, 16)
        val bom = PixelDocument(16, 16)
        bom.set(0, 0, 0xFFFF0000.toInt())
        r.saveDocument(projeto, bom)

        // Simula falha de escrita (disco cheio / permissão): o caminho do
        // ficheiro temporário passa a ser um DIRETÓRIO, tornando a escrita
        // do .tmp impossível.
        val pasta = pastaProjetos()
        File(pasta, "${projeto.id}.osp.tmp").mkdirs()

        val falhou = try {
            r.saveDocument(projeto, PixelDocument(16, 16))
            false
        } catch (e: java.io.IOException) {
            true
        }
        assertTrue("gravação devia falhar", falhou)

        // O ficheiro anterior continua intacto e legível, com os píxeis originais
        val salvo = r.loadProject(projeto.id)!!
        assertEquals(16, salvo.document.width)
        assertEquals(0xFFFF0000.toInt(), salvo.document.get(0, 0))

        // O diretório-obstáculo é removido pelo cleanup do repositório
        // (se não fosse, seria um ficheiro .tmp órfão — verificar limpo)
        val temporarios = pasta.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue(
            "não deviam existir .tmp válidos órfãos: $temporarios",
            temporarios.all { !it.isFile }
        )
    }

    @Test
    fun `repositório rejeita ids não-UUID (path traversal impossível)`() {
        val r = repo()
        assertNull(r.getProject("../../etc/passwd"))
        assertNull(r.getProject("a/b/c"))
        assertFalse(r.deleteProject("../../secrets"))
    }

    @Test
    fun `corrupto na pasta não impede a listagem dos outros`() {
        val r = repo()
        r.createProject("bom", 8, 8)
        File(pastaProjetos(), "00000000-0000-0000-0000-00000000000f.osp")
            .writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val lista = r.listProjects()
        assertEquals(1, lista.size)
        assertEquals("bom", lista[0].name)
    }

    @Test
    fun `safeFileName gera nomes seguros`() {
        assertEquals("sprite-final.osp", FileSystemProjectRepository.safeFileName("sprite final?"))
        assertEquals("projeto.osp", FileSystemProjectRepository.safeFileName("///"))
        assertTrue(FileSystemProjectRepository.safeFileName("nome muito longo ".repeat(5)).length <= 64)
    }

    @Test
    fun `deleteProject apaga o ficheiro`() {
        val r = repo()
        val p = r.createProject("para-apagar", 8, 8)
        assertTrue(r.deleteProject(p.id))
        assertNull(r.loadProject(p.id))
        assertFalse(r.deleteProject(p.id))
    }
}
