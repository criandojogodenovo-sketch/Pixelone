package com.pixelone.studio.export

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.MediaStore
import com.pixelone.studio.pixel.PixelDocument
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Núcleo testável da exportação PNG direta para Download/ (MediaStore).
 *
 * CORREÇÃO DO BUG "z.png com 0 bytes": o caminho antigo (SAF CreateDocument)
 * cria o ficheiro VAZIO no momento em que o utilizador confirma o seletor;
 * se qualquer passo posterior falhasse sem feedback (p. ex. exportação sem
 * documento aberto — retorno silencioso —, ou exceção só visível num
 * snackbar efémero), o ficheiro ficava 0 bytes em Download/. Este núcleo
 * implementa os passos receitados, cada um isolado e testável:
 *
 *  1) [buildBitmapFromDocument] — Bitmap ARGB_8888 com a largura/altura
 *     REAIS do documento, píxeis copiados do buffer direto partilhado
 *     Kotlin↔C++ (fonte de verdade), com validação do buffer, verificação
 *     de bitmap nulo/reciclado e conversão RGBA→ARGB tratada na origem
 *     (ver comentário em baixo — não é necessária conversão manual).
 *  2) [writePngToStream] — exatamente nesta ordem: `val ok =
 *     bitmap.compress(PNG, 100, out)` → `out.flush()` → `close()` (feito
 *     pelo `use{}` do chamador); os bytes escritos são contados para o
 *     Toast (tamanho real do ficheiro).
 *  3) [mediaValues] — ContentValues do MediaStore: DISPLAY_NAME,
 *     MIME_TYPE=image/png, RELATIVE_PATH=Download/ e IS_PENDING=1 — o
 *     ficheiro só se torna visível DEPOIS de o conteúdo estar escrito.
 *  4) [displayNameFor] — nome sanitizado e único (evita colisões que em
 *     alguns providers gerariam entradas vazias/pendentes silenciosas).
 *
 * O ViewModel orquestra: insert (IS_PENDING=1) → openOutputStream →
 * writePngToStream → update(IS_PENDING=0) → Toast com ok/bytes; em caso de
 * exceção REMOVE a entrada pendente (nunca fica um órfão de 0 bytes) e
 * mostra um Toast com a mensagem do erro (e.message) — exceções nunca são
 * engolidas.
 */
object PngExport {

    /** Resultado da escrita do PNG num stream. */
    data class WriteResult(val ok: Boolean, val bytes: Long)

    // ------------------------------------------------------------------
    // 0) Escala e fundo — opções da exportação PNG
    // ------------------------------------------------------------------

    /**
     * Fatores de escala pedidos: 1x, 2x, 4x, 8x e 16x. O escalonamento é
     * SEMPRE nearest-neighbor (sem suavização): o `filter=false` de
     * [Bitmap.createScaledBitmap] e o `isFilterBitmap=false` do Paint
     * mantêm cada píxel como um bloco nítido de N×N — nunca um borrão
     * interpolado.
     */
    enum class ScaleOption(val factor: Int) {
        X1(1), X2(2), X4(4), X8(8), X16(16);

        /** Rótulo curto para a UI (ex.: "4x"). */
        val label: String get() = "${factor}x"
    }

    /**
     * Fundo do PNG exportado:
     *  - [TRANSPARENT] preserva o alfa do documento (padrão; igual ao
     *    comportamento anterior);
     *  - [SOLID_BLACK] achata a arte sobre preto sólido 0xFF000000 — o
     *    bitmap final é PREENCHIDO com `canvas.drawColor` ANTES de
     *    desenhar (receita exata do pedido).
     */
    enum class BackgroundOption { TRANSPARENT, SOLID_BLACK }

    /**
     * Lado máximo do bitmap final. O Android impõe ~32 767 por eixo;
     * fica-se por 16 384 — mais do que suficiente para qualquer export
     * de pixel art e longe dos limites do framework.
     */
    const val MAX_SCALED_SIDE: Int = 16_384

    /**
     * Máximo de píxeis do bitmap final: 32 MP ≈ 128 MB em ARGB_8888.
     * Proteção anti-OOM: uma tela 2048×2048 a 16x geraria 32 768×32 768
     * (~4 GB) — rejeitado AQUI, com mensagem clara no Toast, em vez de
     * deixar a app morrer com OutOfMemoryError (que nem é capturável
     * pelo `catch (Exception)` do ViewModel).
     */
    const val MAX_SCALED_PIXELS: Long = 33_554_432L

    /**
     * Valida as dimensões escaladas ANTES de qualquer alocação. Lança
     * [IllegalStateException] com mensagem pronta para o Toast quando a
     * escala pedida excede [MAX_SCALED_SIDE] ou [MAX_SCALED_PIXELS].
     */
    fun validateScaledDimensions(width: Int, height: Int, scale: Int) {
        require(width >= 1 && height >= 1) {
            "Dimensões do canvas inválidas: ${width}x${height}"
        }
        require(scale >= 1) { "Escala inválida: $scale" }
        val sw = width.toLong() * scale
        val sh = height.toLong() * scale
        if (sw > MAX_SCALED_SIDE || sh > MAX_SCALED_SIDE) {
            throw IllegalStateException(
                "Escala ${scale}x gera ${sw}x${sh} px — o lado máximo é " +
                    "$MAX_SCALED_SIDE. Escolhe uma escala menor."
            )
        }
        if (sw * sh > MAX_SCALED_PIXELS) {
            throw IllegalStateException(
                "Escala ${scale}x gera ${sw}x${sh} px (${sw * sh} píxeis) — " +
                    "o máximo é $MAX_SCALED_PIXELS píxeis (~32 MP). Escolhe uma escala menor."
            )
        }
    }

    /**
     * Paint da alternativa prescrita para desenhar a escala: suavização
     * DESLIGADA nos dois interruptores — `isFilterBitmap=false` (sem
     * filtragem bilinear: cada píxel da origem vira um bloco nítido) e
     * `isAntiAlias=false` (sem bordas semi-transparentes nas arestas).
     */
    fun nearestNeighborPaint(): Paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }

    // ------------------------------------------------------------------
    // 1) Bitmap a partir do buffer partilhado Kotlin↔C++
    // ------------------------------------------------------------------

    /**
     * Constrói o Bitmap de exportação com as dimensões REAIS do documento,
     * copiando os píxeis do buffer direto partilhado (escrito pelo motor
     * C++17 via JNI e pelo Kotlin sobre a mesma memória).
     *
     * FORMATO E CONVERSÃO RGBA→ARGB: o buffer partilhado guarda, por píxel,
     * o uint32 0xAARRGGBB em little-endian (bytes B,G,R,A em memória) — que
     * é EXATAMENTE o layout de memória do Bitmap ARGB_8888 do Android.
     * [PixelDocument.copyPixels] devolve esses valores como color ints ARGB
     * e [Bitmap.setPixels] aceita color ints ARGB: o mapeamento de canais
     * fica correto à origem (não há troca R/B nem conversão manual a fazer;
     * esta é a conversão "se necessário" — tratada e verificada por testes
     * de roundtrip exato).
     *
     * @throws IllegalStateException se o buffer for inválido, se o Bitmap
     *         nascer null ou já reciclado — nunca devolve um bitmap sem
     *         condições para ser comprimido.
     */
    fun buildBitmapFromDocument(doc: PixelDocument): Bitmap {
        val problem = doc.bufferValidationProblem()
        if (problem != null) {
            throw IllegalStateException("Buffer de píxeis inválido: $problem")
        }
        // Snapshot ÚNICO e completo do buffer direto (o chamador garante que
        // corre no executor de mutação — sem leituras rasgadas pelo C++).
        val pixels = doc.copyPixels()
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
            ?: throw IllegalStateException(
                "Bitmap.createBitmap devolveu null (${doc.width}x${doc.height})"
            )
        if (bmp.isRecycled) {
            throw IllegalStateException("Bitmap de exportação foi reciclado")
        }
        // Não premultiplicado: o PNG guarda os valores EXATOS do documento
        // (pixel art exige bytes exatos, inclusive com alpha muito baixo).
        bmp.setPremultiplied(false)
        bmp.setPixels(pixels, 0, doc.width, 0, 0, doc.width, doc.height)
        return bmp
    }

    /**
     * Exportação com ESCALA e FUNDO escolhidos (novidade): constrói o
     * bitmap ORIGINAL com as dimensões reais do canvas (via
     * [buildBitmapFromDocument] — píxeis exatos do buffer partilhado
     * Kotlin↔C++) e produz o bitmap FINAL escalado, sempre em
     * nearest-neighbor:
     *
     *  - [ScaleOption.X1] + [BackgroundOption.TRANSPARENT] devolve o
     *    bitmap original SEM nenhum passo de desenho — caminho 1:1 com o
     *    buffer (comportamento anterior, byte-exato, incl. alpha baixo).
     *
     *  - TRANSPARENT com escala > 1: `Bitmap.createScaledBitmap(original,
     *    w*escala, h*escala, false)` — o `false` (filter) desliga a
     *    suavização e mantém os píxeis nítidos.
     *
     *  - [BackgroundOption.SOLID_BLACK]: o bitmap final é criado vazio,
     *    PREENCHIDO com `canvas.drawColor(preto)` ANTES de desenhar e a
     *    arte é desenhada por cima com `Canvas.drawBitmap` e um Paint de
     *    [nearestNeighborPaint] (isFilterBitmap=false, isAntiAlias=false)
     *    — a alternativa prevista no pedido, necessária aqui porque o
     *    fundo tem de ser pintado antes da arte.
     *
     * PRECISÃO: no caminho 1x transparente os bytes são EXATOS (setPixels
     * direto, sem composição). Em escalas > 1 o desenho passa pelo espaço
     * premultiplicado do Skia — píxeis opacos e totalmente transparentes
     * permanecem exatos (o padrão da pixel art); alfas MUITO baixos
     * (≤ ~7/255) podem sofrer arredondamento de 1 bit nos RGB, limitação
     * conhecida do pipeline gráfico do Android.
     *
     * @throws IllegalStateException se as dimensões escaladas excederem
     *         os limites ([validateScaledDimensions]) ou se algum bitmap
     *         nascer null/reciclado — nunca devolve um bitmap sem
     *         condições para ser comprimido. O bitmap ORIGINAL usado como
     *         origem é reciclado antes de devolver (só o final sobrevive).
     */
    fun buildBitmapFromDocument(
        doc: PixelDocument,
        scale: ScaleOption,
        background: BackgroundOption
    ): Bitmap {
        val original = buildBitmapFromDocument(doc)
        val s = scale.factor
        if (s == 1 && background == BackgroundOption.TRANSPARENT) {
            return original // caminho exato: igual ao comportamento anterior
        }
        try {
            validateScaledDimensions(doc.width, doc.height, s)
            val sw = doc.width * s
            val sh = doc.height * s

            val final: Bitmap = if (background == BackgroundOption.SOLID_BLACK) {
                // Fundo sólido: bitmap final novo → drawColor(preto) ANTES
                // de desenhar → arte por cima com nearest-neighbor.
                val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                    ?: throw IllegalStateException(
                        "Bitmap.createBitmap devolveu null (${sw}x${sh})"
                    )
                if (out.isRecycled) {
                    throw IllegalStateException("Bitmap de fundo foi reciclado")
                }
                // Destino totalmente opaco (preto + arte) — valores retos,
                // sem premultiplicação, mantêm as cores byte-exatas.
                out.setPremultiplied(false)
                val canvas = Canvas(out)
                // 1º PREENCHER o fundo (antes de desenhar a arte — receita)
                canvas.drawColor(android.graphics.Color.BLACK)
                // 2º desenhar a arte escalada, sem suavização nem anti-alias
                val paint = nearestNeighborPaint()
                val dst = RectF(0f, 0f, sw.toFloat(), sh.toFloat())
                canvas.drawBitmap(original, null, dst, paint)
                out
            } else {
                // Fundo transparente: createScaledBitmap com filter=false —
                // nearest-neighbor puro, alfa da arte preservado.
                val scaled = Bitmap.createScaledBitmap(original, sw, sh, false)
                    ?: throw IllegalStateException(
                        "createScaledBitmap devolveu null " +
                            "(${doc.width}x${doc.height} → ${sw}x${sh})"
                    )
                if (scaled.isRecycled) {
                    throw IllegalStateException("Bitmap escalado foi reciclado")
                }
                // Sanity: as dimensões finais têm de ser exatamente w*s × h*s
                check(scaled.width == sw && scaled.height == sh) {
                    "Bitmap escalado tem ${scaled.width}x${scaled.height}; " +
                        "esperado ${sw}x${sh}"
                }
                scaled
            }

            // Verificação final: nunca devolver um bitmap sem condições
            if (final.isRecycled || final.width != sw || final.height != sh) {
                throw IllegalStateException(
                    "Bitmap final inválido: ${final.width}x${final.height}, " +
                        "reciclado=${final.isRecycled} (esperado ${sw}x${sh})"
                )
            }
            return final
        } finally {
            // A origem já não é necessária — o chamador só recebe o final.
            if (s != 1 || background != BackgroundOption.TRANSPARENT) {
                original.recycle()
            }
        }
    }

    // ------------------------------------------------------------------
    // 2) Escrita: compress → flush → close (use{} do chamador)
    // ------------------------------------------------------------------

    /**
     * Escreve o PNG de [bitmap] em [out] e conta os bytes reais escritos.
     * A ordem é exatamente: `compress` (resultado em `ok`) → `flush()` →
     * o `close()` fica a cargo do `use{}` do chamador. Se `compress`
     * devolver false OU o stream falhar, a exceção é propagada com a
     * mensagem original (nunca engolida) — o chamador decide mostrar o
     * Toast de erro e remover a entrada pendente.
     */
    fun writePngToStream(bitmap: Bitmap, out: OutputStream): WriteResult {
        if (bitmap.isRecycled) {
            throw IllegalStateException("Bitmap reciclado não pode ser comprimido")
        }
        val counting = CountingOutputStream(out)
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, counting)
        counting.flush()
        if (!ok) {
            throw IOException("Bitmap.compress devolveu false (PNG não codificado)")
        }
        return WriteResult(ok = true, bytes = counting.count)
    }

    /** Encadeia um stream contando os bytes que passam (para o Toast/report). */
    class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    // ------------------------------------------------------------------
    // 3) Nome do ficheiro
    // ------------------------------------------------------------------

    /** Caracteres problemáticos em nomes de ficheiros Android. */
    private val unsafeChars = Regex("[^A-Za-z0-9 ._()\\-]")

    /** Remove caracteres perigosos; vazio → "pixel-art". */
    fun sanitizeBaseName(name: String): String =
        name.replace(unsafeChars, "_").trim(' ', '.', '_').ifBlank { "pixel-art" }

    /**
     * Nome final do ficheiro em Download/: base sanitizada + ".png" (sem
     * duplicar extensão); se já existir um ficheiro com o mesmo nome
     * (case-insensitive), acrescenta carimbo temporal — garante um alvo
     * de escrita novo em vez de reabrir/esvaziar um ficheiro existente.
     */
    fun displayNameFor(projectName: String, existingNames: Set<String>, nowMs: Long): String {
        var base = sanitizeBaseName(projectName)
        if (base.endsWith(".png", ignoreCase = true)) {
            base = base.dropLast(4).trim(' ', '.', '_')
            if (base.isBlank()) base = "pixel-art"
        }
        var candidate = "$base.png"
        if (existingNames.any { it.equals(candidate, ignoreCase = true) }) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
            candidate = "${base}_$stamp.png"
        }
        return candidate
    }

    // ------------------------------------------------------------------
    // 4) ContentValues do MediaStore
    // ------------------------------------------------------------------

    /**
     * ContentValues da entrada de exportação: DISPLAY_NAME, MIME_TYPE=
     * image/png e, em Android 10+ (Q), RELATIVE_PATH=Download/ com
     * IS_PENDING=1 (o ficheiro só é publicado com IS_PENDING=0 depois de
     * o conteúdo estar escrito e fechado). Em API < 29 o fluxo usado é o
     * SAF legado (sem estas colunas — o chamador não deve chegar aqui).
     */
    fun mediaValues(displayName: String, sdkInt: Int): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (sdkInt >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
}
