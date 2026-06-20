plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    // Compose compiler ships with Kotlin 2.x — version tracks the Kotlin plugin
    // (replaces the old composeOptions.kotlinCompilerExtensionVersion).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
