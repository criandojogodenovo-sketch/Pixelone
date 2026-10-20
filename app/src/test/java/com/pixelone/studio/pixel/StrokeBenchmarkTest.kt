package com.pixelone.studio.pixel

import org.junit.Test

/**
 * MEDIÇÕES DE CÓDIGO (JVM CI) — NÃO são medições de dispositivo físico.
 *
 * Mede, em JVM, os caminhos que a correção tornou independentes do tamanho
 * total do documento por evento de toque:
 * 1. processamento de um traço em lote (fallback Kotlin — o caminho nativo
 *    C++ é medido nos testes instrumentados e nos logs PixelOnePerf/JNI);
 * 2. recolha da REGIÃO suja para atualização regional do bitmap
 *    (comparada com a cópia INTEGRAL antiga);
 * 3. aplicação de deltas (undo/redo);
 * 4. preenchimento com balde de região grande.
 *
 * Os valores impressos no stdout são colhidos do CI e reportados no
 * relatório com a condição "medição de código JVM, não dispositivo".
 * Não há asserts de tempo (apenas de correção) para não tornar o CI frágil.
 */
class StrokeBenchmarkTest {

    private fun measure(label: String, block: () -> Unit): Double {
        block() // aquecimento JIT
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        println(String.format("BENCH %s: %.3f ms", label, ms))
        return ms
    }

    private fun randomWalk(points: Int, size: Int): IntArray {
        val arr = IntArray(points * 2)
        var x = size / 2
        var y = size / 2
        val rnd = java.util.Random(42)
        for (i in 0 until points) {
            x = (x + rnd.nextInt(5) - 2).coerceIn(0, size - 1)
            y = (y + rnd.nextInt(5) - 2).coerceIn(0, size - 1)
            arr[i * 2] = x
            arr[i * 2 + 1] = y
        }
        return arr
    }

    @Test
    fun `benchmark traço em lote em vários tamanhos de documento`() {
        val sizes = listOf(32, 128, 512, 1024)
        for (size in sizes) {
            val doc = PixelDocument(size, size)
            val scratch = NativeEngine.Scratch(doc.pixelCount)
            val pts = randomWalk(600, size)
            measure("traço 600 pontos docs=${size}x${size} (fallback Kotlin)") {
                NativeEngine.strokeBatch(doc, pts, 600, 0xFFFF0000.toInt(), 2, scratch)
            }
        }
    }

    @Test
    fun `benchmark recolha regional vs cópia integral`() {
        val size = 1024
        val doc = PixelDocument(size, size)
        // Traço horizontal a meio do documento: região suja pequena (1024x8)
        val pts = intArrayOf(0, size / 2, size - 1, size / 2)
        val scratch = NativeEngine.Scratch(doc.pixelCount)
        NativeEngine.strokeBatch(doc, pts, 2, 0xFF00FF00.toInt(), 8, scratch)

        measure("recolha REGIONAL 1024x8 (novo caminho)") {
            val region = IntArray(1024 * 8)
            doc.copyRegionInto(0, size / 2 - 4, 1024, 8, region)
        }
        measure("cópia INTEGRAL 1024x1024 (caminho antigo afterEditFull)") {
            doc.copyPixels()
        }
    }

    @Test
    fun `benchmark aplicação de deltas undo`() {
        val size = 512
        val doc = PixelDocument(size, size)
        val scratch = NativeEngine.Scratch(doc.pixelCount)
        val pts = randomWalk(600, size)
        val result = NativeEngine.strokeBatch(doc, pts, 600, 0xFF123456.toInt(), 2, scratch)!!
        val hist = HistoryManager()
        hist.commitDeltas(
            scratch.indices, scratch.before, scratch.after, result.deltaCount,
            result.dirtyX0, result.dirtyY0, result.dirtyX1, result.dirtyY1
        )
        measure("undo de ${result.deltaCount} deltas (512x512)") {
            hist.undo(doc)
            hist.redo(doc)
        }
    }

    @Test
    fun `benchmark balde de região grande`() {
        val size = 512
        val doc = PixelDocument(size, size)
        val scratch = NativeEngine.Scratch(doc.pixelCount)
        // Alterna cores: cada chamada preenche de facto (sem no-op).
        var color = 0xFF654321.toInt()
        val other = 0xFF123456.toInt()
        measure("balde 512x512 região total (fallback Kotlin)") {
            NativeEngine.floodFill(doc, 0, 0, color, 0, scratch)
            color = other // próxima chamada preenche com a outra cor
        }
    }
}
