# Relatório — Correção da exportação PNG (z.png com 0 bytes)

**Branch:** `fix/png-export-zerobytes` · **Commit:** `5cf5084` · **Versão:** `0.3.1-fix-png-export` (versionCode 4)

## A — Causa identificada

A exportação usava o seletor SAF (`ActivityResultContracts.CreateDocument("image/png")`).
O SAF cria o ficheiro de destino **vazio no momento em que o utilizador confirma o
seletor** — o conteúdo só seria escrito depois. Nesse intervalo, dois caminhos
deixavam o ficheiro permanentemente a 0 bytes:

1. **Retorno silencioso sem documento:** `exportPng(uri)` começava com
   `val doc = document ?: return` — se o ViewModel não tivesse documento ativo
   (app restaurada após morte de processo, exportação tocada antes de abrir/criar
   um projeto), a função terminava sem escrever nada **e sem qualquer aviso**.
2. **Falhas reportadas apenas num snackbar efémero:** qualquer exceção (stream
   nulo, falha de codificação, destino indisponível) era apanhada e mostrada num
   snackbar de 4 s que o utilizador podia não ver; o ficheiro SAF criado ficava
   0 bytes.

O nome `z.png` vem do nome do projeto (`"${viewModel.projectName}.png"`) — não
havia qualquer nome `z.png` hardcoded no código.

## B — Ficheiros alterados

| Ficheiro | Alteração |
| --- | --- |
| `app/src/main/java/com/pixelone/studio/export/PngExport.kt` | **NOVO** — núcleo testável: `buildBitmapFromDocument`, `writePngToStream`, `CountingOutputStream`, `displayNameFor`, `sanitizeBaseName`, `mediaValues` |
| `app/src/main/java/com/pixelone/studio/editor/EditorViewModel.kt` | `exportPngToDownloads()` novo (MediaStore + IS_PENDING + Toast); `exportPng(uri)` SAF legado corrigido (sem retorno silencioso, apaga ficheiro órfão em falha); helpers `toast`, `existingDownloadNames`, `queryDisplayName`; tag `PixelOnePerf/Export` |
| `app/src/main/java/com/pixelone/studio/ui/texture/TextureStudioScreen.kt` | Botão "Exportar PNG": Android 10+ usa o fluxo direto para `Download/` (sem seletor); Android < 10 mantém o SAF legado |
| `app/src/test/java/com/pixelone/studio/export/PngExportTest.kt` | **NOVO** — 14 testes (Robolectric) |
| `app/build.gradle.kts` | `versionCode 4`, `versionName 0.3.1-fix-png-export` |
| `README.md` | Secção de exportação atualizada |

Nenhum ficheiro foi removido. Nenhuma alteração ao motor C++17/JNI, ao formato
`.osp`, a spritesheets/atlas ou ao workflow de CI.

## C — A correção, passo a passo (exatamente como pedida)

1. **Bitmap ARGB_8888 com as dimensões reais** — `PngExport.buildBitmapFromDocument(doc)`
   valida primeiro o buffer partilhado (`bufferValidationProblem()`), faz um
   snapshot único do buffer direto Kotlin↔C++ no executor de mutação (sem leituras
   rasgadas) e cria o bitmap com `doc.width × doc.height` reais. Conversão
   RGBA→ARGB: o buffer guarda uint32 `0xAARRGGBB` little-endian (bytes B,G,R,A em
   memória = layout ARGB_8888 do Android); `copyPixels()` devolve color ints ARGB e
   `setPixels()` faz o mapeamento — não há troca de canais, e isso está provado
   por testes de roundtrip exato (alpha 0/1/254/255 incluídos).
2. **Verificação do bitmap** — rejeita `Bitmap.createBitmap == null` e bitmap
   reciclado (`IllegalStateException`), antes e depois da construção.
3. **MediaStore com IS_PENDING** — `ContentValues` com `DISPLAY_NAME` (sanitizado
   e único; colisão → carimbo temporal), `MIME_TYPE=image/png`,
   `RELATIVE_PATH=Download/` e `IS_PENDING=1`; inserção em
   `MediaStore.Downloads.EXTERNAL_CONTENT_URI`; `contentResolver.openOutputStream(uri)`.
4. **Escrita** — dentro de `use { }`: `val ok = bitmap.compress(
   Bitmap.CompressFormat.PNG, 100, out)` → `out.flush()` → `close()` pelo `use{}`.
   Um `CountingOutputStream` mede os bytes reais escritos. `ok == false` vira
   `IOException` (nunca passa como sucesso).
5. **IS_PENDING=0** — só depois do conteúdo escrito e fechado: o ficheiro nunca
   é visível vazio. O nome final é lido de volta (providers podem renomear colisões).
6. **Sem exceções engolidas** — sucesso: Toast
   `PNG exportado: Download/<nome> — ok=true, <N> bytes`. Falha: a entrada
   pendente é **removida** (nunca fica um órfão 0 bytes em Download/) e Toast
   `Falha ao exportar PNG: <e.message> (ok=false)`. Instrumentação
   `PixelOnePerf/Export` regista dims/bytes/duração.

## D — Testes executados (resultados reais)

Comando: `./gradlew :app:testReleaseUnitTest` (JDK Temurin 17.0.20.1)

- **128 testes, 0 falhas, 0 erros** (inclui os 14 novos e todos os anteriores —
  motor 2D, histórico, `.osp`, repositório, spritesheet, atlas, roundtrip PNG,
  buffer partilhado).
- Novos (`PngExportTest`, Robolectric SDK 34):
  1. bitmap do buffer partilhado tem dimensões reais e píxeis exatos
  2. bitmap válido quando o documento é válido (validação interna corre)
  3. escrita devolve ok=true e bytes reais > 0 (assinatura PNG verificada)
  4. PNG escrito decodifica com as mesmas dimensões e cores opacas
  5. stream que falha nunca passa como sucesso e reporta o erro
  6. bitmap reciclado é rejeitado antes de comprimir
  7. CountingOutputStream conta bytes parciais e totais
  8. nome simples mantém-se ("z" → "z.png")
  9. colisão acrescenta carimbo temporal
  10. extensão .png não é duplicada (incl. ".PNG")
  11. caracteres perigosos são substituídos
  12. ContentValues em Q tem Download/ e IS_PENDING
  13. ContentValues pré-Q não tem colunas inexistentes
  14. roundtrip completo preserva bytes RGBA do buffer (IHDR verificado byte a byte)

## E — Compilação e CI

- Compilação local: variante release compilada por `testReleaseUnitTest` (Kotlin
  principal + testes) — BUILD SUCCESSFUL.
- CI GitHub Actions (run #9): testes unitários release + `assembleRelease`
  (inclui build nativo C++17) + validação `apksigner` + artefacto assinado.
- Nota honesta: o comportamento MediaStore/IS_PENDING/Toast não é exercitável em
  Robolectric (provider de Downloads) — a lógica está isolada no núcleo testável
  e a interação com o ContentResolver é fina e direta, seguindo literalmente a
  receita; validação final em dispositivo é sugerida abaixo.

## F — Como reproduzir a verificação no telemóvel

1. Instalar o APK `0.3.1-fix-png-export`.
2. Abrir Texture Studio → criar/abrir um projeto → desenhar.
3. Tocar no ícone Exportar PNG (não abre seletor): o Toast mostra
   `PNG exportado: Download/<nome>.png — ok=true, <N> bytes`.
4. Abrir Downloads: o PNG está lá com conteúdo (nunca 0 bytes).
5. (Opcional) Ativar modo avião/desmontar storage e tentar exportar: verá
   `Falha ao exportar PNG: ... (ok=false)` e **nenhum** ficheiro vazio.
