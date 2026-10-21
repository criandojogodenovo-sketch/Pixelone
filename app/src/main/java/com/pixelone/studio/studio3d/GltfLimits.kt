package com.pixelone.studio.studio3d

/**
 * Limites de recursos do 3D Texture Studio (Fase 3).
 *
 * OBJETIVO: nunca alocar memória desproporcional nem bloquear a UI em
 * dispositivos com ~4 GB de RAM. TODOS os limites são verificados ANTES de
 * qualquer alocação grande e produzem mensagens claras e recuperáveis
 * (nunca um crash nem um OOM).
 *
 * Valores escolhidos com margem confortável para pixel art e modelos de
 * jogos indie; muito acima do uso típico, bem abaixo dos limites perigosos.
 */
object GltfLimits {

    /** Tamanho máximo do ficheiro de modelo (64 MB — GLB de jogos indie cabe). */
    const val MAX_FILE_BYTES: Long = 64L * 1024 * 1024

    /** Máximo do JSON interno do glTF depois de descomprimido (32 MB). */
    const val MAX_JSON_BYTES: Int = 32 * 1024 * 1024

    /** Máximo do chunk BIN somado com buffers data: URI (128 MB). */
    const val MAX_TOTAL_BUFFER_BYTES: Long = 128L * 1024 * 1024

    /** Máximo de triângulos no modelo inteiro (somando todas as primitivas). */
    const val MAX_TRIANGLES: Int = 300_000

    /** Máximo de primitivas renderizáveis (meshes × primitivas). */
    const val MAX_RENDERABLES: Int = 512

    /** Máximo de nós percorridos na cena (proteção contra grafos gigantes). */
    const val MAX_NODES: Int = 10_000

    /** Máximo de imagens (texturas) embutidas no modelo. */
    const val MAX_IMAGES: Int = 16

    /** Máximo de bytes por imagem embutida (PNG/JPEG descodificável). */
    const val MAX_IMAGE_BYTES: Int = 32 * 1024 * 1024

    /** Lado máximo de uma textura descodificada (limite de GPUs móveis comuns). */
    const val MAX_TEXTURE_DIMENSION: Int = 4096

    /** Profundidade máxima de travessia da hierarquia de nós (proteção a ciclos). */
    const val MAX_NODE_DEPTH: Int = 64

    /**
     * Mensagem pronta para o utilizador quando um ficheiro excede um limite.
     * Erros de recursos são RECUPERÁVEIS: o 3D Studio continua funcional
     * (geometria de demonstração) e a mensagem explica o que fazer.
     */
    fun tooLargeError(what: String, found: Long, max: Long): String =
        "Recurso demasiado grande: $what ($found). O máximo é $max. " +
            "Exporta o modelo com menos detalhe ou reduz as texturas."
}
