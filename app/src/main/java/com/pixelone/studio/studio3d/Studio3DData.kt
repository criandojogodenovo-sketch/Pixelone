package com.pixelone.studio.studio3d

import com.pixelone.studio.osp.MiniJson

/**
 * Estado do 3D Texture Studio persistido no .osp (secção studio3d.json).
 *
 * REQUISITO FASE 3: "Guardar no .osp as referências às texturas, materiais e
 * configurações, com versionamento." O .osp mantém os píxeis (pixels.rgba —
 * que SÃO a textura do Texture Studio) e passa a ter uma entrada opcional
 * studio3d.json com REFERÊNCIAS (nunca bytes de modelo — GLBs ficam fora do
 * ZIP para não inflacionar projetos):
 *
 *  - modelo: tipo (demo/importado/none), nome, tamanho, SHA-256, contagens;
 *  - materiais: associação material → textura (DOCUMENT = pixels.rgba,
 *    EMBEDDED = textura embutida do GLB, NONE = cor sólida);
 *  - configurações: amostragem (nearest/linear), sobreposição de UV, modo
 *    ativo e a pose da câmara;
 *  - versionamento: manifest.formatVersion passou a 2; a secção tem o seu
 *    próprio schemaVersion — projetos v1 (sem secção) continuam a abrir.
 *
 * Kotlin puro com [MiniJson] (o mesmo parser do .osp) — 100% testável em JVM.
 */
data class Studio3DState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val modelKind: ModelKind = ModelKind.DEMO,
    val modelFileName: String? = null,
    val modelFileBytes: Long? = null,
    val modelSha256: String? = null,
    val modelTriangleCount: Int = 0,
    val modelMeshCount: Int = 0,
    val modelMaterialCount: Int = 0,
    val modelHasUv: Boolean = false,
    /** Associação material (por índice) → referência de textura. */
    val bindings: Map<Int, TextureRef> = emptyMap(),
    val sampling: SamplingMode = SamplingMode.NEAREST,
    val showUvOverlay: Boolean = true,
    val mode: StudioMode = StudioMode.THREE_D,
    val camera: OrbitCamera.CameraSnapshot? = null
) {

    enum class ModelKind { DEMO, IMPORTED, NONE }
    enum class SamplingMode { NEAREST, LINEAR }
    enum class StudioMode { THREE_D, UV, SPLIT }
    enum class TextureRef { DOCUMENT, EMBEDDED, NONE }

    // ------------------------------------------------------------------
    // Serialização (JSON compacto, chaves curtas estáveis)
    // ------------------------------------------------------------------

    fun toJson(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["schemaVersion"] = schemaVersion
        map["model"] = mapOf(
            "kind" to modelKind.name,
            "fileName" to modelFileName,
            "fileBytes" to modelFileBytes,
            "sha256" to modelSha256,
            "triangles" to modelTriangleCount,
            "meshes" to modelMeshCount,
            "materials" to modelMaterialCount,
            "hasUv" to modelHasUv
        )
        val bindingsList = bindings.entries
            .sortedBy { it.key }
            .map { listOf<Any?>(it.key, it.value.name) }
        map["bindings"] = bindingsList
        map["settings"] = mapOf(
            "sampling" to sampling.name,
            "showUv" to showUvOverlay,
            "mode" to mode.name
        )
        camera?.let {
            map["camera"] = mapOf(
                "yaw" to it.yawDeg, "pitch" to it.pitchDeg, "distance" to it.distance,
                "tx" to it.targetX, "ty" to it.targetY, "tz" to it.targetZ
            )
        }
        return map
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1

        /**
         * Lê o estado de um objeto JSON (da entrada studio3d.json). Erros de
         * conteúdo lançam IllegalArgumentException com mensagem clara — a
         * chamada decide ignorar a secção (projeto continua a abrir sem 3D).
         */
        fun fromJson(root: Map<String, Any?>): Studio3DState {
            val version = MiniJson.optionalInt(root, "schemaVersion", SCHEMA_VERSION)
            if (version > SCHEMA_VERSION) {
                throw IllegalArgumentException(
                    "studio3d.json versão $version mais recente que a suportada ($SCHEMA_VERSION) — " +
                        "o projeto abre sem o estado 3D."
                )
            }
            val modelMap = root["model"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val settings = root["settings"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val cameraMap = root["camera"] as? Map<String, Any?>

            val kind = when ((modelMap["kind"] as? String)?.uppercase()) {
                "IMPORTED" -> ModelKind.IMPORTED
                "NONE" -> ModelKind.NONE
                else -> ModelKind.DEMO
            }
            val bindings = HashMap<Int, TextureRef>()
            (root["bindings"] as? List<*>)?.forEach { entry ->
                val pair = entry as? List<*> ?: return@forEach
                if (pair.size == 2) {
                    val materialIndex = (pair[0] as? Number)?.toInt() ?: return@forEach
                    val ref = when ((pair[1] as? String)?.uppercase()) {
                        "DOCUMENT" -> TextureRef.DOCUMENT
                        "EMBEDDED" -> TextureRef.EMBEDDED
                        else -> TextureRef.NONE
                    }
                    bindings[materialIndex] = ref
                }
            }
            return Studio3DState(
                schemaVersion = SCHEMA_VERSION,
                modelKind = kind,
                modelFileName = (modelMap["fileName"] as? String)?.takeIf { it.isNotBlank() },
                modelFileBytes = (modelMap["fileBytes"] as? Number)?.toLong(),
                modelSha256 = (modelMap["sha256"] as? String)?.takeIf { it.isNotBlank() },
                modelTriangleCount = (modelMap["triangles"] as? Number)?.toInt() ?: 0,
                modelMeshCount = (modelMap["meshes"] as? Number)?.toInt() ?: 0,
                modelMaterialCount = (modelMap["materials"] as? Number)?.toInt() ?: 0,
                modelHasUv = modelMap["hasUv"] == true,
                bindings = bindings,
                sampling = when ((settings["sampling"] as? String)?.uppercase()) {
                    "LINEAR" -> SamplingMode.LINEAR
                    else -> SamplingMode.NEAREST
                },
                showUvOverlay = settings["showUv"] != false,
                mode = when ((settings["mode"] as? String)?.uppercase()) {
                    "UV" -> StudioMode.UV
                    "SPLIT" -> StudioMode.SPLIT
                    else -> StudioMode.THREE_D
                },
                camera = cameraMap?.let {
                    OrbitCamera.CameraSnapshot(
                        yawDeg = MiniJson.optionalFloat(it, "yaw", 35f),
                        pitchDeg = MiniJson.optionalFloat(it, "pitch", 25f),
                        distance = MiniJson.optionalFloat(it, "distance", 3.2f),
                        targetX = MiniJson.optionalFloat(it, "tx", 0f),
                        targetY = MiniJson.optionalFloat(it, "ty", 0f),
                        targetZ = MiniJson.optionalFloat(it, "tz", 0f)
                    )
                }
            )
        }
    }
}
