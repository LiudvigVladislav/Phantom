import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Load release signing credentials from keystores/signing.properties (gitignored)
// or fall back to SIGNING_* env vars (for CI). If neither is available, the
// release build falls back to the debug signing config — lets contributors
// build release APKs locally without access to the production key.
val signingProps = Properties().apply {
    val f = rootProject.file("keystores/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propertyKey: String, envKey: String): String? =
    signingProps.getProperty(propertyKey) ?: System.getenv(envKey)

// Local dev overrides — values in local.properties or env vars override the
// defaults below. local.properties is gitignored (Android Studio default).
// Example for local relay on emulator: relay.url=ws://10.0.2.2:8080/ws
// Example for local relay on physical device: relay.url=ws://192.168.x.y:8080/ws
// Note: cleartext (ws://) is allowed to 10.0.2.2 and localhost only by
//       network_security_config.xml. Physical device local testing requires
//       adding the LAN IP there (not committed) or using wss:// via a tunnel.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localOrEnv(propKey: String, envKey: String, default: String): String =
    localProps.getProperty(propKey) ?: System.getenv(envKey) ?: default

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    sourceSets {
        // JVM-based unit tests for Android-only code (android.util.Log is stubbed by AGP).
        // Runs with ./gradlew :apps:android:testDebugUnitTest (no device required).
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":shared:core:transport"))
            }
        }

        androidMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.uuid)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.websockets)
            implementation(libs.androidx.activity.compose)
            implementation(libs.zxing.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            implementation(libs.mlkit.barcode)
            implementation(libs.libsodium.bindings)
            implementation(libs.sqlcipher.android)
            implementation("androidx.biometric:biometric:1.1.0")
            // WebRTC for voice calls — provides PeerConnectionFactory, AudioTrack, IceCandidate.
            // stream/webrtc-android wraps Google's pre-built libwebrtc .aar so we avoid
            // compiling WebRTC from source (which requires depot_tools + Linux host).
            implementation("io.getstream:stream-webrtc-android:1.1.1")
            implementation(project(":shared:core:identity"))
            implementation(project(":shared:core:crypto"))
            implementation(project(":shared:core:storage"))
            implementation(project(":shared:core:transport"))
            implementation(project(":shared:core:xray"))
            implementation(project(":shared:core:messaging"))
        }
    }
}

android {
    namespace = "phantom.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "phantom.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1-alpha"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFileProp = signingValue("storeFile", "SIGNING_STORE_FILE")
            val storePasswordProp = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
            val keyAliasProp = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
            val keyPasswordProp = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")

            if (storeFileProp != null && storePasswordProp != null &&
                keyAliasProp != null && keyPasswordProp != null
            ) {
                storeFile = rootProject.file(storeFileProp)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
            // If any field is null, this config is left unusable and release
            // below falls back to the debug signing config.
        }
    }

    buildTypes {
        debug {
            // Default: production relay. Override in local.properties (gitignored):
            //   relay.url=ws://10.0.2.2:8080/ws     ← emulator → host machine
            //   relay.url=ws://192.168.x.y:8080/ws  ← physical device → host machine
            // Cleartext (ws://) is only allowed to 10.0.2.2 / localhost by
            // network_security_config.xml; for a LAN IP you must also add it there locally.
            val relayUrl = localOrEnv("relay.url", "RELAY_URL", "wss://relay.phntm.pro/ws")
            buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
            // RELAY_TOKEN removed in F11+F26 fix — relay no longer accepts a
            // shared `?token=`; auth is per-user signed challenge (see
            // KtorRelayTransport.buildAuthedWsUrl).
            // Tor onion endpoint for the relay (ADR-016 Stage 2).
            // Wired in Stage 2B; Stage 2A only exposes the constant. Plain
            // HTTP/WS over the onion is intentional — Tor's circuit already
            // provides confidentiality, integrity and onion-address auth.
            val relayOnionUrl = localOrEnv(
                "relay.onion.url",
                "RELAY_ONION_URL",
                "ws://zmdrxlrkd7iv7ozvdl5nlhctsxgx6eyuqionp6xzriolymy3m6ioloyd.onion:80/ws"
            )
            buildConfigField("String", "RELAY_ONION_URL", "\"$relayOnionUrl\"")
            // PR-RC-DIRECT-WS-DEATH1 Phase 1: build flag for the diagnostic
            // arm selector. Locked in `docs/tracks/rc-direct-ws-death1.md`
            // § Commit 3.2b (rev4) §7 step 2. Values:
            //   "0" — disabled (default; also covers Arm A field runs, which
            //         add no new code — Arm A IS the existing production path)
            //   "B" — Arm B: raw OkHttp sequential diagnostic; production
            //         Hybrid Ktor `transport.connect(...)` is short-circuited
            //         in PhantomMessagingService to keep Inv-ParallelArmIsolation
            //   "E" — Phase 2 only (data-frame heartbeat diagnostic)
            // Override via `local.properties` `rcDirectArm=B` or env
            // RC_DIRECT_ARM=B. Release builds ignore the value entirely
            // (see release block + runtime gate `BuildConfig.DEBUG && ...`).
            val rcDirectArm = localOrEnv("rcDirectArm", "RC_DIRECT_ARM", "0")
            buildConfigField("String", "DEBUG_RC_DIRECT_ARM", "\"$rcDirectArm\"")
            // PR-RC-DIRECT-WS-DEATH1 Phase 2: build flag for the PCAPdroid
            // capture session mode tag. Emitted as
            // `PHASE2_CAPTURE_MARKER mode=${DEBUG_PHASE2_MODE} utc=... s=...`
            // in `RcDirectArmB.runOneSession()` as the wall-clock anchor
            // required by Inv-WallClockAlignment (Phase 2 mini-lock §21).
            // Marker emit is gated by the active Arm B run window —
            // `BuildConfig.DEBUG && DEBUG_RC_DIRECT_ARM == "B"` is enforced
            // by the AppContainer wire-up site (only that flag value
            // constructs `RcDirectArmB`), so this field controls only the
            // `mode=...` value, never the emit-or-not decision.
            // Values:
            //   "0" — no Phase 2 capture intent declared (default; marker
            //         still emits with mode=0 so wall-clock anchor data is
            //         always available when Arm B is armed)
            //   "P1" — Mode 1 capture (Wi-Fi 8-pong rhythm; target 9th Pong)
            //   "P2" — Mode 2 capture (Tele2 LTE severe; target 1st-2nd Pong)
            //   "P3" — control reading (PCAPdroid-on, no analysis target)
            // Override via `local.properties` `phase2Mode=P1` or env
            // PHASE2_MODE. Release builds ignore the value entirely
            // (pinned to "0" in the release block + runtime gate `BuildConfig.DEBUG`).
            val phase2Mode = localOrEnv("phase2Mode", "PHASE2_MODE", "0")
            buildConfigField("String", "DEBUG_PHASE2_MODE", "\"$phase2Mode\"")
            // RC-DIRECT-STABILITY1 Arm A: Caddy-bypass diagnostic URL.
            // When non-empty in a debug build, the wire-up site at
            // `AppContainer.rcDirectArmA` constructs `RcDirectArmA` pointed
            // at this URL, and `PhantomMessagingService.onStartCommand`
            // short-circuits the production Hybrid Ktor `transport.connect(...)`
            // path (Inv-ParallelArmIsolation). Expected values:
            //   ""                          — disabled (default; production-equivalent run)
            //   "ws://10.0.2.2:8081/ws"     — emulator + adb localhost forward
            //                                 (10.0.2.2 is already in NSC cleartext whitelist)
            //   "ws://127.0.0.1:8081/ws"    — physical Tecno via the two-command
            //                                 bridge: `ssh -N -L 8081:127.0.0.1:8081 ...`
            //                                 + `adb reverse tcp:8081 tcp:8081`
            //                                 (127.0.0.1 is already in NSC cleartext whitelist;
            //                                 LAN IPs are forbidden per Inv-NoLanInNsc)
            // Override via `local.properties` `debugBypassUrl=ws://...` or
            // env DEBUG_BYPASS_URL. Release builds ignore the value entirely
            // (pinned to "" in the release block + runtime gate `BuildConfig.DEBUG`).
            // Design locked in `docs/tracks/rc-direct-stability1.md` §3 + §4 Arm A.
            val debugBypassUrl = localOrEnv("debugBypassUrl", "DEBUG_BYPASS_URL", "")
            buildConfigField("String", "DEBUG_BYPASS_URL", "\"$debugBypassUrl\"")
            // RC-DIRECT-STABILITY1 Arm C: OkHttp ping interval matrix
            // diagnostic. When non-empty (a numeric string different from
            // "0") in a debug build, the wire-up site at
            // `AppContainer.rcDirectArmC` constructs `RcDirectArmC` with
            // OkHttpClient.Builder().pingInterval(value, MILLISECONDS),
            // and `PhantomMessagingService.onStartCommand` short-circuits
            // the production Hybrid Ktor path (Inv-ParallelArmIsolation).
            // Strictly diagnostic — production `RelayTransportFactory.kt:71`
            // pingInterval(15_000L, MILLISECONDS) is read-only for the
            // entire RC-DIRECT-STABILITY1 track per Inv-OnlyDiagnosticCadenceChange.
            // No value here can be auto-promoted to production; any promotion
            // requires a separate named PR with its own mini-lock.
            // Expected values:
            //   "0"     — Arm C disabled (default). The gate at the wire-up
            //             site is `BuildConfig.DEBUG_RC_DIRECT_PING_INTERVAL_MS != "0"`,
            //             so "0" means `RcDirectArmC` is NOT constructed and
            //             the service falls through to the next branch: Arm B
            //             if `rcDirectArm=B`, otherwise production Hybrid Ktor.
            //             Baseline runs use this value combined with a separate
            //             choice for the baseline arm (see mini-lock §4 Arm C
            //             Setup step 4 for the baseline-choice table).
            //   "10000" — Arm C with 10 s ping interval (RC_DIRECT_ARM_C_*)
            //   "20000" — Arm C with 20 s ping interval (RC_DIRECT_ARM_C_*)
            //   "30000" — Arm C with 30 s ping interval (RC_DIRECT_ARM_C_*)
            // Override via `local.properties` `rcDirectPingIntervalMs=20000`
            // or env RC_DIRECT_PING_INTERVAL_MS. Release builds ignore the
            // value entirely (pinned to "0" in the release block + runtime
            // gate `BuildConfig.DEBUG`).
            // Design locked in `docs/tracks/rc-direct-stability1.md` §3 +
            // §4 Arm C (refined scope after PR-4 review).
            val rcDirectPingIntervalMs = localOrEnv("rcDirectPingIntervalMs", "RC_DIRECT_PING_INTERVAL_MS", "0")
            buildConfigField("String", "DEBUG_RC_DIRECT_PING_INTERVAL_MS", "\"$rcDirectPingIntervalMs\"")
            // RC-DIRECT-STABILITY1 Arm D: data-frame heartbeat echo
            // diagnostic. When set to "1" in a debug build, the wire-up
            // site at `AppContainer.rcDirectArmD` constructs `RcDirectArmD`
            // and `PhantomMessagingService.onStartCommand` short-circuits
            // the production Hybrid Ktor `transport.connect(...)` path
            // (Inv-ParallelArmIsolation). The diagnostic sends a canonical
            // payload `phantom:diagnostic:heartbeat-echo:v1:<seq>:<client_ms>`
            // every 15 s on the raw OkHttp WS and counts inbound echoes
            // returned by the relay (PR #279 echo handler, gated by
            // RELAY_ENABLE_HEARTBEAT_ECHO=1 on the VPS).
            //
            // Read-only outbound carve-out from Inv-RawArmReadOnly applies
            // narrowly to the canonical heartbeat payload prefix only; the
            // class never calls `webSocket.send(...)` with any other text.
            //
            // Expected values:
            //   "0" — Arm D disabled (default; RcDirectArmD not constructed,
            //         service falls through to next branch or production)
            //   "1" — Arm D enabled (sends heartbeat every 15 s, expects echo)
            //
            // Override via `local.properties` `rcDirectHeartbeatEcho=1` or
            // env RC_DIRECT_HEARTBEAT_ECHO. Release builds ignore the value
            // entirely (pinned to "0" below; runtime gate `BuildConfig.DEBUG`).
            // Design locked in `docs/tracks/rc-direct-stability1.md` §4 Arm D.
            val rcDirectHeartbeatEcho = localOrEnv("rcDirectHeartbeatEcho", "RC_DIRECT_HEARTBEAT_ECHO", "0")
            buildConfigField("String", "DEBUG_RC_DIRECT_HEARTBEAT_ECHO", "\"$rcDirectHeartbeatEcho\"")
            // RC-DIRECT-STABILITY1 Arm A.2: public non-Caddy TLS bypass
            // diagnostic. When non-empty in a debug build, the wire-up site
            // at `AppContainer.rcDirectArmA2` constructs `RcDirectArmA2`
            // pointing at this URL, and `PhantomMessagingService.onStartCommand`
            // short-circuits the production Hybrid Ktor `transport.connect(...)`
            // path (Inv-ParallelArmIsolation). Same read-only outbound
            // carve-out as Arm D — the only `webSocket.send(...)` call site
            // is the canonical heartbeat prefix
            // `phantom:diagnostic:heartbeat-echo:v1:<seq>:<client_ms>`.
            //
            // The URL value targets the §4 Arm A.2 PR-8a server-side stunnel
            // overlay deployed on the VPS host `:8444` (NOT `:8443` — that
            // port is held by production phantom-xray REALITY+WSS; the
            // cumulative PR-8a fixup history is documented in §4 Arm A.2
            // PR-8a implementation record). Expected values:
            //   ""                                     — Arm A.2 disabled (default)
            //   "wss://relay.phntm.pro:8444/ws"        — Arm A.2 production
            //                                            field test endpoint
            //                                            (Tele2 LTE through
            //                                            stunnel, no Caddy)
            //
            // Override via `local.properties` `debugRcDirectArmA2Url=...`
            // or env DEBUG_RC_DIRECT_ARM_A2_URL. Release builds ignore the
            // value entirely (pinned to "" in the release block + runtime
            // gate `BuildConfig.DEBUG`).
            //
            // Design locked in `docs/tracks/rc-direct-stability1.md` §4 Arm A.2
            // + §7 step 5e + PR-8a implementation record subsection.
            val debugRcDirectArmA2Url = localOrEnv("debugRcDirectArmA2Url", "DEBUG_RC_DIRECT_ARM_A2_URL", "")
            buildConfigField("String", "DEBUG_RC_DIRECT_ARM_A2_URL", "\"$debugRcDirectArmA2Url\"")
            // RC-DIRECT-STABILITY1 §10 T2: slow-POST byte-threshold diagnostic.
            // When non-empty in a debug build, the wire-up site at
            // `AppContainer.t2SlowPostDiag` constructs `T2SlowPostDiag`
            // pointing at this URL, and `PhantomMessagingService.onStartCommand`
            // short-circuits the production Hybrid Ktor `transport.connect(...)`
            // path one-shot (Inv-ParallelArmIsolation).
            //
            // T2 is NOT a reconnect-loop arm — it is a single 90-second POST
            // diagnostic that sends 40 960 bytes chunked (8 chunks × 5120
            // bytes, sink.flush() after each chunk, 10 s delay between
            // chunks). The discriminator is the relay `total_received`
            // counter at body complete OR mid-body abort. Verdict logic:
            //   - relay receives 14-32 KB and aborts → `net4people/bbs Issue
            //     #490` cumulative-bytes-per-TCP-connection-freeze hypothesis
            //     confirmed; Matrix-style 25-sec long-poll mandatory primary
            //   - relay receives all 40 960 bytes + 200 OK → byte-threshold
            //     refuted; Arm G (WS-over-Reality) is primary next test
            //
            // The T2 client uses a SEPARATE OkHttp profile from the
            // WebSocket arms (Vladislav hard gate 1 2026-06-06): connect=5s,
            // write=30s, read=60s, callTimeout=180s. The WebSocket arms'
            // `callTimeout(10s)` would kill the slow POST mid-test and
            // produce garbage data.
            //
            // Expected values:
            //   ""                                         — T2 disabled (default)
            //   "https://relay.phntm.pro/diag/slow-post"   — T2 production field
            //                                                test endpoint (Tele2
            //                                                LTE through Caddy)
            //
            // Override via `local.properties` `debugT2SlowPostUrl=...`
            // or env DEBUG_T2_SLOW_POST_URL. Release builds ignore the
            // value entirely (pinned to "" in the release block + runtime
            // gate `BuildConfig.DEBUG`).
            //
            // Design locked in `docs/tracks/rc-direct-stability1.md` §10 T2
            // mini-lock.
            val debugT2SlowPostUrl = localOrEnv("debugT2SlowPostUrl", "DEBUG_T2_SLOW_POST_URL", "")
            buildConfigField("String", "DEBUG_T2_SLOW_POST_URL", "\"$debugT2SlowPostUrl\"")
            // ADR-020 Phase 2: USE_TOR / USE_XRAY BuildConfig flags removed.
            // Outer transport selection is now a runtime decision driven by
            // the user's Privacy Mode (TransportManager walks the strategy
            // chain). Both Tor and Xray subsystems are always present in the
            // APK; whichever is needed by the current chain walk starts on
            // demand. Legacy local.properties keys `tor.enabled` /
            // `xray.enabled` are silently ignored.
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "RELAY_URL", "\"wss://relay.phntm.pro/ws\"")
            // RELAY_TOKEN removed in F11+F26 fix — relay no longer accepts a
            // shared `?token=`; auth is per-user signed challenge (see
            // KtorRelayTransport.buildAuthedWsUrl).
            buildConfigField(
                "String",
                "RELAY_ONION_URL",
                "\"ws://zmdrxlrkd7iv7ozvdl5nlhctsxgx6eyuqionp6xzriolymy3m6ioloyd.onion:80/ws\""
            )
            // PR-RC-DIRECT-WS-DEATH1 Phase 1: release builds ALWAYS pin the
            // diagnostic flag to "0". The runtime gate at the wire-up site
            // also checks `BuildConfig.DEBUG`, so even a corrupted release
            // build that somehow saw a non-"0" value would still skip the
            // diagnostic. Defence-in-depth per design note §7 step 3.
            buildConfigField("String", "DEBUG_RC_DIRECT_ARM", "\"0\"")
            // PR-RC-DIRECT-WS-DEATH1 Phase 2: release builds ALWAYS pin the
            // Phase 2 capture mode tag to "0" as well. Marker emit cannot
            // happen in release anyway (Arm B class is never wired in
            // release per the AppContainer `BuildConfig.DEBUG && ...` gate),
            // but the field is pinned for defence-in-depth and to keep the
            // release BuildConfig surface deterministic.
            buildConfigField("String", "DEBUG_PHASE2_MODE", "\"0\"")
            // RC-DIRECT-STABILITY1 Arm A: release builds ALWAYS pin the
            // Caddy-bypass URL to "". The runtime gate at the wire-up
            // site also checks `BuildConfig.DEBUG && DEBUG_BYPASS_URL.isNotEmpty()`,
            // so a release build can never construct `RcDirectArmA` even
            // if the field were corrupted. A plain-WS path that points at
            // any address from a release APK would violate Inv-NoLanInNsc
            // and the ADR-028 security baseline; this pin is the
            // defence-in-depth backstop locked in
            // `docs/tracks/rc-direct-stability1.md` §3 Inv-BypassIsLoopbackOnly.
            buildConfigField("String", "DEBUG_BYPASS_URL", "\"\"")
            // RC-DIRECT-STABILITY1 Arm C: release builds ALWAYS pin the
            // ping-interval matrix value to "0". The runtime gate at the
            // wire-up site also checks `BuildConfig.DEBUG &&
            // DEBUG_RC_DIRECT_PING_INTERVAL_MS != "0"`, so a release build
            // cannot construct `RcDirectArmC` even if the field were
            // corrupted. Any production-promoted ping-interval change is a
            // separate named PR per Inv-OnlyDiagnosticCadenceChange — this
            // pin is the defence-in-depth backstop.
            buildConfigField("String", "DEBUG_RC_DIRECT_PING_INTERVAL_MS", "\"0\"")
            // RC-DIRECT-STABILITY1 Arm D: release builds ALWAYS pin the
            // heartbeat echo flag to "0". The runtime gate at the wire-up
            // site also checks `BuildConfig.DEBUG &&
            // DEBUG_RC_DIRECT_HEARTBEAT_ECHO == "1"`, so a release build
            // cannot construct `RcDirectArmD` even if the field were
            // corrupted. Defence-in-depth backstop.
            buildConfigField("String", "DEBUG_RC_DIRECT_HEARTBEAT_ECHO", "\"0\"")
            // RC-DIRECT-STABILITY1 Arm A.2: release builds ALWAYS pin the
            // public non-Caddy TLS bypass URL to "". The runtime gate at
            // the wire-up site also checks `BuildConfig.DEBUG &&
            // DEBUG_RC_DIRECT_ARM_A2_URL.isNotEmpty()`, so a release build
            // cannot construct `RcDirectArmA2` even if the field were
            // corrupted. The :8444 stunnel endpoint is a diagnostic surface
            // only; user traffic must never route through it (§4 Arm A.2
            // Refined scope rule "No production traffic promotion"). This
            // pin is the defence-in-depth backstop.
            buildConfigField("String", "DEBUG_RC_DIRECT_ARM_A2_URL", "\"\"")
            // RC-DIRECT-STABILITY1 §10 T2: release builds ALWAYS pin the
            // slow-POST diagnostic URL to "". The runtime gate at the
            // wire-up site also checks `BuildConfig.DEBUG &&
            // DEBUG_T2_SLOW_POST_URL.isNotEmpty()`, so a release build
            // cannot construct `T2SlowPostDiag` even if the field were
            // corrupted. The `/diag/slow-post` relay endpoint is itself
            // env-flag-gated and returns 404 in production anyway, but
            // the client-side pin is the defence-in-depth backstop.
            buildConfigField("String", "DEBUG_T2_SLOW_POST_URL", "\"\"")
            // ADR-020 Phase 2: USE_TOR / USE_XRAY BuildConfig flags removed
            // for release as well — outer transport is selected at runtime by
            // TransportManager + the user's Privacy Mode preference.

            // Use the release key if keystores/signing.properties or SIGNING_*
            // env vars supplied valid credentials; otherwise fall back to debug
            // signing so contributors without the production key can still
            // build a release APK locally (it just won't be Play Store-ready).
            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseConfig.storeFile != null) {
                releaseConfig
            } else {
                logger.warn("No release keystore configured — falling back to debug signing for release build.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // Return default values (null/0/false) for unstubbed Android framework
        // calls in JVM unit tests. Required for android.util.Log calls inside
        // checkCallCapability (CallManagerGuardTest). Without this, any Log.*
        // invocation throws RuntimeException("Method not mocked").
        unitTests.isReturnDefaultValues = true
    }

    // Required by kmp-tor:resource-noexec-tor 409.x (ADR-016 Stage 2).
    // The bundled tor JNI library must be extracted to
    // ApplicationInfo.nativeLibraryDir at install time so dlopen() can find
    // it; legacy packaging keeps the .so files uncompressed and out of the
    // base.apk asset blob. Combined with
    // android.bundle.enableUncompressedNativeLibs=false in gradle.properties.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}
