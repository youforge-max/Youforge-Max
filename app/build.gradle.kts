plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.cisodiagonal.youforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.cisodiagonal.youforge"
        minSdk = 29              // MediaPipe GenAI runs on 24+; SAF + Compose fine on 29
        targetSdk = 35
        versionCode = 15
        versionName = "1.0-r15"

        // Tablet is arm64; drop the other ABIs' native libs to slim the APK.
        ndk { abiFilters += "arm64-v8a" }
    }

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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    // MediaPipe mmaps .tflite straight from the APK — must stay uncompressed.
    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // On-device LLM (Gemma-2 2B .task) for the smart overlay suggestions.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    // On-device vision: selfie segmentation (background removal) + face detection
    // (auto-crop). Models bundled in assets — fully offline, no cloud.
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // On-device speech-to-text (Vosk) for "title from video" — small model
    // downloaded on first use, then fully offline.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
