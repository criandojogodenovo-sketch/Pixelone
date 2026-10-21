package com.pixelone.studio.studio3d

import com.pixelone.studio.osp.MiniJson
import java.util.Base64

/**
 * Importador glTF 2.0 do 3D Texture Studio — Kotlin puro (testável em JVM),
 * SEM dependências externas: o JSON é lido com o [MiniJson] do projeto (o
 * mesmo do .osp) e o contentor GLB é lido byte a byte.
 *
 * FORMATOS ACEITES (validação explícita pedida na Fase 3):
 *  - .glb (contentor binário com JSON + BIN);
 *  - .gltf AUTOCONTIDO (buffers/imagens apenas em data: URI base64).
 * Ficheiros com buffers EXTERNOS (.bin ao lado, http, etc.) são rejeitados
 * com mensagem clara: "exporta um GLB autocontido".
 *
 * O QUE É EXTRAÍDO (nada simulado):
 *  - geometria REAL por primitiva: POSITION (VEC3 float), indices
 *    (ubyte/ushort/uint), TEXCOORD_0 (VEC2 float) — transformações dos nós
 *    (matrix ou TRS com quaternião) aplicadas (baking) para espaço do mundo;
 *  - materiais: name, baseColorFactor, alphaMode (OPAQUE/MASK/BLEND),
 *    alphaCutoff e a textura baseColor (imagem PNG/JPEG embutida);
 *  - imagens: bytes brutos + mimeType (descodificação é feita pela camada
 *    Android com BitmapFactory — aqui só são validadas e contadas).
 *
 * SEGURANÇA: todos os limites de [GltfLimits] são verificados ANTES de
 * alocar; accessors/bufferViews fora dos limites produzem
 * [GltfParseException] com mensagem clara (nunca ArrayIndexOutOfBounds).
 */
object GltfParser {

    private const val GLB_MAGIC: Int = 0x46546C67 // "glTF"
    private const val CHUNK_JSON: Int = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN: Int = 0x004E4942 // "BIN\0"

    /** Erro controlado com mensagem pronta para o utilizador. */
    class GltfParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ------------------------------------------------------------------
    // Modelo de dados de saída
    // ------------------------------------------------------------------

    enum class AlphaMode { OPAQUE, MASK, BLEND }

    /** Primitiva com transformações já aplicadas (espaço do mundo). */
    class Renderable(
        val meshIndex: Int,
        val meshName: String,
        val materialIndex: Int,
        val positions: FloatArray,   // xyz por vértice (mundo)
        val uvs: FloatArray?,        // uv por vértice; null = primitiva SEM UV
        val indices: IntArray        // triângulos (múltiplo de 3)
    )

    /** Material glTF simplificado ao que a pixel art precisa. */
    class Material3D(
        val name: String,
        val baseColorFactor: FloatArray, // rgba
        val alphaMode: AlphaMode,
        val alphaCutoff: Float,
        val textureImageIndex: Int?      // índice em [GltfModel.images] ou null
    )

    /** Imagem embutida (bytes brutos; descodificação na camada Android). */
    class GltfImage(val bytes: ByteArray, val mimeType: String)

    /** Modelo completo, pronto para o renderer e para a UI. */
    class GltfModel(
        val renderables: List<Renderable>,
        val materials: List<Material3D>,
        val images: List<GltfImage>,
        val meshNames: List<String>,
        val hasUv: Boolean,
        val triangleCount: Int,
        val boundsMin: FloatArray, // xyz
        val boundsMax: FloatArray,
        val skippedNonTrianglePrimitives: Int
    ) {
        /** Centro da caixa envolvente (para enquadrar a câmara). */
        fun center(): FloatArray = floatArrayOf(
            (boundsMin[0] + boundsMax[0]) / 2f,
            (boundsMin[1] + boundsMax[1]) / 2f,
            (boundsMin[2] + boundsMax[2]) / 2f
        )

        /** Raio aproximado da caixa envolvente (para enquadrar a câmara). */
        fun radius(): Float {
            val dx = boundsMax[0] - boundsMin[0]
            val dy = boundsMax[1] - boundsMin[1]
            val dz = boundsMax[2] - boundsMin[2]
            return 0.5f * kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    // ------------------------------------------------------------------
    // Entrada principal
    // ------------------------------------------------------------------

    /** Deteta pelo contentor: GLB começa com a magia "glTF". */
    fun isGlb(bytes: ByteArray): Boolean =
        bytes.size >= 12 && readU32(bytes, 0) == GLB_MAGIC

    /**
     * Analisa um ficheiro de modelo (.glb ou .gltf autocontido).
     * @throws GltfParseException com mensagem clara para o Toast/Snackbar.
     */
    fun parse(bytes: ByteArray): GltfModel {
        if (bytes.isEmpty()) throw GltfParseException("Ficheiro vazio — não é um modelo 3D válido")
        if (bytes.size > GltfLimits.MAX_FILE_BYTES) {
            throw GltfParseException(
                GltfLimits.tooLargeError("ficheiro de modelo", bytes.size.toLong(), GltfLimits.MAX_FILE_BYTES)
            )
        }
        val jsonText: String
        var bin: ByteArray? = null
        if (isGlb(bytes)) {
            val glb = parseGlbContainer(bytes)
            jsonText = glb.first
            bin = glb.second
        } else {
            // glTF JSON solto: validar UTF-8 e ver se não é outro formato qualquer.
            jsonText = try {
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                throw GltfParseException("Ficheiro não é GLB nem glTF JSON válido", e)
            }
            if (!jsonText.trimStart().startsWith("{")) {
                throw GltfParseException(
                    "Formato de modelo não suportado. O Pixel One importa glTF 2.0 (.gltf) e GLB (.glb)."
                )
            }
        }

        val root = parseJson(jsonText)
        validateAssetAndExtensions(root)

        val buffers = readBuffers(root, bin)
        val bufferViews = readBufferViews(root, buffers)
        val accessors = root["accessors"] as? List<*> ?: emptyList<Any>()
        val images = readImages(root, bufferViews)
        val materials = readMaterials(root)
        val meshNames = readMeshNames(root)

        // Percorrer a cena default e cozinhar a geometria com as transformações.
        val scene = collectSceneNodes(root)
        val renderables = ArrayList<Renderable>(64)
        val boundsMin = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val boundsMax = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        var triangles = 0
        var skipped = 0
        var allHaveUv = true
        val scratchMatrix = FloatArray(16)

        fun emitNode(meshIndex: Int, world: FloatArray) {
            val mesh = (root["meshes"] as? List<*>)?.getOrNull(meshIndex) as? Map<*, *>
                ?: throw GltfParseException("Nó refere mesh inexistente: $meshIndex")
            val primitives = mesh["primitives"] as? List<*> ?: emptyList<Any>()
            if (primitives.isEmpty()) return
            val name = mesh["name"] as? String ?: "mesh$meshIndex"
            for (p in primitives) {
                val prim = p as? Map<*, *> ?: continue
                val mode = (prim["mode"] as? Number)?.toInt() ?: MODE_TRIANGLES
                if (mode != MODE_TRIANGLES) {
                    skipped++
                    continue
                }
                if (renderables.size >= GltfLimits.MAX_RENDERABLES) {
                    throw GltfParseException(
                        GltfLimits.tooLargeError(
                            "número de primitivas", renderables.size.toLong(), GltfLimits.MAX_RENDERABLES.toLong()
                        )
                    )
                }
                val materialIndex = (prim["material"] as? Number)?.toInt() ?: 0
                val r = buildRenderable(
                    prim, materialIndex, meshIndex, name, world, bufferViews, accessors
                ) { hasUv -> if (!hasUv) allHaveUv = false }
                triangles += r.indices.size / 3
                if (triangles > GltfLimits.MAX_TRIANGLES) {
                    throw GltfParseException(
                        GltfLimits.tooLargeError(
                            "número de triângulos", triangles.toLong(), GltfLimits.MAX_TRIANGLES.toLong()
                        )
                    )
                }
                // Bounding box com a geometria já cozinhada.
                var i = 0
                while (i < r.positions.size) {
                    val x = r.positions[i]; val y = r.positions[i + 1]; val z = r.positions[i + 2]
                    if (x < boundsMin[0]) boundsMin[0] = x
                    if (y < boundsMin[1]) boundsMin[1] = y
                    if (z < boundsMin[2]) boundsMin[2] = z
                    if (x > boundsMax[0]) boundsMax[0] = x
                    if (y > boundsMax[1]) boundsMax[1] = y
                    if (z > boundsMax[2]) boundsMax[2] = z
                    i += 3
                }
                renderables.add(r)
            }
        }

        // Travessia iterativa com pilha explícita (evita StackOverflow em grafos fundos).
        data class NodeTask(val nodeIndex: Int, val parentWorld: FloatArray?, val depth: Int)
        val stack = ArrayDeque<NodeTask>()
        for (n in scene) stack.addLast(NodeTask(n, null, 0))
        var visited = 0
        while (stack.isNotEmpty()) {
            val task = stack.removeLast()
            visited++
            if (visited > GltfLimits.MAX_NODES) {
                throw GltfParseException(
                    GltfLimits.tooLargeError("número de nós", visited.toLong(), GltfLimits.MAX_NODES.toLong())
                )
            }
            if (task.depth > GltfLimits.MAX_NODE_DEPTH) {
                throw GltfParseException("Hierarquia de nós demasiado profunda (possível ciclo)")
            }
            val node = (root["nodes"] as? List<*>)?.getOrNull(task.nodeIndex) as? Map<*, *>
                ?: throw GltfParseException("Cena refere nó inexistente: ${task.nodeIndex}")
            val local = nodeMatrix(node, scratchMatrix)
            val world: FloatArray = if (task.parentWorld != null) {
                Mat4.multiply(FloatArray(16), task.parentWorld, local)
            } else local.copyOf()

            val meshIndex = (node["mesh"] as? Number)?.toInt()
            if (meshIndex != null) emitNode(meshIndex, world)

            val children = node["children"] as? List<*> ?: emptyList<Any>()
            for (c in children) {
                val ci = (c as? Number)?.toInt()
                    ?: throw GltfParseException("Filho de nó inválido (não é um índice)")
                stack.addLast(NodeTask(ci, world, task.depth + 1))
            }
        }

        if (renderables.isEmpty()) {
            throw GltfParseException(
                if (skipped > 0)
                    "O modelo não tem triângulos ($skipped primitivas não triangulares ignoradas — " +
                        "o Pixel One desenha malhas de triângulos)."
                else "O modelo não contém geometria visível (nenhuma mesh na cena)."
            )
        }
        if (triangles == 0) {
            throw GltfParseException("O modelo não tem triângulos (índices vazios)")
        }

        // Caixa degenerada (modelo num ponto único) — cresce minimamente.
        if (boundsMin[0] == Float.POSITIVE_INFINITY) {
            boundsMin.fill(0f); boundsMax.fill(0f)
        }

        return GltfModel(
            renderables = renderables,
            materials = materials,
            images = images,
            meshNames = meshNames,
            hasUv = allHaveUv,
            triangleCount = triangles,
            boundsMin = boundsMin,
            boundsMax = boundsMax,
            skippedNonTrianglePrimitives = skipped
        )
    }

    // ------------------------------------------------------------------
    // Contentor GLB
    // ------------------------------------------------------------------

    private fun parseGlbContainer(bytes: ByteArray): Pair<String, ByteArray?> {
        val totalLength = readU32(bytes, 8)
        if (totalLength.toLong() > GltfLimits.MAX_FILE_BYTES) {
            throw GltfParseException(
                GltfLimits.tooLargeError("contentor GLB", totalLength.toLong(), GltfLimits.MAX_FILE_BYTES)
            )
        }
        if (bytes.size < totalLength) {
            throw GltfParseException("GLB truncado: declara $totalLength bytes mas tem ${bytes.size}")
        }
        var offset = 12
        var json: String? = null
        var bin: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val chunkLength = readU32(bytes, offset)
            val chunkType = readU32(bytes, offset + 4)
            offset += 8
            if (chunkLength < 0 || offset.toLong() + chunkLength > bytes.size) {
                throw GltfParseException("Chunk GLB truncado (declara $chunkLength bytes)")
            }
            when (chunkType) {
                CHUNK_JSON -> {
                    if (chunkLength > GltfLimits.MAX_JSON_BYTES) {
                        throw GltfParseException(
                            GltfLimits.tooLargeError("JSON do GLB", chunkLength.toLong(), GltfLimits.MAX_JSON_BYTES.toLong())
                        )
                    }
                    json = String(bytes, offset, chunkLength, Charsets.UTF_8)
                }
                CHUNK_BIN -> {
                    if (bin != null) throw GltfParseException("GLB com mais de um chunk BIN (inválido)")
                    if (chunkLength > GltfLimits.MAX_TOTAL_BUFFER_BYTES) {
                        throw GltfParseException(
                            GltfLimits.tooLargeError("chunk BIN", chunkLength.toLong(), GltfLimits.MAX_TOTAL_BUFFER_BYTES)
                        )
                    }
                    bin = bytes.copyOfRange(offset, offset + chunkLength)
                }
                else -> throw GltfParseException("Chunk GLB desconhecido (tipo 0x${chunkType.toString(16)})")
            }
            offset += chunkLength
        }
        val jsonText = json ?: throw GltfParseException("GLB sem chunk JSON")
        return Pair(jsonText, bin)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) throw GltfParseException("Ficheiro truncado a ler cabeçalho")
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    // ------------------------------------------------------------------
    // JSON, asset e extensões
    // ------------------------------------------------------------------

    private fun parseJson(text: String): Map<String, Any?> {
        if (text.length > GltfLimits.MAX_JSON_BYTES) {
            throw GltfParseException(
                GltfLimits.tooLargeError("JSON do modelo", text.length.toLong(), GltfLimits.MAX_JSON_BYTES.toLong())
            )
        }
        val parsed = try {
            MiniJson.parse(text)
        } catch (e: MiniJson.JsonParseException) {
            throw GltfParseException("JSON do modelo inválido: ${e.message}", e)
        }
        return parsed as? Map<String, Any?>
            ?: throw GltfParseException("glTF inválido: a raiz deve ser um objeto JSON")
    }

    private fun validateAssetAndExtensions(root: Map<String, Any?>) {
        val asset = root["asset"] as? Map<*, *>
            ?: throw GltfParseException("glTF sem \"asset\" — ficheiro inválido")
        val version = asset["version"] as? String
            ?: throw GltfParseException("glTF sem versão no \"asset\"")
        val major = version.substringBefore('.').toIntOrNull()
        if (major != 2) {
            throw GltfParseException(
                "Versão glTF não suportada: $version. O Pixel One importa glTF 2.0."
            )
        }
        val required = root["extensionsRequired"] as? List<*> ?: emptyList<Any>()
        if (required.isNotEmpty()) {
            val names = required.filterIsInstance<String>().joinToString(", ")
            throw GltfParseException(
                "O modelo exige extensões glTF não suportadas ($names). " +
                    "Exporta o modelo sem Draco/compressão de texturas (glTF 2.0 clássico)."
            )
        }
    }

    // ------------------------------------------------------------------
    // Buffers e bufferViews
    // ------------------------------------------------------------------

    /** Devolve os bytes de cada buffer (por índice), de GLB BIN ou data: URI. */
    private fun readBuffers(root: Map<String, Any?>, glbBin: ByteArray?): List<ByteArray> {
        val buffers = root["buffers"] as? List<*> ?: return emptyList()
        return buffers.mapIndexed { index, raw ->
            val b = raw as? Map<*, *> ?: throw GltfParseException("buffers[$index] inválido")
            val declared = (b["byteLength"] as? Number)?.toLong()
                ?: throw GltfParseException("buffers[$index] sem byteLength")
            if (declared > GltfLimits.MAX_TOTAL_BUFFER_BYTES) {
                throw GltfParseException(
                    GltfLimits.tooLargeError("buffer $index", declared, GltfLimits.MAX_TOTAL_BUFFER_BYTES)
                )
            }
            val uri = b["uri"] as? String
            when {
                uri == null -> {
                    // Buffer do GLB: tem de ser o buffer 0 e caber no chunk BIN.
                    val bin = glbBin ?: throw GltfParseException(
                        "glTF refere buffer embutido mas o ficheiro não tem chunk BIN " +
                            "(exporta um .glb ou usa data: URI)"
                    )
                    if (index != 0) throw GltfParseException("Apenas o buffer 0 pode vir do chunk BIN do GLB")
                    if (declared > bin.size.toLong()) {
                        throw GltfParseException(
                            "Buffer 0 declara $declared bytes mas o chunk BIN só tem ${bin.size}"
                        )
                    }
                    if (bin.size.toLong() != declared) bin.copyOf(declared.toInt()) else bin
                }
                uri.startsWith("data:") -> {
                    val base64 = uri.substringAfter("base64,", "")
                    if (base64.isEmpty() || !uri.contains("base64")) {
                        throw GltfParseException(
                            "Buffer $index usa data: URI sem base64 — não suportado"
                        )
                    }
                    val decoded = try {
                        Base64.getMimeDecoder().decode(base64)
                    } catch (e: IllegalArgumentException) {
                        throw GltfParseException("Buffer $index: base64 inválido", e)
                    }
                    if (decoded.size.toLong() < declared) {
                        throw GltfParseException(
                            "Buffer $index declara $declared bytes mas tem ${decoded.size}"
                        )
                    }
                    decoded
                }
                else -> throw GltfParseException(
                    "O modelo usa buffer EXTERNO (\"$uri\") — o Pixel One importa apenas " +
                        "ficheiros autocontidos. Exporta um .glb (ou .gltf com dados embutidos)."
                )
            }
        }
    }

    private fun readBufferViews(root: Map<String, Any?>, buffers: List<ByteArray>): List<Triple<ByteArray, Int, Int>> {
        val views = root["bufferViews"] as? List<*> ?: return emptyList()
        return views.mapIndexed { index, raw ->
            val v = raw as? Map<*, *> ?: throw GltfParseException("bufferViews[$index] inválido")
            val bufferIndex = (v["buffer"] as? Number)?.toInt()
                ?: throw GltfParseException("bufferViews[$index] sem buffer")
            val buffer = buffers.getOrNull(bufferIndex)
                ?: throw GltfParseException("bufferViews[$index] refere buffer inexistente $bufferIndex")
            val byteOffset = (v["byteOffset"] as? Number)?.toInt() ?: 0
            val byteLength = (v["byteLength"] as? Number)?.toInt()
                ?: throw GltfParseException("bufferViews[$index] sem byteLength")
            if (byteOffset < 0 || byteLength < 0 || byteOffset.toLong() + byteLength > buffer.size) {
                throw GltfParseException(
                    "bufferViews[$index] fora dos limites do buffer " +
                        "($byteOffset+$byteLength em ${buffer.size} bytes)"
                )
            }
            val stride = (v["byteStride"] as? Number)?.toInt() ?: 0
            if (stride < 0) throw GltfParseException("bufferViews[$index] com byteStride negativo")
            Triple(buffer, byteOffset, stride)
        }
    }

    // ------------------------------------------------------------------
    // Imagens, materiais, meshes
    // ------------------------------------------------------------------

    private fun readImages(root: Map<String, Any?>, bufferViews: List<Triple<ByteArray, Int, Int>>): List<GltfImage> {
        val images = root["images"] as? List<*> ?: return emptyList()
        if (images.size > GltfLimits.MAX_IMAGES) {
            throw GltfParseException(
                GltfLimits.tooLargeError("número de imagens", images.size.toLong(), GltfLimits.MAX_IMAGES.toLong())
            )
        }
        return images.mapIndexed { index, raw ->
            val img = raw as? Map<*, *> ?: throw GltfParseException("images[$index] inválido")
            val mime = (img["mimeType"] as? String) ?: "image/png"
            if (mime != "image/png" && mime != "image/jpeg") {
                throw GltfParseException(
                    "Imagem $index com formato não suportado ($mime). Usa PNG ou JPEG."
                )
            }
            val uri = img["uri"] as? String
            val bufferViewIndex = (img["bufferView"] as? Number)?.toInt()
            val bytes: ByteArray = when {
                bufferViewIndex != null -> {
                    val (buffer, offset, _) = bufferViews.getOrNull(bufferViewIndex)
                        ?: throw GltfParseException("images[$index] refere bufferView inexistente $bufferViewIndex")
                    // A imagem usa byteLength do PRÓPRIO bufferView, não do accessor.
                    val length = (((root["bufferViews"] as? List<*>)?.getOrNull(bufferViewIndex) as? Map<*, *>)
                        ?.get("byteLength") as? Number)?.toInt() ?: (buffer.size - offset)
                    if (offset + length > buffer.size) {
                        throw GltfParseException("images[$index] fora dos limites do buffer")
                    }
                    buffer.copyOfRange(offset, offset + length)
                }
                uri != null && uri.startsWith("data:") -> {
                    val base64 = uri.substringAfter("base64,", "")
                    if (!uri.contains("base64") || base64.isEmpty()) {
                        throw GltfParseException("images[$index]: data: URI sem base64 não suportada")
                    }
                    try {
                        Base64.getMimeDecoder().decode(base64)
                    } catch (e: IllegalArgumentException) {
                        throw GltfParseException("images[$index]: base64 inválido", e)
                    }
                }
                uri != null -> throw GltfParseException(
                    "O modelo usa imagem EXTERNA (\"$uri\"). Exporta um .glb com texturas embutidas."
                )
                else -> throw GltfParseException("images[$index] sem bufferView nem uri")
            }
            if (bytes.size > GltfLimits.MAX_IMAGE_BYTES) {
                throw GltfParseException(
                    GltfLimits.tooLargeError("imagem $index", bytes.size.toLong(), GltfLimits.MAX_IMAGE_BYTES.toLong())
                )
            }
            GltfImage(bytes, mime)
        }
    }

    private fun readMaterials(root: Map<String, Any?>): List<Material3D> {
        val materials = root["materials"] as? List<*> ?: return listOf(
            Material3D("material0", floatArrayOf(1f, 1f, 1f, 1f), AlphaMode.OPAQUE, 0.5f, null)
        )
        return materials.mapIndexed { index, raw ->
            val m = raw as? Map<*, *> ?: throw GltfParseException("materials[$index] inválido")
            val name = m["name"] as? String ?: "material$index"
            val pbr = m["pbrMetallicRoughness"] as? Map<*, *>
            val baseColor = FloatArray(4) { 1f }
            (pbr?.get("baseColorFactor") as? List<*>)?.let { f ->
                if (f.size == 4) {
                    for (i in 0..3) baseColor[i] = (f[i] as? Number)?.toFloat() ?: 1f
                }
            }
            val alphaMode = when ((m["alphaMode"] as? String)?.uppercase()) {
                "MASK" -> AlphaMode.MASK
                "BLEND" -> AlphaMode.BLEND
                else -> AlphaMode.OPAQUE
            }
            val cutoff = ((m["alphaCutoff"] as? Number)?.toFloat()) ?: 0.5f
            val textureIndex: Int? =
                ((pbr?.get("baseColorTexture") as? Map<*, *>)?.get("index") as? Number)?.toInt()?.let { texIdx ->
                    val textures = root["textures"] as? List<*>
                    val tex = textures?.getOrNull(texIdx) as? Map<*, *>
                        ?: throw GltfParseException(
                            "Material \"$name\" refere textura inexistente $texIdx"
                        )
                    (tex["source"] as? Number)?.toInt()
                }
            Material3D(name, baseColor, alphaMode, cutoff, textureIndex)
        }
    }

    private fun readMeshNames(root: Map<String, Any?>): List<String> {
        val meshes = root["meshes"] as? List<*> ?: return emptyList()
        return meshes.mapIndexed { i, raw -> (raw as? Map<*, *>)?.get("name") as? String ?: "mesh$i" }
    }

    /** Índices dos nós da cena default (ou da cena 0, ou todos os nós de topo). */
    private fun collectSceneNodes(root: Map<String, Any?>): List<Int> {
        val scenes = root["scenes"] as? List<*>
        val nodes = root["nodes"] as? List<*> ?: emptyList<Any>()
        if (scenes.isNullOrEmpty()) {
            // Sem scenes: todos os nós de topo (sem "parent" explícito — aproximação:
            // usar todos os nós, a travessia cobre hierarquias simples).
            return nodes.indices.toList()
        }
        val sceneIndex = (root["scene"] as? Number)?.toInt() ?: 0
        val scene = scenes.getOrNull(sceneIndex) as? Map<*, *>
            ?: throw GltfParseException("scene $sceneIndex inexistente")
        val nodeIdxs = scene["nodes"] as? List<*> ?: emptyList<Any>()
        return nodeIdxs.map { (it as? Number)?.toInt() ?: throw GltfParseException("scene com nó inválido") }
    }

    // ------------------------------------------------------------------
    // Nós e geometria
    // ------------------------------------------------------------------

    private fun nodeMatrix(node: Map<*, *>, scratch: FloatArray): FloatArray {
        val matrix = node["matrix"] as? List<*>
        if (matrix != null) {
            if (matrix.size != 16) throw GltfParseException("Nó com \"matrix\" sem 16 valores")
            // glTF guarda column-major — igual à nossa convenção.
            for (i in 0 until 16) {
                scratch[i] = (matrix[i] as? Number)?.toFloat()
                    ?: throw GltfParseException("Nó com \"matrix\" não numérica")
            }
            return scratch
        }
        val t = node["translation"] as? List<*>
        val r = node["rotation"] as? List<*>
        val s = node["scale"] as? List<*>
        if (t == null && r == null && s == null) return Mat4.identity(scratch)
        fun vec(list: List<*>?, size: Int, what: String, default: Float = 0f): FloatArray {
            if (list == null) {
                // Omissões glTF: translação (0,0,0); rotação = identidade
                // (quaternião 0,0,0,1); ESCALA = (1,1,1) — nunca zero, que
                // colapsaria a geometria para um ponto.
                return FloatArray(size) { default }.also { if (size == 4) it[3] = 1f }
            }
            if (list.size != size) throw GltfParseException("Nó com \"$what\" sem $size valores")
            return FloatArray(size) { (list[it] as? Number)?.toFloat() ?: default }
        }
        val tv = vec(t, 3, "translation")
        val rv = vec(r, 4, "rotation")
        val sv = vec(s, 3, "scale", default = 1f)
        return Mat4.trs(scratch, tv[0], tv[1], tv[2], rv[0], rv[1], rv[2], rv[3], sv[0], sv[1], sv[2])
    }

    private fun buildRenderable(
        prim: Map<*, *>,
        materialIndex: Int,
        meshIndex: Int,
        meshName: String,
        world: FloatArray,
        bufferViews: List<Triple<ByteArray, Int, Int>>,
        accessors: List<Any?>,
        onUvPresence: (Boolean) -> Unit = {}
    ): Renderable {
        val attributes = prim["attributes"] as? Map<*, *>
            ?: throw GltfParseException("Primitiva da mesh \"$meshName\" sem attributes")
        val posIndex = (attributes["POSITION"] as? Number)?.toInt()
            ?: throw GltfParseException("Primitiva da mesh \"$meshName\" sem POSITION")
        val pos = readVec3Accessor(posIndex, bufferViews, accessors, meshName)

        // TEXCOORD_0: opcional; se existir tem de ser VEC2 float.
        val uvIndex = (attributes["TEXCOORD_0"] as? Number)?.toInt()
        val uvs: FloatArray? = if (uvIndex != null) {
            readVec2Accessor(uvIndex, bufferViews, accessors, meshName).also { onUvPresence(true) }
        } else null
        if (uvIndex == null) onUvPresence(false)

        // Índices: opcional (sem indices = sequencial).
        val idxAccessor = (prim["indices"] as? Number)?.toInt()
        val indices: IntArray = if (idxAccessor != null) {
            readIndexAccessor(idxAccessor, bufferViews, accessors, meshName)
        } else {
            IntArray(pos.size / 3) { it }
        }
        if (indices.size % 3 != 0) {
            throw GltfParseException(
                "Mesh \"$meshName\" tem ${indices.size} índices — não é múltiplo de 3 (triângulos)"
            )
        }
        // Baking da transformação do nó nas posições.
        val baked = FloatArray(pos.size)
        var i = 0
        var vi = 0
        while (i < pos.size) {
            val p = Mat4.transformPoint(world, pos[i], pos[i + 1], pos[i + 2])
            baked[i] = p[0]; baked[i + 1] = p[1]; baked[i + 2] = p[2]
            i += 3; vi++
        }
        // Índices têm de referir vértices existentes.
        for (idx in indices) {
            if (idx < 0 || idx >= vi) {
                throw GltfParseException(
                    "Mesh \"$meshName\" tem índice $idx fora do intervalo de vértices (0..${vi - 1})"
                )
            }
        }
        return Renderable(meshIndex, meshName, materialIndex, baked, uvs, indices)
    }

    // ------------------------------------------------------------------
    // Leitura de accessors
    // ------------------------------------------------------------------

    private data class AccessorRef(
        val view: Triple<ByteArray, Int, Int>,
        val byteOffset: Int,
        val count: Int,
        val componentSize: Int
    )

    private fun resolveAccessor(
        accessorIndex: Int,
        bufferViews: List<Triple<ByteArray, Int, Int>>,
        accessors: List<Any?>
    ): AccessorRef {
        val acc = accessors.getOrNull(accessorIndex) as? Map<*, *>
            ?: throw GltfParseException("accessor $accessorIndex inexistente")
        val count = (acc["count"] as? Number)?.toInt()
            ?: throw GltfParseException("accessor $accessorIndex sem count")
        if (acc["sparse"] != null) {
            throw GltfParseException(
                "accessor $accessorIndex usa \"sparse\" — não suportado pelo Pixel One. " +
                    "Reexporta o modelo sem accessors esparsos."
            )
        }
        val bufferViewIndex = (acc["bufferView"] as? Number)?.toInt()
            ?: throw GltfParseException("accessor $accessorIndex sem bufferView")
        val view = bufferViews.getOrNull(bufferViewIndex)
            ?: throw GltfParseException("accessor $accessorIndex refere bufferView inexistente")
        val byteOffset = (acc["byteOffset"] as? Number)?.toInt() ?: 0
        return AccessorRef(view, byteOffset, count, 0)
    }

    /** Lê POSITION (VEC3 float, obrigatório). */
    private fun readVec3Accessor(
        accessorIndex: Int,
        bufferViews: List<Triple<ByteArray, Int, Int>>,
        accessors: List<Any?>,
        meshName: String
    ): FloatArray {
        val acc = accessors.getOrNull(accessorIndex) as? Map<*, *>
            ?: throw GltfParseException("accessor $accessorIndex (POSITION da mesh \"$meshName\") inexistente")
        val type = acc["type"] as? String
        if (type != "VEC3") {
            throw GltfParseException("POSITION da mesh \"$meshName\" deve ser VEC3 (encontrado $type)")
        }
        if ((acc["componentType"] as? Number)?.toInt() != 5126) {
            throw GltfParseException("POSITION da mesh \"$meshName\" deve ser float (componentType 5126)")
        }
        val ref = resolveAccessor(accessorIndex, bufferViews, accessors)
        val buffer = ref.view.first
        val base0 = ref.view.second + ref.byteOffset // bufferView.byteOffset + accessor.byteOffset
        val stride = ref.view.third
        val step = if (stride == 0) 12 else stride
        if (step < 12) throw GltfParseException("byteStride inválido (${step} < 12) para POSITION")
        val out = FloatArray(ref.count * 3)
        for (i in 0 until ref.count) {
            val base = base0 + i * step
            if (base + 12 > buffer.size) {
                throw GltfParseException("POSITION da mesh \"$meshName\" fora dos limites do buffer")
            }
            out[i * 3] = Float.fromBits(readI32(buffer, base))
            out[i * 3 + 1] = Float.fromBits(readI32(buffer, base + 4))
            out[i * 3 + 2] = Float.fromBits(readI32(buffer, base + 8))
        }
        return out
    }

    /** Lê TEXCOORD_0 (VEC2 float). */
    private fun readVec2Accessor(
        accessorIndex: Int,
        bufferViews: List<Triple<ByteArray, Int, Int>>,
        accessors: List<Any?>,
        meshName: String
    ): FloatArray {
        val acc = accessors.getOrNull(accessorIndex) as? Map<*, *>
            ?: throw GltfParseException("accessor $accessorIndex (TEXCOORD_0 da mesh \"$meshName\") inexistente")
        if (acc["type"] as? String != "VEC2") {
            throw GltfParseException("TEXCOORD_0 da mesh \"$meshName\" deve ser VEC2")
        }
        if ((acc["componentType"] as? Number)?.toInt() != 5126) {
            throw GltfParseException("TEXCOORD_0 da mesh \"$meshName\" deve ser float")
        }
        val ref = resolveAccessor(accessorIndex, bufferViews, accessors)
        val buffer = ref.view.first
        val base0 = ref.view.second + ref.byteOffset
        val stride = ref.view.third
        val step = if (stride == 0) 8 else stride
        if (step < 8) throw GltfParseException("byteStride inválido (${step} < 8) para TEXCOORD_0")
        val out = FloatArray(ref.count * 2)
        for (i in 0 until ref.count) {
            val base = base0 + i * step
            if (base + 8 > buffer.size) {
                throw GltfParseException("TEXCOORD_0 da mesh \"$meshName\" fora dos limites do buffer")
            }
            out[i * 2] = Float.fromBits(readI32(buffer, base))
            out[i * 2 + 1] = Float.fromBits(readI32(buffer, base + 4))
        }
        return out
    }

    /** Lê índices (ubyte 5121, ushort 5123, uint 5125). */
    private fun readIndexAccessor(
        accessorIndex: Int,
        bufferViews: List<Triple<ByteArray, Int, Int>>,
        accessors: List<Any?>,
        meshName: String
    ): IntArray {
        val acc = accessors.getOrNull(accessorIndex) as? Map<*, *>
            ?: throw GltfParseException("accessor $accessorIndex (índices da mesh \"$meshName\") inexistente")
        if (acc["type"] as? String != "SCALAR") {
            throw GltfParseException("Índices da mesh \"$meshName\" devem ser SCALAR")
        }
        val componentType = (acc["componentType"] as? Number)?.toInt()
        val size = when (componentType) {
            5121 -> 1
            5123 -> 2
            5125 -> 4
            else -> throw GltfParseException(
                "Índices da mesh \"$meshName\" com componentType $componentType não suportado " +
                    "(aceites: ubyte, ushort, uint)"
            )
        }
        val ref = resolveAccessor(accessorIndex, bufferViews, accessors)
        val buffer = ref.view.first
        val base0 = ref.view.second + ref.byteOffset
        val stride = ref.view.third
        val step = if (stride == 0) size else stride
        val out = IntArray(ref.count)
        for (i in 0 until ref.count) {
            val base = base0 + i * step
            if (base + size > buffer.size) {
                throw GltfParseException("Índices da mesh \"$meshName\" fora dos limites do buffer")
            }
            out[i] = when (size) {
                1 -> buffer[base].toInt() and 0xFF
                2 -> (buffer[base].toInt() and 0xFF) or ((buffer[base + 1].toInt() and 0xFF) shl 8)
                else -> readI32(buffer, base)
            }
        }
        return out
    }

    private fun readI32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private const val MODE_TRIANGLES = 4
}
