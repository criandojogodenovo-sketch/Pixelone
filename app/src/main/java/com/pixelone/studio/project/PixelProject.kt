package com.pixelone.studio.project

import java.util.UUID

/**
 * Modelo mínimo de um projeto Pixel One.
 *
 * DECISÕES REGISTADAS (Fase 0):
 * - O formato de ficheiro .osp será definido numa fase posterior. Aqui não
 *   existe qualquer serialização — apenas o modelo e o contrato do
 *   repositório — para não criar formatos provisórios incompatíveis com a
 *   arquitetura futura.
 * - [formatVersion] existe desde já para que a versão do formato acompanhe
 *   o modelo desde o primeiro dia e permita migrações futuras.
 */
data class PixelProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val formatVersion: Int = FORMAT_VERSION,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "O nome do projeto não pode ser vazio." }
    }

    companion object {
        /** Versão do formato de dados — base para migrações do futuro formato .osp. */
        const val FORMAT_VERSION: Int = 1
    }
}
