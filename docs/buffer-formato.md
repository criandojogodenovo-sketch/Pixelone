# Formato do buffer de píxeis partilhado (Kotlin ↔ C++17)

Este documento define o formato binário do buffer de píxeis do Pixel One,
a única fonte de verdade do documento, partilhada entre o Kotlin
(`PixelDocument`) e o motor nativo C++17 (`app/src/main/cpp/
pixelone_engine.cpp`).

## Identidade do buffer

- Alocação: `ByteBuffer.allocateDirect(width * height * 4)` no Kotlin.
- Ordem de bytes: `ByteOrder.nativeOrder()` — **little-endian** em todos os
  dispositivos Android atuais (ARM e x86). O lado C++ lê os bytes diretamente
  como `uint32_t`, pelo que a mesma ordem tem de valer nos dois lados.
- Vista Kotlin: **um único** `IntBuffer` persistente (`sharedBuffer.asIntBuffer()`)
  criado no construtor do `PixelDocument`. Todos os acessos são **absolutos**
  (`intView.get(i)` / `intView.put(i, v)`) — nunca se cria uma vista nova por
  píxel nem por evento de toque.
- Vista C++: `env->GetDirectBufferAddress(buffer)` obtido **em cada chamada
  JNI** e nunca guardado entre chamadas — a vida útil do buffer (propriedade
  do `PixelDocument`) excede sempre o trabalho síncrono que o utiliza.

## Layout binário

| Propriedade | Valor |
| --- | --- |
| Tipo por píxel | `uint32_t` / Kotlin `Int` |
| Codificação da cor | `0xAARRGGBB` (alpha nos 8 bits mais significativos) |
| Bytes em memória (little-endian) | `B, G, R, A` por píxel |
| Disposição | row-major: índice = `y * width + x` |
| Stride | `width * 4` bytes (compacto, sem padding) |
| Capacidade | exatamente `width * height * 4` bytes |
| Valor inicial | `0x00000000` (totalmente transparente) |

## Regras de vida útil e segurança

1. O buffer é propriedade do `PixelDocument`; o motor nativo nunca aloca nem
   liberta a memória do documento.
2. O ponteiro nativo é resolvido por chamada (GetDirectBufferAddress) e a
   operação termina antes de a chamada devolver — não existem ponteiros
   retidos.
3. Toda a mutação do documento (traço, balde, undo/redo, snapshots) corre no
   executor serial `pixelone-doc-mutation` (paralelismo 1). A apresentação
   (bitmap espelho) é atualizada na main thread a partir do dirty rect — não
   há leitura/escrita concorrente do buffer sem sincronização.
4. Validações ANTES de qualquer acesso (nos dois lados): buffer não nulo e
   direto, capacidade ≥ `width*height*4`, `1 ≤ width,height ≤ 2048`,
   `width*height ≤ 4 194 304` (MAX_PIXELS), comprimento dos arrays de
   saída ≥ pixelCount. Violações devolvem códigos de erro (`-1`, `-2`) em
   vez de crashar.

## Formato dos resultados do motor (uma chamada por operação)

Cada traço/fill é processado numa única chamada JNI. O motor devolve:

- **retorno (jint)**: número de deltas alterados, ou erro negativo
  (`-1` buffer/dimensões inválidas, `-2` arrays insuficientes);
- **outHeader (jint[8])**:
  - `[0..3]` dirty rect `x0, y0, x1, y1` (inclusive, em píxeis da imagem);
  - `[4]` duração interna do motor (microssegundos, `std::chrono::steady_clock`);
  - `[5]` caminho (1 = nativo);
  - `[6]` contagem de deltas (cópia do retorno, para o fallback);
- **outIndices / outBefore / outAfter (jint[pixelCount] reutilizados)**:
  deltas esparsos — índice linear, cor antiga (`0xAARRGGBB`) e cor nova.
  Apenas os primeiros `retorno` elementos são válidos.

Os arrays de saída são scratch buffers reutilizados por documento
(uma alocação quando o documento é instalado, nunca por gesto).

## Compatibilidade preservada

- `.osp` (ZIP + `manifest.json` + píxeis RGBA sem perdas): o
  `PixelDocument.toRgbaBytes()` continua a produzir exatamente os mesmos
  bytes (R,G,B,A por píxel, não premultiplicado).
- Exportação PNG: feita a partir de um snapshot do buffer com
  `Bitmap.setPremultiplied(false)` + `copyPixelsFromBuffer` — os bytes do
  PNG são idênticos aos píxeis do documento (verificado no roundtrip PNG
  dos testes).
- Bitmap de apresentação: cache visual atualizada por região com
  `Bitmap.setPixels` (nunca recriado, nunca cópia integral por evento);
  NUNCA é segunda fonte de verdade nem entra em exportações.
