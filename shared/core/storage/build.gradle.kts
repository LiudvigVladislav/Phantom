plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
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
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(project(":shared:core:identity"))
            implementation(project(":shared:core:crypto"))
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        // F22 PR-2: instrumented tests for the Android-Keystore-backed
        // private-key wrap. Robolectric cannot fake the Keystore provider
        // end-to-end; verifying the real GCM round-trip requires a real
        // (or emulated) Android runtime. Invoke with
        // `./gradlew :shared:core:storage:connectedDebugAndroidTest`.
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

sqldelight {
    databases {
        create("PhantomDatabase") {
            packageName.set("phantom.core.storage.db")
            srcDirs("src/commonMain/sqldelight")
            // Sprint 2b-B — `opk_reservation` + `pending_ratchet_state`
            // tables added at schema version 21 (see 21.sqm migration).
            // Backs the L4 two-phase OPK consume protocol + L3 pending
            // session companion table per
            // `docs/tracks/sprint-2b-opk-pending-session-scope.md`.
            // Trek 2 Stage 2A (A2) added `transport_seq_state` at v20.
            version = 21
        }
    }
}

android {
    namespace = "phantom.core.storage"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // AndroidJUnitRunner is the runner shipped with androidx.test:runner.
        // It is what `connectedAndroidTest` uses to execute @RunWith(AndroidJUnit4)
        // classes on a connected emulator or device. F22 PR-2 added the first
        // instrumented test for this module — see AndroidKeystoreBlobCipherTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
