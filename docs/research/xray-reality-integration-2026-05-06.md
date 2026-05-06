# Xray-core + VLESS+REALITY integration — engineering research

- Author: Phantom transport research, ADR-018 follow-up
- Date: 2026-05-06
- Status: Research / pre-decision; reference for Stage 5E planning
- Replaces / supplements: ADR-018 (Stage 5 bridges via OnionWrapper)
- Reader: an engineer about to scope the first implementation PR

---

## 1. Executive summary

Stage 5B (public Snowflake) and Stage 5C (operator-controlled WebTunnel bridge on Hetzner) put PHANTOM at the limit of what the Tor Browser pluggable-transport ecosystem can do for Russian carrier networks. Test 6 / Test 10 show the failure mode is not a bridge-discovery problem but a **TLS-flow-shape problem**: TSPU's "16 KB curtain" silently throttles long TLS streams to flagged datacenter ranges (Hetzner included) regardless of which obfuscation lives inside the TLS payload. WebTunnel at `bridge.phntm.pro` is reachable for the first ~16 KB and then stalls, exactly as predicted.

The production answer used daily in Russia by 3X-UI / Marzban / Hiddify / V2RayNG users is **Xray-core's VLESS+REALITY** outer wrapper. REALITY makes our server's TLS handshake byte-for-byte identical to a handshake with a real third-party site (Microsoft, Apple, etc.) because the Xray daemon transparently reverse-proxies the genuine cert chain on cache miss; TSPU classifies the resulting flow as benign big-tech CDN traffic and applies no throttle.

This document scopes embedding Xray-core into PHANTOM as a native Android library (no third-party app installs), how to coexist with the existing Caddy on port 443, how to wire the resulting SOCKS5 listener into our existing transport stack alongside the current Tor path, and the license posture that makes this combinable with PHANTOM's AGPL-3.0-or-later codebase. The first concrete PR (Stage 5E.A) is server-side only: stand up the Xray REALITY listener on the same Hetzner VPS and validate it with a desktop client, before any APK code ships.

---

## 2. A) Cross-compile feasibility for Android

### 2.1 The three known approaches

| Approach | What you get | Used by | Verdict for PHANTOM |
|---|---|---|---|
| **`gomobile bind` directly on `XTLS/Xray-core`** | `.aar` containing per-ABI `libgojni.so` plus generated Java/Kotlin shim | Older v2rayNG forks | Works but couples our build directly to upstream Go internals. We re-do the whole layer ourselves on every Xray release. |
| **Wrapper repo: `2dust/AndroidLibXrayLite`** | `.aar` exposing a curated `Libv2ray` Java API (start/stop, log callbacks, traffic stats) | Production v2rayNG | Mature but v2ray-flavoured naming; v2rayNG-specific API surface. |
| **Wrapper repo: `XTLS/libXray`** (MIT) | `.aar` + `.xcframework` from one Python build script, exposing JSON-in / JSON-out RPC | Hiddify, NekoBox-style apps | **Recommended.** Official XTLS-maintained, MIT-licensed wrapper, builds for all 4 ABIs from a single command, stable enough for production use, decouples us from gomobile flag wrangling. |

`XTLS/libXray` is the recommended path. It exists precisely so app developers do not have to learn `gomobile`'s `androidapi`, `ldflags`, `trimpath`, NDK toolchain plumbing every release cycle. Build command, on a Linux/macOS box with Go 1.22+ and Android NDK r26+ installed:

```bash
git clone https://github.com/XTLS/libXray
cd libXray
python3 build/main.py android      # produces build/output/libXray.aar
```

That single artifact contains arm64-v8a, armeabi-v7a, x86_64, x86 native libs plus the Kotlin/Java RPC shim (`go.Seq`, `LibXray.*`). Min SDK 21. Source: [XTLS/libXray README](https://github.com/XTLS/libXray) and [XTLS/Xray-core Discussion #5167](https://github.com/XTLS/Xray-core/discussions/5167).

### 2.2 Reference: what Hiddify-Next does

[`hiddify/hiddify-app`](https://github.com/hiddify/hiddify-app) is the Flutter-based multi-platform client. For Android it pulls in a precompiled core via [`hiddify/Hiddify-Xray-core`](https://github.com/hiddify/Hiddify-Xray-core) (a soft fork of XTLS/Xray-core with their custom geoip/geosite assets baked in). Build is CI-driven (`Xray-core-custom/install-release.sh`), output is the same `.aar` shape. We do not want to fork Xray-core ourselves, so the Hiddify pattern is "use the upstream + ship our own assets externally" — exactly the libXray model.

### 2.3 Realistic APK size impact

Numbers below are what AndroidLibXrayLite / libXray release artifacts have been tracking through 2025–2026 (XTLS/libXray Releases + 2dust/AndroidLibXrayLite Releases). PHANTOM today ships a single fat APK; once we add ABI splits via App Bundle this is per-ABI download size, not per-APK install size:

| ABI | Stripped `.so` (uncompressed) | Compressed contribution to APK |
|---|---|---|
| arm64-v8a | ~14 MB | ~5–6 MB |
| armeabi-v7a | ~10 MB | ~4 MB |
| x86_64 | ~14 MB | ~5–6 MB |

For our user base (overwhelmingly arm64-v8a Android phones) the relevant added install size is **~5–6 MB compressed, ~14 MB on disk**. This is large but not unreasonable for what we get; comparable to the Tor binary we already ship via `tor-android`.

If we want to keep the global-distribution single APK lean, ship App Bundle (`bundleRelease`) so Play / direct-download flow per-ABI and most users never download arm-v7a or x86_64. F-Droid build will take the full hit.

### 2.4 Build gotchas we will hit

- **CGO + NDK toolchain version drift.** libXray's build script pins NDK r26 today; bumping NDK in our `apps/android/build.gradle.kts` while libXray expects an older version produces silent ABI mismatches. Pin the NDK version in CI.
- **`androidx.startup` initializer for Go runtime.** The Go runtime owns its own goroutines and signal handlers. Initializing it on app cold start (vs. lazily on first use) keeps stop/start predictable but adds ~150–250 ms to cold start. Lazy init on first Privacy-Mode resolution is preferable for PHANTOM's UX.
- **Symbol stripping.** Build flags `-ldflags="-s -w" -trimpath` knock another ~25 % off the `.so`; libXray applies these by default but verify in a release build.
- **`16 KB page size` ABI requirement.** Android 15+ requires native libs to support 16 KB page-size alignment. libXray builds done with NDK r26+ are already compliant; older toolchains will trip the Play Console preflight.

---

## 3. B) Server-side REALITY configuration

### 3.1 Concrete `config.json`

Below is a minimal, production-ready Xray REALITY server config for a single PHANTOM deployment. It listens on **port 8443** so we do not fight Caddy for 443 in this first iteration (see §3.4 for the upgrade path to shared 443).

```jsonc
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "tag": "vless-reality-in",
      "listen": "0.0.0.0",
      "port": 8443,
      "protocol": "vless",
      "settings": {
        "clients": [
          {
            "id": "00000000-0000-0000-0000-000000000000",   // UUID — generate with `xray uuid`
            "flow": "xtls-rprx-vision"
          }
        ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "www.microsoft.com:443",
          "xver": 0,
          "serverNames": [
            "www.microsoft.com"
          ],
          "privateKey": "REPLACE_WITH_OUTPUT_OF_xray_x25519_PRIVATE",
          "shortIds": [
            "",                                               // empty allowed = any client
            "0123456789abcdef"
          ],
          "minClientVer": "1.8.0"
        }
      },
      "sniffing": {
        "enabled": true,
        "destOverride": ["http", "tls", "quic"],
        "routeOnly": true
      }
    }
  ],
  "outbounds": [
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block",  "protocol": "blackhole" }
  ],
  "routing": {
    "rules": [
      // VLESS clients can only reach the relay onion. Everything else
      // gets blackholed so the server is not a generic open proxy.
      {
        "type": "field",
        "inboundTag": ["vless-reality-in"],
        "domain": ["full:zmdrxlrkd7iv7ozvdl5nlhctsxgx6eyuqionp6xzriolymy3m6ioloyd.onion"],
        "outboundTag": "direct"
      },
      { "type": "field", "inboundTag": ["vless-reality-in"], "outboundTag": "block" }
    ]
  }
}
```

Source patterns: [Xray-examples/VLESS-TCP-XTLS-Vision-REALITY/config_server.jsonc](https://github.com/XTLS/Xray-examples/blob/main/VLESS-TCP-XTLS-Vision-REALITY/config_server.jsonc), [chika0801/Xray-examples](https://github.com/chika0801/Xray-examples), [XTLS/Xray-core Discussion #3518](https://github.com/XTLS/Xray-core/discussions/3518).

Generate keys and IDs:

```bash
docker run --rm ghcr.io/xtls/xray-core x25519
# Private key: ...   ← into realitySettings.privateKey
# Public  key: ...   ← into APK / share link
openssl rand -hex 8
# 16 hex chars       ← into shortIds array; client picks one
```

### 3.2 Target site selection (May 2026)

REALITY's `dest` must be a TLS 1.3 + HTTP/2 host whose certificate is in the cert chain we want to fingerprint as. Russia / TSPU specifics matter because if TSPU itself is throttling our chosen `dest`, REALITY does not save us.

| Candidate `dest` | Status in RU 2026 | Recommendation |
|---|---|---|
| `www.microsoft.com:443` | Reachable, very high background traffic volume, cert chain stable, M$ cloud has been blocked for *enterprise* sign-ins (per Moscow Times / The Record) but the marketing site itself is not throttled. | **Default choice.** Highest-volume, most-camouflaged. |
| `www.apple.com:443` | Reachable, very high baseline. Good fallback. | Acceptable. |
| `cloud.google.com:443` | Reachable but lower volume; Google services intermittently throttled. | Avoid as primary. |
| `www.python.org:443` | Reachable, niche traffic. Used as `dest` in some Habr tutorials. | Don't use — too low volume → suspicious. |
| `gateway.icloud.com:443` | High volume. | Acceptable alternate. |
| Any RU-domestic site | Defeats the purpose (TSPU sees us connecting to a domestic site we don't own). | Never. |

Community wisdom in 2026 ([Habr](https://habr.com/en/articles/990128/), [GreatFirewallGuide.com](https://greatfirewallguide.com/lab/vless-reality-vision)): **rotate `serverNames` between two or three high-volume Western targets every 4–8 weeks** so TSPU heuristics watching long-lived flows cannot accumulate a per-(srcIP, sni) profile. Default ship `www.microsoft.com`; ship `www.apple.com` as an alternate config. Both are fine in May 2026.

### 3.3 Key rotation cadence

| Asset | Rotate when | Impact on shipped APKs |
|---|---|---|
| `privateKey` (X25519) | Server compromise suspected, otherwise never. | All APKs containing the matching public key continue to work; rotation breaks every shipped client. |
| `shortIds` | Add new ones freely; remove an old one only after we've shipped an APK that uses a new one. | Removing a shortId immediately breaks all APKs whose embedded config uses it. |
| `serverNames` (`dest`) | Every 4–8 weeks, or on heuristic suspicion. | Old APKs continue to work — REALITY clients send the SNI they were configured with, and we keep both `serverNames` valid for a transition window. |
| UUID | Never per-user (we are not multi-tenant in this model). | n/a |

Rotation discipline: never remove a shortId from server config without a corresponding APK release that has been distributed for at least the supported-version-window. Same fingerprint-pin discipline as our WebTunnel bridge today.

### 3.4 Coexistence with Caddy on 443

Currently the relay's Caddy holds 443 for HTTPS reverse proxy to the websocket endpoint and (separately) to the WebTunnel bridge URL. Three options:

1. **REALITY on a separate port (8443).** Cleanest. No interaction with Caddy. Downside: 8443 is mildly atypical and TSPU may apply different shaping policies to non-443 high ports. **Pick this for Stage 5E.A** (operationally simplest).
2. **REALITY on 443, Caddy as REALITY's fallback.** Xray REALITY listens on 443; non-REALITY traffic (anything that fails the REALITY handshake check) gets PROXY-protocol-forwarded to Caddy on a private port. Caddy is configured with `listener_wrappers { proxy_protocol }`. This is the [henrywithu](https://henrywithu.com/the-ultimate-guide-coexisting-web-apps-with-xrays-vision-and-reality-on-a-single-port/) / [XTLS Discussion #4542](https://github.com/XTLS/Xray-core/discussions/4542) pattern. **Pick this for Stage 5E.B** once 5E.A proves the wire format works.
3. **Caddy on 443, Xray behind Caddy.** Does not work — REALITY uses TLS handshake-level secret-knock that Caddy cannot pass through transparently. ([XTLS Discussion #5774](https://github.com/XTLS/Xray-core/discussions/5774).)

### 3.5 Resource profile on shared VPS

The Hetzner CPX22 VPS (3 vCPU / 4 GB RAM / Helsinki) currently hosts: Caddy + relay (Rust + Tokio) + tor-onion-service + `phantom-webtunnel-bridge` container. Adding `xray` daemon:

- **Idle RAM:** Xray ~30–50 MB resident at 0 active connections (Go runtime baseline). [GitHub Discussion #5719](https://github.com/XTLS/Xray-core/discussions/5719) reports xhttp-mode leaks; vanilla VLESS+REALITY is well-behaved.
- **Per-connection RAM:** ~40–55 KB TCP buffers + TLS state per client. PHANTOM's Alpha-2 user count is small (tens, low hundreds). Headroom is fine.
- **CPU:** TLS 1.3 + AES-NI (CPX22 supports it) means handshake cost is negligible. AES-GCM data path is ~700 MB/s on this CPU class — never the bottleneck.
- **Conflict with our bridge container:** WebTunnel bridge holds 443 inside its container, mapped to host 443 by docker-compose. If we chose option 2 (REALITY on 443) we'd need to put WebTunnel on a different host port. Option 1 (REALITY on 8443) avoids all reshuffling.

Recommendation: deploy Xray as a separate `docker compose` service alongside the existing bridge, healthcheck via `xray test -config /etc/xray/config.json` plus `nc -z localhost 8443`.

---

## 4. C) Client-side Android integration

### 4.1 Module layout

Proposed new gradle module siblings to `shared/core/transport`:

```
shared/core/xray/
  build.gradle.kts                               # androidLibrary plus depend on libXray.aar
  src/commonMain/kotlin/phantom/core/xray/
    XrayService.kt                               # interface (start/stop/state)
    XrayState.kt                                 # sealed class Off/Starting/Ready(socksPort)/Failed
    XrayServiceConfig.kt                         # data class: server addr, sni, pubKey, shortId, uuid, dest
    XrayServiceFactory.kt                        # expect fun createXrayService(config, ctx)
  src/androidMain/kotlin/phantom/core/xray/
    XrayServiceAndroid.kt                        # uses go.Seq libXray.* RPC
    XrayServiceFactory.android.kt                # actual fun
    libs/libXray.aar                             # vendored binary, see §A.1
  src/jvmMain/                                   # no-op stub (desktop tests)
```

Why a new module rather than extending `core/transport`: lifecycles are independent (Xray is one OSI layer below our existing transport selection), and we want the option to disable the whole module at compile time for builds where REALITY is not desired (Beta dual-track, F-Droid reproducible builds).

### 4.2 JNI / FFI sketch

libXray exposes a JSON-in / JSON-out RPC surface via gomobile-generated bindings. The Kotlin side looks like:

```kotlin
// XrayServiceAndroid.kt (sketch)
internal class XrayServiceAndroid(
    private val context: Context,
    private val config: XrayServiceConfig,
) : XrayService {

    private val _state = MutableStateFlow<XrayState>(XrayState.Off)
    override val state: StateFlow<XrayState> = _state.asStateFlow()

    @Volatile private var instanceHandle: String? = null

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (_state.value is XrayState.Ready) return@withContext
        _state.value = XrayState.Starting
        val xrayJson = renderXrayClientConfig(config)              // see §4.3
        val req = JSONObject().apply {
            put("datDir", context.filesDir.resolve("xray-assets").absolutePath)
            put("configRaw", xrayJson)
        }
        // libXray.LibXray.runXray returns base64-JSON status payload
        val resp = libxray.LibXray.runXray(base64(req.toString()))
        val parsed = JSONObject(String(Base64.decode(resp, Base64.DEFAULT)))
        if (!parsed.getBoolean("success")) {
            _state.value = XrayState.Failed(parsed.optString("err"))
            return@withContext
        }
        instanceHandle = parsed.getString("data")                  // opaque handle
        _state.value = XrayState.Ready(socksPort = config.localSocksPort)
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        val h = instanceHandle ?: return@withContext
        libxray.LibXray.stopXray(h)
        instanceHandle = null
        _state.value = XrayState.Off
    }
}
```

The exact RPC names (`runXray` / `stopXray` / `testXray`) are stable across libXray 23.x–24.x; the project explicitly disclaims API stability across major versions, so pin the AAR version in CI and re-test the JSON payload shapes when bumping. Source: [XTLS/libXray README](https://github.com/XTLS/libXray) §"How to use".

### 4.3 Rendered client config

The client config we hand to libXray is a tiny VLESS outbound + a SOCKS5 inbound on 127.0.0.1:

```jsonc
{
  "log": { "loglevel": "warning" },
  "inbounds": [{
    "tag": "socks-in",
    "listen": "127.0.0.1",
    "port": 39060,
    "protocol": "socks",
    "settings": { "udp": false, "auth": "noauth" }
  }],
  "outbounds": [{
    "tag": "vless-reality-out",
    "protocol": "vless",
    "settings": {
      "vnext": [{
        "address": "relay.phntm.pro",
        "port": 8443,
        "users": [{
          "id": "<UUID>",
          "encryption": "none",
          "flow": "xtls-rprx-vision"
        }]
      }]
    },
    "streamSettings": {
      "network": "tcp",
      "security": "reality",
      "realitySettings": {
        "fingerprint": "chrome",
        "serverName": "www.microsoft.com",
        "publicKey": "<server X25519 pub>",
        "shortId": "0123456789abcdef",
        "spiderX": "/"
      }
    }
  }]
}
```

`spiderX` is REALITY's anti-probing decoy path — when the client validates the server cert and gets the *real* Microsoft cert (server didn't accept REALITY auth), the client then mimics a real browser by GET-ing `serverName + spiderX` as cover. Ship `"/"` to look like an idle homepage open.

### 4.4 Lifecycle

Xray client runs **inside `PhantomMessagingService`** (the existing foreground service that already hosts tor + relay websocket + push). One foreground notification, one wakelock, predictable user mental model.

Order of operations on Privacy-Mode resolution:

```
PhantomMessagingService.onCreate
  → TransportSelector.choose(privacyMode, networkClass)
    → Standard / direct-WSS-reachable: skip xray, skip tor
    → Standard / direct-WSS-fails:
        startXray() → wait Ready(socksPort)
                    → KtorRelayTransport(socksPort = xraySocks)
    → Private / RU detected:
        startXray() → wait Ready
                    → startTor()        ← ADR-018 Stage 5C path (still useful as fallback)
                    → connect WSS via xraySocks
    → Ghost:
        startXray() → wait Ready
                    → startTor() bound to xraySocks (tor's Socks5Proxy directive)
                    → connect WSS via tor
```

For Ghost mode specifically, this is `REALITY-wrapped Tor` — tor's TCP traffic to its guards is itself wrapped in REALITY, hiding the fact that you are using tor at all. (TSPU detects tor today via guard fingerprint regardless of bridges; REALITY-wrapping closes that signal at the cost of ~3 RTTs of extra latency and our server appearing in tor exit/guard logs.)

### 4.5 Threading

Go runtime owns its own OS threads via gomobile. **Critical**: do not call `runXray` from a coroutine that may be cancelled mid-RPC — the JNI call is synchronous and cancellation cannot interrupt the Go side cleanly. Wrap in `withContext(NonCancellable + Dispatchers.IO)` for the actual `runXray` / `stopXray` calls; the suspending wrapper is cancellable, the JNI call is not. This mirrors how we treat Briar's `wrapper.start()`.

### 4.6 Connection model decision: REALITY → relay direct, or REALITY → tor → onion?

| Mode | Path | Pros | Cons |
|---|---|---|---|
| **REALITY → relay direct** (skip tor) | client-REALITY-tunnel → server-Xray → loopback to relay.sock | Fastest. ~80 ms RTT vs. ~600 ms via tor onion. | Server logs source IP in the relay's connection accept (we then immediately throw it away in code, but it transits memory). Relay operator can correlate. |
| **REALITY → tor → onion** (wrap tor) | client-REALITY → server-Xray → loopback to tor → onion service → relay | Onion service identity model preserved; even compromised relay box does not see client IP. | ~700 ms RTT, three encryption layers (REALITY + tor + Noise/onion). |

For PHANTOM's threat model:
- **Standard / Private modes:** REALITY → relay direct is acceptable. Standard mode users have already accepted that the relay sees their IP via the WSS connection; REALITY just removes the censor in front of that.
- **Ghost mode:** REALITY → tor → onion. The whole point of Ghost is the relay does not see your IP. REALITY must NOT short-circuit that.

This matches what PHANTOM_ROADMAP_2026.md already says about tiered Privacy Modes.

### 4.7 Config provisioning

Hardcode the public key + shortId + UUID + SNI in the APK at build time, sourced from `gradle.properties`-equivalent secrets, so nothing is fetched dynamically (a fetched config is a censorship attack surface). New BuildConfig fields:

```
PHANTOM_XRAY_SERVER          = "relay.phntm.pro"
PHANTOM_XRAY_PORT            = 8443
PHANTOM_XRAY_UUID            = "..."       # generated once, stable
PHANTOM_XRAY_PUBKEY          = "..."       # X25519 pub
PHANTOM_XRAY_SHORT_ID        = "0123456789abcdef"
PHANTOM_XRAY_SNI             = "www.microsoft.com"
PHANTOM_XRAY_SPIDER_X        = "/"
```

Same pattern as the WebTunnel bridge fingerprint embedding today (`OperatorBridges.WEBTUNNEL`). Rotation = APK release.

---

## 5. D) Security & threat model deltas

### 5.1 REALITY's anti-probing posture

REALITY's defense against active probing is structural, not heuristic. When TSPU sends a probe TLS handshake to our `relay.phntm.pro:8443`:
- Probe sends ClientHello with arbitrary SNI / random extensions.
- Xray inspects the inner SNI; if it does not match a configured `serverName`, or if the embedded REALITY auth doesn't validate against our X25519 keypair + shortId, the entire TCP connection is **transparently reverse-proxied to `dest` (= `www.microsoft.com:443`)** at the byte level.
- Probe sees: a real `www.microsoft.com` TLS handshake, real Microsoft cert chain (ours just relayed bytes), real HTTP/2 frames if probe goes that far.
- TSPU's verdict: "this is www.microsoft.com." Done.

This is a much stronger story than obfs4 / WebTunnel, which can be probed via traffic-shape-after-handshake heuristics. Source: [XTLS/REALITY README](https://github.com/XTLS/REALITY/blob/main/README.en.md), [XTLS Discussion #2623](https://github.com/XTLS/Xray-core/discussions/2623).

### 5.2 Metadata leak to `dest`

When Xray relays a probe to `www.microsoft.com`, Microsoft sees a TCP connection from our Hetzner IP. Microsoft sees:
- Source IP = our VPS, never the original prober's IP.
- TLS SNI = `www.microsoft.com` (because we are passing the byte stream straight through).
- Volume = bounded by probe rate. In practice TSPU probes at single-digit per hour per target IP. Negligible.

Microsoft does NOT see PHANTOM client IPs at any time, because PHANTOM clients are the ones whose REALITY auth *succeeds* — they never trigger the proxy fallback path. Probes are the only traffic that ever reaches `dest`.

### 5.3 License — the critical question

**Xray-core is MPL-2.0 + Exhibit B "Incompatible With Secondary Licenses"** ([LICENSE](https://github.com/XTLS/Xray-core/blob/main/LICENSE)). PHANTOM is **AGPL-3.0-or-later**.

Naive read: incompatible. Actual MPL semantics:

- MPL is **file-level copyleft**. The Exhibit B notice prevents *relicensing the MPL files themselves under AGPL*.
- It does NOT prevent combining MPL files with AGPL files into a "Larger Work" so long as each file keeps its original license. Mozilla's [MPL FAQ §I.10](https://www.mozilla.org/en-US/MPL/2.0/FAQ/) and [MPL §3.3](https://www.mozilla.org/en-US/MPL/2.0/) make this explicit.
- We ship `libXray.aar` (which is a separate compiled binary file) alongside our AGPL Kotlin code in the same APK. Each `.so` is one file; each `.kt` is another file. They are aggregated, not merged-source.
- This is the same legal posture under which **v2rayNG (GPL-3.0)** and **Hiddify-app (GPL-3.0)** ship Xray-core in their APKs today, in production, with no upstream complaints. (Xray-core doesn't lose its MPL status; the AGPL parts of our APK keep AGPL.)

**Pragmatic conclusion:** legally sound; ship a `LICENSES/` directory in the APK assets that clearly attributes Xray-core under MPL-2.0 and lists the upstream commit / source URL, satisfying MPL §3.2 source-availability. Do not relicense any Xray-core source files. Do not modify Xray-core source files in our tree (use `XTLS/libXray` as-is, or fork only with full upstream attribution).

**Defense-in-depth:** before Beta, run this past NLnet's legal review (their grant program includes free legal counsel for FOSS license questions). One paragraph email confirming this analysis = belt + suspenders.

### 5.4 Cryptographic posture

Xray REALITY uses standard TLS 1.3 + X25519 ECDH for the REALITY auth + HKDF for key derivation + AES-128-GCM / ChaCha20-Poly1305 for record encryption (whatever the negotiated TLS suite picks). Nothing custom. ML-DSA-65 post-quantum signatures are an *optional* layer ([REALITY README](https://github.com/XTLS/REALITY/blob/main/README.en.md)) — not enabling it for Stage 5E.A; revisit when the IETF PQ TLS transition stabilizes.

### 5.5 Threat-model deltas summary

| Threat | Pre-REALITY (Tor-only) | With REALITY wrapper |
|---|---|---|
| TSPU TLS-shape throttle | Bridge stalls at 16 KB | Looks like microsoft.com, no throttle |
| Tor guard fingerprint detection | Direct guards blocked, bridges marginal | Hidden inside REALITY (Ghost mode) |
| Active probing of `relay.phntm.pro` | Probe sees obviously-wrong server | Probe sees genuine microsoft.com cert chain |
| Server compromise | Onion identity protects client IP | Same — onion still terminates the path in Ghost mode |
| Microsoft / `dest` learning about us | n/a | Sees ~probe-rate traffic from our VPS only |

---

## 6. E) Operations

### 6.1 Release cadence

Xray-core ships approximately every 4–6 weeks ([XTLS releases](https://github.com/XTLS/Xray-core/releases)). Security-relevant releases are uncommon but do happen (~1–2/year). libXray tags within ~7 days of upstream Xray.

### 6.2 Monitoring

Server-side, add to existing Prometheus exporter on the VPS:

- `xray_up{instance=…} = 1` from blackbox-exporter probing `tcp_connect` on 8443.
- `xray_handshake_success_total{}` from Xray's `/metrics` endpoint (enable `metrics` inbound on a private port, scrape from localhost only).
- `xray_handshake_fail_total{reason="bad_short_id"|"bad_pubkey"|"bad_uuid"}` — high `fail` rate spikes are either (a) an active prober (expected, low rate) or (b) a client-key mismatch after a botched rotation (alert).

Alert: page the operator if `xray_up == 0` for >5 min. Re-page WebTunnel and tor probes to share the same alert pipeline.

### 6.3 Update path

There is no in-band update mechanism — same as everything else in the APK. Security fix in libXray = rebuild PHANTOM APK = users update via Play / direct download / F-Droid. This is fine and is the same constraint as the bundled tor binary today. If a CVE drops, same incident-response runbook as tor-android CVE handling.

### 6.4 Key rotation discipline (consolidated)

| Event | Server change | APK change | User impact |
|---|---|---|---|
| New shortId added | Add to `shortIds` array | None until next release | Old APKs unaffected. |
| Old shortId retired | Remove from `shortIds` array, *after* APK migration | Rebuild required | APKs still on old shortId stop working — coordinate with release. |
| `serverNames` rotation (e.g., microsoft → apple) | Add new SNI to `serverNames`, keep old for a window | Optional (ship new SNI in next APK) | Both work during overlap window. |
| `privateKey` rotation | Replace privateKey in config | Mandatory APK with new pubkey | Hard cut — every installed APK breaks. Treat as nuclear option. |
| UUID rotation | Replace `clients[].id` | Mandatory APK | Same as privateKey: hard cut. |

---

## 7. Concrete PR scope — Stage 5E.A (server-side only)

**PR title:** `feat(transport): Stage 5E.A — Xray VLESS+REALITY server inbound`

**PR scope:** server-side deployment only, no client code, no APK changes. Validates the wire format with a desktop NekoBox / NekoRay client before we commit to the Android integration in 5E.B.

### 7.1 Files to create

```
deploy/xray-reality-setup.md                         # operator runbook
deploy/xray/docker-compose.yml                       # service def
deploy/xray/config.json                              # the §3.1 config, with key placeholders
deploy/xray/healthcheck.sh                           # nc + xray test wrapper
docs/adr/ADR-019-xray-reality-outer-wrapper.md       # decision log (this research doc → ADR)
```

### 7.2 Files to modify

```
deploy/README.md                                     # add §"Xray REALITY (Stage 5E)"
docs/project/MASTER_TIMELINE_2026.md                 # tick off 5E.A row
```

### 7.3 Order of operations for the implementing engineer

1. **Generate REALITY keypair on the VPS** (not on dev laptop): `docker run --rm ghcr.io/xtls/xray-core x25519`. Capture pub + priv to a `pass`-managed secret. Generate UUID via `xray uuid` and one shortId via `openssl rand -hex 8`.
2. **Write `deploy/xray/config.json`** from the §3.1 template, with placeholders for the secrets. Add to `.gitignore` so the actual secrets never land in git.
3. **Author `deploy/xray/docker-compose.yml`** with service `phantom-xray`, image pinned to `ghcr.io/xtls/xray-core:25.x.x` (current at PR time), volume-mount config + assets (`geoip.dat`, `geosite.dat` from xtls/Xray-data), restart `unless-stopped`, port-publish `8443:8443/tcp`.
4. **Author `deploy/xray/healthcheck.sh`** that runs both `xray test -c /etc/xray/config.json` and `nc -z localhost 8443`; wire it into compose's `healthcheck:`.
5. **Author `deploy/xray-reality-setup.md`** in the same shape as `deploy/webtunnel-bridge-setup.md`: prerequisites, generate-keys section, deploy section, validation section (Xray client desktop tool connecting + curling our relay onion through it), rotation section, troubleshooting.
6. **Author ADR-019** distilling §1, §3, §5 of this research doc into the decision log format ADR-016/017/018 use.
7. **Validate end-to-end manually:**
   - Install NekoBox on a Linux desktop in Russia (or via VPN simulating TSPU egress).
   - Configure VLESS+REALITY pointed at `relay.phntm.pro:8443` with our pub/UUID/shortId/SNI.
   - Verify SOCKS5 listener on 127.0.0.1:1080 reaches relay onion via `curl --socks5h 127.0.0.1:1080 <onion>/health`.
   - Probe directly: `curl -v https://relay.phntm.pro:8443/` should return Microsoft's homepage (REALITY's cover behavior — proves the proxy-fallback works).
8. **Update MASTER_TIMELINE row 5E.A → done.**

PR does NOT touch any `shared/*` or `apps/android/*` code. Reviewable in <300 LOC of YAML + JSON + markdown. CI green = no Kotlin / no Rust changes.

### 7.4 Stage 5E.B (next PR after 5E.A merges)

Out of scope for this doc, but for sequencing context: 5E.B adds the `shared/core/xray` Kotlin module, vendored `libXray.aar`, BuildConfig fields, and wires `XrayService` into `PhantomMessagingService` lifecycle behind a feature flag (`PHANTOM_TRANSPORT_XRAY=true`). Until 5E.C flips the default, behavior is unchanged for users; opt-in via debug-menu toggle for operator field-testing on a Tecno + Russian SIM.

---

## 8. Risks & open questions

1. **Does `8443` survive TSPU?** Stage 5E.A validates this on a single test SIM. If TSPU specifically shapes high-port TLS differently than 443, we fall back to Stage 5E option-2 (REALITY on 443, Caddy as fallback) and re-test. Need a Tecno-on-MTS test as the gate.
2. **libXray API churn between the version we vendor in 5E.B and the one we vendor in Beta.** Mitigation: pin AAR version, keep the JNI shim layer thin, write JSON-RPC contract tests in `commonTest`.
3. **Symbol collisions between Xray's vendored Go runtime and any other Go-built `.so`** we might add later (Go's runtime is statically linked into each `.so`, so two distinct gomobile-built libs in the same APK both contain `runtime.morestack` etc.). Today we have no other Go libs; flag this in code review when one is proposed.
4. **NLnet legal review on MPL+Exhibit-B aggregation with AGPL.** Stated in §5.3; queue an email to NLnet legal when the application advances to interview stage. Pre-emptive evidence: v2rayNG (GPL) and Hiddify-app (GPL) already do this, no upstream complaint, no SFConservancy enforcement action.
5. **REALITY's `dest` is a third-party site we don't control.** If Microsoft suddenly drops TLS 1.3 + H2 (won't), or rotates their cert chain to ECDSA-only in a way that breaks our cert capture (unlikely), our REALITY listener silently fails. Mitigation: monitoring (§6.2) detects handshake-fail-rate spikes; runbook says "rotate `serverNames` to `apple.com` if microsoft chain breaks."
6. **Reproducible builds.** Vendoring a prebuilt `.aar` makes F-Droid's reproducible-build verification harder. F-Droid has accepted prebuilt cores for v2rayNG / Hiddify; document the upstream `libXray` commit and build environment in `fastlane/metadata/android/...`. Belt-and-suspenders: optionally provide a script that rebuilds `libXray.aar` from upstream and bytewise-compares to the vendored copy.
7. **UDP / VOIP traffic.** Our voice/video over WebRTC is stateful UDP. VLESS over TCP+REALITY tunnels TCP only; UDP needs a separate inbound (`socks` with `udp: true`) or a different transport (`xhttp`, which itself has memory issues per [Discussion #5719](https://github.com/XTLS/Xray-core/discussions/5719)). Voice traffic is not in Stage 5E scope; revisit when call quality on censored networks becomes the priority.
8. **Detection in the wild.** REALITY has been "discovered" by Russia and China research papers, but the prevailing answer in the field is that no scalable production attack exists today. This may change. Maintain bridge fallback (Stage 5C) as defense-in-depth so a future REALITY break does not strand our user base; never let REALITY become the *only* path.

---

## Sources

- [XTLS/Xray-core — Project X main repo](https://github.com/XTLS/Xray-core)
- [XTLS/libXray — official mobile wrapper](https://github.com/XTLS/libXray)
- [XTLS/REALITY — protocol spec & threat model](https://github.com/XTLS/REALITY)
- [XTLS/Xray-examples — VLESS-TCP-XTLS-Vision-REALITY config](https://github.com/XTLS/Xray-examples/blob/main/VLESS-TCP-XTLS-Vision-REALITY/config_server.jsonc)
- [chika0801/Xray-examples](https://github.com/chika0801/Xray-examples)
- [Project X docs — Transport (uTLS, REALITY)](https://xtls.github.io/en/config/transport.html)
- [XTLS/Xray-core Discussion #5167 — How to add Xray to an app](https://github.com/XTLS/Xray-core/discussions/5167)
- [XTLS/Xray-core Discussion #4542 — Xray + VLESS + Reality + Caddy](https://github.com/XTLS/Xray-core/discussions/4542)
- [XTLS/Xray-core Discussion #5774 — Reality with Caddy fallback](https://github.com/XTLS/Xray-core/discussions/5774)
- [XTLS/Xray-core Discussion #2623 — Avoiding active probing](https://github.com/XTLS/Xray-core/discussions/2623)
- [Henrywithu — Coexisting Web Apps with Xray Vision and Reality on a Single Port](https://henrywithu.com/the-ultimate-guide-coexisting-web-apps-with-xrays-vision-and-reality-on-a-single-port/)
- [Habr — Installing and Configuring a VPN with VLESS and Reality (EN)](https://habr.com/en/articles/990128/)
- [GreatFirewallGuide — VLESS-Reality Setup Guide 2026](https://greatfirewallguide.com/lab/vless-reality-vision)
- [hiddify/hiddify-app — Hiddify Next multi-platform client](https://github.com/hiddify/hiddify-app)
- [hiddify/Hiddify-Xray-core — Hiddify's Xray fork](https://github.com/hiddify/Hiddify-Xray-core)
- [2dust/v2rayNG — production Android client](https://github.com/2dust/v2rayNG)
- [SaeedDev94/Xray — alt Android client, buildXrayCore.sh reference](https://github.com/SaeedDev94/Xray/blob/master/buildXrayCore.sh)
- [Mozilla MPL 2.0 FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/)
- [Mozilla — MPL-in-GPL Developer Guidelines](https://www.mozilla.org/en-US/MPL/2.0/combining-mpl-and-gpl/)
- [FSF — Various Licenses and Comments](https://www.gnu.org/licenses/license-list.en.html)
- [FBK / ACF — Access Denied: How the Kremlin shapes the Russian internet (2026)](https://fbk.info/files/acf-internet-report-EN.pdf)
- [Zona.media — Russia's internet censorship in 2026](https://en.zona.media/article/2026/04/07/russian_internet_censorship_2026)
- [The Record — Russians losing access to Microsoft cloud](https://therecord.media/russians-losing-access-microsoft-cloud-amazon)
- [PremierVPN — VLESS + REALITY Protocol Explained](https://premiervpn.net/blog/vless-reality-vpn-protocols-defeating-censorship-2026)
- [XTLS/Xray-core Discussion #5719 — High RAM/CPU in xhttp](https://github.com/XTLS/Xray-core/discussions/5719)
