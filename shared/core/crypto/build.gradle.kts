plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    jvm()
    // iOS targets: add on macOS when building KMP XCFramework (Alpha-1).

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.libsodium.bindings)
            implementation(project(":shared:core:identity"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // Instrumented tests for libsodium-backed crypto — must run on a real
        // Android runtime because the libsodium native binding cannot be
        // resolved on the JVM test classpath (see docs/tech_debt.md Bug H).
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }
}

android {
    namespace = "phantom.core.crypto"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // AndroidJUnitRunner is the runner shipped with androidx.test:runner.
        // It is what `connectedAndroidTest` uses to execute @RunWith(AndroidJUnit4)
        // classes on a connected emulator or device.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
