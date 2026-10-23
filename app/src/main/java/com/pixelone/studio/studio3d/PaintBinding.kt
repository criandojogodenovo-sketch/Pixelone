package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.Studio3DState.TextureRef

/**
 * MISSÃO TEXTURIZAÇÃO (modelo importado — pintura invisível): planeamento
 * PURO da ligação material → textura necessária para a pintura do projeto
 * ficar VISÍVEL no modelo 3D.
 *
 * CAUSA RAIZ (código real, Studio3DViewModel.importModel): materiais com
 * textura embutida entram com binding EMBEDDED — apresentam a textura
 * original do ficheiro. A pintura edita a textura do PROJETO (document),
 * que não estava ligada a NENHUM material → o utilizador pinta e o modelo
 * 3D continua igual ("eu texturizo e não aparece").
 *
 * REGRA (mesma política da cópia de embutidas — nunca silenciosa):
 * quando o utilizador pinta a textura do projeto, os materiais que
 * APRESENTAM textura embutida passam a apresentar a do projeto (flip
 * EMBEDDED → DOCUMENT), COM mensagem anunciando a mudança. As bitmaps
 * embutidas continuam intactas no catálogo "Texturas do modelo" e podem
 * ser repostas nos chips de material — nada é destruído.
 *
 * 100% Kotlin puro (sem tipos Android) — testável em JVM.
 */
object PaintBinding {

    /** Plano de flips: material → DOCUMENT + nomes (para a mensagem). */
    data class Plan(
        val flips: Map<Int, TextureRef>,
        /** Nomes dos materiais afetados, pela ordem dos índices. */
        val materialNames: List<String>
    ) {
        val isEmpty: Boolean get() = flips.isEmpty()
    }

    /**
     * Devolve os materiais que DEVEM passar a DOCUMENT para a pintura do
     * projeto ficar visível: os ligados EMBEDDED explicitamente E os que
     * usariam EMBEDDED por omissão (sem entrada no mapa mas com textura
     * embutida — mesma regra de fallback de
     * [ModelRenderer bindingOf]/[com.pixelone.studio.studio3d.gl.ModelRenderer]).
     *
     * Materiais NONE ou DOCUMENT ficam como estão (sem textura nenhum
     * flip os esconderia); materiais SEM textura embutida nunca são
     * considerados EMBEDDED.
     */
    fun ensureDocumentVisible(
        materials: List<TextureCatalog.MaterialInfo>,
        bindings: Map<Int, TextureRef>
    ): Plan {
        val flips = LinkedHashMap<Int, TextureRef>()
        val names = ArrayList<String>()
        materials.forEachIndexed { index, material ->
            val currentlyEmbedded = when (bindings[index]) {
                TextureRef.EMBEDDED -> true
                null -> material.textureImageIndex != null // omissão: EMBEDDED se tem textura
                else -> false
            }
            if (currentlyEmbedded) {
                flips[index] = TextureRef.DOCUMENT
                names.add(material.name.ifBlank { "Material $index" })
            }
        }
        return Plan(flips, names)
    }
}
