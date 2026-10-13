pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wiitar-android"

// Pure-Kotlin Wiimote protocol layer. The Android app module will be added
// once the on-device connection path has been confirmed (see README).
include(":protocol")
