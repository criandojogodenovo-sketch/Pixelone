// Pixel One — ficheiro de build raiz.
// Versões fixadas num único local para garantir builds reproduzíveis:
//   - AGP 8.7.3 exige Gradle >= 8.9 e JDK 17+ (usamos Gradle 8.10.2 e JDK 17).
//   - Kotlin 2.0.21 com o plugin oficial do Compose (org.jetbrains.kotlin.plugin.compose),
//     que substitui o antigo kotlinCompilerExtensionVersion.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
