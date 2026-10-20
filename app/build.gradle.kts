plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A assinatura release é configurada EXCLUSIVAMENTE a partir de variáveis de
// ambiente fornecidas pelo GitHub Actions (secrets do repositório):
//   PIXELONE_KEYSTORE_PATH, PIXELONE_KEYSTORE_PASSWORD,
//   PIXELONE_KEY_ALIAS, PIXELONE_KEY_PASSWORD.
// Nenhum segredo é guardado em ficheiros versionados. Sem estas variáveis,
// o build local produz um APK release por assinar; o workflow de CI valida a
// presença dos secrets ANTES de compilar e falha cedo em caso contrário,
// pelo que nunca é publicado silenciosamente um APK release não assinado.
val releaseKeystorePath: String? = System.getenv("PIXELONE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("PIXELONE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("PIXELONE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("PIXELONE_KEY_PASSWORD")

android {
    namespace = "com.pixelone.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pixelone.studio"
        // minSdk 26: cobre a grande maioria dos telemóveis ativos, permite
        // ícones adaptativos (mipmap-anydpi-v26 sem PNGs legados) e fica
        // pronto para OpenGL ES 3.0 nas fases 3D futuras.
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-performance"

        ndk {
            // Preserva a cobertura de ABIs do projeto (sem nativo antes,
            // todas eram possíveis): arm64-v8a/armeabi-v7a para telemóveis,
            // x86_64/x86 para emuladores.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        externalNativeBuild {
            cmake {
                // C++17 explícito; STL estática (lib pequena, sem dependência
                // dinâmica de libc++). As flags de otimização -O3 em Release
                // ficam no CMakeLists.txt por configuração de build.
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null && releaseKeystorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Minificação desativada na Fase 0 (fundação sem R8) — decisão
            // registada no README; reavaliar quando a base de código crescer.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric precisa dos recursos Android para testar o
            // roundtrip PNG real (Bitmap.compress/BitmapFactory).
            isIncludeAndroidResources = true
            // NativeEngine (fallback JVM) usa android.util.Log via PerfLog:
            // devolve valores por omissão nos testes unitários JVM.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // BOM alinha as versões de todas as bibliotecas Compose.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // Fases 1+2: retenção de estado do editor (rotação/ciclo de vida) e
    // corrotinas para IO (guardar/exportar fora do thread principal).
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Apenas APIs estáveis são usadas no código; ui-tooling só existe em debug.
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Testes unitários (motor 2D, histórico, persistência, spritesheet/atlas).
    testImplementation("junit:junit:4.13.2")
    // Robolectric: teste REAL do roundtrip PNG (Bitmap.compress/decode) em JVM.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    // Testes instrumentados (androidTest): prova o buffer partilhado
    // Kotlin↔C++ no dispositivo/emulador e a visibilidade do canvas.
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
