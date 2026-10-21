# Relatório — Fase 3 · Ronda B (pintura real no 3D, máscara por ilha, camadas, ferramentas)

Branch: `feature/fase3-ronda-b-pintura3d` (a partir de `fix/fase3-ronda-a-selecao-uv` @ `a861981`) · Data: 2026-10-21
Âmbito: adiciona PINTURA REAL no modelo 3D e na UV com máscara por ilha, camadas, ferramentas, simetria, padding, exportações e .osp v3. Fases 1–2 e a Ronda A não foram alteradas (difer apenas nos ficheiros listados abaixo).

> **Estado da Ronda A encontrado:** auditado do zero nesta ronda — branch NÃO mesclada na `develop` (vive em `fix/fase3-ronda-a-selecao-uv`). Código revisto ficheiro a ficheiro (insets, geometria fechada do cubo, layout UV 3×2+chão, seleção por raycast, linhas UV 1 px, painéis) e **225 testes unitários existentes re-executados: 225/225 passam**. Nada da Ronda A precisou de correção nesta ronda. A Ronda B partiu do topo dessa branch.

---

## 1. Para cada ponto das secções 2–6: estado real

### Secção 2 — Pintar diretamente no 3D
| Requisito | Estado | Notas |
| --- | --- | --- |
| Tocar no modelo pinta o texel via raycast+UV | **FUNCIONA** | `Studio3DViewModel.paintAtNdc` → `MeshPicking` (Möller–Trumbore, cache do último triângulo) → `PaintCore.uvAtHit` (baricêntricas por produtos internos) → `PaintCore.uvToTexel` → funil de pintura. Testado em JVM ponta-a-ponta (`RondaBPaintPipelineTest`). |
| Pincel de 1 píxel exato por defeito | **FUNCIONA** | `PaintState.brush = 1` default; carimbo de tamanho 1 = texel exato. |
| 1 dedo pinta, 2 dedos navegam | **FUNCIONA** | `Studio3DGlViewport`: 1 dedo = raycast+traço; 2 dedos = rotação/zoom/pan (pinça) — nunca pinta com 2 dedos. |
| Botão Pintar / Navegar | **FUNCIONA** | Sempre visível na barra de pintura; em "Navegar" o toque roda e o toque curto seleciona (Ronda A intacta). |
| Traços contínuos sem falhas | **FUNCIONA** | Interpolação Bresenham entre sucessivos pontos de impacto (`drawLineSegment`), na UV e no 3D. |
| Pintar na UV == pintar no 3D, ao vivo | **FUNCIONA** | Mesmo funil (`paintBegin/Move/End`); a vista UV pinta quando "Pintar" está ativo; ambas atualizam ao vivo (`paintVersion` + `glTexSubImage2D`). |
| Atualização GPU PARCIAL | **FUNCIONA** | `applyEdit` → `composeRegion` (só a região suja) → `updateTextureRegion` (`glTexSubImage2D` do retângulo). Teste: traço de 1 texel produz região 1×1. Recomposição TOTAL só em operações estruturais (camadas/abrir/visibilidade). |
| Resposta < 100 ms | **PARCIAL (sem dispositivo)** | O caminho de pintura é medido com `PerfLog` (traço down em µs — típicos: centenas de µs em JVM; no C++ nativo o motor mede `std::chrono`). **Não medido em dispositivo físico** — o executor de mutação é serial e o custo por traço é proporcional à área do traço. |

### Secção 3 — Máscara por ilha
| Requisito | Estado | Notas |
| --- | --- | --- |
| Pintura só afeta texels dentro da ilha selecionada | **FUNCIONA** | `IslandMask` (rasterização por centro de texel + dilatação) aplicada em stamp/linha/retângulo/balde. Teste: traço atravessando a textura muda 0 texels fora da máscara. |
| Interruptor "Bloquear na ilha" (ligado por defeito com seleção) | **FUNCIONA** | `lockToIsland` default `true`; sem seleção a pintura afeta qualquer ilha (máscara efetiva = null). |
| Destaque da ilha visível sem atrapalhar | **FUNCIONA** | Realce laranja 3D (IBO próprio) + fill/stroke na UV mantidos durante a pintura. |

### Secção 4 — Ferramentas
| Requisito | Estado | Notas |
| --- | --- | --- |
| Lápis, borracha | **FUNCIONA** | Borracha = ARGB 0x00000000 (transparência real, convenção do projeto). |
| Balde (só dentro da ilha, se houver seleção) | **FUNCIONA** | `floodFillMasked` BFS 4-conectado; toque fora da máscara não faz NADA (testado). Sem seleção = balde normal. |
| Conta-gotas no 3D e na UV | **FUNCIONA** | Lê a COMPOSIÇÃO (o que o utilizador vê), via raio 3D ou toque UV; cor escolhida entra nas recentes. |
| Linha | **FUNCIONA** | Bresenham do ponto inicial ao final; commit no fim do gesto (1 entrada de histórico). **Sem pré-visualização ao vivo** (limitação). |
| Retângulo | **FUNCIONA** | Contorno (4 lados por Bresenham); interior preservado (testado). **Sem pré-visualização ao vivo** (limitação). |
| Tamanhos 1, 2, 3, 4, 8 px | **FUNCIONA** | `BRUSH_SIZES` na UI; `PaintCore.MAX_BRUSH=8` coerente com o motor 2D. |
| Simetria H/V opcional com indicação da base | **FUNCIONA** | Off/H/V/H+V; base ISLAND (caixa da máscara) com seleção, TEXTURA sem — a UI mostra "Espelho: ilha/textura". Balde NÃO espelha (decisão documentada). |
| Seletor de cor com paleta, recentes e hex | **FUNCIONA** | `ColorPickerDialog` existente (sliders RGBA + hex) + fila de recentes (12) persistida na paleta do manifesto `.osp`. |
| Desfazer/Refazer partilhado, sempre visíveis, com limite p/ 4 GB | **FUNCIONA** | `LayeredHistory` global (3D/UV/camadas), deltas esparsos, orçamento 32 MB com evicção das mais antigas; botões fixos na barra. Testado (ordem entre camadas, redo limpo, evicção). |

### Secção 5 — Camadas
| Requisito | Estado | Notas |
| --- | --- | --- |
| ≥ 2 camadas, visibilidade, opacidade, reordenação | **FUNCIONA** | `LayerStack` (máx. 4): adicionar/remover/ativar/visibilidade/opacidade ±10/subir/descer no painel de camadas. |
| 3D mostra a composição das camadas visíveis | **FUNCIONA** | `compose()`/`composeRegion` source-over com alfa RETO (nunca premultiplicado) → composição é a textura da GPU, da vista UV e do 2D. |
| Limites com erro recuperável | **FUNCIONA** | Máx. 4 camadas e máx. 2×MAX_PIXELS somados; `LayerLimitException` → mensagem clara, nada corrompido (testado para o limite de nº de camadas). |
| Guardar camadas no .osp com versionamento | **FUNCIONA** | `.osp` **v3**: `pixels.rgba` = composição (compatível com apps antigas), `layers.json` (nome/visível/opacidade/ativa) + `layer-N.rgba` (baixo→topo). Roundtrip exato testado. |
| Projetos antigos abrem como 1 camada | **FUNCIONA** | v1/v2 (sem `layers.json`) → 1 camada "Base" (testado). Secção de camadas incoerente → erro controlado ("Camada ignorada… aberto como 1 camada"). |

### Secção 6 — Qualidade e visualização
| Requisito | Estado | Notas |
| --- | --- | --- |
| Margem (padding/dilatação) à volta das ilhas | **FUNCIONA** | Padding 0–8 px (default 2) por dilatação separável Chebyshev; controlo "Margem" no painel. Testado (cresce e continua confinada). |
| Modo "sem luz" (unlit) | **FUNCIONA (por desenho)** | O renderer do projeto é unlit desde a Fase 3 (fragment sem iluminação — cor real da textura). Não há modo "com luz" nesta fase. |
| Interruptor de wireframe | **FUNCIONA** | `setWireframe` no `ModelRenderer`: arestas laranja (gl.LINES, IBO lazy por primitiva, depth LEQUAL). Não validado em GPU real (sem dispositivo). |
| Textura de teste xadrez | **FUNCIONA** | "Xadrez de teste" preenche a camada ATIVA (1 entrada de histórico, desfazível); limitado a ≤512² com mensagem clara. |
| Grelha de píxeis na UV com zoom | **FUNCIONA** | Da Ronda A (`GRID_MIN_SCALE`), mantida. |
| Exportar textura PNG | **FUNCIONA** | Menu → SAF CreateDocument → PNG da composição (alfa reto). |
| Exportar gabarito UV PNG | **FUNCIONA** | Menu → PNG com linhas UV 1 px sobre fundo transparente. |

### Secção 7 — Layout
- Insets de status/navigation respeitados (Ronda A, mantidos); barra de ferramentas compacta (48 dp por alvo, ícones próprios `PixelIcons` + 2 novos: linha/retângulo, camadas, espelho); painel inferior recolhível (camadas + paleta + opções dentro dele); **nada cobre o modelo** (toolbar é uma linha fixa, não overlay); Desfazer/Refazer + Pintar/Navegar sempre visíveis; tema preto/cinza/laranja mantido.
- **Nota honesta sobre "40%/40%"**: com o painel inferior EXPANDIDO, os painéis 3D/textura têm menos que 40% do ecrã em aparelhos típicos (a barra de ferramentas acrescenta ~52 dp ao chrome da Ronda A). Com o painel RECOLHIDO, 3D e textura dividem a área principal ao meio (weights 1:1, como na Ronda A).

## 2. Regras
- **Nada simulado**: o funil de pintura (raycast→UV→texel→máscara→camada→composição→GPU) é o mesmo para demo e para GLB importado (ilhas por primitiva via `UvIslands.compute`); testes cobrem o pipeline com o modelo demo real. O comportamento em GLB importado segue o mesmo código sem atalhos — mas **não foi executado num dispositivo físico** nesta sessão.
- Sem dependências novas (só Compose/AndroidX já existentes; os ícones novos são vetores próprios).
- Workflow Android preservado; **assinatura untouched** (secrets CI); nenhum segredo no repositório (o token foi usado só na URL de clone e o remote foi reposto para URL sem token).
- .osp v3 rejeita versões futuras do formato com erro controlado (mantido).

## 3. Ficheiros alterados/criados (caminhos)
**Núcleo (novo):**
- `app/src/main/java/com/pixelone/studio/pixel/LayerStack.kt` — camadas + composição source-over alfa reto + limites.
- `app/src/main/java/com/pixelone/studio/pixel/LayeredHistory.kt` — histórico global entre camadas (deltas esparsos, 32 MB).
- `app/src/main/java/com/pixelone/studio/studio3d/PaintCore.kt` — UV→texel, uvAtHit (baricêntricas), carimbo com máscara+simetria, linha/retângulo, balde mascarado.
- `app/src/main/java/com/pixelone/studio/studio3d/IslandMask.kt` — rasterização de ilha + padding/dilatação.
- `app/src/main/java/com/pixelone/studio/osp/LayersCodec.kt` — layers.json (v3).

**Alterados:**
- `app/src/main/java/com/pixelone/studio/osp/OspCodec.kt` — v3: `layers.json` + `layer-N.rgba` (lista branca, limites, coerência).
- `app/src/main/java/com/pixelone/studio/osp/FileSystemProjectRepository.kt` — save/load com secção de camadas + roundtrip.
- `app/src/main/java/com/pixelone/studio/studio3d/Studio3DViewModel.kt` — REFEITO: pilha de camadas + composição, funil de pintura único (2D/3D), máscara com cache, simetria, ferramentas, camadas, .osp v3, exportações, `paintAtNdc`/`discreteAtNdc`, estado observável `paint`/`layers`.
- `app/src/main/java/com/pixelone/studio/studio3d/gl/ModelRenderer.kt` — wireframe opcional (IBO de linhas lazy, uniforms reutilizados).
- `app/src/main/java/com/pixelone/studio/ui/texture3d/Texture3DStudioScreen.kt` — barra de pintura sempre visível, gestos de pintura no viewport GL (Pintar/Navegar), menu com exportações.
- `app/src/main/java/com/pixelone/studio/ui/texture3d/Studio3DPanels.kt` — `PanelSplit` refeito (opções de pintura + camadas + paleta); `PanelUv` ganha opções de pintura; `Panel3DTexture` ganha wireframe/xadrez/unlit; `UvMode` alterna pintar/selecionar.
- `app/src/main/java/com/pixelone/studio/ui/texture3d/PaintPanels.kt` (novo) — `PaintToolBar`, `PaintOptionsRow`, `LayersPanel`, `PalettePanel`, `ViewOptionsRow`.
- `app/src/main/java/com/pixelone/studio/editor/PixelIcons.kt` — ícones `line`, `rect`, `layers`, `flip` (adição).
- `app/build.gradle.kts` — versionCode 6→7, versionName → `0.5.0-fase-3d-ronda-b`.
- `docs/formato-osp.md` — especificação v3 (camadas).

**Testes novos (6 ficheiros, 46 testes):**
- `app/src/test/java/com/pixelone/studio/studio3d/PaintCoreTest.kt`
- `app/src/test/java/com/pixelone/studio/studio3d/IslandMaskTest.kt`
- `app/src/test/java/com/pixelone/studio/studio3d/RondaBPaintPipelineTest.kt` (integração: toque→UV→texel→máscara→traço→composição→undo + auditoria da Ronda A)
- `app/src/test/java/com/pixelone/studio/pixel/LayerStackTest.kt`
- `app/src/test/java/com/pixelone/studio/pixel/LayeredHistoryTest.kt`
- `app/src/test/java/com/pixelone/studio/osp/OspLayersTest.kt`

## 4. Testes e resultados reais
- **Antes da Ronda B (baseline da Ronda A):** 225 testes unitários — 225 passam (executado nesta sessão, JDK 17 + Robolectric).
- **Depois da Ronda B:** **271 testes unitários — 271 passam, 0 falhas** (`:app:testDebugUnitTest`, executado).
- `:app:assembleDebug` — BUILD SUCCESSFUL (inclui build nativo C++17).
- `:app:assembleRelease` (clean) — BUILD SUCCESSFUL em 1m29s; APK `app-release-unsigned.apk` (8.4 MB) com `libpixelone_engine.so` nas 4 ABIs.
- **Assinatura:** local o APK release é POR ASSINAR (a config de assinatura só ativa com os secrets do CI — comportamento documentado do projeto); sanity-check local com `apksigner` (keystore de DEBUG, só para provar que o APK é assinável) — assina e verifica. **A assinatura real de release só acontece no CI** (`android-release.yml`, secrets `ANDROID_KEYSTORE_*`) — não executada aqui.
- **Testes em dispositivo físico/emulador: NÃO executados** (sem dispositivo no ambiente) — os testes instrumentados existentes continuam no CI.

## 5. Branch e commits
- Branch: `feature/fase3-ronda-b-pintura3d` (base: `fix/fase3-ronda-a-selecao-uv` @ a861981 — Ronda A, NÃO mesclada na develop).
- Commits: ver `git log` da branch (núcleo; persistência v3; ViewModel+renderer; UI; testes; docs/relatório).

## 6. Link do APK
- **Não publicado.** O APK assinado é publicado pelo workflow `android-release` no push para `develop` (com os secrets de assinatura do repositório). Esta ronda foi empurrada como BRANCH — sem merge, sem APK público.

## 7. Limitações e problemas que ainda falham
1. **Sem validação em dispositivo físico** — todos os caminhos GL/GLSurfaceView/gestos (pintura no 3D, wireframe, resposta < 100 ms) são válidos por código/testes JVM, não por execução real.
2. **Linha/retângulo sem pré-visualização ao vivo** — aplicados no fim do gesto (uma entrada de histórico). O desenho é correto; falta o preview durante o arrasto.
3. **Balde não aplica simetria** (espelho cobre lápis/borracha/linha/retângulo).
4. **Pintura com traço que salta entre ilhas**: a interpolação Bresenham é em espaço de texel; com máscara ativa nada pinta fora da ilha (correto), mas um traço que salte de uma face para outra desenha dois pontos separados sem "contorno 3D" entre elas (comportamento padrão de pintura por textura; documentado).
5. **Adoção do Texture Studio 2D**: se as dimensões coincidirem, os píxeis entram na CAMADA ATIVA (desfazível); se diferirem, a pilha é substituída por 1 camada (mensagem clara) — não há importação por camada.
6. **Sem reembutir o modelo no .osp** (referência apenas — mantido da Fase 3).
7. **Orçamento de camadas testado no limite de Nº de camadas**; o limite de píxeis somados (2×MAX_PIXELS) segue o mesmo caminho de código mas não foi exercitado com texturas grandes nos testes (custo de memória em JVM).
8. O histórico do editor 2D (Fases 1–2) continua separado — a partilha 3D↔2D continua por troca de composição via `StudioSharedBus`.
