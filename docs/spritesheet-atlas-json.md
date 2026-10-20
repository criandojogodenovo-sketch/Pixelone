# Esquemas JSON de exportação — Spritesheet e Atlas

Ambos os esquemas são versionados com `schemaVersion` e incluem as
dimensões da imagem gerada. Os metadados descrevem exatamente o PNG
exportado — nada é inventado (ex.: não existem campos de animação que o
utilizador não tenha definido).

## Spritesheet — `pixel-one-spritesheet`

Para quadros **regulares** (todos com as mesmas dimensões) numa grelha.

```json
{
  "schemaVersion": 1,
  "type": "pixel-one-spritesheet",
  "app": "Pixel One",
  "sheet": { "width": 36, "height": 26 },
  "frameWidth": 8,
  "frameHeight": 8,
  "columns": 3,
  "rows": 2,
  "spacing": 2,
  "margin": 4,
  "frameCount": 5,
  "frames": [
    { "index": 0, "name": "andar_0", "x": 4,  "y": 4,  "width": 8, "height": 8, "row": 0, "column": 0 },
    { "index": 1, "name": "andar_1", "x": 14, "y": 4,  "width": 8, "height": 8, "row": 0, "column": 1 },
    { "index": 2, "name": "andar_2", "x": 24, "y": 4,  "width": 8, "height": 8, "row": 0, "column": 2 },
    { "index": 3, "name": "andar_3", "x": 4,  "y": 14, "width": 8, "height": 8, "row": 1, "column": 0 },
    { "index": 4, "name": "andar_4", "x": 14, "y": 14, "width": 8, "height": 8, "row": 1, "column": 1 }
  ]
}
```

- `x`/`y` — canto superior esquerdo do quadro no PNG (píxeis).
- Posicionamento determinístico: quadro *i* →
  `column = i % columns`, `row = i / columns`;
  `x = margin + column × (frameWidth + spacing)`,
  `y = margin + row × (frameHeight + spacing)`.
- Modo automático de colunas: `columns = ceil(sqrt(n))`.
- **A ordem de entrada é preservada** — importante para animações; os
  nomes dos quadros são os dos ficheiros de origem.

## Atlas — `pixel-one-atlas`

Para imagens de **tamanhos diferentes** combinadas numa textura maior.

```json
{
  "schemaVersion": 1,
  "type": "pixel-one-atlas",
  "app": "Pixel One",
  "atlas": { "width": 16, "height": 8 },
  "regionCount": 2,
  "regions": [
    { "name": "heroi",   "x": 0, "y": 0, "width": 8, "height": 8 },
    { "name": "inimigo", "x": 9, "y": 0, "width": 4, "height": 4 }
  ]
}
```

- Empacotamento *shelf* determinístico: ordenação por altura desc →
  largura desc → nome asc; **sem rotação** (os dados originais de cada
  textura são preservados ao píxel).
- `atlas.height` é a altura **real usada** (compacta), não o limite máximo.
- Sobreposições são impossíveis por construção (e verificadas nos testes).
- Se as entradas não couberem nos limites definidos, a exportação é
  bloqueada com um erro explicativo (nome da textura, dimensões e limite).

## Limites de memória (ambos)

- Máximo por eixo: 4096 px.
- Máximo total: 8 388 608 píxeis (≈ 32 MB em RGBA) — seguro para um
  telemóvel de ~4 GB de RAM.
- Entradas individuais: máx. 2048 px por eixo e 4 194 304 píxeis.
- O fundo do PNG gerado é sempre transparente (0x00000000); o quadriculado
  das pré-visualizações é apenas visual.
