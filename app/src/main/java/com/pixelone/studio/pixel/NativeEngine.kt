package com.pixelone.studio.pixel

import com.pixelone.studio.perf.PerfLog
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ponte para o motor nativo C++17 (libpixelone_engine) + FALLBACK Kotlin.
 *
 * CONTRATO DE THREADS: o buffer do documento é mutado por estas funções;
 * o chamador (EditorViewModel) garante acesso serializado (executor de
 * mutação de documento com paralelismo 1). O lado nativo NÃO guarda
 * ponteiros entre chamadas — cada chamada resolve o endereço do buffer
 * com GetDirectBufferAddress e termina antes de devolver.
 *
 * FORMATO DO TRAÇO EM LOTE:
 * - [strokeBatch] recebe TODOS os pontos do gesto (down + moves) num único
 *   array intercalado [x0, y0, x1, y1, ...] e processa o traço completo em
 *   UMA chamada JNI (nunca por píxel nem por segmento). Entre pontos
 *   consecutivos o motor aplica Bresenham (continuidade sem falhas).
 * - Resultado: deltas esparsos (índice linear, cor antiga, cor nova) nos
 *   arrays de scratch reutilizados (sem alocação por gesto) + dirty rect +
 *   duração interna medida com std::chrono::steady_clock.
 * - HEADER (outHeader): [0..3] dirty rect x0,y0,x1,y1 (inclusive);
 *   [4] duração no motor (microssegundos); [5] caminho (1=nativo, 0=Kotlin).
 *
 * ERROS: devolvem null e NUNCA crasham — buffer não direto, capacidade
 * errada, dimensões inválidas ou arrays insuficientes são validados antes
 * de qualquer escrita (também no lado C++).
 */
object NativeEngine {

    private const val TAG = "PixelOnePerf/JNI"

    /** true se libpixelone_engine foi carregada com sucesso. */
    val nativeAvailable: Boolean
        get() = loaded.get()

    private val loaded = AtomicBoolean(false)

    init {
        loaded.set(try {
            System.loadLibrary("pixelone_engine")
            PerfLog.i(TAG, "libpixelone_engine carregada — caminho nativo ativo")
            true
        } catch (t: Throwable) {
            PerfLog.i(TAG, "Biblioteca nativa indisponível (${t.javaClass.simpleName}) — fallback Kotlin ativo")
            false
        })
    }

    // Assinaturas JNI (implementadas em app/src/main/cpp/pixelone_engine.cpp).
    private external fun nativeBufferProbe(buffer: ByteBuffer, count: Int): Long
    private external fun nativeBufferWrite(buffer: ByteBuffer, index: Int, value: Int): Boolean
    private external fun nativeStrokeBatch(
        buffer: ByteBuffer, width: Int, height: Int,
        points: IntArray, pointCount: Int,
        color: Int, brushSize: Int,
        outIndices: IntArray, outBefore: IntArray, outAfter: IntArray,
        outHeader: IntArray
    ): Int
    private external fun nativeFloodFill(
        buffer: ByteBuffer, width: Int, height: Int,
        startX: Int, startY: Int, color: Int, tolerance: Int,
        outIndices: IntArray, outBefore: IntArray, outAfter: IntArray,
        outHeader: IntArray
    ): Int

    /**
     * Scratch buffers reutilizados por documento (criados UMA vez quando o
     * documento é instalado, nunca por gesto/evento de toque). A dimensão
     * máxima de deltas num traço/fill é o número de píxeis do documento
     * (cada píxel muda no máximo uma vez por operação — o carimbo ignora
     * píxeis que já têm a cor destino).
     */
    class Scratch(val pixelCount: Int) {
        val indices = IntArray(pixelCount)
        val before = IntArray(pixelCount)
        val after = IntArray(pixelCount)
        val header = IntArray(HEADER_SIZE)
    }

    /** Resultado de uma operação do motor. */
    class Result(
        val dirtyX0: Int, val dirtyY0: Int, val dirtyX1: Int, val dirtyY1: Int,
        val deltaCount: Int,
        val durationMicros: Long,
        val native: Boolean
    ) {
        val changedSomething: Boolean get() = deltaCount > 0
        fun rectForLogging(): String = "${dirtyX1 - dirtyX0 + 1}x${dirtyY1 - dirtyY0 + 1}"
    }

    /**
     * Processa um traço completo (lote de pontos) sobre o buffer direto do
     * documento. Prefere o caminho nativo; sem lib nativa usa o fallback
     * Kotlin com semântica idêntica. Devolve null se nada mudou ou se houve
     * erro de validação.
     *
     * @param points pontos intercalados x,y (coordenadas de imagem) — o
     *        chamador já os validou/converteu do ecrã.
     * @param pointCount número de pontos (points.size >= pointCount*2).
     */
    fun strokeBatch(
        document: PixelDocument,
        points: IntArray,
        pointCount: Int,
        color: Int,
        brushSize: Int,
        scratch: Scratch
    ): Result? {
        require(pointCount >= 1) { "Traço sem pontos" }
        require(points.size >= pointCount * 2) { "Array de pontos menor que pointCount" }
        val problem = document.bufferValidationProblem()
        if (problem != null) {
            PerfLog.w(TAG, "Validação do buffer falhou: $problem")
            return null
        }
        val maxDeltas = scratch.pixelCount
        require(scratch.indices.size >= maxDeltas && scratch.before.size >= maxDeltas &&
            scratch.after.size >= maxDeltas && scratch.header.size >= HEADER_SIZE
        ) { "Scratch buffers inconsistentes com o documento" }

        val startNanos = System.nanoTime()
        var native = false
        var count: Int
        if (nativeAvailable) {
            count = try {
                nativeStrokeBatch(
                    document.sharedBuffer, document.width, document.height,
                    points, pointCount, color, brushSize.coerceIn(1, PixelEngine.MAX_BRUSH),
                    scratch.indices, scratch.before, scratch.after, scratch.header
                )
            } catch (t: Throwable) {
                PerfLog.w(TAG, "Chamada nativa falhou (${t.javaClass.simpleName}) — a repetir em Kotlin")
                -1
            }
            // Erros nativos (-1/-2/-3) repetem em Kotlin: nunca crasha o editor.
            if (count < 0) {
                fallbackStrokeInto(document, points, pointCount, color, brushSize, scratch)
                count = scratch.header[6]
            } else {
                native = true
            }
        } else {
            fallbackStrokeInto(document, points, pointCount, color, brushSize, scratch)
            count = scratch.header[6]
        }
        val totalMicros = (System.nanoTime() - startNanos) / 1000L
        logStroke(native, document, pointCount, count, scratch.header, totalMicros)
        if (count <= 0) return null
        return Result(scratch.header[0], scratch.header[1], scratch.header[2], scratch.header[3],
            count, scratch.header[4].toLong(), native)
    }

    /**
     * Preenchimento com balde (operação discreta; corre fora da main thread
     * no chamador). Mesma semântica do [PixelEngine.floodFill]: BFS 4-conectado,
     * tolerância por canal, sem re-enfileiramento.
     */
    fun floodFill(
        document: PixelDocument,
        startX: Int, startY: Int,
        color: Int,
        tolerance: Int,
        scratch: Scratch
    ): Result? {
        val problem = document.bufferValidationProblem()
        if (problem != null) {
            PerfLog.w(TAG, "Validação do buffer falhou: $problem")
            return null
        }
        if (!document.inBounds(startX, startY)) return null
        if (document.get(startX, startY) == color) return null // no-op

        val startNanos = System.nanoTime()
        var native = false
        var count: Int
        if (nativeAvailable) {
            count = try {
                nativeFloodFill(
                    document.sharedBuffer, document.width, document.height,
                    startX, startY, color, tolerance.coerceIn(0, 255),
                    scratch.indices, scratch.before, scratch.after, scratch.header
                )
            } catch (t: Throwable) {
                PerfLog.w(TAG, "Fill nativo falhou (${t.javaClass.simpleName}) — a repetir em Kotlin")
                -1
            }
            if (count < 0) {
                fallbackFillInto(document, startX, startY, color, tolerance, scratch)
                count = scratch.header[6]
            } else {
                native = true
            }
        } else {
            fallbackFillInto(document, startX, startY, color, tolerance, scratch)
            count = scratch.header[6]
        }
        val totalMicros = (System.nanoTime() - startNanos) / 1000L
        logStroke(native, document, 1, count, scratch.header, totalMicros)
        if (count <= 0) return null
        return Result(scratch.header[0], scratch.header[1], scratch.header[2], scratch.header[3],
            count, scratch.header[4].toLong(), native)
    }

    /**
     * PROVA DE BUFFER PARTILHADO (teste instrumentado): o C++ lê diretamente
     * os primeiros [count] inteiros do buffer e soma-os; o Kotlin compara com
     * a soma calculada pela sua própria vista. Valores iguais ⇒ ambos os
     * lados operam sobre a MESMA memória.
     */
    fun probeBufferSum(document: PixelDocument, count: Int): Long? {
        if (!nativeAvailable) return null
        val nativeSum = try {
            nativeBufferProbe(document.sharedBuffer, count)
        } catch (t: Throwable) {
            PerfLog.w(TAG, "Probe nativo falhou: ${t.message}")
            return null
        }
        var kotlinSum = 0L
        for (i in 0 until count) kotlinSum += document.getAt(i)
        return if (nativeSum == kotlinSum) nativeSum else {
            PerfLog.e(TAG, "Buffer partilhado DIVERGENTE: nativo=$nativeSum kotlin=$kotlinSum")
            null
        }
    }

    /**
     * Escrita nativa num índice (teste instrumentado): prova que a escrita
     * pelo endereço direto do C++ é visível pela vista Kotlin.
     */
    fun nativeWriteProbe(document: PixelDocument, index: Int, value: Int): Boolean =
        nativeAvailable && try {
            nativeBufferWrite(document.sharedBuffer, index, value) &&
                document.getAt(index) == value
        } catch (t: Throwable) {
            false
        }

    // ------------------------------------------------------------------
    // FALLBACK Kotlin — semântica idêntica ao C++ (usado em JVM/testes e
    // se a lib nativa não carregar). Escreve deltas no scratch + header.
    // ------------------------------------------------------------------

    private fun fallbackStrokeInto(
        document: PixelDocument,
        points: IntArray,
        pointCount: Int,
        color: Int,
        brushSize: Int,
        scratch: Scratch
    ) {
        val engineStart = System.nanoTime()
        var deltaCount = 0
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE

        fun record(index: Int, before: Int, after: Int) {
            if (before == after || deltaCount >= scratch.pixelCount) return
            scratch.indices[deltaCount] = index
            scratch.before[deltaCount] = before
            scratch.after[deltaCount] = after
            deltaCount++
            val x = index % document.width
            val y = index / document.width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        fun stamp(centerX: Int, centerY: Int) {
            val size = brushSize.coerceIn(1, PixelEngine.MAX_BRUSH)
            val half = (size - 1) / 2
            for (dy in 0 until size) {
                for (dx in 0 until size) {
                    val x = centerX - half + dx
                    val y = centerY - half + dy
                    if (document.inBounds(x, y)) {
                        val idx = y * document.width + x
                        val before = document.getAt(idx)
                        if (before != color) {
                            document.setAt(idx, color)
                            record(idx, before, color)
                        }
                    }
                }
            }
        }

        // Primeiro ponto + Bresenham entre pontos consecutivos.
        stamp(points[0], points[1])
        for (i in 1 until pointCount) {
            val x0 = points[(i - 1) * 2]
            val y0 = points[(i - 1) * 2 + 1]
            val x1 = points[i * 2]
            val y1 = points[i * 2 + 1]
            // Bresenham (mesma geometria de PixelEngine.drawLine).
            val dx = Math.abs(x1 - x0)
            val dy = -Math.abs(y1 - y0)
            val stepX = if (x0 < x1) 1 else -1
            val stepY = if (y0 < y1) 1 else -1
            var err = dx + dy
            var x = x0
            var y = y0
            while (true) {
                stamp(x, y)
                if (x == x1 && y == y1) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x += stepX }
                if (e2 <= dx) { err += dx; y += stepY }
            }
        }

        writeFallbackHeader(scratch, minX, minY, maxX, maxY, deltaCount, engineStart)
    }

    private fun fallbackFillInto(
        document: PixelDocument,
        startX: Int, startY: Int,
        color: Int,
        tolerance: Int,
        scratch: Scratch
    ) {
        val engineStart = System.nanoTime()
        val w = document.width
        val h = document.height
        val tol = tolerance.coerceIn(0, 255)
        val target = document.get(startX, startY)
        if (target == color) {
            writeFallbackHeader(scratch, 0, 0, -1, -1, 0, engineStart)
            return
        }
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        val ta = (target ushr 24) and 0xFF

        fun matches(argb: Int): Boolean {
            if (argb == color) return false
            if (tol == 0) return argb == target
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val a = (argb ushr 24) and 0xFF
            return Math.abs(r - tr) <= tol && Math.abs(g - tg) <= tol &&
                Math.abs(b - tb) <= tol && Math.abs(a - ta) <= tol
        }

        val total = w * h
        val queue = IntArray(total)
        var head = 0
        var tail = 0
        var deltaCount = 0
        var minX = startX; var maxX = startX
        var minY = startY; var maxY = startY

        fun enqueue(idx: Int) {
            queue[tail] = idx
            tail = (tail + 1) % total
            val x = idx % w
            val y = idx / w
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        fun paint(idx: Int) {
            val before = document.getAt(idx)
            if (before == color) return
            document.setAt(idx, color)
            if (deltaCount < scratch.pixelCount) {
                scratch.indices[deltaCount] = idx
                scratch.before[deltaCount] = before
                scratch.after[deltaCount] = color
                deltaCount++
            }
        }

        val startIdx = startY * w + startX
        paint(startIdx)
        enqueue(startIdx)
        while (head != tail) {
            val idx = queue[head]
            head = (head + 1) % total
            val x = idx % w
            val y = idx / w
            val left = idx - 1
            if (x > 0 && matches(document.getAt(left))) { paint(left); enqueue(left) }
            val right = idx + 1
            if (x < w - 1 && matches(document.getAt(right))) { paint(right); enqueue(right) }
            val up = idx - w
            if (y > 0 && matches(document.getAt(up))) { paint(up); enqueue(up) }
            val down = idx + w
            if (y < h - 1 && matches(document.getAt(down))) { paint(down); enqueue(down) }
        }
        writeFallbackHeader(scratch, minX, minY, maxX, maxY, deltaCount, engineStart)
    }

    /** Header no formato nativo: rect + micros do motor + caminho(0=Kotlin) + count. */
    private fun writeFallbackHeader(
        scratch: Scratch, minX: Int, minY: Int, maxX: Int, maxY: Int,
        deltaCount: Int, engineStartNanos: Long
    ) {
        if (deltaCount > 0) {
            scratch.header[0] = minX; scratch.header[1] = minY
            scratch.header[2] = maxX; scratch.header[3] = maxY
        } else {
            scratch.header[0] = 0; scratch.header[1] = 0
            scratch.header[2] = -1; scratch.header[3] = -1
        }
        scratch.header[4] = ((System.nanoTime() - engineStartNanos) / 1000L).toInt()
        scratch.header[5] = 0 // caminho Kotlin
        scratch.header[6] = deltaCount
    }

    private fun logStroke(
        native: Boolean,
        document: PixelDocument,
        pointCount: Int,
        deltaCount: Int,
        header: IntArray,
        totalMicros: Long
    ) {
        if (deltaCount > 0) {
            PerfLog.i(
                TAG,
                "traço docs=${document.width}x${document.height} pontos=$pointCount " +
                    "alterados=$deltaCount rect=${header[2] - header[0] + 1}x${header[3] - header[1] + 1} " +
                    "motor=${header[4]}us total=${totalMicros / 1000.0}ms caminho=${if (native) "nativo" else "kotlin"}"
            )
        }
    }

    const val HEADER_SIZE: Int = 8 // rect(4) + micros + caminho + count + reserva
}
