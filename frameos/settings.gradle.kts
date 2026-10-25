pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "FrameOS"
include(":app", ":frame-shared", ":frame-ui", ":touchframe")

// Frame TV (the Android TV home screen in ../tvos) shows the shared Weather and Ocean screens, so it builds here as
// :frametv. A source projection that carries only frameos/ simply leaves it out.
val frameTv = file("../tvos/app")
if (frameTv.isDirectory) {
    include(":frametv")
    project(":frametv").projectDir = frameTv
}
