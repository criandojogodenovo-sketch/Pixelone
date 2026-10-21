# Relatório — Missão: correção estrutural da interface e seleção de texturas

Branch: `fix/missao-ui-selecao-texturas` (base: `feature/fase3-ronda-b-pintura3d` @ 108968f — o estado mais completo do repositório, que já contém a Fase 3, a Ronda A e a Ronda B como ancestrais verificados com `git merge-base --is-ancestor`).

Data: 2026-10-21 · Commit principal: `169923f`

---

## 1. Diagnóstico (auditoria obrigatória — repositório real, sem invenções)

### Arquitetura real identificada

| Componente | Realidade verificada no código |
|---|---|
| UI | **Jetpack Compose (Material 3)** — 100% Compose, zero Views/XML; decisão registada no README e confirmada em todos os ecrãs |
| Nativo | **C++/JNI** (`app/src/main/cpp/pixelone_engine.cpp`, CMake 3.22.1, NDK 27) — motor 2D; o renderer 3D é **Kotlin** (`studio3d/gl/ModelRenderer.kt`, OpenGL ES 3.0 via `GLSurfaceView`) |
| Tema | Escuro **fixo** com laranja (`ui/theme/Theme.kt`, `Color.kt`) — preservado; nada foi alterado |
| Navegação | `navigation/PixelOneApp.kt` — 4 rotas: Home, Texture Studio (2D), 3D Texture Studio, 2D→3D |
| Cabeçalho | Home/2D/2D→3D: `Scaffold` + `TopAppBar` (insets por construção). 3D: barra própria (48 dp) com Voltar, nome do projeto, importar e **menu hambúrguer** (`Studio3DMenu` em `Texture3DStudioScreen.kt`) |
| Texturas | `Studio3DState.TextureRef { DOCUMENT, EMBEDDED, NONE }` + `bindings: Map<Int, TextureRef>` (material → referência), persistido em `studio3d.json` (.osp v2/v3) |
| Materiais/objetos/faces | `selectedMaterial` (chips), `selectedMesh` (filtro UV), `selectedIsland` (face/ilhas por raycast — Ronda A) |
| Testes existentes | 271 unitários JVM (JUnit4 + Robolectric) em 6 pacotes + 2 ficheiros instrumentados |

### O problema da FASE 3 — por que não era possível selecionar uma textura individual

Causa estrutural confirmada lendo o código (não é um bug pontual, é uma ausência):

1. **Texturas não eram objetos.** Existia apenas a *ligação* material→referência (`bindMaterial`). Não havia lista de texturas, identidade, nome, dimensões nem "quem usa quê". O painel "Textura" mostrava chips de REFERÊNCIA (Textura do projeto / Embutida / Sem textura) para o material selecionado — nunca uma textura selecionável.
2. **A pintura ia sempre à textura do projeto** (`LayerStack`/`compositeDoc`), independentemente da seleção. Consequência real: se dois materiais estavam ligados a `DOCUMENT`, pintar num deles alterava **ambos em silêncio** — exatamente a queixa "substituir silenciosamente outros materiais".
3. **Texturas embutidas do GLB eram invisíveis.** As vistas UV e Dividido mostravam sempre `presentationBitmap` (composição do projeto); uma textura embutida apresentada no modelo não podia ser vista em 2D, escolhida, amostrada com o conta-gotas ou copiada.
4. **Distinção de níveis incompleta:** material/malha/face existiam; **textura** não existia como nível de seleção.

### Problema real da FASE 2 descoberto na auditoria

- **Barra de pintura cortada em ecrãs estreitos:** `PaintToolBar` tinha ~530 dp de conteúdo fixo (toggle + 6 ferramentas + cor + desfazer/refazer, alvos de 48 dp) numa `Row` **sem rolagem** — num ecrã de 360 dp, **Desfazer/Refazer ficavam fora do ecrã**, violando o requisito "Desfazer/Refazer sempre visíveis" da Ronda B e a regra "nada inacessível" desta missão.
- Os insets estavam corretos desde a Ronda A (`statusBarsPadding` no topo, `navigationBarsPadding` na barra inferior, `Scaffold` nos restantes ecrãs) — **nada a corrigir**; procuradas, não existem alturas fixas usadas como truque de insets.

### FASE 4 — avaliação real

- Modos 3D / UV / Dividido: funcionais; UV e Dividido bloqueados com explicação quando o modelo não tem UV (`setMode` + `ModeChip(enabled = hasUv)`) — correto, preservado.
- **Preservados sem alterações:** Nearest/Linear (`Panel3DSampling`), Textura do projeto / Embutida / Sem textura (chips de binding), guardar .osp, exportações PNG.
- Incoerência encontrada: o modo Dividido ignorava o filtro de malha na sobreposição UV (`uvWireframeSegments()` sem filtro, ao contrário do modo UV). Corrigido.

---

## 2. Correções implementadas (verificáveis)

### FASE 3 — seleção de texturas (núcleo da missão)

**Solução mínima compatível** (sem reescrever a app, sem trocar o renderer, sem tocar em C++/JNI, sem quebrar o mecanismo de guardar — o .osp não mudou):

1. **Catálogo de texturas com identidade estável** (`studio3d/TextureCatalog.kt`, Kotlin puro):
   - `"document"` — a textura do projeto (nome, WxH, materiais que a usam);
   - `"embedded:<índice>"` — cada imagem embutida do GLB usada por ≥ 1 material;
   - ordem determinística, ids estáveis entre reconstruções, `usedByMaterials` explícito — **dois materiais com DOCUMENT aparecem abertamente na mesma textura** (fim do silêncio).
2. **Seleção de textura como nível próprio** (`Studio3DViewModel`): `selectedTexture`, `textureCatalog`, `selectTexture(id)` — distinta de material/objeto/face; sincronizada nos dois sentidos onde faz sentido (selecionar material EMBEDDED mostra a embutida; selecionar textura embutida seleciona o material que a usa). A seleção de face/ilhas (toque no 3D) **não** muda a textura selecionada — o alvo de pintura nunca salta por causa de uma navegação.
3. **Guarda de pintura — a pintura altera apenas o recurso selecionado:** `paintBegin`, `paintEnd` e `discreteAtNdc` recusam pintar com uma embutida selecionada, **com explicação clara** (nunca substituem em silêncio). Pintar continua a editar a textura do projeto (única editável — limitação documentada abaixo).
4. **Vistas UV e Dividido mostram a textura SELECIONADA** (`viewTexture`): composição do projeto OU a embutida (só leitura), com as mesmas linhas UV e realce de ilhas (o espaço UV é o mesmo).
5. **Cópia anunciada de embutida para o projeto** (`copyEmbeddedToDocument` + `PixelAdoption`): "Copiar para o projeto (para editar)" no painel — mesmas dimensões: funde na camada ativa como UMA entrada de histórico (desfazível); dimensões diferentes: substitui a pilha por 1 camada (mesma regra já usada pelo `adoptFromStudio2D`, sempre anunciada via status). O material selecionado é **rebindado explicitamente** para DOCUMENT (a edição fica visível no modelo; os chips mostram a mudança).
6. **Refactor sem mudança de comportamento:** o loop de fusão de `adoptFromStudio2D` foi extraído para `PixelAdoption.mergeIntoLayer` (pura, testável em JVM) e reutilizado pela cópia de embutidas.

### FASE 2 — interface

- **Correção do overflow da barra de pintura:** o segmento Pintar/Navegar + ferramentas + cor agora **rola horizontalmente**; **Desfazer/Refazer ficam FIXOS fora do segmento rolável** — sempre visíveis e acessíveis em qualquer largura (360 dp incluído). Nenhum alvo < 48 dp foi introduzido.
- Insets: verificados ecrã a ecrã (Home, Texture Studio 2D, 2D→3D, 3D) — corretos desde a Ronda A; tema escuro + laranja intactos; **nada alterado onde já estava certo**.
- Linha nova `PaintTargetLine` nos painéis UV e Dividido: diz sempre QUE textura está na vista/alvo da pintura ("A pintar em: …" / "A ver: … — pintura bloqueada") — trocas de recurso nunca são silenciosas.

### FASE 4 — editor móvel

- Modo Dividido passa a respeitar o filtro de malha na sobreposição UV (coerente com o modo UV).
- Todos os modos e presets existentes preservados e recompilados; nenhum botão simulado — cada controlo verificado até ao seu comportamento no ViewModel.

---

## 3. Ficheiros alterados

**Novos:**
- `app/src/main/java/com/pixelone/studio/studio3d/TextureCatalog.kt` — catálogo puro (identidade estável, propriedades, usado-por)
- `app/src/main/java/com/pixelone/studio/studio3d/PixelAdoption.kt` — fusão/substituição pura de píxeis externos
- `app/src/test/java/com/pixelone/studio/studio3d/TextureCatalogTest.kt` — 7 testes
- `app/src/test/java/com/pixelone/studio/studio3d/PixelAdoptionTest.kt` — 5 testes

**Modificados:**
- `app/src/main/java/com/pixelone/studio/studio3d/Studio3DViewModel.kt` — selectedTexture/textureCatalog/viewTexture, selectTexture, guarda de pintura, copyEmbeddedToDocument, sync material↔textura, refresh de catálogo em applyModel/newTexture/openProject/bindMaterial/adoptFromStudio2D
- `app/src/main/java/com/pixelone/studio/ui/texture3d/Studio3DPanels.kt` — chips "Texturas do modelo", botão "Copiar para o projeto", PaintTargetLine, vistas UV/Dividido sobre viewTexture, SplitMode com filtro de malha
- `app/src/main/java/com/pixelone/studio/ui/texture3d/PaintPanels.kt` — barra de pintura: segmento rolável + Desfazer/Refazer fixos

**Não tocados (deliberadamente):** C++/JNI, renderer GL, .osp (formato v3 intacto), tema, Rondas A/B, fases 1–2, MainActivity.

---

## 4. Testes — resultados REAIS (ambiente: JVM, JDK 17.0.20, Gradle 8.10.2, AGP auto-instala NDK 27)

| Verificação | Comando | Resultado |
|---|---|---|
| Suite unitária completa | `:app:testDebugUnitTest` | **283 testes, 0 falhas, 0 erros, 0 ignorados** (271 pré-existentes verdes + 12 novos) |
| Novos: catálogo | `TextureCatalogTest` | 7/7 ✓ (identidade estável, usado-por explícito, NONE não gera textura, embutida não usada não entra, ordem determinística, dimensões desconhecidas não bloqueiam, parse de ids) |
| Novos: adoção de píxeis | `PixelAdoptionTest` | 5/5 ✓ (deltas esparsos com bbox exata, idênticos→null, decisão de dimensões, ARGB exato, erro cedo em array inválido) |
| Compilação debug | `:app:assembleDebug` | ✓ BUILD SUCCESSFUL (inclui JNI 4 ABIs) |
| Compilação release | `:app:assembleRelease` | ✓ BUILD SUCCESSFUL — `app-release-unsigned.apk` (assinatura real só via secrets de CI, como nas rondas anteriores) |

**NÃO executado (honestidade):** testes instrumentados em dispositivo/emulador e teste manual de toques/orientação — este ambiente não tem dispositivo nem emulador. A validação de UI aqui é por leitura de código + compilação + testes JVM do núcleo.

---

## 5. Limitações e o que ficou deliberadamente por fazer

1. **Texturas embutidas continuam só de leitura.** Pintar diretamente numa embutida exigiria uma pilha de camadas + histórico + atualização parcial de GPU por textura embutida (formato .osp novo). Era reescrita disfarçada de correção — fora das restrições. O caminho real e anunciado é: ver → "Copiar para o projeto" → pintar (rebind explícito do material).
2. **Partilha da textura do projeto entre materiais DOCUMENT continua possível** — porque é o modelo de dados real (1 pilha de camadas por projeto). A diferença: agora é **explícita** (chip mostra "N material(is)"; a guarda e o PaintTargetLine anunciam o alvo). Isolamento por material = sistema de texturas por material (fase futura, com formato v4).
3. **Testes de instrumentação e de gesto em ecrã real não foram corridos** (sem dispositivo). Recomenda-se no dispositivo: hamburger menu, seleção de 2 texturas distintas, cópia de embutida, guardar/reabrir.
4. A seleção de textura não é persistida no .osp (estado de sessão) — a binding de materiais continua persistida como antes; nenhum formato mudou.

## 6. Como verificar rapidamente (no código/branch)

- `docs/relatorio-missao-ui-selecao-texturas.md` — este relatório.
- Painel 3D → aba "Textura" → secção nova **"Texturas do modelo"** (chips com nome, dimensões e nº de materiais).
- Importar um GLB com textura embutida → selecionar a embutida no catálogo → as vistas UV/Dividido mostram-na (só leitura, laranja "pintura bloqueada") → "Copiar para o projeto" → pintar → o modelo atualiza (rebind anunciado).
- Ecrã estreito: a barra de pintura roda; Desfazer/Refazer nunca saem do ecrã.
