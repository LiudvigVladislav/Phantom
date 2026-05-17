# PHANTOM — Project Log

> Internal development journal. Captures **state, decisions, and history**
> in one place so any contributor — or any future Claude session that
> walks in cold — can pick up exactly where the previous one left off.
>
> **Maintenance rule.** At the end of every significant working session,
> append a new entry to the [Session journal](#session-journal) with the
> date, goal, what shipped, what was rejected and why. Keep entries
> reverse-chronological so the freshest context is at the top.
>
> User-facing release artefacts (`README.md`, `RELEASE_NOTES.md`, `KNOWN_ISSUES.md`) live at the repo root;
> formal architectural decisions live in [`docs/adr/`](adr/); this log is
> the in-between layer that ties them together with the human story of
> "why are we doing what we're doing right now".

---

## Current state

**Released:** `v0.1.0-alpha.1` (tag → commit `0246b50f`, GitHub Release published)
**Branch state:** `master` is the only active branch; all Alpha 1 feature
branches merged. Latest commit at the time of writing: `0bc715e1`.
**Production:** `relay.phntm.pro` running master, `phntm.pro/terms` and
`/privacy` serving themed HTML in EN + RU.
**Licensing:** AGPL-3.0-or-later established as the formal project
licence (LICENSE + NOTICE + SPDX headers on every .kt and .rs file +
README §License — all shipped in the four-commit licence-hygiene
patch on 2026-04-27).

### What works (proven in QA-v10 / 2026-04-27)

- ✅ End-to-end encrypted 1:1 messaging (libsignal + libsodium, Double Ratchet, Sealed Sender)
- ✅ Trust Tier flow (first message → Message Requests, accept → Trusted)
- ✅ Production relay with store-and-forward at `wss://relay.phntm.pro/ws`
- ✅ QR code + `phantom://invite/<base64>` deep links
- ✅ Crypto integration test suite green (16/16 on Pixel 8 Pro / API 35)
- ✅ Foreground-service WebSocket with WifiLock + partial WakeLock
- ✅ Onboarding ToS in EN, full Terms / Privacy Policy hosted at `/terms` and `/privacy` with EN ⇄ RU switcher
- ✅ Settings → About with all 6 contact addresses (security/support/privacy/legal/abuse/press)
- ✅ Chat input bar inset handling fixed across Tecno HiOS and stock Android (root cause: `enableEdgeToEdge()` instead of `setDecorFitsSystemWindows`)

### Known limitations (documented)

See [`KNOWN_ISSUES.md`](../KNOWN_ISSUES.md) for the full
register. Headline items:

- ⚠️ **ISSUE-001** Tecno HiOS / aggressive-OEM Android skins reconnect every ~60 s due to OS-level Wi-Fi radio parking. **Mitigated** to 1–3 s message-delivery latency through WakeLock, OkHttp `pingInterval(8s)`, `forceCancelAllEngineCalls`, `cancelAndJoin`. **Real fix** scheduled for Alpha 2 via Unified Push (FOSS, no Google).
- No iOS / desktop / web client.
- No voice/video calls (scaffolding present, signalling not wired into UI).
- No attachments.
- No groups (state machine + Sender Keys crypto present, not on the Alpha 1 surface).
- No Tor / BLE / Wi-Fi Direct transport.

---

## Active backlog

The four GitHub Issues drafted in
[`docs/alpha2-backlog.md`](alpha2-backlog.md)
are the Alpha 2 priority queue:

1. **Onboarding ToS review** — final wording approval after lawyer / GDPR review.
2. **Per-message status indicators** (Sending / Sent / Delivered / Read) — wire signals already exist on the relay path; UI bug is purely client-side.
3. **Encrypted profile sync** (Alpha 2 protocol slice) — first/last name and bio visible to contacts; new `ProfileUpdate` envelope type.
4. **Locale-aware date-of-birth picker** — replace plain text field with proper `DatePickerDialog`, locale-aware display formats.

Also tracked:

- ⏳ Legal review by qualified attorney (Wyoming / US privacy law)
- ⏳ GDPR review for EU compliance (Art. 13/14 disclosures)
- ⏳ Russian translation review for legal-terminology accuracy
- ✅ ~~`LICENSE` file at repo root matching the AGPL-3.0 intent of [ADR-006](adr/ADR-006-Crypto-Library-Decision.md)~~ shipped 2026-04-27 (`e99aac0e` + `c403b7cf` + `27cd9bd1` + `0bc715e1`)
- ⏳ External funding programme submission (target window 2026-06-01)
- ⏳ Locale-aware in-app linking from Onboarding to `/terms/ru` / `/privacy/ru` based on `Locale.getDefault()` (currently always English)

---

## Decision log

This section records **rejected** options as well as accepted ones —
otherwise we would re-litigate the same conversations every few weeks.

### Accepted: SHA-1 application restriction for Firebase API key (instead of full key regeneration)

**Date:** 2026-05-08
**Context:** Phase 1 repo cleanup (commit `f07bba8c` + follow-up
`9b0581dd`) removed `apps/android/google-services.json` from git
tracking, but the live Firebase API key shipped inside that file was
publicly visible in every commit between project inception and the
cleanup PR. Two paths to mitigate:

- **A.** Restrict the existing key by Android package name + signing
  certificate SHA-1 fingerprints (debug + release). The leaked key
  remains usable, but only from APKs signed with our keystores. Other
  callers see `403 API_KEY_RESTRICTED`.
- **B.** Regenerate the key entirely. The leaked one is fully
  invalidated; clients with the old key (i.e. every existing PHANTOM
  install) lose FCM push until they receive an updated APK with a fresh
  `google-services.json`.

**Decision:** Path A (restrict, do not regenerate).

**Why:**

- Firebase keys are publishable by design — the Android SDK literally
  bundles them into every APK that ships, so they are extractable from
  any release with `apksigner` + a hex editor. The threat is not
  secrecy, it is *abuse*: someone using our key to exhaust our FCM
  quota and DoS our push channel. Application restriction
  (Android-package + signing-cert SHA-1) is the canonical mitigation
  Google itself recommends.
- Path B's blast radius hits real users: every install in the field
  loses push notifications until they update. Pre-Alpha-2 user count
  is small but non-zero, and the disruption is asymmetric to the
  threat (we have no evidence the key was actually scraped).
- Restoring the security posture is now reversible — if we later see
  abuse on the leaked key, we can still execute Path B (regenerate)
  without losing any of today's restriction work.

**Implementation:**

- Generated SHA-1 fingerprints from both keystores via `keytool -list -v`:
    - Debug (`~/.android/debug.keystore`):
      `B2:9E:30:6F:1F:77:83:AB:83:76:E3:EE:DB:AA:D2:AE:98:6D:74:5A`
    - Release (`keystores/phantom-release.keystore`):
      `A3:2B:94:CA:3A:85:C6:A1:CF:0E:8F:48:A2:86:C5:D9:64:AE:61:D9`
- Added both as Android-app application restrictions on the Firebase
  auto-created Android API key in Google Cloud Console
  (`console.cloud.google.com/apis/credentials?project=phantom-app-a1ca0`).
- Also added the matching SHA-256 fingerprints to the Firebase Console
  app registration page (defence in depth — Firebase Auth, Dynamic
  Links, App Check use these for client-attestation independently of
  the API-key restriction above).

**Follow-up:** if `gradle :apps:android:signingReport` ever shows a
new SHA-1 fingerprint (e.g. release keystore rotated, additional CI
signing key introduced), it must be added to both the Google Cloud
Console restriction and the Firebase Console app registration *before*
distributing the new APK. Otherwise that build will hit
`403 API_KEY_RESTRICTED` on first FCM register.

### Rejected: server-side Tor outbound for Stage 5E (Path B)

**Date:** 2026-05-07
**Context:** Stage 5E's Hetzner Xray VLESS+REALITY server needs to forward
the unwrapped client traffic somewhere. Two architectures were on the
table — Path A (forward straight to clearnet `relay.phntm.pro` on the
same VPS via docker bridge DNS) and Path B (run a local Tor inside the
Xray container, route through a `.onion` to the relay).
**Why rejected:**

- Path B adds 500–800 ms per WebSocket frame on top of the REALITY
  handshake — unacceptable for messenger UX.
- The defence-in-depth Path B promises (Hetzner Xray operator can't
  correlate `client X → relay Y`) is moot because the Hetzner Xray
  operator IS the relay operator. Same VPS, same hand on the keyboard.
- Adds another daemon (Tor) to the production stack — more attack
  surface, more maintenance, more failure modes for the on-call rotation.

**Replacement:** Path A — `dns.hosts: {"relay.phntm.pro": "caddy"}` in
the Xray server config sends VLESS-tunnelled traffic straight to the
Caddy container over the docker bridge network. Zero NAT hairpin, zero
extra latency. Acceptable trade because external observers see only the
REALITY-mimicked Microsoft TLS handshake; the relay-operator-as-attacker
threat is out of scope for this server (it's defended at the Double
Ratchet layer instead — relay sees only ciphertext).

**See:** session entry for 2026-05-07 (Stage 5E production-validated).

### Rejected: FCM (Firebase Cloud Messaging) for push-on-disconnect

**Date:** 2026-04-27
**Context:** Tecno HiOS reconnects every ~60 s. FCM would let the relay
push silent data wakes to the device when the WS dies, eliminating
client-side reconnect cost.
**Why rejected:**

- FCM token = Google-issued stable trackable identifier per install. Adding
  it to PHANTOM puts a Google-known identifier on every account, which
  contradicts ADR-001 (zero-third-party-metadata) and the entire
  "we cannot disclose what we do not have" posture in the Privacy Policy.
- Each push = metadata event Google sees: "device X received PHANTOM message at time T".
- Public-interest funding programmes that align with this project
  specifically fund privacy/freedom tech. A Google dependency in the
  core delivery path damages that narrative.
- Self-hosters of the relay would be forced to provision a Firebase project.

**Replacement plan:** **Unified Push** (FOSS protocol, ntfy.sh / gotify
distributors, no central provider). Scheduled for Alpha 2.

**See:** session entry for 2026-04-27 (FCM scaffold removed in
uncommitted local work; never reached master).

### Rejected: server-driven heartbeat Text frame (commit `22a76c22` reverted in `b2447dde`)

**Date:** 2026-04-27
**Context:** Tried to keep cellular DPI / firewalls happy by having the
relay send `{"type":"heartbeat","ts":<unix>}` every 30 s as visible
application traffic.
**Why reverted:**

- Did not fix the Tecno-HiOS case (carrier DPI was never the cause —
  see "OEM Wi-Fi radio parking" finding below).
- Introduced a regression on the previously stable Pixel emulator: with
  the heartbeat branch in `tokio::select!` interleaving with
  `socket.next()` / `socket.send()`, the emulator's WebSocket started
  dropping after ~3 minutes (37 successful pings then a
  SocketTimeoutException). Almost certainly a subtle interaction with how
  the actor drains pending writes during heavy bidirectional traffic.

**Lesson:** when one mitigation does not work, **revert it and re-test**
before stacking another mitigation on top. Two mitigations interacting
hide root causes.

### Accepted: input-clipping root fix is `enableEdgeToEdge()`, not `setDecorFitsSystemWindows`

**Date:** 2026-04-27
**Context:** Earlier the chat input slid under the 3-button nav bar on
Tecno HiOS. Two attempts to fix it via Compose `Modifier.windowInsetsPadding`
both failed.
**Root cause:** `WindowCompat.setDecorFitsSystemWindows(window, false)`
turns on edge-to-edge **drawing** but does not install the
`OnApplyWindowInsetsListener` Compose needs for `WindowInsets.ime` /
`WindowInsets.navigationBars` to be populated. The Pixel emulator
installed that listener as a side effect of other init paths so things
worked by accident. Tecno HiOS does not, so every
`Modifier.windowInsetsPadding(...)` resolved to zero.
**Fix:** replaced with `enableEdgeToEdge()` (the AndroidX-managed wrapper
that combines the decor-fits flag with the inset listener Compose
expects), guarded to API < 35 because on API 35+ the system enforces it
and re-calling corrupts the EGL surface.
**See:** commit `d2320b95`.

### Accepted: `socket.split()` was the relay's PONG-flush bug

**Date:** 2026-04-26
**Context:** Even after fixing the client-side OkHttp graceful-close
60-second hang (`b14b39ff` → `2ee1d08d`), the phone was still dropping
its WebSocket on a deterministic 60-second cycle.
**Root cause:** Ktor's `OkHttpWebsocketSession.outgoing` is an actor
whose internal `WebSocket` is a local variable, not a field. The actor's
finally block calls `websocket.close()` (graceful) which blocks for
OkHttp's hardcoded `CANCEL_AFTER_CLOSE_MILLIS = 60_000L`. On the relay
side, splitting the WebSocket into `(Sink, Stream)` halves means the
auto-PONG enqueued by tungstenite when a PING arrives only flushes
on the next *write* through the Sink half — which never happens during
idle.
**Fix:** replaced relay's `socket.split()` + two spawned tasks with a
single `tokio::select!` loop that reads frames AND writes frames from
the same future, with explicit `socket.send(Message::Pong(payload))` on
every received PING.
**See:** commit `dbd9393c`. This was the breakthrough.

### Accepted: `Releases/` folder pattern for canonical release artefacts

**Date:** 2026-04-27
**Files:**
- `Releases/README.md` — public-facing project README
- `Releases/RELEASE_NOTES.md` — what's in the release
- `Releases/KNOWN_ISSUES.md` — limitations register
- `Releases/GITHUB_RELEASE_BODY.md` — template for `gh release` body
- `Releases/GITHUB_ISSUES_DRAFT.md` — Alpha 2 issue bodies ready to paste

**Why a separate folder:** the root `README.md` is the internal "Claude
Code + VS Code Pack" guide that the founder uses to bootstrap their
local agent setup. Replacing it with the product README would break the
founder's tooling. `Releases/` keeps both worlds clean.

> **Superseded 2026-04-28.** The `Releases/` folder was retired. Public
> artefacts moved to repo-root standard locations (`README.md`,
> `RELEASE_NOTES.md`, `KNOWN_ISSUES.md`); the GitHub Release-body
> template and the issues-draft moved into `docs/release-drafts/` and
> `docs/alpha2-backlog.md` respectively. The Claude Code + VS Code Pack
> guide that used to occupy the root `README.md` was deleted (no longer
> needed in the public repo — it described the founder's local agent
> tooling, not the product). `Releases/` was also stripped from git
> history via `git-filter-repo` so the public repo presents a clean
> tree.

---

## Lessons learned

Technical findings worth remembering across sessions.

### Compose insets on OEM Android skins

`Modifier.windowInsetsPadding(WindowInsets.ime ⋃ WindowInsets.navigationBars)`
is the right way to pad a screen for both keyboard and system nav bar:
the `union` gives the per-side max so they never stack. **But** the
modifier resolves to zero unless the Activity has called
`enableEdgeToEdge()` (or the older deprecated equivalent). Without that,
inset values are not propagated to Compose by some OEM Android builds —
the Pixel emulator hides this by installing the listener through other
code paths.

### Ktor 3.0.3 OkHttp engine close behaviour

- `session.close()` triggers OkHttp's graceful-close path: send WS CLOSE
  frame, wait up to 60 s for the peer's matching CLOSE before tearing
  down the socket. This is the source of the QA-v3 60-second-hang
  pattern.
- `session.cancel()` is closer to right, but the OkHttp actor's
  `finally { websocket.close(...) }` still runs and blocks.
- The reliable escape hatch is to call `OkHttpClient.dispatcher.cancelAll()`
  on the *engine* — that fires `onFailure`, the actor's finally then
  sees a failed websocket and returns instantly. We expose this through
  an `expect/actual` `forceCancelAllEngineCalls()` plumbed into
  `KtorRelayTransport`.

### Stale coroutines across reconnect generations

When a generation's `pingJob` lives in a `transportScope` that the
`webSocket {}` block fails to cancel via the normal exit path (e.g.
exception path), the next generation's session can be force-cancelled
by the *previous* generation's pingJob firing late. Symptom: cascades of
"Pong timeout" log lines a few seconds after a successful reconnect.
**Pattern:** hoist the per-generation scope out of the `webSocket {}`
block and `cancelAndJoin` it in a `finally` — `cancel()` alone is
asynchronous and lets stale jobs race. Capture the session at launch
time into a local `val` so a stale job cannot read the *new* session
out of the field. Both fixes shipped in `846d6bed`.

### Caddy bind-mount semantics

`docker compose restart` does **not** pick up `volume:` changes. Only
`docker compose up -d --force-recreate` recreates the container with
the new mount config. Caddyfile changes alone (no volume change) do
get picked up by `restart` because the file is bind-mounted and Caddy
reloads on container start.

### Caddy `handle_path` matcher ordering

`handle_path /terms/ru*` MUST appear **before** `handle_path /terms*` —
Caddy matches in source order and the generic block would otherwise
swallow the localised path before the specific block sees it. Same for
`/privacy/ru*` vs `/privacy*`.

### Tecno HiOS aggressive power management

Even with a foreground service notification visible AND a partial
WakeLock AND a high-perf WifiLock, Tecno HiOS still drops the
WebSocket every ~60 s. Per the user's testing, no amount of in-app
mitigation fully fixes this — only push-on-disconnect (Alpha 2) will.
Documented in KNOWN_ISSUES.md as ISSUE-001 with a Tecno-specific
workaround section (Battery → Unrestricted, etc.).

### Don't trust hallucinated git history from a separate Claude

A consultant Claude session repeatedly claimed fixes existed on
specific branches that did not (`Update Dockerfile for Rust 1.88` etc.).
Always verify with `git log --all --oneline -- <path>` before acting
on advice that references prior commits — even from another LLM.

---

## Open follow-ups / unfinished items

> **Maintenance rule.** Any session that ships a PR or makes a non-trivial
> decision must check this list — append items that are deferred, mark
> items that landed, and avoid duplicating across the Session journal.
> This is the durable "what's not done" snapshot — Session journal entries
> tell the story, this list answers "what's still on the queue".

### Reliability / transport (D-track, post-D1d)

- **✅ PR-D2a — Voice / call UI + send-layer guard for Limited realtime.**
  Merged 2026-05-17 night as PR #162 (master `210b827f`). Two-layer
  guard: UI in `ChatScreen.onMicClick` + `onVoiceCall` (Snackbar
  refusal at gesture start + `MEDIA_CAPABILITY blocked
  feature=voice|call mode=<state> source=ui` log), send layer in
  `DefaultMessagingService.sendAudio` (`VOICE_TX blocked_limited_realtime
  … source=send_layer`) and `CallManager.startCall` (`CALL_TX
  blocked_limited_realtime … source=call_manager`). Voice guard also
  tears down an in-progress recorder cleanly if transport degrades
  mid-recording. Snackbar copy lives in `res/values/strings.xml` —
  EN-only for now to match the rest of the inline-EN chat UI (Test
  #53.1 evidence: a `values-ru/` translation just for D2a strings
  disagreed with the surrounding EN UI on RU-locale devices). Verified
  end-to-end on Tecno `103603734A004351` (Tele2 LTE Иркутская) across
  Test #53 → #53.1 → #53.2. Voice on REST **is still not delivered**
  by D2a — it is refused politely instead of silently failing. The
  real fix is PR-D2b.
- **✅ PR-D2b.1 — Durable voice chunk core + sender hardening.**
  Merged 2026-05-17 night as PR #164 (master `9f1f346b`). Foundation
  layer for voice over REST: `AUDIO_CHUNK_BYTES = 8 KB → 3 KB`,
  per-chunk `transport.send()` result check (failed chunks throw,
  local row stays `QUEUED`), new `voice_chunks` SQLDelight table +
  migration `16.sqm` (v16 → v17), durable 1:1 receive path (save
  chunk BEFORE ack-deliver, assemble + insert keyed on `voiceId`,
  delete chunks on success, keep on insert failure), startup
  finalizer that resumes voices interrupted by process death, 24 h
  TTL with per-voice `VOICE_RX partial_expired` log, already-inserted
  pre-check in the helper so the finalizer cannot double-emit /
  double-notify / double-bump unread after a crash between live
  insert and previous cleanup, `deleteOlderThan` whole-voice
  semantics matching `findExpiredSummaries`, and a range guard
  rejecting malformed `chunkIndex >= chunkTotal` payloads. 10 storage
  contract tests + 5 messaging tests cover the new behaviour. Group
  voice intentionally stays on the in-memory path (no `groupId`
  column in `voice_chunks`); group durability is queued separately.
  Voice on Limited realtime is **still gated closed** by D2a — the
  durable path is wired but not yet exercised. Architect re-review
  on round 2 cleared.
- **PR-D2b.2 (next, after D2b.1).** Flip `canSendVoice` lambda to
  allow `RestActive` / `WsCandidate` so voice goes through the
  durable path on Tele2-style transports. Keep `canStartCalls`
  `WsActive`-only (REST cannot carry WebRTC realtime). Drop the
  voice UI guard in `ChatScreen.onMicClick` (`onVoiceCall` keeps
  its guard — calls remain blocked). Add 15-sec recorder cap +
  `VOICE_TX recorder_auto_stop reason=max_duration durationMs=15000`
  log. Build APK + ship Test #54 install commands. Acceptance: phone
  → emu and emu → phone 5–10 sec voice arrives on Tele2 LTE; every
  chunk `envelopeBytes <= 4096`; `VOICE_RX chunk_saved` precedes
  `ack_send_after_handler`; `assembly_complete` fires exactly once
  per `voiceId`; one voice message in UI (not N chunk messages);
  killing receiver mid-transfer doesn't corrupt chat; calls remain
  blocked.
- **PR-R0.5 / PR-PK1 — Prekey upload response-drop fallback.**
  `/prekeys/publish` POST body lands server-side (Caddy shows 201 ~3 ms)
  but the 18-byte response is dropped downstream on Tele2 ~30 s of the
  time, causing client `SocketTimeoutException` and retries. Low-impact
  because three retries currently mask it, but burns 30 s × 3 per
  attempt. Fix idea: after POST timeout, GET `/prekeys/status/<identity>`;
  if `opks_remaining` / `spk_key_id` updated to expected post-upload
  values → treat upload as success (soft-success path). **Priority: low.**
- **PR-D0r follow-up — Sealed-sender empty mirror.** Mirror envelope to
  REST store with `from=from_identity` when sealed_sender is empty
  (review note from PR-D0r round). Currently not blocking but should
  be closed before next REST API surface change.
- **WS keepalive on Tele2 — diagnostic open.** Phone-side
  `inbound_frames=0 pings_received=0` across every WS session lifetime
  (~31 s) on Tele2 LTE. Direct WS is honest "Online via Direct · Limited
  realtime" because realtime is unreliable; REST fallback covers
  text. Not actionable today — covered by D-track REST design. If C-track
  resurrects WS as a primary realtime path it must include a Tele2
  keepalive answer (server-side TCP keepalive alone is insufficient
  per Test #48 evidence).

### Calls (C-track — Standalone realtime stream)

Calls are intentionally **NOT** in the D-track. WebRTC needs a stable
realtime channel; REST short-poll cannot carry voice/video at acceptable
latency.

- **C1 — TransportCapabilities for calls.** A unified `TransportCapabilities`
  type (`text / voiceMessages / calls / realtimeUdp`) drives UI gating
  honestly. Call button must be disabled when transport ≠ stable realtime.
  Today this state is implicit; an explicit type is required before any
  attempt to ship calls on Limited realtime networks.
- **C2 — Reality endpoint pool with realistic probe.** Today `/health`
  passes but WS realtime is dead — health check is a false-positive.
  Probe must include a real WS handshake + first frame exchange and
  iterate across multiple endpoints (different IP / ASN / SNI / port).
  First stable endpoint wins.
- **C3 — Realtime call transport.** Two candidate paths, both
  blocked on C2 evidence: (A) WebRTC + TURN-over-TLS on 443;
  (B) custom low-bitrate Opus stream over Reality WebSocket with
  jitter buffer + push-to-talk fallback. Decide after C2 measures
  what Tele2 actually allows.

### Repo / docs

- **`docs/calls-experimental` ADR refresh.** Track A item #4 marked
  ✅ merged but the doc itself is now out of date relative to the C-track
  plan. Update or supersede.
- **`KNOWN_ISSUES.md` Tele2 entry.** Should add a plain-English entry
  describing the Tele2 LTE realtime limitation (text via REST works,
  voice queued for D2b, calls unsupported until C-track) so external
  users hitting "Online via Direct · Limited realtime" can read what
  it means.

### Historical / paused

- **Phase 1 connectivity matrix (Tests #43-#47).** Paused 2026-05-15
  per strategic pivot; resumes if priority shifts back. Scope reference
  intact in memory `project_phase1_scope_2026_05_14`.
- **Tor bridge BridgeDB-on-device fetcher.** Deferred (Briar doesn't
  have one either — bridge freshness, not the fetcher, is the bottleneck).
- **Cross-operator testing (Beeline / Megafon).** Blocked on hardware:
  Tecno SIM is Wi-Fi only; second phone with mobile data still incoming.
- **Tor-bridge rotation ADR.** Architect-recommended retrospective,
  not started.

---

## Session journal

Reverse-chronological. Each entry: **goal · outcome · key commits ·
follow-ups** in compact form. Cross-reference the Decision log above
when an entry mentions a rejected approach.

### 2026-05-17 (sun) · PR-D2b.2 + D2b.3a probe parked as proof-of-concept — Test #54/#55 surface real cost: 5-sec voice = 150 chunks × 700 ms ≈ 2 min on Tele2 REST; architect-recommended pivot to PR-M1 (encrypted media upload) + PR-C1 (calls capability gating)

**Branch state.** Draft PR #166 on `feat/pr-d2b2-flip-canSendVoice-and-15s-cap` holds two commits, NOT merged:
- `89f1bf0c` — PR-D2b.2: `canSendVoice = (WsActive || RestActive || WsCandidate)`, `canStartCalls = WsActive only`; removed D2a voice UI guard in `ChatScreen.onMicClick`; kept call UI guard in `onVoiceCall`; added `VOICE_RECORDER_MAX_DURATION_MS = 15_000L` auto-stop that reuses manual-stop pipeline via local `stopAndSendRecording` val; logs `VOICE_TX recorder_auto_stop`.
- `89298f37` — PR-D2b.3a probe: `AUDIO_CHUNK_BYTES = 3072 → 256` (temporary diagnostic), 8-field `VOICE_TX size_probe` log (rawBytes/jsonEncoded/padded/paddedB64/bodyBytes etc.), `sendAudio` failure path stamps `MessageStatus.FAILED` on `chunk_send_fail`. Test renamed `sendAudio_returnsFailureAndStampsRowFailed_whenChunkSendRejected`.

**Test #54 (chunk=3072).** `send_oversize`: rawBytes=3072 → REST bodyBytes ≈ 22 288 → REST cap `max_send_body=4096` rejected every chunk. Confirmed: 6.5× JSON-int-array bloat on ratchet `EncryptedMessage` ByteArray serialization is the root multiplier. Architect recommended size-probe instead of blind retune.

**Test #55 (chunk=256).** Probe = PASS at envelope level: rawBytes=256 → bodyBytes ≈ 3672–3856 < 4096, status=201, `chunk_send_ok`. Durable receiver path also confirmed live (`VOICE_RX chunk_received → chunk_decode_ok → chunk_saved source=durable → ack_deliver_send`, `assembly_waiting received=N/total`). End-to-end = FAIL: 38 KB voice / 256 B = **150 chunks × ~700–800 ms = ~100–120 s upload**, UX-unacceptable; user sent a second voice before first completed, creating overlapping 150+166-chunk streams, no `assembly_complete` reached, no UI voice message appeared (intentional — no partial placeholder in v1). On chat re-entry two local outgoing voice rows surfaced — UX bug, not transport bug.

**Architect verdict (2026-05-17).** "Я бы остановил текущую ветку D2b.2/D2b.3 как 'proof-of-concept, не product-ready' и стартовал новый design: PR-M1 — encrypted media upload for voice messages. А для звонков отдельно: PR-C1 — call capability gating + realtime probe design." Reasoning: voice = async store-and-forward media; calls = realtime stream; both share a common Media Core (codec/Opus, mediaKey, chunks, upload/download, manifest, progress, retry/resume) but are distinct transports. Sending voice as 150 ratchet envelopes is structurally wrong for UX even if each envelope succeeds.

**Open paths (no "go" yet from Vladislav).**
- **A.** Park PR #166 as proof-of-concept; start PR-M1 (encrypted media upload — record → mediaKey → encrypt locally → upload encrypted chunks via REST media endpoint with idempotency → send small E2E voice manifest `{voiceId, mediaKey, duration, mime, chunkCount, sha256}` → receiver GETs + decrypts + inserts one voice row). Tele2 constraints: upload body ~1500–2000 B, tiny responses, GET for download (Tele2 small-GET is reliable per 2026-05-16 diagnostic Layer B).
- **B.** Stopgap PR-D2b.3b (compact `EncryptedMessage` serialization: ByteArray → base64 string vs JSON int-arrays) → ~5× shrink → ~38 chunks per 38 KB voice instead of 150, plus in-progress guard (`voiceSendInProgress(conversationId)` → block second voice + log `VOICE_TX blocked_send_in_progress`), uploading-state UI, replace misleading "voice too long" copy. Faster to ship but still ratchet-envelope architecture.
- **C.** PR-C1 in parallel (TransportCapabilities: Limited realtime → calls disabled, Stable realtime → enabled, Tor/REST-only → disabled) + realistic realtime probe (open channel, send upstream frame, receive downstream ack, hold 10–20 s, measure RTT/jitter/loss) — replaces current `/health` proxy. C2 = Reality endpoint pool (multi IP/ASN/SNI/port), C3 = WebRTC+TURN-TLS vs custom Opus-over-Reality.

**Decision locked 2026-05-17 evening — A + C параллельно** (Vladislav + architect aligned).
- **B (compact serialization PR-D2b.3b) rejected.** Architect quote: "Compact serialization даст ускорение, но всё равно останется старая архитектура: голосовое = много ratchet envelopes. Даже если 150 chunks превратятся в 38 chunks, это всё ещё десятки отдельных сообщений, десятки ACK, десятки retry, сложный UX и задержка. Это стопгап, а не продуктовый путь."
- **PR #166 parked, не merge.** D2b.1 остаётся в master как foundation / fallback / research result.
- **PR-M1 contract LOCKED (architect apply 2026-05-17 evening).** Split into 3 PRs:
  - **M1r** — relay: `POST /media/upload-chunk` + `GET /media/chunk/{mediaId}/{idx}`, bearer-session auth, hard cap 3072 bytes (client target ≤2600), idempotency by `(media_id, idx, ciphertext_hash)`, SQLite `media_chunks`, TTL=7d + sweeper, quotas `maxMediaChunks=256` / `maxMediaBytes=512KB–1MB`, no DELETE in round 1. `mediaId = base64url(random 32 bytes)` capability token.
  - **M1k** — Kotlin `MediaUploadTransport` + manifest model + XChaCha20-Poly1305 (AAD=mediaId), inert/not wired. Manifest `{type:"voice_v2", mediaId, mediaKey, nonce, alg:"xchacha20poly1305-v1", durationMs, mime, chunkCount, encryptedSizeBytes, plainSizeBytes, sha256(plaintext)}`.
  - **M1w** — wire `sendAudio` to M1 (upload all chunks → THEN send single manifest via `/relay/send`); receiver on `voice_v2` GETs chunks, reassembles, decrypts+verifies, inserts one voice row. D2b legacy parked.
  - Sequential upload, single voice at a time, 1:1 only.
  - Throughput: ~22–25 chunks vs current 150 → ~12–20 s on Tele2 (still REST-bound, not instant).
- **PR-C1 — calls capability gating (parallel track).** Small scope: `Stable realtime → calls enabled`, `Limited realtime / REST / Tor / Reality-without-realtime-proof → calls disabled`. Call button allowed only when `realtimeStable == true`, Limited realtime copy stays, log `CALL_CAPABILITY disabled reason=limited_realtime`. Full RealtimeProbe (upstream frame + downstream ack + 10–20 s hold + RTT/jitter/loss) lands later in C2/C3.
- **Goal.** Voice = async encrypted media (not ratchet envelopes); calls blocked until stable realtime transport exists.

**Source-of-truth artefacts.** Architect transcripts in `C:\Users\felix\OneDrive\Рабочий стол\messages.txt` (Test #55 narrative + Q&A on speed + Q&A on calls-first). Test logs `test55-tecno.log` / `test55-emu.log` already captured. APK still `apps/android/build/outputs/apk/debug/android-debug.apk`.

---

### 2026-05-17 (sat late night) · PR-D2b.1 merged — durable voice chunk core + sender hardening (still gated by D2a)

**Goal.** Once PR-D2a closed the UX gap on voice/calls in Limited realtime (Test #53 → #53.2 PASS), the next layer is the real fix for voice over REST short-poll: keep the existing chunk-first/encrypt-each architecture from PR #32 (2026-05-04), but make it production-ready on Tele2 LTE. Split D2b into two PRs per architect review: **D2b.1** = durable chunk core + sender hardening; voice on Limited realtime stays gated by D2a until **D2b.2** flips the gate.

**Scope locked with Vladislav + architect 2026-05-17.** Chunk first → encrypt each chunk separately. Each chunk is a stable-envelope-id send. Receiver saves chunk to SQLDelight BEFORE ack-deliver. ACK only after `chunk_saved`. UI inserts exactly one voice message after assembly_complete (no partial placeholder for v1). Partial chunks TTL = 24 h. No partial-placeholder UI. Group voice stays on the in-memory path because the durable schema has no `groupId` column — group durability queued as a separate follow-up PR.

**Implementation (PR #164 → squash `9f1f346b`).** Round 1 (foundation) + round 2 (architect blocker fixes) squashed together:

- **Sender hardening.** `AUDIO_CHUNK_BYTES = 8 KB → 3 KB` so every envelope passes the REST `max_send_body=4096` cap (Test #53.1 emulator log captured the real oversize case `migrate_pending_skip_oversize bodyBytes=7608`). Per-chunk `transport.send()` result check + `VOICE_TX chunk_prepare voiceId=… idx=K/N rawBytes=… envelopeBytes=… envelopeId=…` log + `VOICE_TX chunk_send_fail` + `VOICE_TX chunk_send_ok` instrumentation; on `false` the loop throws so the local message stays `MessageStatus.QUEUED` instead of falsely flipping to `SENT`. Group voice gets the smaller chunks too (accepted side effect — `DefaultGroupMessagingService` references the same constant).
- **Durable receive (new `voice_chunks` table).** Schema migration `16.sqm` (v16 → v17): `PRIMARY KEY (voice_id, idx)` + chunk_bytes blob + total + conversation_id + sender_pubkey_hex + mime_type + duration_ms + updated_at_ms. `VoiceChunkRepository` interface + `SqlDelightVoiceChunkRepository` production impl + `Dispatchers.IO` per call. The 1:1 chunk handler saves chunk → `chunk_saved` → ack-deliver → `countChunks` → if `== total` → `assembleAndDispatch1to1Voice(origin = "live")`. `assembleAndDispatch1to1Voice` concatenates `findOrderedChunks` into one blob, inserts a message keyed on `voiceId` (so live + finalizer paths converge — `INSERT OR IGNORE` makes them idempotent), upserts conversation, emits `_incomingMessages`, fires notification, then `deleteByVoiceId`. If `insertMessage` itself fails, chunks are NOT deleted; finalizer retries on next startup.
- **Startup finalizer.** New pass at the top of `startReceiving` runs `findVoicesReadyToAssemble` and resumes voices whose chunks landed before the previous process death. Same `assembleAndDispatch1to1Voice` helper, `origin = "finalizer"`. Idempotent across both paths via `messages.id = voiceId` + `INSERT OR IGNORE`.
- **24 h TTL with observable eviction.** `VOICE_CHUNK_TTL_MS = 24 h`. Sweep runs opportunistically on every chunk insert AND at `startReceiving`. Each evicted voice emits one `VOICE_RX partial_expired voiceId=… received=K/N ageMs=…` WARN log so the eviction is observable in logcat.
- **Round-2 fix — Blocker 1: already-inserted skip.** Pre-check at the very top of `assembleAndDispatch1to1Voice` calls `messageRepository.getMessageById(voiceId)` and short-circuits to `deleteByVoiceId` with a `VOICE_RX already_inserted_cleanup` log when the row exists. Closes the crash window between live insert and previous `deleteByVoiceId`: pre-fix the finalizer would treat the `INSERT OR IGNORE` no-op as success and proceed to re-bump unread, re-emit `_incomingMessages`, re-fire the notification.
- **Round-2 fix — Blocker 2: `deleteOlderThan` whole-voice semantics.** SQL rewritten to drop every chunk of any voice whose `MIN(updated_at_ms) < cutoff_ms`, matching the `findExpiredSummaries` predicate exactly. Pre-fix row-level `WHERE updated_at_ms < cutoff` left mixed-age voices in a permanently-unassemblable state — fresh chunks at some indices, expired chunks at others, count never reaches total. Both `FakeVoiceChunkRepository` (storage contract test) and `FakeVoiceChunkLedger` (messaging test) updated to mirror the SQL.
- **Round-2 hardening — range guard.** Lives before the durable/in-memory branch: `if (chunkTotal <= 0 || chunkIndex !in 0 until chunkTotal)` → `VOICE_RX chunk_invalid_range` WARN + ack-deliver + return. Rejects payloads where `audioChunkIndex >= audioChunkTotal` so they cannot pollute reassembly state on either path.
- **Backward compat.** Constructor param `voiceChunkRepository: VoiceChunkRepository? = null` defaults to null so existing tests that didn't wire storage fall through to the legacy in-memory path unchanged. Group voice always stays on the in-memory path (no durable `groupId` column yet). Outdated comments in `DefaultMessagingService.kt` ("8 KB → ~53 KB on wire") and `MessagePayload.kt` ("64 KB slices") rewritten to match the actual constants + current semantics.

**Tests (10 storage + 5 messaging).**

- `VoiceChunkRepositoryContractTest` (storage): insert, count, ordered, INSERT OR REPLACE idempotency, find-ready-set, find-expired-summaries, **delete-older-than expired-voices (single-chunk)**, **delete-older-than whole-voice-when-any-chunk-expired (round 2, mixed-age regression)**, delete-by-voice-id, delete-all, ready-voice metadata roundtrip.
- `DefaultMessagingServiceTest` (messaging): `sendAudio_returnsFailureAndKeepsMessageQueued_whenChunkSendRejected` (per-chunk send-result check propagates failure, row stays QUEUED, `transport.send` aborted on first false); `startReceiving_finalizer_assemblesReadyVoice_andDeletesChunks` (2/2 saved chunks become one assembled `[AUDIO:base64]` row); `startReceiving_finalizer_leavesIncompleteVoiceUntouched` (1/3 partial chunks stay in repo, no message row); `startReceiving_finalizer_evictsChunksOlderThanTtl` (25-hour-old voice dropped without insert); **`startReceiving_finalizer_skipsAlreadyInsertedVoice_andCleansUpChunks` (round 2, crash-window regression: pre-seeded msg row + complete chunks → finalizer dedupes via getMessageById, single row, no double-bump, partial freed)**.

**Status after merge.**

- ✅ Voice chunks now survive process death between save and assembly.
- ✅ `transport.send` rejection on any chunk no longer hides behind a falsely-SENT local message.
- ✅ Receiver path emits `VOICE_RX chunk_saved` BEFORE `ack_send_after_handler` on the durable branch — observable order matches the contract.
- ✅ 24 h TTL with per-voice `partial_expired` log; whole-voice eviction guarantees no orphan rows.
- ✅ Finalizer is idempotent across crash + restart; already-inserted voices are cleanly cleaned up without UX duplication.
- ⛔️ Voice on Limited realtime is **still gated closed by D2a's `canSendVoice` lambda** (`mode == WsActive`). The durable path is wired but never exercised in production until D2b.2 flips the gate. This is intentional — the gate flip ships with the recorder cap + real-device acceptance test in one PR so we don't widen the surface area while still validating it.

**What this does NOT do.**

- Does **not** flip `canSendVoice` or remove the `onMicClick` UI guard. That's D2b.2.
- Does **not** add the 15-sec recorder cap. D2b.2.
- Does **not** make group voice durable. Group voice keeps the in-memory 5-minute buffer until a separate PR adds `groupId` to `voice_chunks`.
- Does **not** address the H2b residual risk window between `processedEnvelopeRepository.markProcessed` (inside decrypt mutex) and `voiceChunkRepository.insertChunk`. A crash in that window lets the ledger skip the relay's redelivery and the chunk is lost. Closing it requires receive-pipeline transaction redesign (separate "decrypt ledger" state from "payload persisted" state) — queued as a future PR. The commit + PR body deliberately phrase D2b.1's guarantee as "chunks durable BEFORE ack-deliver after the handler starts to persist them", not "zero-loss between decrypt and save".

**Key commits / PRs.**

- PR #164 (squash-merged as `9f1f346b`) — `feat(media): PR-D2b.1 — durable voice chunk core + sender hardening`. Folds round-1 (`06531596`) + round-2 (`a8849260`).
- 9 files changed, 1612 insertions / 24 deletions. New files: `VoiceChunk.sq`, `16.sqm`, `VoiceChunkRepository.kt`, `SqlDelightVoiceChunkRepository.kt`, `VoiceChunkRepositoryContractTest.kt`. Modified: `DefaultMessagingService.kt`, `MessagePayload.kt`, `DefaultMessagingServiceTest.kt`, `AppContainer.kt`.

**Next.**

- **PR-D2b.2 (next session).** Flip `canSendVoice` to allow `RestActive` / `WsCandidate`. Keep `canStartCalls` `WsActive`-only (REST cannot carry WebRTC). Drop voice UI guard in `ChatScreen.onMicClick`, keep call UI guard in `onVoiceCall`. Add 15-sec recorder cap + `VOICE_TX recorder_auto_stop reason=max_duration durationMs=15000` log. Build APK + deliver Test #54 install commands.
- **Test #54.** Tecno `103603734A004351` on Tele2 LTE Иркутская + emulator-5554. Verify: phone → emu and emu → phone 5–10 sec voice arrives; every chunk `envelopeBytes <= 4096`; `VOICE_RX chunk_saved` precedes `ack_send_after_handler`; `assembly_complete` fires exactly once per voiceId; one voice message in UI (not N chunk messages); killing receiver mid-transfer doesn't corrupt chat (chunks resume on restart or expire silently at 24 h); calls remain blocked.

### 2026-05-17 (sat) · Test #52 PASS — PR-D1d merged, fast active-outbound ACK deadline closes the ~40 s WS→REST latency gap on Tele2 LTE
follow-ups** in compact form. Cross-reference the Decision log above
when an entry mentions a rejected approach.

### 2026-05-17 (sat night) · PR-D2a merged — UX guard for voice/calls on Limited realtime + EN-only Snackbar (Test #53 → #53.1 → #53.2 PASS)

**Goal.** Once PR-D1d closed the ~40 s text-message latency on Tele2
LTE, the next user-visible failure mode was voice messages and calls
silently dying on `RestActive`: REST short-poll caps the body at
4096 bytes, an `audio_chunk` envelope is 11–55 KB, and WebRTC cannot
work over a poll loop at all. The temporary fix — gate both features
at the UI and at the send/call layer when the orchestrator is not
in `WsActive`, with a clear English Snackbar — buys the real fix
(PR-D2b voice-over-REST chunking, plus the separate C-track for
calls) room to land without leaving the user staring at a recorder
that never sends or a call screen that never rings.

**Contract locked with Vladislav 2026-05-17.** Source of truth =
`RestStateMachine.current` (not display text). Two-layer guard:
ChatScreen blocks at the gesture, `DefaultMessagingService.sendAudio`
+ `CallManager.startCall` block at the send layer for any path that
bypasses the UI (programmatic, deep-link, retry). Rule:
`voiceAllowed == callsAllowed == (mode == null || mode == WsActive)`
— `null` covers Alpha 1 / pre-bootstrap where there is no hybrid
transport object. Structured logs on every guard hit so the path
is visible in `PhantomHybrid:V` capture without describing what
was on screen. Scope strictly guard-only — no transport / crypto /
DB / chunking change; voice on REST gets its real fix in PR-D2b.

**Implementation (PR #162 → squash `210b827f`).** Three commits
folded into one squash merge:

- **D2a (initial).** `DefaultMessagingService` adds
  `canSendVoice: () -> Boolean = { true }` ctor param; `sendAudio`
  returns `Result.failure(IllegalStateException(...))` and logs
  `VOICE_TX blocked_limited_realtime conv=… audioBytes=… source=send_layer`
  (default `{ true }` preserves existing tests). `CallManager` adds
  `canStartCalls: () -> Boolean = { true }`; `startCall` returns
  early + `CALL_TX blocked_limited_realtime to=… source=call_manager`
  — no `CALLING` state transition, no `AudioManager` touch on a
  closed gate. `AppContainer` wires both lambdas to the same source
  (`hybridTransport?.stateMachine?.state?.value == RestMode.WsActive`),
  `null` returns `true`. ChatScreen `onMicClick` + `onVoiceCall`
  Snackbar refusal at gesture start; mic guard also tears down
  any in-progress recorder (`mediaRecorder.stop/release/null` +
  `audioFile.delete`) if the transport degrades mid-recording so
  the user sees the Snackbar instead of a silent send-then-fail.
- **D2a.1 (localisation + UI observability).** Snackbar copy moved
  out of inline Kotlin into Android string resources
  (`res/values/strings.xml` EN). UI guard sites add
  `Log.w("PhantomHybrid", "MEDIA_CAPABILITY blocked feature=voice|call
  mode=$mode source=ui recording_in_progress=…")`. Existing send-layer
  logs extended with `source=send_layer` / `source=call_manager` so
  a log reader can tell at a glance which layer fired.
- **D2a.2 (drop values-ru).** Test #53.1 evidence showed that
  shipping a Russian translation for just these two strings while
  the rest of the chat UI is hardcoded English inline made the
  Snackbar disagree with the surrounding screen on RU-locale
  devices. Delete `res/values-ru/strings.xml`; keep only the EN
  default. Comment in `values/strings.xml` documents that the
  Russian copy comes back during a proper centralised localisation
  pass, not piecemeal.

**Test #53 (2026-05-17 evening).** Tecno `103603734A004351` on
Tele2 LTE Иркутская + emulator-5554. UI guards visibly fired (mic
+ call buttons both refused with Snackbar). Architect flagged two
gaps before merge: (1) no `MEDIA_CAPABILITY` log line — UI-level
guard was invisible in logcat unless the user described the screen;
(2) RU Snackbar copy on EN-locale device — localisation needed.

**Test #53.1 (D2a.1 APK, same hardware).** UI logs now present and
correct on Tecno: `MEDIA_CAPABILITY blocked feature=voice
mode=RestActive source=ui recording_in_progress=false` and
`feature=call mode=RestActive source=ui` both captured during the
`RestActive` window. Send-layer logs not triggered (UI caught the
gestures first, which is the intended layering). Emulator stayed
on `WsActive` end-to-end, voice/call buttons remained enabled —
expected, the contract is "Limited realtime blocks media, stable
WS allows it". Bonus evidence on the emulator: REST migrate path
already saw a real oversize voice envelope —
`migrate_pending_skip_oversize id=e96223a6 bodyBytes=7608 max=4096`
— which is the concrete case PR-D2b will need to handle. Snackbar
text however still rendered Russian because Tecno's system locale
is Russian and the platform correctly chose `values-ru/`.

**Test #53.2 (D2a.2 APK, same hardware).** Vladislav confirmed
visual PASS — Snackbar copy now renders English on the device,
matching the rest of the EN-only chat UI. Architect signed off:
acceptance criterion for localisation is visual on the device
(snackbar text does not show up in `logcat`), and D2a's job as a
**temporary UX guard** is done — Limited realtime blocks media,
stable WS allows media, EN copy across the board, structured logs
on every guard layer.

**Status after merge.**

- ✅ Voice messages blocked at UI + send layer on `RestActive` /
  `WsCandidate`; English Snackbar; no silent failure.
- ✅ Calls blocked at UI + `CallManager.startCall`; English
  Snackbar; no `CALLING` state, no `AudioManager` touch on closed
  gate.
- ✅ Structured logs at both layers (`MEDIA_CAPABILITY` UI,
  `VOICE_TX` / `CALL_TX` send layer) with explicit `source=` so a
  log reader sees which path fired.
- ✅ Text fallback unaffected — Test #53 / #53.1 / #53.2 all
  delivered ordinary text messages through the REST path while the
  media guards were closed.

**What this does NOT do — explicitly.** Voice messages on REST
**are still not delivered**; they are now politely refused instead
of silently failing. Calls on REST **are still not delivered**;
they are now politely refused instead of opening a never-connecting
call screen. The real fixes are tracked separately:

- **PR-D2b** — voice-over-REST chunking. Real fix. Split audio into
  ≤ 4096-byte (target ≈ 2.5–3 KB for headroom) encrypted chunks,
  ACK strictly after save, receiver reassembles by `voiceId`, single
  DB voice message after `assembly_complete`. Test #53.1 emulator
  log already produced the concrete oversize case
  (`bodyBytes=7608 > max=4096`) so D2b has real telemetry to design
  against, not just specification.
- **C-track** — calls on a stable realtime channel.
  - **PR-C1** `TransportCapabilities` typed gate
    (`text / voiceMessages / calls / realtimeUdp`) — replaces the
    `state == WsActive` shorthand once Reality endpoint pool + WS
    probe land.
  - **PR-C2** Reality endpoint pool + realistic probe (current
    probe is `/health`, which doesn't catch the Tele2 Layer A
    silent-WS-drop pattern; need a WS-handshake probe).
  - **PR-C3** TURN-over-TLS:443 or custom Opus-over-Reality —
    whichever survives Tele2 / МТС / Beeline LTE in field tests.

**Open follow-ups updated.** D-track item D2a moves from "open"
to "closed (UX guard layer)". D2b remains the headline real-fix
work. C1–C3 remain queued separately. Tele2 WS keepalive baseline
**unchanged** — phone-side WS still dies every ~31 s with
`pings_received=0 inbound_frames=0` (the Test #48 Tele2 Layer A
profile the REST fallback was designed for, not a D2a regression).

**Key commits / PRs.**

- PR #162 (squash-merged as `210b827f`) — `feat(media): PR-D2a —
  UI + send-layer guard for voice/calls on Limited realtime`.
  Folds three iteration commits: `35d3ced2` (initial guards +
  wiring + RU Snackbar inline), `952bc2c9` (D2a.1 — string
  resources + `MEDIA_CAPABILITY` UI logs + `source=` fields on
  send-layer logs), `263a53fc` (D2a.2 — drop `values-ru` to match
  EN-only inline UI).
- 5 files changed, 163 insertions: `DefaultMessagingService.kt`
  (+34), `CallManager.kt` (+27), `AppContainer.kt` (+21),
  `ChatScreen.kt` (+60), `res/values/strings.xml` (+21 new).
- No backwards-compatibility shims — default `{ true }` lambdas on
  both ctor params preserve every existing test path and Alpha 1
  bare-WS flow without a feature flag.

### 2026-05-17 (sat) · Test #52 PASS — PR-D1d merged, fast active-outbound ACK deadline closes the ~40 s WS→REST latency gap on Tele2 LTE

**Goal.** Verify PR-D1d (10 s per-envelope ACK deadline) on the same
Tele2 LTE Иркутская hardware that exposed the ~40 s `active_outbound_threshold`
latency in Test #51, and merge if the contract holds.

**Test #52 setup.** Tecno `103603734A004351` on Tele2 LTE Иркутская +
emulator-5554 on dev Wi-Fi. APK from `feat/pr-d1d-active-outbound-ack-deadline`
head `5859f306` (PR-D1d + round-1 hardening `armAckDeadlineLocked` cancels
any pre-existing Job before replacement). Structured `REST_TRACE` +
`PhantomRelay` capture per `feedback_logcat_format`.

**Acceptance — fast active-outbound degrade.** First stuck outbound
envelope migrates to REST after exactly the configured `ACK_DEADLINE_MS=10000`,
not after 2× WS session deaths (~40–60 s). Phone-side timeline of
`397df3c7` (first text after bootstrap):

```
23:59:05.459  Sending envelope: id=397df3c7 seq=1 sealed=true payloadBytes=1368
23:59:05.460  outbound_ack_deadline_armed id=397df3c7 timeoutMs=10000
23:59:15.464  outbound_ack_deadline_expired id=397df3c7 ageMs=10004    ← timer fires at 10 s
23:59:15.468  mode_switched from=WsActive to=REST_ACTIVE reason=active_outbound_ack_timeout
23:59:15.469  migrate_pending_arm from=WsActive to=RestActive
23:59:15.475  migrate_pending_send id=397df3c7 seq=1 bodyBytes=1808
23:59:16.130  send_response id=397df3c7 status=201 elapsedMs=625        ← REST POST success
23:59:16.130  migrate_pending_ok id=397df3c7 status=201
23:59:16.131  migrate_pending_done ok=1 failed=0
```

Total user-visible latency from `send_start` to relay-accepted = **~11 s**
(10 s deadline + 0.6 s REST POST). Test #51 baseline was ~40 s on the
same network.

Emulator received the migrated envelope correctly:

```
05:00:06.484  Received envelope: id=397df3c7 sealed=true payloadBytes=1368
05:00:06.532  Decrypt OK after bootstrap: plaintextBytes=62
05:00:06.539  Payload parsed: type=message textLen=3
05:00:06.567  handleDeliver DONE for id=397df3c7 (ack-deliver sent)
```

**Acceptance — healthy ACK path cancels deadline.** Emulator → phone
`7cc11e42` (steady-state direction, ACK arrives within ~100 ms):

```
05:00:13.151  Sending envelope: id=7cc11e42 seq=1 payloadBytes=1368
05:00:13.152  outbound_ack_deadline_armed id=7cc11e42 timeoutMs=10000
05:00:13.255  outbound_ack_deadline_cancelled id=7cc11e42 reason=ack_received
05:00:13.255  Ack from relay: id=7cc11e42 status=delivered
```

Timer armed → cancelled within 100 ms. No false REST switch on a healthy
WS frame. Helper invariant (one pendingAck msgId ⇒ at most one active
deadline Job) holds.

**Acceptance — subsequent texts route through REST cleanly.** After the
first envelope migrates, the orchestrator stays in `RestActive` for the
rest of the session. Tele2 → emu (`3d92286f`, `46411fcd`, `30c2ab4d`)
and emu → Tele2 (`d7183d3e`, `8f590e1`, `b635b761`) all complete with
`status=201` + REST poll + decrypt OK. Six texts round-tripped over
~3 minutes on Tele2 LTE.

**Tele2 baseline acknowledged.** Phone-side WS continues to die every
~31 s (`SocketTimeoutException: sent ping but didn't receive pong within
15000ms (after 0 successful ping/pongs)`) — server sees `pings_received=0
inbound_frames=0` as before. This is the Test #48 Tele2 Layer A profile
that REST fallback was designed for; the WS-death cadence is **not** a
D1d regression. D1d's contract is "do not wait for the WS to die to
notice the outbound is stuck", which is exactly what the logs show.

**Voice / calls confirmed out-of-scope for this PR.** Test #52 logs
contain zero `audio_chunk` / `VOICE_TX` / `VOICE_RX` lines. Media
delivery on Limited realtime networks is the next track:

- **PR-D2a** (next, this session) — UI + send-layer guard so voice on
  Limited realtime fails fast with a clear message instead of "recorded
  and disappeared".
- **PR-D2b** — voice-over-REST chunking (body ≤ 4096 b, ACK after save,
  receiver reassembly).
- **C-track** — calls on a stable realtime channel (Reality endpoint
  pool with realistic probe, then TURN-TLS or custom Opus-over-Reality).

See [Open follow-ups](#open-follow-ups--unfinished-items) above for the
full queue.

**Key commits / PRs.**

- PR #160 (squash-merged as `3e4293c3`) — `feat(transport): PR-D1d —
  fast active-outbound ACK deadline (10s per-envelope timer)`.
- Round-1 hardening commit `5859f306` (one-liner in
  `armAckDeadlineLocked`: `ackDeadlineJobs.remove(msgId)?.cancel()`
  before replacement, preserves "at most one Job per msgId" invariant).
- PR #159 (squash-merged as `67cfd0b0`) — Test #51 entry + MASTER_TIMELINE
  bump.
- 5 production removal sites for `pendingAcks` routed through
  `removePendingAckLocked` / `clearAllPendingAcksLocked` helpers; 2
  insertion sites (`send()` + flush re-track) route through
  `armAckDeadlineLocked`. State machine remains a pure reducer;
  HybridRelayTransport does the `state == RestMode.WsActive` mode-check
  before submitting the event.

### 2026-05-17 (sat) · Test #51 partial PASS — PR-D1c + D1c.1 merged to master, bootstrap-ordering bug closed end-to-end on Tele2 LTE

**Goal.** Verify PR-D1c (migrate pending WS outbox → REST on mode switch) + PR-D1c.1 (REST session token lifecycle / CAS / mutex / 401 refresh fix) on real hardware. Test #50 (2026-05-16) had reproduced the bootstrap-ordering symptom on Tele2 LTE: the migration logic ran but the very first REST send returned 401 because of three token-cache bugs. PR-D1c.1 fixed all three and was bundled into PR #157.

**Test #51 setup.** Tecno (phone identity `9ecca9679dbc0529…`) on Tele2 LTE Иркутская + emulator-5554 (identity `592cf3c06040…`) on dev Wi-Fi. Fresh install (`pm clear phantom.android` both sides), APK from `feat/pr-d1c-migrate-pending-ws-to-rest` head `61a051f1`, structured `REST_TRACE` capture per `feedback_logcat_format`.

**End-to-end PASS — the full token + migration + REST send chain works.** Compressed phone log timeline:

```
22:28:24 token_refresh_start reason=bootstrap force=true stale=false   ← PR-D1c.1 acquireOrRefreshToken
22:28:27 session_response status=200 elapsedMs=673 rest_fallback=true max_body=4096
22:28:27 token_cached reason=bootstrap expiresInMs=3601017             ← PR-D1c.1 fix #1: bootstrap caches token
22:28:27 capability_enabled max_body=4096 poll_max_envelopes=1
22:28:27 bootstrap_ok capability=true
22:28:27 orchestrator_started
22:28:27 poll_stopped reason=ws_active                                  ← REST orchestrator dormant while WS is up
22:29:23 SEND_TRACE send_start id=d2416afe... textLen=3                 ← user sends first message
22:29:24 relay_send_call id=d2416afe... payloadBytes=1024               ← goes via WS first
22:29:24 relay_send_return id=d2416afe... ok=true                       ← WS write succeeded, no ACK yet
22:29:31 counter_tick kind=active count=1 pending_acks=1                ← Tele2 layer A: WS frame upstream dropped
22:30:03 counter_tick kind=active count=2 pending_acks=1
22:30:03 mode_switched from=WsActive to=REST_ACTIVE reason=active_outbound_threshold
22:30:03 migrate_pending_arm from=WsActive to=RestActive                ← PR-D1c migration ARMs
22:30:03 poll_started mode=RestActive
22:30:03 token_reused reason=poll expiresInMs=3505198                   ← PR-D1c.1 fix #2: Mutex serialises, single cache
22:30:03 migrate_pending_start count=1                                  ← PR-D1c picks up the WS outbox
22:30:03 migrate_pending_send id=d2416afe seq=1 bodyBytes=1808
22:30:03 token_reused reason=send expiresInMs=3505190                   ← PR-D1c.1 fix #3: token re-acquired inside loop
22:30:04 send_response id=d2416afe status=201 elapsedMs=577             ← REST send succeeded with cached token
22:30:04 migrate_pending_ok id=d2416afe status=201
22:30:04 migrate_pending_done ok=1 failed=0
```

Emulator side (same `d2416afe` envelope landing via REST poll on the receiver's side after the sender migrated it):

```
03:30:54 handleDeliver start: id=d2416afe... sealed=true payloadBytes=1368
03:30:54 Sender identified: 9ecca9679dbc0529...
03:30:54 Bootstrapping recipient session: conv=592cf3c06040...
03:30:54 Decrypt OK after bootstrap: plaintextBytes=62                  ← X3DH/double-ratchet session created
03:30:54 Payload parsed: type=message textLen=3
03:30:54 DB insertMessage OK
03:30:54 Creating new conversation as REQUEST
```

The "Decrypt OK after bootstrap" line is the smoking gun. Before PR-D1c the bootstrap envelope was lost when the state machine flipped mid-flight, so the receiver got the *second* message first and had no session — that was the original Test #49/#50 symptom.

**Steady-state validation — REST orchestrator runs cleanly for the full session.** After the first send, the phone stays in `RestActive` and every subsequent send/poll/ack reuses the cached token: `token_reused reason=send` / `reason=poll` / `reason=ack` on every operation, zero additional `/auth/session` calls, all `send_response status=201`. Five more text messages exchanged both directions over ~3 minutes: all delivered, all decrypted, all ack'd. Duplicate envelopes from REST poll redelivery handled by the H2b ledger guard (`inbound_skip_already_processed id=…`).

**What's closed and what's still open after Test #51.** Mapping each architectural concern from Test #48/#49 to its current state on master:

| Concern | Status after Test #51 |
|---|---|
| Direct probe rejecting healthy `/health` on Tele2 | ✅ closed by PR-R0.3 — `direct.result ok=true totalMs=611` |
| REST capability discovery | ✅ closed by PR-D0r/D1/D1b — `rest_fallback=true max_body=4096` advertised, client honours |
| WS→REST mode switch | ✅ closed — `mode_switched from=WsActive to=REST_ACTIVE reason=active_outbound_threshold` fires correctly |
| Pending WS outbox migration to REST | ✅ closed by PR-D1c — `migrate_pending_arm/start/send/ok/done` runs end-to-end |
| Bootstrap envelope ordering across mode switch | ✅ closed by PR-D1c — receiver gets bootstrap envelope first, X3DH session created |
| REST session token cached after bootstrap | ✅ closed by PR-D1c.1 fix #1 — `token_cached reason=bootstrap` then `token_reused` on every subsequent op |
| Concurrent token-refresh race | ✅ closed by PR-D1c.1 fix #2 — `Mutex` + CAS via `staleToken` parameter |
| 401 refresh propagates to retry attempts | ✅ closed by PR-D1c.1 fix #3 — token acquired inside the retry loop |
| Text delivery via REST end-to-end | ✅ confirmed — six messages each direction, all received and decrypted |
| ACK-after-save / inbound deduplication | ✅ confirmed — H2b ledger guard handles REST redelivery |
| **WS→REST latency** | ❌ **~40 s** wait for 2 active counter ticks — UX-blocker for next PR |
| **Voice over REST** | ❌ blocked — `route_send` shows 11–55 KB voice chunks rejected by `send_oversize max=4096` cap |
| Prekey publish response timeout occasionally | ❗ minor — one `upload_fail SocketTimeoutException elapsedMs=183696` on the phone log, did not block Test #51 (prekeys were already valid) |

**Merge decision.** Vladislav verdict: merge. Reliability fix is proven on real hardware; the remaining UX latency and voice gaps are next-PR scope, not D1c scope. PR #157 was already `MERGEABLE / CLEAN` with head `61a051f1` (PR #158 had been bundled into the D1c branch as a merge commit on 2026-05-16). Squash-merged to master as `d7a05273` with a commit message that enumerates the three D1c.1 token-lifecycle fixes and the Test #51 evidence. Branch `feat/pr-d1c-migrate-pending-ws-to-rest` deleted.

**Next-PR plan locked.** Discussion converged on three follow-ups in priority order:

1. **PR-D1d — fast active-outbound degrade.** The 40-second wait for two counter ticks is the dominant UX cost in Test #51. New mechanism: when an envelope enters `pendingAcks` over WS, arm a per-envelope ACK deadline timer = 10 s. If ACK for that `msg_id` does not arrive before the deadline, emit `RestStateMachine.Event.ActiveOutboundAckTimeout(msg_id, ageMs)` and switch `WS_ACTIVE → REST_ACTIVE` immediately; PR-D1c migration then picks up the same envelope and delivers it over REST. Reset condition is strict: timer is cancelled **only** by ACK on the same envelope-id (not by any incoming WS frame — partial socket liveness ≠ ACK for our outbound envelope). 10 s chosen over 8 s (false-positive risk on radio wake / weak LTE) and over 15 s (still feels slow). Expected outcome on Tele2: first message latency drops from ~40-60 s to ~10-15 s. Council not requested — the trade-off is well-defined and the empirical baseline (`pingInterval(15s)` already deployed in Run C on healthy WS) is the safety margin. New `REST_TRACE` lines: `ack_deadline_armed id=… timeoutMs=10000` / `ack_deadline_cancelled id=… reason=ack_received` / `ack_deadline_expired id=… ageMs=10000` / `mode_switched … reason=active_outbound_ack_timeout`. Test #52 acceptance: first phone→emu text under 15 s, no false REST switch on emulator direct smoke.
2. **PR-D2a — UI gating for voice in `Limited realtime`.** Honest stop-gap before D2b. When `RestStateMachine.state == RestActive`, the chat composer must either disable the voice button or surface a message: "Голосовые временно недоступны в режиме Limited realtime. Текстовые сообщения доставляются через резервный канал." / "Voice messages are temporarily unavailable in Limited realtime mode. Text messages are delivered via REST fallback." Stops the user from believing a 55 KB voice envelope "sent" when it was rejected client-side by `send_oversize`. Small PR, no protocol changes.
3. **PR-D2b — voice-over-REST chunking.** Larger protocol work: target REST body ≤ 2-3 KB per chunk (well under the 4096 cap), receiver reassembles with sequence ordering + retry + reassembly timer. Not merged with D2a — separate scope, separate review pass. Sequenced after D1d.

**Deferred follow-ups.**
- **Prekey upload response timeout fallback (PR-R0.5 / PR-PK1).** After POST `/prekeys/publish` times out, query `GET /prekeys/status`; if the server already shows fresh prekeys, treat the upload as successful. Same root cause as Tele2 Layer B (POST upstream OK, response dropped downstream). Low priority — did not block Test #51.
- **`mirror_envelope_to_rest_store` `from: ""` for sealed-sender envelopes** still queued from 2026-05-16; not surfaced by Test #51 because sealed-sender is the Alpha-2 default.

**Process notes.**
- Confirmed PR-D1c.1 was already inside PR #157 (head OID `61a051f1`) before squash-merging. The `gh pr view 157 --json` cross-check is a cheap habit; running it before every cross-PR merge has saved at least one "merged the wrong head" near-miss already.
- Architect (sub-agent) framed PR-D1d threshold question as "8 / 10 / 15 s, per-envelope vs global, reset on any incoming frame vs only ACK-of-same-id". Three trade-off pairs in one prompt. Vladislav picked the strictest answer set on all three. Saving here so the next session does not re-litigate the same questions: **10 s, per-envelope, reset only by ACK(same-id)**.
- Squash-merge commit body for PR #157 was crafted to enumerate the three D1c.1 fixes + Test #51 evidence + known follow-ups. Future `git log` greps for the bootstrap-ordering bug will find this commit directly without needing to walk the PR branch history.

### 2026-05-16 (fri) · Tele2 reliability Round 2 + REST fallback transport (full stack) — PR-R0.2/R0.3/R0.4a/R0.4b + PR-D0r + PR-D1 + PR-D1b all merged

**Seven PRs merged on master in one day.** Two threads: (1) finish the Tele2 reliability sprint that started 2026-05-15, (2) discover a deeper Tele2 failure mode (WS frame layer is dropped after handshake) and design + ship the **complete** REST short-poll fallback transport — relay endpoints (D0r) + Kotlin client library (D1) + live wire-up into Android messaging (D1b).

**Thread 1 — Tele2 reliability Round 2 (4 PRs).**

- **PR-R0.2 #147** (`030b3108`) — drop OPK publish batch 100 → 40, brings the prekey publish body from 13 903 to 5 863 bytes, slipping under the 8 192-byte Tele2 middlebox cut (the deterministic `bytes_read=8192, status=408, duration=30s` signature observed across Tests #43-#44 on the same Tele2 SIM). Test #45 (2026-05-15) confirmed `prekey_publish_ok status=201 elapsedMs=902`, Direct stays Direct end-to-end on Tele2 without falling to Tor.
- **PR-V0b-diag #148** (`77b96dc1`) — `VOICE_RX` structured logs across `audio_chunk` receiver pipeline. Diagnostic-only; lets Test #46 attribute voice-message MIA between transport, decrypt, or persistence.
- **PR-R0.4a #149** (`18a23b6d`) — stop false reconnect cycling from AlarmManager's stale-inbound check. The AlarmManager-driven proactive reconnect was treating "no inbound frames for ≥45 s" as a stale socket on Tele2 LTE where inbound frames legitimately can pause through Probing. Wakeup receiver now pokes connectivity instead of forcing reconnect.
- **PR-R0.3 #150** (`007ed061`) — replace Ktor-engine Direct probe with a native OkHttp `/health` GET. Ktor engine was reusing pool entries half-closed by Tele2 NAT, returning `ok=false` while SSH `curl` confirmed `HTTP/2 200 48 ms`. New native probe: fresh `OkHttpClient` per call, HTTP/1.1 pinned, `Connection: close`, `ConnectionPool(0)`, `retryOnConnectionFailure(false)`, two attempts with 400 ms backoff, outer timeout raised 5 s → 25 s. Direct probe now reports `ok=true totalMs=1008` on the Tele2 SIM.
- **PR-R0.4b #151** (`727e1a83`) — remove the in-process dead-socket watchdog `forceReconnect`. `KtorRelayTransport`'s `lastInboundFrameMark` is updated only on `Frame.Text`, not on OkHttp WS-protocol Pong, so an idle-but-healthy session was being torn down every 60 s. Watchdog is now passive (idle log only).

**Thread 2 — Test #48 revealed deeper Tele2 layers + REST fallback transport (PR-D0r + PR-D1).**

Test #48 ran R0.3 + R0.4a + R0.4b on the same Tele2 SIM. SSH-pulled relay + Caddy logs (delegated to Vladislav per `feedback_ssh_delegation`) showed two new failure modes that were *hidden* behind the prekey-publish bug we just fixed:

- **Layer A — WS frame layer dropped upstream after handshake.** Every phone WS session on the relay side: `pings_received=0, pongs_sent=0, inbound_frames=0, outbound_frames=0, duration_ms ≈ 153 000` (server's own read timeout). Phone client side: every session dies at 31 s with `SocketTimeoutException: sent ping but didn't receive pong within 15000ms (after 0 successful ping/pongs)`. The 31 vs 153 s asymmetry proves the WS Upgrade handshake passes through the middlebox, but Frame.Text upstream is silently dropped after; phone TCP close not propagated, relay's own read deadline eventually tears down at ~150 s. OkHttp `pingInterval(15s)` timeout is *symptom*, not cause — if we disabled it, WS would live longer as a zombie but still get 0 inbound frames and 0 ACKs.
- **Layer B — POST 5863b body OK upstream, but response dropped downstream.** Caddy access log: 3× `POST /prekeys/publish bytes_read=5863 status=201 duration=2.9ms resp_size=18` server-side success. Phone client side: all 3 attempts time out with `SocketTimeoutException elapsedMs=61283ms` waiting for the 18-byte 201 response. Server sent it within 3 ms; the response never reached phone within 60 s. Asymmetric to Layer A (where upstream is what's broken), here downstream is broken after a non-trivial POST upload — suggesting the middlebox marks the flow "suspicious" after volume crosses some threshold.
- **Layer C (positive) — small GET req/resp works.** `/health` (15 b resp), `/prekeys/status` (48 b resp), `/auth/challenge` (110 b resp): all consistently 200, sub-ms. Direct mode on Tele2 is partially usable but unreliable as a persistent flow; reliable only for small GET round-trips.

**Why PR-R0.4c was killed.** First architect take suggested disabling OkHttp `pingInterval` (let WS live longer). I caught the bigger picture from the server-side data: `pings_received=0` proves phone pings never reach the relay — OkHttp's own Ping timer firing is just measuring the absence of return Pongs that never had a chance to be requested. Disabling client-side Ping would only extend the zombie WS lifetime; it would not make a single byte of data reach the recipient. R0.4c-disable-ping is **dropped from the plan entirely**.

**Locked direction.** REST short-poll fallback with server-side idempotency: client retries POSTs whose responses were dropped, server dedupes by `Idempotency-Key`. Plus capability discovery so old relays keep clients in WS-only mode (no behaviour regression).

- **PR-D0r #152** (`1c1a91a9`) — Rust relay endpoints `/auth/session` (bearer token, retry-safe via `(identity, challenge, signing_pubkey, signature)` cache), `/relay/send` (one envelope per call, `Idempotency-Key` header required, status 201 fresh / 200 replay / 409 conflict), `/relay/poll` (≤ 1 envelope per call, server retains until `/relay/ack-deliver`), `/relay/ack-deliver` (idempotent removal). Sha-256 body hash via `sha2` crate (originally `DefaultHasher` — collision attack on the 200-replay vs 409-conflict decision was the fourth blocker from review). Per-identity LRU idempotency cache (10 K keys × 24 h TTL). Capability discovery in `/auth/session` response: `rest_fallback:true, max_send_body_bytes:4096, poll_max_envelopes:1`. Public mirror helpers `mirror_envelope_to_rest_store` / `remove_envelope_from_rest_store` called from BOTH the WS `Send` / `ack-deliver` arms in `routes.rs` AND the REST send/ack handlers, so WS and REST share envelope state in both directions (was the second review blocker). `sealed_sender` preserved end-to-end through send → poll → live WS delivery JSON (was the third review blocker). 10 integration tests including 4 new ones added in the second review cycle: `session_replay_with_different_signature_returns_401`, `rest_send_preserves_sealed_sender`, `ws_simulated_send_mirrors_into_rest_poll`, `ws_simulated_ack_clears_rest_poll`. Deployed to `relay.phntm.pro` 2026-05-16; smoke-test (`/auth/session` 422 on empty body, `/relay/send` 401 no-auth, `/relay/poll` 401 no-auth) confirms all four endpoints live.
- **PR-D1 #153** (`6f6ade20`) — Kotlin client library for the REST fallback transport. CommonMain: `RestFallbackTransport` (interface + JSON wire models with `@SerialName` snake_case, `RelayCapabilities` with `SAFE_DEFAULTS = restFallback=false`), `RestStateMachine` (pure state machine `WS_ACTIVE ↔ REST_ACTIVE ↔ WS_CANDIDATE` with locked triggers: 2 active fails OR 3 idle fails to enter REST, 60 s alive OR outbound ACK to commit back to WS), `RestFallbackOrchestrator` (lifecycle, token management with retry-safe `/auth/session`, capability gate, 5-attempt retry with 1/3/8/20/60 s backoff and stable `Idempotency-Key`, adaptive poll loop 2/5/15 s). AndroidMain: `AndroidNativeOkHttpRestFallbackTransport` (HTTP/1.1 pinned, `Connection: close`, fresh client per call — same pattern as PR-R0.1 prekey transport and PR-R0.3 Direct probe). `expect/actual createRestFallbackTransport()` factory. 28 unit tests. Shipped **inert** — library built, orchestrator dormant, capability gate keeps it disabled against any relay that does not advertise `rest_fallback:true`. Live wire-up landed same session as PR-D1b (below).

- **PR-D1b #155** (`e9bdf462`) — wire the REST fallback transport into the live Android messaging delivery path. New `HybridRelayTransport` wrapper at the **Android app layer** (`apps/android/src/androidMain/kotlin/phantom/android/transport/HybridRelayTransport.kt`) owns a `KtorRelayTransport` + a `RestFallbackOrchestrator` and implements the existing `RelayTransport` interface. `DefaultMessagingService` itself is **untouched** — it still talks to `RelayTransport`; `AppContainer` just hands it a `HybridRelayTransport` instead of a bare `KtorRelayTransport`. Routing: outbound `send` switches by `RestStateMachine.current` mode (WS or REST); inbound merges WS `Frame.Text` deliveries and REST `PollEnvelope` deliveries into one `SharedFlow`; `sendDeliveryAck` routes to whichever transport originally delivered the envelope. New `RestInboundDeduplicator` (commonMain, 9 unit tests) provides three-state dedup (`Emit` / `SkipNoAck` / `ReAck`) with **`pendingAck` overriding `recentlyEmitted`** so an envelope DMS is still mid-decrypt-ing never re-emits even if the recent window evicted by TTL or capacity. Three-layer duplicate protection (persistent `ProcessedEnvelopeRepository` → in-memory `RestInboundDeduplicator` → DMS's `activeProcessing`). State-machine events serialized via `stateMachineLock: Mutex` with an atomic `submitStateEvents(vararg)` batch for the WS-ACK pair (`WsFrameTextReceived + WsOutboundAckReceived`) so `WsSessionEnded` cannot race between them and revert a `WsCandidate → WsActive` commit. WS-passthrough collectors **always** start at `bootstrapAndStart()` entry regardless of capability — DMS keeps receiving WS messages even when the relay is on a pre-D0r build or `/auth/session` fails. Idempotent `wsPassthroughStarted` guard. `maybeRetryBootstrap()` triggered from `WsSessionEnded` with rate-limit 60 s so a transient `/auth/session` failure self-recovers without leaving the app permanently in WS-only mode. **Graceful degrade**: if `identity.signingPublicKeyHex == null` (Alpha 1 record mid-migration), hybrid is NOT constructed; app stays on bare WS transport and logs a warning — MigrationManager backfills on a later run.

**PR-D1b contract review took 3 rounds.** Vladislav rejected the first draft with 3 blockers (WS passthrough was gated on capability — DMS would have stopped receiving WS messages on bootstrap fail; inline dedup acked while DMS was still processing — would have broken at-least-once with the relay; state-machine events were not serialized) + a strong note (no bootstrap retry → permanent WS-only mode after transient failure) + minor (null signing key crashed instead of degrading). Round 2 fixed all five; Vladislav then caught two more (dedup's `isRecent && isPending → SkipNoAck` rule didn't cover the `!isRecent && isPending` case — a pending id whose recent entry was TTL- or capacity-evicted would re-Emit, racing DMS; and the ACK event pair wasn't atomic under the lock — `WsSessionEnded` could race between WsFrameTextReceived and WsOutboundAckReceived). Round 3 landed `isPending`-first precedence in `RestInboundDeduplicator.resolve()`, two new tests (`pending_id_after_ttl_still_skip_no_ack`, `pending_id_evicted_by_capacity_still_skip_no_ack`), `submitStateEvents(vararg)` atomic-batch helper, and idempotent `wsPassthroughStarted` guard. Vladislav approved + merged. Final stats: 9/9 `RestInboundDeduplicatorTest` green; `:shared:core:transport:jvmTest` green; `:apps:android:assembleDebug` green; CI green on `07a5a280`. Test #49 acceptance now redefined around hybrid behaviour: emulator Direct stays on WS (no `mode_switched`, no `route_send mode=REST`), Tele2 phone surfaces `mode_switched from=WS_ACTIVE to=REST_ACTIVE reason=active_outbound_threshold` after the 2-active-fail trigger, and text actually delivers in both directions through the new path.

**Review discipline that worked.** Vladislav's first review of D0r found 4 contract-level blockers I had missed when I committed the relay-builder agent's output as a checkpoint. All four (replay cache key, store sync, sealed_sender, hash function) closed in one follow-up commit plus 4 new dedicated tests. Lesson: when committing an autonomous agent's output, do a full design-review pass before declaring it ready — the agent satisfies its own definition of done, not the wider contract. Vladislav's second review of D1 caught the symmetric `PollEnvelope.sealedSenderBase64` gap (server now emits the field, client model didn't have it — would have silently dropped sealed-sender on the wire even after the SendRequest fix). Both reviews were posted as PR comments with a clear "do not merge until X" ack pattern.

**SSH delegation pattern proven again.** Per `feedback_ssh_delegation.md` rule, when design decisions depended on VPS-side state I handed Vladislav exact one-liner SSH commands with expected output. Two key uses this session: (1) `curl https://relay.phntm.pro/health` to verify the architect's assumption about `/health` existing before drafting PR-R0.3 design (returned `HTTP/2 200 48 ms`), (2) `docker logs phantom-relay --since 30m | grep -E "910be664|8e1cfd02"` to confirm whether envelopes actually reached the server during Test #48 (they didn't, with one eventual success at 06:49 UTC ~16 min after sender stopped logging — proving the persistent retry loop *can* succeed but on a randomly opening window).

**Open items handed to next session.**

- **APK build + Test #49** is the next blocking step. APK built locally on `master @ e9bdf462` ready to install. Test #49 acceptance redefined: not "Online via Direct" anymore (that's necessary but no longer sufficient), the real PASS is text phone → emu and emu → phone actually arriving through the REST fallback when the WS frame layer is degraded. Concrete log signatures: emulator Direct must show `REST_TRACE capability_enabled` but NOT `mode_switched` / `route_send mode=REST`; Tele2 phone (whenever it next reproduces the Test #48 signature) must produce `mode_switched from=WS_ACTIVE to=REST_ACTIVE reason=active_outbound_threshold` and `ack_after_save` / `ack_sent status=200`.
- **PR-D2 voice strategy** queued behind Test #49 stability. Options on the table: small-chunk audio under 4 KB body cap, or Reality routing dedicated to voice. Decided after Test #49 outcome.
- **PR-D1c network-change event** — feed `NetworkChanged` into `RestStateMachine` so a Wi-Fi → cellular handover (or back) re-evaluates mode without waiting for the next session-end. Deliberately deferred from D1b to keep the wire-up PR focused.
- **PR-R1a `TransportCapabilities`** + call gating. Parallel design track. Tor route → `calls = false, voiceMessages = false (or limited)`; Direct/Reality routes keep all three. If user presses Call while active route is Tor, the UI must say "Calls require Direct or Reality connection" rather than attempt a WebRTC session that cannot work.
- **Non-blocking follow-up:** `mirror_envelope_to_rest_store` currently writes `from: ""` regardless of whether the envelope is sealed-sender or plain. For non-sealed legacy messages on the REST path this would surface as `from: ""` in `/relay/poll` instead of the real sender. Production Alpha-2 is sealed-sender by default, so not a regression, but worth a 5-line fix when we next touch the helper.
- **Lesson saved to internal memory** (`feedback_agent_output_review.md`): autonomous-agent output is a checkpoint, not a finished PR. Always run a full contract-review pass before merge — wire contracts on both sides, crypto/auth boundaries, cross-subsystem state, "what's stored / what's deleted". Vladislav's D0r review caught 4 contract blockers I missed after relay-builder's output; his D1b review caught 5 across two rounds. Treat every agent-produced PR as input to my review, not output of it.

---



- **Goal.** Phase 1 was supposed to start with Test #6 retest on real cellular. Test #42 (2026-05-14 evening, Tele2 LTE Иркутская) instead saw the phone fall through Direct → Reality → Tor in ~2 min 20 s, with Reality probe failing at 20 s and Direct probe failing at 5 s. Today's session: figure out *why* — diagnose-before-fix per project rule, then commit on a fix. Spent the session in a steady diagnostic narrowing rather than a code session.
- **Where we started (wrong hypothesis).** Initial reading of Test #42 phone log: "Tele2 LTE Иркутская blocks Direct AND Reality, only Tor pierces TSPU." This is the same headline pattern from Test #6 (МТС on Tecno, 2026-05-05) and made Reality-first sound urgent. First architectural reaction was multi-VPS Reality pool. Vladislav reasonably asked me to verify before committing to deployment spend.
- **Server-side SSH diagnostic on `relay.phntm.pro` (read-only).** Six containers (`phantom-relay`, `phantom-xray` 8443, `phantom-caddy` 443, `phantom-tor`, `phantom-webtunnel-bridge`, `phantom-ntfy`) all healthy. Self-test `curl -X POST .../prekeys/publish` from the VPS itself returned HTTP 422 in 24 ms — server is fine, the 30 s timeout the phone sees is not a server-side hang. Xray actively listening on `0.0.0.0:8443`. So the bottleneck is somewhere on the path Tele2 LTE → Hetzner 65.108.154.152, not the application.
- **Termux curl from Vladislav's Tele2 LTE handset** (IP `91.149.117.97`, WHOIS = `T2 Russia AS41330` — same network as Test #42, confirmed):
  - `GET /health` (http2) → 200 OK in <1 s ✅
  - `GET /health` (http/1.1, `User-Agent: ktor-client` to match the app) → 200 OK ✅
  - `GET /auth/challenge?identity=<64-hex>` (http/1.1) → 200 OK with JSON ✅
  - `GET /ws` with `Connection: Upgrade, Upgrade: websocket, Sec-WebSocket-*` headers (http/1.1) → 401 Unauthorized in <1 s ✅ (401 is the expected server response for a WS handshake without a signed challenge — it proves the WebSocket endpoint is reachable and Caddy handed control to the relay).
  - Chrome on the same handset to `https://relay.phntm.pro/health` → returned a 404-style "page not found", almost certainly a Chrome auto-redirect or service-worker quirk; orthogonal to the diagnosis.
  - Net: **TSPU on Tele2 LTE Иркутская is NOT blocking our Hetzner IP**, NOT blocking TLS handshake, NOT blocking WebSocket Upgrade, NOT blocking small REST GETs. The "Direct/Reality probes fail" narrative does not match the actual network conditions on the same SIM today.
- **The actual finding — Caddy access log for the identity `d19f8018e1ddd918…` (Vladislav's phone) during the live retest (~02:40 UTC):**

  ```
  02:40:06  GET  /prekeys/status   200  1.7  ms  ✅
  02:40:38  POST /prekeys/publish  408 30.05 s   ❌  bytes_read: 0, Content-Length: 13903
  02:40:38  POST /prekeys/publish  408 30.05 s   ❌  bytes_read: 0, Content-Length: 13903   ← second parallel POST, different ephemeral port (16889 vs 16890)
  02:41:10  GET  /prekeys/status   200  1.0  ms  ✅
  ```

  The phone's OkHttp client opens the TCP/TLS connection, sends the HTTP/1.1 request headers (`Content-Length: 13903`), then **never delivers any of the 13.9 KB body**. Server reads zero body bytes and times out the request at exactly the same 30 s the client's `readTimeout` expires. Same SocketTimeoutException stack on the client (`okhttp3.internal.http1.HeadersReader.readLine` → `Http1ExchangeCodec.readResponseHeaders`) — client is blocked waiting for response headers, server is blocked waiting for request body. Classic deadlock signature for OkHttp upload-body stuck on Android mobile.
- **Architect-2 caught the misread.** I anchored on `bytes_read: 0` and proposed a TSPU "body filter" hypothesis. Architect-2 read the full signature — HTTP/1.1 stack-trace, clean 30 s duration that equals the client `readTimeout` exactly, two simultaneous POSTs from independent ephemeral ports, all other endpoints fine on the same network — and correctly identified the two real bugs:
  1. **Bug A — OkHttp HTTP/1.1 POST body upload stuck on Android mobile carrier.** Same class of failure as the OkHttp HTTP/2 stream-stuck bug PR-G4 closed (`fix/rest-http1-only-pr-g4`, #124). Pinning REST to H1.1 fixed the HTTP/2 head-of-line case; large H1.1 body upload on a mobile-carrier path hits a different latent OkHttp bug. Same hardware-stack root cause class, different surface.
  2. **Bug B — Race condition publishing in parallel from two reconnect generations.** The Caddy log shows two POSTs at the same millisecond from different ephemeral ports, same identity. Each reconnect generation re-triggers `publishPreKeys()` without checking whether a publish is already in flight. Two parallel uploads doubles the failure surface and wastes battery / bandwidth.
- **Strategic-pivot consequences (2026-05-15).** Locked in [`memory/project_strategic_pivot_2026_05_15.md`](../) (internal memory) and [`memory/diagnostic_tele2_2026_05_15.md`](../):
  - **Tor demoted to text-only emergency fallback.** Cannot carry WebRTC (no UDP through onion, 500 ms–2 s one-way latency, insufficient bandwidth for Opus); voice messages and calls require Direct or Reality. This was already implicit but is now explicit policy.
  - **Reality remains the load-bearing mobile transport for the RU user**, but multi-VPS deployment is **NOT** the right next step — the data did not actually support the "Hetzner CIDR blocked" hypothesis it would have been justified by. The cheaper-first experiments (different SNI on the same VPS, then Oracle Cloud Always Free ARM VM if needed) remain queued, but only after PR-R0 lands and we re-baseline.
  - **Phase 1 connectivity matrix PAUSED.** Collecting 5 more `Test #43..#47` data points with the same OkHttp publish bug present would have produced data we can't trust. The matrix resumes once PR-R0 + PR-Diag merge and Tele2 retest succeeds without dropping to Tor purely on publish-timeout grounds.
- **Two PRs launched (parallel, non-overlapping files).** Both via `kmp-builder` agent in background, no auto-merge — Vladislav reviews and merges.
  - **PR-R0 — prekey publish body reliability.** Dedicated `createPreKeyPublishHttpClient()` (HTTP/1.1 only, `Connection: close`, `ConnectionPool(0, 1, SECONDS)`, no request-body compression, separate connect/write/read timeouts) used by `PreKeyApiClient.publishPreKeys()` only; small GETs (`/status`, `/bundle`) keep the existing shared `createRestHttpClient()`. `kotlinx.coroutines.sync.Mutex` on the publish call so duplicate triggers from concurrent reconnect generations debounce. Retry with 500 / 1500 / 3000 ms exponential backoff on `SocketTimeoutException` / `408` / `5xx` / `Connection reset`, no retry on `400/401/403/422`. Extended `PREKEY_TRACE` logging (`prekey_publish_start | _ok | _retry | _fail_giving_up | _debounced`). Acceptance: Caddy access log must no longer show `POST /prekeys/publish bytes_read=0 status=408 duration=30s` from the app, and on Tele2 LTE prekey publish must complete without forcing fall-through to Tor purely on publish-timeout grounds.
  - **PR-Diag — transport probe step observability.** Pure logging, no behaviour change. OkHttp `EventListener` on the per-probe client emits `PROBE_TRACE probe_event kind=… event=dnsStart/connectStart/secureConnectStart/secureConnectEnd/responseHeadersStart/callEnd/…` per phase with elapsed-ms. `TransportManager.connect()` emits `chain_start / prepare_start / prepare_done / prepare_fail / probe_called / probe_returned / probe_outer_timeout / chain_attempt_success / chain_attempt_failed / chain_all_failed`. Tor / Xray state machines stream their states out under the same tag. After this lands, the next field test answers "which phase of which probe died" by reading the log instead of inferring from server-side cross-references.
- **Lesson worth keeping** (added to internal memory). When interpreting a request that timed out on a censored network, check both sides' timing carefully. `bytes_read = 0` on the server **plus** a clean duration that exactly equals the client's `readTimeout` is a **client-side bug signature**, not a network-filter signature. Network filters typically produce more chaotic timing — partial bytes, mid-stream resets, TLS handshake failures, etc. Clean numbers are the OS telling the client one story while doing another. The diagnosis cost was a roughly two-hour detour into multi-VPS deployment planning that the data did not justify — a cheap mistake here because no money was spent, but worth not repeating.
- **Unidentified MTS Irkutsk client `1b51ae78…`.** Caddy log shows this identity from IP `185.224.99.242` (PJSC MTS Irkutsk branch, AS8359) doing `GET /auth/challenge` every ~30 s through HTTP/1.1 Direct, stable for hours. Not one of Vladislav's current test devices. Two possible explanations: an old PHANTOM install on a forgotten device of Vladislav's that retained the SQLCipher identity, or a real-world third-party user that found the build somewhere. Filed as low-priority follow-up to confirm via the relay's JSONL prekey records and either ack or quietly investigate further; **no action that affects this user** until we know which it is.
- **Process notes.**
  - Phase 0 (repo hygiene, seven PRs #135 → #141) is closed and durables are in `master`. Phase 1 was about to start when Test #42 surfaced this. The pause buys us a real bug fix, not lost time.
  - Two architect voices contributed independent reads — both useful, both somewhat wrong in places, both right in the convergent answer. Architect-1 originally flagged the REST-clearnet routing issue; that observation is correct but is the *PR-H1f* concern, not the *PR-R0* fix landing now. Architect-2 read the data tighter and identified Bugs A + B, which is the right scope for the immediate PR.
  - Strategic pivot decision lives in [`memory/project_strategic_pivot_2026_05_15.md`](../); the data-driven correction to that pivot lives in [`memory/diagnostic_tele2_2026_05_15.md`](../). The pivot's *direction* (Tor demoted, Reality for mobile, no calls over Tor) survives. The pivot's *first-action item* changed from "deploy multi-VPS pool" to "fix the OkHttp publish bug first; the data says infra is not the bottleneck yet".
- **Merge update (later same session, 2026-05-15 evening).** Both PRs merged into `master` after first-round CI fix-ups:
  - **PR-Diag — squash-merged as `f3925cac` (#143).** First CI failed with two `e: ... deprecated. moved to val.` errors in `KtorTransportProbe.kt` for `handshake.tlsVersion()` and `handshake.cipherSuite()` (these moved from functions to `val` properties in the OkHttp version pinned by this repo). Follow-up commit `999d18c3` switched both call sites to property access. CI passed second try; squash-merged green.
  - **PR-R0 — squash-merged as `817331a7` (#144).** First CI failed with four Kotlin compile errors in `PreKeyPublishReliabilityTest.kt`: `Unresolved reference 'currentTime'` (×2) and `Argument type mismatch: actual type is 'Long', but 'Double' was expected` (×2). Root cause: `runTest { currentTime }` is provided by an extension that the project's `kotlinx-coroutines-test` version doesn't expose directly, and the 3-arg `assertEquals(Long, Long, String)` call was ambiguous with the `assertEquals(Double, Double, Double, String?)` overload. Follow-up commit `eb0a9c87` switched the two `currentTime` reads to `testScheduler.currentTime` (the always-present underlying `TestCoroutineScheduler` property) and added explicit named `expected = / actual = / message =` parameters on both `assertEquals` calls plus an explicit `Long` type on the `sumOf { … }` result so the generic overload binds cleanly. CI passed second try; squash-merged green.
  - **Repo-hygiene side note.** Mid-session `git add -A` after an inline edit briefly pulled all the local untracked dev-machine junk (`Design/`, `Fonts/`, `CLAUDE.md`, `Releases/ToS/…`, `libXray-main-1/`, two PDFs) into the staged set. `git push --force-with-lease` rejected the push as `stale info` and saved the PR from getting 204-files-/-20K-line bloat. After that, every amend used `git add <explicit path>` instead. CLAUDE.md already advises against `add -A`; logged here as a real example of why.
  - **Author identity reconciled.** Earlier commits in the session were authored as `WladislaWLE <felixandterror@gmail.com>` (machine default), while the rest of the repo's recent history uses `Vladislav.L <60427218+LiudvigVladislav@users.noreply.github.com>` so GitHub displays the `LiudvigVladislav` handle. Updated local `git config` mid-session and amended every commit Vladislav was about to merge so the GitHub PR pages and final squash commits all show the same author. CLAUDE.md's "Author identity: Vladislav (`WladislaWLE`)" line is now stale — update there is non-urgent but should happen sometime.
- **What's next (locked at end of this session).** In rough priority order:
  1. **Tele2 LTE Иркутская retest** on the Tecno handset Vladislav owns. Acceptance for PR-R0 is concrete and Caddy-loggable: zero `POST /prekeys/publish` with `bytes_read=0 status=408 duration=30s` from the app's identity for the duration of a fresh session, AND the route-selection logic does NOT drop down to Tor purely because publish kept timing out. PR-Diag's `PROBE_TRACE` lines will make any residual failure cheap to diagnose without SSH.
  2. **`MASTER_TIMELINE_2026.md`** updated in the same commit family — `Last updated` line bumped to 2026-05-15, the headline paragraph extended with the Tele2 session findings, strategic-pivot bullet added.
  3. **PR-R1a — `TransportCapabilities` + call gating.** Parallel design track. Tor route → `calls = false, voiceMessages = false (or limited)`; Direct/Reality routes keep all three. If user presses Call while active route is Tor, the UI must say "Calls require Direct or Reality connection. PHANTOM is on text-only Tor fallback." rather than attempt a WebRTC session that cannot work. Design first, no code until the retest confirms PR-R0 holds in the field.
  4. **PR-H1f — REST through active SOCKS proxy.** Defer behind PR-R0 validation. If PR-R0's dedicated client makes publish succeed reliably on Tele2 LTE, the REST-clearnet routing problem becomes a *defence-in-depth* concern rather than a *fix-or-it-doesn't-work* concern. Architect-1's observation is still real; it's just no longer the bottleneck.
  5. **Phase 1 connectivity matrix (Tests #43–#47)** remains **paused** until the Tele2 retest succeeds. The matrix collects data that gets attached to the public `CONNECTIVITY_MATRIX.md` artifact, and collecting that data with a known publish bug present would just produce data we cannot trust.
  6. **Unidentified MTS Irkutsk client `1b51ae78…`** — low-priority follow-up. Confirm via the relay's JSONL prekey records (`services/relay/data/prekeys.jsonl` or wherever the live one is on the VPS) whether this identity is one of Vladislav's old installs or a real third-party user. No action that affects this user until we know which.
  7. **Multi-VPS Reality pool** is **NOT** on the queue. Data did not support that hypothesis. If a future Tele2 / other-carrier retest produces a *different* signature than today's (i.e. one that genuinely points at IP / ASN filtering rather than client-side OkHttp), reopen the discussion then. Free first stop if it does reopen: Oracle Cloud Always Free ARM VM, not paid Hetzner.

### 2026-05-14 (wed) · H1e diagnostic sprint closed — PR #134 merged, repo audit + Phase 0 plan locked

- **Goal.** Take the WS-cycle root-cause question deferred at the end of the H1 line ("why does the wire die every 30–60 s on both МТС cellular **and** emulator-on-dev-Wi-Fi — paths that share no carrier") through a controlled four-run diagnostic on the heartbeat layers, pick the production-best config, ship it on master, then step back and audit the repo as a whole before opening any new sprint. NLnet remains off the table; the working frame is "quality > speed, honest > polished" — no fixed deadlines.
- **PR-H1e diagnostic sprint — four runs on `diag/h1e-ws-ping-experiments`.** Three feature flags added in `RelayTransportConfig.kt` so a single APK build could be attributed via a `transport_diag` log line: `EXPERIMENTAL_WS_PING_INTERVAL_MS`, `EXPERIMENTAL_DISABLE_APP_PING`, `EXPERIMENTAL_DISABLE_ALARM_RECONNECT`. `H1E_MARK` timeline markers (reconnect_start / reconnect_success / ws_ping_timeout) added in `KtorRelayTransport.kt` for tcpdump-pcap correlation. Each run = APK install on Tecno phone (`103603734A004351`) + emulator (`emulator-5554`), tag-filtered logcat capture to `C:\temp\test38-*.log` … `test41-*.log` for ≥12 reconnect cycles per device.

  | Run | ws_ping | app_ping | Phone avg | Emu avg | Dominant failure |
  |---|---|---|---|---|---|
  | Run 0  | 15 s | on  | 46.5 s | 39.5 s | mixed (pong-timeout + AlarmManager) |
  | Run B  | 5 s  | on  | 21.8 s | 29.5 s | shorter cycle |
  | Run C  | 15 s | **off** | **72.0 s** | **56.4 s** | OkHttp ping timeout (rare) — winner |
  | Run B+C | 5 s | off | 30.9 s | 29.6 s | OkHttp ping timeout (every ~30 s) |

  Two independent findings, both with paired controls: (1) suppressing the app-level RelayMessage.Ping/Pong loop roughly doubles WS lifetime (Run 0 → Run C); (2) a 5 s OkHttp WS-protocol Ping is itself a kill trigger — the tighter pong window plus the cadence override the gain from (1) (Run B and Run B+C both ≈ 20–30 s).
- **PR #134 — production policy = Run C.** Squash-merge of `diag/h1e-ws-ping-experiments` into master as `bcc501be`. Diff vs master: 3 files changed, +63 / −29. Net effect: `APP_LEVEL_PING_ENABLED = false` new const in `RelayTransportConfig.kt`; OkHttp WS Ping hard-coded to 15 000 ms in `RelayTransportFactory.kt`; AlarmManager proactive reconnect stays enabled (the `EXPERIMENTAL_DISABLE_ALARM_RECONNECT` branch in `PhantomWakeupReceiver.kt` was diagnostic-only and is removed with a net-zero diff vs master). Diagnostic artefacts removed wholesale: all three `EXPERIMENTAL_*` flags, the `transport_diag` connect-line, all three `H1E_MARK` markers. Test #41 (Run B+C) confirmed zero MAC errors / zero ledger-dedup misses / correct FIFO flush across all 12 reconnects on each device — the recovery side of the stack is unchanged by this PR; only the heartbeat policy changes. The diag branch is kept on origin as a reproducible archive of all four runs.
- **Tech debt closed alongside.** Run C is now the lowest-noise WS config we have ever shipped — Phone 72 s average lifetime on Tecno МТС cellular without VPN, which is roughly double the pre-H1e baseline. Architect concurred independently after seeing the four-run table.
- **Repo audit kicked off in parallel with the merge.** With NLnet off the table and the active funding routes being OTF Internet Freedom Fund (submission #22145, ~6–8 weeks to first response) plus FUTO Microgrant (submitted 2026-05-01, ~2–8 weeks), the question shifted from "what is the next sprint" to "is the repo honest and clean enough for a reviewer to walk in cold". Four parallel Explore agents covered (a) repo hygiene + secrets, (b) per-track status across Reliability / Security / Grant-Readiness / Alpha-2 Features, (c) connectivity matrix VPN × no-VPN × transport mode, (d) code-health drift. Cross-checked their reports against `MASTER_TIMELINE_2026.md` myself rather than trusting agent claims unverified (agent B was over-optimistic in tone — corrected). Headline findings:
  - **Public-vs-internal split.** Zero secrets in tracked files, `.gitignore` comprehensive, no grant-mentions leaked into `README.md` / `ARCHITECTURE.md` / ADRs / Threat Model. One stale untracked typo file (`-remote --tags origin v0.1.0-alpha.2`) pollutes `git status` — local-delete only, not in repo. ~21 of the 25 remote branches are either fully merged or abandoned and can be pruned; one (`fix/transport-tcp-keepalive`, 3 commits ahead) needs a diff before delete.
  - **Documentation-vs-code drift on TCP keepalive.** PR-H1c shipped `TCP_KEEPALIVE_IDLE_SECONDS / INTERVAL / COUNT` constants in `RelayTransportConfig.kt` and the server-side socket2 keepalive in `services/relay/src/main.rs`, but **did not** ship the client-side `KeepAliveSocketFactory.kt` that the comment block claims applies the constants on the wire. The comment is currently misleading — to be fixed in Phase 0 PR (a). The `KeepAliveSocketFactory.kt` lives on `fix/transport-tcp-keepalive` (153 lines) and will be either cherry-picked (variant A) or formally dropped (variant B) after Phase 1 connectivity testing yields data on whether OkHttp WS Ping + AlarmManager already cover the case.
  - **FCM contradicts the "no Big Tech metadata" pitch and is currently dormant.** `PhantomFirebaseMessagingService` exists in code with a TODO to register tokens with the relay; google-services plugin is commented out. Vladislav confirmed full removal in Phase 0 PR (b) — class, Manifest entry, plugin references, `libs.versions.toml` line — all to be deleted. UnifiedPush remains the replacement target for Phase 5.
  - **libsignal-client placeholder.** Still in `libs.versions.toml` as a commented-out dependency with "AGPL legal review required" note, despite PHANTOM being committed to custom X3DH / DR / SenderKey on libsodium permanently. The placeholder creates an ongoing question that does not match reality and is to be removed in Phase 0 PR (a).
  - **ADR housekeeping.** ADR-010 + ADR-013 are still marked `proposed` even though their problem statements were closed by PR-H1c + PR-H1e via an alternative approach (inbound-frame liveness + AlarmManager + WS Ping, not the originally-proposed `connectionPool.evictAll()`). ADR-017 number collision: SenderKey signing removal vs Threat Model revision share the slot — Vladislav's call is "SenderKey signing stays ADR-017, Threat Model revision moves to ADR-018". `RECONNECT_MAX_ATTEMPTS @Deprecated` has zero callers and can be removed.
  - **Internal-vs-public docs split.** `docs/project/ACTIVE_SPRINT.md` + `EXECUTION_PLAN_*.md` sit in the public repo as sprint-level scratchpad. Hybrid resolution: move ~70 % (sprint execution, daily notes) to `docs/internal/` under `.gitignore`; keep ~30 % (high-level milestone docs, `MASTER_TIMELINE_2026.md`, `ROADMAP.md`) public so grant reviewers see active project management without reading raw planning chatter.
- **4-phase plan locked for the next 4–6 weeks.**
  - **Phase 0 (this week)** — repo hygiene, three PRs sequenced (a) chore: ADR statuses + remove `@Deprecated` + drop libsignal mention + honest TCP-keepalive comment, (b) chore: remove FCM entirely, (c) docs: internal split + branch prune (21 deletes; keep `fix/transport-tcp-keepalive` until Phase 1 decision). PROJECT_LOG + MASTER_TIMELINE follow-up for PR #134 (this entry) ships **before** the three Phase 0 PRs so the durables are accurate before PR (c) reshuffles `docs/project/`.
  - **Phase 1 (week 2–3)** — honest connectivity baseline. Re-verify Test #6 (Tor on МТС no-VPN) across 3–5 sessions. Run `PRIVACY_MODE_BEHAVIOR.md` Test 15–22 matrix (Standard/Private/Ghost × mode-switch × network-change × Ghost AllFailed) on both devices. Vladislav's explicit addition: prove text messaging is reliable across the matrix, look for further-improvement opportunities, then start voice-message reliability work as the next step. Output: `docs/CONNECTIVITY_MATRIX.md` as a public credibility artifact + TCP-keepalive decision (variant A vs B) by data, not intuition.
  - **Phase 2 (week 4–7)** — voice + calls product quality. Voice MIA retest with the post-H1e transport, PR 2.6 calls audio plumbing (`JavaAudioDeviceModule` + AudioFocus + suppress reconnect during active call), iOS SQLCipher decision (wire it or explicitly document iOS-DB-unencrypted-at-rest in current build), test-coverage gap fill for `PhantomWakeupReceiver` + `TransportManager` mode-switching. OTF response expected during this window.
  - **Phase 3 (week 8+)** — first Alpha-2 feature. Pluggable transports / obfs4 picked over attachments / groups / channels / username directory because of strict alignment with the active OTF censorship-resistance pitch (ADR-015 draft already exists). Other Track D items remain in queue.
- **Strategic decisions locked (Vladislav, 2026-05-14).**
  - Reality+VPN = design, not bug. PR-A2 filter stays. Document "Reality is the no-VPN transport; under system VPN, fallback is Tor" in ARCHITECTURE.md + Settings UI + Threat Model. No PR-A3 research investment unless real user complaints arrive.
  - iOS = Beta milestone, contingent on OTF funding ($40 K → contractor or rented Mac infra). Maintain KMP `iosMain` stubs only; no ad-hoc iOS work.
  - FCM removed entirely from codebase. UnifiedPush is the replacement, scheduled for Phase 5 (~Feb 2027).
  - libsignal-client never integrated. Custom libsodium crypto is the differentiation. Remove placeholder from `libs.versions.toml`.
  - `docs/CONNECTIVITY_MATRIX.md` will be a public artifact (not internal) once Phase 1 testing produces the data — citable, honest, with explicit "untested" cells where applicable.
  - Branch prune policy: 0–2 commits ahead of master → delete blind; 3+ commits ahead → ALWAYS diff first + 1–2-paragraph summary before delete. The `fix/transport-tcp-keepalive` find (a real client-side TCP-keepalive SocketFactory not on master, despite comments claiming it is) is the prototype case for why this rule earns its keep.
- **Engineering cadence agreed.** Sequential engineering phases. Funding outreach (Phase 4) runs in parallel because it is async email / application work that does not consume engineering time. No artificial deadlines; each phase ships when ready, not when a calendar says so.

### 2026-05-13 (tue) · First-message yellow-dot hunt — PR-G3 prekey tracing + PR-G4 REST forced HTTP/1.1

- **Goal.** Test #28 (2026-05-12) reproduced the recurring "yellow dot
  for two minutes on first message to a fresh contact" symptom.
  Earlier hypotheses (timing race during peer onboarding, retry
  cadence too slow, prekey lifecycle never publishing) were all
  rejected once PR-G3 telemetry landed and gave us a clean signal.
- **PR #123 `fix/prekey-publish-tracing-pr-g3`** — observability-only
  diagnostic patch (no behaviour change). Added structured
  `PREKEY_TRACE` logs at every stage of the prekey lifecycle:
  - `PreKeyLifecycleService` — `bootstrap_start/skip/done`,
    `upload_start/ok/fail` with elapsed-ms and reason tag,
    `verify_start/status/republish_triggered`.
  - `PreKeyApiClient` — `http_publish_start/done/fail`,
    `http_bundle_fetch_start/done/fail`, `http_status_start/done/fail`
    with the actual URL and HTTP status code.
  - `DefaultMessagingService` — bundle-fetch path now distinguishes
    `prekey_fetch_result=200|404|timeout|429|http<code>` with
    elapsed-ms; the previous WARN collapsed every failure mode into
    "treated as 404".
- **What the new traces revealed in Test #29.** With a clean wipe and
  emu started before phone, the phone's bundle fetch for the emu
  identity timed out at exactly 8009 ms × 4 in a row; emu's own
  publish hung 20.7 s then 39.5 s before `SocketException: Connection
  reset` from `Http2Reader.readConnectionPreface`. **Yet** the relay
  side's `verify_status` returned `opks_remaining=100` for the same
  identity within 17 s, and a `curl` from the VPS itself fetched the
  bundle in 242 ms (HTTP 200, 634 bytes). Server was healthy
  (`docker stats`: 0.01 % CPU, 2.1 MiB RAM). Caddy access log showed
  the phone's failed bundle GETs **not arriving at Caddy at all** —
  the requests died inside OkHttp before any bytes hit the wire.
  Failed POST publishes from emu were logged with `bytes_read: 0`
  after 30 s and `408 Request Timeout`: client opened the H2 stream,
  sent headers (`Content-Length: 13903`), then never delivered the
  body. **Root cause: OkHttp's HTTP/2 implementation gets stuck on
  REST requests** — both on stream upload (server sees no body) and
  on stale-connection reuse from the pool (request never sent).
- **PR #124 `fix/rest-http1-only-pr-g4`** — one-line behavioural fix:
  pin the REST `OkHttpClient` to `Protocol.HTTP_1_1` only. The WS
  factory was already pinned to H1.1 (Upgrade requires it). iOS uses
  Darwin (URLSession), JVM uses Ktor defaults, both unaffected. Caddy
  speaks both H1.1 and H2; access logs show many H1.1 REST clients
  completing in <2 ms — there is no throughput cost at our request
  volume. Trades H2 multiplexing we do not need for reliability we
  do; if/when we revisit H2 the fix is upstream in OkHttp / Ktor's
  okhttp engine, not patchable from app code.
- **Test #30 (post-PR-G4 retest).**
  - Emu publish bootstrap: **829 ms → 201** (Test #29: 20.7 s + 39.5 s
    timeouts).
  - Phone publish bootstrap: **541 ms → 201** (was already fine on H2,
    still fine on H1.1).
  - Phone fetch emu bundle on first message: **151 ms → 200 OK on the
    first try** (Test #29: 8009 ms timeout × 4, then a sweep eventually
    succeeded ~3 minutes later).
  - First message envelope sent within **484 ms of `send_start`**, no
    DEFERRED, no WAITING. Yellow-dot reproduction step is gone.
- **What is left as tech debt** (not blockers, scoped out of PR-G4):
  - **WS pong timeout cycle every 60–110 s** on both devices. Pong
    drift escalates only after the first envelope is sent (`pong fresh
    9962 ms` → user sends → `pong fresh 40288 ms` → `Pong timeout
    69914 ms`), then `forceReconnect`. Auto-recovery works (re-queue
    + flush + ACK ~200 ms post-reconnect), so messages still arrive,
    but the user sees momentary "lagging" UI. Open as PR-H1
    (WebSocket heartbeat hardening).
  - **One stale-H1.1 case on `verify_status`** (30 053 ms
    `SocketTimeoutException` from `Http1ExchangeCodec`,
    `Caused by: SocketException: Socket closed`). Background path,
    happened once per six-minute test, recovers on next reconnect.
    Low priority — connection-pool tuning, not a behavioural bug.
  - **PR-G5 (UX text + bootstrap gate + fast retry)** proposed by the
    architect earlier — now genuinely cosmetic since PR-G4 fixed the
    underlying race. Deferred until WS stability lands.
- **Process notes.**
  - The architect initially attributed the failure to a peer-publish
    timing race ("recipient publishes ~10 s after sender fetches,
    retry sweep delivers later") and recommended PR-G4 as a fast-retry
    cadence (5 → 10 → 20 → 40 → 60 s). The PR-G3 traces and Caddy
    access logs disproved this: bundle was on the relay before the
    phone's first send, and the relay never saw the failed fetches.
    Recorded as a confirmation of the durable feedback rule
    "diagnose first, do not fix from architectural intuition alone."
  - PR-G3 originally pushed with two files instead of the three
    promised; user caught it, fix-up commit added the third file
    (`PreKeyApiClient.kt`) before the PR went out.
  - PR-G3 first push was stacked on a stale local PR-G1 commit
    (`f373bf44`); GitHub had already squash-merged G1 under a new SHA.
    Rebased `--onto origin/master` to drop the duplicate before PR
    review.
- **Memory + plan implications.**
  - `feedback_seven_times_measure.md` reinforced — PR-G4 flagged a
    diagnose-only retro for one missed pre-push check (PR base
    branch). The 7-point ritual stays mandatory.
  - NLnet is still off the table (decided 2026-05-12); these PRs are
    pure reliability work for other grants and the eventual public
    release.
  - APK output path standardised: `apps/android/build/outputs/apk/debug/`
    is the single source for all in-flight test builds. `Releases/`
    is reserved for signed release builds only.

### 2026-05-13 (tue night) · H1 line closed — PR-H1b + PR-H1c merged, Test #37 verifies stale-socket recovery

- **Goal.** Take the WS pong-timeout cycle (60–110 s on both devices, deferred from the H2 closure entry below) from "auto-recovery works but UI lags" through to a real fix, then verify on real-device twin-emulator + Tecno МТС. Two PRs landed in sequence: PR-H1b (#131, `0baa4196`) for diagnose-only observability, then PR-H1c (#132, `e946caba`) for the actual recovery fix.
- **PR-H1b — diagnose-only WS close-path attribution (#131, `0baa4196`).** Per-session `SessionStats` counter struct on the client (pings_sent, pongs_received, missed_pongs, ping_send_failures, inbound_frames, delivers_received, acks_received, since_last_*_ms). Server-side mirror via per-`conn_id` counter map plus a new `event="session_summary"` log line emitted at unregister with `close_origin` ∈ {"client", "server", "error", "none"}, `close_code`, `close_reason`, `close_error`. Added `closeReason.await()` extraction in the Ktor `webSocket{}` block so we surface the exact close cause. Wire format unchanged; relay required redeploy (done same session). Architect's own diagnose-first push earned its keep — without H1b's data we'd have shipped H1c blind.
- **What Test #35 (post-H1b) revealed.** Server `pings_received=2-5` vs client `pings_sent=11` over 158-s sessions; pong RTT 110 ms right up to instant death; `since_last_ping_ms ≈ 153–160 s` on every `session_summary`. Classic half-open TCP black-hole — middlebox NAT eviction (МТС) + Tecno HiOS aggressive radio park silently drop outbound packets while OS still considers the socket healthy. Architect proposed PONG_TIMEOUT 60 → 90/120 s as mitigation; I pushed back with the data — death is instant not gradual, longer tolerance widens lag without solving root cause. Vladislav agreed; finalised the 6-point H1c scope.
- **PR-H1c — stale-socket recovery (#132, `e946caba`).** Six layers, all in one merge:
  1. **Inbound liveness** (`lastInboundFrameElapsedMs` on the `RelayTransport` interface). The dead-socket watchdog now triggers off ANY inbound frame (Deliver / Ack / Pong / malformed) instead of only Pong — removes a future false-positive class under Tor / Reality where Pong-routing might be selectively dropped while envelope traffic still flows.
  2. **OkHttp WS-protocol Ping at 15 s.** Was `pingInterval(0)` (disabled); now `pingInterval(15, SECONDS)`. OkHttp itself raises `SocketTimeoutException: sent ping but didn't receive pong within 15000ms` when the wire dies, **independent of our app-level Ping**. Test #37 confirmed this is the path that fires first (every 30–46 s).
  3. **`ping_send_failed` → `forceReconnect()` + `break`** (was: log and continue loop). When the OkHttp dispatcher returns `false` from `sendRaw`, the session is dead; abandoning the loop immediately is strictly better than hoping it recovers.
  4. **`TransportState.Reconnecting`** as a distinct state between `Disconnected` and `Connected`. UI can render a soft "Reconnecting…" badge instead of going dark — the connection banner is wired up.
  5. **Proactive AlarmManager reconnect** (`ALARM_STALE_RECONNECT_MS = 45 000`, < `DEAD_SOCKET_TIMEOUT_MS = 60 000`). The wakeup receiver now compares `lastInboundFrameElapsedMs` and forces a reconnect 25 s **before** the in-process pong watchdog would. In Test #37 OkHttp's own 15 s WS Ping fired first, so this path stayed dormant — but it's the safety net for OEMs where AlarmManager is throttled later.
  6. **Server-side TCP SO_KEEPALIVE on listener sockets** (idle 15 s, interval 5 s, retries 3 → ~30 s wall-clock dead-socket detection). Linux-only via `socket2`'s `set_tcp_keepalive` cfg-gated to `#[cfg(unix)]`; Windows host stays on kernel defaults. Production deploy is Linux container, so server-side kicks in there.
- **Test #37 result (post-PR-H1c, twin-emulator + Tecno МТС, 12 sequential text messages each direction).** Detection time on both devices dropped from ~155 s (Test #35 baseline) to **30–46 s**. Recovery time dropped from ~5 s to **~1 s**. Zero message loss across all reconnect cycles — H2b's processed-envelope ledger fired correctly on re-delivered envelopes (visible as `Duplicate envelope (already in ledger)` log lines, e.g. `0417acfd...` survived a session-3 → session-4 transition without double-decrypt). Reconnect path: OkHttp WS Ping → `SocketTimeoutException` after 15 s of no pong → `ping_send_failed` → `forceReconnect()` → new session in ~1 s → re-queue from `pendingOutbox` sorted by `sequenceTs` (PR-H2a) → flush → ack arrives ~100 ms later. Net UX: momentary "Reconnecting…" badge instead of "lagging" — visible as routine churn rather than an outage. Server-side `session_summary` lines still show `since_last_ping_ms ≈ 153 s` for old sessions because the abandoned TCP socket stays "live" from the kernel's perspective until kernel keepalive surfaces RST — that's zombie hygiene, not a UX defect, and is the proposed PR-H1d follow-up (deferred — see below).
- **Why H1c was shipped before tcpdump root-cause work.** The middlebox is killing the wire every 30–60 s on both МТС cellular AND emulator-on-dev-Wi-Fi — same cycle on environments that share no common carrier path. That points at something in our stack (OkHttp connection pool? TLS renegotiation? HTTP/2 vs HTTP/1.1 WS upgrade?) but the recovery mitigation is independent of that diagnosis. H1c makes the symptom invisible to the user; PR-H1e (diagnostic sprint) will go after the actual cause.
- **Process notes.**
  - Architect's diagnose-first push for H1b was the right call — without per-conn_id `session_summary` lines I'd have shipped a blind heartbeat-tweak fix and missed the half-open pattern entirely.
  - Architect proposed PONG_TIMEOUT widening (60 → 120 s) as the H1c approach; I pushed back with the H1b data, Vladislav backed the data-driven counter-proposal. Recorded as a confirmation that "diagnose first" applies to architect proposals too, not just my own intuition.
  - APK install no-op caught: after PR-H1c push, Test #36 first attempt showed wakeup logs in OLD format (`pong fresh` instead of `wire fresh`) — `adb install -r` is a no-op when versionCode is unchanged. Recommended `adb install -r -d` (downgrade-allowed reinstall) for future iterations on the same versionCode. Test #36 second attempt (with `-d`) produced clean H1c traces; Test #37 confirmed end-to-end.
  - Vladislav explicit feedback recorded: "Зачем ты мне даешь push command, когда это должен делать ты?!" — push/commit/deploy commands now executed by me via Bash tool, not handed to Vladislav as copy-paste shell guides. Only PR URL + Title/Body for copy-paste remains (GitHub UI). Saved as `feedback_execute_dont_dictate.md`.
- **Deferred follow-ups.**
  - **PR-H1d (server-side superseded-close + client-side explicit close)** — proposed by the architect for zombie-session hygiene. **Deferred** by Vladislav decision: H1d improves operational logs/FD pressure but doesn't change user-visible UX (reconnect already 1 s, messages already 100 % delivered, ledger already neutralises duplicates). Will be reconsidered if (a) PR-H1e research finds the cycle is unfixable at root and reconnect storms become permanent architecture reality, or (b) relay starts suffering FD/memory pressure under load.
  - **PR-H1e (root-cause research sprint)** — next priority after this entry. Architect-proposed scope: (A) tcpdump on relay during one dying session — capture FIN / RST / outbound Ping packets / TLS close_notify; (B) WS pingInterval 5 s experiment — does middlebox count 15 s as idle?; (C) disable app-level Ping temporarily, leave only OkHttp WS Ping — four heartbeat layers may trigger DPI as anomaly; (D) force HTTP/1.1 only on WS upgrade (`.protocols(listOf(Protocol.HTTP_1_1))`) — same hypothesis class that fixed REST in PR-G4. Diagnostic, not a fix sprint.
  - **Sprint Voice (post-H1e)** — voice messages MIA bug from the QA-v10 Alpha 1 backlog. Transport is now resilient enough to build voice-chunk streaming on top.

### 2026-05-13 (tue evening) · H2 line closed — PR-H2a + PR-H2a.2 + PR-H2b merged, Test #34 → ledger guard

- **Goal.** Take PR-H2a from "open, awaiting Test #33 retest" through to merge, then react to whatever real-device testing surfaced. Close the H2 line cleanly so reliability work can move on to PR-H1b/c (heartbeat hardening) or back into Track A backlog.
- **PR-H2a merge sequence (`674ce231` master, #127).** Branch landed unchanged from the afternoon push — strict FIFO outbox via per-envelope monotonic `sequenceTs` plus `outboundSendMutex` serializing live-send vs flush. Wire format unchanged (server required no redeploy).
- **7-times-measure caught H2a.2 race before push.** Re-reading `KtorRelayTransport.flushPendingOutbox` after the H2a merge surfaced a leftover race window: `outboundSendMutex` was held only across the snapshot+clear of the outbox, not across the actual `Send` loop. A fresh live `send()` could observe the cleared outbox and race onto the wire with a higher `sequenceTs` than envelopes still being flushed. PR-H2a.2 (`72e59ce9`, #128) extends the mutex hold to span the entire flush-Send loop. Pings and ack-deliveries still bypass (orthogonal to per-conversation ratchet ordering). One additional unit test pinned the new invariant.
- **Test #34 (post-H2a/H2a.2 retest, 10 sequential text messages, both directions).** Wire-reorder MAC failures **gone** — all 10+ text messages delivered AND visible in UI on both devices. But the test surfaced **two MAC errors on read-receipt envelopes** (`5b5c4faa` on phone, `88c27761` on emu). Root cause matched the architect's diagnosis on first read: first decrypt advanced the ratchet chain, ack-deliver was lost when the WS reconnected mid-write, the relay re-delivered the same envelope, the second decrypt MAC-failed because the chain had already advanced.
- **Why the existing duplicate guard didn't catch it.** `messageRepository.getMessageById(...)` only catches user-message envelopes — read-receipts and other control payloads are never inserted into the `messages` table, so they bypassed the guard entirely. This was a structural blind spot, not a regression.
- **PR-H2b — processed-envelope ledger (`7008cf3e`, #129).** New `processed_envelopes` table records every decrypted envelope id regardless of payload type. Guard runs **before** `ratchet.decrypt`, so a redelivered envelope cannot advance the chain a second time. INSERT OR IGNORE for race-safety under concurrent receive coroutines. 8-day TTL sweep (one day longer than the 7-day relay store) inside the existing 24h ticker in `AppContainer`. Marks `PROCESSED` on success and `FAILED_MAC` on decrypt failure (debug column only — no UI surface, per Vladislav's variant-C decision).
- **Migration policy — forward-only N→N+1 (Vladislav decision).** `15.sqm` is CREATE TABLE only, no destructive ops, no `pm clear` required. SQLDelight auto-detected schema version bumped 15 → 16; the table appears in both `Schema.create()` (fresh installs) and `migrateInternal` (upgrade path) of the generated `PhantomDatabaseImpl`.
- **Tests.**
  - `ProcessedEnvelopeRepositoryContractTest` — 6 tests covering `exists`, INSERT OR IGNORE idempotency (second `markProcessed` for the same id is a no-op even with different status/payloadType), TTL sweep boundary, GROUP BY status, `deleteAll`. Run against `FakeProcessedEnvelopeRepository` (in-memory mirror of the SQLDelight impl — same `putIfAbsent` semantics).
  - `DefaultMessagingServiceTest` — 2 new tests: redelivered envelope id with a pre-seeded ledger entry skips decrypt and still acks (relay must drop from store); fresh envelope id passes the guard cleanly. The `FakeRelayTransport.incoming` had to be upgraded from `emptyFlow()` to a `MutableSharedFlow` to actually drive deliveries through the receive coroutine; `buildService()` grew an optional `scope` parameter so idempotency tests can pass `backgroundScope` (the `launchIn(scope)` collector would otherwise leak past the test body and trip `UncompletedCoroutinesError`).
  - All 50 messaging-module JVM tests + 6 storage contract tests green.
- **Process notes.**
  - 7-times-measure earned its keep again — the H2a.2 race would have been a same-day regression had we shipped H2a alone.
  - Architect dialogue stayed predictive: the read-receipt-bypasses-guard diagnosis matched what I'd reproduced from the Test #34 logs before the architect message arrived. No wasted PRs.
  - Defence-in-depth: legacy `messages.id` guard kept after the new ledger guard. The two guards are non-redundant in the first 8 days post-deploy (ledger TTL hasn't accumulated history yet on fresh installs); after that the ledger is sufficient and `messages.id` becomes practically unreachable, but there's no rush to remove it.
  - Branch was `feat/processed-envelope-ledger-pr-h2b`. Solo-author per the durable feedback; `🤖 Generated with [Claude Code]` footer omitted.

### 2026-05-13 (tue afternoon) · WS observability PR-H1a + Test #31/#32/#33 + PR-H2a strict FIFO outbox

- **Goal.** Closing PR-G4 fixed first-message yellow-dot, but Test #30
  exposed a separate WS-side instability: pong-timeout / forceReconnect
  cycle every ~70 s with envelope reorder symptoms suspected. PR-H1
  was originally scoped as "WS heartbeat hardening"; before patching
  blind we shipped PR-H1a (observability-only) and ran three tests to
  pinpoint the actual failure mode.
- **PR-H1a (commit `3db97b49`, master `6d7d43aa`)** —
  per-WS-session epoch tagging client-side + `conn_id` + `ack_deliver_*`
  trace lines server-side. Every relay log line now carries
  `[gen=N s=M] / conn_id=K`, letting us correlate which WS session
  emitted each ping/ack/envelope and whether ack-deliver frames reach
  the relay on the right `conn_id`. Wire format unchanged; relay
  required redeploy (done same session).
- **Test #31 result (PR-H1a client-only).** Tags ruled out the architect's
  "zombie writer" hypothesis: every client-side `ack_deliver_send` and
  `ping_send` carried the current `[gen s]`, no stale generation traffic
  observed. Server log was still in old format → server-side PR-H1a not
  redeployed yet. Findings ambiguous without server-side `conn_id`.
- **Test #32 (after VPS redeploy of PR-H1a).** Three text messages
  delivered + acked end-to-end with `ack_deliver_received` matching the
  expected `conn_id`. **But two messages MAC-failed on receiver** with
  `Permanent decrypt failure`, which started looking less like transport
  and more like Double Ratchet divergence. Initial hypothesis: app-restart
  race in `save_session`.
- **Test #33 (clean Test #33 protocol — wipe both DBs, restart relay,
  10 sequential messages, no app restarts, no voice burst).** Reproduced
  the MAC-fail cleanly on second exchange. Trace pinpointed the cause:
  - phone encrypted read receipt at chain pos N (01:45:40)
  - phone encrypted user message at chain pos N+1 (01:45:43)
  - both went into a half-dead WS socket (server received neither)
  - 60 s ACK watchdog fired at 01:46:41 → forceReconnect → flush
  - flush sent N+1 BEFORE N on the wire
  - receiver chain key advanced past N+1's MAC key when N+1 arrived
    first → MAC verification error → `ack-deliver`-and-drop → message
    silently lost
  This is a strict-FIFO violation in the outbox/flush path, NOT a
  ratchet implementation bug.
- **Architect dialogue (rejected then refined hypotheses).** The
  architect first proposed `inbound session lookup` (wrong — code
  reads `senderPubKeyHex` correctly via symmetric `deriveConversationId`)
  and `H1b generation guard` (wrong — Test #32+#33 ruled out zombie
  writers). Final agreed cause matched the data: client-side reorder
  during reconnect flush. Vladislav's instinct to push back
  ("sначала проверь код") prevented two wasted PRs.
- **PR-H2a — strict FIFO outbox (this branch, not yet merged).**
  Two-leg fix to make wire order = encrypt order under all paths:
  - **Leg 1: per-envelope monotonic `sequenceTs`** claimed once at
    `send()` entry, preserved through every requeue / re-track
    transition. Both `mergeUnackedIntoOutboxOrdered` (renamed from
    `requeueUnackedToOutboxFront`) and `flushPendingOutbox` snapshot
    sort by `sequenceTs` ASC.
  - **Leg 2: `outboundSendMutex`** serializes live-send vs flush-write.
    Without this guard a fresh live `send()` could observe the outbox
    empty (we just cleared it for the flush snapshot) and race onto the
    wire with a higher `sequenceTs`. Holding the mutex across the entire
    flush-Send loop forces concurrent live sends to defer to the outbox.
    Pings and ack-deliveries deliberately bypass this mutex (orthogonal
    to per-conversation ratchet ordering).
  - 5 unit tests in `KtorRelayTransportFifoTest`: Test #33 layout,
    interleaved sources, no-op-on-empty, adversarial out-of-order
    insertion, live-send-defers-to-outbox-during-flush.
  - **Wire format unchanged.** `sequenceTs` is purely client-side.
    Server requires no redeploy.
- **Out of scope (deferred to PR-H2b).** Skipped-message-keys in the
  ratchet + bounded retry on MAC error + dead-letter log. Without
  skip-keys any network-layer reorder (TCP retransmit pause, multi-path
  mobile, Tor circuit shifts) would still surface as MAC fail. PR-H2a
  closes the client-side reorder source; PR-H2b makes the receiver
  tolerant of any remaining reorder.
- **Process notes.**
  - 7-times-measure caught the H2a.1 race after Vladislav's review
    (architect-style "live send vs flush race" — second mutex layer
    added before push). The original H2a alone would have shipped a
    half-fix that broke under voice burst.
  - PR-H2a code stays solo-author per the durable feedback;
    `🤖 Generated with [Claude Code]` footer omitted.
  - Internal-only test accessors (`snapshotOutboxForTest`,
    `seedPendingAckForTest`, `setStateConnectedForTest`, etc.) added
    at the bottom of `KtorRelayTransport`. Marked `internal` so
    sibling modules can't reach them; production callers see no
    new public surface.

### 2026-05-12 (mon) · Transport mini-sprint continued — PR-D (rotation reorder) + Briar bridge research + PR-E (RU-tuned bridges with Google AMP fallback)

- **Goal.** Test #5 (МТС Wi-Fi without VPN, 2026-05-11) showed all four
  PR-C bridge profiles fail to bootstrap; the original "transport story
  closed" framing was wrong because Ghost without VPN on МТС is a key
  scenario for the target audience (RU users without VPN). Continue the
  mini-sprint until either Ghost works or every reasonable code-only
  option is exhausted.
- **Two PRs shipped today:**
  - **PR #112 `feat/tor-rotation-reorder-mts-tuned`** — bridge rotation
    reordered per Test #5 data: `Mixed (600s) → Snowflake (360s) →
    Obfs4 (90s) → Webtunnel (90s)`. Mixed first because it reached
    72 % vs single-PT 10–50 %. Ghost-mode AllFailed copy now reads
    "Tor is blocked or slowed by this network. Try Private/Reality or
    enable a VPN." Documented as ISSUE-016 in `KNOWN_ISSUES.md`. Test
    #5 retest with PR-D order: Mixed still stalled at 30 %, Snowflake
    at 50 % — confirmed the bridge IPs/fronts themselves are the
    bottleneck, not the rotation order alone.
  - **PR #113 `feat/tor-bridges-from-briar-ru-tuned`** — bridge data
    import from `briar/onionwrapper @ master` (GPL-3 → AGPL-3
    compatible). 4 RU-tuned snowflake entries (`bridges-s-ru` — cdn77
    + Google AMP cache fronted on `www.google.com`), 9 non-default
    obfs4 entries (`bridges-n-zz`, multi-ASN, mixed iat-mode), 1
    meek_lite entry (`bridges-m-zz`, fronted on `www.phpmyadmin.net`).
    New `BridgeProfile.KitchenSink` puts every bridge entry across
    every transport in a single `enableBridges()` call (Briar's
    empirical winning strategy) and is placed first in the rotation.
    Net pool grew from 5 → ~17 bridges per Tor bootstrap attempt.
    Worst-case rotation walk now 21 min (was 19 min).
- **Briar research findings (`briar/onionwrapper` deep-dive,
  2026-05-12).**
  - Briar does NOT have any Tor magic for RU mobile beyond what we
    already do; their stack is functionally a subset of ours plus one
    trick (concat-all-bridges) and the per-country bridge resources.
  - Briar does NOT have a BridgeDB / Moat fetcher either — bridges are
    baked-in resources updated at release time by syncing from the
    Tor Project. Option 3 from Vladislav's Friday plan
    (BridgeDB-on-device fetcher) is therefore deferred — it would be a
    genuine differentiator from Briar but solves a different problem
    (bridge freshness, not RU bootstrap).
  - The real PHANTOM advantage on МТС remains Stage 5E Xray
    REALITY+SOCKS+WSS (Private mode, May 2026-05-07) — Briar has no
    answer to the TSPU 16-KB curtain, and our Reality path cleared
    that curtain definitively. Tor is the secondary path for users
    who specifically want onion routing.
- **Privacy trade-off explicitly accepted (Vladislav A+C, in-chat).**
  Two of the four imported `bridges-s-ru` entries route their
  broker-discovery TLS through Google's AMP cache fronted on
  `www.google.com`. Google sees: client IP making encrypted TLS
  requests in a pattern broadly classifiable as "Snowflake-style
  broker discovery". Google does NOT see: PHANTOM identity, onion
  address, contacts, or message content (actual Tor circuit traffic
  flows over WebRTC DataChannel directly to a volunteer browser
  proxy, not through Google). The previous `vuejs.org`/Netlify
  fronting saw exactly the same pattern Google now sees — privacy
  property unchanged in kind, Google's value here is resilience
  against censorship. This is the same pattern Tor Browser ships by
  default for RU users and that Briar ships in `bridges-s-ru`. Both
  KNOWN_ISSUES ISSUE-016 and the source-level KDoc on
  `SnowflakeBridges.RU_TUNED` carry an explicit "what Google sees /
  does not see / what TSPU sees" matrix.
- **🎉 BREAKTHROUGH — Ghost on МТС WITHOUT VPN now works.** Test #6
  (2026-05-11 21:25-21:32 МТС Wi-Fi, no VPN, Tecno Spark Go) reached
  `Online via Tor · Ghost` in **~6 minutes** end-to-end on the very
  first KitchenSink (1/4) attempt — bootstrap walked 0 % → 30 % in
  one second, stalled at 50 % for ~5 min while Tor built guard
  circuits, then climbed 50 % → 100 % in another minute. Probe (200
  OK over the onion) and WebSocket handshake both succeeded
  immediately after `Ready`. This is the first time PHANTOM has
  proven Ghost-without-VPN on a Russian carrier network in a captured
  logcat. Compare: PR-D + old snowflake bridges in the same scenario
  timed out at 30 % after 12 minutes; today the 17-bridge Briar-
  imported pool reached 100 % half that fast. The Google-AMP-cache
  snowflake entries in `bridges-s-ru` are the most likely cause of
  the win — TSPU cannot block `www.google.com` without breaking the
  local internet, so the broker-discovery TLS request gets through.
- **Status — closed-success.** Mini-sprint goal "Ghost without VPN
  on МТС at all" achieved on PR-E's bridge data alone, no operations
  work required. KNOWN_ISSUES ISSUE-016 updated from "documented
  limitation" to "now works on bridges imported in PR-E (test #6
  evidence)". Worth a real-world re-verification on a few more
  МТС sessions before claiming production stability — single-test
  caveat noted — but the architecture-side question is answered.
- **Decisions / why:**
  - **Reopened transport mini-sprint after the previous "closed"
    framing.** Vladislav correctly flagged that I had labelled the
    transport story closed when Ghost without VPN was still broken.
    Bias correction noted; transport story remains open until either
    Ghost-without-VPN works or we have explicit user-facing
    explanation of why it cannot in the bridge profile we ship.
  - **Did NOT take Vladislav's plan Options 2 or 4.** Option 2 (deploy
    additional snowflake bridges on non-Hetzner / non-FlokiNET ASN)
    needs VPS budget we don't have right now. Option 4 (test on other
    RU operators — Beeline / Megafon / Tele2) needs a SIM card we
    don't have. Both are good ideas, both deferred to post-NLnet.
  - **Did Options 1 + 3 + 5 from Vladislav's plan.** Option 1 (snowflake
    bridge freshness check) and Option 5 (Briar research) merged into
    PR-E above. Option 3 (BridgeDB-on-device fetcher) deferred — Briar
    research showed Briar doesn't have one either, the value is in the
    bridge data not the fetcher.
- **Files touched (today, beyond yesterday's transport entry).**
  PR-D: `TransportManager.kt` (rotation order + budgets),
  `PhantomMessagingService.kt` (Ghost AllFailed copy), `KNOWN_ISSUES.md`
  (ISSUE-016).
  PR-E: `SnowflakeBridges.kt` (RU_TUNED + DEFAULT refresh + privacy
  KDoc), `OperatorBridges.kt` (OBFS4_NON_DEFAULT), `MeekBridges.kt`
  (new), `TorService.kt` (BridgeProfile.KitchenSink + MeekLite),
  `TorServiceFactory.android.kt` (bridgesFor mapping),
  `TransportManager.kt` (rotation order with KitchenSink first),
  `KNOWN_ISSUES.md` (ISSUE-016 PR-E privacy section).
- **Architect involvement.** The PR-D rotation order (Mixed first,
  90 s short budgets for known-stuck PT-only profiles) came directly
  from architect review of the Test #5 logcat. Briar research +
  bridge import (PR-E) was self-directed after Vladislav explicitly
  selected Options 1 + 3 + 5 from the plan.

### 2026-05-11 (sun) · Transport reliability deep-dive — Reality+VPN audit, Tor staged UX, bridge rotation

- **Goal:** turn the May 10–11 cross-device transport tests into shipped fixes.
  Three real symptoms surfaced under MTS Wi-Fi ± VPN: Reality probe under VPN
  failing at the wrong (~10 s) timeout, Tor on МТС stalling for 10+ minutes
  with zero user feedback, and Reality+VPN never reaching the relay edge at all.
  Goal was to land all three in one focused day so the transport story is
  reviewer-ready before further grant-readiness work.
- **What shipped (5 PRs, all merged into master):**
  - **PR #107 `2ad57a09`** — sync Reality outer probe budget (10 s → 30 s) so
    the inner OkHttp callTimeout=20 s is no longer cancelled prematurely; add
    Tor bootstrap percent streaming so logs surface the real stall point
    instead of a silent `Probing(Tor)`; defensive `tor.stop()` on prepare
    failure so a corpse Tor service does not leak between mode switches.
  - **PR #108 `a953d3e4`** — diagnostic-only PR-A1: sync OkHttp inner
    `connect/read/write` timeouts to `callTimeout` so the per-stage 10 s
    defaults stop tripping before our outer ceiling, and add an Android
    `vpnDetector` (NetworkCapabilities `NET_CAPABILITY_NOT_VPN` + `TRANSPORT_VPN`)
    that logs `vpnActive=true|false` on every `connect()`. No behavioural
    change; data-collection step ahead of PR-A2.
  - **PR #109 `75775c00`** — PR-A2: server-side audit on `relay.phntm.pro`
    (Caddy access logs) confirmed Reality probes under VPN do not arrive at
    the relay edge — zero requests in the 20 s probe window for the device's
    VPN exit IP. Cause is below the application layer (VPN egress DPI / MTU /
    Hetzner ingress IDS); fixing it is not in scope. Mitigation: `TransportManager`
    filters Reality out of the chain when `vpnActive=true`. Standard+VPN walks
    `[Direct, Tor]`, Private+VPN walks `[Tor]` only. Saves 20 s of guaranteed-
    failing probe per chain walk under VPN. Documented as ISSUE-015 in
    `KNOWN_ISSUES.md` with the audit narrative.
  - **PR #110 `53a02967`** — PR-B: replace silent `Probing(Tor)` with a staged,
    time-keyed UX. `ManagerState.Probing` carries an optional
    `TorProbingStatus(percent, stage, elapsedMs)`; a 5 s poller advances the
    `TorBootstrapStage` (Initial → Negotiating → Searching → Slow → Throttled)
    even when the percent is stalled, and percent updates push immediate state
    emissions. Notification text becomes "Searching for a reachable route…
    50 % · Ghost" instead of nothing for 7 minutes. Architect-suggested copy
    deliberately avoids "blocked" without certainty.
  - **PR #111 `ed143c60`** — PR-C: bridge profile rotation. Replace the single
    all-of-the-above Tor attempt with sequential `obfs4-only` (180 s) →
    `webtunnel-only` (120 s) → `snowflake-only` (180 s) → `mixed` (240 s) walk.
    `BridgeProfile` enum in commonMain, `TorService.start(profile)` mapped on
    Android to `OperatorBridges` / `SnowflakeBridges`, `tor.stop()` between
    profiles for clean wrapper restart. Total budget 720 s but most users land
    on the first profile in 1–3 min. Notification copy renders
    `"<stage> NN% · obfs4 (1/4) · Ghost"` so the rotation is visible.
- **Audit evidence underpinning PR-A2:** `docker logs --since 12h phantom-caddy`
  on relay.phntm.pro showed every legitimate test client's `/health` request
  in the last 12 hours from real domestic IPs, but **zero entries** in the
  exact 20 s window of Test #3 (МТС Wi-Fi + commercial VPN, identity
  `8c858658b4c426ae`, 22:38:18 UTC 2026-05-10). Same Xray config + same
  network without the VPN succeeds in 0.6 s. Inferred cause is one of
  VPN egress DPI / MTU on the tunnel / Hetzner ingress IDS — the user-visible
  result is identical for all three so the client-side fix is the same.
- **Validation cycles (post-merge smoke):**
  - PR-A1+A2: Test #4 (МТС + VPN) — `vpnActive=true realityFiltered=true
    ordered=[Direct, Tor]` (Standard) and `ordered=[Tor]` (Private) confirmed
    in logs, no Reality probe attempted.
  - PR-B: visual smoke on different network (with VPN) — staged copy walked
    `Negotiating → Searching → Slow → Throttled` with live percent. Tor
    stalled at 73 % in this run and AllFailed at the previous 600 s budget,
    which directly motivated PR-C.
- **Decisions / why:**
  - **Skip Reality under VPN entirely (PR-A2)** — would not retry as
    last-resort because every retry is 20 s of guaranteed waste. Saved as
    durable architectural fact in
    `memory/project_reality_vpn_audit_2026_05_11.md`.
  - **obfs4 first in rotation (not webtunnel as architect originally
    suggested)** — empirical: Test 13 (2026-05-06) showed our WebTunnel
    handshakes hit the TSPU 16-KB curtain on Hetzner-hosted bridges. obfs4
    to FlokiNET has been the most reliable single-PT path on МТС since
    2026-05-09, so it gets the longest budget and goes first.
  - **Per-profile budgets, no single global Tor cap** — old
    `TOR_PREPARE_TIMEOUT_MS = 600 000L` removed. Per-profile budgets dominate.
- **Files touched (sample):** `shared/core/transport/.../TransportManager.kt`,
  `KtorTransportProbe.kt`, `TorService.kt`, `TorServiceFactory.android.kt`,
  `TorServiceFactory.jvm.kt`, `apps/android/.../AppContainer.kt`,
  `PhantomMessagingService.kt`, `KNOWN_ISSUES.md`,
  `commonTest/.../TransportManagerTest.kt`.
- **Architect involvement:** dispute resolution (5 May architect claim about
  signed-challenge auth + VpnService conflict — both wrong, evidence in PR
  #72 history); audit framing for Reality+VPN; staged Tor UX copy thresholds;
  bridge rotation order recommendation (we adapted it to put obfs4 first
  per our local empirical data).
- **Follow-ups:** (a) Vladislav post-merge smoke test of PR-C — confirm
  rotation actually walks profiles when first profile stalls. (b) Telemetry
  PR (architect's third recommendation) deferred — not blocking for NLnet.
  (c) Resume grant-readiness sprint Phase 1 cleanup once transport story is
  fully smoke-tested.

### 2026-05-08 (thu, late) · F22 QA-pass + MASTER_TIMELINE sync

- **Goal:** verify that the F22 prekey-wrap implementation (PR-1 `6737be91` +
  PR-2 `2bcd891e`) and `SECURITY_ROADMAP.md` (`22f0c30c`) are correct
  end-to-end by running a real-device test with the freshly-merged code.
- **Test scenario:** Vladislav deleted PHANTOM from the Tecno, reinstalled
  from a fresh APK, re-registered, then exchanged text messages and voice
  notes with the emulator. The reinstall forced a fresh keygen — new SPK/OPK
  bytes are generated and stored via the new `AndroidKeystoreBlobCipher` path.
- **Outcomes:**
  - First launch after reinstall: `messagingService is null after init — no
    identity yet, stopping` — expected sentinel. ✅
  - Xray SOCKS5 up, WS connected in ~1 s. ✅
  - Text messages both directions: `sealed=true`, `Decrypt OK`. ✅
  - Voice (audio_chunk): 13+ chunks each direction, all `Decrypt OK`. ✅
  - X3DH bootstrap on emulator: `Bootstrapping recipient session` fired
    correctly for new phone identity; session established without error. ✅
  - Key finding for F22: bootstrap worked → new SPK/OPK private bytes
    (Keystore-wrapped) are correctly unwrapped for X3DH. Production-proven. ✅
  - Call signalling: `call_offer → call_answer → ICE × 4 → call_hangup`. ✅
  - No crashes, no `AndroidRuntime:E`. ✅
- **F22 status:** CLOSED. All three sub-commits merged; QA confirmed on
  physical Tecno (MTS, REALITY/SOCKS) + emulator.
- **MASTER_TIMELINE updated:** Track B item 1 F22 → ✅; Track C phases 2-4 → ✅.
- **Follow-up next session:** Day 14 NLnet draft V2 finalization.

### 2026-05-08 (thu) · Day 1 of council-revised 25-day release-polish plan — Stage 5 closure + Phase 1 cleanup + Firebase rotation + ADR-019

- **Goal:** close all loose ends from yesterday's Stage 5E.B production
  validation, kick off the council-revised 25-day release-polish path
  (target submit day 15 = 2026-05-22, leaving ~10-day buffer before
  the 2026-06-01 release window). Day 1 specifically: restore strict
  Xray routing on the Hetzner server (which had been left in
  diagnostic-relaxed state by yesterday's `7b4ebf77`); ship Track C
  Phase 1 repo cleanup (the live Firebase API key was tracked publicly
  until today); rotate the Firebase key now that it is out of git
  tracking; write ADR-019 capturing the architectural decision behind
  the whole Stage 5E build.
- **Outcomes (four merged PRs in one day):**
  - **Restore strict Xray routing** (#45, commit `d7ba3a41`). Replaced
    the diagnostic single-rule (`VLESS in → freedom out`) with a
    three-rule chain: domain matches `relay.phntm.pro` (with three
    matcher syntaxes layered — `relay.phntm.pro`, `domain:`, `full:` —
    so whichever variant Xray 26.3.27's matcher honours wins) → port
    443 fallback → catchall blackhole. Closes the open-proxy hole.
    Verified end-to-end via Caddy access logs:
    `remote_ip: 172.18.0.7` (docker bridge) means traffic landed
    through Xray, not direct.
  - **Phase 1 repo cleanup** (#46, commits `f07bba8c` + `9b0581dd`).
    `git rm --cached` for `apps/android/google-services.json` (live
    Firebase API key) and `.kotlin/` (76 Kotlin Multiplatform metadata
    cache files). Newly tracked: `docs/project/ARCHITECTURAL_DECISIONS_TODO.md`
    and `docs/project/PHANTOM_ROADMAP_2026.md` (both internal until
    now, Vladislav approved publication for grant-readiness signal).
    `.gitignore` extended with `.mcp.json`, `.kotlin/`,
    `apps/android/google-services.json`, `tag-message.txt`,
    `private/`, and a `*.pdf` blanket with
    `!legal/assets/*.pdf` + `!docs/**/*.pdf` exceptions. The
    follow-up `9b0581dd` made the google-services Gradle plugin
    conditional (`apply false` + later `if (...exists()) apply(...)`)
    so clean-clone CI builds succeed without the Firebase config
    file. Verified: `./gradlew :apps:android:assembleDebug` passed
    in both states (with and without the file present).
  - **Firebase API key SHA-1 restriction logged** (#47, commit
    `ba830353`). Decision-log entry in `docs/PROJECT_LOG.md`
    documenting the Path-A-vs-Path-B reasoning (restrict by
    package name + signing-cert SHA-1, not full key regeneration —
    full reasoning in the Decision log entry above this one).
    Operator action (the actual restriction in Google Cloud Console)
    completed in parallel: two SHA-1 fingerprints (debug + release)
    added to the auto-created Android API key, plus matching
    SHA-256 fingerprints registered in Firebase Console for
    defence-in-depth (Firebase Auth / App Check / Dynamic Links
    use these independently of API-key restriction).
  - **ADR-019 Xray VLESS+REALITY as outer transport** (#48, commit
    `20e71fbb`). 371-line architectural decision record covering
    context (Stage 5C/5D bridge failure on Hetzner *and* FlokiNET
    on RU MTS), decision (libXray gomobile + Path A server),
    five sub-rationale subsections (why VLESS+REALITY beats
    every obfuscation alternative, why same VPS as relay, why
    in-process libXray, why single shared capability-style UUID,
    why MPL-2.0 + AGPL aggregation is OK), threat-model
    consequences, known limitations (each tied to a planned
    mitigation ADR — 020 adaptive transport, 021 multi-server
    fan-out, 022 iOS XCFramework), implementation plan (lists
    the five Stage 5E commit chains as DONE), production-validated
    test plan, and references. Will anchor the Stage 5E
    architectural narrative in any external write-up of the
    project.
- **Decisions captured:** the Firebase rotation decision (Path A
  restrict vs Path B regenerate) is in `docs/PROJECT_LOG.md`
  Decision log section, written today as part of #47.
- **Discoveries / lessons that nearly cost time:**
  - "Phantom not using Xray" red herring (~30 min). When Xray's
    `docker logs phantom-xray` showed only startup messages with no
    accepted-connection lines after a real client connect, the
    instinct was "Phantom bypasses Xray, OkHttp SOCKS proxy bug".
    The actual cause: `access: none` in the Xray log block (added
    yesterday as a security-reviewer finding) intentionally
    disables per-connection logging. Routing was always fine — we
    just had to look at Caddy access logs (`remote_ip` field)
    instead. Captured as a permanent reference in
    `memory/reference_xray_diagnostics.md` so the same time-sink
    doesn't repeat.
  - VPN-on-host vs VPN-on-emulator confusion. Tested Xray on the
    Pixel 8 Pro emulator while Borealis VPN was still active on
    Windows host. Emulator inherits host networking, so the
    "Xray ready" notification was technically correct (local SOCKS
    bound) but no traffic actually went through Xray to the Hetzner
    server (the host VPN intercepted all TCP). Spent ~10 min ruling
    out a code bug before realising it was test-environment
    contamination. Stage 5E architecturally cannot stack with
    another VLESS-based VPN like Borealis — REALITY needs a direct
    TLS-fingerprint path to the cover host.
- **Council session and the revised 25-day plan.** Last night
  (2026-05-07 evening, after the Stage 5E production-validation
  sign-off) I ran a five-advisor LLM Council session on the optimal
  sequencing of the next 25 days of release-polish work without
  burning out the founder. All five lenses converged on a significant
  pivot from the original plan: cut Track B Security Sprint from 4
  items to 1 (F22 only — keystore-wrap SPK/OPK), move the remaining
  findings into a `docs/security/SECURITY_ROADMAP.md` honest-roadmap
  document, ship the Stage 5E demo video on day 3 (one artefact
  serving the README hero + a public write-up + any external review
  context), tag the Alpha-2 release on day 15 with a 10-day buffer
  rather than the last-minute day-25 push. Full synthesis in
  `~/.claude/projects/.../memory/council_2026_05_07_synthesis.md`,
  daily plan in `~/.claude/projects/.../memory/plan_25_days_to_release.md`.
  Today (Day 1) executed the Stage 5 closure + Phase 1 cleanup
  + Firebase rotation + ADR-019 leg of that plan.
- **Day 2 next** (Friday 2026-05-09): README polish (License → AGPL,
  Status → Alpha 2, hero line about Stage 5E in the first three
  lines), `funding.json` (external funding portal prerequisite),
  `.github/FUNDING.yml`, and matching fixes to `RELEASE_NOTES.md`
  and `CONTRIBUTING.md`.
  ~3 hours estimated.
- **Day 3 after that:** demo video for Stage 5E (5-10 min screen
  capture showing Tecno on RU MTS without VPN, plus emulator twin,
  plus Caddy log proof). ~3-4 hours.
- **Follow-ups from today not blocking Day 2:**
  - The Firebase Console SHA-1 fingerprints (defence-in-depth) were
    optional and Vladislav can add them whenever — not on the Day 2
    critical path.
  - `keystores/phantom-release.keystore` backup to off-device
    location is the single critical-asset backup the project does
    not yet have. Captured here so it doesn't get forgotten;
    Vladislav schedules at convenience.

### 2026-05-07 (wed) · Stage 5E (Xray VLESS+REALITY) production-validated

- **Goal:** Finish Stage 5E.B (the Android client side of ADR-018's
  Xray-as-outer-transport plan) and prove end-to-end that PHANTOM works
  on a Russian carrier without VPN, without Orbot, without any
  third-party app. The TSPU "16-kilobyte curtain" — silent throttling
  of TLS streams larger than ~16 KB to flagged datacenter IPs (Hetzner
  confirmed by Test 11/12 on 2026-05-05/06; FlokiNET confirmed by
  Test 13 on 2026-05-06) — had killed the Stage 5C/5D bridge approach
  entirely. Stage 5E mimics a genuine TLS handshake to
  `www.microsoft.com` so the censor classifies the flow as trusted CDN.
- **Outcomes:**
  - **`shared/core/xray/`** — new KMP module exposing
    `XrayService` / `XrayState` (Off/Starting/Ready(socksPort)/Failed),
    `XrayServiceConfig` data class, `OperatorXrayConfig` with the pinned
    REALITY pubkey + shortId + UUID for the Hetzner endpoint, and an
    Android impl that wraps libXray's gomobile JNI. JVM target gets a
    no-op stub. libXray itself (built via the new
    `.github/workflows/build-libxray.yml` workflow) is vendored unpacked
    as `classes.jar` + `libgojni.so` × 4 ABI under
    `src/androidMain/{libs,jniLibs}/` — AGP refuses to bundle a local
    `.aar` inside another AAR, so the four-file split is the canonical
    workaround. ~180 MB committed to git; reroll procedure in
    `src/androidMain/libs/README.md`. Commit `96fcbf1a`.
  - **`PhantomMessagingService` wiring** — `BuildConfig.USE_XRAY` flag
    added alongside `USE_TOR` with a build-time mutual-exclusion check
    (both true → gradle errors immediately). When `USE_XRAY=true`,
    service lazy-constructs Xray, awaits Ready-or-Failed within 30 s,
    hands the localhost SOCKS5 port to `KtorRelayTransport` via the
    same `socksProxyPort` parameter the Tor branch already uses.
    Notification updater extended to surface Xray state. Commit
    `98245f69`.
  - **Server-side Path A** (`deploy/xray/config.json.template` rewrite,
    commit `7b4ebf77` — see Decision log entry below): VLESS routing
    relaxed from strict-domain-match-then-blackhole to
    `inboundTag-only → freedom outbound`, because `domain: ["full:relay.phntm.pro"]`
    silently misses on Xray 26.3.27 even when sniffed SNI visibly equals
    `relay.phntm.pro` in the same log line. With the relaxed rule, plus
    a `dns.hosts` override (`relay.phntm.pro → caddy`) and `access: none`
    in the log block (the latter two folded in from a security-reviewer
    pass), VLESS clients land in the local Caddy container via the
    docker bridge network — no NAT hairpin, no `docker logs` IP leak.
  - **Test 14 (production validation, 2026-05-07 evening):** Tecno on RU
    MTS without VPN ↔ Pixel 8 Pro emulator. After clearing emulator
    storage (the long-standing prekey-bundle bug ate a fresh identity —
    see Follow-ups), text messages `delivered` in 30–100 ms each way;
    5-second voice messages chunked to 5 envelopes of ~55 KB each
    flowed through Xray REALITY with ack `delivered` on every chunk and
    clean `Decrypt OK` on the receiving side. The 55 KB chunk size is
    the critical proof: it is well over the 16 KB curtain threshold,
    bare TLS would have stalled, REALITY masks it as a Microsoft CDN
    stream and the censor passes it.
- **Decisions made:**
  - **Path A (VLESS clients land at clearnet `relay.phntm.pro` on the
    same VPS via docker DNS)** chosen over Path B (server-side Tor →
    onion). Path B would have added 500–800 ms latency and a second
    daemon for defence-in-depth that protects only against an insider
    on the Hetzner Xray container — and that container is the operator's
    own. Decision recorded in commit message `98245f69`.
  - **Single shared client UUID** baked into the APK, treated as a
    *capability* (anyone holding it can use the server) rather than a
    secret tied to identity. Stage 5E's purpose is censorship
    circumvention, not access control — relay-level auth handles abuse
    separately. Rotation playbook in
    `deploy/xray/render-config.sh` header.
  - **Vendor libXray as committed git artefacts** (not Git LFS, not
    fetch-on-build) — earlier we tried "don't commit, instruct
    contributors to download" but Vladislav rightly pointed out that
    creates a reproducibility hole and a bad first impression for any
    contributor cloning the repo. 180 MB one-time cost is acceptable;
    rebuild from `XTLS/libXray@<sha>` is deterministic via the
    workflow.
- **Key commits (all on `feat/tor-stage5-bridges-via-onionwrapper`,
  pushed to origin, **NOT** yet merged to master):**
  - `5cca2976` — workflow `.github/workflows/build-libxray.yml`
  - `f5e21fb3` — workflow audit pass (Go pin, ABI regex, leading-./, SHA-ref fix)
  - `96fcbf1a` — KMP module skeleton + libXray vendoring (15 files, +644 lines + ~180 MB binaries)
  - `98245f69` — `PhantomMessagingService` wiring + Path A server template
  - `7b4ebf77` — diagnostic routing relaxation
- **Follow-ups:**
  - **Prekey republish bug** (pre-existing, surfaced by Stage 5
    testing). Long-offline identity → bundle 404 on `/prekeys/bundle/<peer>`
    → senders' messages stay WAITING forever. Workaround that worked
    today: Clear Data on the offline device → fresh onboarding →
    fresh publish. Permanent fix: client-side retry publish on
    connect when self-bundle is 404, or relay-side fallback to a
    signed-prekey-only bundle when OPK pool is empty. ~1–2 hours.
  - **Restore strict Xray routing** — replace the temporary
    `inboundTag-only` rule with `domain: ["domain:relay.phntm.pro"]`
    (without the `full:` prefix that silently missed) and re-enable
    blackhole-by-default. Server-side, ~30 min plus redeploy.
  - **ADR-019 Xray REALITY rationale** — write it before any external
    write-up needs it: threat model, license posture (MPL-2.0 Xray-core
    aggregation cleanly composable with our AGPL at the docker-compose
    level), Beta-time multi-server fan-out plan. ~1 hour.
  - **PR to master** — open once the three above are done, so master
    doesn't carry the temporarily relaxed routing.

### 2026-04-27 (sat, evening) · Licence hygiene before the Alpha-2 release window

- **Goal:** establish formal AGPL-3.0 licensing across the repo so any
  external reviewer ahead of the 2026-06-01 release window does not see
  absent / ambiguous licensing as a red flag. The user reported a
  perception that the previous LICENSE file carried a Vercel copyright
  (likely a half-remembered reference to the `prototype/web` Next.js
  scaffold); on inspection there was no top-level LICENSE at all,
  which is arguably worse for any external review.
- **Outcomes:** four-commit patch series shipped:
  1. **`e99aac0e`** — `LICENSE` at the repo root: PHANTOM header
     naming Willen LLC + Vladislav Liudvig (both 2026), the AGPL
     grant / warranty paragraph, and the verbatim 661-line GNU
     AGPL-3.0 text fetched from `gnu.org/licenses/agpl-3.0.txt`.
  2. **`c403b7cf`** — SPDX headers on **121** source files (113 .kt
     + 8 .rs). Format: `// SPDX-License-Identifier: AGPL-3.0-or-later`
     and `// Copyright (c) 2026 Willen LLC`. Driven by a small
     idempotent helper `scripts/add-spdx-headers.py` that walks
     `apps/`, `shared/`, `services/`, skips build outputs, checks
     the first 5 lines for an existing SPDX tag before prepending.
     Verified clean by `cargo build --release` (3.56 s) and
     `:apps:android:compileDebugKotlinAndroid`.
  3. **`27cd9bd1`** — `NOTICE` listing every bundled / linked
     third-party dependency with upstream URL and licence: libsodium
     (ISC), Kotlin / kotlinx / Compose Multiplatform / AndroidX /
     Ktor / OkHttp / SQLDelight (Apache-2.0), SQLCipher (BSD 3-Clause),
     tokio / axum / tokio-tungstenite / tower / serde / tracing /
     futures-util (MIT), subtle (BSD 3-Clause), Caddy (Apache-2.0),
     Inter / JetBrains Mono (SIL OFL-1.1, mock-ups only — explicitly
     not bundled).
  4. **`0bc715e1`** — `Releases/README.md` §License updated from the
     "to be finalized" placeholder to the canonical AGPL-3.0 statement
     with rationale (relay-side §13 Remote Network Interaction
     protection) and a commercial-dual-licensing escape hatch contact.
- **Decision:** AGPL-3.0 over GPL-3.0 — recorded in the LICENSE
  commit message and now mirrored in `Releases/README.md` §License.
  See ADR-006 for the original rationale.
- **Follow-ups:**
  - Keep `scripts/add-spdx-headers.py` idempotent — re-run it any
    time new `.kt` / `.rs` files land. Could be wired into a pre-commit
    hook in a later session.
  - The four legal-doc TODOs (lawyer review, GDPR review, RU
    translation review, ownership approval) are unaffected by this
    licence work — they live in `legal/README.md` and remain on the
    Active backlog.

### 2026-04-27 (sat) · QA-v10 polish + RU localisation + correct Russian legal terminology

- **Goal:** Tecno-HiOS input clipping (third attempt), publish themed
  ToS / Privacy Policy at `phntm.pro/terms` and `/privacy`, add EN ⇄ RU
  language switcher, rename Russian "приватность" → "конфиденциальность".
- **Outcomes:**
  - Input clipping **fixed** at root cause: `enableEdgeToEdge()` for
    API < 35 in `MainActivity`. Verified on Tecno HiOS (the chat AND
    Notes screens). `SavedMessagesScreen` also got an explicit
    `windowInsetsPadding(ime ⋃ navigationBars)` since it has no
    Scaffold.
  - `legal/render.py` — stdlib-only Markdown → themed HTML generator.
    PHANTOM design tokens (Surface Deep, Cyan accent, system sans/mono
    stack — no Google Fonts CDN per privacy posture). Brand logo from
    `Claude Design/assets/phantom-logo.png` in header + footer.
  - Caddyfile: `/assets/*` for the logo, `/terms*` and `/privacy*` (EN
    default), `/terms/ru*` and `/privacy/ru*` (Russian, ordered before
    the generic blocks).
  - `docker-compose.yml`: bind-mount `legal/` at `/srv/legal:ro`.
  - Russian terminology pass: "Политика приватности" →
    "Политика конфиденциальности" across MD source, render.py
    LOCALES["ru"], and regenerated HTML. URL `/privacy/ru` unchanged
    (technical, stable).
- **Commits:** `b3629bed`, `09012ab1`, `99a93c41`, `d2320b95`, `f46d2772`,
  `b2056987`, `3d4b0bf8`.
- **Follow-ups:** legal/GDPR/translation reviews still on the TODO list
  (see Active backlog). Locale-aware in-app linking deferred — the
  in-page EN/RU switcher covers the immediate UX gap.

### 2026-04-27 (sat) · Alpha 1 release tagging + production redeploy

- **Goal:** ship Alpha 1 — tag, GitHub Release page, redeploy production
  relay on master.
- **Outcomes:**
  - `v0.1.0-alpha.1` tagged at the merge commit `0246b50f` and pushed.
  - GitHub Release page published with the signed `android-release.apk`
    attached, body taken from `Releases/GITHUB_RELEASE_BODY.md`
    (template committed alongside for reuse).
  - Production redeploy hit two latent bugs: `Dockerfile` sed only
    matched single-line `members = [...]` (workspace had grown to
    multi-line), and the bundled `time = 0.3.47` requires Rust 1.85+
    (image was on rust:1.83). Fixed both: `sed -z` for multi-line
    arrays (`a539695e`), bumped builder image to `rust:1.88-slim-bookworm`
    (`38b20f29`).
  - Three branches merged into master via `--no-ff`:
    `fix/bug-h-libsodium-jni` (everything from the QA sprint, plus
    `chore/assetlinks-real-fingerprint` which was already an ancestor),
    and `docs/initial-drafts` (release-ready public docs).
- **Commits:** `0246b50f` (tag), `4d7d6091`, `01e955fe`, `a539695e`, `38b20f29`.
- **Follow-ups:** Alpha 2 polish items captured as drafts in
  `Releases/GITHUB_ISSUES_DRAFT.md`.

### 2026-04-26 / 04-27 (sun → mon) · 60-second WebSocket reconnect hunt

- **Goal:** chase down the deterministic ~60 s WebSocket disconnect
  cycle that QA-v3 → QA-v8 surfaced. Four false starts, one breakthrough.
- **Outcomes:**
  - **`b14b39ff`**: replace `session.close()` with `session.cancel()` on
    pong/ack timeout. Halved the close-wait but did not fix it — the
    Ktor actor's finally block was still calling graceful close.
  - **`2ee1d08d`**: introduced the `forceCancelAllEngineCalls()`
    expect/actual that calls OkHttp's `dispatcher.cancelAll()`. Bypasses
    the actor entirely. **Major progress** — emulator stopped hanging.
    Phone still cycled.
  - **`846d6bed`**: `cancelAndJoin` between reconnect generations to
    stop stale `pingJob`s from firing on the new socket. Removed the
    "cascade of Pong timeouts" log noise.
  - **`452a0b5e`**: tightened OkHttp `pingInterval` to 8 s and
    `RECONNECT_BASE_DELAY_MS` to 1 s.
  - **`dbd9393c`** (THE FIX): traced the relay-side bug to
    `socket.split()` + tungstenite's auto-PONG queue. Replaced with a
    single `tokio::select!` loop that explicitly answers `Message::Ping`.
    **Pixel emulator went from "every 90 s" to rock-solid for hours.**
  - **`74e6af0a`**: WifiLock + WakeLock for the foreground service.
    Helped some OEM cases but not Tecno HiOS — that's where it became
    clear the remaining issue is OS-level radio parking, not code.
  - **`22a76c22` → `b2447dde`**: the heartbeat experiment that did not
    work and was reverted (see Decision log).
- **Outcome:** stock-Android Wi-Fi connections are stable for hours;
  Tecno HiOS still drops every ~60 s but messages are durable through
  store-and-forward — fix-grade for Alpha 1, real solution scheduled
  for Alpha 2.

### 2026-04-25 (fri) · Onboarding sequence + false-offline badge

- **Goal:** the two non-network bugs surfaced in QA-v5: the WebSocket
  did not connect after the *first* onboarding (only after an app
  restart), and the "Offline — messages queued" banner showed during
  the cold-start window before any connect attempt had happened.
- **Outcomes (commit `5caf61eb`):**
  - `MainActivity.PhantomApp` now re-triggers `startForegroundService(...)`
    from the `OnboardingScreen.onComplete` callback. The service's
    earlier `onStartCommand` had bailed out via `stopSelf()` because
    no identity existed yet.
  - `ConnectionBanner` gates the offline line on a `hasEverConnected`
    flag set the first time `TransportState` transitions to
    `Connecting`/`Connected`.
- **Plus:** `69e87ad8` switched the debug build to default to
  `wss://relay.phntm.pro/ws` instead of a hardcoded LAN IP.

### 2026-04-25 (fri, earlier) · Six bugs from cross-device QA

- **Goal:** stabilise the relay + Android transport based on the
  2026-04-25 morning QA report.
- **Outcomes:** commit `37e1414e` rolls up six fixes:
  outbox `addLast` ordering, `withTimeoutOrNull` on session close,
  `processingLock` deduplication in `handleDeliver`, atomic `conn_id`
  for racing reconnects, exclude `/ws` from `tower-http`'s
  `TimeoutLayer(30s)`, remove Caddy `response_header_timeout 30s`.

### Earlier history

Pre-2026-04-25 work (Этап 2 security, KMP scaffold, deploy artefacts,
groups + Sender Keys, FCM scaffold, etc.) is out of scope for this
log because every fact lives in
[`docs/adr/`](adr/), [`docs/threat-model/`](threat-model/), and the
respective merge commits (`94b6a4d2`, `90a5ec54`, `4425fbda`, `084cd8e2`).
The first session worth tracking here is the QA-driven hardening that
produced Alpha 1.

---

## Quick links

- Public README → [`README.md`](../README.md)
- Release notes → [`RELEASE_NOTES.md`](../RELEASE_NOTES.md)
- Known issues → [`KNOWN_ISSUES.md`](../KNOWN_ISSUES.md)
- Alpha 2 backlog → [`docs/alpha2-backlog.md`](alpha2-backlog.md)
- Release-body drafts → [`docs/release-drafts/`](release-drafts/)
- Threat model → [`docs/threat-model/Threat_Model_v0.md`](threat-model/Threat_Model_v0.md)
- Architectural decisions → [`docs/adr/`](adr/)
- Crypto details → [`docs/CRYPTO.md`](CRYPTO.md)
- Architecture overview → [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)
- Design brief → `PHANTOM_Design_Brief_v2.pdf` at the repo root
