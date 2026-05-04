# Mobile Tor Integration Options for PHANTOM — research findings

**Research date:** 2026-05-04
**Scope:** Android (Alpha-2 priority) and iOS (post-Alpha)
**Context:** PHANTOM is a Kotlin Multiplatform messenger. WebSocket transport breaks on Russian carrier CGN/TSPU. Tor onion routing under evaluation as the alternative transport.

---

## TL;DR

Embedding Tor in a KMP messenger is technically viable in 2026 — `kmp-tor` (05nelsonm) is actively maintained, Apache-2.0, supports Android/iOS/JVM, and tracks upstream C-tor. The single biggest factor is **NOT** library availability — it is **APK size and battery cost together**. Briar pays roughly 45–50 MB per APK (with Tor + obfs4proxy/lyrebird + snowflake bundled across ABIs) and is widely reported by users to "drain the battery" because the Tor daemon must hold a live circuit at all times to receive messages. PHANTOM should **bundle Tor (kmp-tor), not depend on Orbot**, because requiring a second app is fatal for adoption in Russia where Orbot itself is targeted by app-store removals. Long-term migration target is `arti` (Rust); arti reached production-ready onion-service support in v1.4.x (2025) but mobile bindings remain experimental and binary size is unsolved. iOS works only as embedded library inside the app process — Apple does not allow a system-wide Tor service, but Onion Browser, OnionShare, and several Tor-using apps are accepted on the App Store as of 2025, so policy is not the blocker.

---

## 1. Android Tor library options (state of the art 2026)

### 1.1 `tor-android` (Guardian Project) — the legacy baseline

Still alive. The repo at github.com/guardianproject/tor-android remains the canonical "Tor binary + JNI shim for Android" package and underpins Orbot, OnionShare-Android, and TorServices. It wraps **C-tor** (the original tor.git daemon written in C) compiled per-ABI with the obfs4 / lyrebird / snowflake go binaries shipped alongside.

It is NOT formally deprecated, but Guardian Project has publicly stated since 2023 that arti is the long-term direction (see Guardian Project's "Arti, next-gen Tor on mobile", 2023-03-04). Maintenance in 2025–2026 has been mostly version bumps to track Tor stable releases (Tor 0.4.9.x line); no architectural changes.

For a fresh KMP project starting in 2026 there is **no good reason to use `tor-android` directly** — wrapping JNI from a Kotlin Multiplatform shared module is exactly what `kmp-tor` already solves on top of `tor-android`'s binaries.

### 1.2 `kmp-tor` (Matthew Nelson, `05nelsonm`) — the recommended pick

The current best fit for PHANTOM. Stats (as of 2026-05-04):

- **Latest version:** 2.6.0 (released 2026-02-10)
- **License:** Apache-2.0 (compatible with PHANTOM's open-source posture; no AGPL contagion like libsignal)
- **Platforms:** Android, AndroidNative, iOS, JVM, JS/Node, WASM, Linux (incl. ARM), macOS
- **Active development:** 455 commits master, 45 releases — clearly maintained
- **Companion module:** `kmp-tor-resource` ships pre-compiled per-ABI Tor binaries so consuming apps don't rebuild C-tor themselves
- **Integration model:** embedded Tor daemon in-process; exposes SOCKS5 on localhost; control-port API surfaces as Kotlin coroutines / event flows
- **Dependency line:** `io.matthewnelson.kmp-tor:runtime:2.6.0`

Source: github.com/05nelsonm/kmp-tor (fetched 2026-05-04).

This matches PHANTOM's KMP architecture cleanly — one library, one API, Android + iOS + Desktop covered. The SOCKS5 boundary lets us continue using Ktor/OkHttp on Android with minimal rewiring (just point the engine at `127.0.0.1:<socks_port>`).

### 1.3 `tor-mobile-kmp` (ACINQ) — DO NOT USE

ACINQ (the Lightning network company behind the Phoenix wallet) maintained an Android+iOS KMP wrapper. **Repository archived on 2025-02-12** (read-only since), last release was 0.1.0 in 2021-02-03. Effectively dead. Mentioned only to mark it as "considered and rejected".

### 1.4 Orbot integration (system Tor service)

Orbot 17.9.x (April 2026) supports two modes apps can talk to:

- **SOCKS5** — `127.0.0.1:9050`. App-internal opt-in per network call.
- **VPN mode** — Orbot tunnels selected apps system-wide, also exposes per-app routing.

Orbot is the lowest-friction path technically (zero binary in PHANTOM's APK) but it is **the wrong choice for PHANTOM's threat model**:

1. Russia has removed Tor-related apps from Google Play and is pressuring app stores. Requiring users to find and install Orbot adds a fragile prerequisite at the worst possible moment (first-run, on a censored network).
2. Orbot itself uses obfs4 / WebTunnel / Snowflake for bridges — but its UX for bridge selection is not aligned with PHANTOM's UX. The user would need to configure two apps before any message goes through.
3. Hard dependency means that if Orbot crashes, is killed by the OEM battery saver, or is uninstalled, PHANTOM goes silent.

Orbot remains a useful **fallback transport** ("if Orbot is installed and running, prefer it") but should not be the primary path.

### 1.5 Embedded vs Orbot — recommended pattern

Bundle `kmp-tor` as the primary Tor source. Detect Orbot via its public `IPC` content-provider; if found and user opts in, route through Orbot's SOCKS5 to save the battery cost of running two daemons. This dual-source pattern is what OnionShare-Android and Cwtch already use.

### 1.6 `arti` — the future, not yet the present (mobile)

Arti is the Rust rewrite of Tor. Production-ready for **client** use since Arti 1.0.0 (2022-09). Onion-service support landed in 1.4.0 (Feb 2025) and was hardened for resilience in 1.4.6 (mid-2025) including DoS resistance (Proposal 362) and a key-migration utility from C-tor keystores. Arti 1.8.0 (late 2025/early 2026) added further onion-service improvements.

Mobile status (2026-05-04):

- **arti-android docs** at gitlab.torproject.org/tpo/core/arti/-/blob/main/doc/Android.md exist and cross-compilation is documented.
- **arti-mobile-ex** (Guardian Project) is an experimental CI scaffold building Android+iOS app shells around arti. Still labeled experimental.
- **lightarti / lightarti-rest-android** (C4DT / EPFL) is a stripped Tor client based on arti optimizing the consensus download for mobile. Production use: only the EPFL-hosted demos and a few research apps.
- **Cure53 audited** TorVPN-for-Android's arti tunnel — gives some confidence, but TorVPN is not equivalent to a full messenger Tor stack.

Open issue: **Rust standard library binary size**. The Tor Project itself flags this as the main blocker for embedding arti in size-sensitive mobile apps. Until that is addressed, arti on mobile costs more APK MB than the C-tor stack it would replace — exactly the wrong direction for PHANTOM.

**Recommendation:** track arti for 2027. Build PHANTOM Tor on `kmp-tor` (C-tor) for Alpha-2/Beta. Plan a transport-layer migration once arti's mobile binary cost is competitive.

### 1.7 Pluggable transport binaries — Lyrebird / Snowflake / WebTunnel

These ship as separate Go binaries that the Tor daemon spawns:

- **lyrebird** — replaces obfs4proxy as the canonical obfs4 binary (also handles meek-lite, webtunnel client). Already bundled by `tor-android` and therefore by `kmp-tor-resource`.
- **snowflake-client** — Go binary. Bundled by `kmp-tor-resource` and Orbot.
- **webtunnel-client** — bundled inside lyrebird since 2024.

PHANTOM does not need to compile these itself. Including all three across arm64-v8a + armeabi-v7a + x86_64 is what produces the bulk of Tor-related APK weight (see §4).

### 1.8 Briar's approach (reference architecture)

Briar bundles its own forked `tor-android` build, runs the C-tor daemon in a foreground service with a persistent notification, and exposes an onion service per user identity. Each contact pair exchanges `.onion` addresses out-of-band. The daemon stays connected so the onion service can receive incoming connections. This is the closest architectural precedent for what PHANTOM would do.

Two practical Briar-specific lessons for PHANTOM:

- They had to delay arm64 binaries until the August 2019 Play 64-bit deadline forced the issue — APK roughly doubled.
- Their public bug tracker (briar/briar issue #44) lists "reduce battery consumption" as an open-since-day-one issue that has never been closed. This is the unavoidable cost.

### 1.9 Element / Matrix on Tor

Some Element forks proxy through Orbot. There is no "Element-with-embedded-Tor" — Matrix protocol is not designed to run as an onion service (it needs a homeserver). Not useful as a reference for PHANTOM's P2P-onion model.

---

## 2. iOS Tor implementation

### 2.1 No system Tor service

Apple does not allow a generic background daemon. There is no iOS equivalent to Orbot's "system Tor that any app can use". Apple did add NetworkExtension VPN APIs that iCepa attempted to use, but per-app routing requires MDM enrollment and is not available to consumer apps. **Every iOS app that wants Tor must embed it.**

### 2.2 `Tor.framework` (iCepa) — current canonical embed

`iCepa/Tor.framework` is the standard Objective-C/Swift wrapper around the C-tor daemon for iOS apps. Used by:

- Onion Browser (recommended by the Tor Project for iOS)
- OnionShare iOS
- several wallet apps (Wasabi, Sparrow companion)

There was a real App Store rejection caused by Tor.framework referencing private OpenSSL symbols (`_getcontext`, `_makecontext`, `_setcontext` from OpenSSL's async-jobs feature). See iCepa/Tor.framework issue #9. The fix: drop the affected OpenSSL feature at compile time. Apps using current Tor.framework builds pass review.

`kmp-tor` on iOS effectively wraps the same C-tor binary that Tor.framework uses, so PHANTOM's iOS target inherits the same App Store posture.

### 2.3 App Store policies

The empirical pattern as of 2024-2025:

- Apps that **bundle Tor and use it for the app's own networking** are accepted (Onion Browser, OnionShare, Wasabi, Sparrow, Mullvad's Tor mode). Onion Browser has been continuously listed since 2012.
- Apps that try to **provide a system-wide Tor proxy / VPN to other apps** are restricted (iCepa never shipped a public release; only TorVPN-style products work).
- WebKit requirement still applies for browsers (irrelevant to PHANTOM).

There is no documented blanket "anti-Tor" rejection in 2024-2025. Onion Browser passed a Privacy Guides review in 2024-09. The risk is low.

### 2.4 Background execution

iOS aggressively suspends background apps. A Tor daemon embedded in PHANTOM would **not** stay live to receive incoming onion connections while the app is backgrounded — Apple grants only short-lived background tasks (`BGProcessingTask`, ~30 s of execution per opportunity). This is a fundamental architectural problem for any P2P onion-service messenger on iOS:

- **Outbound (you send a message):** works. App is foregrounded, Tor daemon spins up, ~20-40s bootstrap, message goes out.
- **Inbound (someone messages you while your app is backgrounded):** does not work. The recipient's onion service is offline.

Mitigation patterns that other Tor iOS apps use:

1. **Push wakeup + relay store-and-forward** (Cwtch experimented; trade-off: requires a relay that holds ciphertext until poll).
2. **Voice/Audio background-audio entitlement abused as a daemon** — not robust, Apple rejects this if detected.
3. **Periodic BGAppRefresh windows** — best-effort, gives at most a few minutes per day.

For PHANTOM iOS, expect **store-and-forward via relay** (matches PHANTOM's existing relay design) rather than pure P2P onion. iOS is much less Tor-friendly than Android in practice.

### 2.5 Battery on iOS

While the app is foregrounded, Tor's CPU cost is similar to Android (a few % continuous). The bigger issue is that any background-keepalive scheme to compensate for the lack of a background daemon will itself cost battery and may be flagged by iOS's "background activity" UI to the user.

---

## 3. Battery impact

This is the most user-visible cost. There are no recent published lab measurements I could locate; the public record is qualitative:

- **Briar.** The official Briar manual recommends restricting Briar to "only use the internet when connected to power" — i.e. the developers themselves admit running Tor full-time on battery is painful. Issue #44 ("reduce battery consumption") in briar/briar has been open since the project's first beta in 2017 and has never been closed (search 2026-05-04).
- **Cwtch.** Open Privacy's "Discreet Log #27: Cwtch Android Improvements" describes Tor circuit-staleness detection and auto-restart as the dominant battery factor on mobile, because the device falling between cells repeatedly forces full circuit rebuilds. Each full rebuild is roughly 5–30 s of CPU at high frequency cost.
- **Tor daemon baseline.** With a working Internet path and a stable circuit, C-tor's idle CPU is low — single-digit %. The cost concentrates on (a) bootstrap, (b) circuit churn after network change, (c) bridge probing when bridges are blocked.
- **Bridge reconnection cost.** Each new bridge attempt is a TLS handshake plus consensus check — order of 5–30 s of active CPU per attempt. On TSPU networks where many bridges are blocked, the **bridge-discovery loop dominates battery cost** before any messages are exchanged. This is likely worse than the steady-state cost.
- **Comparison with passive (push) model.** A push-based architecture (UnifiedPush / FCM where a notification wakes the app) costs roughly 0% incremental battery — the radio is already woken by the system push channel. A Tor-onion architecture cannot use FCM because FCM is a deanonymizer. The realistic Tor model on Android is a foreground service with persistent notification — that alone is a non-trivial drain (continuous CPU plus radio keep-alive beyond what Doze permits).

**Order-of-magnitude estimate** (no published Briar/Cwtch numbers; this is engineering judgment from the patterns above): expect **5–15% of battery capacity per 24h** for a backgrounded PHANTOM with Tor on a stable Wi-Fi network. On flaky cellular with bridge cycling, that can spike to 20–30% per day. A passive (FCM-style) baseline would be under 2%.

This is the cost users in Briar/Cwtch communities consistently report and it is the principal reason mainstream messengers do not run Tor.

---

## 4. APK size impact

### 4.1 Embedded Tor binary size, per-ABI

Concrete data points:

- Briar 1.5.x APK is **~46–48 MB** total (APKMirror file-size listings, 2024–2025). Of that, roughly **20–25 MB is Tor + lyrebird + snowflake binaries across 4 ABIs**, the rest is Briar's own code, deps, and resources.
- Tor binary alone per-ABI: roughly **3–5 MB** (release-stripped, C-tor 0.4.9.x, OpenSSL static).
- lyrebird (Go) per-ABI: roughly **8–12 MB** (Go's static linking + runtime).
- snowflake-client (Go) per-ABI: roughly **6–9 MB**.
- webtunnel: bundled inside lyrebird since 2024 — no separate cost.

Totals per-ABI: **~17–26 MB** for Tor + transports.

### 4.2 Multiplier across ABIs

Required ABIs for PHANTOM if shipping a fat APK:

- arm64-v8a (modern phones)
- armeabi-v7a (older / cheap Android in Russia and emerging markets)
- x86_64 (emulators + Chromebooks)

3× multiplier → **~50–80 MB of Tor-only native code in the universal APK**.

### 4.3 ABI splits / Play Asset Delivery

Google Play Android App Bundle (AAB) format does ABI-splitting automatically since 2019. Each user downloads only their phone's ABI. For F-Droid the same can be done with per-ABI APKs.

**Per-user download cost (AAB-served):** ~17–26 MB for Tor stack + PHANTOM's own code (~10–20 MB) + libsignal + Compose + media → realistic PHANTOM-with-Tor download is **40–70 MB per user**. Compare to current Briar at ~46 MB and Element/Matrix Android at ~80 MB — competitive.

### 4.4 PHANTOM-specific recommendation on size

- Use AAB on Play, per-ABI APKs on F-Droid and direct download.
- Do NOT ship a universal APK as the primary distribution — universal APKs would be 100+ MB which kills install rate on Russian budget devices.
- Drop x86_64 from the budget build channel if necessary.
- The library's `kmp-tor-resource` artifact already supports per-ABI selection via Gradle ABI filters.

---

## 5. KMP integration specifics

### 5.1 Library binding model

`kmp-tor` exposes a coroutines-first API on the common module. On Android it delegates to `tor-android` JNI; on iOS it loads C-tor through a Kotlin/Native interop wrapper around the same C ABI; on JVM desktop it uses the same JNI binaries.

Identity model: PHANTOM's onion address is derived from an Ed25519 keypair stored under Tor's `HiddenServiceDir`. We can either (a) let kmp-tor manage that directory in the app's private storage (default), or (b) derive the keypair ourselves from PHANTOM's existing Android Keystore identity and feed it to Tor at start. (b) is preferable because it lets us back up / restore identity through PHANTOM's existing flow rather than handling another keyfile.

### 5.2 JNI overhead

For PHANTOM the JNI call cost is irrelevant — Tor calls are I/O bound, not CPU-bound, and the SOCKS5 boundary means we never make hot-path JNI calls (Ktor talks TCP to localhost, not Tor APIs).

### 5.3 SOCKS5 as integration boundary

This is the cleanest pattern. `kmp-tor` opens a SOCKS5 listener on `127.0.0.1:<random_port>`; PHANTOM configures its Ktor `OkHttp` engine to use that proxy. Outbound traffic is now Tor-routed with zero changes to message-layer code.

For onion service hosting (incoming), `kmp-tor`'s control-port API exposes "publish onion service for port X" → returns the `.onion` address. PHANTOM listens on a local TCP port and Tor publishes it.

### 5.4 Identity persistence

The `.onion` v3 address is `base32(sha3-256(pubkey))[:32] + checksum + ".onion"` — 56 chars. Storing only the seed in PHANTOM's existing keystore is enough. On first run PHANTOM writes the seed file into Tor's HSDir, Tor publishes, and the address is stable across restarts.

---

## 6. App Store / Google Play status

### 6.1 Google Play

**Tor Browser is on Play** (official Tor Project listing, app id `org.torproject.torbrowser`). Orbot is on Play. OnionShare is on Play. There is no policy obstacle to bundling Tor.

The only Play policy risk is the **ranking / discoverability hit** Russian-language privacy apps have taken — Roskomnadzor pressure has caused some app removals from the Russia store specifically. PHANTOM should publish via:
1. Google Play (global)
2. F-Droid (no Play dependency, no removal risk)
3. Direct APK from phntm.pro
4. Future: Aurora Store / IzzyOnDroid for Russia-specific reach if Play access degrades

### 6.2 F-Droid

F-Droid welcomes Tor apps. Briar, OnionShare, Tor Browser (alpha), TorServices, Orbot are all on F-Droid. The only F-Droid friction is **reproducible builds** — F-Droid prefers to rebuild from source. `kmp-tor-resource` ships pre-compiled binaries, so PHANTOM would either need an "Anti-Feature: NonFreeNet" or "Includes binaries the F-Droid build server didn't compile" tag, or contribute a reproducible Tor-binary build pipeline. Briar's experience here is the model — they accept the anti-feature.

### 6.3 Apple App Store

Onion Browser, OnionShare, and several wallets are listed. The Tor.framework / OpenSSL private-symbols issue is solved at compile time in current builds. No recent (2024–2026) Tor-specific rejection pattern found in research.

The realistic iOS App Store policy risk is **PHANTOM's encryption + onion routing combo being miscategorized as a VPN**. Apple's NetworkExtension category rules can trigger if anyone interprets PHANTOM's behavior as "unrestricted internet routing" — but since PHANTOM only routes its own messages over Tor (not user web traffic), this should be fine. Onion Browser is a stronger case than PHANTOM and ships.

### 6.4 Bundling Tor vs depending on Orbot

For Play / App Store: bundle. There is no policy reason to depend on Orbot, and depending on it is fragile in the censorship case. F-Droid: same.

---

## Recommendations for PHANTOM

1. **Use `io.matthewnelson.kmp-tor:runtime` (latest 2.6.0) as the primary Tor library.** Apache-2.0, KMP, Android+iOS+Desktop, actively maintained, embedded model. Pin a known-good Tor version through `kmp-tor-resource`.
2. **Bundle Tor binaries inside PHANTOM. Do not require Orbot.** Optional: detect Orbot and route through it if user opts in, to save battery when Orbot is already running.
3. **Distribute as AAB on Play and per-ABI APKs on F-Droid / direct.** Drop x86_64 for the budget build channel. Target ~50–60 MB per-user download.
4. **Architect for "Tor unavailable" gracefully.** PHANTOM's existing relay + WebSocket transport should remain as a fallback when Tor cannot bootstrap (think: airplane Wi-Fi, restrictive corporate networks). Tor is added as a transport option, not a replacement for everything.
5. **iOS strategy: foreground-only Tor + relay store-and-forward.** Do not promise P2P-onion delivery on iOS. The recipient's daemon being unreachable while backgrounded is unsolvable on Apple's terms.
6. **Plan a 2027 evaluation of arti** once mobile binary size is solved. Until then, C-tor is fine.
7. **Battery UX:** copy Briar's "use Internet only when charging" toggle. Be explicit with users about the cost. Default to balanced mode (Tor connects on app foreground + 30 minutes of background).

---

## Open questions for follow-up

1. **Exact Tor version shipped by `kmp-tor-resource` 2.6.0** — need to verify it tracks the 0.4.9.x line which has the key TSPU-relevant fixes.
2. **`kmp-tor` ARM32 (armeabi-v7a) support fully verified?** PHANTOM's Russia user base includes 32-bit-only devices (older Tecno / Itel hardware seen in Vladislav's testing). Need to verify `kmp-tor-resource` ships armv7 binaries, not just arm64.
3. **Does `kmp-tor` expose snowflake bridge configuration through its Kotlin API, or only obfs4?** PHANTOM-specific bootstrap for Russia depends on this.
4. **Reproducible-builds story for F-Droid.** Do we contribute upstream to `kmp-tor-resource` or accept the anti-feature?
5. **iOS background-task reality test.** We need to actually measure how much Tor circuit time we can hold on a real iPhone backgrounded under iOS 17/18 — published numbers are out of date.
6. **Quiet messenger architecture.** Quiet ships an embedded Tor + libp2p inside an Electron app; their lessons may apply but they are not KMP. Worth a focused review.
7. **Concrete battery measurements.** Run our own 24h trial of Briar on a Tecno reference device once we have lab time — fill the gap that the public record does not.

---

## Sources

- [kmp-tor (GitHub, 05nelsonm)](https://github.com/05nelsonm/kmp-tor) — fetched 2026-05-04, version 2.6.0 released 2026-02-10
- [kmp-tor-resource (GitHub, 05nelsonm)](https://github.com/05nelsonm/kmp-tor-resource) — companion Tor binary package
- [tor-mobile-kmp (ACINQ)](https://github.com/ACINQ/tor-mobile-kmp) — archived 2025-02-12, last release 2021-02-03
- [tor-android (Guardian Project)](https://github.com/guardianproject/tor-android) — canonical Android C-tor wrapper
- [Orbot Android (Guardian Project)](https://github.com/guardianproject/orbot-android) — releases page; current 17.9.2 (April 2026)
- [Arti, next-gen Tor on mobile — Guardian Project, 2023-03-04](https://guardianproject.info/2023/03/04/arti-next-gen-tor-on-mobile/)
- [Arti 1.0.0 release — Tor Project blog, 2022-09](https://blog.torproject.org/arti_100_released/)
- [Arti 1.4.0 release — Tor Project blog, Feb 2025](https://blog.torproject.org/arti_1_4_0_released/)
- [Arti 1.4.6 release — Tor Project blog, mid-2025](https://blog.torproject.org/arti_1_4_6_released/)
- [Arti 1.8.0 release — Tor Project blog](https://blog.torproject.org/arti_1_8_0_released/)
- [arti Android docs (GitLab)](https://gitlab.torproject.org/tpo/core/arti/-/blob/main/doc/Android.md)
- [arti-mobile-ex (Guardian Project GitLab)](https://gitlab.com/guardianproject/tormobile/arti-mobile-ex)
- [lightarti-rest-android (C4DT)](https://github.com/c4dt/lightarti-rest-android)
- [Lightarti — C4DT EPFL article](https://c4dt.epfl.ch/article/lightarti-a-lightweight-tor-librairy/)
- [Briar — How it works](https://briarproject.org/how-it-works/)
- [Briar issue #44 "Reduce battery consumption"](https://code.briarproject.org/briar/briar/-/issues/44) — open since 2017
- [Briar issue #1506 "Provide a Tor/obfsproxy arm64 binary"](https://code.briarproject.org/briar/briar/-/issues/1506)
- [Briar 1.5.x APK file size — APKMirror listings](https://www.apkmirror.com/apk/briar-project/briar/) — 46–48 MB range
- [Discreet Log #27: Cwtch Android Improvements — Open Privacy](https://openprivacy.ca/discreet-log/27-android-improvements/)
- [Cwtch documentation](https://docs.cwtch.im/)
- [iCepa/Tor.framework](https://github.com/iCepa/Tor.framework)
- [iCepa/Tor.framework issue #9 — OpenSSL private API rejection](https://github.com/iCepa/Tor.framework/issues/9)
- [iCepa system VPN project](https://github.com/iCepa/iCepa)
- [Onion Browser GitHub](https://github.com/OnionBrowser/OnionBrowser)
- [Onion Browser Review — Privacy Guides, 2024-09-18](https://www.privacyguides.org/articles/2024/09/18/onion-browser-review/)
- [Tor at the Heart: Onion Browser (and more iOS Tor) — Tor Project blog](https://blog.torproject.org/tor-heart-onion-browser-and-more-ios-tor/)
- [Tor Browser on Google Play](https://play.google.com/store/apps/details?id=org.torproject.torbrowser)
- [TorServices on F-Droid](https://f-droid.org/en/packages/org.torproject.torservices/)
- [F-Droid Reproducible Builds documentation](https://f-droid.org/en/docs/Reproducible_Builds/)
- [Briar on F-Droid](https://f-droid.org/packages/org.briarproject.briar.android/)
- [Tor onion service v3 hidden service Flutter plugin (bootstrap timing reference)](https://github.com/SarahRoseLives/tor_hidden_service)
