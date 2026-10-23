# Relatório — Texturização em modelos importados: pintura visível + traço fluido

**Branch:** `fix/missao-ui-selecao-texturas` · **Data:** 2026-10-23
**Âmbito:** exclusivamente os 2 problemas reportados após a ronda anterior —
(1) "pinto em modelos importados e a pintura NÃO aparece no 3D";
(2) "a texturização ainda está travada no modo UV e no Dividido".

---

## FASE 1 — Investigação (código real, sem suposições)

A cadeia completa foi re-rastreada no estado ATUAL da branch (commit
`d6f0e7f` incluído — a correção anterior da corrida de upload estava correta,
mas não cobria estes dois sintomas):
`TexturePaintView`/viewport 3D → `viewModel.paintBegin/Move/End` (ou
`paintAtNdc` → raycast → baricêntricas → UV) → `PaintCore` → `LayeredHistory`
→ `applyEdit` → `composeRegion` + `presentationBitmap` →
`ModelRenderer.updateTextureRegion` (glTexSubImage2D) → `drawRenderable`
→ `bindingOf(material)`.

### Descoberta decisiva (problema 1)

`Studio3DViewModel.importModel()` atribui a ligação inicial dos materiais:

```kotlin
initialBindings[i] = if (m.textureImageIndex != null) TextureRef.EMBEDDED else TextureRef.DOCUMENT
```

**Todo o material COM textura embutida apresenta a textura ORIGINAL do
ficheiro importado** — a pintura edita a textura do PROJETO
(`documentTexture`), que não estava ligada a NENHUM material. Quase todos os
modelos glTF reais têm texturas → o utilizador pinta na vista UV (o traço
aparece aí, porque a vista mostra o documento) e o modelo 3D continua
EXATAMENTE igual. É o sintoma "eu texturizo e não aparece" — não era corrida
nem upload GL: era a LIGAÇÃO material→textura.

A ronda anterior (missão UI/seleção) até oferecia caminhos manuais para
resolver (chips "Textura do projeto" por material; "Copiar para o projeto"),
mas nenhum era automático nem descobrível no gesto de pintar.

### Descoberta decisiva (problema 2)

Medido no código, custo por EVENTO de movimento do dedo (60–120 Hz) em
`TexturePaintView.onDraw`, escalando com o tamanho do modelo importado
(limite do app: 300 000 triângulos):

1. `uvSegments.contentHashCode()` a cada frame — 12 floats/triângulo:
   **3,6 M floats percorridos POR FRAME** a 300k triângulos (~5–15 ms).
2. `canvas.drawPath(uvPath)` a cada frame — 3 arestas/triângulo:
   **até 900 000 verbos rasterizados POR FRAME** (dezenas de ms).
3. O mesmo para o realce da ilha (fill + stroke por frame).

A correção anterior tinha posto os FloatArray em cache, mas o hash e o
desenho do path continuavam por frame — por isso "continua travada" nos
modos UV e Dividido (o cubo demo, 12 triângulos, nunca mostrou o problema).

Verificações C–F da ronda anterior continuam válidas (contexto GL, formato,
flip, stride, atualização regional, persistência): sem regressões.

## FASE 2 — Quantificação (síntese)

| Custo por evento de traço (300k triângulos) | Antes | Depois |
|---|---|---|
| Hash do array de guias | 3,6 M floats/frame | 0 (identidade) |
| Desenho das guias UV | 900k verbos/frame | 1 `drawBitmap` |
| Realce da ilha | 2× paths/frame | incluído no bitmap |
| 1ª construção das guias | 3,6 M `Float` encaixotado (ArrayList) | FloatArray exato, zero boxing |
| Mudança de ligação (chips/1.º traço) | rebuild da CENA (todos os VBOs+texturas) | só remapeia ids de textura |

## FASE 3 — Correções implementadas

| Ficheiro | Alteração | Motivo |
|---|---|---|
| `PaintBinding.kt` (NOVO, puro) | Plano de flips EMBEDDED→DOCUMENT (explícitos E por omissão, mesma regra de fallback do renderer) | Torna a correção testável em JVM; decisão isolada da UI |
| `Studio3DViewModel.kt` | `ensurePaintVisibleOnModel()` no início do traço/balde: materiais que apresentam textura embutida passam a apresentar a do projeto, COM mensagem (nunca silencioso); `bindMaterial` usa o novo `setBindings` (sem rebuild); `uvWireframeSegments` delega em `UvGuides` (sem boxing) | Pintura do projeto fica VISÍVEL no modelo importado no 1.º toque; as embutidas continuam intactas no catálogo e repostas nos chips |
| `ModelRenderer.kt` | `setBindings(next)`: remapeia `materialTextures` na thread GL SEM reconstruir cena; `lastSceneRequest` sincronizado | Fim do hitch de geometria ao mudar ligações; sobrevive a recriação de contexto |
| `TexturePaintView.kt` | Overlay UV+realce RASTERIZADO num bitmap do tamanho da vista (rebuild só quando mudam segmentos/realce por identidade, zoom/pan, tamanho ou textura); caches de path por identidade (fim do `contentHashCode` por frame) | Traço em UV/Dividido = 1 `drawBitmap` por frame, independentemente do nº de triângulos |
| `UvGuides.kt` (NOVO, puro) | Construção dos segmentos com FloatArray pré-alocado (12 floats/triângulo) | Fim de 3,6 M boxing na main thread por modelo |
| `PaintBindingTest.kt`, `UvGuidesTest.kt` (NOVOS) | 10 testes (importação típica, omissão, idempotência, demo intocado, ordem a-b/b-c/c-a, filtro de malha, tamanho exato) | Bloqueiam regressão dos dois contratos novos |

**Fora do âmbito (propositadamente):** nenhuma mudança de UI, formatos,
ferramentas, undo/redo, importação/exportação, C++/JNI; nenhuma thread nova;
o flip NÃO apaga nada — bitmaps embutidas ficam no catálogo "Texturas do
modelo" e qualquer material pode voltar à embutida nos chips (a mensagem
diz exatamente isso).

## FASE 4 — Validação

Executado (evidência real):
- `:app:testReleaseUnitTest` — **302 testes, 0 falhas** (292 anteriores + 10 novos).
- `:app:assembleDebug` — OK. `:app:assembleRelease` — OK (assinatura real no CI, keystore dos secrets, apksigner v2).
- Contrato do flip provado por testes: idempotente (2.º traço = no-op), demo nunca flipado, materiais NONE/DOCUMENT intocados.

NÃO executado (declaração honesta):
- **Sem dispositivo/emulador no ambiente**: os cenários manuais (importar
  .glb texturizado → pintar no UV/Dividido → ver o traço no 3D; troca de
  material;undo/redo; guardar/reabrir) NÃO foram executados fisicamente.
  O CI corre os testes instrumentados no emulador API 30 (job paralelo).
- A sensação de fluidez com 300k triângulos é provada por quantificação de
  código (custo por frame O(1) durante o traço); a prova final é o gesto
  num aparelho.

## Como confirmar no dispositivo

1. Importar um modelo COM texturas → modo Dividido → ativar "Pintar" →
   pintar no painel de baixo: **o traço passa a aparecer no modelo 3D
   imediatamente**; aparece UMA mensagem "Pintura agora visível no modelo:
   …" (as texturas originais continuam em "Texturas do modelo").
2. Traço contínuo no UV/Dividido num modelo grande: sem bloqueios (o
   overlay deixa de ser redesenhado por frame).
3. Repor a textura original: chips de material → "Embutida" (sem hitch —
   já não reconstrói a cena).
4. Logcat `PixelOnePerf/3D`: "ligações de materiais atualizadas (… sem
   rebuild)" no 1.º traço; `texSubImage WxH` apenas do tamanho do traço.
