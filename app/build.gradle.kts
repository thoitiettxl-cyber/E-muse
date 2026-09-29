plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing: environment variables (CI, or `source signing.env` locally) or Gradle
// properties (~/.gradle/gradle.properties). Never commit keystores or passwords.
fun signingValue(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: (findProperty(property) as String?)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("EMUSE_KEYSTORE_FILE", "emuse.keystoreFile")
val releaseStorePassword = signingValue("EMUSE_KEYSTORE_PASSWORD", "emuse.keystorePassword")
val releaseKeyAlias = signingValue("EMUSE_KEY_ALIAS", "emuse.keyAlias")
val releaseKeyPassword = signingValue("EMUSE_KEY_PASSWORD", "emuse.keyPassword")
// Only sign when every value is present and the keystore exists; otherwise release stays unsigned.
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() } && file(requireNotNull(releaseStoreFile)).isFile

// APK file name: E-Muse-release.apk.
base {
    archivesName = "E-Muse"
}

android {
    namespace = "io.github.thoitiet.emuse"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.thoitiet.emuse"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.hidden.api.bypass)
}
