package com.pixelone.studio.pixel

/**
 * Motor de desenho 2D do Pixel One (Kotlin puro, testável em JVM).
 *
 * CAMINHO DUPLO (correção de performance):
 * - Este objeto é o FALLBACK Kotlin e a referência semântica do motor: o
 *   mesmo comportamento (traço/linha/balde/borracha) é implementado em
 *   C++17 em app/src/main/cpp/pixelone_engine.cpp para o caminho rápido.
 * - A UI usa [com.pixelone.studio.pixel.NativeStroke], que prefere o motor
 *   nativo (UMA chamada JNI por traço completo) e cai para este fallback
 *   se a biblioteca nativa não estiver disponível (ex.: JVM/Robolectric).
 * - Todas as funções operam diretamente sobre o buffer direto do
 *   [PixelDocument] (vista IntBuffer persistente, acessos absolutos).
 *
 * DECISÃO DE MISTURA DE COR (documentada):
 * - Todas as ferramentas SUBSTITUEM o valor do píxel (incluindo o canal
 *   alpha), sem mistura alfa progressiva. É o comportamento padrão em
 *   editores de pixel art (ex.: Aseprite) e garante cores exactas e
 *   previsíveis — essencial para texturas de jogos.
 * - A borracha é o lápis com a cor 0x00000000 (totalmente transparente):
 *   apagar É escrever transparência real, não "pintar com a cor de fundo".
 *
 * COORDENADAS: todas as funções aceitam coordenadas de imagem em píxeis
 * (não coordenadas de ecrã); a conversão ecrã→píxel é feita pela camada de
 * UI usando a transformação da câmera (zoom/pan).
 *
 * HISTÓRICO: as operações aceitam um [PixelEngine.StrokeRecorder] opcional.
 * O [HistoryManager] implementa esta interface; a UI chama beginStroke()
 * antes do traço e commitStroke()/cancelStroke() no fim — um traço contínuo
 * inteiro (ou um fill) torna-se UMA entrada de histórico, sem interceptação
 * manual píxel a píxel pela UI.
 */
object PixelEngine {

    /** Ferramentas de edição disponíveis no editor. */
    enum class Tool { PENCIL, ERASER, FILL, EYEDROPPER, PAN }

    /**
     * Observador das alterações de píxeis, usado para capturar o estado
     * "antes" e a região alterada sem duplicar a lógica de desenho.
     * Implementado por [HistoryManager].
     */
    interface StrokeRecorder {
        /** Chamado ANTES de (x, y) mudar, com a cor original. */
        fun onBeforeChange(x: Int, y: Int, beforeColor: Int)

        /** Chamado depois de (x, y) mudar de facto (novo valor != antigo). */
        fun onChanged(x: Int, y: Int)
    }

    /**
     * Cor ARGB do píxel (x, y) — conta-gotas.
     * Fora dos limites devolve 0 (transparente); a UI trata esse caso.
     */
    fun pickColor(document: PixelDocument, x: Int, y: Int): Int = document.get(x, y)

    /**
     * Desenha um píxel único (toque simples sem arrastar).
     * Usa um carimbo quadrado de [brushSize] (1 = pixel art pura).
     * Devolve true se alterou pelo menos um píxel.
     */
    fun drawPixel(
        document: PixelDocument,
        x: Int,
        y: Int,
        color: Int,
        brushSize: Int = 1,
        recorder: StrokeRecorder? = null
    ): Boolean = stamp(document, x, y, color, brushSize, recorder)

    /**
     * Desenha uma linha CONTÍNUA de (x0,y0) a (x1,y1) com o algoritmo de
     * Bresenham — garante conectividade sem falhas para traços de lápis de
     * tamanho 1, mesmo em diagonais íngremes. Chamada a cada movimento do
     * dedo com o último ponto desenhado como origem; o carimbo ignora píxeis
     * que já têm a cor destino, evitando trabalho redundante no traço.
     *
     * @return true se alterou pelo menos um píxel.
     */
    fun drawLine(
        document: PixelDocument,
        x0: Int, y0: Int,
        x1: Int, y1: Int,
        color: Int,
        brushSize: Int = 1,
        recorder: StrokeRecorder? = null
    ): Boolean {
        var changed = false
        val dx = Math.abs(x1 - x0)
        val dy = -Math.abs(y1 - y0)
        val stepX = if (x0 < x1) 1 else -1
        val stepY = if (y0 < y1) 1 else -1
        var err = dx + dy
        var x = x0
        var y = y0
        while (true) {
            if (stamp(document, x, y, color, brushSize, recorder)) changed = true
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += stepX
            }
            if (e2 <= dx) {
                err += dx
                y += stepY
            }
        }
        return changed
    }

    /**
     * Carimbo quadrado da brocha centrado (com desvio determinístico para
     * tamanhos pares: estende para a direita/baixo). Para size 1 é exatamente
     * o píxel — a pixel art de um píxel nunca é prejudicada.
     */
    private fun stamp(
        document: PixelDocument,
        centerX: Int,
        centerY: Int,
        color: Int,
        brushSize: Int,
        recorder: StrokeRecorder?
    ): Boolean {
        val size = brushSize.coerceIn(1, MAX_BRUSH)
        val half = (size - 1) / 2
        var changed = false
        for (dy in 0 until size) {
            for (dx in 0 until size) {
                val x = centerX - half + dx
                val y = centerY - half + dy
                if (document.inBounds(x, y)) {
                    val before = document.get(x, y)
                    if (before != color) {
                        recorder?.onBeforeChange(x, y, before)
                        document.set(x, y, color)
                        recorder?.onChanged(x, y)
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    /**
     * Balde de preenchimento: preenche a região contígua de píxeis cuja cor
     * coincide com a cor de origem dentro de uma tolerância por canal
     * (diferença absoluta máxima em R, G, B e A ≤ [tolerance]).
     *
     * - A região NUNCA ultrapassa os limites do documento nem cores de
     *   "fronteira" fora da tolerância (testado).
     * - BFS com fila em IntArray ring-buffer: sem alocação por píxel, sem
     *   recursão (sem StackOverflow em regiões grandes). Cada píxel entra
     *   na fila no máximo uma vez.
     * - Se a cor destino for igual à cor de origem, a operação é ignorada
     *   (no-op) para não produzir entradas de histórico vazias.
     *
     * @return o retângulo alterado (x0, y0, x1, y1 inclusive) ou null se
     *         nada mudou.
     */
    fun floodFill(
        document: PixelDocument,
        startX: Int,
        startY: Int,
        color: Int,
        tolerance: Int = 0,
        recorder: StrokeRecorder? = null
    ): IntArray? {
        if (!document.inBounds(startX, startY)) return null
        val tol = tolerance.coerceIn(0, 255)
        val target = document.get(startX, startY)
        if (target == color) return null // nada a fazer

        val w = document.width
        val h = document.height
        // Acesso direto à vista persistente do buffer do documento
        // (sem cópias integrais; cada píxel é lido/escrito no sitio).
        val doc = document

        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        val ta = (target ushr 24) and 0xFF

        // Um píxel "encaixa" se estiver dentro da tolerância por canal E não
        // tiver já sido preenchido (o píxel preenchido tem o valor `color`;
        // esta condição dupla impede re-enfileiramento infinito quando a cor
        // de preenchimento está dentro da tolerância da cor de origem).
        fun matches(argb: Int): Boolean {
            if (argb == color) return false
            if (tol == 0) return argb == target
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val a = (argb ushr 24) and 0xFF
            return Math.abs(r - tr) <= tol &&
                Math.abs(g - tg) <= tol &&
                Math.abs(b - tb) <= tol &&
                Math.abs(a - ta) <= tol
        }

        val total = w * h
        val queue = IntArray(total) // ring buffer de índices
        var head = 0
        var tail = 0

        var minX = startX; var maxX = startX
        var minY = startY; var maxY = startY

        // Enfileira um índice, marcando-o já com a cor final (cada píxel
        // entra na fila no máximo uma vez, sem array extra de visited).
        // O bounding box é rastreado NO ENQUEUE: se o preenchimento cobrir o
        // documento inteiro, o ring buffer enche (head == tail) e o loop
        // termina cedo — mas os píxeis e o bbox já estão corretos.
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
            val before = doc.getAt(idx)
            if (before == color) return
            val x = idx % w
            val y = idx / w
            recorder?.onBeforeChange(x, y, before)
            doc.setAt(idx, color)
            recorder?.onChanged(x, y)
        }

        val startIdx = startY * w + startX
        paint(startIdx)
        enqueue(startIdx)

        while (head != tail) {
            val idx = queue[head]
            head = (head + 1) % total
            val x = idx % w
            val y = idx / w

            // Vizinhos 4-conectados (padrão de editores de pixel art),
            // sem alocação de array por píxel.
            val left = idx - 1
            if (x > 0 && matches(doc.getAt(left))) {
                paint(left)
                enqueue(left)
            }
            val right = idx + 1
            if (x < w - 1 && matches(doc.getAt(right))) {
                paint(right)
                enqueue(right)
            }
            val up = idx - w
            if (y > 0 && matches(doc.getAt(up))) {
                paint(up)
                enqueue(up)
            }
            val down = idx + w
            if (y < h - 1 && matches(doc.getAt(down))) {
                paint(down)
                enqueue(down)
            }
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    /** Tamanho máximo da brocha — mantém pixel art coerente e custo previsível. */
    const val MAX_BRUSH: Int = 8
}
