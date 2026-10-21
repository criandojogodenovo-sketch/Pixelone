package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.GltfParser.AlphaMode
import com.pixelone.studio.studio3d.GltfParser.GltfModel
import com.pixelone.studio.studio3d.GltfParser.Material3D
import com.pixelone.studio.studio3d.GltfParser.Renderable

/**
 * Geometria de demonstração do 3D Texture Studio — Kotlin puro.
 *
 * REQUISITO FASE 3: "Geometria de demonstração visível logo ao abrir o
 * estúdio, sem depender de ficheiros externos." O modelo demo é construído
 * EM CÓDIGO (cubo texturizado + chão) e segue exatamente o mesmo caminho de
 * renderização de um modelo importado ([GltfModel]) — uma só via de código,
 * nada simulado.
 *
 * CORREÇÃO RONDA A (problemas B, C, D, E):
 *  - CUBO FECHADO: todas as faces partem do CENTRO do cubo (0, 0.5, 0) e são
 *    empurradas meio-troço pelo normal — antes, origins por face somavam um
 *    offset duplicado e as 6 faces ficavam desligadas (peças soltas, sem
 *    fundo). Agora: x∈[-0.5,0.5], y∈[0,1], z∈[-0.5,0.5], winding CCW visto de
 *    fora (verificado por produto vetorial nos testes) — sólido de qualquer
 *    ângulo com GL_CULL_FACE + depth test.
 *  - LAYOUT UV SEM SOBREPOSIÇÃO: cada face do cubo ocupa a sua própria célula
 *    numa grelha 3×2 (dois terços superiores da textura) e o chão ocupa a
 *    faixa inferior (v de 2/3 a 1). As 7 ilhas (6 faces + chão) têm caixas
 *    disjuntas — na vista UV o cubo aparece como 6 ilhas separadas e cada
 *    face amostra a REGIÃO CORRETA da textura (não a textura inteira).
 *  - CHÃO COM COR PRÓPRIA: a cor sólida (0.35) é distinta das células do
 *    xadrez (0x2E/0x1A) — antes era 0.18 == 0x2E, exatamente a célula clara,
 *    pelo que trocar o material do chão não se via. Agora Textura do projeto /
 *    Embutida / Sem textura produzem mudanças visíveis e imediatas.
 */
object DemoGeometry {

    const val MATERIAL_TEXTURED: Int = 0
    const val MATERIAL_GROUND: Int = 1

    // ------------------------------------------------------------------
    // Layout UV (regra: cada malha/ilha na sua própria região, sem
    // sobreposição — requisito da Ronda A, problema D)
    // ------------------------------------------------------------------

    /** Grelha UV do cubo: 3 colunas × 2 linhas (6 faces). */
    const val CUBE_UV_COLS: Int = 3
    const val CUBE_UV_ROWS: Int = 2

    /** Primeira linha de v (topo) onde começam as células do cubo. */
    const val CUBE_UV_V0: Float = 0f

    /** Altura de cada célula do cubo (1/3 da textura). */
    const val CUBE_UV_CELL_V: Float = 1f / 3f

    /** v onde começa a ilha do chão (faixa inferior da textura). */
    const val GROUND_UV_V0: Float = CUBE_UV_V0 + CUBE_UV_ROWS * CUBE_UV_CELL_V // 2/3

    /** Margem (em UV) entre ilhas — evita contato/sangramento e separa-as na vista UV. */
    const val ISLAND_MARGIN: Float = 0.015f

    /** Constrói o modelo de demonstração: cubo (±0,5 em XZ, 0..1 em Y) sobre
     * um plano-chão de 3×3 em Y=0. Total: 24+4 vértices, 36+6 índices. */
    fun createModel(): GltfModel {
        val materials = listOf(
            Material3D(
                name = "Textura do projeto",
                baseColorFactor = floatArrayOf(1f, 1f, 1f, 1f),
                alphaMode = AlphaMode.OPAQUE,
                alphaCutoff = 0.5f,
                textureImageIndex = null // sem imagem embutida: liga-se à textura do documento
            ),
            Material3D(
                name = "Chão",
                // RONDA A (E): cor distinta do xadrez (0x2E/0x1A ≈ 0.11/0.18)
                // para a mudança de material ser visível e imediata.
                baseColorFactor = floatArrayOf(0.35f, 0.35f, 0.35f, 1f),
                alphaMode = AlphaMode.OPAQUE,
                alphaCutoff = 0.5f,
                textureImageIndex = null
            )
        )

        val cube = cubeRenderable()
        val ground = groundRenderable()
        val renderables = listOf(cube, ground)

        val boundsMin = floatArrayOf(-1.5f, 0f, -1.5f)
        val boundsMax = floatArrayOf(1.5f, 1f, 1.5f)

        return GltfModel(
            renderables = renderables,
            materials = materials,
            images = emptyList(),
            meshNames = listOf("Cubo", "Chão"),
            hasUv = true,
            triangleCount = (cube.indices.size + ground.indices.size) / 3,
            boundsMin = boundsMin,
            boundsMax = boundsMax,
            skippedNonTrianglePrimitives = 0
        )
    }

    /** Textura padrão do demo (64×64): xadrez escuro com marca laranja Pixel One. */
    fun createDefaultTexture(width: Int = 64, height: Int = 64): IntArray {
        require(width in 1..256 && height in 1..256) { "Textura demo fora do tamanho suportado" }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val checker = ((x / 8) + (y / 8)) % 2 == 0
                pixels[y * width + x] = if (checker) 0xFF2E2E2E.toInt() else 0xFF1A1A1A.toInt()
            }
        }
        // "P1" simplificado em laranja — identidade do Pixel One (com proteção
        // de limites: funciona em qualquer dimensão suportada).
        val orange = 0xFFF57C00.toInt()
        fun set(x: Int, y: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = orange
        }
        for (y in 8 until 20) {
            for (x in 10 until 14) set(x, y)  // haste do P
            for (x in 18 until 22) set(x, y)  // haste do 1
        }
        for (x in 10 until 17) { set(x, 8); set(x, 12) }
        for (x in 8 until 24) { set(x, 24); set(x, 28) }
        for (y in 24 until 29) {
            for (x in 8 until 12) set(x, y)
            for (x in 20 until 24) set(x, y)
        }
        return pixels
    }

    // ------------------------------------------------------------------
    // Geometria
    // ------------------------------------------------------------------

    /**
     * Retângulo UV da face [faceIndex] na grelha 3×2 do cubo, com margem.
     * Devolve (u0, v0, u1, v1) — v cresce para baixo (convenção glTF, igual à
     * vista UV do estúdio).
     */
    fun cubeFaceUvRect(faceIndex: Int): FloatArray {
        val col = faceIndex % CUBE_UV_COLS
        val row = faceIndex / CUBE_UV_COLS
        val u0 = col / CUBE_UV_COLS.toFloat() + ISLAND_MARGIN
        val u1 = (col + 1) / CUBE_UV_COLS.toFloat() - ISLAND_MARGIN
        val v0 = CUBE_UV_V0 + row * CUBE_UV_CELL_V + ISLAND_MARGIN
        val v1 = CUBE_UV_V0 + (row + 1) * CUBE_UV_CELL_V - ISLAND_MARGIN
        return floatArrayOf(u0, v0, u1, v1)
    }

    /** Retângulo UV da ilha do chão (faixa inferior, largura total). */
    fun groundUvRect(): FloatArray =
        floatArrayOf(ISLAND_MARGIN, GROUND_UV_V0 + ISLAND_MARGIN, 1f - ISLAND_MARGIN, 1f - ISLAND_MARGIN)

    /**
     * Cubo com 24 vértices (4 por face), 36 índices (12 triângulos) e UVs por
     * ilha (1 face = 1 ilha na grelha 3×2).
     *
     * RONDA A (B): todas as faces partem do CENTRO do cubo (0, 0.5, 0):
     * posição = centro + normal×0.5 + tangente×(cu−0.5) + bitangente×(cv−0.5).
     * Os 6 normais/tangentes/bitangentes abaixo foram verificados a produzir
     * winding CCW visto de FORA (produto vetorial aresta1×aresta2 aponta para
     * o exterior) — o culling GL_BACK mantém o cubo sólido de qualquer ângulo.
     */
    private fun cubeRenderable(): Renderable {
        // faces: +X, -X, +Y, -Y, +Z, -Z
        val positions = FloatArray(24 * 3)
        val uvs = FloatArray(24 * 2)
        val indices = IntArray(36)

        data class Face(val normal: FloatArray, val tangent: FloatArray, val bitangent: FloatArray)

        val faces = listOf(
            Face(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f)),
            Face(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f)),
            Face(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f)),
            Face(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f)),
            Face(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
            Face(floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        )

        // Centro do cubo (±0.5 em XZ, 0..1 em Y).
        val cx = 0f; val cy = 0.5f; val cz = 0f

        var vi = 0
        var ii = 0
        for ((faceIndex, f) in faces.withIndex()) {
            val rect = cubeFaceUvRect(faceIndex)
            for (corner in 0 until 4) {
                val cu = if (corner == 0 || corner == 3) 0f else 1f
                val cv = if (corner < 2) 0f else 1f
                positions[vi * 3] = cx + f.normal[0] * 0.5f + f.tangent[0] * (cu - 0.5f) + f.bitangent[0] * (cv - 0.5f)
                positions[vi * 3 + 1] = cy + f.normal[1] * 0.5f + f.tangent[1] * (cu - 0.5f) + f.bitangent[1] * (cv - 0.5f)
                positions[vi * 3 + 2] = cz + f.normal[2] * 0.5f + f.tangent[2] * (cu - 0.5f) + f.bitangent[2] * (cv - 0.5f)
                // UV global: célula da grelha com margem (ilha própria por face).
                uvs[vi * 2] = rect[0] + cu * (rect[2] - rect[0])
                uvs[vi * 2 + 1] = rect[1] + cv * (rect[3] - rect[1])
                vi++
            }
            val base = faceIndex * 4
            // CCW visto de fora (verificado por teste para TODAS as faces).
            indices[ii++] = base; indices[ii++] = base + 1; indices[ii++] = base + 3
            indices[ii++] = base + 1; indices[ii++] = base + 2; indices[ii++] = base + 3
        }

        return Renderable(
            meshIndex = 0,
            meshName = "Cubo",
            materialIndex = MATERIAL_TEXTURED,
            positions = positions,
            uvs = uvs,
            indices = indices
        )
    }

    /** Plano-chão 3×3 em Y=0 (dois triângulos, ilha própria na faixa inferior). */
    private fun groundRenderable(): Renderable {
        val positions = floatArrayOf(
            -1.5f, 0f, 1.5f,
            1.5f, 0f, 1.5f,
            1.5f, 0f, -1.5f,
            -1.5f, 0f, -1.5f
        )
        val rect = groundUvRect()
        val u0 = rect[0]; val v0 = rect[1]; val u1 = rect[2]; val v1 = rect[3]
        val uvs = floatArrayOf(
            u0, v0,
            u1, v0,
            u1, v1,
            u0, v1
        )
        // RONDA A (B): winding CCW visto de CIMA (normal +Y). Os índices antigos
        // (0,2,1)(0,3,2) davam normal −Y — com GL_CULL_FACE(BACK) o chão era
        // cortado visto de cima (o "sem fundo" do APK). Verificado por teste.
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        return Renderable(
            meshIndex = 1,
            meshName = "Chão",
            materialIndex = MATERIAL_GROUND,
            positions = positions,
            uvs = uvs,
            indices = indices
        )
    }
}
