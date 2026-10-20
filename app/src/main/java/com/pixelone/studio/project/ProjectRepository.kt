package com.pixelone.studio.project

/**
 * Repositório responsável pelo acesso aos projetos locais.
 *
 * Contrato mínimo da Fase 0: listar, consultar, criar e apagar. A
 * persistência em ficheiro (formato .osp) chega numa fase posterior; as
 * implementações concretas decidem onde os dados vivem.
 *
 * DECISÃO DE ARMAZENAMENTO: o Pixel One usa o armazenamento PRIVADO da
 * aplicação (filesDir) para os dados internos — sem permissões de sistema.
 * O Storage Access Framework fica reservado para operações futuras em que o
 * utilizador escolha explicitamente ficheiros (exportar/importar), evitando
 * permissões de armazenamento abrangentes.
 */
interface ProjectRepository {
    /** Lista os projetos por ordem de criação. */
    fun listProjects(): List<PixelProject>

    /** Devolve o projeto com o id indicado, ou null se não existir. */
    fun getProject(id: String): PixelProject?

    /**
     * Cria um projeto com o nome indicado e devolve-o.
     * @throws IllegalArgumentException se o nome for vazio/branco.
     */
    fun createProject(name: String, createdAtEpochMs: Long = System.currentTimeMillis()): PixelProject

    /** Apaga o projeto com o id indicado. Devolve true se existia. */
    fun deleteProject(id: String): Boolean
}
