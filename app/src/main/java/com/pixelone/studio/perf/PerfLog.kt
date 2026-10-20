package com.pixelone.studio.perf

import android.util.Log

/**
 * Logger de instrumentação de desempenho, seguro em JVM (testes unitários
 * sem Robolectric): as chamadas a android.util.Log são envolvidas em
 * try/catch — em JVM o Log não é funcional e é silenciosamente ignorado.
 *
 * TAGS consistentes (documentadas no relatório):
 *   PixelOnePerf/Touch   — recolha de pontos e ciclo do gesto
 *   PixelOnePerf/JNI     — chamada ao motor (nativo ou fallback), deltas, dirty rect
 *   PixelOnePerf/Render  — atualização regional do bitmap e renderização
 *
 * Os logs são UMA linha por operação lógica (traço/fill/undo), nunca por
 * píxel — sem verbosidade indesejada em Release.
 */
object PerfLog {
    inline fun i(tag: String, message: String) {
        try { Log.i(tag, message) } catch (_: Throwable) { }
    }

    inline fun w(tag: String, message: String) {
        try { Log.w(tag, message) } catch (_: Throwable) { }
    }

    inline fun e(tag: String, message: String) {
        try { Log.e(tag, message) } catch (_: Throwable) { }
    }

    /** Mede um bloco e devolve a duração em milissegundos (float). */
    inline fun <T> measure(tag: String, label: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        i(tag, "$label ms=$ms")
        return result
    }
}
