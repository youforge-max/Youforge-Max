plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "eu.youforgemax"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.youforgemax"
        minSdk = 29              // MediaPipe GenAI runs on 24+; SAF + Compose fine on 29
        targetSdk = 35
        // Bump both on every release; versionCode must increase monotonically or
        // Android treats the build as a reinstall rather than an update. It sat at
        // 31 from v1.1-max-p19 through v1.5-max-p23, so five releases were
        // indistinguishable to the package manager. versionName matches the tag.
        versionCode = 32
        versionName = "1.5.1"

        // Tablet is arm64; drop the other ABIs' native libs to slim the APK.
        ndk { abiFilters += "arm64-v8a" }

        // GGUF backend (llama.cpp via NDK). c++_shared so the STL is shared with
        // the other native libs (MediaPipe/Vosk) instead of duplicated.
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "26.3.11579264"

    signingConfigs {
        create("release") {
            val ksPath = (findProperty("RELEASE_STORE_FILE") as String?)
                ?: System.getenv("RELEASE_STORE_FILE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = (findProperty("RELEASE_STORE_PASSWORD") as String?)
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = (findProperty("RELEASE_KEY_ALIAS") as String?)
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = (findProperty("RELEASE_KEY_PASSWORD") as String?)
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if ((findProperty("RELEASE_STORE_FILE") as String?) != null ||
                System.getenv("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Debug builds also carry x86_64 so the app runs on an x86_64 emulator
            // under KVM (incl. the MediaPipe/Vosk native libs). Release stays arm64-only.
            ndk { abiFilters += "x86_64" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
    // composeOptions.kotlinCompilerExtensionVersion removed — the Compose compiler
    // is now provided by the org.jetbrains.kotlin.plugin.compose Gradle plugin.

    // MediaPipe mmaps .tflite straight from the APK — must stay uncompressed.
    androidResources { noCompress += "tflite" }
}

// Kotlin 2.x compilerOptions DSL (replaces the deprecated kotlinOptions block).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // On-device LLM (Gemma-2 2B .task) for the smart overlay suggestions.
    implementation("com.google.mediapipe:tasks-genai:0.10.35")
    // On-device vision: selfie segmentation (background removal) + face detection
    // (auto-crop). Models bundled in assets — fully offline, no cloud.
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    // On-device speech-to-text (Vosk) for "title from video" — small model
    // downloaded on first use, then fully offline.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Video editor engine (Youforge-Max): Media3 Transformer/Composition — on-device,
    // MediaCodec-based trim/merge/speed/effects/overlays, no FFmpeg, no watermark.
    // ExoPlayer + media3-ui drive the editor's live preview.
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("androidx.media3:media3-effect:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
}
