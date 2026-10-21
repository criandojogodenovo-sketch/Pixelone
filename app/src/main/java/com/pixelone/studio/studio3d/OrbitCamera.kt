package com.pixelone.studio.studio3d

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Câmara orbital do 3D Texture Studio — Kotlin puro (testável em JVM).
 *
 * GESTOS (regra do pedido da Fase 3):
 *  - 1 dedo arrasta → RODA a câmara em volta do alvo (yaw/pitch);
 *  - 2 dedos pinça → ZOOM (distância);
 *  - 2 dedos arrastam (após pinça ou pan puro) → DESLOCAMENTO do alvo;
 *  - botão "Repor" → [reset] restaura a pose inicial.
 *
 * SEM APIs Android: a UI só escreve aqui os deltas em píxeis/normalizados e
 * lê a matriz vista (column-major pronta para o shader). Todas as
 * computações são O(1) — a rotação responde em << 100 ms.
 */
class OrbitCamera(
    initialYawDeg: Float = 35f,
    initialPitchDeg: Float = 25f,
    initialDistance: Float = 3.2f,
    initialTarget: Float3 = Float3(0f, 0f, 0f)
) {
    /** Vetor 3 simples (evita dependências externas). */
    data class Float3(var x: Float, var y: Float, var z: Float)

    var yawDeg: Float = initialYawDeg
        private set
    var pitchDeg: Float = initialPitchDeg
        private set
    var distance: Float = initialDistance
        private set
    val target: Float3 = Float3(initialTarget.x, initialTarget.y, initialTarget.z)

    /** Pose inicial — o botão "Repor" volta sempre a ESTES valores. */
    private val homeYaw = initialYawDeg
    private val homePitch = initialPitchDeg
    private val homeDistance = initialDistance
    private val homeTarget = Float3(initialTarget.x, initialTarget.y, initialTarget.z)

    // Limites de segurança: a câmara nunca atravessa o modelo nem vai ao infinito.
    var minDistance: Float = 0.15f
    var maxDistance: Float = 60f
    var minPitchDeg: Float = -89f
    var maxPitchDeg: Float = 89f

    /** Sensibilidade de rotação (graus por píxel de ecrã arrastado). */
    var rotateSensitivity: Float = 0.35f

    /**
     * Sensibilidade de pan — 1.0 = 1:1 com o gesto (arrastar N píxeis move
     * o alvo N × mundo/píxel à profundidade do alvo).
     */
    var panSensitivity: Float = 1f

    // Buffers reutilizáveis — zero alocações por frame no caminho quente.
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    /** Roda a câmara com o delta do gesto de 1 dedo (em píxeis de ecrã). */
    fun rotate(dxPixels: Float, dyPixels: Float) {
        yawDeg = wrapDegrees(yawDeg + dxPixels * rotateSensitivity)
        pitchDeg = (pitchDeg - dyPixels * rotateSensitivity)
            .coerceIn(minPitchDeg, maxPitchDeg)
    }

    /** Zoom por pinça: fator > 1 aproxima, < 1 afasta. */
    fun zoom(factor: Float) {
        require(factor > 0f) { "Fator de zoom tem de ser positivo" }
        distance = (distance / factor).coerceIn(minDistance, maxDistance)
    }

    /**
     * Desloca o alvo no plano do ecrã (gesto de 2 dedos arrastando).
     * viewportHeight em píxeis, para escalar o pan com a perspetiva.
     * Multiplicador 1:1 — arrastar N píxeis move o alvo N * mundo/píxel.
     */
    fun pan(dxPixels: Float, dyPixels: Float, viewportHeight: Int) {
        val h = viewportHeight.coerceAtLeast(1)
        val worldPerPixel = 2f * distance * tan(Math.toRadians(FOV_Y_DEG / 2.0).toFloat()) / h
        // Base da câmara: right e up derivam do yaw/pitch (mesma fórmula do lookAt).
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cx = cos(yaw).toFloat() * cos(pitch).toFloat()
        val cy = sin(pitch).toFloat()
        val cz = sin(yaw).toFloat() * cos(pitch).toFloat()
        // right = normalize(cross(worldUp, zAxis)) com worldUp = +Y
        var rx = -cz; var ry = 0f; var rz = cx
        val rLen = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
        if (rLen > 1e-8f) { rx /= rLen; ry /= rLen; rz /= rLen }
        // up da câmara = cross(z, right)
        val ux = cy * rz - cz * ry
        val uy = cz * rx - cx * rz
        val uz = cx * ry - cy * rx
        val scale = worldPerPixel * panSensitivity
        target.x += (-dxPixels * rx + dyPixels * ux) * scale
        target.y += (-dxPixels * ry + dyPixels * uy) * scale
        target.z += (-dxPixels * rz + dyPixels * uz) * scale
    }

    /** Repor a câmara na pose inicial (botão dedicado). */
    fun reset() {
        yawDeg = homeYaw
        pitchDeg = homePitch
        distance = homeDistance
        target.x = homeTarget.x
        target.y = homeTarget.y
        target.z = homeTarget.z
    }

    /** Posição do olho (eye) derivada de yaw/pitch/distância/alvo. */
    fun eyePosition(): Float3 {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val c = cos(pitch).toFloat()
        return Float3(
            target.x + distance * c * cos(yaw).toFloat(),
            target.y + distance * sin(pitch).toFloat(),
            target.z + distance * c * sin(yaw).toFloat()
        )
    }

    /** Matriz vista (column-major, 16) — recalcada a cada chamada. */
    fun viewMatrix(): FloatArray {
        val eye = eyePosition()
        Mat4.lookAt(
            viewMatrix,
            eye.x, eye.y, eye.z,
            target.x, target.y, target.z,
            0f, 1f, 0f
        )
        return viewMatrix
    }

    /** Matriz proj * view (column-major) pronta para gl_Position. */
    fun viewProjection(aspect: Float, out: FloatArray = vpMatrix): FloatArray {
        Mat4.perspective(projMatrix, Math.toRadians(FOV_Y_DEG.toDouble()).toFloat(), aspect, 0.05f, 500f)
        Mat4.multiply(out, projMatrix, viewMatrix())
        return out
    }

    /** Estado serializável (para o .osp). */
    fun snapshot(): CameraSnapshot =
        CameraSnapshot(yawDeg, pitchDeg, distance, target.x, target.y, target.z)

    fun restore(s: CameraSnapshot) {
        yawDeg = s.yawDeg
        pitchDeg = s.pitchDeg
        distance = s.distance.coerceIn(minDistance, maxDistance)
        target.x = s.targetX; target.y = s.targetY; target.z = s.targetZ
    }

    data class CameraSnapshot(
        val yawDeg: Float,
        val pitchDeg: Float,
        val distance: Float,
        val targetX: Float,
        val targetY: Float,
        val targetZ: Float
    )

    companion object {
        /** Abertura vertical fixa (graus) — igual na projeção e no pan. */
        const val FOV_Y_DEG: Float = 50f

        /** Normaliza ângulos para [0, 360). */
        fun wrapDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d < 0f) d += 360f
            return d
        }
    }
}
