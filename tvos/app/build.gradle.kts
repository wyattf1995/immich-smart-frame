plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Frame TV builds inside the FrameOS project (frameos/settings.gradle.kts) as :frametv, so it can show :frame-ui's
// shared Weather and Ocean screens. Those screens read FrameOS's weather models, which FrameOS keeps in its app
// sources. Android source sets accept directories, not include patterns: this build-owned mirror compiles exactly
// the listed files into Frame TV, never the frame's browser, sign-in or companion code. No source is hand-copied.
abstract class FrameSourceSync : Sync() {
    @get:OutputDirectory
    abstract val generatedDirectory: DirectoryProperty

    init { into(generatedDirectory) }
}

val syncFrameSources by tasks.registering(FrameSourceSync::class) {
    from(rootProject.file("app/src/main/kotlin")) {
        include(
            "com/wyattfleming/frameos/weather/WeatherModels.kt",
            "com/wyattfleming/frameos/weather/LegacyIsrgRootTls.kt",
            "com/wyattfleming/frameos/ui/NativeSceneDensity.kt",
        )
    }
    generatedDirectory.set(layout.buildDirectory.dir("generated/frameos/main/kotlin"))
}

android {
    namespace = "com.wyattfleming.frametv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wyattfleming.frametv"
        minSdk = 28
        targetSdk = 36
        versionCode = 10
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // :frame-ui also serves API 23 frames and requires its consumers to desugar. At Frame TV's minSdk 28
        // java.time is native, so D8 rewrites nothing the TV relies on.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        // NAS exports an unsigned development APK. Signing happens only on the Mac.
        getByName("debug") { signingConfig = null }
        getByName("release") { isMinifyEnabled = false }
    }

    lint { abortOnError = true }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(syncFrameSources, FrameSourceSync::generatedDirectory)
    }
}

dependencies {
    implementation(project(":frame-ui"))
    // The same pin as :frame-ui; lint must not silently force an upgrade.
    //noinspection GradleDependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")
    testImplementation("junit:junit:4.13.2")
    // JVM unit tests must use a real JSON implementation; android.jar provides stubs.
    testImplementation("org.json:json:20260814")
}
