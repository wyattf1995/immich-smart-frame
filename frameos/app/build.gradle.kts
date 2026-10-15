plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Modern remains the default. Legacy is deliberately opt-in for the verified
// API23/ARMv7 PF1007 and never changes the normal release artifact.
val frameosTarget = providers.gradleProperty("frameosTarget").orElse("modern").get()
val legacyArmv7 = when (frameosTarget) {
    "modern" -> false
    "legacyArmv7" -> true
    else -> error("Unsupported frameosTarget '$frameosTarget'; use modern or legacyArmv7")
}

android {
    namespace = "com.wyattfleming.frameos"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wyattfleming.frameos"
        minSdk = if (legacyArmv7) 23 else 29
        targetSdk = 37
        // Retain 1: the verified signed 0.2.8 rollback artifact uses it, and
        // direct-device rollback installs must remain possible.
        versionCode = 1
        versionName = "0.2.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (legacyArmv7) {
                // GeckoView 144 pulls SnakeYAML 2.2, whose JavaBeans references are
                // absent from Android. Keep this compatibility declaration out of the
                // modern artifact; it does not add JavaBeans classes or relax minification.
                proguardFiles("proguard-legacy-rules.pro")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The NIO flavor covers the existing java.time and java.nio.file uses on the API23 legacy target. :frame-ui
        // (the shared Weather and Ocean screens, minSdk 23) requires its consumers to desugar too; at the modern
        // target's minSdk 29 these APIs are native, so D8 rewrites nothing and platform behaviour is unchanged.
        isCoreLibraryDesugaringEnabled = true
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
        // These sources are also compiled at minSdk 23 (the legacy target and :frame-shared for FrameOS Touch), where
        // the version checks and the values-v27 folder are required; at the modern minSdk 29 lint calls them obsolete.
        disable += "ObsoleteSdkInt"
    }
}

dependencies {
    // Gecko's annotation dependency is runtime-only; expose API requirements to lint callers.
    compileOnly("androidx.annotation:annotation:1.11.0")
    implementation(
        if (legacyArmv7) {
            // Mozilla AAR manifest verified: 144 is the newest armeabi-v7a
            // release with minSdk 21; 145 and newer require minSdk 26.
            "org.mozilla.geckoview:geckoview-armeabi-v7a:144.0.20251027123126"
        } else {
            "org.mozilla.geckoview:geckoview-arm64-v8a:154.0.20260814215756"
        },
    )
    // Android's documented Java 11 NIO desugaring artifact (API23 legacy, and :frame-ui's requirement).
    //noinspection GradleDependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")
    // FrameOS's shared native screens: the new Weather page and Ocean.
    implementation(project(":frame-ui"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
