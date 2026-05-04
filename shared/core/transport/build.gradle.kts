plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget()
    jvm()
    // iOS targets added when building KMP XCFramework on macOS (Alpha-1).
    // Kotlin/Native cross-compilation to iOS is not supported on Windows.

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // kmp-tor — embedded Tor client for the ADR-016 hybrid transport.
            // runtime is the public API; resource-noexec-tor ships the bundled
            // tor 0.4.9.5 binary as a JNI library (in-process, not a child
            // process). See gradle/libs.versions.toml for the rationale.
            implementation(libs.kmp.tor.runtime)
            implementation(libs.kmp.tor.resource.noexec)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
    }
}

android {
    namespace = "phantom.core.transport"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
