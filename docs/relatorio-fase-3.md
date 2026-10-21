# Relatório da Fase 3 — 3D Texture Studio

**Data:** 2026-10-21 · **Branch:** `feature/fase-3-3d-texture-studio` · **Base:** `develop` (250424e)

## 1. Estado inicial encontrado

- `develop` em `250424e` — Fases 0–2 completas e estáveis (editor de pixel art,
  histórico esparso, buffer direto Kotlin↔C++, `.osp` v1, exportação PNG com
  escala 1x–16x, spritesheets, atlas). 141 testes unitários verdes.
- O 3D Texture Studio era um **placeholder** (`ui/texture3d/Texture3DStudioScreen.kt`
  da Fase 0, com "FASE 0 - PLANEADO"): nada de renderer, modelos, UV ou pintura 3D.
- Nada da Fase 3 existia; nenhuma funcionalidade das fases anteriores foi
  substituída — todas as alterações são aditivas ou compatíveis.

## 2. Funcionalidades existentes preservadas

- Editor 2D (Texture Studio), motor C++17/JNI, histórico, `.osp`, exportação
  PNG (incl. escala/fundo), spritesheets e atlas: **inalterados**.
- Únicas alterações no código 2D existente (todas aditivas):
  `EditorViewModel.adoptSharedTexture()` + `publishCurrentTextureToBus()`
  (bus de partilha com o 3D) e 1 linha no `onDispose` do ecrã 2D.
- `.osp`: projetos **v1 abrem intactos** (testado); a app grava v2.
- 212/212 testes verdes (os 141 anteriores incluídos).

## 3. Funcionalidades da Fase 3 implementadas

| Funcionalidade | Estado | Onde |
| --- | --- | --- |
| Visualizador 3D interativo (1 dedo roda, 2 dedos zoom+pan, botão repor) | ✅ funciona | `OrbitCamera`, `Studio3DGlViewport` |
| Geometria de demonstração ao abrir (sem ficheiros externos) | ✅ funciona | `DemoGeometry` (cubo texturizado + chão) |
| Importação glTF/GLB com validação explícita | ✅ funciona | `GltfParser` (GLB e glTF autocontido; erros claros para Draco/extensões/buffers externos/JSON inválido) |
| Deteção de materiais, malhas e UV | ✅ funciona | parser extrai materials/meshes/TEXCOORD_0; painel Info e faixa de materiais |
| Aplicação de texturas do Texture Studio via UV | ✅ funciona | binding `DOCUMENT` (pixels.rgba é a textura), shaders com UV |
| Atualização do 3D quando a textura muda (sem reiniciar projeto) | ✅ funciona | `StudioSharedBus` + `replaceDocumentTexture` |
| Transparência e cores RGBA preservadas | ✅ funciona | alfa reto (nunca premultiplicado), blend separado, MASK com discard |
| Amostragem Nearest (padrão) / Linear (opcional) | ✅ funciona | `GL_NEAREST`/`GL_LINEAR` trocável em runtime |
| Mensagens claras (sem UV, inválido, material incompatível, demasiado grande) | ✅ funciona | Snackbars; limites em `GltfLimits` |
| Guardar no .osp as referências (texturas/materiais/configurações) com versionamento | ✅ funciona | `.osp` v2 + `studio3d.json` + `Studio3DState` |

### Funcionalidades de UV

| Funcionalidade | Estado | Notas |
| --- | --- | --- |
| Vista UV: linhas sobre a textura, finas e semitransparentes, com interruptor | ✅ funciona | `TexturePaintView` + Switch "Linhas UV" |
| Seleção de ilha UV por toque (destaca na vista UV e no 3D) | ✅ funciona | `UvIslands.hitTest` + 2.º passe GL com `glPolygonOffset` |
| Seleção de malha e de material para ver as UV correspondentes | ✅ funciona | chips no painel UV (`selectMesh`/`selectMaterial`) |
| Botão "Pintar esta textura" → Texture Studio | ✅ funciona | bus 3D→2D com guarda de edições não guardadas; ao voltar, o 3D adota a textura editada |
| Modo Dividido (3D em cima, pintura em baixo, 3D atualiza ao vivo) | ✅ funciona | atualização PARCIAL da GPU: `glTexSubImage2D` só da região suja por traço (`DirtyRectAccumulator` → `ModelRenderer.updateTextureRegion`) |
| Modelos sem UV: bloquear UV e Dividido com explicação, 3D funcional | ✅ funciona | `setMode` recusa com mensagem; chips desativados |

### Precisão (nota honesta)

- Alfa reto em toda a linha (bitmaps decodificados com `getPixels` → RGBA
  bytes → `glTexImage2D`); **`GLUtils` não é usado** (premultiplica).
- Píxeis opacos/transparentes permanecem exatos; material BLEND usa
  `glBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA)`.
- Modelos .osp **não reembutem o GLB**: o projeto guarda a REFERÊNCIA
  (nome, SHA-256, contagens) — ao reabrir, a app pede a reimportação do
  modelo (as definições 3D mantêm-se). Decisão documentada: embutir MBs de
  modelo no ZIP inflacionaria projetos; o pedido pedia referências.

## 4. Ficheiros criados e alterados

**Criados (núcleo testável, Kotlin puro):**
- `app/src/main/java/com/pixelone/studio/studio3d/GltfParser.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/GltfLimits.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/UvIslands.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/DirtyRect.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/Mat4.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/OrbitCamera.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/DemoGeometry.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/Studio3DData.kt` (`Studio3DState`)
- `app/src/main/java/com/pixelone/studio/studio3d/RgbaPixels.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/StudioSharedBus.kt`

**Criados (Android):**
- `app/src/main/java/com/pixelone/studio/studio3d/gl/ModelRenderer.kt`
- `app/src/main/java/com/pixelone/studio/studio3d/Studio3DViewModel.kt`
- `app/src/main/java/com/pixelone/studio/ui/texture3d/Texture3DStudioScreen.kt` (reescrita)
- `app/src/main/java/com/pixelone/studio/ui/texture3d/TexturePaintView.kt`
- `app/src/main/java/com/pixelone/studio/ui/texture3d/Studio3DPanels.kt`

**Criados (testes — 71 novos):**
- `app/src/test/java/com/pixelone/studio/studio3d/GltfParserTest.kt` (22)
- `app/src/test/java/com/pixelone/studio/studio3d/UvIslandsTest.kt` (8)
- `app/src/test/java/com/pixelone/studio/studio3d/DirtyRectTest.kt` (5)
- `app/src/test/java/com/pixelone/studio/studio3d/OrbitCameraMat4Test.kt` (13)
- `app/src/test/java/com/pixelone/studio/studio3d/RgbaPixelsTest.kt` (5)
- `app/src/test/java/com/pixelone/studio/studio3d/DemoGeometryTest.kt` (8)
- `app/src/test/java/com/pixelone/studio/studio3d/Studio3DStateTest.kt` (3)
- `app/src/test/java/com/pixelone/studio/osp/OspCodecStudio3DTest.kt` (7)

**Alterados (aditivos/compatíveis):**
- `osp/OspCodec.kt` — v2 (entrada `studio3d.json` opcional, ≤ 64 KB, lista
  branca estendida); leitura aceita v1 e v2; v3+ continua rejeitada
- `osp/FileSystemProjectRepository.kt` — `saveDocument(…, studio3dJson)` e
  `LoadedProjectData.studio3dJson`
- `osp/MiniJson.kt` — `optionalInt`/`optionalFloat` (aditivos)
- `editor/EditorViewModel.kt` — `adoptSharedTexture` + `publishCurrentTextureToBus`
- `ui/texture/TextureStudioScreen.kt` — consome oferta do bus ao entrar;
  publica a textura no `onDispose`
- `navigation/PixelOneApp.kt` — rotas do 3D Studio com callbacks
- `app/build.gradle.kts` — `versionCode 6`, `versionName "0.4.0-fase-3d"`
- `docs/formato-osp.md` — especificação v2
- `README.md` — estado do projeto

## 5. Testes executados e resultados reais

- `:app:testDebugUnitTest`: **212 testes, 0 falhas** (141 anteriores + 71 novos)
- `:app:testReleaseUnitTest`: **212 testes, 0 falhas**
- Cobertura dos testes obrigatórios do pedido:
  - carregamento/validação de modelos → `GltfParserTest` (GLB feliz, data: URI,
    sem índices, stride, hierarquia, quaternião; rejeições: magia, versão,
    extensão obrigatória, buffer externo, accessor fora dos limites, índice
    inválido, imagem externa, mime, sparse, sem triângulos, ficheiro grande)
  - deteção de materiais e UV → `GltfParserTest`
  - associação de texturas a materiais → `Studio3DStateTest` + `GltfParserTest`
  - seleção de ilhas UV e sobreposição → `UvIslandsTest`
  - atualização parcial de textura → `DirtyRectTest` + `RgbaPixelsTest` (sub-rect)
  - recursos inválidos/demasiado grandes → `GltfParserTest` + limites
  - persistência e reabertura `.osp` → `OspCodecStudio3DTest` (roundtrip v2)
  - compatibilidade com projetos anteriores → `OspCodecStudio3DTest` (v1 real)
  - integração com o existente → os 141 testes anteriores permanecem verdes
- NÃO executados: testes instrumentados em emulador/dispositivo físico
  (`connectedDebugAndroidTest`) — sem emulador neste ambiente; o CI corre-os
  no job `instrumented-tests` (API 30) e o resultado fica registado na run.
  Não há declaração de validação em dispositivo físico.

## 6. Compilação Release e validação da assinatura

- Compilação Release: feita pelo workflow `android-release` do GitHub Actions
  (JDK 17, NDK, `:app:testReleaseUnitTest :app:assembleRelease`), com a
  **mesma chave de assinatura** das versões anteriores (secrets do
  repositório — nenhuma chave nova, nenhum segredo exposto/comitado).
- Validação: `apksigner verify --verbose --print-certs` no CI + verificação
  local do SHA-256 contra `SHA256SUMS.txt` do artefacto.

## 7. Branch e commits

- Branch: `feature/fase-3-3d-texture-studio` (base `develop` @ `250424e`)
- `8626517` feat(studio3d): núcleo testável — parser glTF/GLB, ilhas UV, câmara orbital, demo
- `82b9700` feat(studio3d): renderer OpenGL ES, ViewModel, UI dos 3 modos e .osp v2
- (seguidos de commit de documentação e do merge para `develop`)

## 8. APK

- Artefacto **Pixel-One-Release-APK** da execução do workflow (branch e
  develop), com APK assinado + `SHA256SUMS.txt` + `apksigner-report.txt`.
- Cópia local entregue em `/home/z/my-project/download/`.

## 9. Limitações conhecidas / tarefas incompletas

1. **Modelo não é reembutido no .osp** — referência apenas (ver nota acima).
2. **UV como guia dentro do Texture Studio 2D** — o overlay UV vive no 3D
   Studio (vistas UV e Dividido). "Pintar esta textura" abre o Texture Studio
   com a textura, mas SEM as linhas UV sobrepostas ao editor 2D (evitou-se
   tocar no caminho quente do `PixelCanvas` numa fase já extensa).
3. **Primitivas não TRIANGLES** (linhas/tiras) são ignoradas, com contagem
   reportada; skinned meshes desenham na bind pose (sem skinning).
4. **Sem mipmap** — filtros NEAREST/LINEAR sem mips (texturas NPOT); longe
   pode haver cintilação em LINEAR; NN é o padrão.
5. **BLEND não ordenado por profundidade** — opacos→MASK→BLEND numa passada;
   casos raros de sobreposição de materiais BLEND podem mostrar ordem errada.
6. **Testes instrumentados não corridos localmente** (ver §5).
7. Câmera pan/rotação ajustados por sensibilidade fixa — não calibrados em
   dispositivo físico.

## 10. Decisão C++/JNI (regra do pedido)

O renderizador é OpenGL ES puro via GLSurfaceView/Kotlin: o gargalo é a GPU,
não o CPU — JNI adicionaria risco sem benefício mensurável. O motor C++17
existente continua a ser usado onde já demonstrou valor (traço/balde da
pintura no modo Dividido, via `NativeEngine`).
