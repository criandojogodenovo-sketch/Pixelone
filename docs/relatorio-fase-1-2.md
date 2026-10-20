# Relatório — Fases 1 + 2 do Pixel One

**Data:** 2026-10-20 · **Branch:** `develop` · **Head:** `072f7f9` (mais commit do relatório)
**Escopo:** PROMPT 02 — implementação integrada das Fases 1 (editor de pixel art funcional) e 2 (exportação PNG, spritesheets e atlas).

---

## A. Funcionalidades implementadas

### Fase 1 — Editor de pixel art funcional

**1. Documento e canvas**
- Documento 2D real (`PixelDocument`): buffer ARGB row-major, RGBA com
  transparência real (alpha 0–255), limites validados antes de qualquer
  alocação (1–2048 px por eixo; máx. 4 194 304 píxeis ≈ 16 MB).
- Canvas ocupa a maior parte do ecrã; fundo quadriculado de transparência
  desenhado apenas visualmente (nunca faz parte do bitmap exportado).
- Grelha de píxeis opcional, visível a partir de zoom ≥ 4×, com culling ao
  viewport (proteção de performance em canvas grandes).
- Renderização nítida: `FilterQuality.None` — zero interpolação em qualquer
  zoom. Tamanho lógico da imagem é independente do tamanho visual no ecrã.
- Mapeamento ecrã→píxel: `floor((ecrã − offset) / escala)`, correto sob
  qualquer zoom/pan (testado).

**2. Ferramentas**
- Lápis: píxeis e linhas contínuas durante o movimento (Bresenham; o traço
  começa no DOWN do dedo — sem falhas entre toque e primeiro movimento).
- Borracha: escreve transparência real (0x00000000), não "pinta de fundo".
- Balde: preenchimento contíguo 4-conectado com tolerância por canal
  (0–64, ciclo na UI), BFS com fila em anel sem alocação por píxel.
- Conta-gotas: recupera a cor RGBA exata do píxel.
- Seletor de cores: sliders R/G/B/A + campo hex `#AARRGGBB` + pré-visualização
  sobre quadriculado; cor ativa sempre visível na barra.
- Paleta do projeto: cores frequentes, persistida dentro do `.osp`,
  adicionável/removível pelo utilizador.
- Pincel 1–8 px (quadrado determinístico; 1 = pixel art pura).
- Ferramenta ativa e cor selecionada destacadas visualmente.

**3. Gestos e navegação no canvas**
- Estratégia documentada (no código e no README): **1 dedo desenha; 2 dedos
  movem e fazem zoom** (pinça ancorada no centroide). Um único dedo NUNCA
  move o canvas.
- Segundo dedo durante um traço → traço CANCELADO e restaurado (sem marcas
  acidentais), gesto passa a navegação; dedo remanescente ignorado até
  todos subirem.
- Botões dedicados: zoom − / ajustar à vista / zoom +, alternar grelha.
- Limites de zoom 0,05×–64×; transformações estáveis (zoom ancorado no ponto).

**4. Desfazer e refazer**
- Histórico real com diferenças regionais (`RegionEdit`): retângulo delimitador
  + valores antes/depois — NUNCA copia a imagem inteira por movimento do dedo.
- Um traço contínuo = UMA operação lógica (commit no fim do gesto); fill é
  atómico. Não existe entrada por píxel.
- Orçamento de memória: 32 MB por defeito (configurável), libertação das
  entradas mais antigas; entrada única maior que o orçamento é mantida sozinha
  (comportamento explícito e documentado).
- Nova edição após undo limpa a pilha redo; `cancelStroke` restaura píxeis.

**5. Persistência real (.osp)**
- Formato nativo `.osp` **v1** (especificação completa em `docs/formato-osp.md`):
  ZIP com `manifest.json` (versionado) + `pixels.rgba` sem perdas.
- Guardar/abrir/continuar sem alteração de píxeis (testado por roundtrip).
- Transparência preservada; validação de versão, dimensões, entradas e
  recursos em falta; versões futuras rejeitadas com mensagem clara.
- Segurança: lista branca de entradas (path traversal impossível — não há
  extração em disco), limites anti zip-bomb (64 KB manifesto; tamanho exato
  dos píxeis validado).
- Gravação ATÓMICA: `.tmp` → reler e validar → rename; uma falha de escrita
  NUNCA destrói o ficheiro anterior (testado).
- Armazenamento: privado da app (`filesDir/projects`) sem permissões; SAF
  para Guardar como/Importar/Exportar (destino escolhido pelo utilizador).

**6. Estado e experiência**
- Novo projeto (presets 8–256 + personalizado validado), abrir (folha com
  lista ordenada + apagar), guardar, guardar como, importar `.osp`.
- Indicador "•" de alterações não guardadas; confirmação antes de descartar
  (Guardar e sair / Descartar / Continuar).
- Feedback de sucesso/falha via snackbar; mensagens de erro compreensíveis
  (IO, disco cheio, ficheiro inválido, versão não suportada).
- Autosave no ON_STOP (interrupções do ciclo de vida) com proposta de
  restauro na entrada do editor; ViewModel sobrevive a rotação.
- Nenhum botão decorativo: todas as ações executam funções reais.

### Fase 2 — Exportação PNG, spritesheets e atlas

**7. Exportação PNG**
- Exportar a imagem atual via `CreateDocument("image/png")` (SAF).
- Dimensões e valores RGBA exatos: bitmap de exportação **não premultiplicado**
  (`setPremultiplied(false)`) — correção descoberta pelos testes (Android
  pré-multiplica por omissão e degrada RGB com alpha baixo).
- Transparência preservada; grelha/quadriculado/UI nunca entram no ficheiro
  (comprovado por parser PNG independente nos testes).
- Erros de escrita/cancelamento tratados com mensagens; validação por leitura
  e comparação de píxeis nos testes.

**8. Spritesheets**
- Seleção de imagens (SAF multi-seleção), largura/altura do quadro (auto ou
  fixa), colunas (auto = ceil(√n) ou fixas), espaçamento e margem.
- Posicionamento determinístico (ordem preservada — nomes de quadros para
  animação mantidos), transparência preservada.
- Pré-visualização REAL da composição antes de guardar.
- Validação: quadros incompatíveis rejeitados com lista dos ofensores;
  limites 4096 px/eixo e 8M píxeis (32 MB) com mensagens explicativas.
- Exportação PNG + JSON com `schemaVersion` (esquema em
  `docs/spritesheet-atlas-json.md`): dimensões da folga, posição, dimensões,
  linha/coluna de cada quadro.

**9. Atlas de texturas**
- Seleção de várias imagens de tamanhos diferentes; validação e limites.
- Empacotamento *shelf* determinístico (altura desc → largura desc → nome asc),
  sem rotação — dados originais preservados; sem sobreposições (por
  construção + testes); altura final compacta (real usada).
- Limites máximos configuráveis (com teto de segurança); se não couber, erro
  explicativo (nome, dimensões, limite excedido).
- Pré-visualização antes de guardar; PNG + JSON com regiões e dimensões.

**10. Eficiência e robustez**
- Sem cópias completas do bitmap por evento de toque (sync por píxel no
  traço; cópia completa apenas no commit/undo/redo/fill).
- IO pesado (guardar/exportar/decodificar) em `Dispatchers.IO`; plano e
  composição de spritesheet/atlas em `Dispatchers.Default`.
- Limites de memória em documentos, entradas e saídas; reciclagem de bitmaps
  temporários; falhas de stream nunca reportadas como sucesso (testado).

---

## B. Testes executados, resultados e evidências

**78 testes unitários JVM — TODOS PASSAM** (`./gradlew :app:testDebugUnitTest`,
evidência: `app/build/test-results/` e relatório HTML do Gradle; mesma suíte
corre na variante release no CI).

| Suíte | Testes | Cobre |
| --- | --- | --- |
| `PixelDocumentTest` | 9 | cantos/centro, limites, transparência, RGBA bytes, validação de dimensões |
| `PixelEngineTest` | 13 | linhas contínuas (6 direções + conectividade 8), borracha→transparência, conta-gotas RGBA, fill região/tolerância/degenerado, brochas |
| `HistoryManagerTest` | 12 | undo restaura, redo reaplica, redo limpo em nova edição, orçamento de memória, traço=1 entrada, cancelamento, fill atómico |
| `OspCodecTest` | 11 | roundtrip total, transparência/alphas extremos, ficheiro inválido, JSON corrupto, versão futura, zip-bomb/traversal, zip truncado, escaping |
| `FileSystemProjectRepositoryTest` | 7 | criar-guardar-fechar-reabrir-comparar, falha de escrita não destrói anterior, path traversal, corrupto não impede listagem |
| `SpriteSheetPlannerTest` | 7 | coordenadas, sem sobreposições, JSON↔PNG coerentes, ordem preservada, incompatíveis rejeitados, limites |
| `AtlasPackerTest` | 7 | determinismo, sem sobreposições, dados originais, JSON, "não cabe" explicativo, limites |
| `PngRoundtripTest` (Robolectric) | 3 | roundtrip REAL Bitmap.compress→parser independente dos bytes do PNG, valores exatos incl. alpha 1 e 254, falha de stream ≠ sucesso |
| `PixelProjectTest` + `ProjectRepositoryTest` (Fase 0) | 9 | regressão da fundação |

**Descoberta importante dos testes:** o roundtrip PNG inicial falhava num
píxel com alpha 1 — o Bitmap Android é premultiplicado por omissão e
arredonda RGB com alpha muito baixo. Corrigido com `setPremultiplied(false)`
no caminho de exportação e verificação dos bytes do ficheiro com parser PNG
independente (o ficheiro passou a conter os valores exatos, provado no teste).

**Integração Android (teste F):**
- Navegação entre os 3 espaços de trabalho: mantida (grafo inalterado;
  Texture Studio agora é o editor real; os outros 2 mantêm placeholders
  explícitos). Compilação e rotas verificadas no build.
- Criar/guardar/fechar/reabrir projeto: testado a nível de repositório e
  codec (JVM) — o mesmo código usado pela UI.
- Testes de UI Compose/instrumentados: **NÃO executados** — não há
  dispositivo/emulador acessível neste ambiente (declarado em C e D).

---

## C. Funcionalidades incompletas

- **Testes em dispositivo físico / UI Compose instrumentados**: não
  executados (sem aparelho nem emulador no ambiente). Não afirmo teste físico.
- **Camadas, seleção/recorte, transformações e animação no editor**: fora do
  escopo das Fases 1+2 (especificação não os pedia).
- **Nenhuma funcionalidade pedida ficou pendente**: desenhar, desfazer/
  refazer, guardar/reabrir, exportar PNG, spritesheets e atlas estão
  implementados e testados.

## D. Problemas encontrados e limitações

1. **Premultiplicação alfa** (resolvido): ver secção B.
2. **`Guardar como` via SAF**: se a escrita falhar a meio, o destino pode
   ficar parcial (limitação do SAF — o stream trunca ao abrir). Mitigado:
   o ficheiro é serializado/validado ANTES de abrir o stream e o projeto
   interno permanece intacto.
3. **Decodificação de PNG no Android** pré-multiplica por omissão; leituras
   que exijam valores exatos devem usar `inPremultiplied=false` (documentado).
4. **Sem dispositivo físico**: a interação de gestos foi verificada por
   implementação/testes do mapeamento e compilação, não por toque real.
5. `PreviewPanel` de spritesheet/atlas: imagens parcialmente transparentes
   podem parecer ligeiramente mais brilhantes na pré-visualização
   (renderização de bitmap não premultiplicado); o PNG exportado é exato.

## E. Ficheiros criados e alterados

**Novos (motor/persistência/exportação — Kotlin puro):**
- `app/src/main/java/com/pixelone/studio/pixel/PixelDocument.kt`
- `app/src/main/java/com/pixelone/studio/pixel/PixelEngine.kt`
- `app/src/main/java/com/pixelone/studio/pixel/HistoryManager.kt`
- `app/src/main/java/com/pixelone/studio/osp/MiniJson.kt`
- `app/src/main/java/com/pixelone/studio/osp/OspCodec.kt`
- `app/src/main/java/com/pixelone/studio/osp/FileSystemProjectRepository.kt`
- `app/src/main/java/com/pixelone/studio/export/SpriteSheetPlanner.kt`
- `app/src/main/java/com/pixelone/studio/export/AtlasPacker.kt`

**Novos (UI/estado):**
- `app/src/main/java/com/pixelone/studio/editor/EditorViewModel.kt`
- `app/src/main/java/com/pixelone/studio/editor/PixelIcons.kt`
- `app/src/main/java/com/pixelone/studio/ui/editor/PixelCanvas.kt`
- `app/src/main/java/com/pixelone/studio/ui/editor/EditorDialogs.kt`
- `app/src/main/java/com/pixelone/studio/ui/editor/OpenProjectSheet.kt`
- `app/src/main/java/com/pixelone/studio/ui/editor/SheetAndAtlasScreens.kt`

**Novos (testes):** 7 suítes em `app/src/test/java/com/pixelone/studio/`
(`pixel/`, `osp/`, `export/`).

**Novos (docs):** `docs/formato-osp.md`, `docs/spritesheet-atlas-json.md`,
`docs/relatorio-fase-1-2.md`.

**Alterados:** `app/src/main/java/com/pixelone/studio/ui/texture/TextureStudioScreen.kt`
(placeholder → editor completo), `app/build.gradle.kts` (deps + versão
0.2.0/versionCode 2), `README.md`, dois ecrãs placeholder (rótulos).

**Não alterados:** `.github/workflows/android-release.yml` (preservado como
estava — já cumpria todos os requisitos: assinatura por secrets, apksigner,
SHA256SUMS, artefacto, permissões mínimas), `AndroidManifest.xml` (sem
permissões novas necessárias), tema e navegação da fundação.

## F. Decisões arquiteturais e dependências novas

| Decisão | Justificação |
| --- | --- |
| Motor 2D em Kotlin puro (`IntArray` ARGB) | Testável em JVM real (78 testes sem dispositivo); UI/Bitmap isolados numa camada fina. |
| Diferenças regionais no histórico (não snapshots) | Sem cópias da imagem por gesto; orçamento de memória previsível (32 MB). |
| Substituição direta de píxel (sem blending alfa) | Padrão de editores de pixel art; cores exatas e determinísticas. |
| `.osp` = ZIP (manifest.json + pixels.rgba) | Versionado, sem perdas, inspecionável; lista branca e limites anti zip-bomb; Java ZIP em JVM e Android sem dependências. |
| MiniJson próprio | Evita dependência de JSON; formatos próprios pequenos; parser rejeitante com limite de profundidade. |
| `setPremultiplied(false)` na exportação | Valores RGBA exatos no PNG (descoberto por teste). |
| Ícones vetoriais próprios | Evita material-icons-extended (~30 MB) com R8 desligado. |
| Gestos: 1 dedo desenha, 2 dedos navegam | Separação clara sem modos ambiguos; cancelamento de traço ao entrar 2.º dedo. |
| ViewModel + autosave ON_STOP | Sobrevive a rotação; interrupções do ciclo de vida não perdem trabalho. |
| Preservação do workflow da Fase 0 | Já cumpria todos os requisitos do PROMPT 02 (testes antes do build, assinatura, apksigner, SHA256SUMS, artefacto). |

**Dependências novas (todas justificadas, nenhuma "conveniência"):**
- `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7` — retenção de estado.
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` — IO fora do main thread.
- `org.robolectric:robolectric:4.14.1` + `androidx.test:core:1.6.1` (apenas testes) — roundtrip PNG REAL em JVM.

## G. Resultado real do GitHub Actions

- **Run 39407150545** (`android-release`, push `072f7f9` → `develop`):
  **concluído com SUCCESS** em ~2 min.
- Executou `:app:testReleaseUnitTest` (78 testes, 0 falhas) e
  `:app:assembleRelease`.
- `apksigner verify --verbose --print-certs`: **Verifies**, esquema v2,
  certificado `CN=Pixel One Release, OU=Pixel One Studio, O=Pixel One,
  L=Luanda, ST=Luanda, C=AO` (mesma identidade da Fase 0 — chaves nos
  secrets preservadas, nenhuma chave nova gerada).
- Artefacto **Pixel-One-Release-APK** (6 505 281 bytes) com
  `PixelOne-0.2.0-fases1-2-release.apk`, `SHA256SUMS.txt` e
  `apksigner-report.txt`; retenção 30 dias.
- Histórico completo: https://github.com/criandojogodenovo-sketch/Pixelone/actions

## H. Link para o APK Release assinado

**Descarga direta (sem login — repo público, release pré-release
`v0.2.0-fases1-2`, nova tag, sem substituir a `v0.1.0-fase0`):**

https://github.com/criandojogodenovo-sketch/Pixelone/releases/download/v0.2.0-fases1-2/PixelOne-0.2.0-fases1-2-release.apk

Página da release (APK + SHA256SUMS.txt + apksigner-report.txt):
https://github.com/criandojogodenovo-sketch/Pixelone/releases/tag/v0.2.0-fases1-2

Artefacto do workflow (requer login no GitHub):
https://github.com/criandojogodenovo-sketch/Pixelone/actions/runs/39407150545

## I. SHA-256 e validação da assinatura

- **SHA-256 do APK:**
  `0fcc0d621c5cfff38860299ff4909820193fea81311f69d81b9b85e752441c94`
- Consta no `SHA256SUMS.txt` publicado e foi re-verificado após download
  da release (valor idêntico).
- **Assinatura validada DUAS vezes** com `apksigner verify --verbose
  --print-certs` (CI e local, build-tools 35): `Verifies`; APK Signature
  Scheme v2; 1 assinante; RSA 2048; certificado
  `CN=Pixel One Release…` — a identidade de assinatura da Fase 0 foi
  preservada (cert SHA-256 `63c64623d74c2fa817d6a633814faf8c6d24ff9cfd0748c9d6702d7bc1c12507`).

## J. Instalar e testar no telemóvel

1. Descarregue o APK do link da secção H no telemóvel Android 8.0+ (minSdk 26).
2. Ao instalar, autorize "Instalar apps desconhecidas" para o navegador/gestor
   de ficheiros (sideload normal para APK fora da Play Store).
3. Sugestão de roteiro de teste (5 min):
   - Texture Studio → Novo projeto (32×32) → desenhar com 1 dedo (lápis),
     testar borracha, balde numa região fechada, conta-gotas;
   - pinça com 2 dedos para zoom/pan; botão de grelha; zoom alto mostra a grelha;
   - desenhar e tocar em desfazer/refazer (um traço inteiro some/volta);
   - menu (⋮) → Guardar → mudar nome? Não: Guardar guarda; fechar app e
     reabrir → Abrir projeto → reabrir (píxeis intactos);
   - menu (⋮) → Exportar PNG → escolher Downloads → abrir o PNG (fundo
     transparente, sem grelha);
   - menu (⋮) → Spritesheet → escolher 2–3 PNGs → ver pré-visualização →
     Exportar PNG + JSON;
   - menu (⋮) → Atlas → 2 imagens de tamanhos diferentes → pré-visualização →
     PNG + JSON;
   - rodar o telemóvel a meio da edição (estado mantido); botão home e
     regresso (autosave/restauro se aplicável).
4. Verificação de integridade (opcional, num computador):
   `sha256sum -c SHA256SUMS.txt`.

## K. Recomendações para as Fases 3 + 4

**Fase 3 — 3D Texture Studio (OpenGL ES):**
1. Começar por um `GLSurfaceView`/`GLSurfaceRenderer` isolado num preview do
   editor (o motor 2D já entrega texturas RGBA — subir como `GL_TEXTURE_2D`
   é direto: os dados do `.osp`/PNG já estão em formato compatível).
2. Suporte inicial a OBJ (malhas simples, sem material) + carregamento de UV;
   renderizar a textura do Texture Studio no modelo com checker UV de fundo.
3. Reaproveitar o atlas da Fase 2 como "material atlas" do modelo 3D.
4. minSdk 26 já cobre GLES 3.0; JNI continua desnecessário a este nível.
5. Testes: carregamento de OBJ em Kotlin puro (parsing testável) + golden
   images se possível.

**Fase 4 — 2D-to-3D Studio:**
1. Referências ortográficas: importar frente/lado/topo com opacidade
   ajustável sobre a grelha (reutilizar o canvas da Fase 1 com camada de
   imagem de fundo).
2. Extração de geometria: traçar linhas sobre a referência e extrudar —
   começar por primitivas (caixa, cilindro) alinhadas às vistas antes de
   triangulação livre.
3. Persistir os modelos num formato JSON próprio versionado (mesmo padrão
   do `.osp`), guardado no mesmo repositório privado.

**Gerais:**
- Validar num telemóvel real cedo (gestos, performance do canvas 512×512+).
- Reavaliar minifyEnabled/R8 quando a base crescer (com regras ProGuard para
  reflexão, se houver).
- Considerar testes instrumentados conectados (ex.: Firebase Test Lab) para
  cobrir interações reais em ecrãs pequenos.
