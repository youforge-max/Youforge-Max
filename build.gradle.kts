plugins {
    id("com.android.application") version "9.3.0" apply false
    // Compose compiler ships with Kotlin 2.x — version tracks the Kotlin plugin
    // (replaces the old composeOptions.kotlinCompilerExtensionVersion).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
