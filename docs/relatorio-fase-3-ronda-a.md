# Relatório — Fase 3 · Ronda A (correções A–J + seleção de face/ilha)

Branch: `fix/fase3-ronda-a-selecao-uv` · Commits: `ec3f3a9` + `9316272` (relatório) + ronda de REVERIFICAÇÃO independente · Data: 2026-10-21
Âmbito: corrige a Fase 3 (3D Texture Studio). NÃO adiciona pintura no 3D, camadas nem ferramentas novas. Fases 1–2 intocadas (zero diffs fora do `studio3d`/`ui.texture3d`).

> **REVERIFICAÇÃO INDEPENDENTE (mesma sessão, auditoria do zero):** todo o trabalho abaixo foi re-auditado código a código (geometria verificada por produto vetorial, base da câmara do picking confrontada com `Mat4.lookAt`, formatos de realce confrontados com o consumidor) e RE-EXECUTADO do zero numa máquina limpa (SDK 35 + NDK 27.0.12077973 + JDK 17): 225 testes unitários ✓, `assembleDebugAndroidTest` ✓, `assembleRelease` ✓. A reverificação detetou **uma lacuna real no requisito A** (padding de navegação anunciado mas não aplicado na barra inferior) — corrigida neste commit.

---

## 1. Problemas A–J: causa encontrada → correção → verificação

### A. Barra de estado sobre a hora/ícones do sistema
- **Causa:** `MainActivity` usa `enableEdgeToEdge` (barras transparentes) e o `Texture3DStudioScreen` constrói a barra superior com um `Column` puro **sem insets**. As Fases 1–2 usam `Scaffold`/`TopAppBar`, que já respeitam insets — o problema era exclusivo do estúdio 3D.
- **Correção:** `statusBarsPadding()` dentro da barra superior e `navigationBarsPadding()` na barra de navegação inferior (`Studio3DBottomNav`, em `Studio3DPanels.kt`). A cor das barras estende-se por baixo das system bars (edge-to-edge preservado); o conteúdo começa abaixo da hora/ícones e a navegação inferior fica ACIMA da barra de gestos.
- **REVERIFICAÇÃO:** a versão anterior deste commit anunciava o `navigationBarsPadding` num comentário mas NÃO o aplicava — a barra inferior ficava parcialmente sob a barra de gestos. Corrigido: o padding está agora na `Row` de `Studio3DBottomNav` (único ecrã que usa a função; Fases 1–2 intocadas).
- **Verificação:** compilação + revisão de código. Não validado em dispositivo físico (sem dispositivo no ambiente) — o padrão `statusBarsPadding`/`navigationBarsPadding` é o mecanismo oficial para este caso.

### B. Cubo de demonstração incompleto (peças soltas, sem fundo)
- **Causa (2 bugs reais, ambos reproduzidos por cálculo/teste):**
  1. `DemoGeometry.cubeRenderable` usava **origens por face erradas** — cada face somava `origin + normal×0.5` com origins tipo `(0.5, 0, 0.5)`, duplicando o offset. Resultado: 6 quads de 1×1 DESLIGADOS (face +X em x=1 com y∈[−0.5,0.5]; topo em y=1.5; fundo em y=−0.5; face −Z em z=0, dentro do cubo). Não era um cubo — eram peças soltas.
  2. O chão tinha índices `(0,2,1)(0,3,2)` → normal geométrica **−Y** (para baixo); com `GL_CULL_FACE(BACK)` o chão era cortado visto de cima — o "sem fundo" do APK.
- **Correção:** todas as faces partem do centro do cubo `(0, 0.5, 0)` e são empurradas meio-troço pelo normal (cubo fechado: x∈[−0.5,0.5], y∈[0,1], z∈[−0.5,0.5]); winding CCW visto de fora verificado por produto vetorial nas 12 faces; chão com índices `(0,1,2)(0,2,3)` → normal +Y. Culling GL_BACK + depth test ficam corretos — cubo sólido de qualquer ângulo.
- **Verificação:** testes `cubo é fechado...` (planos + arestas coincidentes par a par), `winding CCW visto de fora...`, `caixa envolvente cobre...` (geometria dentro dos limites declarados). 100% automatizado em JVM.

### C. Textura não aparece (cubo "castanho liso")
- **Causa:** `ModelRenderer.onSurfaceCreated` recriava a cena mas NÃO a textura do documento: `ensureDocumentTexture` fazia early-return quando `documentTexture != 0 && dimensões iguais` — após recriação do contexto EGL (app em 2.º plano/rotação, comum em aparelhos de 4 GB) o id guardado ficava **stale** e a textura amostrada era inválida (cor plana/undefined em vez do desenho). Em primeira abertura, a geometria/UV partidas (B/D) impediam de qualquer forma ver as regiões corretas.
- **Correção:** em `onSurfaceCreated` os ids GL mortos (`documentTexture`, `highlightIbo`) são repostos a 0 ANTES de recriar (apagar ids de contexto morto seria operar sobre ids inválidos); a textura é recriada de `lastDocumentPixels` e a cena de `lastSceneRequest`; o IBO de realce é reconstruído. Com D corrigido, cada face amostra a sua região da textura.
- **Verificação:** revisão de código + compilação. Comportamento em contexto GL não é reproduzível em JVM (Robolectric não cobre GLES) — não validado em dispositivo; a causa era inequívoca no código (early-return com id de contexto anterior).

### D. Layout UV sobreposto (cubo desaparecia na vista UV)
- **Causa:** todas as 6 faces do cubo E o chão usavam UV 0..1 (a textura inteira) — as 7 ilhas sobrepunham-se a [0,1]² e a soldadura por UV fundia os 24 vértices em 4 pontos.
- **Correção:** layout novo em `DemoGeometry`: cubo numa **grelha 3×2** (células de 1/3 × 1/3, margem 0.015 entre ilhas) e chão na **faixa inferior** (v de 2/3 a 1, largura total). Cada face = 1 ilha com caixa própria; na vista UV o cubo aparece como 6 ilhas separadas e o chão como a 7.ª.
- **Verificação:** teste `layout UV tem 7 ilhas separadas sem sobreposição` (6+1 ilhas, 2 triângulos cada, caixas disjuntas, dentro de [0,1]²). Automatizado.

### E. Material "Sem textura" do Chão sem efeito visível
- **Causa:** o pipeline de bindings funcionava, mas a cor sólida do chão era `(0.18, 0.18, 0.18)` = `0x2E2E2E` = **exatamente a célula clara do xadrez** da textura demo. Trocar entre "Textura do projeto"/"Sem textura" produzia visualmente a MESMA cor. Antes de D, tudo amostrava a mesma textura escura, agravando a indistinguibilidade.
- **Correção:** cor sólida do chão passou a `(0.35, 0.35, 0.35)` — distinta de ambas as células (0x2E/0x1A). Com o layout D, o chão texturizado mostra a faixa inferior da textura (xadrez) e "Sem textura" mostra o cinza médio: mudança visível e imediata (`bindMaterial` → `setScene` já reage no mesmo frame de comandos GL).
- **Verificação:** teste atualizado (`demo tem dois materiais...` exige cor distinta > 0.25). Efeito na vista 3D: revisão de código; não validado em dispositivo.

### F. Linhas UV grossas e opacas
- **Causa (3 bugs):**
  1. `uvPaint.strokeWidth = 1f` era aplicado DENTRO de `canvas.scale(scale)` — a 64× de zoom as linhas tinham 64 px.
  2. Anti-alias ligado + arestas partilhadas desenhadas 2× (cada triângulo desenha as suas 3 linhas) escureciam as linhas.
  3. `SplitMode` ignorava `showUvOverlay` — passava segmentos sempre, mesmo com o interruptor OFF.
- **Correção:** `strokeWidth = 1f/scale` (1 px DE ECRÃ em qualquer zoom), `isAntiAlias = false`, alpha 140; modo Dividido agora respeita o interruptor (o interruptor existe também no painel do modo Dividido); cache do path por `contentHashCode` (antes por tamanho — mudar de malha com o mesmo nº de segmentos mantinha o path antigo).
- **Verificação:** revisão de código + compilação (desenho Canvas não é testável em JVM).

### G. Interruptor "Linhas UV" duplicado e etiqueta "Ilha" sobre a textura
- **Causa:** o `UvMode` tinha um overlay com o interruptor no canto superior ESQUERDO **e** o `PanelUv` tinha outro; a etiqueta "Ilha #…/limpar" era um overlay no canto superior DIREITO, por cima da textura.
- **Correção:** overlays removidos. Um só interruptor, no painel inferior (e no painel do modo Dividido — um por ecrã, estado partilhado). A informação da ilha, "Focar" e "Limpar" vivem no painel inferior, com alvos de 48 dp.
- **Verificação:** revisão de código; compilação.

### H. "12 tri." na etiqueta mas só 2 triângulos desenhados
- **Causa (2 bugs):**
  1. Com as UVs sobrepostas, a soldadura fundia as 6 faces numa ÚNICA ilha de 12 triângulos cuja representação na vista UV colapsava num único quad (2 triângulos visíveis).
  2. O path de preenchimento (`buildPath` com `closed=true`) consumia `i += 6` floats por passo num array de segmentos de 4 floats — lia a meio do triângulo e gerava `a→b→b` (área zero): o FILL era invisível.
- **Correção:** (1) resolvido pelo layout D (1 ilha por face = 2 triângulos: etiqueta e desenho coincidem). (2) `islandHighlightSegments()` passou a emitir **triplos de vértice** (ax,ay,bx,by,cx,cy — 6 floats/triângulo) e o `TexturePaintView.buildIslandPath` desenha `moveTo(a)→lineTo(b)→lineTo(c)→close`, um path único para FILL e STROKE.
- **Verificação:** testes `contagem de triângulos da ilha coincide com os desenhados` e correspondência 3D↔UV. Automatizado.

### I. Texto de depuração no painel e textos cortados
- **Causa:** cabeçalho do `BottomPanel` com a string "Painel — Textura | Amostragem | Info"; painel de 140 dp sem rolagem; chips "Textura do projeto" numa `Row` sem scroll; descrição da Amostragem longa.
- **Correção:** texto de depuração removido (linha de recolha só com a seta); conteúdo dos painéis com `verticalScroll`, chips com `horizontalScroll`, descrição encurtada ("Nearest: nítida (pixel art) · Linear: suave"); painel 150 dp.
- **Verificação:** compilação + revisão.

### J. Proporções do modo Dividido e Desfazer/Refazer
- **Causa:** a área principal ocupava `fillMaxHeight(0.58f)` dividida ao meio → 3D ≈ 29% e textura ≈ 29% do ecrã. Undo/redo estavam na MESMA `Row` que a fila de pincéis (em ecrãs estreitos saíam do ecrã). Nota de ajuda longa (2 linhas).
- **Correção:** a área principal é agora `weight(1f)` (todo o espaço restante) com 3D e textura a `weight(1f)` cada (≈40% do ecrã cada em telemóveis típicos). Desfazer/Refazer/Balde numa fila FIXA (primeira linha), pincéis numa fila rolável separada (segunda linha) — SEMPRE visíveis, alvos 48 dp. Dica curta: "1 dedo pinta · 2 dedos navegam · toque no 3D seleciona a face".
- **Verificação:** compilação + revisão (proporções exatas dependem do aparelho).

---

## 2. Nova funcionalidade: seleção de face/ilha

**O que funciona (nada simulado):**
- **Toque no modelo 3D** (viewport GL): toque curto (sem arrastar, ≤350 ms) converte-se em NDC → raio com a MESMA câmara/projeção do render (`MeshPicking.rayFromNdc`) → Möller–Trumbore contra todos os triângulos (dupla face, hit mais próximo) → seleciona a **face** (triângulo tocado) e a **ilha UV que a contém**. Toque fora do modelo limpa a seleção.
- **Toque na vista UV**: `UvIslands.hitTest` (ponto-em-triângulo com tolerância de borda) seleciona a ilha tocada; arrastar faz pan (toque vs. arrasto decidido pelo touchSlop — antes, todo o início de pan selecionava).
- **Realce laranja**: no 3D (2.º passe com `glPolygonOffset`) e na vista UV (fill + contorno) — em simultâneo nos dois painéis do modo Dividido.
- **Botão "Focar"**: a vista UV faz zoom fit à caixa da ilha (10% de respiro); com zoom ≥ 6× desenha-se uma **grelha de píxeis** 1 px alinhada à textura.
- **Botão "Limpar seleção"**: limpa em todos os painéis (48 dp).
- **A seleção NÃO altera a textura** — só escolhe a parte (conforme pedido).

**Decisão de desenho documentada:** para modelos importados genéricos não é fiável reconstruir "a face" (quad) a partir de triângulos; a unidade de realce no 3D é a **ilha UV** (que contém a face tocada). No cubo demo, ilha == face (1 ilha por face), pelo que o requisito "a face selecionada fica destacada a laranja no 3D" cumpre-se integralmente; o triângulo exato tocado fica registado na seleção (`faceTriangle`) para a ronda seguinte.

**O que NÃO funciona / não verificado:** interação por toque real em dispositivo (sem dispositivo no ambiente); picking em modelos importados não foi testado contra GLBs reais (o algoritmo é genérico e testado contra a geometria demo).

---

## 3. Ficheiros alterados (caminhos completos)

| Ficheiro | Alteração |
|---|---|
| `app/src/main/java/com/pixelone/studio/studio3d/DemoGeometry.kt` | Cubo fechado, winding, layout UV 3×2 + faixa do chão, cor do chão, constantes públicas de layout |
| `app/src/main/java/com/pixelone/studio/studio3d/MeshPicking.kt` | **NOVO** — raycast NDC→mundo + Möller–Trumbore (Kotlin puro) |
| `app/src/main/java/com/pixelone/studio/studio3d/Studio3DViewModel.kt` | `selectFaceAtNdc`, `IslandSelection(faceTriangle)`, `selectedIslandUvBounds`, realce por ilha, triplos no realce UV |
| `app/src/main/java/com/pixelone/studio/studio3d/gl/ModelRenderer.kt` | Reposição de ids GL stale em `onSurfaceCreated` + reconstrução do IBO de realce |
| `app/src/main/java/com/pixelone/studio/ui/texture3d/TexturePaintView.kt` | Linhas 1 px/AA off, paths por triplo, grelha de píxeis, `focusOnIsland`, tap-vs-arrasto |
| `app/src/main/java/com/pixelone/studio/ui/texture3d/Studio3DPanels.kt` | Overlays removidos, Focar/Limpar, Dividido 50/50 com seleção nos 2 painéis, fila fixa de undo/redo, textos; **reverificação:** `navigationBarsPadding()` aplicado de facto em `Studio3DBottomNav` (A) |
| `app/src/main/java/com/pixelone/studio/ui/texture3d/Texture3DStudioScreen.kt` | Insets (A), área principal `weight(1f)`, toque→raycast, host da vista UV |
| `app/src/test/java/com/pixelone/studio/studio3d/DemoGeometryTest.kt` | +4 testes (fecho, winding, layout UV, contagem, correspondência; 12 no total) |
| `app/src/test/java/com/pixelone/studio/studio3d/MeshPickingTest.kt` | **NOVO** — 8 testes de picking |

---

## 4. Testes executados (resultados REAIS — re-executados na reverificação)

- **Suite unitária completa (`testDebugUnitTest`): 225 testes, 0 falhas, 0 erros, 0 ignorados** — CONFIRMADA duas vezes: na ronda original e na reverificação independente (máquina limpa, JDK 17 + SDK 35 + NDK 27.0.12077973), a partir do working tree corrigido.
  - `DemoGeometryTest` 12 ✓ (fecho do cubo, winding, 7 ilhas sem sobreposição, contagem=desenho, correspondência UV)
  - `MeshPickingTest` 8 ✓ (raio NDC, toque no centro/acima, 5 faces por raio reto, ilha do chão, correspondência 3D↔UV, Möller–Trumbore unidade, hit mais próximo)
  - `UvIslandsTest` 8 ✓ (regressão) · `GltfParserTest` 22 ✓ · `OspCodecStudio3DTest` 8 ✓ (projetos v1/v2 abrem) · restantes suítes 2D/export/persistência ✓ (24 suítes no total)
- **Testes instrumentados (`connectedDebugAndroidTest`): NÃO executados** — não há emulador/dispositivo neste ambiente. Os fontes de `androidTest` COMPILAM (`assembleDebugAndroidTest` BUILD SUCCESSFUL — confirmado na reverificação). O CI do projeto (emulador API 30) é quem os pode executar.
- Interruptor de linhas UV: verificado por revisão de código (SplitMode e UvMode respeitam `showUvOverlay`) — não automatizável em JVM (Canvas/View não têm fallback JVM).

## 5. Compilação Release e assinatura

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** — confirmado na reverificação (máquina limpa; inclui build nativo C++17 nas 4 ABIs com NDK 27.0.12077973 e `lintVitalRelease` sem erros).
- Artefacto pós-reverificação: `app/build/outputs/apk/release/app-release-unsigned.apk` (8 314 772 bytes; SHA-256 `d3d641deb36b3f07f39e0a58c940320897cd7965523a3c3d43ccd799303a81cf`; versionCode 6, versionName 0.4.0-fase-3d). O SHA difere da ronda original (`7fe955…`) porque inclui a correção de insets da barra inferior — esperado.
- **Assinatura: o APK local NÃO está assinado** (`apksigner verify` → DOES NOT VERIFY — confirmado na reverificação). É o comportamento documentado do projeto: a assinatura usa exclusivamente os secrets do GitHub Actions e o workflow só corre em `develop`/manual. A configuração de assinatura em `build.gradle.kts` NÃO foi alterada (nem versionCode) — a assinatura da versão anterior mantém-se; nenhuma chave nova foi criada; nenhum segredo comitado.
- **Não há link de APK publicado** — esta ronda não publica nada por decisão honesta: só o CI com os secrets pode gerar o APK assinado.

## 6. Branch e commits

- Branch: `fix/fase3-ronda-a-selecao-uv` (a partir de `develop` @ `18f6d1c`)
- Commit: `ec3f3a9` — fix(studio3d)+feat(seleção): Ronda A — correções A–J e picking de face/ilha
- Commit: `9316272` — docs: relatório da Ronda A
- Commit da reverificação: correção do requisito A (insets na barra inferior) + este relatório atualizado

## 7. Limitações e problemas que ainda falham

1. **Sem validação em dispositivo físico/emulador** — insets reais, gestos, GL e o fluxo de contexto perdido (C) só foram verificados por revisão de código, testes JVM e compilação. Recomenda-se instalar o APK de CI num aparelho antes de considerar a ronda fechada.
2. **Testes instrumentados não executados** nesta ronda (sem emulador); o CI do projeto continua a ser a via oficial.
3. O **picking com modelos importados** (GLB reais) não foi testado contra ficheiros reais — o algoritmo é genérico e a geometria demo cobre os casos testados.
4. **Pintura na vista UV** (modo UV selecionar ilha e pintar-lhe por cima) continua a ser por pixel do documento — a ronda NÃO adiciona pintura limitada à ilha (fica para a ronda seguinte, como pedido).
5. **Alvos de toque < 48 dp PRÉ-EXISTENTES da Fase 3** (fora do âmbito A–J, não tocados): os chips do seletor de modo (36 dp) e o chip "Repor câmara" (~36 dp de altura). Tudo o que a ronda criou/toque cumpre 48 dp (Focar 64×48, Limpar 76×48, undo/redo 48, pincéis 48, barra superior 48).
6. O Snackbar mantém o padding fixo de 200 dp herdado da Fase 3 (cosmético; não fazia parte de A–J).
7. O 2D→3D Studio continua "FASE 0 - PLANEADO" (fora do âmbito).
