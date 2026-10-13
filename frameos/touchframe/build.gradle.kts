plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wyattfleming.touchframe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wyattfleming.touchframe"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "../app/proguard-legacy-rules.pro")
        }
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
}

// Source contract fixtures resolve paths from the FrameOS Gradle root.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    workingDir = rootProject.projectDir
    // The media readiness test reads this source contract at runtime; declare it
    // so test up-to-date/cache decisions cover the non-classpath input too.
    inputs.file(rootProject.layout.projectDirectory.file("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt"))
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.11.0")
    implementation(project(":frame-shared"))
    // Keep the shared API23 Java11 NIO compatibility pin until a separately
    // validated dependency migration; lint must not silently force an upgrade.
    //noinspection GradleDependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
