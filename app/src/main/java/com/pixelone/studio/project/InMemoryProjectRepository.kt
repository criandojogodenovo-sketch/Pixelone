package com.pixelone.studio.project

/**
 * Implementação em memória do repositório de projetos, usada como base da
 * Fase 0. A persistência real (armazenamento privado da app, formato .osp)
 * será adicionada numa fase posterior implementando o mesmo contrato, pelo
 * que o resto do código não precisa de mudar.
 *
 * Segura para uso concorrente (acesso sincronizado).
 */
class InMemoryProjectRepository : ProjectRepository {

    private val projects = LinkedHashMap<String, PixelProject>()
    private val lock = Any()

    override fun listProjects(): List<PixelProject> =
        synchronized(lock) { projects.values.toList() }

    override fun getProject(id: String): PixelProject? =
        synchronized(lock) { projects[id] }

    override fun createProject(name: String, createdAtEpochMs: Long): PixelProject =
        synchronized(lock) {
            // PixelProject valida o nome; a exceção é lançada antes de
            // qualquer mutação do repositório.
            val project = PixelProject(
                name = name,
                createdAtEpochMs = createdAtEpochMs,
                updatedAtEpochMs = createdAtEpochMs
            )
            projects[project.id] = project
            project
        }

    override fun deleteProject(id: String): Boolean =
        synchronized(lock) { projects.remove(id) != null }
}
