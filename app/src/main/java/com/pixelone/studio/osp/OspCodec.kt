package com.pixelone.studio.osp

import com.pixelone.studio.pixel.PixelDocument
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Formato nativo de projeto do Pixel One: `.osp` (One Sprite Project).
 *
 * ESPECIFICAÇÃO (versão 2 — documentada também em docs/formato-osp.md):
 * - Contentor ZIP com até três entradas, por esta ordem:
 *     1. manifest.json — metadados (ver [OspManifest]);
 *     2. pixels.rgba   — píxeis RAW em bytes R,G,B,A por píxel, row-major,
 *                        SEM compressão adicional (o ZIP já comprime);
 *                        tamanho obrigatório = width * height * 4;
 *     3. studio3d.json — OPCIONAL (v2): referências do 3D Texture Studio
 *                        (modelo, associações material→textura, câmara,
 *                        amostragem) — ver [Studio3DState]. Projetos v1
 *                        (sem esta entrada) continuam a abrir sem alterações.
 * - SEM PERDAS: os valores ARGB são preservados ao byte, incluindo a
 *   transparência real (alpha 0 = transparente, 255 = opaco).
 *
 * SEGURANÇA E ROBUSTEZ:
 * - O ZIP é lido IN-MEMORY com lista branca de nomes de entrada ("manifest.json",
 *   "pixels.rgba" e "studio3d.json"); qualquer outra entrada torna o ficheiro
 *   inválido — não existe extração para o sistema de ficheiros, logo path
 *   traversal é impossível por construção.
 * - O tamanho total descomprimido é limitado: manifest ≤ [MAX_MANIFEST_BYTES],
 *   studio3d.json ≤ [MAX_STUDIO3D_BYTES] e pixels.rgba tem de medir EXATAMENTE
 *   width*height*4 validado a partir do manifesto lido antes (impede zip bombs
 *   e alocações excessivas).
 * - Número máximo de entradas: [MAX_ENTRIES].
 * - Versões desconhecidas do formato (superiores à suportada): rejeitadas de
 *   forma controlada com [OspUnsupportedVersionException] (mensagem clara ao
 *   utilizador, dados locais nunca corrompidos). Versões antigas (v1) abrem
 *   normalmente — compatibilidade para trás garantida pela Fase 3.
 */
object OspCodec {

    /** Identificador e versão do formato (v2 — adiciona studio3d.json opcional). */
    const val FORMAT_ID: String = "pixel-one-osp"
    const val FORMAT_VERSION: Int = 2

    const val ENTRY_MANIFEST: String = "manifest.json"
    const val ENTRY_PIXELS: String = "pixels.rgba"
    const val ENTRY_STUDIO3D: String = "studio3d.json"

    /** Limite de segurança para o manifesto (orçamento: 64 KB). */
    const val MAX_MANIFEST_BYTES: Int = 64 * 1024

    /** Limite de segurança para a secção 3D (referências — 64 KB chegam). */
    const val MAX_STUDIO3D_BYTES: Int = 64 * 1024

    /** Máximo de entradas aceites no ZIP (lista branca tem 3). */
    const val MAX_ENTRIES: Int = 16

    /** Ficheiro .osp malformado/inválido (erro controlado, mensagem clara). */
    class OspInvalidException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /** Versão do formato desconhecida (ex.: ficheiro mais recente que a app). */
    class OspUnsupportedVersionException(val foundVersion: Int) :
        IOException(
            "Formato .osp versão $foundVersion não suportado por esta versão do " +
                "Pixel One (suportada: $FORMAT_VERSION). Atualize a aplicação."
        )

    // ------------------------------------------------------------------
    // Escrita
    // ------------------------------------------------------------------

    /**
     * Escreve o projeto como .osp em [output]. O stream é fechado pelo chamador.
     *
     * @param manifest metadados do projeto (nome, dimensões, timestamps, paleta).
     * @param document documento com os píxeis.
     * @param studio3dJson conteúdo JSON da secção 3D (opcional, v2); null = não escreve a entrada.
     * @throws IOException em falha de escrita (inclui falta de espaço).
     */
    fun save(
        manifest: OspManifest,
        document: PixelDocument,
        output: OutputStream,
        studio3dJson: String? = null
    ) {
        // Validação ANTES de qualquer escrita: nunca deixar um ficheiro a meio.
        manifest.validateFor(document)

        val json = MiniJson.write(manifest.toJson())
        val rgba = document.toRgbaBytes()

        val zip = ZipOutputStream(output)
        try {
            val manifestEntry = ZipEntry(ENTRY_MANIFEST).apply {
                time = manifest.updatedAtEpochMs
            }
            zip.putNextEntry(manifestEntry)
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val pixelsEntry = ZipEntry(ENTRY_PIXELS).apply {
                time = manifest.updatedAtEpochMs
                size = rgba.size.toLong()
            }
            zip.putNextEntry(pixelsEntry)
            zip.write(rgba)
            zip.closeEntry()

            if (!studio3dJson.isNullOrBlank()) {
                val studio3dEntry = ZipEntry(ENTRY_STUDIO3D).apply {
                    time = manifest.updatedAtEpochMs
                }
                zip.putNextEntry(studio3dEntry)
                zip.write(studio3dJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } finally {
            zip.finish()
            zip.flush()
        }
    }

    // ------------------------------------------------------------------
    // Leitura
    // ------------------------------------------------------------------

    /** Resultado da leitura de um .osp. */
    data class LoadedProject(
        val manifest: OspManifest,
        val document: PixelDocument,
        /** Conteúdo JSON da secção 3D, ou null se o projeto não a tiver (v1). */
        val studio3dJson: String? = null
    )

    /**
     * Lê um projeto .osp de [input] (não fecha o stream).
     *
     * @throws OspInvalidException se o ficheiro for inválido/malformado.
     * @throws OspUnsupportedVersionException se a versão for desconhecida.
     */
    fun load(input: InputStream): LoadedProject {
        val zip = ZipInputStream(input, Charsets.UTF_8)

        var manifestJson: ByteArray? = null
        var pixelsRgba: ByteArray? = null
        var studio3dJson: ByteArray? = null
        var entries = 0

        while (true) {
            val entry: ZipEntry = try {
                zip.nextEntry ?: break
            } catch (e: EOFException) {
                throw OspInvalidException("ZIP truncado ou incompleto", e)
            }
            entries++
            if (entries > MAX_ENTRIES) {
                throw OspInvalidException("ZIP com entradas a mais (${MAX_ENTRIES} máximo)")
            }
            val name = entry.name
            // Lista branca estrita: apenas as três entradas conhecidas (a 3.ª é
            // opcional e só existe em projetos v2).
            when (name) {
                ENTRY_MANIFEST -> {
                    if (manifestJson != null) throw OspInvalidException("Entrada duplicada: $name")
                    manifestJson = readExactly(zip, MAX_MANIFEST_BYTES, name)
                }
                ENTRY_PIXELS -> {
                    if (pixelsRgba != null) throw OspInvalidException("Entrada duplicada: $name")
                    // Lê com um teto de segurança alto (o limite real vem do manifesto);
                    // pior caso permitido: 2048*2048*4 = 16 MB.
                    pixelsRgba = readExactly(zip, PixelDocument.MAX_PIXELS * 4, name)
                }
                ENTRY_STUDIO3D -> {
                    if (studio3dJson != null) throw OspInvalidException("Entrada duplicada: $name")
                    studio3dJson = readExactly(zip, MAX_STUDIO3D_BYTES, name)
                }
                else -> throw OspInvalidException("Entrada inesperada no ZIP: '$name'")
            }
            zip.closeEntry()
        }

        val jsonBytes = manifestJson
            ?: throw OspInvalidException("Ficheiro sem $ENTRY_MANIFEST")
        val rgba = pixelsRgba
            ?: throw OspInvalidException("Ficheiro sem $ENTRY_PIXELS")

        val manifest = parseManifest(jsonBytes)

        if (manifest.formatVersion > FORMAT_VERSION) {
            throw OspUnsupportedVersionException(manifest.formatVersion)
        }
        // formatVersion 1 (Fases 0-2, sem secção 3D) e 2 (Fase 3+) são ambas
        // aceites — compatibilidade com projetos antigos garantida.
        if (manifest.formatVersion < 1) {
            throw OspInvalidException("formatVersion inválida: ${manifest.formatVersion}")
        }

        val expected = manifest.width.toLong() * manifest.height.toLong() * 4L
        if (rgba.size.toLong() != expected) {
            throw OspInvalidException(
                "pixels.rgba tem ${rgba.size} bytes; esperado $expected " +
                    "(${manifest.width}x${manifest.height}x4)"
            )
        }

        val document = try {
            PixelDocument.fromRgbaBytes(manifest.width, manifest.height, rgba)
        } catch (e: IllegalArgumentException) {
            throw OspInvalidException("Dados de píxeis inválidos: ${e.message}", e)
        }
        val studio3dText = studio3dJson?.toString(Charsets.UTF_8)
        return LoadedProject(manifest, document, studio3dText)
    }

    /** Lê um .osp de um ByteArray (utilitário para testes e uso em memória). */
    fun load(bytes: ByteArray): LoadedProject = load(bytes.inputStream())

    /**
     * Lê um .osp de um ficheiro, com a ESTRATÉGIA ANTI-CORRUPÇÃO:
     * este método é usado na validação antes de substituir qualquer projeto
     * existente — um .osp só substitui o anterior depois de relido e validado.
     */
    fun readManifest(bytes: ByteArray): OspManifest {
        val zip = ZipInputStream(bytes.inputStream(), Charsets.UTF_8)
        var found: ByteArray? = null
        var entries = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            entries++
            if (entries > MAX_ENTRIES) throw OspInvalidException("ZIP com entradas a mais")
            if (entry.name == ENTRY_MANIFEST) {
                found = readExactly(zip, MAX_MANIFEST_BYTES, ENTRY_MANIFEST)
                zip.closeEntry()
                break // apenas o manifesto; o resto não interessa aqui
            }
            zip.closeEntry()
        }
        val jsonBytes = found ?: throw OspInvalidException("Ficheiro sem $ENTRY_MANIFEST")
        return parseManifest(jsonBytes)
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    /**
     * Lê a entrada atual do ZIP com limite estrito de bytes. Devolve exatamente
     * os bytes da entrada (sem bytes em excesso) e lança se exceder o limite.
     */
    private fun readExactly(zip: ZipInputStream, maxBytes: Int, entryName: String): ByteArray {
        val buffer = ByteArrayOutputStream(minOf(maxBytes, 8192).coerceAtLeast(256))
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = try {
                zip.read(chunk)
            } catch (e: EOFException) {
                throw OspInvalidException("Entrada '$entryName' truncada", e)
            }
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw OspInvalidException(
                    "Entrada '$entryName' excede o limite de $maxBytes bytes (ficheiro suspeito)"
                )
            }
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    private fun parseManifest(jsonBytes: ByteArray): OspManifest {
        val text = try {
            jsonBytes.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw OspInvalidException("manifest.json não é UTF-8 válido", e)
        }
        val root = try {
            MiniJson.parse(text)
        } catch (e: MiniJson.JsonParseException) {
            throw OspInvalidException("manifest.json inválido: ${e.message}", e)
        }
        if (root !is Map<*, *>) {
            throw OspInvalidException("manifest.json deveria ser um objeto JSON")
        }
        val obj = root.entries.associate { (k, v) -> k.toString() to v }

        val format = MiniJson.optionalString(obj, "format", "")
        if (format != FORMAT_ID) {
            throw OspInvalidException(
                "Este ficheiro não é um projeto .osp do Pixel One (format='$format')"
            )
        }
        val version = MiniJson.requireInt(obj, "formatVersion")
        val width = MiniJson.requireInt(obj, "width")
        val height = MiniJson.requireInt(obj, "height")
        if (width < 1 || height < 1 || width > PixelDocument.MAX_DIMENSION ||
            height > PixelDocument.MAX_DIMENSION ||
            width.toLong() * height.toLong() > PixelDocument.MAX_PIXELS
        ) {
            throw OspInvalidException("Dimensões inválidas no manifesto: ${width}x$height")
        }
        return try {
            OspManifest.fromJson(obj)
        } catch (e: MiniJson.JsonParseException) {
            throw OspInvalidException("manifest.json inválido: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw OspInvalidException("manifest.json inválido: ${e.message}", e)
        }
    }
}

/**
 * Metadados do projeto no manifest.json do .osp.
 *
 * Esquema v1 (compacto):
 * {
 *   "format": "pixel-one-osp",
 *   "formatVersion": 1,
 *   "app": "Pixel One",
 *   "name": "meu-sprite",
 *   "width": 64, "height": 64,
 *   "createdAtEpochMs": 1730000000000,
 *   "updatedAtEpochMs": 1730000000000,
 *   "palette": ["#FF000000", "#FFF57C00", ...]   // opcional, cores ARGB hex
 * }
 */
data class OspManifest(
    val formatVersion: Int = OspCodec.FORMAT_VERSION,
    val name: String,
    val width: Int,
    val height: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val palette: List<String>? = null
) {
    /** Valida coerência com o documento antes de gravar (evita ficheiros mentirosos). */
    fun validateFor(document: PixelDocument) {
        require(formatVersion == OspCodec.FORMAT_VERSION) {
            "formatVersion deve ser ${OspCodec.FORMAT_VERSION}"
        }
        require(name.isNotBlank()) { "O nome do projeto não pode ser vazio" }
        require(width == document.width && height == document.height) {
            "Manifesto ${width}x${height} não corresponde ao documento ${document.width}x${document.height}"
        }
        require(createdAtEpochMs >= 0 && updatedAtEpochMs >= 0) { "Timestamps inválidos" }
        palette?.forEach { p ->
            require(PALETTE_HEX_REGEX.matches(p)) { "Cor de paleta inválida: '$p'" }
        }
    }

    fun toJson(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["format"] = OspCodec.FORMAT_ID
        map["formatVersion"] = formatVersion
        map["app"] = APP_NAME
        map["name"] = name
        map["width"] = width
        map["height"] = height
        map["createdAtEpochMs"] = createdAtEpochMs
        map["updatedAtEpochMs"] = updatedAtEpochMs
        if (!palette.isNullOrEmpty()) map["palette"] = palette
        return map
    }

    companion object {
        const val APP_NAME: String = "Pixel One"
        private val PALETTE_HEX_REGEX = Regex("#[0-9A-Fa-f]{8}")

        /** Constrói a partir de um objeto JSON já validado nas dimensões. */
        fun fromJson(obj: Map<String, Any?>): OspManifest = OspManifest(
            formatVersion = MiniJson.requireInt(obj, "formatVersion"),
            name = MiniJson.requireString(obj, "name"),
            width = MiniJson.requireInt(obj, "width"),
            height = MiniJson.requireInt(obj, "height"),
            createdAtEpochMs = MiniJson.requireLong(obj, "createdAtEpochMs"),
            updatedAtEpochMs = MiniJson.requireLong(obj, "updatedAtEpochMs"),
            palette = MiniJson.optionalStringList(obj, "palette")
        )
    }
}
