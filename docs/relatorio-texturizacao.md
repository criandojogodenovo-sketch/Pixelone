# Relatório — Texturização: pintura UV no 3D + desempenho do traço

**Branch:** `fix/missao-ui-selecao-texturas` · **Data:** 2026-10-23
**Âmbito:** exclusivamente os 2 problemas reportados — (1) pintura do editor UV não aparece corretamente no modelo 3D; (2) lentidão/atrasos/bloqueios durante o traço.

---

## FASE 1 — Investigação (código real, sem suposições)

Fluxo rastreado por completo: `TexturePaintView`/viewport 3D → `viewModel.paintBegin/Move/End`
(ou `paintAtNdc` → raycast → baricêntricas → UV) → `PaintCore` (stamp/Bresenham na camada ativa)
→ `LayeredHistory` (undo por texel) → `applyEdit` → `LayerStack.composeRegion` → `compositeDoc`
+ `presentationBitmap` → `ModelRenderer.updateTextureRegion` → `glTexSubImage2D` → material com
binding DOCUMENT → `drawRenderable` (RENDERMODE_CONTINUOUSLY).

### Verificações A–F

| Ponto | Veredicto (evidência) |
|---|---|
| **A.** Traços alteram pixels reais? | ✅ SIM — pintam na camada ativa (`activeLayerDoc`), recomposição regional em `compositeDoc`; a vista UV mostra `presentationBitmap` (mesma instância atualizada). Nada é "camada de visualização". |
| **B.** Mesma textura lógica/material? | ✅ Vista UV = `presentationBitmap`; 3D = `documentTexture` (mesmos píxeis via `updateTextureRegion`). Material com binding DOCUMENT aponta para `documentTexture` (`drawRenderable`). Guarda de pintura para texturas EMBEDDED já existia (missão anterior). |
| **C.** IDs GL/contexto/formato/stride/flip? | ✅ Contexto novo tratado (`onSurfaceCreated` repõe ids — correção Ronda A); RGBA não-premultiplicado; sem flip (v glTF → linha → T coerente em UV e 3D); stride correto no `extractRectToRgba`. **MAS** — corrida de dados no buffer de subida (ver causa raiz 1). |
| **D.** Atualização após cada alteração? | ✅ Por evento (`applyEdit` → `glTexSubImage2D` regional). Sem recriação de material. **MAS** drop silencioso se `documentTexWidth != docWidth` avaliado fora da thread GL (estado stale no re-attach). |
| **E.** Trocas UV↔3D / materiais / gravação perdem alterações? | ✅ NÃO — ao voltar ao 3D o viewport é recriado e `attachRenderer` reenvia a composição INTEIRA (`replaceDocumentTexture`); bindings persistem no `studio3d.json`; gravação guarda camadas + composição. |
| **F.** Sincronização Kotlin/JNI/C++? | ⚠️ `PixelDocument` é ByteBuffer direto Kotlin puro (sem JNI por texel) — sem perdas nesse nível. O problema de sincronização estava **entre a thread de mutação e a thread GL** (causa raiz 1). |

## Causas raiz

### Problema 1 — pintura não aparece corretamente no modelo 3D
**Corrida de dados no buffer de subida da textura (`uploadScratch`) em `ModelRenderer`:**
- `updateTextureRegion` era chamado na **thread de mutação** e escrevia a região no buffer
  direto PARTILHADO; só depois enfileirava o `glTexSubImage2D`.
- O traço gera eventos a 60–120 Hz — mais rápido do que a thread GL consome a fila
  `pendingOps`. Cada nova extração **sobrescrevia o mesmo buffer** antes do upload anterior
  correr → o GL enviava os píxeis da região N+1 para o retângulo da região N → pintura
  **deslocada/emelindrada/aparentemente ausente** no modelo (sintoma exato "não aparece
  corretamente").
- Secundário: a verificação `documentTexWidth != docWidth` lia estado do GL thread a partir
  da thread chamadora (stale logo após o attach) → updates podiam ser descartados.

### Problema 2 — lentidão, atrasos e bloqueios durante o traço
Medido pelo código (custo por evento de movimento):
1. **`uvWireframeSegments()` reconstruído a cada recomposição** dos painéis UV/Dividido —
   e o painel recompose a cada evento (`paintVersion`). Para modelos grandes: FloatArray de
   centenas de KB + boxing em `ArrayList<Float>` **na thread principal, por evento** —
   principal causa dos bloqueios.
2. **Checkerboard desenhado célula a célula** no `onDraw` (~3.000 `drawRect` por frame em
   ecrã cheio) a cada invalidação.
3. **`viewModelScope.launch(Dispatchers.Main)` por evento** em `applyEdit` (alocação de
   corutina + dispatch por movimento; sem coalescência).
4. Menores (identificados, NÃO alterados por serem aceitáveis/riscos maiores):
   boxing `HashMap` no histórico por texel (`LayeredHistory.onBeforeChange`) — ~centenas
   por evento; `RENDERMODE_CONTINUOUSLY` (CPU/GPU constante em repouso — não afeta o
   traço; mudar exigiria `requestRender` em todos os estados, contrário de "menor correção
   segura").

## FASE 3 — Correções implementadas

| Ficheiro | Alteração | Motivo |
|---|---|---|
| `ModelRenderer.kt` | Extração da região movida PARA DENTRO do comando GL (`pendingOps`); verificação de dimensões/existência re-feita na thread GL; `scratchFor()` centraliza o buffer de subida | Elimina a corrida de dados — cada `glTexSubImage2D` leva os SEUS píxeis; `uploadScratch` agora é tocado só pela thread GL. Assinatura/chamadores inalterados. |
| `Studio3DViewModel.kt` | Cache de `uvWireframeSegments` (por meshFilter) e `islandHighlightSegments` (por instância de seleção), invalidadas em `applyModel`; `postValue` em vez de `launch` no `applyEdit`/`recomposeAll` | Fim do rebuild por evento na UI thread; invalidações coalescidas. Modelos novos invalidam a cache (sem stale). |
| `TexturePaintView.kt` | Fundo quadriculado pré-renderizado (tile 32×32 com `BitmapShader` REPEAT, aspeto idêntico); `borderPaint` em cache | 1 `drawRect` em vez de milhares por frame; menos alocação por `onDraw`. |
| `RgbaPixelsTest.kt` | Novo teste: extrações repetidas NO MESMO buffer (padrão do scratch GL) permanecem exatas | Bloqueia regressão do contrato do novo caminho. |

**Fora do âmbito (propositadamente):** nenhuma mudança de UI, de formatos, de ferramentas,
de undo/redo, de importação/exportação, de C++/JNI, nem substituição de stack. Nenhuma
thread ou mecanismo de sincronização novo (a correção REMOVE partilha entre threads).

## FASE 4 — Validação

Executado (evidência real):
- `:app:testReleaseUnitTest` — **292 testes, 0 falhas** (291 anteriores + 1 novo: contrato
  do buffer reutilizado). Cobre o funil de pintura (Ronda B): UV→texel, máscara, simetria,
  balde, histórico entre camadas, `composeRegion` ≡ `compose`, `.osp` v3 roundtrip.
- `:app:assembleDebug` — OK.
- `assembleRelease` assinado pelo CI (keystore real, apksigner) — publicado na branch.

NÃO executado (declaração honesta):
- **Sem dispositivo/emulador no ambiente**: os cenários manuais (pintar no UV e ver no 3D;
  múltiplas ilhas; alternância UV↔3D repetida; troca de material; undo/redo; guardar e
  reabrir; traços contínuos com texturas grandes) NÃO foram executados fisicamente. O CI
  corre os testes instrumentados no emulador API 30 (job paralelo do release).
- A correção da corrida é provada por raciocínio sobre o código + testes do contrato do
  buffer; a prova definitiva de sensação de fluidez exige o gesto real num aparelho.

## Como confirmar no dispositivo
1. Modo **Dividido**: pintar embaixo — o modelo em cima deve mostrar o traço no sítio
   certo, sem emelindos nem deslocamentos (antes podia aparecer errado).
2. Traço contínuo com pincel grande em textura 1024²: sem "engasgos" (antes havia blocos
   causados pelo rebuild das linhas UV por evento).
3. Filtrar Logcat por `PixelOnePerf` (PerfLog) — cada `texSubImage WxH` deve ter W×H do
   tamanho do traço, não da textura inteira.
