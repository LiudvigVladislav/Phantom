# ADR-019: Xray VLESS+REALITY as outer transport for censorship resistance

Status: accepted (2026-05-07, production-validated on Tecno МТС without VPN)
Layer: shared/core/xray (KMP, new module), apps/android, deploy/xray
Extends: ADR-016 (Tor + UnifiedPush hybrid transport),
ADR-018 (Tor stage progression — Stage 5E supersedes the Stage 5C/5D
bridge approach for Russian carrier traffic)

## Context

ADR-016 set PHANTOM's design goal as "works without VPN, anywhere"
and introduced Tor as the data-plane transport for restrictive
networks. Stages 5A through 5D progressively extended that with
embedded kmp-tor (5A/5B), an operator-controlled WebTunnel bridge on
Hetzner (5C), and a multi-bridge fan-out to FlokiNET RO (5D).

All four stages **failed** end-to-end on Russian carrier traffic
(MTS, Megafon — confirmed by tests 11/12/13 on 2026-05-05/06).

The failure mode was identified as the **TSPU 16-kilobyte curtain**:
Russia's Roskomnadzor-deployed deep-packet-inspection middleware
(TSPU) silently throttles TLS streams that grow past ~16 KB to
flagged datacenter ASNs (Hetzner confirmed first; FlokiNET confirmed
second — the curtain is behavioural, not ASN-allowlist-based).
Tor's WebTunnel pluggable transport tunnels Tor consensus documents
through a single TLS stream, and the consensus itself is well over
16 KB, so the bootstrap froze at 14 % every time on carrier networks.

Two parallel research threads ruled out the obvious workarounds:

- **Move bridges off Hetzner.** Test 13 showed FlokiNET RO bridges
  exhibited identical stall behaviour on the same Russian carriers,
  one hour after deploy. The curtain is not about Hetzner's ASN —
  it is about the TLS-stream-size signature itself triggering the
  throttle on any flagged datacenter range.
- **Switch to obfs4 / meek / Snowflake.** All three are known-blocked
  on Russian carriers since 2024 (publicly tracked). Snowflake
  brokers are Netlify-hosted; Netlify is intermittently blocked.
  obfs4 fingerprints are passively detected. Meek-azure was
  decommissioned by Microsoft.

The only known censorship-resistance technique that empirically
defeats TSPU on Russian carriers without a third-party app is
**VLESS+REALITY**: Xray-core's outer-protocol implementation that
makes the client's TLS handshake byte-for-byte indistinguishable
from a genuine handshake to a chosen cover host (typically
`www.microsoft.com`). The censor's classifier sees a trusted-CDN
TLS handshake to a real Microsoft endpoint and applies no throttle.

Stage 5E records the decision to embed VLESS+REALITY into PHANTOM
as a parallel outer transport alongside the Tor stack from Stages 5A-5C.

## Decision

PHANTOM adds a third outer-transport option for the data plane:
**Xray-core embedded on Android via libXray (gomobile JNI), connecting
to an operator-controlled VLESS+REALITY listener on the same Hetzner
VPS as the relay**.

The deployment shape:

- **Server side** (`deploy/xray/`): a Xray-core 26.3.27 container
  bound to `:8443/tcp` on the operator VPS. REALITY mimics the
  TLS-1.3 handshake to `www.microsoft.com:443`. VLESS clients land
  on a freedom outbound that resolves `relay.phntm.pro` via a docker
  bridge DNS hosts override (`relay.phntm.pro → caddy`) — the
  unwrapped traffic exits the Xray container straight into the Caddy
  container over the docker bridge network, never touching the
  public internet.
- **Client side** (`shared/core/xray/`): a new KMP module wrapping
  libXray's gomobile-compiled `.aar`. Exposes an `XrayService`
  interface (Off → Starting → Ready(socksPort) → Failed) symmetric
  with the existing `TorService`. On Android, the service starts a
  loopback SOCKS5 listener on `127.0.0.1:10808` and forwards each
  connection through VLESS+REALITY to the Hetzner server.
- **Build-time selection**: a `USE_XRAY` BuildConfig flag, mutually
  exclusive at compile time with `USE_TOR` (gradle errors if both
  are true). The Stage 4 Privacy-Mode UI will replace this static
  flag with a runtime choice, but the underlying wiring in
  `PhantomMessagingService` is identical for both flags.
- **End-to-end shape** for `USE_XRAY=true`:

  ```
  Phantom client  ──SOCKS5(127.0.0.1:10808)─►  embedded libXray
                  ──TLS-handshake-mimicking-microsoft.com:443──►
                                               REALITY listener (Hetzner :8443)
                  ──freedom outbound + dns.hosts override──►
                                               Caddy container (:443)
                  ──HTTPS WebSocket──►          Rust relay container
  ```

### Why VLESS+REALITY (not other obfuscation)

Three alternatives were eliminated:

- **obfs4 / meek-azure / Snowflake** — all known-blocked on Russian
  carriers since 2024 (see Context).
- **Plain TLS to a non-flagged IP** — the only "non-flagged" residential
  IP we could realistically hold is on a consumer ISP, which has
  asymmetric uplink and unstable reverse-DNS. Operationally
  unworkable for a multi-user relay.
- **WireGuard / OpenVPN** — both have well-known UDP-pattern
  fingerprints and are blocked on Russian carriers. Even
  AmneziaWG (which intentionally randomises the WireGuard
  fingerprint) requires a separate user-installed app, which
  contradicts our "no third-party app" pitch.

REALITY is the only known protocol that survives the curtain because
it doesn't *hide* traffic — it presents traffic that **is, on the
wire, indistinguishable from a real Microsoft TLS handshake**. The
trick: the Xray server completes its REALITY-side handshake by live-
proxying packets to and from the real `www.microsoft.com:443` for
any client that doesn't carry the right pre-shared shortId+pubkey.
TSPU active-probing thus sees a genuine Microsoft cert chain on
every probe; the censor's classifier marks the IP as benign CDN.

### Why the Xray server lives on the same VPS as the relay (Path A)

Two server-side architectures were on the table when Stage 5E.B
landed in production:

- **Path A** — Xray server forwards unwrapped traffic to clearnet
  `relay.phntm.pro` on the same VPS, via a docker-bridge DNS hosts
  override. Single hop on the server side.
- **Path B** — Xray server forwards through a local Tor daemon to
  the relay's `.onion` address. Two hops on the server side.

Path A was chosen. Path B's defence-in-depth promise (the Hetzner
Xray operator can't correlate `client X → relay Y`) is moot because
the Hetzner Xray operator IS the relay operator. Path B costs
500-800 ms of additional latency per WebSocket frame and adds a
second daemon's failure modes for no defensive gain in our trust
model. Full reasoning in `docs/PROJECT_LOG.md` decision-log entry
"Rejected: server-side Tor outbound for Stage 5E (Path B)".

Path A's `dns.hosts` override deliberately uses the docker service
name (`caddy`) rather than the public IP, so the server-side
forward never depends on Linux NAT hairpin (which silently hangs
connections on some kernel versions).

### Why libXray gomobile (in-process) — not a separate Xray process

libXray ships Xray-core as a Go library compiled to a JNI `.so`
(via `gomobile bind`). PHANTOM's Android process loads it directly;
Xray's lifecycle is bound to our own foreground service. Same
in-process model as kmp-tor's `resource-noexec-tor` variant chosen
in ADR-016.

Two reasons:

- **Aggressive-OEM accounting.** Tecno HiOS, Infinix XOS, Xiaomi
  MIUI all use process-tree heuristics to decide which app gets
  killed first on memory pressure. A child process for Xray would
  show as orphaned the moment our JVM was paused, and would be
  killed independently. In-process means Xray dies cleanly with
  PHANTOM and restarts cleanly with PHANTOM; no orphan to confuse
  the OS, no second foreground notification.
- **Permission surface.** A separate Xray process binary would need
  its own SELinux label (or a permission grant the user has to
  click through). The in-process JNI .so loads from
  `nativeLibraryDir` which has no extra permission cost.

Build cost: `libXray.aar` is reproducibly built via
`.github/workflows/build-libxray.yml` (NDK r27c, Go 1.26.2). The
unpacked artefacts (`classes.jar` + 4 ABI `libgojni.so` ≈180 MB)
are vendored under `shared/core/xray/src/androidMain/{libs,jniLibs}/`
because AGP refuses to bundle a local `.aar` inside another AAR (the
`hasLocalAarDeps` check), and `:shared:core:xray` is itself an AAR.
Refresh procedure documented in `shared/core/xray/src/androidMain/libs/README.md`.

### Why a single shared client UUID (capability-style auth)

The VLESS UUID is shipped in the APK via `OperatorXrayConfig.kt`
and is therefore **public** by construction — every PHANTOM install
in the wild has the same UUID. This is intentional. Stage 5E's
purpose is **censorship circumvention, not access control**.

The threat we are guarding the Xray server against is
*open-proxy abuse* (someone using our server to tunnel arbitrary
traffic), not *unauthorised PHANTOM use* (which is meaningless —
PHANTOM is open-source and freely distributable). Open-proxy abuse
is closed by the routing rules in `deploy/xray/config.json.template`
which restrict VLESS clients to one destination (`relay.phntm.pro`)
and blackhole everything else. The UUID is just the per-install
capability that says "this client speaks VLESS to our server" —
not "this client is user X".

Per-user UUIDs would create a new metadata channel on the Xray
server (operator can correlate UUID with on-wire timing). For a
censorship-circumvention layer this is exactly the wrong direction.

Rotation playbook in `deploy/xray/render-config.sh` header — when
needed, generate a new keypair + UUID, update `.env` on VPS, update
`OperatorXrayConfig.kt` in the APK, ship a new release. Old clients
fail-closed at the REALITY handshake.

### License compatibility (MPL-2.0 + AGPL)

Xray-core is MPL-2.0 licensed. PHANTOM is AGPL-3.0-or-later. We
**do not** modify Xray source. The Xray server runs as an unmodified
container image (`ghcr.io/xtls/xray-core:latest`); the libXray
gomobile build is unmodified upstream. Aggregation at the
docker-compose / KMP-module level is permitted by both licenses
(MPL §3.2 / §3.3, AGPL §0 — "modify" definitions). No source-file-
level mixing of MPL and AGPL code occurs.

This is the same posture v2rayNG and Hiddify (both MPL-Xray + GPL
client) ship with — well-trodden ground.

If we ever fork Xray-core and modify upstream files, the license
analysis changes (MPL-2.0 source modifications must remain MPL-2.0,
which would propagate per-file). At that point we re-open this
decision in a follow-up ADR.

## Threat model consequences

What Stage 5E **adds** to the threat model:

- A second operator-controlled production component (Xray container
  on the relay VPS). Compromise of the VPS now leaks REALITY
  private keys *in addition to* relay state. Mitigation: keys are
  cheap to rotate (one APK release), and in the worst case the
  curtain returns to its baseline behaviour but PHANTOM still works
  for users without TSPU on path.
- The Xray container's docker logs would default-leak per-connection
  client IPs to the VPS disk image. **Closed by `access: none` in
  the log block** — logs only carry startup messages and unrecoverable
  errors, never client identifying data.
- The shared UUID means a leaked APK gives an outsider VLESS access
  to our server. Closed by the routing rules — the only thing they
  can target is `relay.phntm.pro`, where the relay enforces its own
  per-identity authentication. Net effect: leaked UUID + leaked APK
  is functionally identical to a normal honest user installing
  PHANTOM.

What Stage 5E **does not change**:

- The Double Ratchet end-to-end encryption layer is unchanged. The
  outer transport sees only ciphertext envelopes. Compromise of
  the Xray server reveals zero plaintext message content.
- Sealed Sender envelope is unchanged. The Xray server cannot read
  sender identity from envelopes.
- The relay's existing per-identity capability checks
  (`token` parameter on `/ws`, prekey-bundle binding, etc.) are
  enforced inside the WebSocket frames the Xray server forwards.
  Xray sees opaque TCP bytes after the inner TLS handshake to
  Caddy completes.

## Known limitations

Documented honestly here so an external reviewer (auditor, future
contributor, security researcher) does not have to discover these
by reading the code:

- **Single point of failure.** All Stage 5E clients reach the same
  Hetzner VPS. If that IP is added to TSPU's active block-list
  (separate from the curtain throttle), no PHANTOM client can reach
  the relay through the Xray path until the IP changes. ADR-021
  (planned, Beta-tier) addresses this with a multi-server
  operator-controlled fan-out and a client-side server-discovery
  mechanism.
- **Adaptive transport selection is manual.** Stage 5E.B ships with
  a build-time `USE_XRAY` flag, mutually exclusive with `USE_TOR`.
  A user on a network where direct WSS works gets no benefit from
  Xray (it just adds CPU + battery cost), and a user on a network
  where Xray is blocked has no automatic fall-back to Tor. ADR-020
  (planned, Beta-tier) introduces a runtime probe + state machine
  that selects the working transport per connection. The current
  static flag is a pragmatic interim — the censorship-resistance
  primitive needed to ship as soon as it was production-validated
  on a Russian carrier rather than waiting on the runtime probe
  layer.
- **VPN co-existence is competitive, not additive.** When a user
  has a VPN active on the device (or on the host running an
  emulator), the device's TUN interface captures all TCP and routes
  it through the VPN tunnel. The local libXray then either fails
  the REALITY handshake (because the VPN's exit node terminates and
  re-establishes TLS) or succeeds but with REALITY's whole purpose
  defeated (the censor never sees our traffic — the VPN does).
  PHANTOM still works in that scenario via the user's VPN, but Stage
  5E becomes a no-op. This is documented behaviour, not a bug —
  the user's VPN already does what Stage 5E exists to do.
- **REALITY's cover host is currently a hard pin.** We cover-mimic
  `www.microsoft.com:443`. If Microsoft starts blocking RU access
  to that endpoint (sanctions scenario), the Hetzner Xray server's
  active-probe response would fail and the IP would get reclassified
  by TSPU. Mitigation: the cover host is a server-side template
  variable; switching to another well-known TLS endpoint is a
  config-change-and-restart operation, no APK release required.
- **iOS support is post-Alpha-2.** libXray's gomobile output
  produces `.aar` (Android only); the iOS equivalent (`.xcframework`
  via `gomobile bind --target=ios`) compiles only on macOS and is
  blocked on the same Apple-toolchain dependency that blocks the
  rest of `:shared:core:*` iOS targets from Windows-based builds.
  Tracked under ADR-014 (iOS port roadmap).

## Implementation plan — already shipped (Stage 5E.B.1-5)

| Stage | What | Commit |
|-------|------|--------|
| 5E.A  | Server-side Xray VLESS+REALITY listener (Hetzner :8443) | `be1ecad5` + 4 follow-up fixes |
| 5E.B.1 | GitHub Actions workflow that builds `libXray.aar` reproducibly | `5cca2976`, `f5e21fb3` |
| 5E.B.2/3 | KMP module `:shared:core:xray` + libXray vendoring | `96fcbf1a` |
| 5E.B.4 | Wire `XrayService` into `PhantomMessagingService`; `USE_XRAY` build flag with build-time mutex against `USE_TOR` | `98245f69` |
| 5E.B.5 | Diagnostic + production routing fixes on the Hetzner Xray server | `7b4ebf77`, `d7ba3a41` |

## Operator runbook references

- `deploy/xray/README.md` — operator-side setup (key generation,
  config render, container start)
- `deploy/xray/render-config.sh` — config render script header
  carries the rotation playbook
- `deploy/xray/config.json.template` — server config with extensive
  inline comments explaining each routing rule and DNS-hosts entry
- `shared/core/xray/src/androidMain/libs/README.md` — refresh
  procedure for the vendored libXray artefacts
- `shared/core/xray/src/androidMain/kotlin/phantom/core/xray/OperatorXrayConfig.kt`
  — pinned production endpoint (server IP, port, REALITY public key,
  cover SNI, capability UUID)

## Test plan

Production validation already done — Test 14 (2026-05-07):

- Tecno on RU MTS without VPN, no Orbot, no proxy app
- Twin device: Pixel 8 Pro emulator on dev host
- Text envelopes (~1 KB) — round-trip in 30-100 ms each direction
- Voice messages chunked to ~55 KB envelopes (well over the 16 KB
  curtain threshold) — five chunks per 5-second voice, all delivered,
  decrypt OK on receive

Per-Caddy-log verification for routing through Xray (not direct):
`docker logs phantom-caddy --since 10m | grep '"host":"relay.phntm.pro"'`
should show `remote_ip` from the docker bridge range (`172.x.x.x`),
not from the client's public IP. See
`memory/reference_xray_diagnostics.md` for the full diagnostic
recipe — Xray's own access log is intentionally disabled
(`access: none`) for privacy hygiene, so Caddy logs are the canonical
source for confirming connections go through Xray.

## Future work (Phase 2 deliverables)

- **ADR-020: Adaptive Transport Selection.** Runtime probe-and-pick
  state machine across direct WSS, Xray, Tor. Removes the build-time
  flag; UI surfaces current transport in the foreground notification.
  Estimated 2 weeks single-developer.
- **ADR-021: Multi-server Operator Fan-out for Stage 5E.** Two or
  three operator-controlled Xray servers in different jurisdictions
  / ASNs. Client-side rotation between them, fall-over on failure.
  Closes the single-point-of-failure limitation. Estimated 1 week
  single-developer once the second VPS is acquired.
- **ADR-022: iOS XCFramework for `:shared:core:xray`** (parallel
  with broader iOS port). Required for full multiplatform parity.
- **Xray access log temporary opt-in for incident response.** When
  on-call needs to debug a routing issue, an operator command flips
  `access` from `none` to `stdout` in the rendered config, restarts
  the container, captures one diagnostic window, then flips back.
  Procedure documented in `deploy/xray/README.md` (TODO).

## References

- `ADR-016-tor-unified-push-hybrid-transport.md` — original two-channel
  transport architecture; Stage 5E adds a third option in the
  "data plane" column
- `docs/PROJECT_LOG.md` 2026-05-07 session entry — Stage 5E.B
  implementation diary
- `docs/PROJECT_LOG.md` decision-log entry "Rejected: server-side
  Tor outbound for Stage 5E (Path B)"
- `memory/project_stage5e_xray_success_2026_05_07.md` — operator
  notes capturing production validation
- XTLS REALITY documentation: <https://xtls.github.io/en/config/transports/reality.html>
- libXray repository: <https://github.com/XTLS/libXray>
- Stage 5C/5D failure analysis: `memory/project_tspu_16kb_curtain_2026_05_06.md`
