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
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            // ADR-020 Phase 2: TransportManager imports XrayService directly
            // (commonMain interface) so the chain walk drives REALITY the same
            // way it drives Tor.
            implementation(project(":shared:core:xray"))
            // Trek 2 Stage 2A (A5+A7) — `EnvelopeId.random()` uses the
            // `LibsodiumCsprng` helper in the crypto module as the single
            // CSPRNG source for both id generation and future jitter
            // draws. Stage 2B will reuse the same helper for the hold-
            // consumption and next-request jitter sites in
            // RestFallbackOrchestrator.pollLoop.
            implementation(project(":shared:core:crypto"))
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // Briar Tor stack (ADR-018, replaces kmp-tor 2.6.0). Only the
            // wrapper AAR is on the compile classpath — `tor-android` and
            // `lyrebird-android` are pure resource JARs (4× ABI-flat
            // `armeabi-v7a/libtor.so` etc), not Java/Kotlin code, and are
            // unpacked into jniLibs by the `unpackTorBinaries` task below.
            implementation(libs.briar.onionwrapper.android)
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
            // Trek 2 Stage 2A (PR #298 round-2 P2) — EnvelopeIdTest needs to
            // exercise `EnvelopeId.random()` directly (not just indirectly
            // via CsprngTest.hex(16) in the crypto module). That path
            // requires `LibsodiumInitializer.initialize()` at test setup,
            // which in turn needs the libsodium bindings on the test
            // classpath. The crypto module pulls them in via
            // `implementation` scope, so they do not leak to transport's
            // test classpath transitively — we add them here explicitly.
            implementation(libs.libsodium.bindings)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Briar tor + lyrebird native binary unpacking (ADR-018)
//
// The `org.briarproject:tor-android` and `org.briarproject:lyrebird-android`
// artifacts are JAR files containing flat-prefixed ABI directories:
//
//   tor-android-0.4.8.22.jar:
//     arm64-v8a/libtor.so
//     armeabi-v7a/libtor.so
//     x86/libtor.so
//     x86_64/libtor.so
//
//   lyrebird-android-0.6.2.jar:  (same layout, libLyrebird.so)
//
// AGP only auto-packages native libs from AAR `jni/` directories or JARs
// with the `lib/<abi>/` prefix. Briar's flat layout falls outside both, so
// we extract the JARs into `src/androidMain/jniLibs/` ourselves before
// the Android library build runs. AGP then merges them into the final
// APK at the standard `lib/<abi>/` paths, where `dlopen` can find them.
//
// This mirrors the approach Briar themselves use in their `bramble-android`
// gradle (Groovy original) — same task names, same dependency configuration
// pattern, translated to Kotlin DSL.
// ─────────────────────────────────────────────────────────────────────────────
val tor: Configuration by configurations.creating

dependencies {
    add("tor", libs.briar.tor.android.get())
    add("tor", libs.briar.lyrebird.android.get())
}

val torLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")

val cleanTorBinaries by tasks.registering {
    outputs.dir(torLibsDir)
    doLast {
        delete(fileTree(torLibsDir))
    }
}

val unpackTorBinaries by tasks.registering(Copy::class) {
    dependsOn(cleanTorBinaries)
    outputs.dir(torLibsDir)
    from(tor.map { zipTree(it) })
    into(torLibsDir)
}

tasks.named("preBuild") {
    dependsOn(unpackTorBinaries)
}

// ─────────────────────────────────────────────────────────────────────────────
// CLIENT-PREKEY-SELFHEAL: identical-body enforcement for the two JVM/Android
// classifier copies. The `classifyOnJvm` body is duplicated across
// androidMain and jvmMain source sets because they cannot share a private
// function without an additional intermediate source set (not configured
// on this module). The Gradle task below extracts the body of each function
// and fails the build if they differ, so drift is caught in CI.
// ─────────────────────────────────────────────────────────────────────────────
val assertClassifyOnJvmBodiesMatch by tasks.registering {
    val androidFile = file("src/androidMain/kotlin/phantom/core/transport/RetryClassificationAndroid.kt")
    val jvmFile = file("src/jvmMain/kotlin/phantom/core/transport/RetryClassificationJvm.kt")
    inputs.file(androidFile)
    inputs.file(jvmFile)
    doLast {
        fun extractBody(f: java.io.File): String {
            val text = f.readText()
            val marker = "internal fun classifyOnJvm(t: Throwable): RetryDecision {"
            val start = text.indexOf(marker)
            check(start >= 0) {
                "CLIENT-PREKEY-SELFHEAL invariant: ${f.name} must contain `internal fun classifyOnJvm(...)`."
            }
            // Balance braces from the opening `{` of the function.
            var depth = 0
            var i = start + marker.length - 1
            val opening = i
            while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(opening, i + 1)
                        }
                    }
                }
                i++
            }
            error("CLIENT-PREKEY-SELFHEAL invariant: unbalanced braces in ${f.name}::classifyOnJvm.")
        }
        val androidBody = extractBody(androidFile)
        val jvmBody = extractBody(jvmFile)
        check(androidBody == jvmBody) {
            "CLIENT-PREKEY-SELFHEAL invariant: `classifyOnJvm` body drift between " +
                "androidMain and jvmMain source sets. Update both files with identical body.\n" +
                "  androidMain hash: ${androidBody.hashCode()}\n" +
                "  jvmMain hash:     ${jvmBody.hashCode()}"
        }
    }
}

tasks.named("check").configure { dependsOn(assertClassifyOnJvmBodiesMatch) }

android {
    namespace = "phantom.core.transport"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    // Keep tor + lyrebird native libraries uncompressed so dlopen can
    // mmap them directly from the APK (faster start, no extra disk I/O).
    packaging {
        jniLibs {
            keepDebugSymbols += listOf("**/libtor.so", "**/liblyrebird.so")
        }
    }
}
