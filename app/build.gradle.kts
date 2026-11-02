plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.intertextual.bible"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.intertextual.bible"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Keep the pack stored as-is: it's already compressed, so recompressing
    // only wastes build time and gains nothing.
    androidResources {
        noCompress += listOf("zip")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
