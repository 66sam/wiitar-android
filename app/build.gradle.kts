plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.wiitar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.wiitar.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        // Extract native libs to nativeLibraryDir so the Shizuku service process can load them by path.
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        // Committed on purpose: a fixed debug key lets each CI-built APK install over the
        // previous one (keeping permissions and pairing). Not used for releases.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release key from the environment (CI secrets). Without one, releases fall back to the
        // debug key above, so they still install over test builds.
        val releaseStore = System.getenv("WIITAR_KEYSTORE_FILE")
        if (releaseStore != null && file(releaseStore).exists()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("WIITAR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WIITAR_KEY_ALIAS")
                keyPassword = System.getenv("WIITAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // No shrinking: the bridge calls hidden Android APIs and the Odin mapping app by reflection.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// APKs come out as wiitar-<version>-<buildtype>.apk.
base.archivesName.set("wiitar-${android.defaultConfig.versionName}")

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":protocol"))
    // Shizuku: ADB-shell privileges without root, for reading evdev and creating a uhid gamepad.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
}
