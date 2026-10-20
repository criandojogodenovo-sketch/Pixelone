# Correção: escala (1x–16x) e fundo na exportação PNG

**Data:** 2026-10-21 · **Branch:** `fix/png-export-escala-fundo` · **Base:** `develop`

## Pedido

Na exportação PNG, adicionar uma opção de escala (1x, 2x, 4x, 8x, 16x):

- Criar um bitmap **original** com a largura e altura **reais** do canvas e o bitmap
  **final** com `Bitmap.createScaledBitmap(original, w*escala, h*escala, false)` — o
  `false` (filter) desliga a suavização e mantém os píxeis nítidos.
- Alternativa prevista: desenhar com `Canvas.drawBitmap` e um `Paint` com
  `isFilterBitmap = false` e `isAntiAlias = false`.
- Deixar escolher o fundo: **transparente** ou **cor sólida (preto)**, preenchendo o
  bitmap com `canvas.drawColor` **antes** de desenhar.
- Guardar com `compress(PNG, 100)` e mostrar no Toast a **largura**, **altura** e
  **tamanho final em bytes**.

## Implementação

### Núcleo — `app/src/main/java/com/pixelone/studio/export/PngExport.kt`

| Novo elemento | Função |
| --- | --- |
| `enum ScaleOption(factor)` | X1, X2, X4, X8, X16 (`label` "4x" para a UI) |
| `enum BackgroundOption` | `TRANSPARENT` (alfa preservado) e `SOLID_BLACK` (0xFF000000) |
| `validateScaledDimensions(w, h, scale)` | Validação ANTES de alocar: lado ≤ `MAX_SCALED_SIDE` (16 384) e píxeis ≤ `MAX_SCALED_PIXELS` (32 MP). Lança `IllegalStateException` com mensagem pronta para o Toast — protege contra OOM (2048×2048 a 16x seriam ~4 GB) |
| `nearestNeighborPaint()` | Paint com `isFilterBitmap=false` e `isAntiAlias=false` (alternativa prevista no pedido) |
| `buildBitmapFromDocument(doc, scale, background)` | Novo overload — ver abaixo |

Caminhos do overload (ambas as técnicas prescritas são usadas, cada uma onde encaixa):

1. **1x + transparente** — devolve o bitmap original SEM nenhum passo de desenho:
   caminho 1:1 com o buffer partilhado Kotlin↔C++, byte-exato (incl. alfa baixo),
   igual ao comportamento anterior.
2. **Transparente com escala > 1** — `Bitmap.createScaledBitmap(original, w*s, h*s,
   false)`: filter `false` = nearest-neighbor, píxeis nítidos, alfa preservado.
3. **Fundo preto** — bitmap final novo → `canvas.drawColor(preto)` ANTES de
   desenhar → arte por cima com `Canvas.drawBitmap` + `nearestNeighborPaint()`
   (a alternativa prevista no pedido, necessária porque o fundo tem de ser pintado
   antes da arte).

O bitmap original é reciclado no fim (`finally`); só o final sobrevive. O final é
verificado: não nulo, não reciclado, dimensões exatamente `w*s × h*s`.

### ViewModel — `EditorViewModel.kt`

- `exportPngToDownloads(scale, background)` e o caminho legado SAF
  `exportPng(uri, scale, background, onDone)` aceitam as novas opções.
- O fluxo MediaStore (IS_PENDING=1 → compress → flush → close → IS_PENDING=0)
  fica intacto.
- **Toast de sucesso** passou a incluir as dimensões FINAIS:
  `"PNG exportado: Download/<nome> — ok=true, <w>x<h> px, <bytes> bytes"`.
- Toast de erro continua a mostrar `e.message` (ok=false) e a remover a entrada
  pendente — exceções nunca engolidas; o erro da escala excessiva chega ao
  utilizador com a mensagem clara de limite.
- `PerfLog` (`PixelOnePerf/*`) regista `escala=` e `fundo=` para diagnóstico.

### UI — `EditorDialogs.kt` + `TextureStudioScreen.kt`

- Novo `ExportOptionsDialog`: chips de escala 1x/2x/4x/8x/16x, fundo
  Transparente/Preto e pré-visualização "Saída: 64×64 → 512×512 px".
- Se a escala exceder os limites, o botão Exportar é desativado com aviso
  (bloqueio preventivo, coerente com `validateScaledDimensions`).
- O botão Exportar PNG da barra abre o diálogo; depois:
  - Android 10+ → `exportPngToDownloads(scale, background)` (Download/ direto);
  - Android < 10 → guarda as escolhas e lança o seletor SAF, que passa as opções
    ao `exportPng`.

### Versão

`versionCode 5`, `versionName "0.3.2-escala-png"`.

## Precisão (nota honesta)

- 1x transparente: bytes **exatos** (sem composição — `setPixels` direto).
- Escalas > 1: o desenho passa pelo pipeline premultiplicado do Skia. Píxeis
  opacos e totalmente transparentes (o padrão da pixel art) permanecem exatos;
  alfas MUITO baixos (≤ ~7/255) podem sofrer arredondamento de 1 bit nos RGB —
  limitação conhecida do pipeline gráfico do Android, registada aqui para
  transparência.
- Fundo preto: alfa do resultado é sempre 255; semi-transparências são achatadas
  sobre o preto (canal × alfa), que é o resultado visual correto de "flatten".

### Descoberta do CI (corrigida no mesmo branch)

A primeira submissão desenhava o fundo preto num destino `setPremultiplied(false)`.
O CI falhou com `RuntimeException` nos 2 testes de fundo preto — e o motivo é
estrutural, não só do Robolectric: **o Skia não aceita superfícies de Canvas com
alphaType `kUnpremul`** (`SkSurfaces::Raster` devolve null → exceção), também em
dispositivo. Correção: o destino do desenho mantém-se PREMULTIPLICADO (padrão).
Como o resultado do fundo preto é 100% opaco (preto + arte), premul e reto
coincidem byte a byte e o PNG sai exato; os testes passaram a validar isso.

## Testes (`PngExportTest.kt`)

Todos em JVM/Robolectric (sdk 34). Novos 12 testes, cobrindo:

1. `escala 1x com fundo transparente é o caminho exato igual ao original` — igualdade
   píxel a píxel com `buildBitmapFromDocument(doc)`, incluindo alfa 1 e 64.
2. `escala 4x produz blocos nítidos de 4 por 4 sem cores interpoladas` — blocos
   4×4 idênticos à origem; conjunto de cores do PNG == conjunto da origem (prova de
   nearest-neighbor estrito).
3. `escala 16x de canvas pequeno dá dimensões exatas e preserve alfa` — 3×2 → 48×32;
   transparência preservada.
4. `roundtrip PNG com escala 2x escreve dimensões finais no IHDR` — ficheiro real
   12×8 (IHDR big-endian lido byte a byte).
5. `fundo preto preenche píxeis transparentes e preserva a arte opaca` —
   transparente → 0xFF000000 exato; arte opaca byte a byte; só duas cores no PNG.
6. `fundo preto achata semi-transparência sobre preto com alfa 255` — α=128,
   canais = canal×α/255 (valores robustos ao arredondamento do Skia).
7. `fundo transparente mantém alfa zero na escala` (8x).
8. `escala que excede o lado máximo é rejeitada antes de alocar` (2048² a 16x).
9. `escala que excede o orçamento de píxeis é rejeitada` (2048² a 8x e 4x > 32 MP).
10. `escalas normais de pixel art são aceites` (128²/256² a 16x, 2048² a 1x/2x).
11. `canvas pequeno a 16x produz bitmap final válido 64 por 64`.
12. `paint nearest-neighbor tem filter e antiAlias desligados` +
    `opções de escala expõem os fatores pedidos` (1, 2, 4, 8, 16).

Os 10 testes anteriores do núcleo z.png (escrita, nome, ContentValues, roundtrip
byte-exato) permanecem verdes — o comportamento anterior não regrediu.

## Compatibilidade

- `.osp`, spritesheet, atlas, undo/redo: **inalterados** (o overload antigo
  `buildBitmapFromDocument(doc)` continua a existir e é o caminho 1x).
- Exportação de spritesheet/atlas não recebe escala nesta fase (fora do pedido).
