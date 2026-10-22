package com.pixelone.studio.studio3d

import com.pixelone.studio.studio3d.GltfParser.AlphaMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Testes do importador glTF/GLB (Fase 3): carregamento e validação de
 * modelos suportados, deteção de materiais e UV, associação inicial de
 * texturas e rejeição de recursos inválidos ou demasiado grandes.
 *
 * Os ficheiros GLB são construídos PROGRAMATICAMENTE (sem fixtures externos)
 * — cada teste controla exatamente os bytes que o parser lê.
 */
class GltfParserTest {

    // ------------------------------------------------------------------
    // Construtor de GLB (magia, versão, chunks com padding correto)
    // ------------------------------------------------------------------

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun le16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()
    )

    private fun leFloat(value: Float): ByteArray = le32(java.lang.Float.floatToIntBits(value))

    private fun buildGlb(json: String, bin: ByteArray? = null): ByteArray {
        var jsonBytes = json.toByteArray(Charsets.UTF_8)
        if (jsonBytes.size % 4 != 0) {
            jsonBytes += ByteArray(4 - jsonBytes.size % 4) { 0x20 } // espaços (spec)
        }
        val binPadded = bin?.let {
            if (it.size % 4 == 0) it else it + ByteArray(4 - it.size % 4)
        }
        val total = 12 + 8 + jsonBytes.size + (binPadded?.let { 8 + it.size } ?: 0)
        val out = ByteArrayOutputStream()
        out.write(le32(0x46546C67)) // "glTF"
        out.write(le32(2))          // versão
        out.write(le32(total))
        out.write(le32(jsonBytes.size))
        out.write(le32(0x4E4F534A)) // "JSON"
        out.write(jsonBytes)
        if (binPadded != null) {
            out.write(le32(binPadded.size))
            out.write(le32(0x004E4942)) // "BIN\0"
            out.write(binPadded)
        }
        return out.toByteArray()
    }

    /**
     * Quad com UVs e índices + material com cor base e textura (imagem 2×2
     * PNG mínima não é necessária — os bytes da imagem não são descodificados
     * aqui; o parser só extrai e valida).
     */
    private fun quadGlb(withUv: Boolean = true): ByteArray {
        val positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            1f, 1f, 0f,
            0f, 1f, 0f
        )
        val uvs = floatArrayOf(
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 1f
        )
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // assinatura PNG parcial

        val bin = ByteArrayOutputStream()
        positions.forEach { bin.write(leFloat(it)) }
        if (withUv) uvs.forEach { bin.write(leFloat(it)) }
        indices.forEach { bin.write(le16(it)) }
        bin.write(imageBytes)

        val uvAttribute = if (withUv) ",\"TEXCOORD_0\": 1" else ""
        val uvAccessor = if (withUv) "{\"bufferView\": 1, \"componentType\": 5126, \"count\": 4, \"type\": \"VEC2\"}," else ""
        val uvView = if (withUv) "{\"buffer\": 0, \"byteOffset\": 48, \"byteLength\": 32}," else ""
        val indicesIndex = if (withUv) 2 else 1
        val indicesViewIndex = if (withUv) 2 else 1
        val indicesOffset = if (withUv) 80 else 48
        // A imagem usa o bufferView SEGUINTE aos das geometrias (com byteLength próprio).
        val imageViewIndex = if (withUv) 3 else 2
        val extraView = if (withUv) {
            "{\"buffer\": 0, \"byteOffset\": 92, \"byteLength\": 4}"
        } else {
            "{\"buffer\": 0, \"byteOffset\": 60, \"byteLength\": 4}"
        }
        val bufferLength = if (withUv) 96 else 64

        val json = """
        {
          "asset": {"version": "2.0"},
          "scene": 0,
          "scenes": [{"nodes": [0]}],
          "nodes": [{"mesh": 0, "translation": [1.0, 2.0, 3.0]}],
          "meshes": [{
            "name": "quad",
            "primitives": [{
              "attributes": {"POSITION": 0$uvAttribute},
              "indices": $indicesIndex,
              "material": 0
            }]
          }],
          "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
            $uvAccessor
            {"bufferView": $indicesViewIndex, "componentType": 5123, "count": 6, "type": "SCALAR"}
          ],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 48},
            $uvView
            {"buffer": 0, "byteOffset": $indicesOffset, "byteLength": 12},
            $extraView
          ],
          "images": [{"bufferView": $imageViewIndex, "mimeType": "image/png"}],
          "textures": [{"source": 0}],
          "materials": [{
            "name": "mat-quad",
            "pbrMetallicRoughness": {
              "baseColorFactor": [1.0, 0.5, 0.0, 1.0],
              "baseColorTexture": {"index": 0}
            },
            "alphaMode": "BLEND"
          }],
          "buffers": [{"byteLength": $bufferLength}]
        }
        """.trimIndent()
        return buildGlb(json, bin.toByteArray())
    }

    // ------------------------------------------------------------------
    // Carregamento feliz
    // ------------------------------------------------------------------

    @Test
    fun `glb com quad parseia posições índices material e imagem`() {
        val model = GltfParser.parse(quadGlb())
        assertEquals(1, model.renderables.size)
        val r = model.renderables[0]
        assertEquals(4, r.positions.size / 3)
        assertEquals(6, r.indices.size)
        // Baking da translação do nó: vértice 0 era (0,0,0) → (1,2,3).
        assertEquals(1f, r.positions[0], 1e-6f)
        assertEquals(2f, r.positions[1], 1e-6f)
        assertEquals(3f, r.positions[2], 1e-6f)
        assertEquals(1, model.materials.size)
        assertEquals("mat-quad", model.materials[0].name)
        assertEquals(AlphaMode.BLEND, model.materials[0].alphaMode)
        assertEquals(0, model.materials[0].textureImageIndex)
        assertEquals(1, model.images.size)
        assertEquals("image/png", model.images[0].mimeType)
        assertEquals(2, model.triangleCount)
        assertTrue(model.hasUv)
        // Caixa envolvente inclui a translação: y do quad (0..1) + 2 → 2..3.
        assertEquals(1f, model.boundsMin[0], 1e-6f)
        assertEquals(3f, model.boundsMax[1], 1e-6f)
    }

    @Test
    fun `uv correspondem aos vértices e quad forma uma ilha`() {
        val model = GltfParser.parse(quadGlb())
        val uvs = model.renderables[0].uvs!!
        assertEquals(0f, uvs[0], 1e-6f)
        assertEquals(1f, uvs[2], 1e-6f)
        val islands = UvIslands.compute(model.renderables[0].indices, uvs)
        assertEquals(1, islands.size) // quad ligado = uma ilha
    }

    @Test
    fun `modelo sem TEXCOORD_0 marca hasUv falso e uvs nulos`() {
        val model = GltfParser.parse(quadGlb(withUv = false))
        assertFalse(model.hasUv)
        assertNull(model.renderables[0].uvs)
        assertEquals(2, model.triangleCount)
    }

    @Test
    fun `modelo sem indices gera sequencial`() {
        val json = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": [0]}],
          "nodes": [{"mesh": 0}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "material": 0}]}],
          "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "materials": [{"name": "m", "pbrMetallicRoughness": {"baseColorFactor": [1,1,1,1]}}],
          "buffers": [{"byteLength": 36}]
        }
        """.trimIndent()
        val bin = ByteArrayOutputStream().apply {
            listOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { write(leFloat(it)) }
        }.toByteArray()
        val model = GltfParser.parse(buildGlb(json, bin))
        assertEquals(3, model.renderables[0].indices.size)
        assertEquals(listOf(0, 1, 2), model.renderables[0].indices.toList())
        assertNull(model.materials[0].textureImageIndex)
    }

    @Test
    fun `gltf autocontido com buffer data uri parseia`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f)
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        val bin = ByteArrayOutputStream().apply {
            positions.forEach { write(leFloat(it)) }
            indices.forEach { write(le16(it)) }
        }.toByteArray()
        val base64 = java.util.Base64.getEncoder().encodeToString(bin)
        val json = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": [0]}],
          "nodes": [{"mesh": 0}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "material": 0}]}],
          "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
            {"bufferView": 1, "componentType": 5123, "count": 6, "type": "SCALAR"}
          ],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 48},
            {"buffer": 0, "byteOffset": 48, "byteLength": 12}
          ],
          "materials": [{"name": "m"}],
          "buffers": [{"byteLength": 60, "uri": "data:application/octet-stream;base64,$base64"}]
        }
        """.trimIndent()
        val bytes = json.toByteArray(Charsets.UTF_8)
        assertFalse(GltfParser.isGlb(bytes))
        val model = GltfParser.parse(bytes)
        assertEquals(2, model.triangleCount)
    }

    // ------------------------------------------------------------------
    // Rejeições com mensagens claras
    // ------------------------------------------------------------------

    private fun assertParseError(bytes: ByteArray, expectedFragment: String) {
        try {
            GltfParser.parse(bytes)
            org.junit.Assert.fail("Deveria ter falhado com: $expectedFragment")
        } catch (e: GltfParser.GltfParseException) {
            assertTrue(
                "Mensagem '${e.message}' deveria conter '$expectedFragment'",
                e.message!!.contains(expectedFragment)
            )
        }
    }

    @Test
    fun `ficheiro não glTF é rejeitado com mensagem clara`() {
        assertParseError("isto não é um modelo".toByteArray(), "Formato de modelo não suportado")
    }

    @Test
    fun `ficheiro vazio é rejeitado`() {
        assertParseError(ByteArray(0), "Ficheiro vazio")
    }

    @Test
    fun `glb com magia errada não é confundido`() {
        assertFalse(GltfParser.isGlb(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
    }

    @Test
    fun `versão glTF diferente de 2 é rejeitada`() {
        val json = """{"asset": {"version": "1.0"}, "scenes": [], "nodes": []}"""
        assertParseError(buildGlb(json), "Versão glTF não suportada")
    }

    @Test
    fun `extensão obrigatória não suportada é rejeitada com nome`() {
        val json = """
        {"asset": {"version": "2.0"},
         "extensionsRequired": ["KHR_draco_mesh_compression"],
         "scenes": [], "nodes": []}
        """.trimIndent()
        assertParseError(buildGlb(json), "KHR_draco_mesh_compression")
    }

    @Test
    fun `buffer externo é rejeitado com orientação clara`() {
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 1, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 12}],
         "buffers": [{"byteLength": 12, "uri": "modelo.bin"}]}
        """.trimIndent()
        assertParseError(buildGlb(json), "buffer EXTERNO")
    }

    @Test
    fun `accessor fora dos limites do buffer é rejeitado`() {
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 9, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 12}],
         "buffers": [{"byteLength": 12}]}
        """.trimIndent()
        assertParseError(buildGlb(json, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), "fora dos limites")
    }

    @Test
    fun `índice fora do intervalo de vértices é rejeitado`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val bin = ByteArrayOutputStream().apply {
            positions.forEach { write(leFloat(it)) }
            write(le16(0)); write(le16(1)); write(le16(7)) // 7 não existe
        }.toByteArray()
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
         "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
         ],
         "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 36},
            {"buffer": 0, "byteOffset": 36, "byteLength": 6}
         ],
         "buffers": [{"byteLength": 42}]}
        """.trimIndent()
        assertParseError(buildGlb(json, bin), "fora do intervalo de vértices")
    }

    @Test
    fun `ficheiro demasiado grande é rejeitado antes de alocar`() {
        val huge = ByteArray(GltfLimits.MAX_FILE_BYTES.toInt() + 1)
        assertParseError(huge, "Recurso demasiado grande")
    }

    @Test
    fun `imagem externa é rejeitada`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val bin = ByteArrayOutputStream().apply { positions.forEach { write(leFloat(it)) } }.toByteArray()
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "material": 0}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
         "images": [{"uri": "textura.png"}],
         "materials": [{"name": "m", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}}],
         "textures": [{"source": 0}],
         "buffers": [{"byteLength": 36}]}
        """.trimIndent()
        assertParseError(buildGlb(json, bin), "imagem EXTERNA")
    }

    @Test
    fun `mime de imagem não suportado é rejeitado`() {
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 1, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 12}],
         "images": [{"bufferView": 0, "mimeType": "image/webp"}],
         "buffers": [{"byteLength": 12}]}
        """.trimIndent()
        assertParseError(buildGlb(json, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), "image/webp")
    }

    @Test
    fun `modelo sem triângulos é rejeitado`() {
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": 1}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 2, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 24}],
         "buffers": [{"byteLength": 24}]}
        """.trimIndent()
        val bin = ByteArray(24)
        assertParseError(buildGlb(json, bin), "não tem triângulos")
    }

    @Test
    fun `accessor esparso é rejeitado com mensagem`() {
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 1, "type": "VEC3",
                        "sparse": {"count": 0, "indices": {"bufferView": 0}, "values": {"bufferView": 0}}}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 12}],
         "buffers": [{"byteLength": 12}]}
        """.trimIndent()
        assertParseError(buildGlb(json, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)), "sparse")
    }

    // ------------------------------------------------------------------
    // Associação de texturas (inicial) e hierarquia de nós
    // ------------------------------------------------------------------

    @Test
    fun `materiais sem textura embutida ficam com referência nula`() {
        // GLB com material SEM pbrMetallicRoughness.baseColorTexture — a
        // associação inicial é decidida pela UI (DOCUMENT), não pelo parser.
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "material": 0}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
         "materials": [{"name": "m", "pbrMetallicRoughness": {"baseColorFactor": [1,1,1,1]}}],
         "buffers": [{"byteLength": 36}]}
        """.trimIndent()
        val bin = ByteArrayOutputStream().apply {
            write(leFloat(0f)); write(leFloat(0f)); write(leFloat(0f))
            write(leFloat(1f)); write(leFloat(0f)); write(leFloat(0f))
            write(leFloat(0f)); write(leFloat(1f)); write(leFloat(0f))
        }.toByteArray()
        val model = GltfParser.parse(buildGlb(json, bin))
        assertNull(model.materials[0].textureImageIndex)
        assertEquals(4, model.materials[0].baseColorFactor.size)
        assertTrue(model.images.isEmpty())
    }

    @Test
    fun `hierarquia de nós compõe transformações pai-filho`() {
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}],
         "nodes": [
            {"translation": [10.0, 0.0, 0.0], "children": [1]},
            {"mesh": 0}
         ],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
         "buffers": [{"byteLength": 36}]}
        """.trimIndent()
        val bin = ByteArrayOutputStream().apply {
            write(leFloat(1f)); write(leFloat(2f)); write(leFloat(3f))
            write(leFloat(1f)); write(leFloat(2f)); write(leFloat(3f))
            write(leFloat(1f)); write(leFloat(2f)); write(leFloat(3f))
        }.toByteArray()
        val model = GltfParser.parse(buildGlb(json, bin))
        val p = model.renderables[0].positions
        assertEquals(11f, p[0], 1e-5f)
        assertEquals(2f, p[1], 1e-5f)
        assertEquals(3f, p[2], 1e-5f)
    }

    @Test
    fun `rotação por quaternião é aplicada ao baking`() {
        // Rotação de 90° em Z (quat = 0,0,√½,√½): (1,0,0) → (0,1,0).
        val s = Math.sqrt(0.5).toFloat()
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}],
         "nodes": [{"rotation": [0.0, 0.0, $s, $s], "mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
         "buffers": [{"byteLength": 36}]}
        """.trimIndent()
        val bin = ByteArrayOutputStream().apply {
            write(leFloat(1f)); write(leFloat(0f)); write(leFloat(0f))
            write(leFloat(1f)); write(leFloat(0f)); write(leFloat(0f))
            write(leFloat(1f)); write(leFloat(0f)); write(leFloat(0f))
        }.toByteArray()
        val model = GltfParser.parse(buildGlb(json, bin))
        val p = model.renderables[0].positions
        assertEquals(0f, p[0], 1e-4f)
        assertEquals(1f, p[1], 1e-4f)
    }

    @Test
    fun `stride não compactado em POSITION é respeitado`() {
        // VEC3 com byteStride 16 (interleaved com 4 bytes extra por vértice) —
        // 3 vértices formam 1 triângulo (sem índices = sequencial, múltiplo de 3).
        val bin = ByteArrayOutputStream().apply {
            write(leFloat(1f)); write(leFloat(2f)); write(leFloat(3f)); write(ByteArray(4))
            write(leFloat(4f)); write(leFloat(5f)); write(leFloat(6f)); write(ByteArray(4))
            write(leFloat(7f)); write(leFloat(8f)); write(leFloat(9f)); write(ByteArray(4))
        }.toByteArray()
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 48, "byteStride": 16}],
         "buffers": [{"byteLength": 48}]}
        """.trimIndent()
        val model = GltfParser.parse(buildGlb(json, bin))
        val p = model.renderables[0].positions
        assertEquals(1f, p[0], 1e-6f)
        assertEquals(9f, p[8], 1e-6f)
    }

    // ------------------------------------------------------------------
    // Deteção por CONTEÚDO e importação .gltf + .bin (missão 3D import)
    // ------------------------------------------------------------------

    /** Triângulo simples (3 vértices + 3 índices ushort) como bytes de .bin. */
    private fun triangleBin(): ByteArray {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        return ByteArrayOutputStream().apply {
            positions.forEach { write(leFloat(it)) }
            write(le16(0)); write(le16(1)); write(le16(2))
        }.toByteArray()
    }

    /** glTF JSON solto cujo buffer 0 é o .bin externo "modelo.bin". */
    private fun triangleGltfWithExternalBin(bufferUri: String = "modelo.bin"): String = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "material": 0}]}],
         "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
         ],
         "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 36},
            {"buffer": 0, "byteOffset": 36, "byteLength": 6}
         ],
         "materials": [{"name": "m"}],
         "buffers": [{"byteLength": 42, "uri": "$bufferUri"}]}
    """.trimIndent()

    @Test
    fun `deteção por conteúdo ignora extensão e mime`() {
        // GLB com nome errado continua a ser GLB; JSON com nome errado continua
        // a ser modelo — e bytes arbitrários não se disfarçam de modelo.
        val glb = quadGlb()
        val json = triangleGltfWithExternalBin().toByteArray(Charsets.UTF_8)
        assertTrue(GltfParser.isGlb(glb))
        assertTrue(GltfParser.looksLikeModelJson(json))
        assertTrue(GltfParser.looksLikeModel(glb))
        assertTrue(GltfParser.looksLikeModel(json))
        assertFalse(GltfParser.looksLikeModel("PK\u0003\u0004 zip qualquer".toByteArray()))
        assertFalse(GltfParser.looksLikeModel("<html>erro 404</html>".toByteArray()))
    }

    @Test
    fun `gltf com BOM UTF-8 parseia`() {
        // Bug real: ficheiros .gltf gravados com BOM (EF BB BF) falhavam a
        // verificação do "{" inicial e eram recusados como "formato não
        // suportado".
        val bin = triangleBin()
        val base64 = java.util.Base64.getEncoder().encodeToString(bin)
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "material": 0}]}],
         "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}
         ],
         "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 36},
            {"buffer": 0, "byteOffset": 36, "byteLength": 6}
         ],
         "materials": [{"name": "m"}],
         "buffers": [{"byteLength": 42, "uri": "data:application/octet-stream;base64,$base64"}]}
        """.trimIndent()
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json.toByteArray(Charsets.UTF_8)
        assertTrue(GltfParser.looksLikeModelJson(withBom))
        val model = GltfParser.parse(withBom, "modelo_com_bom.gltf")
        assertEquals(1, model.triangleCount)
    }

    @Test
    fun `conteúdo desconhecido mostra diagnóstico real no erro`() {
        try {
            GltfParser.parse("isto não é um modelo".toByteArray(), "acidente.glb")
            org.junit.Assert.fail("Deveria ter falhado")
        } catch (e: GltfParser.GltfParseException) {
            val msg = e.message!!
            assertTrue("falta a frase base: $msg", msg.contains("Formato de modelo não suportado"))
            assertTrue("falta o nome: $msg", msg.contains("acidente.glb"))
            assertTrue("faltam os hex dos primeiros bytes: $msg", msg.contains("69 73 74 6F"))
            assertTrue("falta o tamanho: $msg", msg.contains("bytes"))
        }
    }

    @Test
    fun `buffer externo é resolvido com o bin selecionado`() {
        val json = triangleGltfWithExternalBin()
        val bytes = json.toByteArray(Charsets.UTF_8)
        val bin = triangleBin()
        val model = GltfParser.parse(bytes, "modelo.gltf", mapOf("modelo.bin" to bin))
        assertEquals(1, model.triangleCount)
        assertEquals(3, model.renderables[0].positions.size / 3)
        assertEquals(listOf(0, 1, 2), model.renderables[0].indices.toList())
    }

    @Test
    fun `buffer externo resolve por basename ignorando caminho e maiúsculas`() {
        val json = triangleGltfWithExternalBin(bufferUri = "./dados/MODELO.BIN")
        val bin = triangleBin()
        val model = GltfParser.parse(
            json.toByteArray(Charsets.UTF_8), "modelo.gltf", mapOf("modelo.bin" to bin)
        )
        assertEquals(1, model.triangleCount)
    }

    @Test
    fun `buffer externo sem bin orienta para glb ou seleção conjunta`() {
        val json = triangleGltfWithExternalBin()
        try {
            GltfParser.parse(json.toByteArray(Charsets.UTF_8), "modelo.gltf")
            org.junit.Assert.fail("Deveria ter falhado")
        } catch (e: GltfParser.GltfParseException) {
            val msg = e.message!!
            assertTrue("falta 'buffer EXTERNO': $msg", msg.contains("buffer EXTERNO"))
            assertTrue("falta a orientação .glb: $msg", msg.contains(".glb"))
            assertTrue("falta a orientação .bin: $msg", msg.contains(".bin"))
            assertTrue("falta o uri em falta: $msg", msg.contains("modelo.bin"))
        }
    }

    @Test
    fun `imagem externa é resolvida com o ficheiro selecionado`() {
        val json = """
        {"asset": {"version": "2.0"},
         "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
         "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "material": 0}]}],
         "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
         "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
         "images": [{"uri": "textura.png"}],
         "materials": [{"name": "m", "pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}}],
         "textures": [{"source": 0}],
         "buffers": [{"byteLength": 36, "uri": "modelo.bin"}]}
        """.trimIndent()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val model = GltfParser.parse(
            json.toByteArray(Charsets.UTF_8), "modelo.gltf",
            mapOf("modelo.bin" to triangleBin().copyOf(36), "textura.png" to png)
        )
        assertEquals(0, model.materials[0].textureImageIndex)
        assertEquals(png.size, model.images[0].bytes.size)
        assertEquals("image/png", model.images[0].mimeType)
    }

    @Test
    fun `nenhum modelo na seleção devolve diagnóstico do primeiro ficheiro`() {
        val zip = "PK\u0003\u0004 conteudo zip".toByteArray()
        try {
            GltfParser.parse(zip, "descarga.zip")
            org.junit.Assert.fail("Deveria ter falhado")
        } catch (e: GltfParser.GltfParseException) {
            assertTrue(e.message!!.contains("descarga.zip"))
        }
    }
}
