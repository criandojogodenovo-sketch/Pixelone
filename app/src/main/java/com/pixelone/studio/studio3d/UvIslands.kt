package com.pixelone.studio.studio3d

/**
 * Cálculo de ILHAS UV do 3D Texture Studio — Kotlin puro (testável em JVM).
 *
 * Uma ilha UV é um grupo de triângulos ligados por vértices com a MESMA
 * coordenada UV (soldadura por quantização — vértices duplicados na malha,
 * prática normal em glTF, voltam a ser o mesmo ponto UV).
 *
 * USOS (Fase 3):
 *  - Vista UV: desenhar contornos das ilhas sobre a textura;
 *  - Toque na vista UV: [hitTest] descobre a ilha tocada;
 *  - Seleção: a ilha escolhida destaca-se NA VISTA UV e NO MODELO 3D
 *    (os triângulos da ilha são redesenhados com realce);
 *  - Modelos sem UV: [compute] devolve lista vazia e a UI bloqueia os
 *    modos UV e Dividido com explicação.
 */
object UvIslands {

    /** Épsilon de soldadura em unidades UV (menor que meio píxel a 4k). */
    const val WELD_EPSILON: Float = 1e-5f

    /** Uma ilha: índices de TRIÂNGULO (id do triângulo no array de índices) + limites UV. */
    class UvIsland(
        val id: Int,
        val triangleIndices: IntArray,
        val minU: Float,
        val minV: Float,
        val maxU: Float,
        val maxV: Float
    ) {
        /** Número de TRIÂNGULOS da ilha (triangleIndices guarda ids de triângulo). */
        val triangleCount: Int get() = triangleIndices.size
    }

    /**
     * Calcula as ilhas de UMA primitiva (índices + UVs emparelhados por vértice).
     * Devolve ilhas ordenadas por primeiro triângulo (determinístico — essencial
     * para a persistência da seleção e para os testes).
     */
    fun compute(indices: IntArray, uvs: FloatArray): List<UvIsland> {
        require(indices.size % 3 == 0) { "Índices não são múltiplo de 3" }
        if (indices.isEmpty()) return emptyList()
        val vertexCount = indices.max() + 1
        require(uvs.size >= vertexCount * 2) {
            "UVs (${uvs.size / 2}) insuficientes para os vértices ($vertexCount)"
        }

        // 1) Union-find sobre vértices soldados pela coordenada UV quantizada.
        val parent = IntArray(vertexCount) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) x = parent[x]
            // Compressão de caminho (simples, segundo passe).
            var y = a
            while (parent[y] != x) { val next = parent[y]; parent[y] = x; y = next }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        // Quantização: chave = (round(u/eps), round(v/eps)) em Long.
        val buckets = HashMap<Long, Int>(vertexCount)
        for (v in 0 until vertexCount) {
            val key = quantize(uvs[v * 2], uvs[v * 2 + 1])
            val existing = buckets[key]
            if (existing == null) buckets[key] = v else union(existing, v)
        }

        // 2) Triângulos ligados se partilham um vértice soldado.
        val triangleCount = indices.size / 3
        val triParent = IntArray(triangleCount) { it }
        fun findTri(a: Int): Int {
            var x = a
            while (triParent[x] != x) x = triParent[x]
            var y = a
            while (triParent[y] != x) { val next = triParent[y]; triParent[y] = x; y = next }
            return x
        }
        fun unionTri(a: Int, b: Int) {
            val ra = findTri(a); val rb = findTri(b)
            if (ra != rb) triParent[ra] = rb
        }
        val vertexOwner = HashMap<Int, Int>(vertexCount) // raiz soldada -> primeiro triângulo
        for (t in 0 until triangleCount) {
            for (k in 0 until 3) {
                val weldedRoot = find(indices[t * 3 + k])
                val owner = vertexOwner[weldedRoot]
                if (owner == null) vertexOwner[weldedRoot] = t else unionTri(owner, t)
            }
        }

        // 3) Agrupar triângulos por raiz e calcular limites UV.
        data class Group(val triangles: MutableList<Int>)

        val groups = LinkedHashMap<Int, Group>()
        val bounds = HashMap<Int, FloatArray>()
        for (t in 0 until triangleCount) {
            val root = findTri(t)
            val g = groups.getOrPut(root) { Group(ArrayList()) }
            g.triangles.add(t)
            val b = bounds.getOrPut(root) {
                floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            }
            for (k in 0 until 3) {
                val u = uvs[indices[t * 3 + k] * 2]
                val v = uvs[indices[t * 3 + k] * 2 + 1]
                if (u < b[0]) b[0] = u
                if (v < b[1]) b[1] = v
                if (u > b[2]) b[2] = u
                if (v > b[3]) b[3] = v
            }
        }

        // 4) Determinismo: ordenar por primeiro índice de triângulo.
        return groups.entries
            .sortedBy { it.value.triangles.first() }
            .mapIndexed { id, entry ->
                val b = bounds.getValue(entry.key)
                UvIsland(id, entry.value.triangles.toIntArray(), b[0], b[1], b[2], b[3])
            }
    }

    /**
     * Descobre a ilha que contém o ponto UV (u, v) — toque na vista UV.
     * Testa ponto-em-triângulo (com pequena tolerância de borda) apenas nos
     * triângulos das ilhas cuja caixa contém o ponto. Devolve null se nenhum.
     */
    fun hitTest(islands: List<UvIsland>, indices: IntArray, uvs: FloatArray, u: Float, v: Float): UvIsland? {
        for (island in islands) {
            if (u < island.minU - EDGE_TOLERANCE || u > island.maxU + EDGE_TOLERANCE ||
                v < island.minV - EDGE_TOLERANCE || v > island.maxV + EDGE_TOLERANCE
            ) continue
            for (t in island.triangleIndices) {
                val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
                val au = uvs[a * 2]; val av = uvs[a * 2 + 1]
                val bu = uvs[b * 2]; val bv = uvs[b * 2 + 1]
                val cu = uvs[c * 2]; val cv = uvs[c * 2 + 1]
                if (pointInTriangle(u, v, au, av, bu, bv, cu, cv)) return island
            }
        }
        return null
    }

    /** Chave quantizada (u, v) para soldadura — estável e determinística. */
    private fun quantize(u: Float, v: Float): Long {
        val qu = Math.round(u / WELD_EPSILON)
        val qv = Math.round(v / WELD_EPSILON)
        return (qu.toLong() shl 32) or (qv.toLong() and 0xFFFFFFFFL)
    }

    /** Ponto em triângulo via sinais das arestas, com tolerância de borda. */
    private fun pointInTriangle(
        px: Float, py: Float,
        ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float
    ): Boolean {
        val d1 = cross(px, py, ax, ay, bx, by)
        val d2 = cross(px, py, bx, by, cx, cy)
        val d3 = cross(px, py, cx, cy, ax, ay)
        val hasNeg = d1 < -EDGE_TOLERANCE || d2 < -EDGE_TOLERANCE || d3 < -EDGE_TOLERANCE
        val hasPos = d1 > EDGE_TOLERANCE || d2 > EDGE_TOLERANCE || d3 > EDGE_TOLERANCE
        return !(hasNeg && hasPos)
    }

    private fun cross(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float
    ): Float = (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    private const val EDGE_TOLERANCE = 1e-4f
}
