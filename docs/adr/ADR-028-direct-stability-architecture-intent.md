# ADR-028: Direct WebSocket Stability — Architecture Intent

**Status:** Accepted
**Date:** 2026-06-03
**Deciders:** Vladislav Liudvig (solo author)
**Related:** [ADR-003](ADR-003-Transport-Abstraction.md) (Transport Abstraction), [ADR-010](ADR-010-transport-reconnect-deadlock.md) (Transport reconnect lifecycle), [ADR-011](ADR-011-alarm-manager-network-wakeup.md) (Network-change wakeups), [ADR-020](ADR-020-Adaptive-Transport-Selection.md) (Adaptive Transport Selection), [ADR-025](ADR-025-Call-Signaling-E2EE.md) (Call Signaling E2EE), [ADR-027](ADR-027-Per-User-Signed-Challenge-Auth.md) (Per-User Signed Challenge Auth)
**Closes:** Strategic pivot 2026-06-03 — primary fix track for Direct WebSocket stability is the actual fix work, not the UX-shield (3.2b.1).

---

## Context

RC-DIRECT-WS-DEATH1 Phase 1+2 closed on 2026-06-03 (PR #272) with two empirically established facts:

1. **Direct WebSocket on Tecno against `relay.phntm.pro` is unreliable on both Wi-Fi and Tele2 LTE.** Mode 1 (Wi-Fi 8-pong rhythm, ~150 s lifetime) and Mode 2 (Tele2 LTE 0-1-pong rhythm, ~30-45 s lifetime) both reproduce on raw OkHttp `newWebSocket(...)` — Ktor is not the proximal cause.
2. **The kill is below app/Ktor.** For all conclusively-classified deaths, the relay-side `ws_protocol_pong_sent` log shows the Pong was sent, but the device pcap shows zero inbound TLS records around the expected anchor. This is return-path loss at the TCP/TLS path layer, not OkHttp internal mis-counting. (The Mode 2 pp=1 sub-case has a TCP-layer ambiguity around relay-side delivery-stall vs uplink loss; see RC-DIRECT-WS-DEATH1 §32(c).)

After Phase 2, the natural next move was to design 3.2b.1 (adaptive validation = switch chain when Direct WS bad). I rejected that as the primary work. **3.2b.1 is a UX safety net, not a fix.** The product target is a robust messenger, not a messenger that gracefully tells users "Limited realtime" when Direct WS fails. The primary work is to make Direct WS hold reliably; 3.2b.1 stays as a parallel safety layer for the residual case where physical-network unreliability cannot be eliminated client-side.

But promising "Direct WS always works" is unhonest. Mobile networks, DPI, NAT, CGNAT, carrier middleboxes, radio sleep, and operator-side packet loss can break long-lived WebSocket connections in ways no client-side change fixes. Mature messaging products handle this differently: Direct is fixed as much as possible, but the user never perceives a degradation, because there is a complete fallback architecture underneath.

This ADR fixes that fallback architecture as an explicit, durable decision — so future tracks (RC-DIRECT-STABILITY1 fix attempts, 3.2b.1 safety net, future voice / calls / privacy decisions) reference one common contract rather than rediscovering it each time.

## Decision

PHANTOM commits to a four-layer reliability architecture for every realtime / transport decision going forward:

### Layer 1 — Messages: REST store-and-forward, always available

User-to-user text messages, sealed-sender envelopes, ack receipts, and queue draining all transit the relay's REST endpoints (`/send`, `/poll`, `/relay/poll`) whenever Direct WS is unavailable or degraded. REST is the floor: as long as the network can carry HTTP, messages can flow.

This does not change. ADR-003 + ADR-020 already establish REST fallback as a first-class transport. This ADR re-affirms that property at the architecture level: **REST is the source of truth for message delivery; Direct WS is an optimisation on top of it.**

### Layer 2 — Realtime signaling: Direct WS / SSE / alternative path with graceful fallback

For realtime push notification of new messages, presence, typing indicators, and any other "tell the client now" signal, Direct WS is the preferred path. When Direct WS fails — for the network class, for the device, or for unforeseen reasons — alternative realtime paths take over:

- HTTP long-poll (already implemented as `/relay/poll`)
- Server-Sent Events (SSE) — future track if Direct WS is structurally unstable
- Any other future realtime carrier validated for the network class

Graceful fallback means: when Direct WS is degraded, the alternative carries the same signal stream, and the user's experience is "things still update in realtime" rather than "things stopped updating." The 3.2b.1 adaptive validation work (when it lands) is the policy layer for "which realtime carrier is active right now" — the implementation contract is that any chosen carrier delivers the same signal semantics.

### Layer 3 — Voice notes (asynchronous voice messages): REST media transport

Voice notes are uploaded and downloaded over REST media endpoints. This is **not** a WS data-plane feature. This was already established as the right approach in the D2b pivot 2026-05-17 (PR #166 parked) — voice transit over WS proved infeasible on Tele2 LTE because the per-chunk roundtrip cost grew faster than the audio duration could absorb. The M2 trilogy (M2d.1a → M2d.1b → M2e → M2f.2) shipped REST-based voice with byte-based early manifest, parallel-pooled downloads, and a 30-sec voice note transit time of ~50 s on Tele2 LTE.

This ADR locks in the principle: **voice transport is REST, not WS, regardless of how Direct WS stability work progresses.** No future track may propose moving voice back to WS.

### Layer 4 — Calls: WebRTC + TURN relay, not app-level WebSocket

Real-time calls (audio / video) use WebRTC for the media plane and a TURN relay for NAT traversal. App-level WebSocket is **not** a media carrier for calls. Signaling for calls (SDP offer / answer / ICE candidates / hangup) goes through the same ADR-025 sealed-sender pipeline that protects messages, but the media itself is end-to-end WebRTC over TURN.

This ADR locks in this principle as the foundation for future call work. Any future track that proposes moving call media to WS-tunneled streams is rejected by this ADR.

### Privacy / resilience overlays: Tor and Reality / Xray

Tor (via `kmp-tor`) and Xray Reality outer transport (per ADR-019 / ADR-020) are **privacy and censorship-resilience overlays**, not the media foundation. They wrap the same Layer 1-4 contracts above and do not replace them. A user in a censored region uses Tor or Reality to reach the relay, but the relay still serves REST for messages, WS for realtime signaling, REST media for voice notes, and WebRTC for calls.

This ADR does not change ADR-020's runtime outer-transport selector or ADR-019's Reality implementation. It clarifies the architectural role: overlays are envelopes, not foundations.

## Consequences

### Positive

- **Direct stability work has a defined ceiling.** RC-DIRECT-STABILITY1 attempts fix candidates; if none meets the §6 ship criterion, the verdict is "Direct WS is structurally unreliable on this network class, alternative realtime path opens, 3.2b.1 unfreezes as safety net." The architecture has a fallback — the work is bounded.
- **3.2b.1 has a defined role.** Not the primary fix; not vestigial. It is the policy layer that selects which Layer-2 realtime carrier is active when Direct WS is degraded.
- **Voice and calls are protected from scope creep.** No future track may propose voice-over-WS or call-media-over-WS; the ADR closes those paths.
- **Architecture intent is portable across tracks.** Every future stability / transport / privacy decision references this ADR for the layer model, rather than re-deriving it.

### Negative

- **Alternative-realtime implementation cost is on the future track.** SSE or long-poll as a primary realtime carrier (when Direct WS fails) requires relay-side changes (new route, new connection-tracking state, auth-surface adjustment). The cost of that work is acknowledged here but not paid here.
- **Calls media plane work is gated.** WebRTC + TURN is the locked direction, but the actual implementation is a separate substantial track (TURN server selection / hosting / cost; WebRTC peer-connection in commonMain; encrypted media key derivation). This ADR fixes the direction but does not pay the cost.
- **Layered fallback is not free for the user.** Users on degraded networks see slower updates (long-poll cadence vs WS push). This is an honest trade-off that the architecture accepts rather than hides.

### Neutral but worth flagging

- **The Mode 2 pp=1 TCP-layer ambiguity remains parked.** ADR-028 does not require us to discriminate "uplink loss vs relay-side TLS/WS delivery stall" — the layered architecture works either way. If product decisions later need that discrimination, the parked server-side BPF work in RC-DIRECT-WS-DEATH1 §38 can be unparked.
- **Privacy overlays (Tor / Reality) inherit reliability properties from the wrapped layer.** A user on Tor whose Direct WS fails sees the same Layer-2 fallback to long-poll / SSE as a clearnet user. This is the right behaviour but means censored users feel the fallback latency on top of the overlay latency.

## Security baseline for plain-WS diagnostic paths

During RC-DIRECT-STABILITY1 Arm A diagnostic experiments, the relay's WS endpoint is reached via a temporary loopback-only `ws://127.0.0.1:8081/ws` binding from the dev machine through an SSH tunnel. This **plain-WS path is safe** only because:

1. **Auth is integrity-based, not confidentiality-based.** ADR-027's signed-challenge protocol uses Ed25519 over a one-shot nonce; the relay consumes the nonce before signature verification. An observer of the plain-HTTP `/auth/challenge` GET or the plain-WS upgrade URL sees `nonce_hex` and `signature_hex` but cannot forge new credentials without the Ed25519 private key, and cannot replay because the nonce is gone.
2. **Loopback binding is enforced by CI.** `Inv-BypassIsLoopbackOnly` is checked by `.github/workflows/deploy-lint.yml` (shipped in PR #273); any compose-file change that binds the relay to a non-loopback host_ip fails the merge check.
3. **The diagnostic APK build flag is double-gated.** `BuildConfig.DEBUG_BYPASS_URL` is pinned to `""` in the release block of `apps/android/build.gradle.kts`, and the wire-up site checks `BuildConfig.DEBUG && BuildConfig.DEBUG_BYPASS_URL.isNotEmpty()` before reading the field. A leaked debug APK can never connect a plain-WS endpoint in a release build.
4. **The window is time-boxed.** Arm A experiments require explicit operator setup (compose edit + container restart + SSH tunnel) and explicit teardown. No persistent plain-WS path is left active outside a measured field test.

This security baseline does **not** authorise plain-WS as a production transport. The production transport remains `wss://relay.phntm.pro/ws` via Caddy. The baseline exists only to make short-window diagnostic experiments safe under the F-AuthIntegrity property of ADR-027.

## Out of scope for this ADR

- The specific Arm A-F fix-candidate evaluations are scoped in `docs/tracks/rc-direct-stability1.md` §4.
- Per-arm code design (e.g. `RcDirectArmA.kt` class shape) is in the per-arm PRs that follow this mini-lock.
- 3.2b.1 implementation design (when it unparks) lives on the WS-HEALTH-STATE1 track.
- Future SSE / long-poll alternative-transport design is a separate relay-track mini-lock (parking-lot per RC-DIRECT-STABILITY1 §9).
- Future WebRTC + TURN calls implementation is a separate calls-track mini-lock.

## Notes

- This ADR does not supersede any existing ADR. It clarifies the architectural role of each transport layer and locks the direction for future tracks. ADR-003 / ADR-020 / ADR-025 / ADR-027 remain unchanged and are referenced as Related above.
- The four-layer model deliberately matches the empirical evidence from RC-DIRECT-WS-DEATH1: Layer 1 (REST messages) is what survived Mode 1 and Mode 2 deaths during Phase 2 captures; Layer 2 (realtime signaling) is what failed; Layer 3 (voice) is what already moved off WS during the D2b pivot 2026-05-17; Layer 4 (calls) is what has not yet been built and now has its direction locked.
- This ADR is the durable architecture intent that justifies why RC-DIRECT-STABILITY1 is the primary fix track and 3.2b.1 is the safety net.
