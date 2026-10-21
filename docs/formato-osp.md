# Formato nativo de projeto do Pixel One — `.osp`

**Versão do formato: 2** (`OspCodec.FORMAT_VERSION` — v2 adiciona a secção
opcional do 3D Texture Studio; projetos v1 continuam a abrir sem alterações)

O `.osp` (*One Sprite Project*) é um contentor ZIP com até três entradas,
por esta ordem:

| # | Entrada | Conteúdo |
| --- | --- | --- |
| 1 | `manifest.json` | Metadados em JSON UTF-8 (máx. 64 KB) |
| 2 | `pixels.rgba` | Píxeis brutos, 4 bytes por píxel, ordem R,G,B,A |
| 3 | `studio3d.json` | **Opcional (v2)** — referências do 3D Texture Studio (máx. 64 KB) |

## manifest.json

```json
{
  "format": "pixel-one-osp",
  "formatVersion": 1,
  "app": "Pixel One",
  "name": "meu-sprite",
  "width": 64,
  "height": 64,
  "createdAtEpochMs": 1730000000000,
  "updatedAtEpochMs": 1730000100000,
  "palette": ["#FF0A0A0A", "#FFF57C00", "#80FF57C0"]
}
```

- `format` — identificador fixo `"pixel-one-osp"`. Valores diferentes
  rejeitam o ficheiro com erro controlado.
- `formatVersion` — versão do formato (2 desde a Fase 3). Uma versão
  **maior** que a suportada pela aplicação é rejeitada com a mensagem
  *"Formato .osp versão X não suportado…"* — os dados locais nunca são
  corrompidos. A versão 1 (Fases 0–2, sem secção 3D) abre normalmente —
  compatibilidade para trás garantida.
- `palette` — opcional; cores em hex `#AARRGGBB` (alpha incluído).

## pixels.rgba

- Comprimento obrigatório: `width × height × 4` bytes (validado na leitura).
- Ordem row-major: píxel (x, y) em `4 × (y × width + x)`.
- Canais na ordem **R, G, B, A** — valores **não premultiplicados**;
  `A = 0` é transparência total, `A = 255` é opaco.

## Regras de leitura (segurança)

- Lista branca: apenas as duas entradas acima; qualquer entrada extra
  torna o ficheiro inválido (máximo 16 entradas verificadas).
- Não há extração para o sistema de ficheiros — leitura in-memory apenas;
  *path traversal* é impossível por construção.
- `manifest.json` ≤ 64 KB; `pixels.rgba` tem de medir exatamente
  `width×height×4` (impede *zip bombs* e alocações excessivas).

## Regras de escrita (atomicidade)

- Projetos internos são gravados em `<id>.osp.tmp`, relidos e validados
  (roundtrip completo dos píxeis) e só então renomeados para o ficheiro
  final (move atómico quando disponível). Uma falha de escrita/disco cheio
  **nunca** destrói o ficheiro anterior.
- "Guardar como" (SAF) serializa integralmente antes de abrir o stream de
  destino; o projeto interno permanece intacto em caso de falha.

## Comportamento com versão desconhecida

Um `.osp` com `formatVersion > 1` (ex.: gravado por uma futura versão da
app) é rejeitado de forma controlada: o utilizador vê a mensagem com a
versão encontrada e a suportada; nada é parcialmente lido nem gravado.
Migrações futuras devem acrescentar caminhos de atualização versionados
sem reutilizar este comportamento de rejeição.


## studio3d.json (v2, opcional)

Referências do 3D Texture Studio — **nunca bytes de modelo** (o GLB fica
fora do ZIP para não inflacionar projetos; guarda-se a identificação para
pedir reimportação quando necessário). Schema `schemaVersion: 1`:

```json
{
  "schemaVersion": 1,
  "model": {
    "kind": "IMPORTED",
    "fileName": "cena.glb",
    "fileBytes": 123456,
    "sha256": "…",
    "triangles": 1200,
    "meshes": 2,
    "materials": 2,
    "hasUv": true
  },
  "bindings": [[0, "DOCUMENT"], [1, "EMBEDDED"]],
  "settings": { "sampling": "NEAREST", "showUv": true, "mode": "THREE_D" },
  "camera": { "yaw": 35.0, "pitch": 25.0, "distance": 3.2, "tx": 0.0, "ty": 0.0, "tz": 0.0 }
}
```

- `model.kind` — `DEMO` (geometria de demonstração) | `IMPORTED` | `NONE`.
  O modelo importado NÃO é reembutido: ao reabrir, a app mantém as
  definições e pede a reimportação do `.glb`.
- `bindings` — por índice de material: `DOCUMENT` (pixels.rgba do próprio
  projeto é a textura) | `EMBEDDED` (textura embutida no GLB) | `NONE`
  (cor sólida `baseColorFactor`).
- `settings.mode` — `THREE_D` | `UV` | `SPLIT` (só restaurado se o modelo
  tiver UV).
- A entrada é escrita apenas quando existe estado 3D; projetos só 2D
  continuam a ser gravados/abríveis exatamente como antes (v1 incluídos).
