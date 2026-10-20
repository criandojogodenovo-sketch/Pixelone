# Pixel One

Aplicação Android nativa para criação de pixel art, texturas 2D e aplicação
de texturas a modelos 3D, com construção geométrica assistida 2D-para-3D.

- **Plataforma:** Android nativo (não é uma aplicação web; sem Unity ou outros motores)
- **Tecnologias:** Kotlin, Jetpack Compose; OpenGL ES nas fases 3D futuras; C++ via JNI apenas quando existir vantagem técnica demonstrável
- **Identidade visual:** preto, cinzento e laranja
- **Modelo:** mobile-first, offline nas funções centrais, sem backend, contas, anúncios ou pagamentos
- **Nota de rebranding:** todas as referências anteriores a "One Sprite Studio" passam a designar-se **Pixel One**

## Estado do projeto — Correções de performance, canvas e UX (0.3.0)

Correções implementadas nesta fase (sobre as Fases 1+2), em
`fix/texture-studio-performance`:

- **Motor C++17/JNI (`libpixelone_engine`)**: um traço completo (lote de
  pontos do gesto) é processado em UMA chamada JNI com Bresenham, brocha,
  borracha e balde; devolve deltas esparsos (índice, cor antiga, cor nova),
  o dirty rect e a duração interna (`std::chrono::steady_clock`). Fallback
  Kotlin idêntico (usado em JVM/testes e se a lib não carregar).
- **Buffer direto partilhado**: os píxeis vivem num único
  `ByteBuffer.allocateDirect` (`PixelDocument.sharedBuffer`) — fonte de
  verdade única, vista `IntBuffer` persistente com acessos absolutos, lido
  pelo C++ via `GetDirectBufferAddress` por chamada (sem ponteiros
  retidos). Formato documentado em `docs/buffer-formato.md`.
- **Sem trabalho proporcional ao documento por toque**: durante o gesto o
  traço é um PREVIEW no canvas (não modifica píxeis); no fim, o lote vai ao
  motor e o bitmap de apresentação é atualizado APENAS na região suja com
  `Bitmap.setPixels` — nunca `setPixel` por píxel, nunca cópia integral por
  evento, nunca recriação do bitmap.
- **Histórico por deltas esparsos** com orçamento de memória (32 MB);
  um traço/fill = UMA entrada; undo/redo aplicam deltas no executor serial
  e atualizam o bitmap regionalmente.
- **Threading**: executor serial `pixelone-doc-mutation` para TODAS as
  mutações do documento (traço/fill/undo/redo/snapshots); gravação,
  exportação e autosave operam sobre snapshots (sem leituras rasgadas);
  atualizações visuais na main thread.
- **Canvas aparece imediatamente** ao criar/abrir/importar/restaurar:
  `document`/`imageBitmap` são estado Compose observável; viewport chega via
  `onSizeChanged` (sem escrita de estado durante o draw); fit-to-screen
  centrado quando o viewport tem dimensões válidas (pendingFit).
- **Entrada do Texture Studio clara**: ecrã vazio com 3 ações principais
  (Novo projeto, Abrir recente, Novo spritesheet), presets 16/32/64/128 +
  personalizado, ação Guardar na barra superior, rótulos nas ferramentas,
  barra inferior com scroll horizontal nas duas linhas, paleta com cor
  primária/secundária (toque longo define a secundária; botão de troca) e
  grelha com Path em cache.
- **Instrumentação**: `PixelOnePerf/Touch`, `/JNI`, `/Render` — uma linha
  por operação (dimensões, pontos, píxeis alterados, dirty rect, durações,
  caminho nativo/fallback).
- 114 testes unitários JVM (inclui buffer direto, traço em lote, deltas,
  estado do ViewModel com Robolectric e benchmark de código) + testes
  instrumentados (androidTest) que provam o buffer partilhado Kotlin↔C++ e
  a visibilidade do canvas em emulador/dispositivo.

Ainda **não** implementado (planeado): renderer 3D/OpenGL ES, mapeamento UV,
construção 2D-para-3D, camadas, seleção/recorte.

### Documentação técnica adicional

- `docs/buffer-formato.md` — formato binário do buffer partilhado
  (endianness, canais, stride, regras de vida útil e validação).
- `docs/formato-osp.md` — especificação completa do formato nativo `.osp`.
- `docs/spritesheet-atlas-json.md` — esquemas JSON dos metadados de exportação.
- `docs/relatorio-fase-1-2.md` — relatório A–K das Fases 1+2.
- `docs/relatorio-correcoes-performance.md` — relatório A–J desta correção.

## Estado anterior — Fases 1+2 (editor de pixel art, persistência e exportação)

Implementado nesta fase (sobre a fundação da Fase 0):

- **Editor de pixel art real** no Texture Studio: canvas com zoom por pinça
  (0,05×–64×) e pan, fundo quadriculado de transparência (nunca exportado),
  grelha de píxeis opcional em zoom alto, renderização nítida sem interpolação.
- **Ferramentas**: lápis (linhas contínuas Bresenham), borracha (transparência
  real), balde de preenchimento com tolerância por canal, conta-gotas,
  pincel 1–8 px, paleta de cores do projeto e seletor RGBA (sliders + hex).
- **Estratégia de gestos documentada**: 1 dedo desenha; 2 dedos movem e fazem
  zoom; um segundo dedo durante um traço CANCELA o traço (sem marcas
  acidentais); botões dedicados de zoom/ajustar/grelha.
- **Desfazer/refazer** com diferenças regionais: um traço contínuo inteiro é
  UMA entrada do histórico; orçamento de memória de 32 MB com libertação
  automática das entradas mais antigas; nova edição limpa o redo.
- **Persistência `.osp`** (ZIP versionado, v1): guardar/abir/continuar sem
  perda de dados, transparência preservada, gravação atómica (falha de
  escrita nunca destrói o ficheiro anterior), validação de versões
  desconhecidas e rejeição controlada de ficheiros inválidos.
- **Exportação PNG** direta para `Download/` (Android 10+) via MediaStore com
  ciclo `IS_PENDING` — o ficheiro só se torna visível com o conteúdo escrito
  (nunca mais um PNG 0 bytes), Toast final com `ok` e o tamanho real em bytes,
  e remoção automática da entrada pendente em caso de falha; valores RGBA
  exatos (bitmap não premultiplicado), transparência preservada; grelha e UI
  nunca entram no ficheiro. Em Android < 10 mantém-se o seletor SAF com o
  mesmo núcleo testável e feedback obrigatório.
- **Spritesheets** (quadros regulares + JSON por quadro) e **Atlas de
  texturas** (regiões de tamanhos diferentes + JSON por região), ambos com
  posicionamento determinístico, pré-visualização real e limites de memória.
- **Autosave** no fundo da app (ON_STOP) com proposta de restauro.
- 78 testes unitários JVM (motor 2D, histórico, .osp, repositório,
  spritesheet, atlas, roundtrip PNG real via Robolectric).

Ainda **não** implementado (planeado): renderer 3D/OpenGL ES, mapeamento UV,
construção 2D-para-3D, camadas, seleção/recorte, testes em dispositivo físico.

### Documentação técnica adicional

- `docs/formato-osp.md` — especificação completa do formato nativo `.osp`.
- `docs/spritesheet-atlas-json.md` — esquemas JSON dos metadados de exportação.
- `docs/relatorio-fase-1-2.md` — relatório A–K das Fases 1+2.

## Estado anterior — Fase 0 (fundação)

Implementado nesta fase:

- Estrutura Gradle/Kotlin/Compose completa e compilável.
- Tema escuro fixo com a identidade preto/cinzento/laranja.
- Ecrã inicial com acesso aos três espaços de trabalho e navegação funcional.
- Conteúdo explícito em cada espaço, identificando o que existe e o que é planeado (nada simula ferramentas inexistentes).
- Modelo mínimo de projeto (id, nome, versão de formato) e repositório com implementação em memória.
- Testes unitários da lógica existente.
- Workflow GitHub Actions que compila, assina, valida e publica o APK release.

Ainda **não** implementado (planeado): editor de pixel art, renderer 3D/OpenGL ES, mapeamento UV, construção 2D-para-3D, persistência em ficheiro (formato `.osp`) e UI de criação/gestão de projetos.

## Espaços de trabalho

1. **Texture Studio** — **editor de pixel art funcional** (Fases 1+2): desenho,
   histórico, projetos `.osp`, exportação PNG, spritesheets e atlas.
2. **3D Texture Studio** — aplicação de texturas a modelos 3D através de UVs
   (planeado; o ecrã indica-o explicitamente).
3. **2D-to-3D Studio** — construção geométrica assistida por desenhos e
   referências ortográficas (planeado; o ecrã indica-o explicitamente).

## Decisões técnicas

| Tema | Decisão | Justificação |
| --- | --- | --- |
| UI | Jetpack Compose | Projeto novo, UI declarativa oficial do Google, adaptação natural a mobile-first. Não se mistura com Android Views. |
| minSdk / targetSdk | 26 / 35 | minSdk 26 cobre a grande maioria dos telemóveis ativos, permite ícones adaptativos sem PNGs legados e prepara OpenGL ES 3.0. |
| OpenGL ES | Fase futura | Reservado para a visualização 3D; nada de OpenGL nesta fase. |
| C++ / JNI | Só com vantagem demonstrável | Nenhum JNI nas Fases 0–2. |
| Motor 2D | Kotlin puro (IntArray ARGB) | Testável em JVM sem dispositivo; separado do Bitmap/renderização. |
| Mistura de cor | Substituição direta do píxel (sem blending alfa) | Padrão de editores de pixel art; cores exatas e previsíveis. |
| Ícones | Vetoriais próprios (`PixelIcons`) | Evita material-icons-extended (~30 MB) com a minificação desligada. |
| Armazenamento | Privado da app (`filesDir`) | Sem permissões abrangentes. O Storage Access Framework fica para exportar/importar ficheiros escolhidos pelo utilizador. |
| Formato `.osp` | ZIP v1 (manifest.json + pixels.rgba) | Versionado, sem perdas, seguro (lista branca de entradas, anti zip-bomb); ver `docs/formato-osp.md`. |
| Tema | Escuro fixo | Identidade do produto e precisão de cor num editor de pixel art. |
| Minificação R8 | Desativada na Fase 0 | Base pequena; reavaliar quando o código crescer. |

## Ferramentas e versões

| Ferramenta | Versão |
| --- | --- |
| JDK | 17 (CI usa Temurin 17) |
| Android Gradle Plugin | 8.7.3 |
| Gradle (wrapper) | 8.10.2 |
| Kotlin | 2.0.21 (+ plugin oficial Compose) |
| Compose BOM | 2024.12.01 |
| Navigation Compose | 2.8.5 |
| compileSdk / targetSdk | 35 |

## Compilar e testar

Pré-requisitos: JDK 17 e Android SDK (API 35 + build-tools) — ou simplesmente abrir o projeto no Android Studio.

```bash
# Build de debug
./gradlew :app:assembleDebug

# Testes unitários (78 testes: motor, histórico, .osp, exportação, PNG)
./gradlew :app:testDebugUnitTest

# Instalar num dispositivo/emulador ligado (opcional)
adb install app/build/outputs/apk/debug/app-debug.apk
```

## APK Release assinado via GitHub Actions

O workflow `.github/workflows/android-release.yml`:

1. Corre em `push` para `develop` e manualmente via `Run workflow`.
2. Verifica a presença dos secrets de assinatura — **falta de secrets interrompe o workflow** (nunca publica APK release não assinado).
3. Decodifica o keystore para um ficheiro temporário (`RUNNER_TEMP`, chmod 600) e remove-o no fim.
4. Executa os testes unitários e compila `:app:assembleRelease`.
5. Valida com `apksigner verify --verbose --print-certs` e regista o SHA-256 no resumo da execução.
6. Publica o artefacto **Pixel-One-Release-APK** (APK + `SHA256SUMS.txt` + `apksigner-report.txt`), retenção de 30 dias.

### Configurar os secrets (primeira vez)

Gerar um keystore de release localmente (uma única vez — a chave deve ser
persistente para preservar a identidade de assinatura entre versões):

```bash
keytool -genkeypair -v \
  -keystore pixel-one-release.keystore \
  -storetype PKCS12 \
  -alias pixel-one-release \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Pixel One Release, OU=Pixel One Studio, O=Pixel One, L=Luanda, ST=Luanda, C=AO"
```

Codificar e definir os secrets (nunca commitar o keystore — o `.gitignore`
já bloqueia `*.keystore`):

```bash
base64 -w0 pixel-one-release.keystore > keystore.base64

gh secret set ANDROID_KEYSTORE_BASE64 < keystore.base64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS -b"pixel-one-release"
gh secret set ANDROID_KEY_PASSWORD
```

Sem `gh` CLI, usar a UI: **Settings → Secrets and variables → Actions → New repository secret**, criando os quatro secrets com os nomes acima. Apagar os ficheiros temporários (`keystore.base64`) após a configuração.

**Importante:** guardar o keystore e a senha num cofre seguro. Perder o
keystore significa perder a identidade de assinatura — atualizações futuras
exigem a mesma chave.

### Executar e descarregar o APK

1. Abrir **Actions** no GitHub e escolher o workflow **android-release**.
2. Correr manualmente com **Run workflow** (branch `develop`) ou aguardar um push para `develop`.
3. Na execução concluída com sucesso, na secção **Artifacts**, descarregar **Pixel-One-Release-APK**.
4. Extrair o ZIP: contém o APK, `SHA256SUMS.txt` e o relatório do `apksigner`.
5. Verificar a integridade (opcional): `sha256sum -c SHA256SUMS.txt`.
6. Transferir o APK para o telemóvel e instalar (é necessário autorizar "instalar aplicações desconhecidas" no Android).

## Segurança

- O keystore vive apenas nos secrets do GitHub; nunca no repositório, nos logs ou em artefactos.
- O workflow usa `permissions: contents: read` + `actions: write` (caches) e não corre em pull requests de forks.
- Senhas e conteúdo do keystore nunca são impressos nos logs.
- O artefacto publicado contém apenas o APK e relatórios de validação.

## Estrutura do repositório

```
app/src/main/java/com/pixelone/studio/
  MainActivity.kt            # ponto de entrada Compose
  navigation/PixelOneApp.kt  # grafo de navegação (4 rotas)
  pixel/                     # MOTOR 2D (Kotlin puro): PixelDocument, PixelEngine,
                             # HistoryManager — testável em JVM
  osp/                       # formato .osp: OspCodec, MiniJson, FileSystemProjectRepository
  export/                    # SpriteSheetPlanner, AtlasPacker (determinísticos)
  editor/                    # EditorViewModel (estado do editor), PixelIcons
  ui/editor/                 # PixelCanvas (gestos/render), diálogos, spritesheet/atlas
  ui/texture/                # TextureStudioScreen (editor completo)
  ui/theme|components|home|texture3d|studio2dto3d/
  project/                   # PixelProject + ProjectRepository (contrato)
app/src/test/                # 78 testes unitários
.github/workflows/android-release.yml  # CI de release assinado
docs/formato-osp.md          # especificação do .osp
docs/spritesheet-atlas-json.md  # esquemas JSON de exportação
docs/relatorio-fase-0.md     # relatório da Fase 0
docs/relatorio-fase-1-2.md   # relatório das Fases 1+2
```

## Limitações conhecidas (Fases 1+2)

- Não houve ainda validação em dispositivo físico (sem aparelho acessível
  durante o desenvolvimento); os testes são JVM (incl. Robolectric) e a
  compilação/assinatura é validada no GitHub Actions.
- Sem camadas, seleção/recorte, importação de referências ou animação no editor.
- O "Guardar como" via SAF escreve uma cópia; se a escrita falhar a meio,
  o destino pode ficar parcial (o projeto interno permanece intacto).
- 3D e conversão 2D-para-3D não existem ainda; os ecrãs indicam-no explicitamente.
- Instalação em dispositivo físico requer autorização de fontes desconhecidas (sideload).

## Próximas fases (indicativo)

1. **Fase 3** — 3D Texture Studio: OpenGL ES, importação de modelos e mapeamento UV.
2. **Fase 4** — 2D-to-3D Studio: construção geométrica assistida com referências ortográficas.
3. Fase 5 (sugestões no relatório): camadas, seleção/recorte, animação, testes em dispositivo.
