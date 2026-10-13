plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wyattfleming.frameshared"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions { unitTests.isIncludeAndroidResources = false }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        disable += "AndroidGradlePluginVersion"
    }

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/main/kotlin", "../app/src/main/kotlin"))
            java.include(
                "com/wyattfleming/frameos/FrameOperationLogger.kt",
                "com/wyattfleming/frameos/auth/**",
                "com/wyattfleming/frameos/weather/**",
                "com/wyattfleming/frameos/security/FrameUrlPolicy.kt",
                "com/wyattfleming/frameos/photos/FrameOfflineReserve.kt",
                "com/wyattfleming/frameos/photos/FramePhotoAttachmentEpoch.kt",
                "com/wyattfleming/frameos/photos/FramePhotoBridge.kt",
                "com/wyattfleming/frameos/photos/FrameSharedPlaybackMetadata.kt",
                "com/wyattfleming/frameos/ui/WeatherContentView.kt",
                "com/wyattfleming/frameos/web/FrameOAuthWebSurface.kt",
            )
            assets.srcDir("../app/src/main/assets")
            assets.include("frame-photo-bridge/**")
        }
        getByName("test") {
            java.setSrcDirs(listOf("src/test/kotlin", "../app/src/test/kotlin"))
            java.include(
                "com/wyattfleming/frameos/auth/**",
                "com/wyattfleming/frameos/weather/**",
                "com/wyattfleming/frameos/security/FrameUrlPolicyTest.kt",
                "com/wyattfleming/frameos/photos/FrameOfflineReserveTest.kt",
                "com/wyattfleming/frameos/photos/FrameOfflineReserveMigrationTest.kt",
                "com/wyattfleming/frameos/photos/FramePhotoAttachmentEpochTest.kt",
                "com/wyattfleming/frameos/photos/FramePhotoBridgeCallbackResponseTest.kt",
                "com/wyattfleming/frameos/photos/FramePhotoBridgeDiagnosticsTest.kt",
                "com/wyattfleming/frameos/photos/FramePhotoBridgeExtensionContractTest.kt",
                "com/wyattfleming/frameos/photos/FramePhotoBridgePackagingContractTest.kt",
                "com/wyattfleming/frameos/photos/FrameSharedPlaybackMetadataTest.kt",
            )
        }
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.11.0")
    api("org.mozilla.geckoview:geckoview-armeabi-v7a:144.0.20251027123126")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
