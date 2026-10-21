package com.pixelone.studio.studio3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Testes da matemática 3D (Fase 3): Mat4 (identidade, TRS com escala por
 * coluna, lookAt, perspetiva) e OrbitCamera (rotação 1 dedo, zoom pinça,
 * pan 2 dedos, reset, clamp de limites). Kotlin puro.
 */
class OrbitCameraMat4Test {

    // ------------------------------------------------------------------
    // Mat4
    // ------------------------------------------------------------------

    @Test
    fun `identidade não altera pontos`() {
        val m = Mat4.identity(FloatArray(16))
        val p = Mat4.transformPoint(m, 3f, -2f, 7f)
        assertEquals(3f, p[0], 1e-6f)
        assertEquals(-2f, p[1], 1e-6f)
        assertEquals(7f, p[2], 1e-6f)
    }

    @Test
    fun `multiplicação corresponde à composição esperada`() {
        // T(1,0,0) * T(0,2,0) = T(1,2,0)
        val a = Mat4.trs(FloatArray(16), 1f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        val b = Mat4.trs(FloatArray(16), 0f, 2f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        val ab = Mat4.multiply(FloatArray(16), a, b)
        val p = Mat4.transformPoint(ab, 0f, 0f, 0f)
        assertEquals(1f, p[0], 1e-6f)
        assertEquals(2f, p[1], 1e-6f)
        assertEquals(0f, p[2], 1e-6f)
    }

    @Test
    fun `trs aplica escala por coluna (não uniforme correto)`() {
        // Escala (2, 3, 4) sem rotação: coluna j multiplicada por s_j.
        val m = Mat4.trs(FloatArray(16), 0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f)
        val p = Mat4.transformPoint(m, 1f, 1f, 1f)
        assertEquals(2f, p[0], 1e-6f)
        assertEquals(3f, p[1], 1e-6f)
        assertEquals(4f, p[2], 1e-6f)
    }

    @Test
    fun `trs com quaternião de 90 graus em Z roda corretamente`() {
        val s = Math.sqrt(0.5).toFloat()
        val m = Mat4.trs(FloatArray(16), 0f, 0f, 0f, 0f, 0f, s, s, 1f, 1f, 1f)
        val p = Mat4.transformPoint(m, 1f, 0f, 0f)
        assertEquals(0f, p[0], 1e-5f)
        assertEquals(1f, p[1], 1e-5f)
    }

    @Test
    fun `lookAt na origem olhando -Z dá vista identidade`() {
        val v = Mat4.lookAt(FloatArray(16), 0f, 0f, 5f, 0f, 0f, 0f, 0f, 1f, 0f)
        val p = Mat4.transformPoint(v, 0f, 0f, 0f) // alvo vai à origem
        assertEquals(0f, p[0], 1e-5f)
        assertEquals(0f, p[1], 1e-5f)
        assertEquals(-5f, p[2], 1e-5f) // a 5 unidades, no -Z da câmara
    }

    @Test
    fun `perspetiva mapeia o plano near corretamente`() {
        val proj = Mat4.perspective(FloatArray(16), Math.toRadians(90.0).toFloat(), 1f, 1f, 100f)
        // Ponto no near plane (z = -1), x = 1 (frustum 90° → borda em x=±1 em z=-1)
        // clip: x_clip = 1, w = 1 → NDC x = 1 (borda direita).
        val xClip = proj[Mat4.M00] * 1f + proj[Mat4.M02] * -1f
        val wClip = proj[Mat4.M32] * -1f
        val ndc = xClip / wClip
        assertEquals(1f, ndc, 1e-4f)
    }

    // ------------------------------------------------------------------
    // OrbitCamera
    // ------------------------------------------------------------------

    @Test
    fun `rotação com 1 dedo altera yaw e pitch`() {
        val cam = OrbitCamera()
        val yaw0 = cam.yawDeg
        val pitch0 = cam.pitchDeg
        cam.rotate(100f, 50f)
        assertTrue(abs(cam.yawDeg - yaw0) > 10f)
        assertTrue(abs(cam.pitchDeg - pitch0) > 10f)
    }

    @Test
    fun `pitch é limitado a mais menos 89 graus`() {
        val cam = OrbitCamera()
        cam.rotate(0f, 100000f)
        assertTrue(cam.pitchDeg >= cam.minPitchDeg)
        cam.rotate(0f, -100000f)
        assertTrue(cam.pitchDeg <= cam.maxPitchDeg)
    }

    @Test
    fun `zoom por pinça aproxima e respeita o mínimo`() {
        val cam = OrbitCamera(initialDistance = 3f)
        cam.zoom(2f) // aproxima pela metade
        assertEquals(1.5f, cam.distance, 1e-4f)
        cam.zoom(1000f)
        assertEquals(cam.minDistance, cam.distance, 1e-4f)
        cam.zoom(0.0001f)
        assertTrue(cam.distance <= cam.maxDistance)
    }

    @Test
    fun `pan com 2 dedos move o alvo no plano da câmara`() {
        val cam = OrbitCamera()
        val tx = cam.target.x
        val ty = cam.target.y
        cam.pan(-100f, 0f, 800) // arrastar para a direita (conteúdo segue o dedo)
        assertTrue(abs(cam.target.x - tx) > 0.01f)
        cam.pan(0f, 100f, 800)
        assertTrue(abs(cam.target.y - ty) > 0.01f)
    }

    @Test
    fun `reset volta exatamente à pose inicial`() {
        val cam = OrbitCamera(
            initialYawDeg = 40f, initialPitchDeg = 30f, initialDistance = 4f,
            initialTarget = OrbitCamera.Float3(1f, 2f, 3f)
        )
        cam.rotate(500f, 500f)
        cam.zoom(10f)
        cam.pan(300f, 300f, 800)
        cam.reset()
        assertEquals(40f, cam.yawDeg, 1e-4f)
        assertEquals(30f, cam.pitchDeg, 1e-4f)
        assertEquals(4f, cam.distance, 1e-4f)
        assertEquals(1f, cam.target.x, 1e-4f)
        assertEquals(2f, cam.target.y, 1e-4f)
        assertEquals(3f, cam.target.z, 1e-4f)
    }

    @Test
    fun `snapshot e restore fazem roundtrip`() {
        val cam = OrbitCamera()
        cam.rotate(30f, -20f)
        cam.zoom(1.7f)
        cam.pan(40f, -10f, 720)
        val snap = cam.snapshot()
        val other = OrbitCamera()
        other.restore(snap)
        assertEquals(cam.yawDeg, other.yawDeg, 1e-4f)
        assertEquals(cam.pitchDeg, other.pitchDeg, 1e-4f)
        assertEquals(cam.distance, other.distance, 1e-4f)
        assertEquals(cam.target.x, other.target.x, 1e-4f)
    }

    @Test
    fun `viewProjection devolve matriz finita com aspecto`() {
        val cam = OrbitCamera()
        val m = cam.viewProjection(1.5f, FloatArray(16))
        for (value in m) {
            assertTrue(!value.isNaN() && !value.isInfinite())
        }
    }
}
