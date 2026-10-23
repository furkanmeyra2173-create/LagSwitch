plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anonh4x.lagswitch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anonh4x.lagswitch"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
