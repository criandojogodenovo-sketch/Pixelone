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
 * O cubo tem UVs completas por face (a textura do Texture Studio aparece já
 * aplicada ao abrir). O chão é um material sólido sem textura, para provar
 * materiais "sem textura" também no demo.
 */
object DemoGeometry {

    const val MATERIAL_TEXTURED: Int = 0
    const val MATERIAL_GROUND: Int = 1

    /**
     * Constrói o modelo de demonstração: cubo (±0,5 em XZ, 0..1 em Y) sobre
     * um plano-chão de 3×3 em Y=0. Total: 48+4 vértices, 36+6 índices.
     */
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
                baseColorFactor = floatArrayOf(0.18f, 0.18f, 0.18f, 1f),
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

    /** Cubo com 24 vértices (4 por face, UVs 0..1) e 36 índices. */
    private fun cubeRenderable(): Renderable {
        // faces: +X, -X, +Y, -Y, +Z, -Z (winding CCW visto de fora)
        val positions = FloatArray(24 * 3)
        val uvs = FloatArray(24 * 2)
        val indices = IntArray(36)

        data class Face(val normal: FloatArray, val tangent: FloatArray, val bitangent: FloatArray, val origin: FloatArray)

        val faces = listOf(
            Face(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0.5f, 0f, 0.5f)),
            Face(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), floatArrayOf(-0.5f, 0f, 0.5f)),
            Face(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f), floatArrayOf(-0.5f, 1f, 0.5f)),
            Face(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(-0.5f, 0f, 0.5f)),
            Face(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(-0.5f, 0f, 0.5f)),
            Face(floatArrayOf(0f, 0f, -1f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0.5f, 0f, 0.5f))
        )

        var vi = 0
        var ii = 0
        for ((faceIndex, f) in faces.withIndex()) {
            for (corner in 0 until 4) {
                val u = if (corner == 0 || corner == 3) 0f else 1f
                val v = if (corner < 2) 0f else 1f
                // posição = origin + normal*0.5 + tangent*(u-0.5) + bitangent*(v-0.5)
                positions[vi * 3] = f.origin[0] + f.normal[0] * 0.5f + f.tangent[0] * (u - 0.5f) + f.bitangent[0] * (v - 0.5f)
                positions[vi * 3 + 1] = f.origin[1] + f.normal[1] * 0.5f + f.tangent[1] * (u - 0.5f) + f.bitangent[1] * (v - 0.5f)
                positions[vi * 3 + 2] = f.origin[2] + f.normal[2] * 0.5f + f.tangent[2] * (u - 0.5f) + f.bitangent[2] * (v - 0.5f)
                uvs[vi * 2] = u
                uvs[vi * 2 + 1] = v
                vi++
            }
            val base = faceIndex * 4
            // CCW visto de fora; ajustar winding quando necessário por face.
            when (faceIndex) {
                0 -> { // +X: tangente -Z, bitangente +Y → (0,0)=canto (0.5,0,0.5)… verificar com triângulo 0,1,2
                    indices[ii++] = base; indices[ii++] = base + 1; indices[ii++] = base + 3
                    indices[ii++] = base + 1; indices[ii++] = base + 2; indices[ii++] = base + 3
                }
                else -> {
                    indices[ii++] = base; indices[ii++] = base + 1; indices[ii++] = base + 3
                    indices[ii++] = base + 1; indices[ii++] = base + 2; indices[ii++] = base + 3
                }
            }
        }
        // Para simplificar e garantir winding correto sem luz (materiais UNLIT),
        // desativa-se o culling no renderer — ambos os windings ficam visíveis.

        return Renderable(
            meshIndex = 0,
            meshName = "Cubo",
            materialIndex = MATERIAL_TEXTURED,
            positions = positions,
            uvs = uvs,
            indices = indices
        )
    }

    /** Plano-chão 3×3 em Y=0 (dois triângulos, UVs 0..1). */
    private fun groundRenderable(): Renderable {
        val positions = floatArrayOf(
            -1.5f, 0f, 1.5f,
            1.5f, 0f, 1.5f,
            1.5f, 0f, -1.5f,
            -1.5f, 0f, -1.5f
        )
        val uvs = floatArrayOf(
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 1f
        )
        val indices = intArrayOf(0, 2, 1, 0, 3, 2)
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
