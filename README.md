# Pixel One

Aplicação Android nativa para criação de pixel art, texturas 2D e aplicação
de texturas a modelos 3D, com construção geométrica assistida 2D-para-3D.

- **Plataforma:** Android nativo (não é uma aplicação web; sem Unity ou outros motores)
- **Tecnologias:** Kotlin, Jetpack Compose; OpenGL ES nas fases 3D futuras; C++ via JNI apenas quando existir vantagem técnica demonstrável
- **Identidade visual:** preto, cinzento e laranja
- **Modelo:** mobile-first, offline nas funções centrais, sem backend, contas, anúncios ou pagamentos
- **Nota de rebranding:** todas as referências anteriores a "One Sprite Studio" passam a designar-se **Pixel One**

## Estado do projeto — Fase 0 (fundação)

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

1. **Texture Studio** — desenho de pixel art e criação de texturas 2D.
2. **3D Texture Studio** — aplicação de texturas a modelos 3D através de UVs.
3. **2D-to-3D Studio** — construção geométrica assistida por desenhos e referências ortográficas.

## Decisões técnicas

| Tema | Decisão | Justificação |
| --- | --- | --- |
| UI | Jetpack Compose | Projeto novo, UI declarativa oficial do Google, adaptação natural a mobile-first. Não se mistura com Android Views. |
| minSdk / targetSdk | 26 / 35 | minSdk 26 cobre a grande maioria dos telemóveis ativos, permite ícones adaptativos sem PNGs legados e prepara OpenGL ES 3.0. |
| OpenGL ES | Fase futura | Reservado para a visualização 3D; nada de OpenGL nesta fase. |
| C++ / JNI | Só com vantagem demonstrável | Nenhum JNI na Fase 0. |
| Armazenamento | Privado da app (`filesDir`) | Sem permissões abrangentes. O Storage Access Framework fica para exportar/importar ficheiros escolhidos pelo utilizador. |
| Formato `.osp` | Fase posterior | Nada de serialização prematura; `formatVersion` já acompanha o modelo para migrações futuras. |
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

# Testes unitários
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
  MainActivity.kt          # ponto de entrada Compose
  navigation/PixelOneApp.kt# grafo de navegação (4 rotas)
  ui/theme/                # paleta, tipografia e tema escuro
  ui/components/           # cartões, etiquetas de estado, scaffold, áreas reservadas
  ui/home|texture|texture3d|studio2dto3d/  # ecrãs
  project/                 # PixelProject + ProjectRepository (contrato e memória)
app/src/test/              # testes unitários do modelo/repositório
.github/workflows/android-release.yml     # CI de release assinado
docs/relatorio-fase-0.md                  # relatório da Fase 0
```

## Limitações conhecidas (Fase 0)

- Ferramentas de edição, 3D e conversão 2D-para-3D não existem ainda; os ecrãs indicam-no explicitamente.
- Projetos vivem em memória; guardar/reabrir ficheiros chega com o formato `.osp` numa fase posterior.
- Instalação em dispositivo físico requer autorização de fontes desconhecidas (sideload).

## Próximas fases (indicativo)

1. **Fase 1** — Texture Studio: canvas de pixel art, pincéis, paletas.
2. **Fase 2** — formato `.osp`, persistência local e gestão de projetos.
3. **Fase 3** — 3D Texture Studio com OpenGL ES e mapeamento UV.
4. **Fase 4** — 2D-to-3D Studio com construção assistida.
