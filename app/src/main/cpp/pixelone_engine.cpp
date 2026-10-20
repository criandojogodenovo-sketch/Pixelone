// Pixel One — Motor de píxeis nativo (C++17 / JNI)
//
// ARQUITETURA (correção de performance — ver docs/buffer-formato.md):
// - O Kotlin e este código partilham o MESMO ByteBuffer.allocateDirect()
//   (fonte de verdade dos píxeis). O endereço nativo é obtido com
//   GetDirectBufferAddress EM CADA CHAMADA e nunca é guardado entre
//   chamadas — a vida útil do buffer excede sempre o trabalho que o usa.
// - UM TRAÇO COMPLETO = UMA CHAMADA JNI: o lote de pontos do gesto chega
//   num único array; este motor interpola com Bresenham entre pontos
//   consecutivos e devolve, numa só passagem: deltas esparsos (índice,
//   cor antiga, cor nova), o retângulo sujo (dirty rectangle) e a duração
//   interna medida com std::chrono::steady_clock.
// - FORMATO DOS PÍXEIS: uint32 0xAARRGGBB por píxel, row-major,
//   little-endian (ByteOrder.nativeOrder() do Kotlin = little-endian em
//   ARM/x86), stride = width * 4 bytes, sem padding.
// - SEGURANÇA: todas as entradas são validadas (buffer direto, capacidade,
//   dimensões, comprimento dos arrays) e os erros devolvem códigos
//   negativos SEM crash — o Kotlin repete em fallback.
// - Threads: nenhuma variável global mutável; sem estado entre chamadas.

#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <chrono>
#include <vector>

namespace {

constexpr jint ERR_BAD_BUFFER = -1; // buffer nulo/não direto/dimensões inválidas
constexpr jint ERR_BAD_ARRAYS = -2; // arrays de saída insuficientes
constexpr jint MAX_DIMENSION = 2048;
constexpr jint HEADER_SIZE = 8;

struct DirtyRect {
    int32_t minX = INT32_MAX;
    int32_t minY = INT32_MAX;
    int32_t maxX = INT32_MIN;
    int32_t maxY = INT32_MIN;

    inline void add(int32_t x, int32_t y) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
    }
};

// Contexto de captura de deltas: escreve nos arrays de scratch fornecidos
// pelo Kotlin (sem alocação nativa por píxel).
struct DeltaCapture {
    jint* indices;
    jint* before;
    jint* after;
    jint capacity;   // número máximo de deltas (= pixelCount)
    jint count = 0;
    DirtyRect rect;

    inline void record(int32_t index, uint32_t oldColor, uint32_t newColor) {
        if (count >= capacity) return; // proteção: nunca ultrapassa os limites
        indices[count] = index;
        before[count] = static_cast<jint>(oldColor);
        after[count] = static_cast<jint>(newColor);
        count++;
        rect.add(index % rectWidth, index / rectWidth);
    }

    int32_t rectWidth = 1; // largura do documento, para converter índice→(x,y)
};

// Valida e resolve o endereço do buffer direto partilhado.
inline uint32_t* resolveBuffer(JNIEnv* env, jobject jbuffer, jint width, jint height,
                               jlong& outCapacity) {
    if (jbuffer == nullptr || width < 1 || height < 1 ||
        width > MAX_DIMENSION || height > MAX_DIMENSION) {
        return nullptr;
    }
    void* addr = env->GetDirectBufferAddress(jbuffer);
    jlong capacity = env->GetDirectBufferCapacity(jbuffer);
    if (addr == nullptr || capacity <= 0) return nullptr;
    const jlong needed = static_cast<jlong>(width) * static_cast<jlong>(height) * 4LL;
    if (capacity < needed) return nullptr; // overflow/atenho: rejeitar antes de operar
    outCapacity = capacity;
    return static_cast<uint32_t*>(addr);
}

// Copia os `count` primeiros elementos de um jintArray para um vetor
// (leitura em região: sem pinning prolongado, sem JNI por elemento).
inline bool readRegion(JNIEnv* env, jintArray arr, jsize count, std::vector<jint>& out) {
    if (arr == nullptr || count < 0) return false;
    const jsize len = env->GetArrayLength(arr);
    if (len < count) return false;
    out.resize(static_cast<size_t>(count));
    env->GetIntArrayRegion(arr, 0, count, out.data());
    return true;
}

inline void writeHeader(JNIEnv* env, jintArray headerArr, const DirtyRect& rect,
                        jint count, jint micros) {
    if (headerArr == nullptr || env->GetArrayLength(headerArr) < HEADER_SIZE) return;
    jint header[HEADER_SIZE] = {
        rect.minX, rect.minY, rect.maxX, rect.maxY,
        micros, 1 /* caminho nativo */, count, 0
    };
    env->SetIntArrayRegion(headerArr, 0, HEADER_SIZE, header);
}

// Carimbo quadrado da brocha (mesma geometria do fallback Kotlin:
// centrado, tamanhos pares estendem para a direita/baixo).
inline void stamp(JNIEnv* env_unused, uint32_t* pixels, jint width, jint height,
                  int32_t cx, int32_t cy, uint32_t color, jint brushSize,
                  DeltaCapture& deltas) {
    const jint size = brushSize < 1 ? 1 : (brushSize > 8 ? 8 : brushSize);
    const int32_t half = (size - 1) / 2;
    for (int32_t dy = 0; dy < size; ++dy) {
        const int32_t y = cy - half + dy;
        if (y < 0 || y >= height) continue;
        for (int32_t dx = 0; dx < size; ++dx) {
            const int32_t x = cx - half + dx;
            if (x < 0 || x >= width) continue;
            const int32_t index = y * width + x;
            const uint32_t oldColor = pixels[index];
            if (oldColor != color) {
                pixels[index] = color;
                deltas.record(index, oldColor, color);
            }
        }
    }
}

// Bresenham entre (x0,y0) e (x1,y1) — idêntico ao fallback Kotlin.
inline void bresenhamLine(uint32_t* pixels, jint width, jint height,
                          int32_t x0, int32_t y0, int32_t x1, int32_t y1,
                          uint32_t color, jint brushSize, DeltaCapture& deltas,
                          JNIEnv* env) {
    int32_t dx = std::abs(x1 - x0);
    int32_t dy = -std::abs(y1 - y0);
    int32_t stepX = x0 < x1 ? 1 : -1;
    int32_t stepY = y0 < y1 ? 1 : -1;
    int32_t err = dx + dy;
    int32_t x = x0, y = y0;
    while (true) {
        stamp(env, pixels, width, height, x, y, color, brushSize, deltas);
        if (x == x1 && y == y1) break;
        const int32_t e2 = 2 * err;
        if (e2 >= dy) { err += dy; x += stepX; }
        if (e2 <= dx) { err += dx; y += stepY; }
    }
}

} // namespace

extern "C" {

// PROVA DE BUFFER PARTILHADO: soma os primeiros `count` píxeis lidos pelo
// endereço direto do C++. O Kotlin compara com a soma da sua própria vista.
JNIEXPORT jlong JNICALL
Java_com_pixelone_studio_pixel_NativeEngine_nativeBufferProbe(
        JNIEnv* env, jobject /*thiz*/, jobject jbuffer, jint count) {
    jlong capacity = 0;
    uint32_t* pixels = resolveBuffer(env, jbuffer, 1, 1, capacity);
    if (pixels == nullptr) return -1;
    jlong total = static_cast<jlong>(count);
    if (total < 0) return -1;
    if (total * 4LL > capacity) total = capacity / 4LL;
    jlong sum = 0;
    for (jlong i = 0; i < total; ++i) {
        sum += static_cast<jlong>(pixels[i]);
    }
    return sum;
}

// Escrita nativa num índice do buffer partilhado (teste instrumentado).
JNIEXPORT jboolean JNICALL
Java_com_pixelone_studio_pixel_NativeEngine_nativeBufferWrite(
        JNIEnv* env, jobject /*thiz*/, jobject jbuffer, jint index, jint value) {
    jlong capacity = 0;
    uint32_t* pixels = resolveBuffer(env, jbuffer, 1, 1, capacity);
    if (pixels == nullptr) return JNI_FALSE;
    const jlong idx = static_cast<jlong>(index);
    if (idx < 0 || idx * 4LL + 4LL > capacity) return JNI_FALSE;
    pixels[idx] = static_cast<uint32_t>(value);
    return JNI_TRUE;
}

// TRAÇO COMPLETO EM UMA CHAMADA: lote de pontos + Bresenham + brocha +
// borracha (color = 0x00000000 escreve transparência real). Devolve o
// número de deltas ou código de erro negativo.
JNIEXPORT jint JNICALL
Java_com_pixelone_studio_pixel_NativeEngine_nativeStrokeBatch(
        JNIEnv* env, jobject /*thiz*/,
        jobject jbuffer, jint width, jint height,
        jintArray jpoints, jint pointCount,
        jint color, jint brushSize,
        jintArray outIndices, jintArray outBefore, jintArray outAfter,
        jintArray outHeader) {

    const auto t0 = std::chrono::steady_clock::now();

    jlong capacity = 0;
    uint32_t* pixels = resolveBuffer(env, jbuffer, width, height, capacity);
    if (pixels == nullptr) return ERR_BAD_BUFFER;

    if (pointCount < 1 || jpoints == nullptr) return ERR_BAD_BUFFER;
    const jsize pointsLen = env->GetArrayLength(jpoints);
    if (pointsLen < pointCount * 2) return ERR_BAD_BUFFER;

    const jint pixelCount = width * height; // ≤ 2048² — sem overflow
    if (outIndices == nullptr || outBefore == nullptr || outAfter == nullptr ||
        env->GetArrayLength(outIndices) < pixelCount ||
        env->GetArrayLength(outBefore) < pixelCount ||
        env->GetArrayLength(outAfter) < pixelCount) {
        return ERR_BAD_ARRAYS;
    }

    // Leitura do lote de pontos (uma região, sem JNI por elemento).
    std::vector<jint> points;
    if (!readRegion(env, jpoints, pointCount * 2, points)) return ERR_BAD_ARRAYS;

    // Saídas: os arrays Kotlin são os scratch reutilizáveis; obtém-se o
    // ponteiro de escrita apenas nesta chamada (nunca guardado).
    jint* outIdx = static_cast<jint*>(env->GetPrimitiveArrayCritical(outIndices, nullptr));
    jint* outBef = outIdx == nullptr ? nullptr
        : static_cast<jint*>(env->GetPrimitiveArrayCritical(outBefore, nullptr));
    jint* outAft = outBef == nullptr ? nullptr
        : static_cast<jint*>(env->GetPrimitiveArrayCritical(outAfter, nullptr));
    if (outIdx == nullptr || outBef == nullptr || outAft == nullptr) {
        if (outIdx != nullptr) env->ReleasePrimitiveArrayCritical(outIndices, outIdx, JNI_ABORT);
        if (outBef != nullptr) env->ReleasePrimitiveArrayCritical(outBefore, outBef, JNI_ABORT);
        if (outAft != nullptr) env->ReleasePrimitiveArrayCritical(outAfter, outAft, JNI_ABORT);
        return ERR_BAD_ARRAYS;
    }

    DeltaCapture deltas;
    deltas.indices = outIdx;
    deltas.before = outBef;
    deltas.after = outAft;
    deltas.capacity = pixelCount;
    deltas.rectWidth = width;

    // O lote inteiro é uma operação lógica: primeiro ponto + Bresenham
    // entre pontos consecutivos (nenhuma chamada JNI por píxel/segmento).
    const uint32_t target = static_cast<uint32_t>(color);
    stamp(env, pixels, width, height, points[0], points[1], target, brushSize, deltas);
    for (jint i = 1; i < pointCount; ++i) {
        bresenhamLine(pixels, width, height,
                      points[(i - 1) * 2], points[(i - 1) * 2 + 1],
                      points[i * 2], points[i * 2 + 1],
                      target, brushSize, deltas, env);
    }

    env->ReleasePrimitiveArrayCritical(outIndices, outIdx, 0);
    env->ReleasePrimitiveArrayCritical(outBefore, outBef, 0);
    env->ReleasePrimitiveArrayCritical(outAfter, outAft, 0);

    const jint micros = static_cast<jint>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - t0).count());
    writeHeader(env, outHeader, deltas.rect, deltas.count, micros);
    return deltas.count;
}

// PREENCHIMENTO COM BALDE: BFS 4-conectado com tolerância por canal,
// semântica idêntica ao fallback Kotlin (incluindo a regra anti
// re-enfileiramento: píxeis já com a cor destino não "encaixam").
JNIEXPORT jint JNICALL
Java_com_pixelone_studio_pixel_NativeEngine_nativeFloodFill(
        JNIEnv* env, jobject /*thiz*/,
        jobject jbuffer, jint width, jint height,
        jint startX, jint startY, jint color, jint tolerance,
        jintArray outIndices, jintArray outBefore, jintArray outAfter,
        jintArray outHeader) {

    const auto t0 = std::chrono::steady_clock::now();

    jlong capacity = 0;
    uint32_t* pixels = resolveBuffer(env, jbuffer, width, height, capacity);
    if (pixels == nullptr) return ERR_BAD_BUFFER;
    if (startX < 0 || startY < 0 || startX >= width || startY >= height) return ERR_BAD_BUFFER;

    const jint pixelCount = width * height;
    if (outIndices == nullptr || outBefore == nullptr || outAfter == nullptr ||
        env->GetArrayLength(outIndices) < pixelCount ||
        env->GetArrayLength(outBefore) < pixelCount ||
        env->GetArrayLength(outAfter) < pixelCount) {
        return ERR_BAD_ARRAYS;
    }

    const uint32_t target = static_cast<uint32_t>(color);
    const uint32_t origin = pixels[startY * width + startX];
    if (origin == target) return 0; // no-op — nada a fazer

    const jint tol = tolerance < 0 ? 0 : (tolerance > 255 ? 255 : tolerance);
    const int32_t oR = (origin >> 16) & 0xFF;
    const int32_t oG = (origin >> 8) & 0xFF;
    const int32_t oB = origin & 0xFF;
    const int32_t oA = (origin >> 24) & 0xFF;

    auto matches = [&](uint32_t argb) -> bool {
        if (argb == target) return false; // já preenchido → nunca re-enfileira
        if (tol == 0) return argb == origin;
        const int32_t r = (argb >> 16) & 0xFF;
        const int32_t g = (argb >> 8) & 0xFF;
        const int32_t b = argb & 0xFF;
        const int32_t a = (argb >> 24) & 0xFF;
        return std::abs(r - oR) <= tol && std::abs(g - oG) <= tol &&
               std::abs(b - oB) <= tol && std::abs(a - oA) <= tol;
    };

    jint* outIdx = static_cast<jint*>(env->GetPrimitiveArrayCritical(outIndices, nullptr));
    jint* outBef = outIdx == nullptr ? nullptr
        : static_cast<jint*>(env->GetPrimitiveArrayCritical(outBefore, nullptr));
    jint* outAft = outBef == nullptr ? nullptr
        : static_cast<jint*>(env->GetPrimitiveArrayCritical(outAfter, nullptr));
    if (outIdx == nullptr || outBef == nullptr || outAft == nullptr) {
        if (outIdx != nullptr) env->ReleasePrimitiveArrayCritical(outIndices, outIdx, JNI_ABORT);
        if (outBef != nullptr) env->ReleasePrimitiveArrayCritical(outBefore, outBef, JNI_ABORT);
        if (outAft != nullptr) env->ReleasePrimitiveArrayCritical(outAfter, outAft, JNI_ABORT);
        return ERR_BAD_ARRAYS;
    }

    DeltaCapture deltas;
    deltas.indices = outIdx;
    deltas.before = outBef;
    deltas.after = outAft;
    deltas.capacity = pixelCount;
    deltas.rectWidth = width;

    // BFS com fila de cada-píxel-no-máximo-uma-vez (aloca 1× por operação
    // discreta de fill — não é um caminho por evento de toque).
    std::vector<int32_t> queue;
    queue.reserve(static_cast<size_t>(pixelCount));
    size_t head = 0;

    auto enqueue = [&](int32_t index) {
        queue.push_back(index);
        deltas.rect.add(index % width, index / width);
    };

    auto paint = [&](int32_t index) {
        const uint32_t oldColor = pixels[index];
        if (oldColor == target) return;
        pixels[index] = target;
        deltas.record(index, oldColor, target);
    };

    const int32_t startIdx = startY * width + startX;
    paint(startIdx);
    enqueue(startIdx);

    while (head < queue.size()) {
        const int32_t idx = queue[head++];
        const int32_t x = idx % width;
        const int32_t y = idx / width;

        if (x > 0 && matches(pixels[idx - 1])) { paint(idx - 1); enqueue(idx - 1); }
        if (x < width - 1 && matches(pixels[idx + 1])) { paint(idx + 1); enqueue(idx + 1); }
        if (y > 0 && matches(pixels[idx - width])) { paint(idx - width); enqueue(idx - width); }
        if (y < height - 1 && matches(pixels[idx + width])) { paint(idx + width); enqueue(idx + width); }
    }

    env->ReleasePrimitiveArrayCritical(outIndices, outIdx, 0);
    env->ReleasePrimitiveArrayCritical(outBefore, outBef, 0);
    env->ReleasePrimitiveArrayCritical(outAfter, outAft, 0);

    const jint micros = static_cast<jint>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - t0).count());
    writeHeader(env, outHeader, deltas.rect, deltas.count, micros);
    return deltas.count;
}

} // extern "C"
