// Top-level build file for E-Muse.
plugins {
    alias(libs.plugins.android.application) apply false
}

// Pin Kotlin 2.4.10 for AGP 9.x built-in Kotlin (via buildscript classpath).
// AGP 9.3.2 bundles Kotlin 2.2.0, but Miuix 0.9.4 was compiled with 2.4.0.
// This makes AGP's built-in Kotlin use 2.4.10 instead of the bundled 2.2.0.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}
