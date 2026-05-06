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
        }
        androidMain.dependencies {
            // Picks up libXray.jar (Java/Kotlin API surface for libXray) from
            // src/androidMain/libs/. The matching native artifacts (libgojni.so
            // for the four supported ABIs) live in src/androidMain/jniLibs/
            // and are merged into the APK by AGP via the sourceSets.jniLibs
            // wiring below.
            //
            // Why a flat jar+jniLibs split rather than a vendored .aar:
            // AGP refuses to bundle a local .aar dependency into another AAR
            // ("hasLocalAarDeps" check), which is exactly what `:shared:core:*`
            // modules produce. Splitting the AAR (which is just classes.jar
            // + jniLibs + an empty AndroidManifest in libXray's case) sidesteps
            // that restriction with no semantic change — the runtime artefact
            // surface is identical.
            //
            // To refresh: download a fresh libXray.aar via the
            // .github/workflows/build-libxray.yml artefact, then run the
            // unpack steps documented in src/androidMain/libs/README.md.
            implementation(fileTree("src/androidMain/libs") { include("*.jar") })
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "phantom.core.xray"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // libgojni.so ships in arm64-v8a / armeabi-v7a / x86 / x86_64 only.
        // Lock the APK to those four to fail fast if a build host slips in
        // a fifth ABI (e.g. riscv64 once NDK adds it) for which Xray has no
        // native artifact.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }
    // Point AGP at the unpacked native libraries. Default lookup is
    // `src/main/jniLibs/`; we keep them under the androidMain source set to
    // co-locate with the Kotlin code that loads them.
    sourceSets.getByName("main").jniLibs.srcDir("src/androidMain/jniLibs")
}
