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

        // N1-F1 R-N1.8 — the inbound settlement transaction is only
        // meaningfully testable against a real SQL engine: a fake
        // repository can show the call order but never that a failed
        // statement rolls the whole thing back. TEST-ONLY, JVM-only,
        // pinned to the same SQLDelight version as the runtime.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.sqldelight.sqlite.driver)
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
            // Migration 22.sqm (generated schema 22 -> 23) records the local
            // one-time pre-key its candidate was derived with, so a
            // promotion never has to infer that binding.
            // Backs the L4 two-phase OPK consume protocol + L3 pending
            // session companion table per
            // `docs/tracks/sprint-2b-opk-pending-session-scope.md`.
            // Trek 2 Stage 2A (A2) added `transport_seq_state` at v20.
            // Migration 23.sqm (schema 23 -> 24): durable replay eligibility.
            // Migration 24.sqm (schema 24 -> 25): displaced receive sessions.
            version = 24
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
