# Formato nativo de projeto do Pixel One — `.osp`

**Versão do formato: 1** (`OspCodec.FORMAT_VERSION`)

O `.osp` (*One Sprite Project*) é um contentor ZIP com duas entradas,
por esta ordem:

| # | Entrada | Conteúdo |
| --- | --- | --- |
| 1 | `manifest.json` | Metadados em JSON UTF-8 (máx. 64 KB) |
| 2 | `pixels.rgba` | Píxeis brutos, 4 bytes por píxel, ordem R,G,B,A |

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
- `formatVersion` — versão do formato (1). Uma versão **maior** que a
  suportada pela aplicação é rejeitada com a mensagem
  *"Formato .osp versão X não suportado…"* — os dados locais nunca são
  corrompidos. Versões menores são rejeitadas como suspeitas (a app nunca
  gravou versões antigas).
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
