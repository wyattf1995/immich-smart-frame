plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// FrameOS's shared native screens (theme, Weather, Ocean) for both apps: FrameOS Touch and the Lenovo FrameOS.
// The FrameOS weather/HTTP classes these screens read are compile-only: FrameOS Touch gets them at runtime from
// :frame-shared and the Lenovo app from its own canonical sources, so neither app carries two copies.
android {
    namespace = "com.wyattfleming.frameui"
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
        getByName("main") { java.setSrcDirs(listOf("src/main/kotlin")) }
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.11.0")
    compileOnly(project(":frame-shared"))
    // Keep the shared API23 Java 11 NIO compatibility pin until a separately
    // validated dependency migration; lint must not silently force an upgrade.
    //noinspection GradleDependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")
}
