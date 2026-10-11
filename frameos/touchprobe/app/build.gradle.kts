plugins {
    id("com.android.application")
}

android {
    namespace = "com.wyattfleming.frameos.touchprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wyattfleming.frameos.touchprobe"
        minSdk = 23
        targetSdk = 23
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
