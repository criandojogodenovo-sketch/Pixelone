package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.GltfParser.Renderable

/**
 * MISSÃO TEXTURIZAÇÃO (desempenho): construção dos segmentos do gabarito UV
 * SEM boxing — prealoca o FloatArray exato e preenche por índice.
 *
 * ANTES (custo medido no código): ArrayList<Float> com boxing — para um
 * modelo de 300 000 triângulos (limite GltfLimits.MAX_TRIANGLES) eram
 * 3,6 M floats encaixotados (~dezenas de MB transitórios + pressão de GC)
 * na thread principal, na primeira recomposição após a importação.
 * AGORA: UM FloatArray de tamanho exato, zero alocações intermédias.
 *
 * 12 floats por triângulo (3 arestas × 2 pontos × 2 coordenadas) — o mesmo
 * formato consumido por TexturePaintView/exportUvTemplateTo.
 * 100% Kotlin puro — testável em JVM.
 */
object UvGuides {

    /** Floats por triângulo (3 arestas × (u0,v0,u1,v1)). */
    private const val FLOATS_PER_TRIANGLE = 12

    /**
     * Segmentos (u0,v0,u1,v1) das arestas UV, filtrados por malha
     * ([meshFilter] null = todas). Primitivas sem UV são ignoradas.
     */
    fun segments(renderables: List<Renderable>, meshFilter: Int?): FloatArray {
        var total = 0
        renderables.forEach { r ->
            if (meshFilter != null && r.meshIndex != meshFilter) return@forEach
            if (r.uvs == null) return@forEach
            total += (r.indices.size / 3) * FLOATS_PER_TRIANGLE
        }
        if (total == 0) return FloatArray(0)
        val out = FloatArray(total)
        var o = 0
        renderables.forEach { r ->
            if (meshFilter != null && r.meshIndex != meshFilter) return@forEach
            val uvs = r.uvs ?: return@forEach
            var i = 0
            while (i + 2 < r.indices.size) {
                val a = r.indices[i]; val b = r.indices[i + 1]; val c = r.indices[i + 2]
                out[o++] = uvs[a * 2]; out[o++] = uvs[a * 2 + 1]
                out[o++] = uvs[b * 2]; out[o++] = uvs[b * 2 + 1]
                out[o++] = uvs[b * 2]; out[o++] = uvs[b * 2 + 1]
                out[o++] = uvs[c * 2]; out[o++] = uvs[c * 2 + 1]
                out[o++] = uvs[c * 2]; out[o++] = uvs[c * 2 + 1]
                out[o++] = uvs[a * 2]; out[o++] = uvs[a * 2 + 1]
                i += 3
            }
        }
        return out
    }
}
