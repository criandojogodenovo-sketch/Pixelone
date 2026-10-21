package com.pixelone.studio.osp

import com.pixelone.studio.pixel.LayerStack
import com.pixelone.studio.pixel.PixelDocument

/**
 * Codec da secção layers.json do .osp v3 (Ronda B — camadas).
 *
 * ESQUEMA (versão 1, estável):
 * {
 *   "version": 1,
 *   "active": 0,
 *   "layers": [
 *     {"name": "Base", "visible": 1, "opacity": 100},
 *     {"name": "Camada B", "visible": 1, "opacity": 60}
 *   ]
 * }
 *
 * - A ordem do array é a ORDEM DA PILHA (baixo→topo) — os píxeis crus vivem
 *   nas entradas layer-0.rgba .. layer-(n-1).rgba do mesmo ZIP (ver [OspCodec]).
 * - "visible" é 0/1 (JSON compacto, sem ambiguidade de booleanos no MiniJson).
 * - "opacity" é 0..100 (int).
 * - Projetos antigos (sem esta secção) NUNCA chegam aqui — abrem como 1 camada
 *   com os píxeis de pixels.rgba (regra da Ronda B).
 */
object LayersCodec {

    const val SECTION_VERSION: Int = 1

    /** Serializa o estado da pilha (sem píxeis — vão nas entradas layer-N.rgba). */
    fun toJson(stack: LayerStack): String {
        val layersList = ArrayList<Map<String, Any?>>(stack.layers.size)
        for (layer in stack.layers) {
            layersList.add(
                linkedMapOf(
                    "name" to layer.name,
                    "visible" to if (layer.visible) 1 else 0,
                    "opacity" to layer.opacity.coerceIn(0, 100)
                )
            )
        }
        return MiniJson.write(
            linkedMapOf(
                "version" to SECTION_VERSION,
                "active" to stack.activeIndex.coerceIn(0, stack.layers.size - 1),
                "layers" to layersList
            )
        )
    }

    /**
     * Lê layers.json e aplica os METADADOS a [stack] (que o chamador criou
     * com o número certo de camadas — os píxeis das entradas layer-N.rgba são
     * aplicados por [fromRgbaList]/chamador antes ou depois desta função).
     *
     * @throws OspCodec.OspInvalidException se a secção for inválida/incoerente.
     */
    fun applyTo(json: String, stack: LayerStack) {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonParseException) {
            throw OspCodec.OspInvalidException("layers.json inválido: ${e.message}", e)
        }
        if (root !is Map<*, *>) {
            throw OspCodec.OspInvalidException("layers.json deveria ser um objeto JSON")
        }
        val obj = root.entries.associate { (k, v) -> k.toString() to v }
        val version = MiniJson.optionalInt(obj, "version", SECTION_VERSION)
        if (version > SECTION_VERSION) {
            throw OspCodec.OspInvalidException(
                "layers.json versão $version não suportada (suportada: $SECTION_VERSION)"
            )
        }
        val layersRaw = obj["layers"] as? List<*>
            ?: throw OspCodec.OspInvalidException("layers.json sem a lista 'layers'")
        if (layersRaw.size != stack.layers.size) {
            throw OspCodec.OspInvalidException(
                "layers.json tem ${layersRaw.size} camadas; o ficheiro tem " +
                    "${stack.layers.size} entradas layer-N.rgba"
            )
        }
        for (i in stack.layers.indices) {
            val raw = layersRaw[i]
            if (raw !is Map<*, *>) {
                throw OspCodec.OspInvalidException("Camada $i não é um objeto")
            }
            val layerObj = raw.entries.associate { (k, v) -> k.toString() to v }
            val name = MiniJson.optionalString(layerObj, "name", "Camada ${i + 1}").ifBlank { "Camada ${i + 1}" }
            val visible = (MiniJson.optionalInt(layerObj, "visible", 1) != 0)
            val opacity = MiniJson.optionalInt(layerObj, "opacity", 100).coerceIn(0, 100)
            val layer = stack.layers[i]
            val renamed = LayerStack.PixelLayer(layer.id, name, layer.doc, visible, opacity)
            stack.layers[i] = renamed
        }
        stack.setActive(MiniJson.optionalInt(obj, "active", 0))
    }

    /**
     * Reconstrói uma [LayerStack] a partir dos píxeis RGBA crus das camadas
     * (ordem baixo→topo, formato do .osp: bytes R,G,B,A) + metadados JSON.
     */
    fun fromRgbaList(width: Int, height: Int, layerPixels: List<ByteArray>, json: String): LayerStack {
        require(layerPixels.isNotEmpty()) { "Lista de camadas vazia" }
        require(layerPixels.size <= OspCodec.MAX_LAYERS) {
            "Demasiadas camadas no ficheiro (${layerPixels.size})"
        }
        val stack = LayerStack(width, height)
        layerPixels.forEachIndexed { i, rgba ->
            val doc = try {
                PixelDocument.fromRgbaBytes(width, height, rgba)
            } catch (e: IllegalArgumentException) {
                throw OspCodec.OspInvalidException("Píxeis da camada $i inválidos: ${e.message}", e)
            }
            stack.layers.add(
                LayerStack.PixelLayer(i.toLong() + 1, "Camada ${i + 1}", doc, true, 100)
            )
        }
        applyTo(json, stack)
        return stack
    }
}
