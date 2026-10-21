package com.pixelone.studio.studio3d

/**
 * MISSÃO UI/SELEÇÃO — Catálogo de TEXTURAS com identidade estável.
 *
 * DIAGNÓSTICO (auditoria do repositório real): até esta missão o app tinha
 * apenas uma LIGAÇÃO material → referência (DOCUMENT/EMBEDDED/NONE —
 * [Studio3DState.TextureRef]); as texturas em si NÃO eram objetos selecionáveis
 * — não havia forma de listar "que texturas existem no modelo", escolher uma
 * individualmente, ver as suas propriedades ou saber que materiais a usam.
 *
 * Este catálogo dá a cada textura do modelo UMA identidade estável:
 *  - "document"      — a textura do projeto (pixels.rgba / pilha de camadas);
 *  - "embedded:<i>"  — a imagem embutida <i> do GLB usada por ≥ 1 material.
 *
 * 100% Kotlin puro (sem tipos Android) — testável em JVM, igual ao resto do
 * núcleo studio3d.
 */
data class TextureEntry(
    /** Identidade estável: [TextureCatalog.DOCUMENT_ID] ou "embedded:<índice>". */
    val id: String,
    val kind: Kind,
    val name: String,
    val width: Int,
    val height: Int,
    /** Materiais (índices) que APRESENTAM esta textura — derivado das bindings. */
    val usedByMaterials: List<Int>
) {
    enum class Kind { DOCUMENT, EMBEDDED }

    val isDocument: Boolean get() = kind == Kind.DOCUMENT
}

object TextureCatalog {

    const val DOCUMENT_ID: String = "document"
    const val EMBEDDED_PREFIX: String = "embedded:"

    fun embeddedId(imageIndex: Int): String = "$EMBEDDED_PREFIX$imageIndex"

    /** Índice da imagem embutida num id "embedded:<i>" (null para outros ids). */
    fun parseEmbeddedImageIndex(id: String): Int? =
        if (id.startsWith(EMBEDDED_PREFIX)) id.removePrefix(EMBEDDED_PREFIX).toIntOrNull()
        else null

    /** Informação mínima de material para construir o catálogo (sem tipos glTF). */
    data class MaterialInfo(
        val name: String,
        val textureImageIndex: Int?
    )

    /**
     * Constrói o catálogo determinístico:
     *  1. a textura do projeto (sempre presente — é a única editável);
     *  2. por ordem crescente de índice, cada imagem embutida usada por ≥ 1
     *     material com binding EMBEDDED.
     *
     * @param embeddedSizes dimensões (w, h) das imagens embutidas por índice —
     *        imagens sem dimensão conhecida ficam com 0×0 (nunca bloqueia).
     */
    fun build(
        documentName: String,
        documentWidth: Int,
        documentHeight: Int,
        materials: List<MaterialInfo>,
        bindings: Map<Int, Studio3DState.TextureRef>,
        embeddedSizes: Map<Int, Pair<Int, Int>>
    ): List<TextureEntry> {
        val entries = ArrayList<TextureEntry>()

        // 1. Textura do projeto — usada por TODOS os materiais com DOCUMENT.
        val documentUsers = bindings
            .filterValues { it == Studio3DState.TextureRef.DOCUMENT }
            .keys.sorted()
        entries.add(
            TextureEntry(
                id = DOCUMENT_ID,
                kind = TextureEntry.Kind.DOCUMENT,
                name = documentName,
                width = documentWidth,
                height = documentHeight,
                usedByMaterials = documentUsers
            )
        )

        // 2. Imagens embutidas realmente usadas (ordem estável por índice).
        val embeddedUsers = LinkedHashMap<Int, MutableList<Int>>()
        materials.forEachIndexed { materialIndex, material ->
            val imageIndex = material.textureImageIndex ?: return@forEachIndexed
            if (bindings[materialIndex] != Studio3DState.TextureRef.EMBEDDED) return@forEachIndexed
            embeddedUsers.getOrPut(imageIndex) { ArrayList() }.add(materialIndex)
        }
        embeddedUsers.keys.sorted().forEach { imageIndex ->
            val size = embeddedSizes[imageIndex]
            val users = embeddedUsers[imageIndex]!!.sorted()
            val firstUser = users.firstOrNull()
            val label = when {
                firstUser != null -> materials[firstUser].name
                else -> "embutida $imageIndex"
            }
            entries.add(
                TextureEntry(
                    id = embeddedId(imageIndex),
                    kind = TextureEntry.Kind.EMBEDDED,
                    name = label,
                    width = size?.first ?: 0,
                    height = size?.second ?: 0,
                    usedByMaterials = users
                )
            )
        }
        return entries
    }
}
