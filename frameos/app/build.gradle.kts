plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wyattfleming.frameos"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wyattfleming.frameos"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // AGP 9.1.1 requires Gradle 9.3.1; it is also within Kotlin 2.4's fully
        // supported range. Keep the checksum-pinned wrapper until that stack moves.
        disable += "AndroidGradlePluginVersion"
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:154.0.20260814215756")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
