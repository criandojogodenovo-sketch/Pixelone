# Relatório — Pixel One · Fase 0 (Fundação Android)

- **Branch de desenvolvimento:** `develop` (a `main` permanece intocada, conforme a regra de execução)
- **Commits da fase:** `8fc4b06` (fundação Android) · `113deff` (workflow de CI)
- **Execução de CI de referência:** [run #39391616071](https://github.com/criandojogodenovo-sketch/Pixelone/actions/runs/39391616071) — `workflow_dispatch` em `develop`, conclusão **success**

## A. Implementado

1. **Estrutura do projeto Android nativo** — Gradle Kotlin DSL, AGP 8.7.3, Gradle 8.10.2 (wrapper versionado com jar da distribuição oficial), Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Navigation Compose 2.8.5, JDK 17, minSdk 26 / target & compileSdk 35.
2. **Tema e identidade visual** — preto/cinzento/laranja (`PixelOneTheme`, tema escuro fixo), tipografia monospace nos títulos, ícone adaptativo com motivo de píxeis (sem PNGs legados).
3. **Ecrã inicial** — marca "PIXEL ONE", cartões dos três espaços de trabalho (áreas de toque generosas), nota informativa honesta sobre o estado da versão.
4. **Navegação funcional** — `NavHost` com 4 rotas (home + 3 espaços de trabalho), botão de retorno em cada espaço, comportamento de retorno do sistema coerente com a pilha.
5. **Conteúdo explícito por espaço** — estado atual ("base de trabalho"/"planeado"), área de trabalho RESERVADA (marco visual do canvas futuro) e painel de funcionalidades planeadas; nada simula ferramentas inexistentes.
6. **Mobile-first** — layouts scrolláveis que se adaptam a retrato/paisagem, limite de largura 640dp em ecrãs grandes (sem barra lateral de desktop), edge-to-edge com system bars escuras e respeito pelos recortes.
7. **Modelo de projeto e repositório** — `PixelProject` (id, nome, `formatVersion`, carimbos temporais, validação de nome), `ProjectRepository` (contrato) e `InMemoryProjectRepository` (implementação em memória sincronizada).
8. **Testes unitários** — 9 testes do modelo e do repositório (versão de formato, unicidade de ids, validação de nome, criar/listar/consultar/apagar, ordem de criação).
9. **CI de release assinado** — `.github/workflows/android-release.yml`: verifica secrets (falha cedo com mensagem explicativa se faltarem), decodifica keystore para `RUNNER_TEMP` (chmod 600, removido no fim), corre testes + `:app:assembleRelease`, valida com `apksigner verify --verbose --print-certs`, regista SHA-256 no resumo da execução e publica artefacto `Pixel-One-Release-APK` (retenção 30 dias).
10. **Documentação** — README (arquitetura, decisões, build, assinatura, descarga, segurança) e este relatório.
11. **Configuração de secrets** — os 4 secrets de assinatura estão configurados no repositório, com keystore de release persistente entregue ao proprietário fora do repositório.

## B. Testado com evidências

| Verificação | Evidência |
| --- | --- |
| Build release termina com sucesso no GitHub Actions | Run [#39391616071](https://github.com/criandojogodenovo-sketch/Pixelone/actions/runs/39391616071) — conclusão `success` (execução anterior `39391056778` falhou por senha de keystore desalinhada durante a configuração inicial; corrigida — ver D) |
| Testes unitários | `:app:testReleaseUnitTest` executado no mesmo job antes de `assembleRelease`; 9 testes, sem falhas (job só avança em caso de sucesso) |
| Assinatura confirmada pelo apksigner | `apksigner verify --verbose --print-certs`: **Verifies**; esquema v2 válido; certificado `CN=Pixel One Release, ..., C=AO`; SHA-256 do certificado `63c64623d74c2fa817d6a633814faf8c6d24ff9cfd0748c9d6702d7bc1c12507` — idêntico ao fingerprint do keystore entregue ao proprietário |
| APK existe e não está vazio | `PixelOne-0.1.0-fase0-release.apk` — 6.753.307 bytes (guarda mínima de 100 KB aplicada no workflow) |
| SHA-256 registado | `cd8dd51f520872847f1b94b9b371b7564175706882f113e5c8ec82100492ea53` (verificado localmente contra `SHA256SUMS.txt` do artefacto: OK) |
| Artefacto corresponde à variante release | caminho `app/build/outputs/apk/release/` + `versionName=0.1.0-fase0` + certificado de release (não debug) |
| Artefacto descarregável | `Pixel-One-Release-APK` (6.368.338 bytes comprimidos, não expirado) + Release v0.1.0-fase0 com assets públicos |
| Gradle Wrapper autêntico | `gradle/actions/wrapper-validation@v4` passou; jar gerado pela distribuição oficial 8.10.2 |

**Não foi testado em dispositivo físico/emulador** — não existe dispositivo Android acessível a este ambiente. A instalação foi deixada como passo documentado para o proprietário.

## C. Ainda não implementado (planeado, não concluído)

- Editor de pixel art (canvas, pincéis, paletas, camadas, desfazer/refazer) — Fase 1.
- Persistência real de projetos (armazenamento privado + formato `.osp`) e UI de gestão de projetos — Fase 2.
- Renderer 3D com OpenGL ES, importação de modelos e mapeamento UV — Fase 3.
- Construção geométrica assistida 2D-para-3D — Fase 4.
- C++/JNI — nenhum código nativo nesta fase (sem vantagem técnica demonstrável).
- Minificação R8, distribuição por Play Store, release automática por tag.

## D. Bloqueios e limitações

- **Corrigido durante a fase:** a primeira configuração dos secrets ficou desalinhada (a execução interrompida a meio gravou o keystore antes da senha). Correção: keystore regenerado com a senha gravada ANTES de qualquer chamada à API; nenhum APK tinha sido publicado com a chave anterior, pelo que não há impacto na identidade de assinatura do produto — a chave persistente válida é a atual (fingerprint em B).
- **Instalação em dispositivo não verificada** (sem dispositivo acessível) — o APK assinado está pronto para sideload; instruções no README.
- **Projetos em memória** — fechar a aplicação perde os projetos criados por código; é o comportamento declarado da Fase 0, sem serialização prematura.
- **Artefacto do Actions expira** (30 dias); a Release v0.1.0-fase0 mantém o APK disponível permanentemente.

## E. Ficheiros criados/alterados

```
.github/workflows/android-release.yml
README.md
.gitignore
build.gradle.kts
settings.gradle.kts
gradle.properties
gradlew · gradlew.bat · gradle/wrapper/gradle-wrapper.jar · gradle/wrapper/gradle-wrapper.properties
app/build.gradle.kts
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/values/{strings,colors,themes}.xml
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml · ic_launcher_round.xml
app/src/main/java/com/pixelone/studio/MainActivity.kt
app/src/main/java/com/pixelone/studio/navigation/PixelOneApp.kt
app/src/main/java/com/pixelone/studio/ui/theme/{Color,Type,Theme}.kt
app/src/main/java/com/pixelone/studio/ui/components/{PixelAccentRow,StatusBadge,SectionTitle,ReservedCanvasArea,PlannedPanel,InfoNote,WorkspaceCard,StudioScreenScaffold}.kt
app/src/main/java/com/pixelone/studio/ui/home/HomeScreen.kt
app/src/main/java/com/pixelone/studio/ui/texture/TextureStudioScreen.kt
app/src/main/java/com/pixelone/studio/ui/texture3d/Texture3DStudioScreen.kt
app/src/main/java/com/pixelone/studio/ui/studio2dto3d/Studio2Dto3DScreen.kt
app/src/main/java/com/pixelone/studio/project/{PixelProject,ProjectRepository,InMemoryProjectRepository}.kt
app/src/test/java/com/pixelone/studio/project/{PixelProjectTest,ProjectRepositoryTest}.kt
docs/relatorio-fase-0.md
```

Sem segredos em nenhum ficheiro versionado (`.gitignore` bloqueia `*.keystore`/`*.jks`).

## F. Instruções para reproduzir o build

**CI (caminho canónico):** Actions → `android-release` → Run workflow (branch `develop`), ou qualquer push para `develop` (exclui alterações só de markdown). Requisitos: os 4 secrets já configurados.

**Local (JDK 17 + Android SDK 35):**

```bash
./gradlew :app:testReleaseUnitTest :app:assembleRelease
# assinatura local (opcional): exportar PIXELONE_KEYSTORE_PATH/PASSWORD/ALIAS/KEY_PASSWORD
```

**Descarga do APK:** artefacto `Pixel-One-Release-APK` na execução, ou release permanente `v0.1.0-fase0` (link direto no telemóvel).

## G. Recomendações para iniciar a Fase 1

1. Congelar a base: criar tag de fundação (ex.: `v0.1.0-fase0` já criada) e proteger `main`/`develop`.
2. Implementar o canvas do Texture Studio com Compose (`Canvas` + gestos de pan/zoom) e a grelha de píxeis; manter o padrão de áreas reservadas desaparecendo à medida que as ferramentas chegam.
3. Definir cedo o esboço do formato `.osp` (JSON ou binário versionado com `formatVersion`) para o modelo já existente — evita retrabalho no repositório de dados.
4. Adicionar testes de UI básicos (compose-ui-test) e benchmark do desempenho de desenho em dispositivos modestos.
5. Avaliar minificação R8 antes de publicar APKs maiores.
6. Considerar publicação automática de Release por tag no workflow (necessita `contents: write` num job dedicado).
