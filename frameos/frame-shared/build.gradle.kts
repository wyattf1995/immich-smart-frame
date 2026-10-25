import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitivity

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Android source sets accept directories, not PatternFilterable.include. These
// build-owned mirrors keep the reviewed allowlist while compiling the canonical
// app sources. Task inputs track source-proof mutations; no source is hand-copied.
abstract class SharedInputSync : Sync() {
    @get:OutputDirectory
    abstract val generatedDirectory: DirectoryProperty

    init { into(generatedDirectory) }
}

val syncSharedMainSources by tasks.registering(SharedInputSync::class) {
    from("../app/src/main/kotlin") {
        include(
            "com/wyattfleming/frameos/FrameOperationLogger.kt",
            "com/wyattfleming/frameos/control/FrameCompanionClient.kt",
            "com/wyattfleming/frameos/control/FrameCompanionCommandStore.kt",
            "com/wyattfleming/frameos/control/FrameCompanionCommandJournal.kt",
            "com/wyattfleming/frameos/control/FrameCompanionCredentialsStore.kt",
            "com/wyattfleming/frameos/control/FrameCompanionEnvelope.kt",
            "com/wyattfleming/frameos/control/FrameCompanionEventDecoder.kt",
            "com/wyattfleming/frameos/control/FrameCompanionEventStore.kt",
            "com/wyattfleming/frameos/control/FrameCompanionSettingsDecoder.kt",
            "com/wyattfleming/frameos/control/FrameCompanionSnapshotRevision.kt",
            "com/wyattfleming/frameos/control/FrameCompanionCommandDecoder.kt",
            "com/wyattfleming/frameos/control/FrameRemoteControlPolicy.kt",
            "com/wyattfleming/frameos/control/FrameRemoteEndpoint.kt",
            "com/wyattfleming/frameos/control/FrameRemoteModels.kt",
            "com/wyattfleming/frameos/control/FrameRemotePollPolicy.kt",
            "com/wyattfleming/frameos/control/FrameCompanionResponseReader.kt",
            "com/wyattfleming/frameos/control/FramePhotosProfileUrl.kt",
            "com/wyattfleming/frameos/navigation/FrameMode.kt",
            "com/wyattfleming/frameos/config/FrameExperienceSettings.kt",
            "com/wyattfleming/frameos/config/FrameExperienceStore.kt",
            "com/wyattfleming/frameos/config/QuietHoursBrightnessPolicy.kt",
            "com/wyattfleming/frameos/auth/**",
            "com/wyattfleming/frameos/weather/**",
            "com/wyattfleming/frameos/security/FrameUrlPolicy.kt",
            "com/wyattfleming/frameos/photos/FrameOfflineReserve.kt",
            "com/wyattfleming/frameos/photos/FramePhotoAttachmentEpoch.kt",
            "com/wyattfleming/frameos/photos/FramePhotoBridge.kt",
            "com/wyattfleming/frameos/photos/FrameSharedPlaybackMetadata.kt",
            "com/wyattfleming/frameos/ui/WeatherContentView.kt",
            "com/wyattfleming/frameos/web/FrameOAuthWebSurface.kt",
            "com/wyattfleming/frameos/web/FrameTextureGeckoView.kt",
        )
    }
    generatedDirectory.set(layout.buildDirectory.dir("generated/shared/main/kotlin"))
}

val syncSharedTestSources by tasks.registering(SharedInputSync::class) {
    from("../app/src/test/kotlin") {
        include(
            "com/wyattfleming/frameos/auth/**",
            "com/wyattfleming/frameos/weather/**",
            "com/wyattfleming/frameos/security/FrameUrlPolicyTest.kt",
            "com/wyattfleming/frameos/photos/FrameOfflineReserveTest.kt",
            "com/wyattfleming/frameos/photos/FrameOfflineReserveMigrationTest.kt",
            "com/wyattfleming/frameos/photos/FramePhotoAttachmentEpochTest.kt",
            "com/wyattfleming/frameos/photos/FramePhotoBridgeCallbackResponseTest.kt",
            "com/wyattfleming/frameos/photos/FramePhotoBridgeDiagnosticsTest.kt",
            "com/wyattfleming/frameos/photos/FramePhotoBridgeExtensionContractTest.kt",
            "com/wyattfleming/frameos/photos/FramePhotoBridgePreparedAttachmentContractTest.kt",
            "com/wyattfleming/frameos/photos/FramePhotoBridgePackagingContractTest.kt",
            "com/wyattfleming/frameos/photos/FrameSharedPlaybackMetadataTest.kt",
            "com/wyattfleming/frameos/control/FrameCompanionEventStoreTest.kt",
            "com/wyattfleming/frameos/ui/WeatherHeaderLayoutTest.kt",
            "com/wyattfleming/frameos/web/FrameTextureFrameGateTest.kt",
        )
    }
    generatedDirectory.set(layout.buildDirectory.dir("generated/shared/test/kotlin"))
}

val syncSharedAssets by tasks.registering(SharedInputSync::class) {
    from("../app/src/main/assets") { include("frame-photo-bridge/**") }
    generatedDirectory.set(layout.buildDirectory.dir("generated/shared/main/assets"))
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
            java.setSrcDirs(listOf("src/main/kotlin"))
        }
        getByName("test") {
            java.setSrcDirs(listOf("src/test/kotlin"))
            resources.srcDir("../app/src/test/resources")
        }
    }
}

// Variant sources retain producer dependencies and mark these inputs generated.
// AGP 9 intentionally rejects task Providers passed to the static source-set DSL.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(syncSharedMainSources, SharedInputSync::generatedDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(syncSharedAssets, SharedInputSync::generatedDirectory)
        // AGP 9.1 hides Variant.unitTest from Kotlin; hostTests is the supported API.
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.sources?.java?.addGeneratedSourceDirectory(
            syncSharedTestSources,
            SharedInputSync::generatedDirectory,
        )
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    workingDir = file("../app")
    // Packaging contracts read these canonical files directly without Android test resources.
    // Track them or Gradle may restore a green test result after an extension asset changes.
    inputs.dir(file("../app/src/main/assets/frame-photo-bridge"))
        .withPropertyName("framePhotoBridgePackagingAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.11.0")
    api("org.mozilla.geckoview:geckoview-armeabi-v7a:144.0.20251027123126")
    // Keep the shared API23/ARMv7 Java 11 NIO compatibility pin until a separately
    // validated dependency migration; lint must not silently force an upgrade.
    //noinspection GradleDependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
