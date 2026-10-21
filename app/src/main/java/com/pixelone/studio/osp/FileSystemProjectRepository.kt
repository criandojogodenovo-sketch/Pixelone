package com.pixelone.studio.osp

import com.pixelone.studio.pixel.PixelDocument
import com.pixelone.studio.project.PixelProject
import com.pixelone.studio.project.ProjectRepository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Repositório de projetos do Pixel One em ARMazenamento PRIVADO da aplicação
 * (ficheiros `.osp` num diretório do filesDir), implementando o contrato
 * [ProjectRepository] da Fase 0 e as operações de documento do editor.
 *
 * DECISÃO DE ARMAZENAMENTO (mantida da Fase 0, agora concreta):
 * - Diretório privado da app: SEM permissões de sistema, SEM Storage Access
 *   Framework para os projetos internos. O SAF fica para operações em que o
 *   utilizador escolhe explicitamente um destino/ficheiro (Guardar como,
 *   Exportar PNG, Importar .osp).
 *
 * ATOMICIDADE E PROTEÇÃO CONTRA PERDA DE DADOS:
 * - Guardar escreve SEMPRE para `id.osp.tmp`, fecha, RELÊ o temporário e
 *   valida-o (roundtrip completo), e só então o renomeia para o ficheiro
 *   final. O projeto anterior permanece intacto até a validação passar —
 *   uma falha de escrita/disco cheio NUNCA destrói o ficheiro anterior.
 * - A gravação usa move atómico quando o sistema o suporta.
 */
class FileSystemProjectRepository(
    /** Diretório onde vivem os .osp (tipicamente context.filesDir/projects). */
    private val projectsDir: File,
    /** Tamanho por defeito de novos projetos (64x64 é o padrão de pixel art). */
    private val defaultWidth: Int = 64,
    private val defaultHeight: Int = 64
) : ProjectRepository {

    init {
        if (!projectsDir.exists()) projectsDir.mkdirs()
    }

    // ------------------------------------------------------------------
    // Contrato ProjectRepository (Fase 0)
    // ------------------------------------------------------------------

    override fun listProjects(): List<PixelProject> =
        ospFiles().mapNotNull { file ->
            try {
                val manifest = OspCodec.readManifest(file.readBytes())
                manifest.toProject(file.projectId())
            } catch (e: Exception) {
                // Um .osp corrupto não impede a listagem dos restantes.
                null
            }
        }.sortedByDescending { it.updatedAtEpochMs }

    override fun getProject(id: String): PixelProject? {
        val file = projectFile(id) ?: return null
        if (!file.exists()) return null
        return try {
            OspCodec.readManifest(file.readBytes()).toProject(id)
        } catch (e: Exception) {
            null
        }
    }

    override fun createProject(name: String, createdAtEpochMs: Long): PixelProject =
        createProject(name, defaultWidth, defaultHeight, createdAtEpochMs)

    override fun deleteProject(id: String): Boolean {
        val file = projectFile(id) ?: return false
        return file.exists() && file.delete()
    }

    // ------------------------------------------------------------------
    // Operações do editor (documento completo)
    // ------------------------------------------------------------------

    /**
     * Cria um projeto novo com as dimensões indicadas (documento totalmente
     * transparente) e grava-o imediatamente. Lança [IOException] se a
     * gravação inicial falhar (o projeto não fica "meio criado").
     */
    fun createProject(
        name: String,
        width: Int,
        height: Int,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): PixelProject {
        val project = PixelProject(
            name = name,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = createdAtEpochMs
        )
        val document = PixelDocument(width, height)
        saveDocument(project, document)
        return project
    }

    /** Devolve o projeto, o documento completo, a paleta e a secção 3D (opcional). */
    data class LoadedProjectData(
        val project: PixelProject,
        val document: PixelDocument,
        val palette: List<String>?,
        /** Conteúdo de studio3d.json (v2), ou null em projetos v1/sem 3D. */
        val studio3dJson: String? = null
    )

    fun loadProject(id: String): LoadedProjectData? {
        val file = projectFile(id) ?: return null
        if (!file.exists()) return null
        val loaded = OspCodec.load(file.inputStream().buffered())
        val project = loaded.manifest.toProject(id)
        return LoadedProjectData(project, loaded.document, loaded.manifest.palette, loaded.studio3dJson)
    }

    /**
     * Guarda o documento ATOMICAMENTE: escreve para um .tmp, relê e valida,
     * e só então substitui o ficheiro final. Devolve o projeto atualizado
     * (updatedAtEpochMs novo).
     *
     * @param palette paleta opcional em hex ARGB (#AARRGGBB) para o manifesto.
     * @param studio3dJson conteúdo JSON opcional da secção 3D do projeto (v2;
     *        ver Studio3DState) — guardada como studio3d.json quando não nula.
     * @throws IOException em falha de escrita/validação (o ficheiro anterior
     *         permanece intacto).
     */
    fun saveDocument(
        project: PixelProject,
        document: PixelDocument,
        palette: List<String>? = null,
        studio3dJson: String? = null
    ): PixelProject {
        require(project.id.isNotBlank()) { "id do projeto vazio" }
        val finalFile = File(projectsDir, "${project.id}.osp")
        val tmpFile = File(projectsDir, "${project.id}.osp.tmp")

        val updated = project.copy(updatedAtEpochMs = System.currentTimeMillis())
        val manifest = OspManifest(
            name = updated.name,
            width = document.width,
            height = document.height,
            createdAtEpochMs = updated.createdAtEpochMs,
            updatedAtEpochMs = updated.updatedAtEpochMs,
            palette = palette?.takeIf { it.isNotEmpty() }
        )

        try {
            OspCodec.save(manifest, document, tmpFile.outputStream().buffered(), studio3dJson)
            // Validação de roundtrip ANTES de substituir o ficheiro final.
            val reloaded = OspCodec.load(tmpFile.inputStream().buffered())
            require(reloaded.document.width == document.width &&
                reloaded.document.height == document.height &&
                reloaded.document.copyPixels().contentEquals(document.copyPixels())
            ) { "Validação de roundtrip falhou" }
            require(reloaded.studio3dJson == studio3dJson) { "Roundtrip da secção 3D falhou" }
        } catch (e: Exception) {
            tmpFile.delete()
            throw IOException("Falha ao guardar o projeto '${updated.name}': ${e.message}", e)
        }

        try {
            try {
                Files.move(
                    tmpFile.toPath(), finalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (atomicUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw IOException("Falha ao finalizar a gravação: ${e.message}", e)
        }
        return updated
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private fun ospFiles(): List<File> =
        projectsDir.listFiles { f -> f.isFile && f.name.endsWith(".osp") }?.toList() ?: emptyList()

    private fun projectFile(id: String): File? {
        if (!ID_REGEX.matches(id)) return null // proteção contra path traversal
        return File(projectsDir, "$id.osp")
    }

    private fun File.projectId(): String = name.removeSuffix(".osp")

    private fun OspManifest.toProject(id: String): PixelProject = PixelProject(
        id = id,
        name = name,
        formatVersion = formatVersion,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    companion object {
        /** UUID v4 canónico (os ids são gerados pelo PixelProject). */
        private val ID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        /** Nome de ficheiro .osp exportado (Guardar como): slug do nome do projeto. */
        fun safeFileName(projectName: String): String {
            val slug = projectName.map { c ->
                if (c.isLetterOrDigit()) c else '-'
            }.joinToString("").trim('-').take(60)
            val base = if (slug.isBlank()) "projeto" else slug
            return "$base.osp"
        }
    }
}
