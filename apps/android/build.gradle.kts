import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // Paparazzi — verified baseline (pinned in libs.versions.toml). Backs
    // Compose golden/snapshot tests for DesignV2 components. AGP 9.1.1 +
    // Kotlin 2.2.10 + KMP androidTarget compatibility was proven by the
    // 2026-07-30 F0-A spike on branch android/ui-designv2-foundation-2026-07-30
    // and has stayed green across F1 → F2b → onboarding-baseline-landing.
    // Do NOT self-switch to a different snapshot tool without approval; on
    // regression, stop and report.
    alias(libs.plugins.paparazzi)
}

// Load release signing credentials from keystores/signing.properties (gitignored)
// or SIGNING_* env vars (for CI). Release packaging fails closed when any
// credential is absent; debug builds and JVM tests remain key-free.
val signingProps = Properties().apply {
    val f = rootProject.file("keystores/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propertyKey: String, envKey: String): String? =
    signingProps.getProperty(propertyKey) ?: System.getenv(envKey)

val releaseSigningStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")
val releaseSigningStorePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
val releaseSigningKeyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
val releaseSigningKeyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseSigningStoreFile,
    releaseSigningStorePassword,
    releaseSigningKeyAlias,
    releaseSigningKeyPassword,
).all { !it.isNullOrBlank() }

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
    // Lookup priority: Gradle -P project property > local.properties > env var > default.
    // The -P branch was added in Round 12 step 4 so the operator can build the
    // diagnostic APK variants without editing local.properties — the canonical
    // command line is `./gradlew :apps:android:assembleDebug -PpollSkipLpAndPp=1`.
    // Backwards compatible: existing local.properties / env-var workflows are
    // unchanged.
    (project.findProperty(propKey) as? String) ?: localProps.getProperty(propKey) ?: System.getenv(envKey) ?: default

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
                // RC-RECONNECT-QUIESCENCE1 MC-2 (2026-07-01) — reflection
                // bridge for `androidUnitTest`-side callers reaching
                // `internal` seams that live on `shared:core:transport`
                // (`KtorRelayTransport.closeForTest` +
                // `cleanupInflightForTest` + related helpers). The
                // seams stay `internal` so they are not reachable as
                // Kotlin source-level API from a sibling module —
                // reflection bypasses source-level visibility, but the
                // reflection bridge file lives only in `androidUnitTest`,
                // which is excluded from any APK.
                implementation(kotlin("reflect"))
                implementation(project(":shared:core:transport"))
                // Robolectric hosts the JVM test environment for both:
                //   - Direct WSS diagnostic `DiagnosticTransportPinStoreTest`
                //     (real `SharedPreferences` round-trip against the
                //     debug-only pin store, from PR #399); and
                //   - F2b (android/ui-designv2-foundation-2026-07-30)
                //     Compose semantics tests — `ui-test-junit4-android`
                //     enables `createComposeRule()`, `ui-test-manifest`
                //     provides the merged test manifest.
                // All entries below are testImplementation-scoped and
                // never ship in release.
                implementation(libs.robolectric)
                // WSS PR #399 test additions:
                //   `kotlinx-coroutines-test` for `DiagnosticSendCoordinatorTest`
                //   (`runTest {}` + `backgroundScope` — suspending-fake
                //   ordering test).
                //   `androidx.test:core` for `ApplicationProvider`
                //   in the Robolectric-hosted pin-store test.
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.test.core)
                // L1 baseline-landing additions (DesignV2 F2b semantics
                // matrix):
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.compose.ui.test.manifest)
                // Session-order full-stack rig: a real PhantomDatabase on an
                // in-memory JDBC driver, so the receive path runs against the
                // production SqlDelight repositories rather than fakes.
                implementation(project(":shared:core:storage"))
                implementation(project(":shared:core:messaging"))
                implementation(project(":shared:core:crypto"))
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.sqlite.driver)
                // Keep JVM-hosted Android tests on the same JNA release as
                // the packaged app. JNA is the native-library loader for
                // libsodium here.
                implementation("com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings-jvm:0.9.5") {
                    exclude(group = "net.java.dev.jna")
                }
                implementation(libs.jna)
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
            // Override libsodium-bindings' legacy JNA 5.12.1. JNA 5.17+
            // includes the complete Android 16 KB page-size fix; keeping the
            // version explicit prevents the old libjnidispatch.so from being
            // repackaged if the transitive dependency changes again.
            implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
            implementation(libs.sqlcipher.android)
            implementation("androidx.biometric:biometric:1.1.0")
            // WebRTC for voice calls — provides PeerConnectionFactory, AudioTrack, IceCandidate.
            // stream/webrtc-android wraps Google's pre-built libwebrtc .aar so we avoid
            // compiling WebRTC from source (which requires depot_tools + Linux host).
            implementation("io.getstream:stream-webrtc-android:1.2.3")
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
    compileSdkMinor = 1

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
            if (releaseSigningConfigured) {
                storeFile = rootProject.file(requireNotNull(releaseSigningStoreFile))
                storePassword = requireNotNull(releaseSigningStorePassword)
                keyAlias = requireNotNull(releaseSigningKeyAlias)
                keyPassword = requireNotNull(releaseSigningKeyPassword)
            }
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
            // WebSocket arms (hard gate 1, locked 2026-06-06): connect=5s,
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
            // RC-DIRECT-STABILITY1 §14 Arm G: Reality-tunneled WS heartbeat
            // diagnostic. Strict boolean flag — `"1"` enables Arm G, any
            // other value (including `"true"`, `"yes"`, empty string, unset)
            // disables. Mirrors `RELAY_ENABLE_HEARTBEAT_ECHO` /
            // `RELAY_ENABLE_SLOW_POST_DIAG` strict-parse pattern.
            //
            // When enabled in a debug build, the wire-up site at
            // `AppContainer.rcDirectArmG` constructs `RcDirectArmG` with
            // `relayUrl = BuildConfig.RELAY_URL` (production WSS through
            // Caddy) AND the production `xrayService` singleton, and
            // `PhantomMessagingService.onStartCommand` short-circuits the
            // production Hybrid Ktor `transport.connect(...)` path between
            // Arm D and the production fall-through (precedence per §14
            // hard gate 7: A → A.2 → T2 → B → C → D → G → production).
            //
            // The single structural variable that changes vs Arm D is the
            // outer transport — Arm D's OkHttp client connects directly to
            // production `relay.phntm.pro:443` through Caddy on bare TLS;
            // Arm G's OkHttp client connects through a SOCKS5 proxy at
            // `127.0.0.1:<Ready.socksPort>` provided by the embedded
            // libXray daemon, which wraps the outbound stream in
            // VLESS+REALITY to the Stage 5E production endpoint and
            // forwards the decrypted inner stream from the server side
            // to production `relay.phntm.pro:443` (still Caddy on the
            // inner side, but originating from the operator VPS IP).
            //
            // Discriminator (3 outcomes per §14):
            //   PASS    — lifetime ≥ 10 min + echo round-trips + no Mode 2
            //             → Reality-primary realtime + 3.2b.1 safety net,
            //               ~3-4 weeks impl
            //   PARTIAL — lifetime ≥ 10 min + echo round-trips fail
            //             → REST + Matrix long-poll primary + Reality
            //               REST-fallback safety net, ~6-8 weeks impl
            //   FAIL    — Mode 2 persists OR byte-budget class persists
            //             → pure REST + Matrix 25-sec long-poll Option A,
            //               abandon Direct WS, ~6-8 weeks impl
            //
            // Expected values:
            //   ""  — Arm G disabled (default)
            //   "1" — Arm G enabled (strict; any other non-empty value also
            //         disabled, fails closed)
            //
            // Override via `local.properties` `debugRcDirectArmGViaReality=1`
            // or env DEBUG_RC_DIRECT_ARM_G_VIA_REALITY=1. Release builds
            // ignore the value entirely (pinned to "" in the release
            // block + runtime gate `BuildConfig.DEBUG`).
            //
            // Design locked in `docs/tracks/rc-direct-stability1.md` §14
            // Arm G mini-lock (PR #294 squash `f0b436a5` master 2026-06-05).
            val debugRcDirectArmGViaReality = localOrEnv(
                "debugRcDirectArmGViaReality",
                "DEBUG_RC_DIRECT_ARM_G_VIA_REALITY",
                "",
            )
            buildConfigField(
                "String",
                "DEBUG_RC_DIRECT_ARM_G_VIA_REALITY",
                "\"$debugRcDirectArmGViaReality\"",
            )
            // ADR-020 Phase 2: USE_TOR / USE_XRAY BuildConfig flags removed.
            // Outer transport selection is now a runtime decision driven by
            // the user's Privacy Mode (TransportManager walks the strategy
            // chain). Both Tor and Xray subsystems are always present in the
            // APK; whichever is needed by the current chain walk starts on
            // demand. Legacy local.properties keys `tor.enabled` /
            // `xray.enabled` are silently ignored.

            // Trek 2 Stage 2A (A6) — single runtime gate for every Stage 2B
            // long-poll behaviour (`wsActivePollJob`, `X-Phantom-Long-Poll: 1`
            // opt-in header, raised OkHttp callTimeout/readTimeout, jittered
            // hold consumption, persisted `lastSeenSeq` use, periodic
            // re-auth ceiling). Values follow the existing
            // `DEBUG_RC_DIRECT_ARM` String "1"/"0" idiom (locked
            // 2026-06-09). Debug builds default to "1" (long-poll on so
            // beta phones exercise the path); release builds pin to "0"
            // (defence in depth — Stage 2B promotion to production is a
            // separate named PR + a deliberate buildConfigField flip in this
            // release block).
            //
            // Override via `local.properties` `longPollV2Enabled=0` or env
            // `LONGPOLL_V2_ENABLED=0` to force long-poll off on a debug build
            // (e.g. when reproducing legacy short-poll behaviour during a
            // bisect). Stage 2A's `RestFallbackOrchestrator.longPollEnabled`
            // stores the parsed Boolean but does NOT consume it at runtime —
            // Stage 2B wires every consumer.
            val longPollV2Enabled = localOrEnv("longPollV2Enabled", "LONGPOLL_V2_ENABLED", "1")
            buildConfigField("String", "LONGPOLL_V2_ENABLED", "\"$longPollV2Enabled\"")

            // Trek 2 Stage 2B-B (C6 review-fix round 3 P2) — debug
            // override gate for the Tele2 LTE smoke S6 controllable
            // breaker trigger. The previous `BuildConfig.DEBUG`
            // gate was load-bearing for ALL three defence-in-depth
            // layers; if a future beta variant runs with
            // `isDebuggable = false`, the smoke runbook ("debug or
            // beta APK") would be silently invalidated because the
            // trigger surface would be unreachable. This dedicated
            // flag decouples the gate from `BuildConfig.DEBUG` so
            // a beta variant can opt into the trigger explicitly
            // by setting `s6DebugTriggerEnabled=1` in
            // `local.properties` (or `S6_DEBUG_TRIGGER_ENABLED=1`
            // env). Default `"1"` on debug builds; release pins to
            // `"0"`. Mirrors the existing `LONGPOLL_V2_ENABLED`
            // String "1"/"0" idiom (locked 2026-06-09).
            val s6DebugTriggerEnabled = localOrEnv(
                "s6DebugTriggerEnabled",
                "S6_DEBUG_TRIGGER_ENABLED",
                "1",
            )
            buildConfigField(
                "String",
                "S6_DEBUG_TRIGGER_ENABLED",
                "\"$s6DebugTriggerEnabled\"",
            )

            // QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK (2026-06-30).
            // L1 synthetic-trigger debug flag. Wires the optional
            // `debugForceMode2Enabled: Boolean` constructor parameter on
            // `phantom.core.transport.KtorRelayTransport` per the L1
            // mini-lock §4.6 / §5.1. Default `"0"` even on debug — the
            // operator must explicitly opt in via `-PdebugForceMode2=1`
            // or `DEBUG_FORCE_MODE_2_DETECTION=1` env. Mirrors the
            // String "1"/"0" idiom of `MODE_2_FAST_PATH_ENABLED` /
            // `MODE_2_STICKY_ENABLED` / `S6_DEBUG_TRIGGER_ENABLED`.
            // Release pin lives in the release-block declaration below.
            val debugForceMode2Enabled = localOrEnv(
                "debugForceMode2",
                "DEBUG_FORCE_MODE_2_DETECTION",
                "0",
            )
            buildConfigField(
                "String",
                "DEBUG_FORCE_MODE_2_DETECTION",
                "\"$debugForceMode2Enabled\"",
            )

            // QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK §13.1 / §13.4
            // gate-only carve-out (2026-06-30). RC-RECONNECT-QUIESCENCE1
            // gate-component activation flag. Pinned to "0" in release
            // (see release block below) — the gate-only carve-out brings
            // the `phantom.core.transport.WsReconnectGate` type-and-interface
            // surface to master AHEAD of the MC implementation PR; that
            // later PR will land the gate's state-transition logic on
            // `RestStateMachine`, the `WsReconnectGateProvider` /
            // `RewalkCoordinatorGateProvider` implementations, and the
            // 1172-LOC `WsReconnectGateTest.kt` integration suite plus
            // the orchestrator wiring test. Until then there is NO
            // production reader of this flag; it is reserved for the
            // forthcoming MC implementation PR's wiring layer. Debug
            // default `"0"` because (a) the gate code isn't yet wired
            // into any orchestrator path even on debug builds and (b)
            // mirrors the canary opt-in idiom of the existing
            // `MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED` /
            // `DEBUG_FORCE_MODE_2_DETECTION` flags. Operator can flip via
            // `-PreconnectQuiesce=1` (local.properties) or env
            // `RECONNECT_QUIESCENCE_ENABLED=1` once the MC PR's wiring
            // lands; on this carve-out the flag is a no-op at runtime
            // either way.
            val reconnectQuiesceEnabled = localOrEnv(
                "reconnectQuiesce",
                "RECONNECT_QUIESCENCE_ENABLED",
                "0",
            )
            buildConfigField(
                "String",
                "RECONNECT_QUIESCENCE_ENABLED",
                "\"$reconnectQuiesceEnabled\"",
            )

            // Trek 2 Stage 2B-B Round 12 step 3 — diagnostic toggle
            // that drops BOTH `X-Phantom-Long-Poll` AND
            // `X-Phantom-Padded-Poll` opt-in headers from the
            // `/relay/poll` request atomically when set to "1". The
            // strip is gated at the AppContainer wiring layer on
            // (a) `BuildConfig.DEBUG == true` AND (b) the active
            // PrivacyMode equalling `Standard`; flipping this field to
            // "1" alone is necessary but not sufficient to enable the
            // strip on a Privacy or Ghost session, by design (the
            // Vladislav-locked uniform-functionality rule disallows
            // silently degrading those tiers' wire shape).
            //
            // Debug builds default to "0" (no behavioural change vs
            // production wire shape). Operator opts in for a
            // body-size discriminator diagnostic run via
            // `local.properties` `pollSkipLpAndPp=1` or env
            // `POLL_SKIP_LP_AND_PP=1` before `assembleDebug`. Release
            // builds pin to "0" — this is a release-pin invariant
            // independent of the LONGPOLL_V2_ENABLED L6 pin, since
            // the council's security cross-check flagged any
            // diagnostic strip in release as a guardrail-C violation
            // and a privacy-mode metadata regression vector.
            //
            // PARTIAL-STRIP IS BANNED. The original Round 12 patch
            // proposal called this `POLL_SKIP_PADDED_BODY` and would
            // have stripped only `X-Phantom-Padded-Poll` while
            // retaining `X-Phantom-Long-Poll`. The council
            // (Layer 2 kmp + security + test) blocked it because
            // partial-strip violates the L1 LP+PP coupling lock
            // (scope-doc lock 1: "both or neither") and produces a
            // new wire-shape fingerprint distinguishable from the
            // padded production shape. The current name carries
            // BOTH letters so a future reader sees both surfaces are
            // touched together; a negative-grep test fences against
            // the rejected name reappearing.
            val pollSkipLpAndPp = localOrEnv(
                "pollSkipLpAndPp",
                "POLL_SKIP_LP_AND_PP",
                "0",
            )
            buildConfigField(
                "String",
                "POLL_SKIP_LP_AND_PP",
                "\"$pollSkipLpAndPp\"",
            )

            // T2 carrier-ceiling instrumentation client-side gate (2026-06-16
            // Option A Item 3 scope-lock). When set to "1" AND
            // `BuildConfig.DEBUG == true`, `PreKeyApiClient.publishWithRetry`
            // emits an additional `T2_DIAG_PUBLISH_TRACE` log line per
            // attempt carrying: client-side request_id (for correlation
            // with server-side `t2_diag_publish_chunk` / `_timeout` lines
            // by timestamp), attempt N/M, negotiated HTTP protocol
            // (`http/1.1` / `h2` / `h3` / `unknown_pre_response`), body
            // bytes, elapsed_ms at success/failure, and the failure-class
            // exception simpleName chain on retry-triggering throws.
            //
            // The instrumentation answers two open T2 questions:
            //   (a) Does the ~5 KB cliff happen exclusively on H1.1/H2
            //       connections (TCP byte-budget) and not on H3 (QUIC)?
            //   (b) Which timer fires first on a stalled publish — the
            //       server's 30 s axum TimeoutLayer or the client's 60 s
            //       OkHttp writeTimeout?
            //
            // Debug default "1" so an operator session pulling logs from
            // a debug APK captures the trace automatically. Release pins
            // to "0" in the release block below — release APKs continue
            // to emit only the existing `PREKEY_TRACE` lines (no T2 trace
            // overhead, no diagnostic-specific log surface visible to a
            // production user).
            //
            // Override via `local.properties` `relayT2DiagClient=0` or env
            // `RELAY_T2_DIAG_CLIENT=0` to force the trace off on a debug
            // build (e.g. while bisecting trace-volume regressions).
            val relayT2DiagClient = localOrEnv(
                "relayT2DiagClient",
                "RELAY_T2_DIAG_CLIENT",
                "1",
            )
            buildConfigField(
                "String",
                "RELAY_T2_DIAG_CLIENT",
                "\"$relayT2DiagClient\"",
            )

            // 3.6 Fast REST degradation (2026-06-18). When set to "1",
            // `AppContainer` reads `BuildConfig.MODE_2_FAST_PATH_ENABLED`
            // directly (no `BuildConfig.DEBUG` conjunction) and passes
            // `mode2FastPathEnabled=true` to `RestFallbackOrchestrator`,
            // which in turn passes it to `RestStateMachine`. The state
            // machine then bypasses the existing 2/3-cycle counter
            // wait and transitions to `RestMode.RestActive` on the
            // FIRST `Event.WsSessionEnded` matching the Mode-2
            // signature (zero inbound frames + parser-confirmed
            // OkHttp ping watchdog + duration inside the locked
            // 25 000-65 000 ms window). Detection latency on Tele2
            // LTE drops from ~93 s to ~30 s.
            //
            // Matched-signature telemetry (`REST_TRACE
            // mode_2_signature_matched action=...`) fires regardless
            // of this flag — operator can observe Mode-2 frequency on
            // builds where actuation is off.
            //
            // Default `"0"` on debug — opt-in via
            // `local.properties` `fastRestMode2=1` or env
            // `MODE_2_FAST_PATH_ENABLED=1`. Release builds pin to
            // literal `"0"` below; that literal IS the rollout
            // contract (no `BuildConfig.DEBUG` conjunction in
            // AppContainer's setter), so promotion to production
            // default is one-line: flip the release literal to `"1"`.
            val fastRestMode2 = localOrEnv(
                "fastRestMode2",
                "MODE_2_FAST_PATH_ENABLED",
                "0",
            )
            buildConfigField(
                "String",
                "MODE_2_FAST_PATH_ENABLED",
                "\"$fastRestMode2\"",
            )

            // R3.6 Sticky-per-route Fast REST degradation (2026-06-20).
            // Opt-in via `-PmodeSticky=1` (local.properties) or env MODE_2_STICKY_ENABLED=1.
            // Default "0" on debug. Release builds pin to literal "0" below.
            // Requires MODE_2_FAST_PATH_ENABLED="1" (build-time invariant in RestStateMachine).
            val modeSticky = localOrEnv(
                "modeSticky",
                "MODE_2_STICKY_ENABLED",
                "0",
            )
            buildConfigField(
                "String",
                "MODE_2_STICKY_ENABLED",
                "\"$modeSticky\"",
            )

            // B2-K8 client-side hold-override diagnostic (2026-07-06).
            // Companion to relay-side PR #370 squash `c5e077db`. When
            // this field is a non-sentinel non-negative integer, the
            // Android /relay/poll builder appends `?hold=N` (server
            // clamps [0, 30]; the client sends the raw value). The
            // relay MUST have `RELAY_DIAG_WS_K8_CLIENT_HOLD_OVERRIDE_ENABLED=1`
            // set in its .env for the parameter to be honoured; both
            // ends default to off so bare debug builds are byte-identical
            // to pre-K8. Encoded as the file-wide String idiom (mirror
            // of `MODE_2_STICKY_ENABLED` / `DEBUG_FORCE_MODE_2_DETECTION`);
            // the debug reader parses back to Int at request-build time.
            // Sentinel `"-1"` = "no override, no ?hold param appended".
            // Debug default `"-1"` so operator opts in explicitly via
            // `-PdebugK8HoldOverrideSeconds=10` or
            // `DEBUG_K8_HOLD_OVERRIDE_SECONDS=10` env. A shared-prefs
            // key (`debug_k8_hold_override_seconds`, Int) is read
            // FIRST at each poll build so the operator can change the
            // hold value between polls without rebuilding the APK;
            // BuildConfig is the fallback when prefs is absent OR
            // sentinel. Release pin lives in the release block below.
            val debugK8HoldOverrideSeconds = localOrEnv(
                "debugK8HoldOverrideSeconds",
                "DEBUG_K8_HOLD_OVERRIDE_SECONDS",
                "-1",
            )
            buildConfigField(
                "String",
                "DEBUG_K8_HOLD_OVERRIDE_SECONDS",
                "\"$debugK8HoldOverrideSeconds\"",
            )

            // B2-K8 companion — force `Connection: close` on /relay/poll
            // + `ConnectionPool.evictAll()` after each poll. Narrow scope
            // to the poll path only (send/ack/auth unaffected). Default
            // `"0"` on debug — operator opts in explicitly via
            // `-PdebugK8ConnectionClose=1` or env DEBUG_K8_CONNECTION_CLOSE=1.
            // Prefs key `debug_k8_connection_close` (Boolean) overrides
            // when present. Release pins to literal `"0"` below. The
            // wire behaviour matches the existing `Connection: close`
            // request header at `buildPollRequest` — the interceptor
            // is idempotent for the header (overwrite, not append) and
            // additionally forces `pool.evictAll()` post-response so
            // the next poll opens a fresh TCP+TLS connection.
            val debugK8ConnectionClose = localOrEnv(
                "debugK8ConnectionClose",
                "DEBUG_K8_CONNECTION_CLOSE",
                "0",
            )
            buildConfigField(
                "String",
                "DEBUG_K8_CONNECTION_CLOSE",
                "\"$debugK8ConnectionClose\"",
            )

            // B2-K11 §5C debug-only session-token observer (2026-07-09).
            // When flipped to `"1"` on a debug build, the AppContainer
            // wire-up constructs a `debugSessionTokenObserver` that emits
            // one `K11_5C_TOKEN_DEBUG token=... expiresInMs=...` line to
            // logcat immediately after each fresh session token is cached
            // by `RestFallbackOrchestrator.acquireOrRefreshToken`. Purpose:
            // let the K11 §5C probe extract the live bearer token without
            // MITM (which would replace the OkHttp TLS ClientHello that
            // 5C is designed to preserve) and without persisting the
            // token on disk. Default `"0"` — operator opts in explicitly
            // via `-PdebugK11_5cTokenLogEnabled=1` or
            // `DEBUG_K11_5C_TOKEN_LOG_ENABLED=1` env. Release pin lives
            // in the release block below. Locked design in
            // `C:/temp/direct-wss-fix-family-2026-07-09/
            // k11-5c-authenticated-poll-clone-mini-lock.md` §1.5 + §2.3.
            val debugK11_5cTokenLogEnabled = localOrEnv(
                "debugK11_5cTokenLogEnabled",
                "DEBUG_K11_5C_TOKEN_LOG_ENABLED",
                "0",
            )
            buildConfigField(
                "String",
                "DEBUG_K11_5C_TOKEN_LOG_ENABLED",
                "\"$debugK11_5cTokenLogEnabled\"",
            )

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
            // RC-DIRECT-STABILITY1 §14 Arm G: release builds ALWAYS pin the
            // Reality-tunneled WS diagnostic flag to "". The runtime gate
            // at the wire-up site also checks `BuildConfig.DEBUG &&
            // DEBUG_RC_DIRECT_ARM_G_VIA_REALITY == "1"`, so a release build
            // cannot construct `RcDirectArmG` even if the field were
            // corrupted. Arm G reuses the production `xrayService`
            // singleton (which IS shipped in release for private mode);
            // pinning the diagnostic flag prevents release builds from
            // ever entering the Arm G short-circuit branch in the Service
            // and accidentally routing user traffic through the
            // diagnostic class. Defence-in-depth backstop per §14 hard
            // gate 1 + WORKING_RULES rule 8 narrow carve-out.
            buildConfigField("String", "DEBUG_RC_DIRECT_ARM_G_VIA_REALITY", "\"\"")
            // Trek 2 Stage 2B-D rollout (2026-06-16): release builds NOW
            // pin the long-poll V2 gate to "1". The AppContainer wire-up
            // reads this value and computes the Boolean passed to
            // `RestFallbackOrchestrator.longPollEnabled`, so release builds
            // emit `X-Phantom-Long-Poll: 1` + `X-Phantom-Padded-Poll: 1`
            // opt-in headers on every `/relay/poll` request — and the
            // production relay (Round 14 deployed via PR #310 squash
            // `345d9761`, `RELAY_POLL_CHUNKED_FLUSH=1` set in the VPS
            // .env on 2026-06-16) responds with the paced chunked-flush
            // 4 × 1152-byte body that closes the Tele2 LTE byte-budget
            // stall observed pre-Round-14.
            //
            // Stage 2B-D entry criteria met: Sprint 2b-A + 2b-B + 2b-C
            // merged + Stage 2B-D Tele2 LTE integration smoke 2026-06-16
            // PASS (all three layers — Round 14 wire, Sprint 2a guard,
            // Sprint 2b-C inbound-repair + promotion). See the
            // `docs/PROJECT_LOG.md` 2026-06-16 entry "Stage 2B-D Tele2
            // LTE integration smoke PASS + PR #310 MERGED + Round 14
            // LIVE on production" and the amended L10 in
            // `docs/tracks/sprint-2b-opk-pending-session-scope.md`.
            //
            // Rollback contract: reverting this line to `"\"0\""`
            // returns release builds to the pre-Stage-2B-D wire shape
            // (no LP/PP opt-in, legacy mono padded poll). Production
            // relay can keep `RELAY_POLL_CHUNKED_FLUSH=1` set
            // independently — without the LP/PP opt-in headers the
            // server falls back to mono padded poll for those clients,
            // so the relay-side flag is a safe no-op for any non-opted-
            // in client. This decoupling is the rollback safety contract.
            buildConfigField("String", "LONGPOLL_V2_ENABLED", "\"1\"")
            // Trek 2 Stage 2B-B (C6 review-fix round 3 P2) —
            // release builds ALWAYS pin the S6 debug trigger flag
            // to `"0"`. The AppContainer wire-up reads this value
            // and gates the receiver registration + the
            // orchestrator constructor flag on it (independent of
            // `BuildConfig.DEBUG`). A release APK can never reach
            // the trigger path even if the receiver were
            // dispatched. Defence-in-depth backstop per the same
            // OQ7 idiom as `LONGPOLL_V2_ENABLED`.
            buildConfigField("String", "S6_DEBUG_TRIGGER_ENABLED", "\"0\"")
            // QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK (2026-06-30).
            // Release builds ALWAYS pin the L1 synthetic-trigger debug
            // flag to `"0"`. The AppContainer wire-up reads this value
            // and refuses to call `KtorRelayTransport.debugForceMode2Synthetic`;
            // the constructor-injected Boolean defaults to `false` in
            // release so even an out-of-band invocation refuses with
            // `RefusedDisabled`. Defence-in-depth backstop per the
            // String "1"/"0" idiom of the other release-pinned flags.
            buildConfigField("String", "DEBUG_FORCE_MODE_2_DETECTION", "\"0\"")

            // QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK §13.1 / §13.4 gate-only
            // carve-out (2026-06-30). Release builds ALWAYS pin
            // `RECONNECT_QUIESCENCE_ENABLED` to literal `"0"`. The gate
            // component (`WsReconnectGate`) shipped by this carve-out is a
            // pure type-and-interface surface — no production code path
            // reads this flag yet, and the carve-out adds no orchestrator
            // wiring. The MC implementation PR will land the gate's
            // state-transition logic on `RestStateMachine` plus the
            // `WsReconnectGateProvider` / `RewalkCoordinatorGateProvider`
            // implementations, at which point an `AppContainer` reader
            // will gate on `BuildConfig.RECONNECT_QUIESCENCE_ENABLED ==
            // "1"`. This pin is the load-bearing rollout knob for that
            // future wiring — promoting RC-RECONNECT-QUIESCENCE1 to
            // production after Wi-Fi smoke PASS is a deliberate one-line
            // flip of this literal in a separate named PR. Defence-in-
            // depth backstop per the same idiom as
            // `MODE_2_FAST_PATH_ENABLED` / `MODE_2_STICKY_ENABLED` /
            // `DEBUG_FORCE_MODE_2_DETECTION`.
            buildConfigField("String", "RECONNECT_QUIESCENCE_ENABLED", "\"0\"")

            // Trek 2 Stage 2B-B Round 12 step 3 — release pin. The
            // diagnostic LP+PP-strip toggle MUST be off in release
            // regardless of any -PpollSkipLpAndPp Gradle property,
            // local.properties entry, or env variable that an
            // operator could pass at build time. The council security
            // cross-check identified the toggle as a privacy-mode
            // metadata regression vector if ever active in a release
            // shipped to users; pinning here on top of the
            // debug-only `BuildConfig.DEBUG` runtime gate is
            // belt-and-braces.
            buildConfigField("String", "POLL_SKIP_LP_AND_PP", "\"0\"")

            // T2 carrier-ceiling instrumentation client-side gate (2026-06-16
            // Option A Item 3 scope-lock). Release builds ALWAYS pin the
            // client-side T2 diag trace flag to "0" — a release APK can
            // never emit `T2_DIAG_PUBLISH_TRACE` lines even if the debug
            // block's runtime override somehow leaked into a release
            // BuildConfig. The runtime gate at the trace emission point
            // checks `BuildConfig.DEBUG && BuildConfig.RELAY_T2_DIAG_CLIENT
            // == "1"`; with `BuildConfig.DEBUG == false` AND this field
            // pinned to `"0"`, the trace is unreachable from release builds
            // by both halves of the AND. Defence-in-depth backstop per the
            // same OQ7 + OQ11 idiom as `LONGPOLL_V2_ENABLED` and
            // `POLL_SKIP_LP_AND_PP`.
            buildConfigField("String", "RELAY_T2_DIAG_CLIENT", "\"0\"")

            // 3.6 Fast REST degradation (2026-06-18). Release builds
            // pin the gate to literal "0" as the SOLE production-side
            // mechanism for keeping actuation off. AppContainer reads
            // `BuildConfig.MODE_2_FAST_PATH_ENABLED` directly without
            // a `BuildConfig.DEBUG` conjunction — so a future named
            // PR can promote actuation by changing ONLY this line to
            // `"1"` and shipping the release build. If the runtime
            // gate had `BuildConfig.DEBUG &&` in it, the flip would
            // be permanently ineffective. The literal here is the
            // load-bearing rollout knob.
            buildConfigField("String", "MODE_2_FAST_PATH_ENABLED", "\"0\"")

            // R3.6 Sticky-per-route Fast REST degradation (2026-06-20).
            // Release builds ALWAYS pin to literal "0". Promotion is a
            // deliberate one-line flip in a separate named PR after smoke PASS.
            // Requires MODE_2_FAST_PATH_ENABLED="1" (build-time invariant).
            buildConfigField("String", "MODE_2_STICKY_ENABLED", "\"0\"")

            // B2-K8 client-side hold-override diagnostic (2026-07-06).
            // Release builds ALWAYS pin the K8 override to the sentinel
            // literal `"-1"` so a release APK can never append `?hold=N`
            // to /relay/poll. The AppContainer wire-up also gates the
            // provider on the debug-source-set helper class which is
            // absent from the release compilation unit; the release pin
            // here is the defence-in-depth backstop against a corrupted
            // local.properties / env leak into a release BuildConfig.
            // Mirrors the release-pin idiom of `DEBUG_FORCE_MODE_2_DETECTION`
            // / `MODE_2_STICKY_ENABLED`.
            buildConfigField("String", "DEBUG_K8_HOLD_OVERRIDE_SECONDS", "\"-1\"")

            // B2-K8 companion — release builds ALWAYS pin the
            // `Connection: close` + `evictAll()` interceptor flag to
            // literal `"0"`. Release APK never installs the interceptor
            // (provider path returns `false` under this pin AND the
            // absence of prefs override AND `BuildConfig.DEBUG == false`).
            buildConfigField("String", "DEBUG_K8_CONNECTION_CLOSE", "\"0\"")

            // B2-K11 §5C debug-only session-token observer (2026-07-09).
            // Release builds ALWAYS pin the flag to `"0"` so a release
            // APK can never construct the debug observer even if the
            // outer `BuildConfig.DEBUG` gate is bypassed by a mistake
            // upstream. The AppContainer double-gate
            // (`BuildConfig.DEBUG && DEBUG_K11_5C_TOKEN_LOG_ENABLED == "1"`)
            // short-circuits to `null` under this pin. Locked design in
            // `C:/temp/direct-wss-fix-family-2026-07-09/
            // k11-5c-authenticated-poll-clone-mini-lock.md` §1.5 + §2.3.
            buildConfigField("String", "DEBUG_K11_5C_TOKEN_LOG_ENABLED", "\"0\"")

            // ADR-020 Phase 2: USE_TOR / USE_XRAY BuildConfig flags removed
            // for release as well — outer transport is selected at runtime by
            // TransportManager + the user's Privacy Mode preference.

            signingConfig = signingConfigs.getByName("release")
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
        // F2b (android/ui-designv2-foundation-2026-07-30) — Robolectric-backed
        // Compose semantics tests need access to Android resources
        // (AndroidManifest merging, string/plurals resolution, activity theme
        // lookup). Paparazzi 2.0-alpha05 renders correctly either way but
        // benefits from the same setting; enabling it here is a net-safe
        // change for the semantics test infrastructure.
        unitTests.isIncludeAndroidResources = true
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

// F2b (android/ui-designv2-foundation-2026-07-30) — the compose-ui-test-manifest
// artifact must be added at the AGP variant configuration level (`debugImplementation`),
// NOT inside `kotlin { sourceSets { androidUnitTest { } } }`, so its embedded
// AndroidManifest.xml — which registers `androidx.activity.ComponentActivity` for
// Compose-test `createComposeRule()` — participates in the debug variant's manifest
// merge. Without this, Robolectric-hosted semantics tests fail with
// "Unable to resolve activity for Intent { cmp=phantom.android/androidx.activity.ComponentActivity }".
// The artifact is <3 KB, contains only the manifest, and DOES NOT ship in release
// because release doesn't consume `debugImplementation` configurations.
dependencies {
    "debugImplementation"(libs.androidx.compose.ui.test.manifest)
}

// WSS-3 audit ROUND-22 P0-2: canonical producer fixtures under
// `docs/tracks/direct-wss/operator-package/fixtures/` are the SINGLE
// canonical shape (contract §5). The Kotlin conformance test loads
// them as a JVM classpath resource via `getResourceAsStream`. This
// task copies them into `build/generated/canonicalFixtures/` and
// registers that directory as a resource root for the AGP unit-test
// source set. NO second checked-in copy is allowed by architect
// ROUND-22 P0-2; this generated copy has declared task input/output
// so incremental builds stay correct.
val wss3CanonicalFixturesOut =
    layout.buildDirectory.dir("generated/canonicalFixtures/wss3").get().asFile
val copyCanonicalWss3Fixtures by tasks.registering(Copy::class) {
    val srcDir = rootProject.file(
        "docs/tracks/direct-wss/operator-package/fixtures"
    )
    from(srcDir) {
        include("canonical_network_profile.*.json")
    }
    into(wss3CanonicalFixturesOut)
    inputs.dir(srcDir).withPropertyName("wss3CanonicalFixturesSrc")
}
android {
    // AGP SourceSet API only accepts plain paths, not Providers.
    sourceSets.getByName("test").resources.srcDir(wss3CanonicalFixturesOut)
}
// Ensure `processTestResources` (which packages test-resource
// directories into the JVM classpath) waits for the copy.
tasks.matching { it.name.startsWith("processDebugUnitTest") || it.name == "processTestResources" }
    .configureEach { dependsOn(copyCanonicalWss3Fixtures) }

// The host runner executes complementary JUnit categories in separate Gradle
// invocations, preserving all Paparazzi plugin/AGP setup on the original task.
// Regular tests deliberately share a JVM so inter-test leaks remain visible.
// No property preserves the historical mixed run for diagnostic comparisons.
val hostTestEngine = providers.gradleProperty("phantomHostTestEngine").orNull
require(hostTestEngine == null || hostTestEngine in setOf("regular", "paparazzi")) {
    "phantomHostTestEngine must be regular or paparazzi"
}
if (hostTestEngine != null) {
    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
        useJUnit {
            val category = "phantom.android.testing.PaparazziTestEngine"
            if (hostTestEngine == "paparazzi") includeCategories(category)
            else excludeCategories(category)
        }
        setForkEvery(if (hostTestEngine == "paparazzi") 1L else 0L)
        maxParallelForks = 1
    }
    // AGP/KMP assign report locations later than the Test configuration above.
    // Set these after plugin evaluation so the second engine cannot overwrite
    // the first engine's XML, HTML or binary results.
    gradle.projectsEvaluated {
        tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
            val resultName = "$name-$hostTestEngine"
            reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$resultName"))
            reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$resultName"))
            binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/$resultName/binary"))
        }
    }
}

// --------------------------------------------------------------------------
// Paparazzi determinism via CONDITIONAL per-class JVM isolation
// (Onboarding Commit 2 round-2 REDLINE P1-2, 2026-08-01)
// --------------------------------------------------------------------------
// Diagnostic evidence for the pixel-drift leak (see the prior handoff of
// Commit 2 amend 3): full-shelf `recordPaparazziDebug` and full-shelf
// `verifyPaparazziDebug` are separate Gradle tasks each spawning their
// own test JVM. Under the "full context" (many test classes loaded per
// JVM), the record JVM and verify JVM diverge in class-load / JIT /
// font-cache / LayoutLib-session initialisation state in a stable but
// non-identical way between the two tasks, producing sub-percent pixel
// drift on 6 goldens (0.014..0.470 %). In isolation (only one test
// class per JVM) the divergence disappears entirely — the
// `record isolated → verify isolated` experiment returned GREEN for
// the representative failing class.
//
// The fix: fork a fresh test JVM per class, but ONLY when a Paparazzi
// task is scheduled in the current Gradle invocation. Round-1 REDLINE
// initially set `unitTests.all { forkEvery=1; maxParallelForks=1 }`
// unconditionally, which also isolated semantics + transport + other
// non-snapshot tests. Architect P1-2 (round 2): scope the isolation
// strictly to Paparazzi's tasks so a plain
// `./gradlew testDebugUnitTest` (semantics, transport, WsLifecycle,
// etc.) keeps its previous non-isolated, potentially faster forking
// shape and does not hide leaks between "regular" unit-test classes.
//
// Mechanism: `gradle.taskGraph.whenReady { ... }` fires after Gradle
// has resolved the task graph but before task execution begins. If any
// scheduled task's name starts with `recordPaparazzi` or
// `verifyPaparazzi`, apply `forkEvery = 1L; maxParallelForks = 1` to
// every `Test` task (both the Paparazzi task variants and the
// underlying `testDebugUnitTest` they depend on). Otherwise, leave
// the default forking behaviour untouched.
//
// `maxParallelForks = 1` keeps forks sequential — parallel forks would
// reintroduce another class of nondeterminism (LayoutLib global-state
// contention) and defeat the fix.
gradle.taskGraph.whenReady {
    val paparazziInGraph = allTasks.any { task ->
        val n = task.name
        n.startsWith("recordPaparazzi") || n.startsWith("verifyPaparazzi")
    }
    check(!paparazziInGraph || hostTestEngine != "regular") {
        "A regular host run cannot record or verify Paparazzi snapshots"
    }
    if (paparazziInGraph) {
        tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
            setForkEvery(1L)
            maxParallelForks = 1
        }
    }
}

// --------------------------------------------------------------------------
// verifyR8StripsTestSeams — path-2 step 2 ProGuard narrowing verifier
// --------------------------------------------------------------------------
// Fails the release build if R8's `mapping.txt` shows that a forbidden
// CLASS or MEMBER pattern survived shrinking on any `phantom.*` class.
// Wired as `finalizedBy assembleRelease` so the verification runs
// immediately after the release APK is assembled and before the build is
// considered successful.
//
// Forbidden patterns (a class simple name OR a member name matching any
// of these is a hard failure):
//
//   - `*ForTest*`     — production-test seams (e.g. `submitEventNow_internalForTest`)
//   - `debugForce*`   — debug-only synthetic triggers (e.g. the future
//                       `debugForceMode2Synthetic` from the L1 mini-lock)
//   - `*Synthetic*`   — any class or member name carrying the synthetic
//                       trigger discipline tell (e.g. the future
//                       `SyntheticTriggerResult` sealed class)
//
// The patterns are an explicit deny-list, NOT an allow-list. If a future
// PR introduces a new debug-only surface, the surface MUST be either
// stripped by R8 (no rule keeps it) or named such that it matches one of
// the deny patterns above so this verifier catches a regression.
//
// Forward-looking design: today (path-2 step 2 ship) `KtorRelayTransport`
// has no surviving `*ForTest*` / `debugForce*` / `*Synthetic*` members
// on master HEAD, so the wildcard removal alone does not generate
// violations. The verifier is the load-bearing catch for the FUTURE
// regression (when the L1 implementation PR introduces
// `debugForceMode2Synthetic` + `SyntheticTriggerResult`, re-introducing
// the wildcard would then produce violations the verifier catches). The
// `KtorRelayTransportProguardNarrowingPinTest` structural check in
// `androidUnitTest` is today's catch — it asserts the wildcard is not
// present as a live ProGuard directive in `proguard-rules.pro`.
//
// Parser-level self-test: the `doFirst` block exercises the parser with
// a synthetic mapping fixture that covers (a) ranged R8 method lines
// like `1:1:void debugForceMode2Synthetic(long):123:123 -> a` whose
// naive `substringAfterLast(":")` parsing would extract the line number
// instead of the method name, (b) class-level deny matches against the
// simple-name segment of a phantom class, and (c) R8-internal class
// containers (`$$ExternalSyntheticLambda`) which MUST be ignored. The
// self-test fails the task before the real run if the parser regresses.

/**
 * Returns `phantom.foo.Bar#memberName` style violation entries for every
 * member or class header in [mappingLines] whose original name matches
 * one of [denyPatterns]. R8-internal lambda containers
 * (`$$ExternalSyntheticLambda` / `$$InternalSyntheticLambda`) are skipped
 * at the class level. Members whose original names begin with `$`
 * (compiler-synthesised, e.g., `$r8$classId`, `f$0`) are skipped.
 *
 * Class headers themselves are also matched against the deny patterns,
 * so a future `phantom.foo.SyntheticTriggerResult` surviving R8 trips
 * the verifier even if none of its members match a deny pattern by
 * themselves.
 *
 * Method names are extracted from the part BEFORE the opening `(` so
 * ranged R8 method lines like `1:1:void name(args):startLine:endLine`
 * yield the method's original simple name, not the line-range tail.
 */
fun parseMappingForForbiddenSurvivors(
    mappingLines: Sequence<String>,
    denyPatterns: List<Regex>,
): List<String> {
    val r8SyntheticClassMarker = "\$\$"
    val compilerSyntheticMemberPrefix = "\$"
    val violations = mutableListOf<String>()
    var currentClass: String? = null
    for (rawLine in mappingLines) {
        val line = rawLine.trimEnd()
        if (line.isEmpty() || line.startsWith("#")) {
            continue
        }
        if (!line.startsWith("    ") && line.contains(" -> ")) {
            // Class header line: `phantom.core.transport.KtorRelayTransport -> a.b.c:`
            val originalName = line.substringBefore(" -> ").trim()
            currentClass = originalName.takeIf {
                it.startsWith("phantom.") &&
                    !it.contains(r8SyntheticClassMarker)
            }
            // Check the class's simple name against deny patterns too. A
            // surviving class whose simple name matches (e.g.,
            // `SyntheticTriggerResult`) is a violation independent of
            // member matches.
            val simpleName = currentClass?.substringAfterLast('.')
            if (simpleName != null && denyPatterns.any { it.matches(simpleName) }) {
                violations += currentClass!!
            }
        } else if (currentClass != null && line.startsWith("    ")) {
            // Member line. Two shapes:
            //
            //   Field: `    Type field -> a`
            //          or `    Type field:line:line -> a`
            //   Method: `    ReturnType method(args) -> a`
            //           or `    line:line:ReturnType method(args):line:line -> a`
            //
            // Extract the original member name by:
            //   1. Cut off the ` -> obfuscated` tail.
            //   2. For methods, cut off the `(args)...` tail by splitting
            //      on the first `(`.
            //   3. The member name is the LAST whitespace-delimited token
            //      in the surviving prefix (for methods: after the return
            //      type; for fields: after the field type).
            val beforeArrow = line.trim().substringBefore(" -> ")
            val beforeParen = beforeArrow.substringBefore("(")
            val memberName = beforeParen.substringAfterLast(' ')
            if (memberName.startsWith(compilerSyntheticMemberPrefix)) {
                continue
            }
            if (denyPatterns.any { it.matches(memberName) }) {
                violations += "$currentClass#$memberName"
            }
        }
    }
    return violations
}

val verifyR8StripsTestSeams = tasks.register("verifyR8StripsTestSeams") {
    group = "verification"
    description =
        "Verifies no `*ForTest*` / `debugForce*` / `*Synthetic*` patterns survive R8 on any `phantom.*` class or member in the release `mapping.txt`."

    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    inputs.file(mappingFile).withPropertyName("mappingTxt")

    val forbiddenPatterns = listOf(
        Regex(".*ForTest.*"),
        Regex("debugForce.*"),
        Regex(".*Synthetic.*"),
        // B2-K8 diagnostic (2026-07-06): the K8DebugConnectionCloseInterceptor
        // class (androidMain, PR #<pending>) must strip from release. The
        // interceptor is behind a provider gate that always returns false in
        // release (BuildConfig.DEBUG_K8_CONNECTION_CLOSE hardpinned to "0" +
        // no Settings-Diagnostics UI to flip the prefs key), so R8 dead-code
        // elimination removes the class. This deny pattern is the release
        // -APK verification backstop against a code path that accidentally
        // holds a live reference to the class.
        Regex("K8Debug.*"),
    )

    doFirst {
        // Parser self-test: exercise the parser against a synthetic mapping
        // fixture so a future refactor that breaks the parser is caught
        // before it silently masks the real run. Covers ranged R8 method
        // lines (P1 regression), class-level deny matches, and R8-internal
        // lambda container exclusions.
        val syntheticMapping = """
            phantom.core.transport.KtorRelayTransport -> a.a:
                1:1:void connect():100:120 -> a
                1:1:void debugForceMode2Synthetic(long):234:240 -> b
                int submitEventNow_internalForTest -> c
                java.lang.Object SyntheticTriggerResult -> d
                kotlinx.coroutines.flow.Flow wsSessionLifecycle -> e
                kotlinx.coroutines.flow.Flow getWsSessionLifecycle():350:350 -> e
                int ${'$'}r8${'$'}classId -> f
            phantom.foo.SyntheticTriggerResult -> b.a:
                void foo() -> a
            phantom.foo.SomeForTestHelper -> c.a:
                void irrelevant() -> a
            phantom.foo.bar.${'$'}${'$'}ExternalSyntheticLambda0 -> d.a:
                int ${'$'}r8${'$'}classId -> a
                java.lang.Object f${'$'}0 -> b
        """.trimIndent()
        val actual = parseMappingForForbiddenSurvivors(
            mappingLines = syntheticMapping.lineSequence(),
            denyPatterns = forbiddenPatterns,
        ).toSet()
        val expected = setOf(
            // KtorRelayTransport survives but harbours forbidden members:
            "phantom.core.transport.KtorRelayTransport#debugForceMode2Synthetic",
            "phantom.core.transport.KtorRelayTransport#submitEventNow_internalForTest",
            "phantom.core.transport.KtorRelayTransport#SyntheticTriggerResult",
            // A whole class whose simple name carries a deny pattern:
            "phantom.foo.SyntheticTriggerResult",
            "phantom.foo.SomeForTestHelper",
        )
        check(actual == expected) {
            "verifyR8StripsTestSeams parser self-test FAILED.\n" +
                "  expected violations: $expected\n" +
                "  actual violations:   $actual\n" +
                "The parser is broken — the deny patterns will not catch real R8 mapping output."
        }
        logger.lifecycle("verifyR8StripsTestSeams self-test PASS — parser handles ranged method lines + class-level deny.")
    }

    doLast {
        val mapping = mappingFile.get().asFile
        check(mapping.exists()) {
            "Expected R8 mapping at ${mapping.absolutePath} but it does not exist. " +
                "Is `isMinifyEnabled = true` set on the release buildType?"
        }
        val violations = mapping.useLines { lines ->
            parseMappingForForbiddenSurvivors(
                mappingLines = lines,
                denyPatterns = forbiddenPatterns,
            )
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "verifyR8StripsTestSeams FAILED — ${violations.size} forbidden " +
                            "class(es) / member(s) survived R8 on `phantom.*` classes:",
                    )
                    violations.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine(
                        "Either (a) the offending class / member must be removed from production code, " +
                            "(b) `apps/android/proguard-rules.pro` must stop keeping it, or " +
                            "(c) the deny patterns in `verifyR8StripsTestSeams` are stale and " +
                            "must be updated (operator decision, not silent edit).",
                    )
                },
            )
        }
        logger.lifecycle(
            "verifyR8StripsTestSeams PASS — no `*ForTest*` / `debugForce*` / " +
                "`*Synthetic*` classes or members survived on any `phantom.*` class.",
        )
    }
}

// --------------------------------------------------------------------------
// verifyR8KeepsGomobileJniSurface — POSITIVE release verification
// --------------------------------------------------------------------------
// The companion of `verifyR8StripsTestSeams`. That task proves forbidden
// surfaces are ABSENT; this one proves a required surface is PRESENT.
// Stage 2 physical acceptance (2026-09-14) failed on exactly the gap
// between those two statements: seam stripping was green while the
// vendored gomobile runtime had been shrunk away, and the release process
// aborted with `failed to find method Seq.getRef` out of
// `Java_go_Seq_init` in `libgojni.so`.
//
// Why this reads the DEX and not `mapping.txt` or `proguard-rules.pro`:
//
//   - a grep of the rules file proves a rule was WRITTEN, not that it took
//     effect, and the failing build had a rule (`-keepclasseswithmembernames`)
//     that looked like it covered this and did not;
//   - `mapping.txt` describes renaming. A class R8 removed outright is
//     simply absent from it, so "not mentioned" is ambiguous between kept
//     and deleted.
//
// Why it reads DEFINITIONS and not the id tables. The first version of this
// check asked whether each required descriptor appeared in `type_ids`,
// `method_ids` and `field_ids`. That was unsound, and an independent review
// measured it: those tables are the DEX symbol REFERENCE pool, so a class
// that some other class merely calls into is listed there even when its own
// definition has been removed. A one-class probe that referenced all
// thirteen entries and defined none of them passed the first version.
//
// So the check reads `class_defs` and each target's `class_data_item`:
//
//   - a class counts only when it has its own `class_def`;
//   - a method or field counts only when it is an `encoded_method` /
//     `encoded_field` of that class with the required name AND descriptor —
//     a `getRef` that survived with the wrong signature would still abort
//     the process;
//   - the JNI shape is checked from `access_flags`: gomobile invokes the
//     five `go.Seq` entry points through `CallStatic*Method`, and reads
//     `go.Seq$Ref.obj` through `GetObjectField` on an instance, so a member
//     that flipped between static and instance is a failure even though its
//     name and descriptor are intact.
//
// The id tables are still parsed, but only to separate the two failure
// modes in the report — "gone entirely" versus "referenced but not
// defined" — which is precisely the distinction the first version missed.
//
// Self-test. Before reporting a pass this task rebuilds the artifact's DEX
// images with every contract owner's `class_def` removed and nothing else
// touched, then requires its own verdict to flip to a rejection of all
// thirteen entries, each for the "referenced but not defined" reason. A
// verifier that still passes that image is unsound and fails the build
// rather than certifying it.
//
// Negative control. Point it at the known-broken artifact and it must fail:
//
//   ./gradlew :apps:android:verifyR8KeepsGomobileJniSurface \
//       -PgomobileVerifyApk=/path/to/android-release-34a89021.apk

/** `access_flags` bit for a static member, per the Dalvik executable format. */
val ACC_STATIC = 0x8

/** One entry of the JNI contract the vendored `libgojni.so` resolves by name. */
data class GomobileJniEntry(
    val kind: String,
    val owner: String,
    val name: String,
    val descriptor: String,
    /**
     * Required JNI shape for member entries: `true` when the member must be
     * static, `false` when it must be an instance member. Always null for a
     * `class` entry.
     */
    val requiredStatic: Boolean? = null,
) {
    override fun toString(): String = when (kind) {
        "class" -> owner
        "field" -> owner + "->" + name + ":" + descriptor
        else -> owner + "->" + name + descriptor
    }
}

/**
 * The surface this APK's arm64 `libgojni.so` looks up by name.
 *
 * Every method and field entry corresponds to a `failed to find ...`
 * diagnostic compiled into that library; every class entry corresponds to a
 * `FindClass` name string in it. The list is evidence-derived, not a guess:
 * see the block comment on the keep rule in `proguard-rules.pro`.
 *
 * The static/instance column is taken from the vendored jar itself
 * (`javap -private -s` on `go.Seq` and `go.Seq$Ref`), not assumed.
 */
val gomobileJniContract: List<GomobileJniEntry> = listOf(
    GomobileJniEntry("method", "Lgo/Seq;", "incRefnum", "(I)V", requiredStatic = true),
    GomobileJniEntry("method", "Lgo/Seq;", "incRef", "(Ljava/lang/Object;)I", requiredStatic = true),
    GomobileJniEntry("method", "Lgo/Seq;", "decRef", "(I)V", requiredStatic = true),
    GomobileJniEntry("method", "Lgo/Seq;", "incGoObjectRef", "(Lgo/Seq\$GoObject;)I", requiredStatic = true),
    GomobileJniEntry("method", "Lgo/Seq;", "getRef", "(I)Lgo/Seq\$Ref;", requiredStatic = true),
    GomobileJniEntry("class", "Lgo/Seq\$Ref;", "", ""),
    GomobileJniEntry("field", "Lgo/Seq\$Ref;", "obj", "Ljava/lang/Object;", requiredStatic = false),
    GomobileJniEntry("class", "Lgo/Universe\$proxyerror;", "", ""),
    GomobileJniEntry("class", "LlibXray/CountGeoDataRequest;", "", ""),
    GomobileJniEntry("class", "LlibXray/DialerController;", "", ""),
    GomobileJniEntry("class", "LlibXray/LibXray\$proxyDialerController;", "", ""),
    GomobileJniEntry("class", "LlibXray/RunXrayFromJSONRequest;", "", ""),
    GomobileJniEntry("class", "LlibXray/RunXrayRequest;", "", ""),
)

/**
 * What a DEX image contains, split into the two things an R8 verification
 * must never confuse.
 *
 * `referenced*` comes from the global id tables — every symbol the image
 * MENTIONS, including calls into classes defined elsewhere or nowhere.
 * `defined*` comes from `class_defs` and `class_data_item` — what this image
 * actually CARRIES. Only the second kind can keep a JNI lookup alive; the
 * first is kept for diagnostics.
 */
class DexSurface {
    val referencedTypes = HashSet<String>()
    val referencedMethods = HashSet<String>()
    val referencedFields = HashSet<String>()
    val definedTypes = HashSet<String>()

    /** Member key to `access_flags` of the `encoded_method` / `encoded_field`. */
    val definedMethods = HashMap<String, Int>()
    val definedFields = HashMap<String, Int>()

    fun mergeFrom(other: DexSurface) {
        referencedTypes += other.referencedTypes
        referencedMethods += other.referencedMethods
        referencedFields += other.referencedFields
        definedTypes += other.definedTypes
        definedMethods += other.definedMethods
        definedFields += other.definedFields
    }
}

/**
 * Parse one `classes*.dex` image into its referenced and defined surfaces.
 *
 * Offsets are the fixed `header_item` fields of the Dalvik executable
 * format; member keys are `owner->name(params)ret` for methods and
 * `owner->name:type` for fields.
 */
fun readDexSurface(dex: ByteArray): DexSurface {
    fun u1(o: Int) = dex[o].toInt() and 0xFF
    fun u2(o: Int) = u1(o) or (u1(o + 1) shl 8)
    fun u4(o: Int) = u2(o) or (u2(o + 2) shl 16)

    fun uleb128(start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var o = start
        while (true) {
            val b = u1(o); o++
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to o
    }

    val stringIdsSize = u4(0x38); val stringIdsOff = u4(0x3C)
    val typeIdsSize = u4(0x40); val typeIdsOff = u4(0x44)
    val protoIdsSize = u4(0x48); val protoIdsOff = u4(0x4C)
    val fieldIdsSize = u4(0x50); val fieldIdsOff = u4(0x54)
    val methodIdsSize = u4(0x58); val methodIdsOff = u4(0x5C)
    val classDefsSize = u4(0x60); val classDefsOff = u4(0x64)

    val strings = Array(stringIdsSize) { i ->
        val dataOff = u4(stringIdsOff + i * 4)
        // `string_data_item` is a ULEB128 UTF-16 length followed by MUTF-8
        // bytes terminated by NUL. Only the bytes are needed here.
        val after = uleb128(dataOff).second
        var end = after
        while (dex[end].toInt() != 0) end++
        String(dex, after, end - after, Charsets.UTF_8)
    }
    val types = Array(typeIdsSize) { i -> strings[u4(typeIdsOff + i * 4)] }

    val protos = Array(protoIdsSize) { i ->
        val base = protoIdsOff + i * 12
        val ret = types[u4(base + 4)]
        val paramsOff = u4(base + 8)
        val params = if (paramsOff == 0) {
            ""
        } else {
            val n = u4(paramsOff)
            (0 until n).joinToString("") { k -> types[u2(paramsOff + 4 + k * 2)] }
        }
        "(" + params + ")" + ret
    }

    // Keys are built once per id so `class_data_item` can look them up by
    // index without re-reading the tables.
    val methodKeys = Array(methodIdsSize) { i ->
        val base = methodIdsOff + i * 8
        types[u2(base)] + "->" + strings[u4(base + 4)] + protos[u2(base + 2)]
    }
    val fieldKeys = Array(fieldIdsSize) { i ->
        val base = fieldIdsOff + i * 8
        types[u2(base)] + "->" + strings[u4(base + 4)] + ":" + types[u2(base + 2)]
    }

    val surface = DexSurface()
    surface.referencedTypes.addAll(types)
    surface.referencedMethods.addAll(methodKeys)
    surface.referencedFields.addAll(fieldKeys)

    for (i in 0 until classDefsSize) {
        val base = classDefsOff + i * 32
        val descriptor = types[u4(base)]
        surface.definedTypes += descriptor
        val classDataOff = u4(base + 24)
        // An interface or marker class with no members has no class_data_item.
        if (classDataOff == 0) continue

        var o = classDataOff
        val staticFieldsSize = uleb128(o).also { o = it.second }.first
        val instanceFieldsSize = uleb128(o).also { o = it.second }.first
        val directMethodsSize = uleb128(o).also { o = it.second }.first
        val virtualMethodsSize = uleb128(o).also { o = it.second }.first

        // Each of the four lists carries its own index, accumulated from
        // per-entry deltas and reset between lists.
        for (list in 0 until 2) {
            var fieldIdx = 0
            val count = if (list == 0) staticFieldsSize else instanceFieldsSize
            repeat(count) {
                fieldIdx += uleb128(o).also { r -> o = r.second }.first
                val flags = uleb128(o).also { r -> o = r.second }.first
                val key = fieldKeys[fieldIdx]
                check(key.startsWith(descriptor + "->")) {
                    "Malformed DEX: class_data_item of $descriptor encodes field $key"
                }
                surface.definedFields[key] = flags
            }
        }
        for (list in 0 until 2) {
            var methodIdx = 0
            val count = if (list == 0) directMethodsSize else virtualMethodsSize
            repeat(count) {
                methodIdx += uleb128(o).also { r -> o = r.second }.first
                val flags = uleb128(o).also { r -> o = r.second }.first
                uleb128(o).also { r -> o = r.second } // code_off, unused
                val key = methodKeys[methodIdx]
                check(key.startsWith(descriptor + "->")) {
                    "Malformed DEX: class_data_item of $descriptor encodes method $key"
                }
                surface.definedMethods[key] = flags
            }
        }
    }
    return surface
}

/**
 * Return a parser-input copy of `dex` with every `class_def` whose descriptor
 * is in `targets` removed, and the count removed.
 *
 * The id tables are deliberately left untouched, so the result still MENTIONS
 * every symbol it used to define. That is the shape of the probe that defeated
 * the first version of this check, which is why the task builds one from the
 * artifact under test and requires itself to reject it. The result is a
 * fixture for this parser, not a loadable DEX: `map_list` and the checksum
 * are not repaired.
 */
fun stripClassDefs(dex: ByteArray, targets: Set<String>): Pair<ByteArray, Int> {
    fun u1(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    fun u2(b: ByteArray, o: Int) = u1(b, o) or (u1(b, o + 1) shl 8)
    fun u4(b: ByteArray, o: Int) = u2(b, o) or (u2(b, o + 2) shl 16)

    val out = dex.copyOf()
    val stringIdsOff = u4(out, 0x3C)
    val typeIdsOff = u4(out, 0x44)
    val classDefsSize = u4(out, 0x60)
    val classDefsOff = u4(out, 0x64)

    fun descriptorOf(typeIdx: Int): String {
        val dataOff = u4(out, stringIdsOff + u4(out, typeIdsOff + typeIdx * 4) * 4)
        var o = dataOff
        while (u1(out, o) and 0x80 != 0) o++ // skip the ULEB128 length
        o++
        var end = o
        while (out[end].toInt() != 0) end++
        return String(out, o, end - o, Charsets.UTF_8)
    }

    var kept = 0
    var removed = 0
    for (i in 0 until classDefsSize) {
        val base = classDefsOff + i * 32
        if (descriptorOf(u4(out, base)) in targets) {
            removed++
            continue
        }
        val dest = classDefsOff + kept * 32
        if (dest != base) System.arraycopy(out, base, out, dest, 32)
        kept++
    }
    // `class_defs_size` is authoritative, so the trailing slots are simply
    // no longer addressed.
    for (b in 0 until 4) out[0x60 + b] = ((kept shr (b * 8)) and 0xFF).toByte()
    return out to removed
}

/** Why one contract entry was not satisfied. */
data class GomobileMiss(val entry: GomobileJniEntry, val reason: String, val detail: String)

/**
 * Decide the contract against DEFINITIONS only. `referenced*` is consulted
 * purely to label the failure.
 */
fun evaluateGomobileContract(
    surface: DexSurface,
    contract: List<GomobileJniEntry>,
): List<GomobileMiss> = contract.mapNotNull { entry ->
    val key = entry.toString()
    when (entry.kind) {
        "class" -> when {
            entry.owner in surface.definedTypes -> null
            entry.owner in surface.referencedTypes ->
                GomobileMiss(entry, "REFERENCED-ONLY", "in type_ids but has no class_def")
            else -> GomobileMiss(entry, "ABSENT", "no class_def and not referenced")
        }
        else -> {
            val isField = entry.kind == "field"
            val defined = if (isField) surface.definedFields else surface.definedMethods
            val referenced = if (isField) surface.referencedFields else surface.referencedMethods
            val flags = defined[key]
            val requiredStatic = entry.requiredStatic
            if (flags == null) {
                if (key in referenced) {
                    GomobileMiss(
                        entry,
                        "REFERENCED-ONLY",
                        "in the id tables but not encoded in the class_data_item of " + entry.owner,
                    )
                } else {
                    GomobileMiss(entry, "ABSENT", "not defined and not referenced")
                }
            } else if (requiredStatic != null && (flags and ACC_STATIC != 0) != requiredStatic) {
                GomobileMiss(
                    entry,
                    "WRONG-SHAPE",
                    "defined, but access_flags=0x" + Integer.toHexString(flags) + " makes it " +
                        (if (flags and ACC_STATIC != 0) "static" else "an instance member") +
                        " while the native call site requires " +
                        (if (requiredStatic) "static" else "an instance member"),
                )
            } else {
                null
            }
        }
    }
}

val verifyR8KeepsGomobileJniSurface = tasks.register("verifyR8KeepsGomobileJniSurface") {
    group = "verification"
    description =
        "Verifies the minified release DEX still DEFINES every `go.Seq` / gomobile member the vendored `libgojni.so` resolves by name."

    val defaultApk = layout.buildDirectory.file("outputs/apk/release/android-release.apk")
    val apkOverride = providers.gradleProperty("gomobileVerifyApk")
    val contract = gomobileJniContract
    // A release gate must never be skipped as up-to-date.
    outputs.upToDateWhen { false }

    doLast {
        val apk = apkOverride.map { File(it) }.orNull ?: defaultApk.get().asFile
        check(apk.exists()) {
            "Expected a release APK at ${apk.absolutePath} but it does not exist. " +
                "Assemble the release build first, or pass -PgomobileVerifyApk=<path>."
        }

        val dexImages = ArrayList<ByteArray>()
        ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                val n = entry.name
                if (!n.startsWith("classes") || !n.endsWith(".dex") || n.contains('/')) continue
                dexImages += zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        check(dexImages.isNotEmpty()) {
            "No classes*.dex found in ${apk.absolutePath} — is this an APK?"
        }

        val surface = DexSurface()
        dexImages.forEach { surface.mergeFrom(readDexSurface(it)) }

        val missing = evaluateGomobileContract(surface, contract)
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "verifyR8KeepsGomobileJniSurface FAILED — ${missing.size} of ${contract.size} " +
                            "required gomobile JNI entries are not defined in the minified DEX of " +
                            "${apk.absolutePath} (${dexImages.size} dex file(s)):",
                    )
                    missing.forEach { appendLine("  ${it.reason}  ${it.entry.kind}  ${it.entry} — ${it.detail}") }
                    appendLine()
                    appendLine(
                        "The vendored `libgojni.so` resolves these by name from `Java_go_Seq_init` " +
                            "and aborts the process on the first miss — this is the release-only " +
                            "SIGABRT `failed to find method Seq.getRef` seen in Stage 2 physical " +
                            "acceptance. Restore the `-keep class go.** { *; }` / " +
                            "`-keep class libXray.** { *; }` rules in apps/android/proguard-rules.pro. " +
                            "`-keepnames` and `-keepclasseswithmembernames` are NOT sufficient: they " +
                            "stop renaming but still allow R8 to shrink the classes away.",
                    )
                },
            )
        }

        // Self-test: the same verdict must flip to a rejection once the
        // definitions are gone but the references remain. See the block
        // comment above — this is the probe shape that defeated the first
        // version of this check.
        val owners = contract.map { it.owner }.toSet()
        var removedDefs = 0
        val stripped = DexSurface()
        dexImages.forEach {
            val (image, removed) = stripClassDefs(it, owners)
            removedDefs += removed
            stripped.mergeFrom(readDexSurface(image))
        }
        check(removedDefs >= owners.size) {
            "verifyR8KeepsGomobileJniSurface self-test is vacuous: expected to strip at least " +
                "${owners.size} class_def entries but stripped $removedDefs."
        }
        val strippedMisses = evaluateGomobileContract(stripped, contract)
        val referencedOnly = strippedMisses.count { it.reason == "REFERENCED-ONLY" }
        if (strippedMisses.size != contract.size || referencedOnly != contract.size) {
            throw GradleException(
                buildString {
                    appendLine(
                        "verifyR8KeepsGomobileJniSurface SELF-TEST FAILED — this check cannot be " +
                            "trusted and is refusing to certify the build.",
                    )
                    appendLine(
                        "With all ${owners.size} contract owners' class_def entries removed and the id " +
                            "tables left intact, it should have rejected all ${contract.size} entries as " +
                            "REFERENCED-ONLY; it rejected ${strippedMisses.size} " +
                            "($referencedOnly as REFERENCED-ONLY).",
                    )
                    appendLine(
                        "That is the defect an independent review found in the first version of this " +
                            "task: id tables list symbols a DEX merely mentions, so they cannot prove a " +
                            "class survived R8.",
                    )
                },
            )
        }

        logger.lifecycle(
            "verifyR8KeepsGomobileJniSurface PASS — all ${contract.size} gomobile JNI entries are " +
                "defined with the required descriptors and static/instance shape in " +
                "${dexImages.size} dex file(s); self-test rejected the definition-stripped image " +
                "${strippedMisses.size}/${contract.size}.",
        )
    }
}

val verifyReleaseSigningConfigured by tasks.registering {
    group = "verification"
    description = "Fails release artifact builds unless production signing is fully configured."
    doLast {
        if (!releaseSigningConfigured) {
            throw GradleException(
                "Release signing is not configured. Provide all four values in " +
                    "keystores/signing.properties or SIGNING_STORE_FILE, " +
                    "SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS and SIGNING_KEY_PASSWORD.",
            )
        }
        val keyStore = rootProject.file(requireNotNull(releaseSigningStoreFile))
        if (!keyStore.isFile) {
            throw GradleException("Release keystore does not exist: ${keyStore.absolutePath}")
        }
    }
}

tasks.matching {
    it.name in setOf(
        "assembleRelease",
        "packageRelease",
        "bundleRelease",
        "packageReleaseBundle",
        "signReleaseBundle",
    )
}.configureEach {
    dependsOn(verifyReleaseSigningConfigured)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyR8StripsTestSeams, verifyR8KeepsGomobileJniSurface)
}
