package com.pixelone.studio.export

import android.content.ContentValues
import android.graphics.Bitmap
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
