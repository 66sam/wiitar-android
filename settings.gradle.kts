dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wiitar-android"

// Pure-Kotlin Wiimote protocol layer; builds anywhere with a JDK.
include(":protocol")

// The Android app needs an SDK: Android Studio writes local.properties, CI sets ANDROID_HOME.
if (System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    rootDir.resolve("local.properties").exists()
) {
    include(":app")
}
