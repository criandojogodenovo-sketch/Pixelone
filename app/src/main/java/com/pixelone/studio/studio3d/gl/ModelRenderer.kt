package com.pixelone.studio.studio3d.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.pixelone.studio.perf.PerfLog
import com.pixelone.studio.studio3d.DirtyRect
import com.pixelone.studio.studio3d.GltfParser
import com.pixelone.studio.studio3d.GltfParser.AlphaMode
import com.pixelone.studio.studio3d.GltfParser.GltfModel
import com.pixelone.studio.studio3d.OrbitCamera
import com.pixelone.studio.studio3d.RgbaPixels
import com.pixelone.studio.studio3d.Studio3DState.SamplingMode
import com.pixelone.studio.studio3d.Studio3DState.TextureRef
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderer OpenGL ES do 3D Texture Studio (Fase 3).
 *
 * DECISÃO TÉCNICA: OpenGL ES via GLSurfaceView em Kotlin — "nativo" no
 * sentido do requisito (OpenGL ES real, shaders próprios, buffers de
 * hardware), sem reinventar um renderer em C++: o JNI só existiria com
 * benefício mensurável (regra do projeto — o gargalo é a GPU, não o Kotlin).
 * O motor C++17 existente continua a ser usado na PINTURA (traço/balde).
 *
 * REQUISITOS CUMPRIDOS AQUI:
 *  - amostragem Nearest Neighbor (padrão) ou Linear (opcional), trocável em
 *    runtime (GL_NEAREST/GL_LINEAR);
 *  - ATUALIZAÇÃO PARCIAL da textura na GPU: glTexSubImage2D APENAS da região
 *    suja — nunca a textura inteira por traço (requisito do modo Dividido);
 *  - transparência e cores RGBA preservadas: alfa reto (nunca premultiplicado),
 *    blend SRC_ALPHA para materiais BLEND, discard para MASK;
 *  - realce da ilha UV selecionada no 3D (2.º passe com glPolygonOffset);
 *  - memória: VBO/IBO/texturas criados UMA vez por cena, eliminados com
 *    glDelete* quando a cena muda; zero alocações por frame.
 *
 * THREADING: a thread GL consome uma fila de comandos (cena nova, textura do
 * documento, região suja, amostragem, realce) — a UI nunca chama GLES.
 */
class ModelRenderer(
    private val camera: OrbitCamera,
    private val perfTag: String = "PixelOnePerf/3D"
) : GLSurfaceView.Renderer {

    // ------------------------------------------------------------------
    // Shaders (GLSL ES 1.00 — funciona em contexto ES 2.0 e 3.0)
    // ------------------------------------------------------------------

    private val vertexShader = """
        uniform mat4 uMVP;
        attribute vec3 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVP * vec4(aPosition, 1.0);
            vUV = aTexCoord;
        }
    """

    private val fragmentShader = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTexture;
        uniform vec4 uBaseColor;
        uniform float uUseTexture;
        uniform float uAlphaMode;   // 0 OPAQUE, 1 MASK, 2 BLEND
        uniform float uAlphaCutoff;
        uniform float uHighlight;
        void main() {
            vec4 color = uBaseColor;
            if (uUseTexture > 0.5) {
                color *= texture2D(uTexture, vUV);
            }
            if (uAlphaMode > 1.5) {
                gl_FragColor = color;                       // BLEND (alfa reto)
            } else if (uAlphaMode > 0.5) {
                if (color.a < uAlphaCutoff) discard;        // MASK
                gl_FragColor = vec4(color.rgb, 1.0);
            } else {
                gl_FragColor = vec4(color.rgb, 1.0);        // OPAQUE
            }
            if (uHighlight > 0.5) {
                gl_FragColor.rgb = mix(gl_FragColor.rgb, vec3(1.0, 0.55, 0.0), 0.45);
            }
        }
    """

    // ------------------------------------------------------------------
    // Fila de comandos (UI → thread GL)
    // ------------------------------------------------------------------

    private val pendingOps = ConcurrentLinkedQueue<() -> Unit>()

    // ------------------------------------------------------------------
    // Estado GL (apenas thread GL)
    // ------------------------------------------------------------------

    private var program = 0
    private var locMVP = 0
    private var locPosition = 0
    private var locTexCoord = 0
    private var locTexture = 0
    private var locBaseColor = 0
    private var locUseTexture = 0
    private var locAlphaMode = 0
    private var locAlphaCutoff = 0
    private var locHighlight = 0

    private var scene: GlScene? = null

    private var documentTexture = 0
    private var documentTexWidth = 0
    private var documentTexHeight = 0

    private var sampling: SamplingMode = SamplingMode.NEAREST

    /** Wireframe (Ronda B) — estado aplicado na thread GL via fila de comandos. */
    private var wireframeEnabled = false

    private var highlightRenderable: Int? = null
    private var highlightTriangles: IntArray? = null
    private var highlightIbo = 0
    private var highlightIndexCount = 0
    private var highlightIndexType = 0

    private var viewportWidth = 1
    private var viewportHeight = 1

    private val vpMatrix = FloatArray(16)

    /** Buffer reutilizado para subidas de textura (completa/sub-rect). */
    private var uploadScratch: ByteBuffer? = null

    // ------------------------------------------------------------------
    // API pública (qualquer thread)
    // ------------------------------------------------------------------

    /** Substitui a cena por [model] com as associações material→textura dadas. */
    fun setScene(
        model: GltfModel,
        embeddedBitmaps: Map<Int, Bitmap>,
        bindings: Map<Int, TextureRef>,
        samplingMode: SamplingMode
    ) {
        pendingOps.add {
            sampling = samplingMode
            rebuildScene(model, embeddedBitmaps, bindings)
        }
    }

    /** Troca Nearest/Linear das texturas existentes. */
    fun setSampling(samplingMode: SamplingMode) {
        pendingOps.add {
            sampling = samplingMode
            scene?.materialTextures?.values?.forEach { if (it != 0) applySampling(it) }
            if (documentTexture != 0) applySampling(documentTexture)
            PerfLog.i(perfTag, "amostragem=${samplingMode.name}")
        }
    }

    /** (Re)cria a textura do documento com estas dimensões (chamar antes do upload). */
    fun replaceDocumentTexture(pixels: IntArray, width: Int, height: Int) {
        pendingOps.add {
            ensureDocumentTexture(width, height)
            uploadDocumentFull(pixels)
            // Materiais com binding DOCUMENT passam a apontar para a nova id:
            scene?.let { s ->
                s.model.materials.forEachIndexed { i, _ ->
                    if (s.bindingOf(i) == TextureRef.DOCUMENT) s.materialTextures[i] = documentTexture
                }
            }
        }
    }

    /**
     * ATUALIZAÇÃO PARCIAL (requisito Fase 3): lê APENAS a região suja do
     * documento e envia-a com glTexSubImage2D. Chamada por traço/fase.
     */
    fun updateTextureRegion(rect: DirtyRect, getPixel: (Int) -> Int, docWidth: Int) {
        val w = rect.width
        val h = rect.height
        if (w <= 0 || h <= 0 || documentTexWidth != docWidth) return
        var buf = uploadScratch
        val needed = w * h * 4
        if (buf == null || buf.capacity() < needed) {
            buf = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            uploadScratch = buf
        }
        RgbaPixels.extractRectToRgba(docWidth, getPixel, rect, buf)
        pendingOps.add {
            if (documentTexture != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, documentTexture)
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, rect.x0, rect.y0, w, h,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
                )
                PerfLog.i(perfTag, "texSubImage ${w}x${h} em (${rect.x0},${rect.y0})")
            }
        }
    }

    /** Realce da ilha selecionada no 3D (null limpa). */
    fun setHighlight(renderableIndex: Int?, islandTriangles: IntArray?) {
        pendingOps.add {
            highlightRenderable = renderableIndex
            highlightTriangles = islandTriangles
            rebuildHighlightBuffer()
        }
    }

    /**
     * Interruptor de WIREFRAME (Ronda B — visualização): desenha as arestas
     * dos triângulos em laranja por cima da superfície. Construção do IBO de
     * linhas é LAZY (1.ª vez que é mostrado) e por primitiva.
     */
    fun setWireframe(enabled: Boolean) {
        pendingOps.add {
            if (wireframeEnabled != enabled) {
                wireframeEnabled = enabled
                if (enabled) scene?.renderables?.forEach { ensureWireIbo(it) }
            }
        }
    }

    /** Liberta todos os recursos GL (no onDestroy da vista). */
    fun release() {
        pendingOps.add {
            destroySceneLocked()
            if (documentTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(documentTexture), 0)
                documentTexture = 0
            }
            destroyHighlightBufferLocked()
        }
    }

    // ------------------------------------------------------------------
    // Renderer (thread GL)
    // ------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        locMVP = GLES20.glGetUniformLocation(program, "uMVP")
        locPosition = GLES20.glGetAttribLocation(program, "aPosition")
        locTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        locTexture = GLES20.glGetUniformLocation(program, "uTexture")
        locBaseColor = GLES20.glGetUniformLocation(program, "uBaseColor")
        locUseTexture = GLES20.glGetUniformLocation(program, "uUseTexture")
        locAlphaMode = GLES20.glGetUniformLocation(program, "uAlphaMode")
        locAlphaCutoff = GLES20.glGetUniformLocation(program, "uAlphaCutoff")
        locHighlight = GLES20.glGetUniformLocation(program, "uHighlight")
        GLES20.glClearColor(0.04f, 0.04f, 0.04f, 1f) // #0A0A0A (tema Pixel One)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        // RONDA A (C): o contexto pode ser NOVO (app em 2.º plano, rotação,
        // mudança de GPU) — TODOS os ids GL guardados ficaram STALE. Sem esta
        // reposição, ensureDocumentTexture fazia early-return (mesmas
        // dimensões) e o cubo ficava a amostrar uma textura inválida (cor
        // plana/undefined em vez do desenho). O id antigo pertence a um
        // contexto morto: apagá-lo seria operar sobre ids inválidos.
        documentTexture = 0
        highlightIbo = 0
        highlightIndexCount = 0

        // Contexto pode ser novo: recriar a textura do documento e a cena.
        if (documentTexWidth > 0 && documentTexHeight > 0) {
            ensureDocumentTexture(documentTexWidth, documentTexHeight)
            lastDocumentPixels?.let { uploadDocumentFull(it) }
        }
        val request = lastSceneRequest
        if (request != null) {
            rebuildScene(request.model, request.embeddedBitmaps, request.bindings)
        }
        // Realce da seleção: o IBO também morreu com o contexto.
        if (highlightRenderable != null && highlightTriangles != null) {
            rebuildHighlightBuffer()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        while (true) {
            val op = pendingOps.poll() ?: break
            try {
                op.invoke()
            } catch (t: Throwable) {
                PerfLog.w(perfTag, "Comando GL falhou: ${t.message}")
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = scene ?: return
        val aspect = if (viewportHeight > 0) viewportWidth.toFloat() / viewportHeight else 1f
        synchronized(camera) { camera.viewProjection(aspect, vpMatrix) }
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(locMVP, 1, false, vpMatrix, 0)
        GLES20.glUniform1i(locTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(locHighlight, 0f)

        // Opacos e MASK primeiro; BLEND por último (sem depth write).
        val ordered = current.renderables.sortedBy { it.alphaModeInt }
        for (r in ordered) drawRenderable(current, r)

        // Realce da ilha selecionada.
        val hlIbo = highlightIbo
        val hlRenderable = highlightRenderable?.let { current.byIndex[it] }
        if (hlIbo != 0 && highlightIndexCount > 0 && hlRenderable != null) {
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glPolygonOffset(-1f, -2f)
            GLES20.glUniform1f(locHighlight, 1f)
            drawElementsWithBuffers(hlRenderable, hlIbo, highlightIndexCount, highlightIndexType)
            GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glUniform1f(locHighlight, 0f)
        }

        // WIREFRAME (Ronda B): arestas em laranja por cima da superfície.
        if (wireframeEnabled) drawWireframe(current)
    }

    /** Desenha as arestas de TODAS as primitivas (gl.LINES, cor laranja). */
    private fun drawWireframe(current: GlScene) {
        // Cor de linhas sobre o modelo (uniformes do MESMO programa).
        GLES20.glUniform4f(locBaseColor, 0.96f, 0.55f, 0.05f, 1f)
        GLES20.glUniform1f(locUseTexture, 0f)
        GLES20.glUniform1f(locAlphaMode, 0f)
        GLES20.glUniform1f(locHighlight, 0f)
        GLES20.glLineWidth(1f)
        // LEQUAL: linhas coplanares com a superfície passam o teste de profundidade.
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(false)
        for (r in current.renderables) {
            ensureWireIbo(r)
            if (r.wireIbo != 0 && r.wireIndexCount > 0) {
                drawElementsWithBuffers(r, r.wireIbo, r.wireIndexCount, r.wireIndexType)
            }
        }
        GLES20.glDepthMask(true)
        GLES20.glDepthFunc(GLES20.GL_LESS)
    }

    /** Constrói o IBO de linhas da primitiva (uma vez — 3 arestas por triângulo). */
    private fun ensureWireIbo(r: GlRenderable) {
        if (r.wireIbo != 0) return
        val triCount = r.indexCount / 3
        if (triCount <= 0) return
        if (triCount * 3 > 65535) {
            // Nº de vértices > 65535: índices de linhas em UINT (ES 3.0).
            val indices = IntArray(triCount * 6)
            var o = 0
            var i = 0
            while (i + 2 < r.source.indices.size) {
                val a = r.source.indices[i]; val b = r.source.indices[i + 1]; val c = r.source.indices[i + 2]
                indices[o++] = a; indices[o++] = b
                indices[o++] = b; indices[o++] = c
                indices[o++] = c; indices[o++] = a
                i += 3
            }
            val intBuf = directIntBuffer(indices.size).put(indices)
            intBuf.position(0)
            val ibo = IntArray(1); GLES20.glGenBuffers(1, ibo, 0)
            if (ibo[0] == 0) return
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER, intBuf.capacity() * 4, intBuf, GLES20.GL_STATIC_DRAW
            )
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            r.wireIbo = ibo[0]
            r.wireIndexCount = indices.size
            r.wireIndexType = GLES30.GL_UNSIGNED_INT
        } else {
            val indices = ShortArray(triCount * 6)
            var o = 0
            var i = 0
            while (i + 2 < r.source.indices.size) {
                val a = r.source.indices[i]; val b = r.source.indices[i + 1]; val c = r.source.indices[i + 2]
                indices[o++] = a.toShort(); indices[o++] = b.toShort()
                indices[o++] = b.toShort(); indices[o++] = c.toShort()
                indices[o++] = c.toShort(); indices[o++] = a.toShort()
                i += 3
            }
            val shortBuf = ByteBuffer.allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer()
            shortBuf.put(indices)
            shortBuf.position(0)
            val ibo = IntArray(1); GLES20.glGenBuffers(1, ibo, 0)
            if (ibo[0] == 0) return
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
            GLES20.glBufferData(
                GLES20.GL_ELEMENT_ARRAY_BUFFER, shortBuf.capacity() * 2, shortBuf, GLES20.GL_STATIC_DRAW
            )
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            r.wireIbo = ibo[0]
            r.wireIndexCount = indices.size
            r.wireIndexType = GLES20.GL_UNSIGNED_SHORT
        }
    }

    // ------------------------------------------------------------------
    // Cena
    // ------------------------------------------------------------------

    private class GlRenderable(
        val source: GltfParser.Renderable,
        val positionVbo: Int,
        val uvVbo: Int,
        val indexIbo: Int,
        val indexCount: Int,
        val indexType: Int,
        val alphaModeInt: Int,
        /** IBO de LINHAS do wireframe (lazy — construído ao ativar o modo). */
        var wireIbo: Int = 0,
        var wireIndexCount: Int = 0,
        var wireIndexType: Int = 0
    )

    private class GlScene(
        val model: GltfModel,
        val renderables: List<GlRenderable>,
        val byIndex: Map<Int, GlRenderable>,
        /** id de textura por material (EMBEDDED e DOCUMENT já resolvidos). */
        val materialTextures: MutableMap<Int, Int>,
        val bindings: Map<Int, TextureRef>
    ) {
        fun bindingOf(materialIndex: Int): TextureRef =
            bindings[materialIndex]
                ?: model.materials.getOrNull(materialIndex)?.textureImageIndex?.let { TextureRef.EMBEDDED }
                ?: TextureRef.DOCUMENT
    }

    private class SceneRequest(
        val model: GltfModel,
        val embeddedBitmaps: Map<Int, Bitmap>,
        val bindings: Map<Int, TextureRef>
    )

    private var lastSceneRequest: SceneRequest? = null
    private var lastDocumentPixels: IntArray? = null

    private fun rebuildScene(model: GltfModel, embeddedBitmaps: Map<Int, Bitmap>, bindings: Map<Int, TextureRef>) {
        destroySceneLocked()
        lastSceneRequest = SceneRequest(model, embeddedBitmaps, bindings)

        val startNanos = System.nanoTime()
        val materialTextures = HashMap<Int, Int>()
        model.materials.forEachIndexed { matIndex, material ->
            when (bindings[matIndex]
                ?: material.textureImageIndex?.let { TextureRef.EMBEDDED }
                ?: TextureRef.DOCUMENT) {
                TextureRef.EMBEDDED -> {
                    val bmp = material.textureImageIndex?.let { embeddedBitmaps[it] }
                    if (bmp != null) materialTextures[matIndex] = uploadBitmapTexture(bmp)
                }
                TextureRef.DOCUMENT -> {
                    if (documentTexture != 0) materialTextures[matIndex] = documentTexture
                }
                TextureRef.NONE -> { /* cor sólida */ }
            }
        }

        val renderables = ArrayList<GlRenderable>(model.renderables.size)
        val byIndex = HashMap<Int, GlRenderable>()
        for ((index, r) in model.renderables.withIndex()) {
            val alphaMode = model.materials.getOrNull(r.materialIndex)?.alphaMode ?: AlphaMode.OPAQUE
            uploadRenderable(r, alphaMode)?.let {
                renderables.add(it)
                byIndex[index] = it
            }
        }
        scene = GlScene(model, renderables, byIndex, materialTextures, bindings)
        val ms = (System.nanoTime() - startNanos) / 1_000_000L
        PerfLog.i(
            perfTag,
            "cena aplicada: ${renderables.size} primitivas, ${model.triangleCount} triângulos, " +
                "${materialTextures.size} texturas em $ms ms"
        )
    }

    private fun uploadRenderable(r: GltfParser.Renderable, alphaMode: AlphaMode): GlRenderable? {
        if (r.indices.isEmpty() || r.positions.size < 3) return null
        val posBuf = directFloatBuffer(r.positions.size).put(r.positions)
        posBuf.position(0)
        val maxIndex = r.indices.max()

        val posVbo = IntArray(1); GLES20.glGenBuffers(1, posVbo, 0)
        val ibo = IntArray(1); GLES20.glGenBuffers(1, ibo, 0)
        var uvVbo = 0
        if (posVbo[0] == 0 || ibo[0] == 0) {
            if (posVbo[0] != 0) GLES20.glDeleteBuffers(1, posVbo, 0)
            if (ibo[0] != 0) GLES20.glDeleteBuffers(1, ibo, 0)
            PerfLog.w(perfTag, "Sem buffers GL disponíveis")
            return null
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, posVbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, posBuf.capacity() * 4, posBuf, GLES20.GL_STATIC_DRAW)

        val uvBuffer = r.uvs
        if (uvBuffer != null) {
            val uvBuf = directFloatBuffer(uvBuffer.size).put(uvBuffer)
            uvBuf.position(0)
            val uvArr = IntArray(1); GLES20.glGenBuffers(1, uvArr, 0)
            uvVbo = uvArr[0]
            if (uvVbo != 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, uvVbo)
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, uvBuf.capacity() * 4, uvBuf, GLES20.GL_STATIC_DRAW)
            } else {
                PerfLog.w(perfTag, "Sem VBO de UV — primitiva desenha sem textura")
            }
        }

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        val indexType: Int
        if (maxIndex <= 65535) {
            val shortBuf = ByteBuffer.allocateDirect(r.indices.size * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer()
            shortBuf.put(ShortArray(r.indices.size) { r.indices[it].toShort() })
            shortBuf.position(0)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, shortBuf.capacity() * 2, shortBuf, GLES20.GL_STATIC_DRAW)
            indexType = GLES20.GL_UNSIGNED_SHORT
        } else {
            val intBuf = directIntBuffer(r.indices.size).put(r.indices)
            intBuf.position(0)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, intBuf.capacity() * 4, intBuf, GLES20.GL_STATIC_DRAW)
            indexType = GLES30.GL_UNSIGNED_INT // contexto ES 3.0 (minSdk 26)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        val alphaModeInt = when (alphaMode) {
            AlphaMode.BLEND -> 2
            AlphaMode.MASK -> 1
            AlphaMode.OPAQUE -> 0
        }
        return GlRenderable(r, posVbo[0], uvVbo, ibo[0], r.indices.size, indexType, alphaModeInt)
    }

    /**
     * Textura EMBEDDED: caminho EXATO igual ao do documento — getPixels (alfa
     * reto) → bytes RGBA → glTexImage2D. GLUtils NÃO é usado (premultiplica).
     */
    private fun uploadBitmapTexture(bmp: Bitmap): Int {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        if (tex[0] == 0) return 0
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        val buffer = RgbaPixels.argbToRgbaBuffer(pixels, uploadScratch)
        uploadScratch = buffer
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            bmp.width, bmp.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        applySampling(tex[0])
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return tex[0]
    }

    private fun ensureDocumentTexture(width: Int, height: Int) {
        if (documentTexture != 0 && documentTexWidth == width && documentTexHeight == height) return
        if (documentTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(documentTexture), 0)
            documentTexture = 0
        }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        documentTexture = tex[0]
        documentTexWidth = width
        documentTexHeight = height
        if (documentTexture != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, documentTexture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            applySampling(documentTexture)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
    }

    private fun uploadDocumentFull(pixels: IntArray) {
        if (documentTexture == 0) return
        lastDocumentPixels = pixels
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, documentTexture)
        val buffer = RgbaPixels.argbToRgbaBuffer(pixels, uploadScratch)
        uploadScratch = buffer
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0,
            documentTexWidth, documentTexHeight,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        PerfLog.i(perfTag, "textura do documento na GPU (${documentTexWidth}x${documentTexHeight})")
    }

    private fun applySampling(tex: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val minFilter: Int
        val magFilter: Int
        if (sampling == SamplingMode.LINEAR) {
            minFilter = GLES20.GL_LINEAR; magFilter = GLES20.GL_LINEAR
        } else {
            // NEAREST NEIGHBOR (requisito): cada píxel da pixel art nítido.
            minFilter = GLES20.GL_NEAREST; magFilter = GLES20.GL_NEAREST
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, magFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    // ------------------------------------------------------------------
    // Desenho
    // ------------------------------------------------------------------

    private fun drawRenderable(scene: GlScene, r: GlRenderable) {
        val material = scene.model.materials.getOrNull(r.source.materialIndex) ?: return
        val binding = scene.bindingOf(r.source.materialIndex)
        val texId = when (binding) {
            TextureRef.DOCUMENT -> documentTexture
            TextureRef.EMBEDDED -> scene.materialTextures[r.source.materialIndex] ?: 0
            TextureRef.NONE -> 0
        }
        val useTexture = texId != 0 && r.source.uvs != null

        GLES20.glUniform4f(
            locBaseColor,
            material.baseColorFactor[0], material.baseColorFactor[1],
            material.baseColorFactor[2], material.baseColorFactor[3]
        )
        GLES20.glUniform1f(locUseTexture, if (useTexture) 1f else 0f)
        GLES20.glUniform1f(locAlphaMode, r.alphaModeInt.toFloat())
        GLES20.glUniform1f(locAlphaCutoff, material.alphaCutoff)

        if (r.alphaModeInt == 2) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(
                GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA
            )
            GLES20.glDepthMask(false)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDepthMask(true)
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (useTexture) texId else 0)
        drawElementsWithBuffers(r, r.indexIbo, r.indexCount, r.indexType)
        if (r.alphaModeInt == 2) {
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private val zeroUv: FloatBuffer =
        ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(0f, 0f)).apply { position(0) }

    private fun drawElementsWithBuffers(r: GlRenderable, ibo: Int, indexCount: Int, indexType: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, r.positionVbo)
        GLES20.glEnableVertexAttribArray(locPosition)
        GLES20.glVertexAttribPointer(locPosition, 3, GLES20.GL_FLOAT, false, 12, 0)
        if (locTexCoord >= 0) {
            if (r.uvVbo != 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, r.uvVbo)
                GLES20.glEnableVertexAttribArray(locTexCoord)
                GLES20.glVertexAttribPointer(locTexCoord, 2, GLES20.GL_FLOAT, false, 8, 0)
            } else {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                GLES20.glDisableVertexAttribArray(locTexCoord)
                GLES20.glVertexAttribPointer(locTexCoord, 2, GLES20.GL_FLOAT, false, 8, zeroUv)
            }
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, indexType, 0)
        GLES20.glDisableVertexAttribArray(locPosition)
        if (locTexCoord >= 0) GLES20.glDisableVertexAttribArray(locTexCoord)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    // ------------------------------------------------------------------
    // Realce da ilha
    // ------------------------------------------------------------------

    private fun rebuildHighlightBuffer() {
        destroyHighlightBufferLocked()
        val rIndex = highlightRenderable ?: return
        val tris = highlightTriangles ?: return
        if (tris.isEmpty()) return
        val glr = scene?.byIndex?.get(rIndex) ?: return
        val indices = IntArray(tris.size * 3)
        var i = 0
        for (t in tris) {
            indices[i++] = glr.source.indices[t * 3]
            indices[i++] = glr.source.indices[t * 3 + 1]
            indices[i++] = glr.source.indices[t * 3 + 2]
        }
        highlightIndexCount = indices.size
        highlightIndexType = if (glr.source.indices.max() <= 65535) {
            GLES20.GL_UNSIGNED_SHORT
        } else {
            GLES30.GL_UNSIGNED_INT
        }
        val ibo = IntArray(1)
        GLES20.glGenBuffers(1, ibo, 0)
        if (ibo[0] == 0) return
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        if (highlightIndexType == GLES20.GL_UNSIGNED_SHORT) {
            val shortBuf = ByteBuffer.allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer()
            shortBuf.put(ShortArray(indices.size) { indices[it].toShort() })
            shortBuf.position(0)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, shortBuf.capacity() * 2, shortBuf, GLES20.GL_DYNAMIC_DRAW)
        } else {
            val intBuf = directIntBuffer(indices.size).put(indices)
            intBuf.position(0)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, intBuf.capacity() * 4, intBuf, GLES20.GL_DYNAMIC_DRAW)
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        highlightIbo = ibo[0]
    }

    private fun destroyHighlightBufferLocked() {
        if (highlightIbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(highlightIbo), 0)
            highlightIbo = 0
            highlightIndexCount = 0
        }
    }

    // ------------------------------------------------------------------
    // Libertação e utilitários
    // ------------------------------------------------------------------

    private fun destroySceneLocked() {
        val current = scene ?: return
        val buffers = ArrayList<Int>()
        current.renderables.forEach {
            buffers.add(it.indexIbo); buffers.add(it.positionVbo)
            if (it.uvVbo != 0) buffers.add(it.uvVbo)
            if (it.wireIbo != 0) buffers.add(it.wireIbo)
        }
        if (buffers.isNotEmpty()) {
            val arr = buffers.toIntArray()
            GLES20.glDeleteBuffers(arr.size, arr, 0)
        }
        val textures = current.materialTextures.values.filter { it != 0 && it != documentTexture }
        if (textures.isNotEmpty()) {
            val arr = textures.toIntArray()
            GLES20.glDeleteTextures(arr.size, arr, 0)
        }
        scene = null
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader.trimIndent())
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader.trimIndent())
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("Falha ao ligar o programa GL: $log")
        }
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Falha ao compilar shader GL: $log")
        }
        return shader
    }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun directIntBuffer(size: Int): IntBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
}
