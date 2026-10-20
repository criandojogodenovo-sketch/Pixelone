# Relatório — Correções de performance, canvas e UX (Pixel One 0.3.0)

**Branch:** `fix/texture-studio-performance` (a partir de `develop`; `main` intocado)
**Âmbito:** PROMPT de correção crítica — Problema 1 (atraso na pintura),
Problema 2 (canvas não aparecia ao criar projeto), Problema 3 (entrada do
Texture Studio pouco clara).

---

## A. Causas identificadas (diagnóstico no código real, antes de alterar)

### Problema 1 — atraso de segundos na pintura
O atraso não tinha uma causa única, mas uma cadeia de caminhos caros no
evento de toque:

1. **`Bitmap.setPixel()` por píxel, por evento de movimento** —
   `EditorViewModel.StrokeSync.onChanged()` chamava `bitmap.setPixel(x, y, …)`
   para cada píxel alterado durante o traço. Cada `setPixel` é uma chamada
   JNI para o Skia; num traço com brocha grande isto eram centenas/milhares
   de chamadas nativas por segundo, todas na main thread.
2. **Cópia integral do documento no fim de cada operação** —
   `afterEditFull()` fazia `bitmap.copyPixelsFromBuffer(IntBuffer.wrap(...))`
   com o documento INTEIRO no fim de cada traço, cancelamento, undo, redo e
   fill (`copyPixelsFromBuffer(IntBuffer.wrap(doc.pixelsView()))`).
3. **Invalidação mal dirigida** — `frameVersion` não era lido no bloco de
   desenho do `PixelCanvas` (só escala/offset/grelha); as edições não
   invalidavam o canvas de forma determinística, e a invalidação "útil"
   vinha de recomposições da barra inferior.
4. **Estado escrito durante o draw** — `updateViewport(size.width,
   size.height)` era chamado DENTRO do `DrawScope`, e na primeira passagem
   escrevia `scale`/`offset` durante a fase de desenho (antipadrão de
   invalidação).
5. **Balde na main thread** — `tapTool()` executava o flood fill (custo
   proporcional à região, até ao documento inteiro) no thread de UI.

### Problema 2 — canvas não aparecia ao criar projeto
**Causa raiz:** `EditorViewModel.document` e `imageBitmap` eram **campos
simples, não estado Compose** (`mutableStateOf`). A gate
`if (doc == null)` do ecrã lia um valor não observável, pelo que criar o
projeto não recomponha de forma fiável o conteúdo do `Scaffold` — o ecrã
vazio continuava renderizado até que alguma recomposição incidental
(mensagem de estado, interação noutra zona) forçasse a reavaliação. O
ajuste à vista dependia ainda do viewport recebido durante o draw
(`pendingFit`/`updateViewport` consumidos na fase de desenho), frágil
quando o layout ainda não existia.

### Problema 3 — entrada do Texture Studio pouco clara
- Ecrã vazio com apenas 2 ações (Novo/Abrir), sem "Novo spritesheet".
- Presets do diálogo incluíam 8/256 e não os pedidos 16/32/64/128.
- Sem ação "Guardar" na barra superior (só no menu suspenso).
- Ferramentas sem rótulos de texto (só ícone + contentDescription).
- Primeira linha da barra inferior sem scroll horizontal (em ecrãs
  estreitos os botões de undo/redo ficavam apertados).
- Sem cor secundária nem troca primária↔secundária.
- Grelha já tinha limiar e culling, mas reconstruía as linhas em todos os
  frames de pan (sem Path em cache).

---

## B. Ficheiros completos alterados (caminhos + papel)

**Criados**

| Ficheiro | Papel |
| --- | --- |
| `app/src/main/cpp/pixelone_engine.cpp` | Motor C++17/JNI: traço em lote (Bresenham+brocha+borracha), balde BFS, deltas esparsos, dirty rect, duração interna; validações e códigos de erro sem crash |
| `app/src/main/cpp/CMakeLists.txt` | C++17, otimização por configuração (Release −O2/RelWithDebInfo, Debug −O0), stack-protector |
| `app/src/main/java/com/pixelone/studio/pixel/NativeEngine.kt` | Ponte JNI + fallback Kotlin idêntico + scratch buffers por documento + probe de buffer partilhado |
| `app/src/main/java/com/pixelone/studio/perf/PerfLog.kt` | Instrumentação `PixelOnePerf/*` segura em JVM |
| `app/src/test/java/com/pixelone/studio/pixel/PixelDocumentBufferTest.kt` | Buffer direto: capacidade, cantos/centro, índices, região, roundtrip .osp |
| `app/src/test/java/com/pixelone/studio/pixel/StrokeBatchTest.kt` | Traço em lote (H/V/diagonais, brocha, borracha, deltas, dirty rect, erros) |
| `app/src/test/java/com/pixelone/studio/pixel/StrokeBenchmarkTest.kt` | Medições de código (JVM) com saída `BENCH` |
| `app/src/test/java/com/pixelone/studio/editor/EditorViewModelStateTest.kt` | Robolectric: estado observável, fit-to-screen, zoom, cores P/S, preview |
| `app/src/androidTest/java/com/pixelone/studio/NativeEngineInstrumentedTest.kt` | Prova em emulador/dispositivo do buffer partilhado Kotlin↔C++ e do caminho nativo |
| `app/src/androidTest/java/com/pixelone/studio/CanvasVisibilityInstrumentedTest.kt` | UI Compose: 3 ações visíveis; canvas imediato ao criar projeto |
| `docs/buffer-formato.md` | Especificação do formato binário do buffer partilhado |
| `docs/relatorio-correcoes-performance.md` | Este relatório |

**Modificados**

| Ficheiro | Alteração essencial |
| --- | --- |
| `app/src/main/java/com/pixelone/studio/pixel/PixelDocument.kt` | Fonte de verdade migrada para `ByteBuffer.allocateDirect` + vista `IntBuffer` persistente (acessos absolutos); `copyRegionInto`; validação de buffer |
| `app/src/main/java/com/pixelone/studio/pixel/PixelEngine.kt` | floodFill opera sobre o buffer direto; documentação do caminho duplo |
| `app/src/main/java/com/pixelone/studio/pixel/HistoryManager.kt` | Deltas esparsos (índice/antes/depois) com bbox; `commitDeltas`; correção do bug de chaves `y<<16|x` em `cancelStroke`/`buildSparseEdit` |
| `app/src/main/java/com/pixelone/studio/editor/EditorViewModel.kt` | Estado observável; preview de traço; lote único no executor serial; atualização regional; snapshots para IO; cor secundária; instrumentação |
| `app/src/main/java/com/pixelone/studio/ui/editor/PixelCanvas.kt` | `onSizeChanged` (sem estado no draw); leitura de `frameVersion` no draw; preview; grelha com Path em cache + culling; testTags |
| `app/src/main/java/com/pixelone/studio/ui/texture/TextureStudioScreen.kt` | 3 ações no ecrã vazio; Guardar na top bar; rótulos; scroll nas 2 linhas; paleta primária/secundária com troca |
| `app/src/main/java/com/pixelone/studio/ui/editor/EditorDialogs.kt` | Presets 16/32/64/128 (+ personalizado) |
| `app/src/main/java/com/pixelone/studio/editor/PixelIcons.kt` | Ícone `swapColors` |
| `app/build.gradle.kts` | `externalNativeBuild` (CMake), ABIs, versionCode 3 / versionName 0.3.0-performance, deps androidTest, `isReturnDefaultValues` |
| `gradle.properties` | Memória/paralelismo contidos (daemon 1536m, 2 workers) |
| `.github/workflows/android-release.yml` | Novo job `instrumented-tests` (emulador); job `release` intocado |
| `README.md` | Secção 0.3.0 + documentação técnica |
| `app/src/test/java/com/pixelone/studio/pixel/HistoryManagerTest.kt` | Asserções adaptadas a `undo/redo → SparseEdit?` |

**Removidos:** nenhum ficheiro.

---

## C. Testes executados e resultados

| Suíte | Âmbito | Resultado |
| --- | --- | --- |
| `:app:testReleaseUnitTest` (local, JDK 17 Temurin) | 114 testes: motor, buffer direto, traço em lote (fallback), histórico esparso, .osp, repositório, spritesheet, atlas, roundtrip PNG (Robolectric), estado do ViewModel (Robolectric) | **114/114 verdes** |
| `:app:testReleaseUnitTest` no GitHub Actions (job release) | Mesma suíte | **verdes** (ver G) |
| `:app:connectedDebugAndroidTest` (emulador API 30, CI) | Buffer partilhado Kotlin↔C++, caminho nativo, UI Compose (canvas imediato) | ver G (execução real registada; primeira tentativa falhou por um import em falta num teste — corrigido e re-executado) |
| Cobertura dos critérios pedidos | cantos/centro; linhas H/V/diagonais contínuas; brocha/borracha; sem acessos fora dos limites; 1 chamada JNI por traço; deltas=alterados; dirty rect; erros sem crash; undo/redo exatos; uma entrada por traço; memória limitada; redo limpo; criar/abrir → canvas imediato; paleta P/S; .osp/PNG/spritesheet/atlas preservados (114 testes incluem os das fases anteriores, todos verdes) | **cumprido** (dispositivo físico: não disponível — ver F/G) |

---

## D. Build C++17/NDK e confirmação das flags de otimização

- CMake 3.22.1 + NDK r27 (`27.0.12077973`), C++17 (`target_compile_features(cxx_std_17)` + `-std=c++17`), STL estática (`c++_static`).
- **Prova das flags** (`app/.cxx/RelWithDebInfo/<hash>/arm64-v8a/compile_commands.json`, entrada do `pixelone_engine.cpp`):

```
FLAGS: ['-O2', '-fno-omit-frame-pointer', '-fstack-protector-strong', '-g', '-std=c++17']
```

- A variante **release** do AGP mapeia para a configuração CMake
  **RelWithDebInfo** (comportamento predefinido do AGP — mantém símbolos
  para simbolização de crashes). Nesta configuração o CMakeLists aplica
  **−O2**; Debug aplica −O0 −g. Nenhuma flag que comprometa a segurança de
  memória (`-fstack-protector-strong` e frame pointer ativos sempre).
- ABIs construídas: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (todas
  presentes no APK — verificado com `unzip -l`).
- Build local: `:app:assembleDebug` (11,6 MB) e `:app:assembleRelease`
  (não assinado localmente; a assinatura só acontece no CI com os secrets)
  concluídos com sucesso.

## E. Evidência de que o motor usa JNI e o buffer partilhado

1. **Código**: `System.loadLibrary("pixelone_engine")` em `NativeEngine`;
   funções `external` implementadas em `pixelone_engine.cpp` com
   `GetDirectBufferAddress` por chamada; `libpixelone_engine.so` presente
   no APK nas 4 ABIs.
2. **Testes instrumentados** (executam no emulador/dispositivo, job
   `instrumented-tests`):
   - `kotlinECppLeemAMesmaMemoria`: o C++ soma os píxeis pelo endereço
     direto e o resultado é IGUAL à soma da vista Kotlin (mesma memória);
   - `escritaNativaEhVisivelPelaVistaKotlin`: escrita pelo C++ lida pelo
     Kotlin;
   - `tracoMultiSegmentoProcessaNumaUnicaChamadaNativa`: traço "N" de 4
     pontos → `result.native == true`, continuidade Bresenham, deltas e
     dirty rect corretos — um traço completo, uma chamada;
   - `result.native` asserido também no balde.
3. **Instrumentação em runtime**: cada traço/fill registra uma linha
   `PixelOnePerf/JNI` com `caminho=nativo|kotlin`, permitindo confirmar em
   logcat qual caminho executou.

## F. Valores reais de desempenho e condições da medição

**Condições:** medições de CÓDIGO em JVM (CI/local, OpenJDK/Temurin 17),
NÃO de dispositivo físico — nenhum aparelho físico estava acessível (e
declaramo-lo explicitamente). O custo nativo real (C++) e o ganho do
caminho de render em hardware são medidos nos testes instrumentados de
emulador e pelos logs `PixelOnePerf/*` em qualquer dispositivo.

```
BENCH traço 600 pontos docs=32x32 (fallback Kotlin): 0.196 ms
BENCH traço 600 pontos docs=128x128 (fallback Kotlin): 0.236 ms
BENCH traço 600 pontos docs=512x512 (fallback Kotlin): 0.455 ms
BENCH traço 600 pontos docs=1024x1024 (fallback Kotlin): 0.198 ms
BENCH recolha REGIONAL 1024x8 (novo caminho): 0.290 ms
BENCH cópia INTEGRAL 1024x1024 (caminho antigo afterEditFull): 2.316 ms
BENCH undo de 685 deltas (512x512): 0.041 ms
BENCH balde 512x512 região total (fallback Kotlin): 40.870 ms
```

Leituras:
- O custo do traço é **independente do tamanho do documento** (0,2–0,45 ms
  de 32² a 1024²) — o requisito central "sem trabalho proporcional ao
  tamanho total da imagem em cada evento" está cumprido por construção
  (lote único + deltas esparsos + região suja).
- A recolha regional é ~8× mais barata que a cópia integral no mesmo
  documento de 1024²; no caminho antigo essa cópia acontecia no fim de
  CADA traço (e o bitmap era reconstruído por completo).
- O balde de região grande (40,9 ms em 512²) confirma por que o fill corre
  agora no executor serial, nunca na main thread.
- Comandos usados: `./gradlew :app:testReleaseUnitTest
  --tests "com.pixelone.studio.pixel.StrokeBenchmarkTest"`; saída em
  `app/build/test-results/.../TEST-...StrokeBenchmarkTest.xml` (system-out).

## G. Estado do GitHub Actions (execuções reais)

| Run | Motivo | Release | Instrumented (emulador API 30) |
| --- | --- | --- | --- |
| #39422265035 | 1.º dispatch | **SUCCESS** (APK assinado validado) | FAILURE — compilação de teste (import em falta) → corrigido `f3dfe19` |
| #39423069843 | 2.ª | **SUCCESS** | FAILURE — `testInstrumentationRunner` ausente ⇒ «Starting 0 tests» → corrigido `9f67c19` |
| #39425158869 | 3.ª | **SUCCESS** | FAILURE — 14 testes descobertos, 2 falhas (sinal no probe; lote de pontos do teste) → corrigidos `b05aa7a` |
| #39426602644 | 4.ª | **SUCCESS** | FAILURE — 13/14 (conversão x/y no teste) → corrigido `12a8945` |
| **#39428158144** | final | **SUCCESS** — `testReleaseUnitTest` verdes + `assembleRelease` + apksigner `Verifies` (v2) | **SUCCESS — 14/14 testes instrumentados** (`Starting 14 tests … Finished 14 tests`, zero falhas) |

O pipeline de release (secrets, assinatura, apksigner, artefacto) **não
foi alterado**; o job de testes instrumentados é independente — a sua
falha não bloqueia o APK, e todas as iterações ficaram registadas.

## H. Commit SHA e link da branch

- Branch: <https://github.com/criandojogodenovo-sketch/Pixelone/tree/fix/texture-studio-performance>
- Commits (ordem): `db07f1e` documento/buffer → `e6fbc35` motor nativo →
  `9bd0c02` histórico esparso → `705ca4c` editor (Problemas 1+2) →
  `4dfd1cd` UX (Problema 3) → `c68c41c` testes instrumentados →
  `e574756` CI+docs → `f3dfe19` import do teste → `9f67c19` runner
  instrumentado → `b05aa7a` probe sem sinal + lote do teste →
  `12a8945` conversão x/y do teste.
- `develop` e `main` NÃO foram alterados (integração via merge/PR fica
  como decisão do proprietário).

## I. APK Release assinado — link e SHA-256

- **Execução final**: run #39428158144 (branch
  `fix/texture-studio-performance`, commit `12a8945`), job *Compilar,
  assinar e validar APK release* → SUCCESS.
- Artefacto **`Pixel-One-Release-APK`** (download: página da run →
  Artifacts) com `PixelOne-0.3.0-performance-release.apk` (8 175 504
  bytes), `SHA256SUMS.txt` e `apksigner-report.txt`.
- **SHA-256 do APK** (confere entre o `SHA256SUMS.txt` do CI e a
  re-verificação local do ficheiro descarregado):

```
0f96d292d9c0ac159219abff82649169188b287422314d9d28123dc1cf92e30b  PixelOne-0.3.0-performance-release.apk
```

- Assinatura re-verificada localmente com o apksigner do build-tools 35:
  **Verifies — APK Signature Scheme v2: true**, certificado
  `CN=Pixel One Release, OU=Pixel One Studio, O=Pixel One, L=Luanda,
  ST=Luanda, C=AO` (a MESMA identidade dos releases anteriores — nenhuma
  chave nova foi criada).
- Segurança: secrets intocados; scan do repositório sem segredos; o
  keystore temporário é removido do runner pelo próprio workflow.

### Instalação num telemóvel (sem loja)
1. Descarregar o artefacto e transferir o `PixelOne-0.3.0-performance-release.apk`
   para o aparelho.
2. Abrir o ficheiro → autorizar «Instalar apps desconhecidas» quando
   pedido.
3. (Opcional, recomendado) verificar no aparelho:
   `adb install -r PixelOne-0.3.0-performance-release.apk`, ou comparar o
   SHA-256 com o valor acima antes de instalar.
4. O APK substitui a versão 0.2.0 mantendo os projetos `.osp` guardados
   (mesmo applicationId, assinatura idêntica, versionCode 3 > 2).

## J. Problemas pendentes

1. **Dispositivo físico**: nenhum disponível — a validação em hardware
   real (percentagem de frames, gestos reais, termal throttling) continua
   por fazer; os 14 testes instrumentados correm em emulador API 30 (CI,
   KVM) e os logs `PixelOnePerf/*` dão as medições em qualquer aparelho.
2. **Integração em `develop`**: a correção vive na branch
   `fix/texture-studio-performance`; recomenda-se merge após validação em
   dispositivo (o workflow corre automaticamente no push a `develop`).
3. **Preview vs. motor**: o preview desenha carimbos mesmo sobre píxeis
   que o motor depois ignora (já com a cor destino) — visualmente
   idêntico, mas é uma aproximação documentada.
4. **Tolerância do balde** continua a ser configurada por ciclos no botão
   (+8 até 64) — aceitável, mas um slider seria melhor UX (não bloqueante).
5. **Deprecação `pixelsView()`**: mantida como cópia para transição;
   remover quando nenhum chamador externo a use.

---

### Nota de execução (preenchida no fim da sessão)

Os valores das secções G/H/I foram preenchidos com os dados reais da
execução final #39428158144 (lidos da API do GitHub) e re-verificação
local do artefacto (SHA-256 e apksigner).
