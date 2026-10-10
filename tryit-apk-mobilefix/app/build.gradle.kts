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
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
