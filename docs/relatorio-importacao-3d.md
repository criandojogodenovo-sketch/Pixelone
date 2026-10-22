# Relatório — Correção da importação de modelos 3D ("Formato de modelo não suportado")

**Branch:** `fix/missao-ui-selecao-texturas` · **Commit:** `0d92d06` · **Data:** 2026-10-22

## 1. Diagnóstico real (código auditado, nada assumido)

O erro "Formato de modelo não suportado. O Pixel One importa glTF 2.0 (.gltf) e GLB (.glb)"
é lançado em **um único ponto**: `GltfParser.parse()` (linha do `throw`, ramo de JSON solto).

Auditoria do caminho de importação (`Texture3DStudioScreen` → `Studio3DViewModel.importModelFromUri`
→ `GltfParser.parse(bytes)`):

| Ponto verificado | Estado ANTES | Conclusão |
|---|---|---|
| Deteção do formato | JÁ era por conteúdo (`isGlb` lê a magia `0x46546C67`; senão tenta JSON) | Base correta, mas com lacunas (BOM) e sem diagnóstico |
| Nome do ficheiro | JÁ vinha de `OpenableColumns.DISPLAY_NAME` | Conforme o pedido — mantido e generalizado |
| MIME do seletor | JÁ era `launch(arrayOf("*/*"))` | Conforme o pedido — mantido (agora `OpenMultipleDocuments`) |
| `.gltf` + `.bin` externo | Rejeitado com mensagem genérica; impossível selecionar o `.bin` | **Corrigido** |
| BOM UTF-8 (`EF BB BF`) no `.gltf` | `trimStart()` não remove `\uFEFF` → JSON com BOM era recusado **exatamente com o erro reportado** | **Bug real corrigido** |
| Mensagem de erro | Frase genérica, sem dados do ficheiro | **Agora com diagnóstico real** |
| Registo (log) | Nenhum | **`Log.d("PixelOneImport", …)` em todo o caminho** |

**Causas prováveis do erro reportado pelo utilizador** (todas agora visíveis na mensagem/log):
`.gltf` gravado com BOM; ficheiro que não é o que parece (ZIP de loja, HTML de erro de
descarga — verificado pelos primeiros bytes); ou `.gltf` com `.bin` externo que falhava
mais à frente. Com o diagnóstico, o motivo real aparece na própria mensagem.

## 2. Correções implementadas (mapeadas ao pedido)

1. **Nome real do ficheiro** — `OpenableColumns.DISPLAY_NAME` (já existia; extraído para
   `displayNameOf()` e usado também para multi-seleção e diagnóstico). Nenhuma leitura de
   extensão do URI.
2. **Deteção por conteúdo** — `isGlb()` (magia `glTF`) + novo `looksLikeModelJson()`
   (JSON começado por `{`, **BOM UTF-8 tolerado**) + `looksLikeModel()`. A extensão e o
   MIME são irrelevantes em todo o caminho.
3. **Seletor sem esconder ficheiros** — `OpenMultipleDocuments` com `arrayOf("*/*")`
   (o contrato define `EXTRA_MIME_TYPES` a partir da lista; com `*/*` nada é filtrado).
   Seleção múltipla permite levar o `.gltf` **e** o `.bin` de uma vez.
4. **`.gltf` com `.bin` externo** — os ficheiros selecionados além do modelo ficam
   disponíveis como recursos externos (`resolveExternal()`: correspondência por nome real,
   exata → basename → basename sem maiúsculas). Buffers **e imagens** externas são
   resolvidos. Sem o `.bin`, a mensagem orienta claramente: exportar `.glb` autocontido
   **ou** selecionar o `.gltf` junto com o `.bin`.
5. **Erro com motivo real** — novo `GltfParser.diagnose(bytes, fileName)`:
   nome, tamanho e primeiros 8 bytes em hex + ASCII (`ficheiro: X.glb · 12345 bytes ·
   primeiros bytes: [50 4B 03 04 …] ('PK··')`). Incluído nos erros de formato
   desconhecido, de leitura falhada e de seleção sem modelo.
6. **Log do caminho de importação** — `Log.d` com tag **`PixelOneImport`**: URI(s)
   recebidos, por ficheiro lido (nome, tamanho, cabeçalho, veredito), modelo principal e
   ficheiros de apoio, resultado do parse (malhas/materiais/triângulos/imagens/ms) e
   falhas com exceção. Visível no Logcat e nos logs das Actions.

## 3. Ficheiros alterados

| Ficheiro | Mudança |
|---|---|
| `app/src/main/java/com/pixelone/studio/studio3d/GltfParser.kt` | BOM tolerado; `looksLikeModelJson`/`looksLikeModel`/`diagnose`; `parse(bytes, fileName, externalFiles)`; resolução de buffers/imagens externas; mensagens com diagnóstico |
| `app/src/main/java/com/pixelone/studio/studio3d/Studio3DViewModel.kt` | `importModelFromUris()` (multi-ficheiro, deteção por conteúdo, escolha do modelo principal); `displayNameOf`/`readModelBytes` extraídos; `Log.d PixelOneImport`; erros com diagnóstico |
| `app/src/main/java/com/pixelone/studio/ui/texture3d/Texture3DStudioScreen.kt` | `OpenDocument` → `OpenMultipleDocuments` (`*/*`), encaminha para `importModelFromUris` |
| `app/src/test/java/com/pixelone/studio/studio3d/GltfParserTest.kt` | 8 testes novos (ver §4) |

Sem alterações em C++/JNI, sem novas dependências, sem remoção de funcionalidades.

## 4. Testes (291 unitários, 0 falhas)

Novos em `GltfParserTest` (ficheiros construídos programaticamente, sem fixtures):

- `deteção por conteúdo ignora extensão e mime` — GLB/JSON detetados; ZIP e HTML recusados;
- `gltf com BOM UTF-8 parseia` — regressão do bug real do BOM;
- `conteúdo desconhecido mostra diagnóstico real no erro` — nome + hex + tamanho na mensagem;
- `buffer externo é resolvido com o bin selecionado` — parse completo com `.bin` externo;
- `buffer externo resolve por basename ignorando caminho e maiúsculas` — `./dados/MODELO.BIN` ↔ `modelo.bin`;
- `buffer externo sem bin orienta para glb ou seleção conjunta` — mensagem com `.glb`, `.bin` e o URI em falta;
- `imagem externa é resolvida com o ficheiro selecionado` — imagem externa + mime inferido;
- `nenhum modelo na seleção devolve diagnóstico do primeiro ficheiro`.

Testes pré-existentes mantidos verdes (incluindo os que garantem a rejeição de `.gltf`
autocontido malformado e das mensagens anteriores — frases-chave preservadas).

## 5. Build e validação

- `:app:testReleaseUnitTest` — **291 testes, 0 falhas** (283 + 8 novos);
- `:app:assembleDebug` — OK (APK 12,0 MB);
- `assembleRelease` assinado pelo **CI** (keystore real dos secrets) — run
  `39728876624` do workflow `android-release.yml`, validado com `apksigner`;
- APKs publicados na própria branch em `apk/` (debug + release) com `SHA256SUMS`.

## 6. Limitações honestas

- **Testes instrumentados/emulador não executados localmente** (sem dispositivo/emulador
  no ambiente); o CI corre-os no emulador API 30 em paralelo ao release.
- `.bin` externo: a correspondência é pelo nome do ficheiro (exata → basename →
  basename sem maiúsculas). `.gltf` cujo `.bin` tenha outro nome precisa de ambos
  selecionados com os nomes coerentes com o JSON.
- `data:` URI sem base64 continua não suportado (spec glTF permite outras codificações
  raramente usadas).
- O diagnóstico mostra os primeiros 8 bytes — suficiente para identificar GLB/JSON/ZIP/
  HTML sem expor conteúdo sensível.
