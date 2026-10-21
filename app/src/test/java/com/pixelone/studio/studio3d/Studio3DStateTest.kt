package com.pixelone.studio.studio3d

import com.pixelone.studio.osp.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do estado persistível do 3D Studio (Fase 3): serialização JSON
 * (referências às texturas, materiais e configurações, com versionamento).
 */
class Studio3DStateTest {

    @Test
    fun `roundtrip completo de estado`() {
        val state = Studio3DState(
            modelKind = Studio3DState.ModelKind.IMPORTED,
            modelFileName = "cena.glb",
            modelFileBytes = 123_456L,
            modelSha256 = "abc123",
            modelTriangleCount = 42_000,
            modelMeshCount = 3,
            modelMaterialCount = 4,
            modelHasUv = true,
            bindings = mapOf(0 to Studio3DState.TextureRef.DOCUMENT, 1 to Studio3DState.TextureRef.EMBEDDED),
            sampling = Studio3DState.SamplingMode.LINEAR,
            showUvOverlay = false,
            mode = Studio3DState.StudioMode.SPLIT,
            camera = OrbitCamera.CameraSnapshot(10f, 20f, 3.5f, 1f, 2f, 3f)
        )
        val json = MiniJson.write(state.toJson())
        val parsed = MiniJson.parse(json)
        @Suppress("UNCHECKED_CAST")
        val restored = Studio3DState.fromJson(parsed as Map<String, Any?>)

        assertEquals(state.schemaVersion, restored.schemaVersion)
        assertEquals(Studio3DState.ModelKind.IMPORTED, restored.modelKind)
        assertEquals("cena.glb", restored.modelFileName)
        assertEquals(123_456L, restored.modelFileBytes)
        assertEquals("abc123", restored.modelSha256)
        assertEquals(42_000, restored.modelTriangleCount)
        assertTrue(restored.modelHasUv)
        assertEquals(2, restored.bindings.size)
        assertEquals(Studio3DState.TextureRef.DOCUMENT, restored.bindings[0])
        assertEquals(Studio3DState.TextureRef.EMBEDDED, restored.bindings[1])
        assertEquals(Studio3DState.SamplingMode.LINEAR, restored.sampling)
        assertEquals(false, restored.showUvOverlay)
        assertEquals(Studio3DState.StudioMode.SPLIT, restored.mode)
        assertNotNull(restored.camera)
        assertEquals(10f, restored.camera!!.yawDeg, 1e-4f)
        assertEquals(3.5f, restored.camera!!.distance, 1e-4f)
    }

    @Test
    fun `estado mínimo (omissões) lê com defaults`() {
        val restored = Studio3DState.fromJson(emptyMap())
        assertEquals(Studio3DState.ModelKind.DEMO, restored.modelKind)
        assertEquals(Studio3DState.SamplingMode.NEAREST, restored.sampling)
        assertEquals(Studio3DState.StudioMode.THREE_D, restored.mode)
        assertEquals(true, restored.showUvOverlay)
        assertEquals(emptyMap<Int, Studio3DState.TextureRef>(), restored.bindings)
    }

    @Test
    fun `versão futura da secção é rejeitada com mensagem clara`() {
        try {
            Studio3DState.fromJson(mapOf("schemaVersion" to 99L))
            org.junit.Assert.fail("Deveria rejeitar versão futura")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("versão 99"))
        }
    }
}
