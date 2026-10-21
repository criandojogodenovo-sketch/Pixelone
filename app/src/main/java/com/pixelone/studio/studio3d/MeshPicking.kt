package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.GltfParser.GltfModel
import kotlin.math.tan

/**
 * PICKING por raio do 3D Texture Studio — Kotlin puro (testável em JVM).
 *
 * REQUISITO RONDA A (seleção de face/ilha): "Tocar num ponto do modelo 3D
 * seleciona a face e a ilha UV correspondente (raycast/picking)" — NADA
 * simulado: o toque no viewport é convertido para NDC, gera um raio a partir
 * da MESMA câmara/projeção usada no render ([OrbitCamera].FOV_Y_DEG e aspect
 * do viewport) e testado contra TODOS os triângulos do modelo (Möller–
 * Trumbore, dupla face para tolerar windings inesperados). O hit mais próximo
 * (menor t) devolve a primitiva + o índice do TRIÂNGULO tocado — a ilha é
 * obtida depois pelo [UvIslands] (os triângulos da ilha contêm o triângulo
 * tocado).
 *
 * Zero alocações no caminho quente fora do vetor direção (chamada por TOQUE,
 * não por frame) — seguro para telemóveis de 4 GB.
 */
object MeshPicking {

    /** Resultado de um toque no modelo 3D. */
    data class PickHit(
        /** Índice da primitiva ([GltfModel.renderables]) tocada. */
        val renderableIndex: Int,
        /** Índice do TRIÂNGULO tocado (0-based no array de índices/3). */
        val triangleIndex: Int,
        /** Distância do olho ao ponto de impacto (unidades do mundo). */
        val distance: Float,
        /** Ponto de impacto no espaço do mundo (x, y, z). */
        val point: FloatArray
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Raio que passa pelo ponto NDC (nx, ny) do viewport com o aspecto dado,
     * usando a pose da câmara orbital. nx ∈ [-1, 1] (esquerda→direita),
     * ny ∈ [-1, 1] (BAIXO→topo, convenção OpenGL — a UI converte o y do toque).
     */
    fun rayFromNdc(camera: OrbitCamera, ndcX: Float, ndcY: Float, aspect: Float): Ray {
        require(aspect > 0f) { "Aspecto inválido: $aspect" }
        val eye = camera.eyePosition()
        // Base da câmara — MESMAS fórmulas de Mat4.lookAt / OrbitCamera.pan.
        val yaw = Math.toRadians(camera.yawDeg.toDouble())
        val pitch = Math.toRadians(camera.pitchDeg.toDouble())
        val cx = kotlin.math.cos(yaw).toFloat() * kotlin.math.cos(pitch).toFloat()
        val cy = kotlin.math.sin(pitch).toFloat()
        val cz = kotlin.math.sin(yaw).toFloat() * kotlin.math.cos(pitch).toFloat()
        // z = normalize(eye - target) (aponta para a câmara)
        val zx = cx; val zy = cy; val zz = cz
        // x = normalize(up × z), up = +Y
        var xx = 1f * zz - 0f * zy
        var xy = 0f * zx - 0f * zz
        var xz = 0f * zy - 1f * zx
        val xLen = kotlin.math.sqrt(xx * xx + xy * xy + xz * xz)
        require(xLen > 1e-8f) { "Câmara degenerada (pitch ±90º com up paralelo)" }
        xx /= xLen; xy /= xLen; xz /= xLen
        // y = z × x
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        // forward = -z
        val fx = -zx; val fy = -zy; val fz = -zz
        val tanHalf = tan(Math.toRadians(OrbitCamera.FOV_Y_DEG.toDouble()) / 2.0).toFloat()
        val rx = ndcX * aspect * tanHalf
        val uy = ndcY * tanHalf
        val dx = fx + xx * rx + yx * uy
        val dy = fy + xy * rx + yy * uy
        val dz = fz + xz * rx + yz * uy
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        return Ray(
            ox = eye.x, oy = eye.y, oz = eye.z,
            dx = dx / len, dy = dy / len, dz = dz / len
        )
    }

    /** Raio (origem + direção normalizada). */
    data class Ray(val ox: Float, val oy: Float, val oz: Float, val dx: Float, val dy: Float, val dz: Float)

    /**
     * Lança [ray] contra TODAS as primitivas do modelo e devolve o hit mais
     * próximo, ou null se o toque não acertou em nada (o chamador limpa a
     * seleção — comportamento previsível).
     */
    fun pick(model: GltfModel, ray: Ray): PickHit? {
        var best: PickHit? = null
        var bestT = Float.POSITIVE_INFINITY
        model.renderables.forEachIndexed { rIndex, r ->
            val pos = r.positions
            var i = 0
            var tri = 0
            while (i + 2 < r.indices.size) {
                val a = r.indices[i] * 3
                val b = r.indices[i + 1] * 3
                val c = r.indices[i + 2] * 3
                val t = rayTriangleT(
                    ray,
                    pos[a], pos[a + 1], pos[a + 2],
                    pos[b], pos[b + 1], pos[b + 2],
                    pos[c], pos[c + 1], pos[c + 2]
                )
                if (t != null && t < bestT) {
                    bestT = t
                    best = PickHit(
                        renderableIndex = rIndex,
                        triangleIndex = tri,
                        distance = t,
                        point = floatArrayOf(
                            ray.ox + ray.dx * t, ray.oy + ray.dy * t, ray.oz + ray.dz * t
                        )
                    )
                }
                i += 3
                tri++
            }
        }
        return best
    }

    /**
     * Möller–Trumbore: devolve t (distância ao plano) se o raio intersecta o
     * triângulo, ou null. Dupla face (aceita ambas as orientações) — o picking
     * não depende do winding, o render sim.
     */
    fun rayTriangleT(
        ray: Ray,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float
    ): Float? {
        // h = dir × (b - a)
        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        val hx = ray.dy * e2z - ray.dz * e2y
        val hy = ray.dz * e2x - ray.dx * e2z
        val hz = ray.dx * e2y - ray.dy * e2x
        val det = e1x * hx + e1y * hy + e1z * hz
        if (det > -1e-9f && det < 1e-9f) return null // paralelo
        val invDet = 1f / det
        val sx = ray.ox - ax; val sy = ray.oy - ay; val sz = ray.oz - az
        val u = (sx * hx + sy * hy + sz * hz) * invDet
        if (u < 0f || u > 1f) return null
        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x
        val v = (ray.dx * qx + ray.dy * qy + ray.dz * qz) * invDet
        if (v < 0f || u + v > 1f) return null
        val t = (e2x * qx + e2y * qy + e2z * qz) * invDet
        return if (t > 1e-6f) t else null
    }
}
