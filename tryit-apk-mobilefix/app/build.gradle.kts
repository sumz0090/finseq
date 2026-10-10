plugins {
    id("com.android.application")
}

android {
    namespace = "com.tryitexclusive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tryitexclusive.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
