import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing, from keystore.properties (git-ignored, kept next to the key outside the repo).
// Without it, release builds come out unsigned.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.iurika.galleryflows"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.iurika.galleryflows"
        // 30: all-files access (MANAGE_EXTERNAL_STORAGE) and File-based ThumbnailUtils
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("release") }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
