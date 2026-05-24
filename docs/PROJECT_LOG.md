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

### What works today (master `8f4c68c9`, 2026-05-21)

This block reflects the current production state of `master` after the M1w / M2 / H1 / H2 / D-track / REC sprints. The Alpha-1 baseline immediately below is preserved as a historical reference point.

- ✅ **End-to-end encrypted 1:1 messaging** — Double Ratchet + Sealed Sender via libsodium; identity-prekey separation per ADR-009 implemented; F22 prekey private-key wrap via Android Keystore (ADR-023 Accepted, PR #56 `d862f3d0`); F8 Double Ratchet state wrap (ADR-024, PR #60 `cc48a333`); H2b idempotent envelope ledger (PR #129 `7008cf3e`) neutralises relay redelivery.
- ✅ **Multi-transport stack with runtime gating** — three transports plus a fallback layer:
  - **Direct WSS** (`Standard` mode) — the primary path on healthy networks; H1c/H1e Run C policy locked (PR-H1c `e946caba`, PR-H1e `bcc501be`).
  - **Reality (VLESS+REALITY through Xray)** (`Private` mode) — load-bearing on RU mobile carriers per the 2026-05-15 strategic pivot follow-up; Stage 5E production-validated 2026-05-07.
  - **Tor v3 onion service** (`Ghost` mode) — text-only emergency fallback per pivot. PR-E imported Briar's `bridges-s-ru` bridge set + Google-AMP-cache Snowflake (Test #6 reached Ghost-without-VPN on МТС in ~6 min).
  - **REST short-poll fallback** (PR-D0r relay endpoints + PR-D1/D1b/D1c/D1c.1/D1d client orchestrator) — activates automatically when WS realtime degrades. Tested end-to-end on Tele2 LTE Иркутская.
- ✅ **Encrypted media-upload voice messages 1:1** (M1w end-to-end). Record → encrypt (XChaCha20-Poly1305 AAD=mediaId) → chunk → upload via native OkHttp fresh-client-per-call → manifest envelope via ratchet → receiver durable enqueue + ACK + GET + decrypt + SHA-256 verify + bubble. PR-M1w (#172 `561de17c`) plus the M2a–M2h.1 sequence locked production chunk size = 3200 bytes, byte-based early manifest = 5100 bytes, ~3× throughput headroom vs the v2 ceiling.
- ✅ **Recording Panel UX trilogy** — RecordingPanelState enum with Recording / Paused / Locked / SwipeCancel states. REC1 baseline (PR #195 `08ccf906`), REC2 WhatsApp-style hold-to-lock + recorder crash hardening + swipe-left-cancel safety shim (PR #197 `52a9773f`), REC3 SwipeCancel state visual + threshold/trail/animated arrow (PR #205 `770e61f4`).
- ✅ **Outgoing voice upload cancel** — X glyph on the uploading bubble actually stops the upload via `MessagingService.cancelVoiceUpload` + `CancellationException` propagation + `NonCancellable` cleanup + `job.cancel + join` (PR-MEDIA-UPLOAD-CANCEL2.1 in PR #198 `b117dcb9`).
- ✅ **Per-user Ed25519 signed-challenge auth** (F11+F26 closed via PR #72 `6e90e3c6`; ADR-027 Accepted). Replaced the shared WS token; production-validated on Tecno МТС + emulator 2026-05-09.
- ✅ **Trust Tier flow** (first message → Message Requests, accept → Trusted) — unchanged from Alpha-1.
- ✅ **Production relay** with store-and-forward at `wss://relay.phntm.pro/ws` plus the REST endpoints `/auth/session`, `/relay/send`, `/relay/poll`, `/relay/ack-deliver`, `/prekeys/publish`, `/prekeys/status`, `/media/v3/{mediaId}/{idx}` POST + GET.
- ✅ **QR code + `phantom://invite/<base64>` deep links** — unchanged from Alpha-1.
- ✅ **Crypto integration test suite green** (16/16 on Pixel 8 Pro / API 35); H2b storage contract + messaging tests green; D2b.1 voice durability tests green; M1w/M2 tests green; D0r REST-fallback integration tests green.
- ✅ **Foreground-service WebSocket** with WifiLock + partial WakeLock + AlarmManager-driven 45 s proactive reconnect.
- ✅ **Onboarding ToS** in EN, full Terms / Privacy Policy hosted at `/terms` and `/privacy` with EN ⇄ RU switcher (unchanged from Alpha-1).
- ✅ **Settings → About** with all 6 contact addresses (unchanged from Alpha-1).
- ✅ **Discipline scaffolding** — `docs/WORKING_RULES.md` codifies single-developer discipline (one active track, one PR per layer, mini-lock before code, two architectural failures = park, hand-off note on context switch, log everything, log-not-develop on new findings). First PR-end-to-end under that discipline = REC3 (PR #205) plus PR-DOC-HONESTY (this PR).

### What works (proven in QA-v10 / 2026-04-27) — historical Alpha-1 baseline

Preserved as the original snapshot at tag `v0.1.0-alpha.1` (commit `0246b50f`). Read alongside the **What works today** block above to see what changed.

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

### Consolidated queue (Vladislav-locked order, 2026-05-23)

This is the next-session locked queue, refreshed after PR-NOTIF-DIAG closed on 2026-05-23 with two new follow-ups generated (PR-UI-CHAT-AUTOSCROLL1, PR-NOTIF-POLICY1). Order below is **locked** — do not reorder without explicit Vladislav greenlight.

1. ✅ **PR-DOC-HONESTY** — shipped 2026-05-21 (PR #208 + close #209).
2. ✅ **PR-UI-REC-FOLLOWUP** — shipped 2026-05-22 (PR #210 `a625bde7`). Test #77 PASS.
3. ✅ **PR-NOTIF-DIAG** — shipped 2026-05-23 (PR #213 `a0484602`). Test #78 PASS. Surfaced two new follow-ups (rows 4 and 5 below).
4. **PR-UI-CHAT-AUTOSCROLL1** — fix `ChatScreen` not scrolling to bottom on open when unread messages arrived in the background. Mini-lock at `docs/tracks/chat-autoscroll.md`. Architect recommends doing this BEFORE PR-NOTIF-POLICY1 because it's the more user-visible bug Vladislav noticed by hand.
5. **PR-NOTIF-POLICY1** — conversation-level notification with `InboxStyle` summary + unread count + clear-on-chat-open. Closes the perezatiranie finding from Test #78. Mini-lock at `docs/tracks/notif-policy.md`. Variant A vs B decision at mini-lock review.
6. **PR-D1e** — first-message bootstrap fast path (yellow-dot 10–20 s on first text after `add contact`; PR-G4 closed the 8 s × 4 timeouts but the residual ~10 s on bootstrap remains). See `KNOWN_ISSUES.md` ISSUE-022.
7. **Network matrix Standard / Private / Ghost** — systematic re-verification of each transport on (a) clean Wi-Fi, (b) Tele2 LTE, (c) МТС Wi-Fi (no SIM cellular today), (d) emu-on-dev-Wi-Fi. Multi-session, multi-time-of-day; produces the `docs/project/CONNECTIVITY_MATRIX.md` public artifact.
8. **Calls testing — C-track sequence.** C1 typed `TransportCapabilities` gate → C2 Reality endpoint pool + realistic probe → C3 TURN-over-TLS on 443 or custom Opus-over-Reality.
9. **Voice quality A/B** — 24 kbps OPUS mono 16 kHz confirmed as production floor; a future "Data Saver" 16 kbps toggle is gated behind this A/B.
10. **PR-UI-SUPPORT-SCREEN — Settings → Support PHANTOM** (Vladislav 2026-05-24, "это потом"). New Settings section / dedicated screen with donation channels surfaced from `funding.json`: Liberapay + Buy Me a Coffee as deep-links opening in browser; BTC / XMR / ETH as copy-to-clipboard + QR-code for the user's wallet camera. Source of truth = `funding.json` (either hard-coded mirror or fetched from `https://phntm.pro/funding.json` — pick at mini-lock review). Foundation already in place after 2026-05-24: funding.json in repo (PR #215) + on the domain (PR #218 + #219 hotfix). No mini-lock yet — Vladislav said "это потом".
4. **PR-D1e first-message bootstrap fast path** — yellow-dot 10–20 s on first text after `add contact` (`PREKEY_TRACE upload_fail SocketTimeoutException elapsedMs=8021`); same class as the H1e half-open WS pattern but on the prekey path. See `KNOWN_ISSUES.md` ISSUE-022.
5. **Network matrix Standard / Private / Ghost** — systematic re-verification of each transport on (a) clean Wi-Fi, (b) Tele2 LTE, (c) МТС Wi-Fi (no SIM cellular today), (d) emu-on-dev-Wi-Fi. Multi-session, multi-time-of-day; produces the `docs/project/CONNECTIVITY_MATRIX.md` public artifact promised in `feedback_strategy_decisions_2026_05_14.md`.
6. **Calls testing — C-track sequence.** C1 typed `TransportCapabilities` gate (replace `state == WsActive` shorthand) → C2 Reality endpoint pool + realistic probe (current `/health` probe doesn't catch the Tele2 Layer A silent WS-drop pattern) → C3 TURN-over-TLS on 443 or custom Opus-over-Reality (decide after C2 measures what Tele2 actually allows).
7. **Voice quality A/B** — confirmed 24 kbps OPUS mono 16 kHz as the production floor (`feedback_voice_quality_priority.md`); a future "Data Saver" 16 kbps toggle is gated behind this A/B.

### Deferred individual items (not in the locked-order queue above)

The items below are tracked durably but are not on the active queue. Most have been waiting for relay-side or operations work before they can land.

- **M2e early-manifest re-enable** — depends on receiver-side media-cancel relay protocol (see `KNOWN_ISSUES.md` ISSUE-023). M2e overlap is currently disabled in favour of correct cancellation semantics; long voices ship at `max(upload, download)` instead of the M2e parallel baseline.
- **Receiver-side media download cancel** — needs the two new relay endpoints sketched in ISSUE-023 (`DELETE /media/v3/{mediaId}` + `POST /media/v3/{mediaId}/cancel-pull`). Without these, the receiver bubble's X glyph is intentionally hidden.
- **PR-INFRA-MediaRO — second relay route diversity** — deploy a second `phantom-relay` at a different VPS / ASN (FlokiNET Romania candidate); allocate a media subdomain; design `mediaRelayId` extension to `VoiceManifestV2`; re-run the PR-M2c.0 probe against two `MediaProbeEndpoint` entries. Probe code preserved on `diag/m2c0-media-route-probe`. Needs VPS budget + a decision on auth model (federated identity vs per-relay registry).
- **PR-R0.5 / PR-PK1 — prekey upload response-drop fallback** — `/prekeys/publish` POST body lands server-side (201 ~3 ms) but the 18-byte response is dropped downstream on Tele2 ~30 % of the time. Three retries currently mask it but burn 30 s × 3. Fix idea: after POST timeout, GET `/prekeys/status/<identity>` and treat the soft-success path as terminal if `opks_remaining` / `spk_key_id` advanced.
- **PR-D0r follow-up — sealed-sender empty mirror** — review note from PR-D0r round; mirror envelope to REST store with `from=from_identity` when `sealed_sender` is empty. Currently not blocking but should be closed before next REST API surface change.
- **WS keepalive on Tele2 — diagnostic open** — phone-side `inbound_frames=0 pings_received=0` across every WS session lifetime (~31 s) on Tele2 LTE. Direct WS is honest "Online via Direct · Limited realtime" because realtime is unreliable; REST fallback covers text. Not actionable today; the C-track will need to address this if WS is resurrected as a primary realtime path.
- **PR-H2c — skipped-message-keys per Signal spec** — closes the third leg of the H2 reorder story (legitimate future-message reorder from TCP retransmit pauses, multi-path mobile, Tor circuit shifts). No production reproducer of legitimate reorder has surfaced since H2b landed.
- **UI stale-bubble track** — receiver `AudioBubble` briefly flashes "Downloading" after `download_complete` on some Test #70.2 chunk-size choices; Compose state-refresh issue. Tracked separately, no urgency.
- **`docs/calls-experimental` ADR refresh** — Track A item #4 marked ✅ merged but the doc itself is out of date relative to the C-track plan; either update or formally supersede.
- **93 non-docs `[gone]` local branches** — broader prune sweep awaiting Vladislav's explicit greenlight per `feedback_branch_prune_policy.md`.

### Historical / paused

- **Phase 1 connectivity matrix (Tests #43-#47)** — paused 2026-05-15 per strategic pivot; resumes if priority shifts back. Scope reference intact in memory `project_phase1_scope_2026_05_14`.
- **Tor bridge BridgeDB-on-device fetcher** — deferred (Briar doesn't have one either — bridge freshness, not the fetcher, is the bottleneck).
- **Cross-operator testing (Beeline / Megafon)** — blocked on hardware: Tecno SIM is Wi-Fi only since 2026-05-14; second phone with mobile data still incoming.
- **Tor-bridge rotation ADR** — architect-recommended retrospective, not started.

---

### Pre-2026-05-21 sub-sections (preserved for context, do not re-edit)

Below this point the original sub-section blocks (Reliability / transport, Calls, Repo / docs, Historical / paused) are preserved as the durable per-track journal. The **Consolidated queue** above is now the canonical "what's next" — these blocks are the per-item evidence trail.

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

### 2026-05-24 (sun, late) · Out-of-queue: serve funding.json on phntm.pro + email-placeholder hotfix

Two related PRs merged + deployed end-to-end during the same session. Out-of-queue (FLOSS/fund unblock continuation from PR #215).

**PR #218 (master `14236dd0`) — Caddy route + bind-mount.** Added explicit `handle /funding.json` block on the `phntm.pro` vhost (placed before `/terms`/`/privacy`/catch-all so it matches first) plus a single-file bind-mount of the repo-root `funding.json` into the caddy container at `/srv/funding/funding.json`. Headers per `fundingjson.org` spec: `Content-Type: application/json; charset=utf-8`, `Access-Control-Allow-Origin: *`, `Cache-Control: public, max-age=3600`. Single source of truth at repo root — no duplicate copies in `deploy/landing/` that would silently drift.

Deploy on VPS (Vladislav-side, found prod repo via `docker compose ls` → `/home/phantom/Phantom`):
```
cd /home/phantom/Phantom && git pull origin master && \
docker compose -f deploy/docker-compose.yml up -d caddy
```
Only caddy recreated; relay / tor / ntfy / xray / webtunnel-bridge untouched. `curl -I https://phntm.pro/funding.json` returned `HTTP/1.1 200 OK`, `Content-Type: application/json; charset=utf-8`, `Content-Length: 6378`, `Access-Control-Allow-Origin: *`, `Cache-Control: public, max-age=3600`, `cf-cache-status: DYNAMIC`. End-to-end JSON validate passed.

**PR #219 (master `073bc70f`) — email-placeholder hotfix.** PR #215's `funding.json` landed with literal `[email protected]` strings in two places (`entity.email` and `bank-wire.address`). Initially suspected Anthropic PII redaction in my tool I/O, but byte-level dump (`python` + `open('rb')`) showed `funding.json` physically contained the 17-character ASCII string `[email protected]` at the relevant offsets (hex `5b656d61696c2070726f7465637465645d`). Root cause: the source `D:\VL Stories Studio\funding.json` that seeded PR #215 was copy-pasted from HTML where Cloudflare's email-protection feature wrapped the original address in a JavaScript decoder tag. Copy-as-plain-text strips the decoder and leaves the placeholder.

Fix: byte-level `replace` via Python (Edit tool failed because Anthropic's PII filter masks `<localpart>@phntm.pro` strings in my tool inputs as identical to `[email protected]`, so `old_string != new_string` check failed). CRLF line endings preserved — exact 2-line diff. JSON re-parsed OK.

Deploy on VPS — first attempt with `docker compose up -d caddy` showed `STATUS: Up 19 minutes` (NOT recreated). Caddy continued to serve the old funding.json. Root cause: **single-file Docker bind-mount + `git pull`**. `git pull` updates the host file via `mv`, which creates a NEW inode at the same path. The container's mount-point still points to the OLD inode (now unlinked from the path). `docker compose up -d` without `--force-recreate` does NOT recreate the container if compose.yaml is unchanged — it just checks the config diff. So the container's mount stays bound to the old inode forever, until explicitly recreated.

Second attempt: `docker compose up -d --force-recreate caddy`. New container, new inode mapping. `curl https://phntm.pro/funding.json` then confirmed: `Content-Length: 6374` (was 6378; difference is exactly 2 × (17-15) bytes), `entity.email` length 15, `bank-wire.address` length 77, zero literal `[email protected]` substrings, 3 "legal" matches (2 email + 1 plan guid/name).

**Lesson hardened in agent memory:** any future `git pull` on the VPS that updates `funding.json` or any other single-file bind-mounted asset MUST be followed by `--force-recreate` on the affected service, NOT just `up -d`. Captured as `feedback_single_file_bind_mount_recreate.md`.

**Source-of-truth follow-up (Vladislav-side, NOT in this PR):** the local copy at `D:\VL Stories Studio\funding.json` that seeded PR #215 still contains the same Cloudflare placeholders. Vladislav to copy the post-PR-#219 master `funding.json` back over the local file so the next manual edit doesn't reintroduce the bug.

**Open follow-up generated during this session: row 10 in consolidated queue — PR-UI-SUPPORT-SCREEN.** Vladislav 2026-05-24: "нам еще в дальнейшем надо будет добавить возможность поддержки нас (то есть указать ссылки на все эти сервисы Buy Me A Coffee и другие варианты включая криптокошельки. но это потом". Working sketch (not a mini-lock yet — Vladislav said "потом"): new section in Settings → "Support PHANTOM" with cards per channel — Liberapay + Buy Me a Coffee as deep-links opening in browser; BTC/XMR/ETH as copy-to-clipboard + QR. Source of truth = `funding.json` (either hard-coded mirror in Android resources, or fetched from `https://phntm.pro/funding.json` at runtime). Optional short "why we accept these channels" rationale per channel for transparency. Foundation in place after today: funding.json in repo (PR #215) + on the domain (PR #218 + #219).

### 2026-05-24 (sun) · Out-of-queue: funding.json + README Funding section

Closed PR #215 (`feat/funding-json-update` → master `789c3c9e`). Two commits, two files (`funding.json` + `README.md`). Out-of-queue task per Vladislav, not part of the locked sequence — explicitly inserted to unblock the FLOSS/fund submission flow.

**What landed:**

- **`funding.json` replaced** with the post-pivot canonical version (was the Delaware/NLnet-era manifest from 2026-05). New manifest:
  - `entity.name = Willen LLC`, jurisdiction = Wyoming USA (confirmed by Vladislav 2026-05-24 against Articles of Organization — see memory entry `project_willen_llc_wyoming_2026_05_24.md`).
  - Channels: Liberapay, Buy Me a Coffee, Bitcoin, Monero, Ethereum, bank wire. Replaces the older GitHub Sponsors / Open Collective / Polar / operator-email set.
  - Plans: iOS port hardware unblock $2 000, external cryptographic audit $15 000, 12-month maintainer runway $60 000, production infrastructure $1 200/yr, legal/compliance baseline $1 500/yr (Wyoming registered agent + annual report + 5472+1120 federal filing + FinCEN BOI).
  - `history: []` (intentionally empty per fundingjson.org spec until first disclosure).
  - Validated: `python -m json.tool funding.json` → Valid JSON.
- **`README.md` Funding section rewrite.** Old `## Funding & sustainability` (philosophy paragraph about commercial managed deployments + priority support + encrypted backup as a sustainability track) was **dropped intentionally** — Vladislav's reasoning: that paragraph contradicts the way PHANTOM is positioned in every active grant application (FLOSS/fund, FUTO, Sovereign Tech all fund non-commercial FOSS, and the commercial-track paragraph created dissonance for grant reviewers). New `## Funding` section is the exact three-line text Vladislav specified: pointer to `funding.json`, list of donation channels (Liberapay, BMAC, BTC/XMR/ETH), and the maintainer/jurisdiction line "PHANTOM is maintained by Willen LLC, a company registered in Wyoming, USA." The previous "Willen LLC (Delaware, USA)" line — which was a historical typo from early-stage docs and never matched reality — is gone for good.
- **License badge already AGPL-3.0-or-later** (no change needed).
- **No NLnet references in README** to remove (already clean in master).

**Verification:** `curl -I https://raw.githubusercontent.com/LiudvigVladislav/Phantom/master/funding.json` → `HTTP/1.1 200 OK`, `Content-Length: 6378`, `Content-Type: text/plain; charset=utf-8`, `ETag: "2f05602cdcd74…"`. URL ready to paste into FLOSS/fund submission form.

**Branch protection note.** The original task brief said `git push origin main`. Direct push to the protected `master` branch is blocked by branch protection (would require admin-merge bypass + `--no-verify` skipping hooks, both forbidden by default policy). Landed via standard PR + squash-merge workflow — content-identical result, just respects the protection rules. Future tasks of the same shape should default to `gh pr create + gh pr merge --squash` rather than direct push.

**Discipline note.** The original session ended without a journal entry for PR #215 — Vladislav caught the omission ("ты внес в наш лог и документацию, то, что мы сделали…") and asked explicitly before authorising the next track. This entry is the catch-up. Out-of-queue tasks still need durable fixation per `feedback_durable_log.md` (2026-05-07). Adding a memory entry to harden this for future sessions.

**Locked queue unchanged:**

1. ✅ PR-DOC-HONESTY
2. ✅ PR-UI-REC-FOLLOWUP
3. ✅ PR-NOTIF-DIAG
4. 🟢 PR-UI-CHAT-AUTOSCROLL1 — **next**, Vladislav greenlit 2026-05-24 in the same message that flagged the missing journal entry. Mini-lock at `docs/tracks/chat-autoscroll.md`.
5. PR-NOTIF-POLICY1 — Variant A vs B decision at mini-lock review.
6. PR-D1e — first-message bootstrap.
7. Network matrix Standard / Private / Ghost.
8. Calls (C-track).
9. Voice quality A/B.

### 2026-05-23 (sat) · PR-NOTIF-DIAG — incoming-message notification path observability

Closed PR #213 (`feat/pr-notif-diag` → master `a0484602`). One commit, four files (`PhantomNotificationManager.kt` + `PhantomApplication.kt` + `AppContainer.kt` + `DefaultMessagingService.kt`), +295 / −39 lines. Test #78 PASS per architect verdict. Fourth PR end-to-end under `docs/WORKING_RULES.md` (REC3 → PR-DOC-HONESTY → REC-FOLLOWUP → NOTIF-DIAG).

**What landed.** Diagnostic-only PR per mini-lock at `docs/tracks/notifications-diag.md` (merged earlier same day as PR #212). Adds structured `PhantomNotif`-tagged logs at every step from "envelope arrives" to "Android shows heads-up". Zero behaviour change — no new heads-ups, no missed heads-ups, no fix attempts. Modelled on PR-Diag (#143) for the WS transport path.

Specifics:

- `DefaultMessagingService.onNewMessageNotification` signature gained a leading `source: String` parameter (closed enum: `text`, `voice_v1_assembled`, `voice_v1_chunk`, `voice_v2_manifest`). Used for diagnostic attribution only. New private `invokeIncomingNotificationCallback(...)` helper unifies the four invoke call sites under one `runCatching` with `NOTIF invoke_attempt / invoke_ok / invoke_threw` lines. `runCatching` semantics preserved; legacy `Invoking onNewMessageNotification callback (null=…)` text-path line retained for backwards compatibility with older triage habits; legacy `VOICE_RX notification_start/ok` on the legacy voice chunk path also retained alongside.
- `PhantomNotificationManager` got tag `PhantomNotif` and full show-path logging: `show_entry / api_level / permission_check (API 33+) / channel_check / skip reason=… / notify_called / notify_returned`. `createChannel` got attempt/created/skipped lines. `notificationId` logged on every show line (this turned out to be the smoking gun — see Finding below).
- `PhantomApplication.onCreate` now writes one `NOTIF app_snapshot …` line per process start: `permissionGranted=… channelExists=… channelEnabled=… channelImportance=… appNotificationsEnabled=… sdk=…`. Gives every test logcat an immediate snapshot of the device's notification posture.
- `AppContainer` wraps `onNewMessageNotification` callback with `PhantomNotif`-tagged `callback_invoked / callback_returned | callback_threw`. Old `PhantomMessaging`-tagged error log retained.

**Test #78 evidence (Tecno HiOS SDK 31, Wi-Fi, 8 incoming events captured):**

- 7 × `source=text`, 1 × `source=voice_v2_manifest`. All eight events produced the full chain: `NOTIF invoke_attempt → invoke_ok → callback_invoked → show_entry → api_level → channel_check (app_enabled=true channel_enabled=true channel_importance=4) → notify_called → notify_returned → callback_returned`. No `permission_check` line because SDK 31 is pre-Tiramisu (POST_NOTIFICATIONS not runtime). Zero `callback_threw`, zero `invoke_threw`, zero `SecurityException`, zero `AndroidRuntime`.
- The voice path also produced the M1w download chain in parallel (`MEDIA_RX manifest_acked_and_queued → download_progress 1/8 … 8/8 → download_complete → message_ready path=AUDIO_LOCAL`), confirming voice notification + voice download both work end-to-end on Tele2-via-Wi-Fi.
- Startup snapshot: `NOTIF app_snapshot channelExists=true channelEnabled=true channelImportance=4 appNotificationsEnabled=true sdk=31 permissionGranted=n_a_pre_33`. Permission/channel state is clean — flakiness is NOT a permission/channel cause.

**Primary finding (architect, 2026-05-23):** All notifications for one conversation share `notificationId = conversationId.hashCode() = 687143777` (`tag = null`). Android's documented behaviour for `notify(int, Notification)` with the same id is **update** — every new arrival **replaces** the previous heads-up in the same slot. This is not a notification-pipeline bug — the pipeline works exactly as instrumented. It IS the UX bug Vladislav noticed as "notifications sometimes disappear after firing": they didn't disappear, they were replaced. The fix is a notification *policy* decision, not a pipeline plumbing fix. Tracked as **PR-NOTIF-POLICY1** (mini-lock authored same day; Variant A vs Variant B decision at mini-lock review).

**Secondary finding (unrelated to notifications):** Vladislav surfaced "когда приходят сообщения, а ты находишься не в самом чате, и заходишь в чат с контактом, почему-то автоматом не скроллится вниз на новые сообщения". UI bug; not a notification-pipeline bug. Tracked as **PR-UI-CHAT-AUTOSCROLL1** (mini-lock authored same day).

**Discipline checkpoint.** Held scope strictly: didn't change `notificationId` strategy / channel config / `NotificationCompat.Builder` visuals / permission re-ask UX / FG-service notification, even though the perezatiranie finding stared us in the face. The right place for those changes is PR-NOTIF-POLICY1, not this PR.

**Open follow-ups generated by this track (added to consolidated queue):**

- **PR-UI-CHAT-AUTOSCROLL1** — fix `ChatScreen` not scrolling to bottom on open when unread messages arrived in the background. Mini-lock at `docs/tracks/chat-autoscroll.md`. Architect's recommendation: do this BEFORE the notification policy because it's a more visible UX bug that Vladislav already noticed by hand.
- **PR-NOTIF-POLICY1** — conversation-level notification with `InboxStyle` summary + unread count + clear-on-chat-open. Mini-lock at `docs/tracks/notif-policy.md`. Variant A (recommended) vs Variant B (per-message) decision deferred to mini-lock review.

### 2026-05-22 (fri) · PR-UI-REC-FOLLOWUP — metadata-derived voice duration + empty-voice gate

Closed PR #210 (`feat/pr-ui-rec-followup-duration-empty-race` → master `a625bde7`). One commit, one file (`ChatScreen.kt`), +92 / −5 lines. Test #77 PASS on Tecno (Vladislav verdict).

**What shipped.** Two fundamentals left over from REC2.4 / REC3:

1. **Recording-duration source fix.** Replaced the 100 ms ticker (Test #76.5b reproducer: `durationMs=8000` on a ~12 s voice; the ticker pauses or is destroyed across some Compose state transitions and undercounts ~33 % on long voices) with `MediaMetadataRetriever.METADATA_KEY_DURATION` read off the finished file after `stop()`. New private helper `readAudioDurationMs(file)` wraps the retriever in `runCatching` + `try { } finally { release() }`; returns `Long?` so the caller can fall back if the retriever cannot extract a positive value (some AAC_ELD-on-API-26–28 encoder edge cases). The `VOICE_REC complete` log now carries both `durationMs` (the final value used everywhere downstream) and `tickerMs` (old value, for diagnostic attribution) + `source=metadata|ticker_fallback`.
2. **Empty-voice safety gate.** New private constants `MIN_SENDABLE_VOICE_DURATION_MS = 700L` and `MIN_SENDABLE_VOICE_BYTES = 1024L` next to `MIN_HOLD_SEND_MS`. New gate inside `finalizeAndSendVoice` runs after `readBytes()` + `readAudioDurationMs`: if `finalDurationMs < 700` OR `bytes.size < 1024`, log `VOICE_REC drop_empty durationMs=… tickerMs=… bytes=… source=… reason=too_short_or_empty`, `runCatching { file.delete() }`, `return@launch`. The outer `finally` still resets `voiceSendInProgress`. Catches the warm-up race the gesture-layer 700 ms `MIN_HOLD_SEND_MS` cannot catch (Test #76.3 class: `heldMs=819 durationMs=0`, file ~98 bytes — gesture passed the gate but `MediaRecorder` produced nothing playable).

**Test #77 data points (Vladislav, real device).** Ticker undercount confirmed and fixed:

- Scenario A (3–5 sec): `heldMs=4239 tickerMs=3100 metadata=3718` — metadata wins.
- Scenario B (10–12 sec): `heldMs=11306 tickerMs=9900 metadata=11118` — ticker would have undercounted to 9.9 s on what was an 11.3 s hold.
- Scenario C (locked-send, ~14 s from recorder start to Send tap): `tickerMs=12600 metadata=14038` — metadata matches wall-clock; the ~3 s gap user perceived as "wait, it's 11 s" was actually the gap between locked-entry and Send-tap, not a duration miscount.
- Scenarios D (swipe-cancel) and E (sub-700 ms tap): no `VOICE_REC complete`, no `MEDIA_TX upload_*`, no new LazyColumn row — gesture-layer cancels still work, `drop_empty` gate is dormant in this run because every short tap was caught earlier by `MIN_HOLD_SEND_MS`. The new gate's branch is reachable; it just didn't trigger in the run because Vladislav couldn't reproduce the warm-up race today.

**Discipline checkpoint.** Third PR end-to-end under `docs/WORKING_RULES.md` (REC3 → PR-DOC-HONESTY → REC-FOLLOWUP). Mini-lock authored before code per rule 3 (the `rec-followup.md` lock landed inside PR #209 closing PR-DOC-HONESTY). Held scope strictly: group voice path (`GroupChatScreen.kt` `sendGroupAudio`) was tempting because it has the same bug class, but it was explicitly out-of-scope per the mini-lock; left untouched, logged as a follow-up in this entry. Rule 4 parking threshold never reached.

**Open follow-ups (added by this track):**

- **Group voice durationMs + empty-voice guard** — `GroupChatScreen.kt` `sendGroupAudio` still ships ticker `recordingDurationMs` and has no empty-voice gate. Same bug class as 1:1; needs a separate group-durability track because the group voice path also lacks durable chunk storage keyed by `groupId` (per the D2b.1 commit note). No urgency until group voice surfaces as a tested feature.
- **`tickerMs=` field cleanup** — once a few more real-device sessions confirm the metadata source is reliable, the `tickerMs=` field in the `VOICE_REC complete` log can be removed. It's there only for diagnostic attribution during the adoption phase.

### 2026-05-21 (thu, late) · PR-DOC-HONESTY — documentation honesty pass

Closed PR #208 (`docs/pr-doc-honesty` → master `d953b131`). Single PR, three logical commits, no code changes. Brings the project's docs from pre-M1w / pre-M2 / pre-REC state up to the production reality on master `8f4c68c9`. This is the second PR worked end-to-end under `docs/WORKING_RULES.md` (REC3 was the first).

- **`docs(known-issues): align with M1w/M2/REC reality (post-Alpha-1)`** (`1adc2d1e`) — `KNOWN_ISSUES.md` 451 → 605 lines. Title retitled "PHANTOM Alpha 1 — Known Issues" → "PHANTOM — Known Issues" (post-Alpha-1, no fixed release deadline). ISSUE-001 rewritten to cover the half-open TCP middlebox class diagnosed in PR-H1b + H1c/H1e Run C locked policy. ISSUE-004 marked ✅ RESOLVED by PR-H2b. ISSUE-006 marked ⚠️ PARTIALLY ADDRESSED. ISSUE-014 post-pivot. ISSUE-017 reframed under M1w. New ISSUE-018 (Tele2 Layer A WS), ISSUE-019 (Tele2 Layer B POST), ISSUE-020 (single-relay media ceiling 2400 b on v2 / ~3 × headroom on v3), ISSUE-021 (native OkHttp fresh-client-per-call pattern for RU LTE), ISSUE-022 (first-message bootstrap ~10–20 s delay), ISSUE-023 (receiver-side media cancel unsupported; blocks M2e re-enable).
- **`docs(adr,timeline): advance ADRs and kill superseded deadlines`** (`94bace93`) — ADR-011 `proposed` → `Accepted` (PR-H1c `e946caba` + PR-H1e `bcc501be`, Run C policy locked). ADR-023 `proposed` → `Accepted` (PR #56 `d862f3d0`; implementation footprint verified by grep on `KeystoreBlobCipher`, `AndroidKeystoreBlobCipher`, `SqlDelightLocal*PreKeyRepository.privateKeyCipher`, `AndroidKeystoreBlobCipherTest`). MASTER_TIMELINE: every calendar item killed by the 2026-05-14 strategic pivot + 2026-05-12 grants pivot marked with strikethrough + one-line cause line per Vladislav's lock #2 (June 1 release / June secondary funding / 25-day plan / NLnet draft V2 / FLOSS submission / Tag `v0.1.0-alpha.2` / June–July phased calendar / July-Sept / Oct-Dec / Q1 2027 / Phase 5 UnifiedPush 2027).
- **`docs(log): refresh current state and consolidate Open follow-ups`** (`ac15797c`) — new "What works today (master `8f4c68c9`, 2026-05-21)" block under the preserved Alpha-1 baseline. Consolidated "Vladislav-locked order, 2026-05-21" queue at the top of Open follow-ups (REC-FOLLOWUP → notifications diag → D1e → network matrix → calls → voice A/B). "Deferred individual items" + "Historical / paused" preserved.

**Discipline checkpoint.** Mini-lock (`docs/tracks/doc-honesty.md`) authored before scope per WORKING_RULES rule 3. Three logical commits inside one PR per Vladislav's PR-format lock. Strikethrough-not-delete per his cleanup lock. ADR-023 status decision gated on `git log --grep` + grep verification, not on optimism, per his ADR lock. Out-of-scope findings during the docs pass stayed logged as deferred follow-ups (e.g. text-bubble status icons under ISSUE-006). No "fix in passing" code edits — diff is strictly five docs files.

**Locked next-session start: PR-UI-REC-FOLLOWUP.** Recording-duration source fix (Test #76.5b: `durationMs=8000` on a ~12 s voice; the 100 ms ticker undercounts vs `MediaMetadataRetriever`) + empty-voice race (Test #76.3: `hold_release_send heldMs=819 durationMs=0`, gesture release passed the 700 ms gate but `MediaRecorder` hadn't captured anything). Both fix sites are inside `finalizeAndSendVoice`, not at the gesture layer — a mini-lock for that PR will be authored before code per WORKING_RULES rule 3.

### 2026-05-21 (thu, late) · PR-UI-REC3 SwipeCancel state visual

Closed PR #205 (`feat/pr-ui-rec3-swipecancel-visual` → master `770e61f4`). Five iterations on a single feature branch — a textbook execution of the new `docs/WORKING_RULES.md` one-PR-per-layer + mini-lock + iterate-on-the-same-branch discipline:

- **REC3** (`48a0f9fe`) — initial SwipeCancel render branch added to `InputBar`'s `recordingState != null` block: trail gradient (`Danger.22 → Danger.04`), dashed threshold marker at 72 %, trash handle on the right edge, "SWIPE TO DISCARD" hint, live % indicator, side-control dim to 0.4, both themes via existing tokens. Gesture detector untouched — REC2.4's swipe-left shim was already producing the cancel; REC3 made it *visible*.
- **REC3.1** (`bb511e3c`) — Test #76.6 fix: replaced two `align()` overlays with a single Row + weight=1 ellipsis hint so the % stopped overlapping the text; killed the latching `swipeCancelArmed` flag so the user can drag past 56 dp and back below it without arming-then-being-stuck. Release decision now uses live `finalDragLeftPx = (downX - change.position.x).coerceAtLeast(0f)` so what the user sees on the % indicator matches the actual gesture outcome.
- **REC3.2** (`1ff8a81a`) — shortened the hint to `"discard"` (matching Vladislav's screenshot) and added an animated arrow nudge via `rememberInfiniteTransition` + `animateFloat(targetValue = -3f)` so the affordance reads as motion-toward-discard.
- **REC3.3** (`70990a38`) — hide the Pause/Resume in-panel control while in press-hold Recording (`isPressHoldRecording = recordingState == Recording && isMicHeld`). Reasoning: the user's finger is on the mic, so they can't reach the in-panel button with the same finger anyway, and showing it during a hold-gesture was visual noise. Pause reappears in Locked, Paused, and Resumed-from-Paused.
- **REC3.4** (`2e0b3260`) — Test #76.6c fix: stopped flipping `isMicHeld = false` in the swipe-haptic block. That flip was carrying two responsibilities since REC2.4 — hiding the lock-hint chip at the 56 dp threshold AND (since REC3.3) gating Pause/Resume. When the user swiped past 56 dp, isMicHeld flipped, Pause reappeared briefly. Moved chip-hiding to a `swipeDragLeftPx <= swipeVisibleThresholdPx` check on the Popup so chip and Pause are gated by separate, intent-matching flags.

Test #76.6d PASS confirmed by Vladislav on real device — chip hidden through swipe, Pause hidden through press-hold, cancel arms/disarms cleanly, lock and paused render unchanged.

**Discipline checkpoint.** This is the first PR worked end-to-end under the new `docs/WORKING_RULES.md`. The mini-lock (`docs/tracks/rec3-swipecancel-visual.md`) was authored before the first commit per rule 3, with explicit scope / out-of-scope / parking conditions. Five iterations later, every fix was inside the agreed scope (UI render branch only, gesture-detector untouched, no transport / crypto / DB / chunk-size changes). Out-of-scope findings (durationMs source, empty-voice race, M2e re-enable, receiver-side cancel, notifications) stayed logged in `Open follow-ups`, none were "fixed in passing" per rule 7. No architectural reset was needed (rule 4 parking threshold never reached).

**Known follow-ups (open after REC3).** Same list as the prior session — REC3 was a visual layer over the already-shipped REC2.4 gesture, so the queue is unchanged:

1. **PR-UI-REC-FOLLOWUP** — recording duration source (ticker undercounts vs `MediaMetadataRetriever`).
2. **Empty-voice race** when `heldMs >= 700` but `durationMs <= 0` — fix inside `finalizeAndSendVoice`, not at the gesture layer.
3. **Re-enable M2e early manifest** once the relay grows a receiver-side media-cancel protocol.
4. **Receiver download cancel** needs new relay endpoints.
5. **Notifications flakiness diagnostic PR.**
6. **Doc honesty PR** — KNOWN_ISSUES expansion (Tele2 Layer A/B, M1w/M2 trilogy, REC*), ADR-011/-023 status advance, MASTER_TIMELINE killed-deadlines cleanup. Deferred from earlier today; now next on the queue.

### 2026-05-21 (thu, late) · Branch prune

Branch prune: deleted 6 stale branches per delete-blind policy. List: `feat/pr-m2b-download-parallelism`, `feat/pr-d2b2-flip-canSendVoice-and-15s-cap`, `pr-d1b-rest-fallback-wireup` (3 commits — diff-verified as squash-merged via PR-D1b #157/#158), `fix/prekey-publish-reliability`, `feat/probe-step-observability`, `docs/2026-05-15-merge-update`. Retained per memory: `diag/m2c0-media-route-probe`, `fix/transport-tcp-keepalive`, `infra/media-ro-bridge2-cotenant`. Side-finding (deferred for triage): 117 additional local branches still `[gone]` on origin — broader sweep awaiting Vladislav's call.

Branch prune (docs/*, 2026-05-21 follow-up): deleted 24 local-only docs/* branches per delete-blind policy (all ≤ 1 commit ahead of master and content squash-merged). 21 were already `[gone]` on origin (Tor / H1 / H2 / G3-G4 / M2 / Track A/B / NLnet-drafts / security-roadmap / Firebase-rotation / day-1-2-4 recap commits); 3 had no live remote tracking. List delegated to the commit message; no retained docs/* exceptions. 93 non-docs `[gone]` local branches remain — pruned later if/when Vladislav greenlights a broader sweep.

### 2026-05-21 (thu) · Voice recorder UX hardening — PR #197 Recording Panel Matrix states 3+4 (REC2 trilogy) + PR #198 outgoing-voice upload cancel (CANCEL trilogy)

**Branch state.** Two PRs squash-merged in sequence: `52a9773f` (#197 PR-UI-REC2 Locked + hold-to-lock + REC2.1 routing fix + REC2.2 lifecycle rewrite + REC2.3 gesture state machine + REC2.4 Locked-send routing + swipe-left-cancel + recorder crash hardening) and `b117dcb9` (#198 PR-MEDIA-UPLOAD-CANCEL1 + CANCEL2 + CANCEL2.1). Each PR went through 4–5 iterations driven by real-device test verdicts (#76, #76.1, #76.1.1, #76.1.2, #76.2, #76.3, #76.4, #76.5, #76.5b) before the architect signed off. All branches deleted on remote. Zero `AndroidRuntime` / `IllegalStateException` / `sha256_mismatch` / `decrypt_failed` / `media_chunks_gone` across the final passing test rounds.

**Context.** After 2026-05-20 closed REC1 (Recording + Paused states), the recorder UX still had three open ends: a Locked-hands-free state that the visual already supports but no gesture reached; a swipe-to-cancel state for accidental hold-mid-press; and an X-button on the uploading bubble that was a visual-only placeholder (`/* PR-UI follow-up — wire cancellation */`). Today's session closed Locked + the safety guardrail for swipe-left + the upload cancellation; SwipeCancel-state visual is queued as PR-UI-REC3 separately.

**PR-UI-REC2 (PR #197) — Locked state + WhatsApp-style hold-to-lock gesture.** Took five iterations to get the gesture lifecycle right. The original REC2 commit shipped a long-press-timeout-based detector inside `awaitEachGesture { … awaitPointerEvent() … if (elapsed >= longPressTimeoutMs) … }` plus a single `pointerInput(Unit)` Box that was supposed to host both mic-press and send-text/send-voice taps. Test #76 caught an immediate blocker: tapping the cyan Send-text arrow with text in the input field started a voice recording because the gesture detector dispatched every plain tap as a mic tap regardless of visual mode. REC2.1 (`3789223d`) fixed routing via `rememberUpdatedState` snapshots of `text` / `recordingState` / `isEditing` taken at press-down, plus defence-in-depth `inputText.trim().isNotEmpty()` guards inside both start-recording paths.

Test #76.1 PASSED routing but exposed the next layer: REC2.1's hold detection only kicked the recorder off at the long-press boundary, so a user holding the mic for ~500 ms got only ~14 ms of audio before they released (MediaRecorder.start() warm-up consumed the rest). Result: `VOICE_REC complete durationMs=0 bytes=98` ghost voice every short hold. REC2.2 (`bae8ba38`) moved the `startChatRecording(...)` call to press-down via `onMicClick()` so the recorder ran for the full press duration; the long-press timeout became cosmetic (lock-chip visibility + slide-up detector arming) and never restarted the recorder. Added `MIN_HOLD_SEND_MS = 700` gate so a hold + release inside the cancel zone dropped the recording silently instead of shipping a near-empty voice. Removed the dead `onMicPressHold` callback.

Test #76.1.2 PASSED short-hold safety but uncovered the structural bug: `awaitPointerEvent()` does not fire ticks for a stationary finger. An 8-second hold without movement produced ZERO pointer events between ACTION_DOWN and ACTION_UP, so `elapsed >= longPressTimeoutMs` was never evaluated, `didStartHold` stayed false, release fell through to "quick tap", and REC1 hands-free was the only outcome the user ever saw. REC2.3 (`11ca8168`) was a full rewrite as a real ACTION_DOWN / drag / ACTION_UP state machine — recording starts synchronously at press-down via `onMicDownStartRecording: () -> Boolean` (owns capability / busy / text-not-empty / permission guards), `onMicHoldReleaseSend(heldMs)` / `onMicHoldTooShortCancel(heldMs)` / `onMicSlideUpLock()` / `onSendVoiceTap()` callbacks map cleanly to pointer-up outcomes. REC1's tap-to-record hands-free was retired in this rewrite; user wanting hands-free now uses hold + slide-up-to-lock (WhatsApp parity). Right-side button split into two mutually-exclusive Boxes — text-mode (plain `clickable(onSend)`, **no** mic pointerInput) and mic-mode (persistent across mic ↔ send-voice with the new gesture detector).

Test #76.2 PASSED basic recording + Locked-entry but reproduced three new blockers Vladislav caught in a single session: tapping in-panel Send while in Locked state did not finalise the voice (logs showed `ignored_start reason=busy state=Locked` instead of `send_voice_tap`); dragging finger right-to-left then releasing after 700 ms actually SENT the voice instead of cancelling; the app crashed when `MediaRecorder.stop()` ran on a too-short / stale recorder. REC2.4 (`9e9c736e`) fixed all three: `currentIsSendVoiceVisual = rememberUpdatedState(recordingState != null)` so the gesture detector reads the LIVE visual mode (the plain `val isSendVoiceVisual` outside `pointerInput(Unit)` was frozen at first composition as `false`); new interim `swipeCancelArmed` flag tripped when `downX - change.position.x >= 56 dp` while pressed so a horizontal-only gesture cancels via the new `onMicHoldSwipeCancel(heldMs)` callback instead of falling into release-to-send; new `stopReleaseRecorderSafely(reason: String)` helper used by every recorder teardown path (`DisposableEffect.onDispose`, `finalizeAndSendVoice`, `onCancelRecording`, the capability-disabled tear-down, `onMicHoldTooShortCancel`, the new swipe-cancel) — nullifies the field BEFORE calling stop so a re-entrant teardown cannot double-stop, then `runCatching` both `stop()` and `release()` with a `VOICE_REC stop_failed reason=…` / `release_failed` log on failure. No raw `mediaRecorder?.stop()` remains in ChatScreen.

**Test #76.3 PASS** verified the full REC2 acceptance matrix on the merged build: `hold_record_start` at press-down, `locked_entered reason=hold_slide_up` on slide-up, `send_voice_tap state=Locked` → `locked_send` → `VOICE_REC complete` → `MEDIA_TX upload_complete` end-to-end, no `ignored_start reason=busy` after Lock, no `IllegalStateException`. SwipeCancel state visual (trail / trash icon / threshold animation) is still PR-UI-REC3 territory; REC2.4's swipe-left gesture is the smallest shim that closes the data-loss bug without rendering the full UX.

**PR-MEDIA-UPLOAD-CANCEL1 (PR #198) — wire the X button on outgoing uploading voice bubbles.** Three iterations. The architect carved this out as its own PR because the X-button-noop was a separate pre-existing bug surfaced by Test #76.3 (the recording / locked / swipe / crash fixes from REC2.4 were already correct), so we cut a fresh branch from `52a9773f` rather than bundling into REC2.

CANCEL1 (`a4e68102`) wired the full path end-to-end: `MessagingService.cancelVoiceUpload(conversationId, localMsgId): Result<Unit>` interface entry, `DefaultMessagingService` holds in-flight uploads in `voiceUploadJobs: MutableMap<localMsgId, Job>` (guarded by `voiceUploadJobsLock: Mutex`), `sendAudioV2` populates the map right after `scope.launch { … }` and removes in `finally`. `VoiceV2Sender.uploadVoice` + `uploadWithNetworkRetry` got `coroutineContext.ensureActive()` checkpoints before/after each chunk upload and each retry-backoff `delay`. UI: `AudioBubble.onCancelUpload: (() -> Unit)?` (null → X glyph hidden — receiver download bubbles intentionally don't show a tappable no-op), `MessageBubble.onReloadMessages: () -> Unit` (pinhole back to ChatScreen so the LazyColumn snapshot refreshes after the cancelled row is deleted).

Test #76.4 caught **five compounding architectural bugs** the architect spelled out one by one. CANCEL2 (`7635a134`) fixed them all in one commit:

1. **`runCatching` in `VoiceV2Sender.uploadVoice` swallowed CancellationException.** `runCatching` catches every `Throwable` including the cancellation. The cancel marker was converted into a normal `Result.failure(...)` and the `catch (CancellationException)` in DMS never ran. Replaced with explicit `try { Result.success(uploadVoiceInner(…)) } catch (ce: CancellationException) { throw } catch (t: Throwable) { Result.failure(t) }`. Body extracted into private `uploadVoiceInner` for readability.

2. **Early manifest poisoned the receiver after a cancel.** `sendAudioV2` was firing the manifest envelope after the first ~2 chunks (M2e overlap). A user cancel after that point left the receiver knowing about a media the sender then refused to complete — the receiver started downloading and got stuck on `MEDIA_RX chunk_not_ready_yet … reason=media_chunks_gone`. Until the relay grows a real receiver-side media-cancel / chunk-delete protocol there is no safe way to retract a manifest. `sendAudioV2` now passes `onEarlyManifest = null` and falls through to a single tail `sendManifestEnvelope(manifest)` after `uploadResult.isSuccess`. M2e overlap is temporarily disabled in favour of correct cancellation semantics; re-enable is queued as a follow-up that depends on the relay-side protocol.

3. **DMS cleanup was cancellable.** The `finally` block held a few suspend points (`Mutex.withLock`, `mediaProgressBus.clear`); once the upload coroutine entered the cancelled state every suspend re-threw `CancellationException`, aborted the rest of the cleanup, and left `voiceSendInProgress` stuck (Vladislav: "приложение не даёт записать новое голосовое"). Wrapped the entire catch+finally in `withContext(NonCancellable) { … }` so every suspend completes regardless of cancellation state.

4. **`job.cancel()` returned immediately.** UI side called `cancel()` and immediately ran `onReloadMessages()`, but the catch+finally inside the upload coroutine had not yet deleted the local row, so the reload still saw the cancelled bubble. Added `job.cancel(...)` → `job.join()` → new `MEDIA_TX upload_cancel_joined` log. UI-side reload only fires after the join returns.

5. **CancellationException marker matched too narrowly.** Depending on the wrap path inside the coroutine machinery, the marker can land on `ce.cause?.message` instead of `ce.message`. Added private helper `CancellationException.isUserUploadCancel()` that checks both.

Test #76.5 PASSED functionally — cancel actually stopped the upload, no `upload_complete`, no `early_manifest_sent`, no `media_chunks_gone` on the emulator, sender could record again. But the architect spotted that the DMS-side cancel logs were missing from logcat. CANCEL2.1 (`7684994a`) closed the diagnostic gap: `DefaultMessagingService.messagingLog(...)` writes under tag `PhantomMessaging`, while every test session uses the filter `PhantomMedia:V PhantomUI:V PhantomTransport:V *:S` which silences `PhantomMessaging`. By contrast `VoiceV2Sender` already takes a `log: (String) -> Unit` callback wired by `AppContainer` to `Log.i("PhantomMedia", …)`. The fix: new constructor parameter `DefaultMessagingService.mediaLog: (String) -> Unit = {}`, AppContainer wires `mediaLog = { msg -> Log.i("PhantomMedia", msg) }`, every cancel-path log site (`upload_cancel_requested` / `upload_cancel_dispatched` / `upload_cancel_joined` / `upload_cancel_noop` / `upload_cancelled_by_user` / `upload_job_cancelled`) writes through BOTH `messagingLog(...)` (existing tag preserved for the messaging history) AND `mediaLog(...)` (new PhantomMedia tag visible under the standard logcat filter). No behaviour change — pure observability fix.

**Test #76.5b PASS** confirmed the full cancel chain on Tecno Tele2 LTE → emulator: `MEDIA_UI upload_cancel_tap` → `MEDIA_TX upload_cancel_requested` → `MEDIA_TX upload_cancel_dispatched` → `MEDIA_TX upload_cancelled_by_user manifestSent=false` → `MEDIA_TX upload_cancel_joined` → `ChatScreen reloadMessages loaded=12` (was 13 before cancel). The in-flight `idx=2` chunk completed after cancel (acceptable — already on the wire); `idx=3` never started; no `manifest_sent`; no `upload_complete`; bubble disappeared from the LazyColumn; sender immediately recorded another voice without the legacy "voice is still uploading" lock.

**Known follow-ups (open):**

1. **PR-UI-REC3** — full SwipeCancel state visual (trail gradient, trash icon, dashed threshold border, 72 % distance indicator, disabled Send/Resume controls). Currently REC2.4's swipe-left handler cancels at gesture level but the visual stays as Recording during the swipe — REC3 will surface the cancel intent before release.
2. **PR-UI-REC-FOLLOWUP — recording duration source.** Test #76.5b logs show `VOICE_REC complete durationMs=8000` on a voice Vladislav reports as ~12 seconds. The ticker-based `recordingDurationMs` undercounts (likely because the timer ticker exits during state transitions). Right fix is to use `MediaMetadataRetriever` duration after `stop()`, or accumulate real elapsed session time, rather than the 100-ms ticker.
3. **Empty-voice race.** Test #76.3 logs show `hold_release_send heldMs=819 durationMs=0` — gesture release passed the 700 ms gate but MediaRecorder hadn't captured anything. The right fix is a `durationMs`-based check inside `finalizeAndSendVoice` (drop the row if `recordingDurationMs <= some minimum`), not another gesture-layer change.
4. **Re-enable M2e early manifest** once the relay grows a receiver-side media-cancel / chunk-delete protocol. Until then `sendAudioV2` ships voices sequentially (upload → tail manifest → download); long voices take longer than they did at the M2e baseline. Pure trade-off for safe cancellation.
5. **Receiver download cancel** needs new relay endpoints (chunk-delete / mediaId-revoke) before the receiver-side X can do anything useful. Hidden in this PR; tracked separately.
6. **Notifications flakiness** still on the queue from Test #75 — diagnostic PR before any fix attempt.

**Memory entries.** No new entries created; the voice-recorder gesture model is captured in the inline `// PR-UI-REC2.x` and `// PR-MEDIA-UPLOAD-CANCEL2.x` source comments rather than as agent-memory entries (where they would rot quickly against the next iteration).

### 2026-05-20 (wed) · Voice UI polish day — PR #192 Voice Bubble Matrix + PR #193 M2h.1 fresh-default + PR #194 AudioBubble playback + PR #195 Recording Panel Matrix states 1+2

**Branch state.** Four PRs squash-merged into master in sequence: `62fa397e` (#192 Voice Bubble Matrix + midnight day-sep + text-bubble timestamp footer), `d50c8e31` (#193 PR-M2h.1 — `useDownloadPool` default flipped `true → false` after Test #72), `b0f2b135` (#194 PR-UI-VB1 — AudioBubble pause keeps progress + preload duration + 4-state label), `08ccf906` (#195 PR-UI-REC1 — RecordingPanel composable wiring states 1 + 2). All four branches deleted on remote. Three real-device test rounds (Test #73 on the combined M2h.1 + VBM build, Test #74 on VB1, Test #75 on REC1) — every round green on Tecno Tele2 LTE + emulator with zero `sha256_mismatch` / `decrypt_failed` / `media_chunks_gone` / `AndroidRuntime` / `java.lang.*`.

**Context.** After M2f.2 closed the transport-layer voice-delivery work on 2026-05-19, the dominant remaining track was visual polish on the voice surface (recording panel, sent bubble, received bubble, playback state) plus the M2h pooled-download verdict from Test #72. The day chained those four PRs sequentially — each one shipped as its own APK, tested on real hardware, merged on PASS, before the next branch was cut from a fresh master. No batched APKs ("M2h.1 + VBM + VB1 + REC1 in one build") so that any regression could be attributed to a single PR.

**PR-M2h.1 — flip `useDownloadPool` default `true → false` after Test #72.** Test #72 (run 2026-05-19) had probed M2h's pooled-OkHttp download path on the emulator and on Tecno over Tele2 LTE. The emulator returned ~100 ms / chunk on the pooled path — a real speed-up over fresh-per-call. But on Tecno the **first** pooled GET stalled 10 013 ms with `InterruptedIOException` before the sticky pool-disable guard tripped; the safety net then carried the voice through on fresh-per-call without integrity errors. Architect verdict: pool reuse is a real win on healthy routes, but a 10-second extra delay on every first incoming voice over Tele2-class networks is too costly to ship as the production default. PR-M2h.1 (`shared/core/transport/.../AndroidNativeOkHttpMediaUploadTransport.kt`) flipped only the default value (`@Volatile private var useDownloadPool: Boolean = true → false`) and added `fun setDownloadPoolEnabled(enabled: Boolean)` so a debug Settings toggle or future adaptive-per-network probe can flip the pool on at runtime; the sticky-disable logic, `POOL_STALL_THRESHOLD_MS`, `download_pool_fallback` telemetry, and the entire pooled `OkHttpClient` instance remain in code. No transport / relay / chunk-size / parallelism / crypto changes. Test #73 (run on the combined M2h.1 + VBM build) confirmed every `MEDIA_V3 download_start` line carried `mode=fresh` from `idx=0` on both devices — zero `mode=pooled`, zero `download_pool_fallback`. M2h closed as fresh-default with pool-as-runtime-opt-in; the adaptive A/B probe (background `n×` healthy round-trips → enable pool for this network) is deferred to a future PR.

**PR #192 — Voice Bubble Matrix (sent + received voice bubbles) + midnight day-separator tick + text-bubble timestamp footer.** Three orthogonal visual fixes bundled per Vladislav's preference for one-PR-per-track on UI work. (a) **Voice Bubble Matrix visual.** The old `AudioBubble` was a single Surface2 pill with a 36-bar fixed-shape waveform that could overflow the canvas on narrow widths, a double-bordered parent `MessageBubble` frame around it, and a Stop glyph instead of Pause during playback. The Matrix design (`D:\VL Stories Studio\Phantom - Messengers-handoff\phantom-messengers\project\Voice Bubble Matrix.html`) ships a 36 dp disc on the left (cyan-dark `#0099BB` background while playing, surface-elevated otherwise, with a rotating arc during load), a scrolling 36-bar waveform whose bars scale to the canvas width via `Canvas` `drawRoundRect`, a right-column percent-or-duration label in JetBrains Mono, and a footer meta row (`HH:MM` + status icon + speed pill). Parent `MessageBubble` drops the double frame for audio rows so AudioBubble fully owns the visual. Pause glyph swapped from Stop (square) to two distinct vertical bars. (b) **Midnight day-separator tick.** `ChatDateSep` had been computing its "TODAY" / "YESTERDAY" label from `Calendar.getInstance()` directly, but Compose marks the composable as skippable on `(millis: Long)`, so once the chat had been rendered before midnight the separator captured the label once and never recomposed after 00:00 — yesterday's messages stayed labelled "TODAY" while new ones also showed "TODAY" (Vladislav screenshot 2026-05-20). Fix: hoist the start-of-today wall-clock into a screen-level `rememberDayStartMillis()` state that delays past the next local midnight and refreshes itself via `LaunchedEffect`; `ChatDateSep` now takes the `dayStartMillis` parameter and the label recomposes whenever the state ticks. (c) **Text-bubble timestamp footer.** The single-line text bubble used a `Box` overlay (Text with 36/56 dp end-padding + Row aligned `BottomEnd`), which made the bubble grow to widest-wrapped-line + the reserved padding. When the *last* wrapped line was short (e.g. "идут долго") that left a visible empty gap between the text end and the timestamp. Fix: drop the overlay, stack the meta row below the text inside the existing bubble Column with `Modifier.align(End)` on the meta — bubble now hugs the widest wrapped line and the meta sits tight on its own footer row, consistent with the new AudioBubble meta layout. **Test #73** verified all three on Tecno + emu: voice bubbles single-framed and waveform-scaled, day separator showed "TODAY" only for today's messages (full midnight-rollover behaviour is a quieter follow-up that needs an actual 00:00 crossing to verify), multi-line text bubbles hugged their content with no whitespace before the timestamp.

**PR-UI-VB1 — AudioBubble playback fixes (pause keeps progress + preload duration).** Two playback UX bugs Vladislav caught on Test #73 with the VBM build: (1) `val waveProgress = if (isPlaying) playProgress else 0f` zeroed the waveform the moment the user tapped Pause, so paused looked identical to stop; the right-column label also collapsed to total-only, hiding the elapsed time. (2) `durationMs` was only set inside `togglePlayback()` after `MediaPlayer.prepare()`, so every voice in the chat showed `—:——` until the first tap of Play. Architect's review of Test #73 confirmed both as the primary blockers. Fix: (a) `waveProgress = playProgress` always — `MediaPlayer.onCompletionListener` is the single owner of the reset back to 0. (b) Right-column label has four states wired explicitly: idle ready (total only), playing (`current / total`), paused-with-progress (`current / total` — driven by a derived `hasPlaybackPosition = playProgress > 0 && < 0.999`), completed (total only after `onCompletion` zeroes `playProgress`). (c) New `LaunchedEffect(plaintextCache, isLoading)` extracts duration via `MediaMetadataRetriever` on `Dispatchers.IO` as soon as the bubble enters a ready state; for `[AUDIO_LOCAL:<path>]` the underlying VoiceFileStore file is read directly (no copy), for legacy `[AUDIO:<base64>]` bubbles the decoded bytes are written into `cacheDir` once and reused for both duration extraction and playback. (d) `togglePlayback()` seeks back to 0 when the MediaPlayer sits at the end of the clip — tapping Play after Completion now restarts the clip instead of returning instantly. The legacy temp-file is deleted on composable dispose; the underlying VoiceFileStore file is left alone. **Test #74** (VB1-only APK, sha256 `a372f653…`) on Tecno + emu PASS — emu downloaded a 9-chunk voice cleanly (`download_complete plainBytes=26804`), recorded a new 5.5-sec voice (`VOICE_REC complete durationMs=5500 bytes=25129`), no `pause_failed` / `resume_failed` / `AndroidRuntime` lines.

**PR-UI-REC1 — Recording Panel Matrix (states 1 + 2 only).** The recording panel handoff (`D:\VL Stories Studio\Phantom - Messengers-handoff Recording Panel\phantom-messengers\project\Recording Panel Matrix.html`) ships a 56 dp composer-row variant with four sub-states: Recording (live), Paused, Locked, SwipeCancel. Per Vladislav's confirm-message before implementation, this PR delivers **states 1 + 2 only** at the visual layer plus the state machine and amplitude poller needed for the remaining two to slot in cleanly later. (a) **`RecordingPanelState` enum** (Recording / Paused / Locked / SwipeCancel) — the two unreachable values are declared so PR-UI-REC2 (hold-to-lock) and PR-UI-REC3 (swipe-to-cancel) can add render branches without re-touching the state machine. (b) **State machine refactor:** `var isRecording: Boolean` → `var recordingState: RecordingPanelState?` in ChatScreen; new 100 ms timer LaunchedEffect runs only while state is Recording or Locked; new 80 ms amplitude poller LaunchedEffect calls `MediaRecorder.getMaxAmplitude()`, normalises to `[0..1]`, appends to a 64-entry SnapshotStateList ring buffer. The reset-to-0 of `recordingDurationMs` is owned by the start path (mic-button start + permissionLauncher), not by the ticker — a Recording → Paused → Recording round-trip preserves both `recordingDurationMs` and `recordingAmplitudes`, which fixes the architect's flagged "current LaunchedEffect(isRecording) resets recordingDurationMs to 0" pitfall. (c) **`MediaRecorder.pause() / resume()`** wired (API 24+; minSdk = 26 so no runtime guard). Errors logged as `VOICE_REC pause_failed` / `VOICE_REC resume_failed` but do not crash the panel. (d) **`RecordingPanel` composable** matches the Matrix design exactly — 56 dp row of Cancel · Center stack (8 dp Danger pulsing dot with aura `tween(500) Reverse` opacity 0 → 0.22 scale 0.7 → 1.0; JetBrains Mono 13 sp tabular-nums timer; DAW waveform with last 26 samples as "recent" / rest as "older"; PAUSED capsule when state = Paused) · Pause/Resume (44 dp Surface2 disc, glyph swap only) · Send (44 dp cyan disc, same as text composer). InputBar gates the new panel at the Column level — when `recordingState != null` the whole row is `RecordingPanel`, otherwise the row is the unchanged emoji + textfield + send/mic. The legacy "Recording 0:17" pill plus the per-side cancel(X) + send-arrow inside InputBar are gone. **Test #75** (REC1-only APK, sha256 `2ab815f9…`) on Tecno + emu PASS — multiple `VOICE_REC complete` + `MEDIA_TX upload_complete` cycles, no `pause_failed` / `resume_failed`, panel transitions Recording → Paused → Recording cleanly with `recordingDurationMs` preserved.

**Out of scope (queued).** State 3 Locked + hold-to-lock gesture → **PR-UI-REC2**. State 4 SwipeCancel + drag-gesture (drag the mic toward a discard threshold, trail + trash handle + disabled controls + return to Paused/Recording when released before the threshold) → **PR-UI-REC3**. Light theme variant — app is dark-only for Beta. The `RecordingPanelState` enum already includes the Locked + SwipeCancel values so REC2/REC3 add a render branch in `RecordingPanel`, a gesture handler in InputBar, and a new state transition — no state machine refactor required.

**Notification flakiness — separate diagnostic track.** Vladislav reported on Test #75 that notifications sometimes appear and sometimes don't, with occasional disappearance after firing. This pre-exists today's work (was not introduced by any of the four PRs that merged) and is explicitly out-of-scope for the voice-UI track. Queued as a diagnostic PR after REC2/REC3 lands — first step will be a logcat instrumentation PR similar to PR-Diag (#143) on the WS path, not a fix attempt, because the failure mode is not yet root-caused.

**Known follow-ups (open, not blocking the next track):**
1. **PR-UI-REC2** — Locked state + hold-to-lock gesture. Risk: current mic-button click is the start/stop toggle; layering a long-press → lock gesture on top of it needs care to avoid eating regular taps.
2. **PR-UI-REC3** — SwipeCancel state + drag gesture. Discard threshold with continuous visual feedback (red trail + dashed boundary + trash handle); release before threshold → return to Paused.
3. **Notification flakiness diagnostic PR** — logcat instrumentation only, no fix attempt yet.
4. **PR-D1e** — first-message bootstrap fast path (still open from M2 trilogy follow-ups).
5. **PR-M2h adaptive A/B** — eventually re-enable pool only on networks where a background probe passes; sits behind notifications + REC2/REC3 in priority.

**Memory entries.** No new entries created today — all four PRs are within the existing voice / UI tracks captured in `project_m2_track_status_2026_05_19.md`, `project_m2d_roadmap_2026_05_18.md`, and `feedback_voice_quality_priority.md`. The notification flakiness item is logged in the in-conversation todo list rather than memory because it has no root cause yet.

### 2026-05-19 (tue, evening) · M2f trilogy closed — production voice chunk size locked to 3200 bytes after Test #69/#70/#70.1/#70.2/#71

**Branch state.** Four PRs squash-merged into master in sequence: `ef74ada3` (M2f, binary `/media/v3` endpoints + media_capabilities advertisement), `9ab7f872` (M2f.1, debug-only Settings selector for chunk size), `c98b5c72` (M2f.1b, selector extended with 2800/3000/3200/3500), `ac310718` (M2f.2, production chunk size lock 1700 → 3200 + byte-based early manifest). All four branches deleted on remote. No code-level regressions observed across the five real-hardware test rounds; the only visible artefact carried forward is a UI-track follow-up.

**Context.** After PR-M2e shipped the upload/download overlap, the dominant cost on long voice was the chunk count itself — at 1700-byte raw ciphertext per chunk, a 30-second voice produced ~82 sequential HTTP round-trips on each side. PR-M2c.0 (2026-05-18) had concluded that bigger chunks weren't feasible on Tele2 LTE because the Helsinki path's full-roundtrip ceiling was ~2400 wire bytes. But that measurement was taken on the v2 JSON+Base64 path, where 1700 raw inflated to ~2388 wire bytes; the Tele2 ceiling was already nearly maxed before any chunk-size headroom. The M2f trilogy unwinds this by moving the wire format itself, then probing the real binary ceiling.

**PR-M2f — binary `/media/v3` endpoints (additive, v2 kept).** Adds `POST /media/v3/{mediaId}/{idx}?total=N` (raw ciphertext body, always 204 + `X-Chunk-Stored/Duplicate/Idx` + `ETag: "<sha256_hex>"`) and `GET /media/v3/{mediaId}/{idx}` (`application/octet-stream` body, `ETag` + `X-Chunk-Total`) alongside the legacy v2 pair. The v3 path shares the same in-memory `MediaStore` as v2 (idempotency by `sha256(ciphertext)`, same total/quota/idx checks). 7 new integration tests added to `services/relay/tests/media_endpoints.rs`; all 23 media tests pass. Capability discovery via the extensible `media_capabilities: { binary_v3: true, max_upload_body_bytes: N }` nested object in `/auth/session` response — older relays without the field default to `binary_v3=false` and the client stays on v2 (kotlinx-serialization safe default). `AndroidNativeOkHttpMediaUploadTransport` accepts a `binaryV3Enabled: () -> Boolean` lambda wired to `restOrchestrator.capabilities.value.mediaBinaryV3`, with a sticky 404/405 fallback to v2 that triggers only on the capability-stale runtime case (network errors stay in the existing retry loop). `RelayCapabilities` gains `mediaBinaryV3` and `mediaUploadBodyBytes` fields; `SAFE_DEFAULTS` keeps both off. **Test #69** confirmed: every upload chunk through `MEDIA_V3 upload_response status=204`, every download chunk through `MEDIA_V3 download_response status=200 bytes=1700`, zero `MEDIA_V3 fallback`, zero `MEDIA_HTTP` (v3 fully owns the path), voice playable both ends. Architect verdict: "M2f reduces wire overhead but does not change chunk count yet; download per-chunk RTT (~1.1s) is the new bottleneck — chunk-size bump next."

**PR-M2f.1 — debug-only chunk-size selector.** Adds `phantom.android.diagnostics.ChunkSizeProbe` with `isProbeAvailable = BuildConfig.DEBUG` and a 5-size candidate matrix (1700/2200/2300/2400/2600). `VoiceV2Sender.uploadVoice` accepts a `chunkSizeProvider: () -> Int` lambda (default `MediaChunker.TARGET_RAW_CHUNK_BYTES = 1700`), clamped `[1024, 8000]` as defence-in-depth, logged as `MEDIA_TX chunk_size_selected mediaId=... bytes=N source=provider` once per voice send (plus `chunkSizeBytes=N` added to the existing `chunk_split` log). Settings → Diagnostics shows a `DropdownMenu` only on debug builds; release strips the entire section. `AppContainer` wires the provider to `ChunkSizeProbe.currentValue(prefs)`. **Test #70** ran the matrix and all four sizes above 1700 passed full roundtrip Tecno → emu with zero integrity errors — including 2600 which had been seeded as a borderline negative control. The old "Tele2 ceiling ~2400" assumption was invalidated: on the v3 binary path the 33% JSON+Base64 inflation that v2 paid is gone, freeing ~600-700 bytes of headroom.

**PR-M2f.1b — selector extended with 2800/3000/3200/3500.** One-line PR. Vladislav-locked path B 2026-05-19: probe higher before locking production. **Test #70.1** ran the extended matrix Tecno upload → emu download; all four sizes passed with zero errors. But this proved only half — emu was always the receiver, so Tele2 download path wasn't tested. **Test #70.2** ran the matrix in reverse (emu sender, Tecno receiver on Tele2 LTE) for both ranges (2200/2300/2400/2600 + 2800/3000/3200/3500). All eight sizes passed full roundtrip. 3200 and 3500 logs showed several `MEDIA_RX chunk_not_ready_yet retry_in=1000ms` entries — this is the M2e early-manifest path doing exactly what it should: receiver overtakes sender tail-upload, gets 404 on a chunk that hasn't landed yet, backs off 1s, retries, succeeds. Architect verdict: "Tecno on Tele2 LTE downloads v3 binary chunks up to 3500 cleanly; the old 2400 ceiling does not apply on v3." Test #70.2 surfaced one UI-track follow-up: on 2800/3000 the receiver bubble briefly flashed "Downloading" again after `download_complete`, but the file was on disk and playable after re-entering the chat — a Compose state-refresh issue, not a transport regression.

**PR-M2f.2 — production lock 3200 + byte-based early manifest.** Per Vladislav's locked margin policy ("never ship max-passed-once; 3500 stable both directions → ship 3200"), `MediaChunker.TARGET_RAW_CHUNK_BYTES = 1700` → `3200`. Critical architectural fix bundled in the same PR: `VoiceV2Sender.EARLY_MANIFEST_AFTER_CHUNKS = 3` constant replaced with `EARLY_MANIFEST_AFTER_BYTES = 5100` + dynamic recompute `max(1, ceil(5100.0 / chunkSize)).coerceAtMost(total)`. The prior fixed value was tuned for 1700 (3 × 1700 = 5100 bytes proof-of-life). At 3200 it would have delayed the manifest to 9600 bytes — almost double the original M2e budget — and noticeably weakened the upload/download overlap. The byte-based formula yields 3 chunks at 1700/2400, 2 chunks at 3200/3500, preserving M2e timing across the entire chunk-size knob. `MediaChunkerTest` updated: one hard-coded `1700` literal replaced with the constant; misleading test method name dropped (others were already self-adapting). All 7 messaging tests green on JVM. Debug selector stays in DEBUG-only Settings for future M2h probes and a future voice-quality A/B. **Test #71** sanity (production parameters, both directions on Tele2 LTE Tecno + emu): `MEDIA_TX chunk_split chunkSizeBytes=3200` confirmed on both ends, `early_manifest_sent afterChunks=2 total=8/44` confirmed for long voice, zero `sha256_mismatch` / `decrypt_failed` / `media_chunks_gone` / `MEDIA_V3 fallback`.

**Production speed snapshot after M2f.2** (30-second voice on Tele2 LTE, both directions):

| Metric | Pre-M2 trilogy (1700) | After M2f.2 (3200) | Δ |
|---|---|---|---|
| Long-voice chunk count (Tecno-sized) | ~82 | ~44 | -46 % |
| Tele2 upload wall time | ~78 s | ~26 s | -67 % |
| Tele2 download wall time | ~90 s | ~15 s | -83 % |
| Emulator download wall time | ~90 s | ~48 s | -47 % |
| Total user-visible delay (`max(upload, download)`) | ~150 s | ~50 s | -67 % |

(Tecno download dropped further than Emu download because the path is now ~1.1 s/chunk for 22-44 chunks vs ~1.1 s/chunk for 82 chunks. The Emu path is the slower receiver in this matrix simply because the sender is also Tecno on Tele2 LTE; the per-chunk emu-side cost is similar but starts later because of the upload-tail dependency. Once both ends are post-overlap, `max(upload, download)` is the real wall-clock cost.)

**Emulator-side bytesPerSec anomaly noted in Test #71** — Tecno's `MediaRecorder` writes ~4500 bytes/s for 30-second voice (44 chunks), but the emulator's `MediaRecorder` writes ~2200 bytes/s (22 chunks) at the same OPUS/AAC profile. This is a Android encoder/microphone variance (different VBR behaviour + ambient silence on the emulator audio pipeline), not a chunking bug. The chunker just slices the produced ciphertext at 3200 bytes; if the file is half-sized, chunk count halves.

**Known follow-ups (open, not blocking M2 track):**
1. **PR-M2h** — pooled OkHttp download A/B. After M2f.2 the per-chunk download cost (~1.1 s) is the dominant remaining bottleneck. Hypothesis: each fresh client + fresh TLS handshake pays ~600-800 ms that a pooled connection would amortise. Risk: Tele2 may close pooled HTTP/1.1 connections aggressively (the same class of issue PR-R0.1/R0.3/M1w-R4 mitigated for other endpoints by going fresh-per-call). Therefore A/B with a runtime fallback to fresh-per-call on stall/timeout, not a blind switch.
2. **PR-D1e** — first-message bootstrap fast path. Every test in the M2 trilogy reproduced the same symptom: after adding a new contact, the first text message hangs ~10-20 s with a yellow dot before delivering. Logs pinpoint `PREKEY_TRACE upload_fail SocketTimeoutException elapsedMs=8021` on the contact-bootstrap publish. Architect-locked fix: prewarm prekey bundle after contact add, REST-first send for bootstrap in Limited realtime, shorter ACK timeout before REST fallback. Unrelated to media path.
3. **UI stale-bubble track** — receiver `AudioBubble` briefly flashes "Downloading" again after `download_complete` on some Test #70.2 sizes; re-entering the chat refreshes it. A Compose state-refresh issue tracked separately from transport.

**Memory entries.** `project_m2d_roadmap_2026_05_18.md` (multi-PR sequence, now showing M2d.1a/M2d.1b/M2e/M2f/M2f.1/M2f.1b/M2f.2 closed and M2h/D1e/UI as remaining) and `project_m2e_locked_params_2026_05_19.md` (fresh-task window math — kept since the parameters still apply at the receiver level).

### 2026-05-19 (tue) · Voice-delivery acceleration trilogy merged — PR-M2d.1a (#182) + PR-M2d.1b (#183) + PR-M2e (#184)

**Branch state.** Three PRs squash-merged into master back-to-back: `2a9ba08c` (M2d.1a, 204 No Content + idempotency-aware retry), `f804e0d8` (M2d.1b, progress UI for upload/download), `fd53b676` (M2e, early manifest + overlap). All three branches deleted on remote. Visual polish (PR-M2d.1b commit on top of the original skeleton) is part of the same squash. No regressions on text-message path across any of the three real-hardware tests.

**Context.** After PR-M2c.0 rejected bigger chunks on the Helsinki path, the audit's third-architect analysis recommended a different lever: keep the chunk size, fix the things on top of it. Three orthogonal wins were sequenced one PR at a time so each could be reverted independently if it regressed. None did.

**PR-M2d.1a — 204 No Content on upload + idempotency-aware retry.** The 31-second per-chunk gap on Tele2 (visible in Test #62 and confirmed by VPS-side relay logs serving in single-digit ms) was an OkHttp body-read timeout: the relay returned 201 JSON, the response body was small, but Tele2 Layer B dropped it on the way to the phone. The fallback retry then re-uploaded already-stored chunks. Fix: client sends `Prefer: return=minimal` (RFC 7240), relay branches on a `wants_minimal` header check and returns 204 with `X-Chunk-Stored/Duplicate/Idx` headers + `Cache-Control: no-store`. Client reads `statusCode + headersOnlyElapsedMs` BEFORE body read; on 204 there is no body to drop. `bodyReadError` is captured into a separate variable so the legacy-path fallback (`if bodyReadError != null && statusCode in [200, 201]` → success) only fires when the relay actually accepted the chunk — 401/409/413/5xx still propagate as errors. Relay tests: 3 new test cases covering `Prefer` accepted (stored), `Prefer` accepted (duplicate), `Prefer` absent (legacy 201/JSON). Test #67a (5-sec voice + 35-sec voice on Tecno Tele2 → emulator under VPN): all upload responses `status=204 bodyMs=0` (or =1) on Tecno. 5-sec voice: 15 chunks × ~720 ms ≈ 10.8 s upload + 0.9 s manifest = 11.7 s on phone side. 35-sec voice: 105 chunks × ~727 ms = ~77 s upload. The transport-layer bug class (Tele2 body-drop false-timeout) was confirmed closed; the user-visible slow time was now obviously the N sequential `headersMs` round-trips per chunk, which M2d.1a did not address. Architect verdict: PASS for the narrow goal, do not expect this alone to speed up long voice.

**PR-M2d.1b — live N/M progress UI for sender and receiver.** Without a counter the user could not tell whether the long upload/download was alive or hung. Added `MediaProgressBus` (in-memory `StateFlow<Map<String, Progress>>` — zero DB writes, cleared on completion/failure). `VoiceV2Sender.uploadVoice` and `VoiceV2DownloadOrchestrator.runDownloadTask` accept progress callbacks and emit `MEDIA_TX upload_progress sent=N total=M` / `MEDIA_RX download_progress received=N total=M` per chunk. DMS wires callbacks (sender keyed by `localMsgId`, receiver keyed by `mediaId` because the receive-side row PK == mediaId per the M1w handler) and clears the bus in `finally`. `AppContainer` exposes the bus, `ChatScreen.MessageBubble` collects via `collectAsState()` and passes the row's progress entry to `AudioBubble`, which renders `Uploading N/M` (sender) or `Downloading N/M` (receiver) with a live progress bar. Also fixed a UI-side recorder re-entry bug surfaced during Test #67b (after a 5-sec voice finished, a new VOICE_REC config fired 334 ms later — looked like a bounced tap or stray touch event reusing the mic onClick): added a `voiceSendInProgress` Compose state that blocks the mic button until `sendAudio` returns, with `VOICE_REC ignored_start reason=finalizing_or_sending` logged on bounced taps. **Test #67b** caught two bugs: recorder re-entry (closed in 67b.1), and the original M2d.1b condition `status == MessageStatus.UPLOADING && progress != null` was too strict — logs proved the row was being loaded as `status=QUEUED` (the `reloadMessages()` snapshot ran between the DB INSERT and the `updateStatus(UPLOADING)` commit on the sender path), so the upload bubble never lit up on the sender side. **Test #67b.1** dropped the status gate (just `isSent && progress != null && direction == UPLOAD` — the presence of a bus entry IS the live-upload signal) and added a `MEDIA_UI progress_lookup found=true direction=upload sent=N total=M isSent=true status=QUEUED` diagnostic log per recomposition. Test #67b.1 PASS: Vladislav saw `Uploading N/M` on the sender bubble (faint), `Downloading N/M` on the receiver bubble, no recorder re-entry. **Test #67b.2** (visual polish before push) made the sender bubble use `BgDeep` ink on the cyan sent background (was barely visible at `TextDim` alpha 0.65), bumped label to `12.sp / FontWeight.Medium`, thickened the progress bar `3 → 4 dp` with rounded clip, and dimmed the time-row to `BgDeep.copy(alpha=0.65)`. Visual style still considered "raw" by Vladislav but acceptable as a non-blocker — UI/UX polish will be a separate track covering read receipts, presence, bubble layout, failed/retry states (issues that pre-existed M2d.1b). M2d.1b is locked UI/state/progress only — no transport, relay, chunk size, codec, parallelism, or manifest changes.

**PR-M2e — early manifest + overlap upload/download.** Even with the 204 fix and the progress counter, long voice was still slow because the chain was strictly sequential: sender uploads ALL chunks → sender sends manifest → receiver downloads ALL chunks. Total wait ≈ `upload + download`. M2e turns it into two overlapping phases so total wait approaches `max(upload, download)`. `VoiceV2Sender.uploadVoice` now accepts an `onEarlyManifest: suspend (manifest) -> Unit` callback. The manifest is built up front (mediaId / mediaKey / nonce / sha256 are known immediately after `encryptVoice` returns) and the callback fires exactly once after `EARLY_MANIFEST_AFTER_CHUNKS = 3` chunks have committed on the relay (`min(3, total)` for very short voices). DMS hoists the manifest-send block into a `suspend fun sendManifestEnvelope(manifest)` lambda used by both the early callback and a tail fallback, guarded by a local `manifestSent` boolean — guaranteed exactly one `MEDIA_TX manifest_sent` per `mediaId`. `UPLOADING → SENT` flips after manifest leaves the device; the upload tail continues in background and the bubble's `Uploading N/M` keeps ticking until `upload_complete` (the bus entry survives until `finally`, AudioBubble does NOT gate on status). On the receiver, the old `ByteArray?` return type collapsed 404 (chunks-gone) and auth-exhausted (terminal) into the same `null`, which would have caused false `media_chunks_gone` failures on the upload tail under overlap. Added a sealed `ChunkOutcome.Ok / NotReady / Terminal`. The fresh-task window scales with chunk count — `freshWindowMs = (chunkCount × 1500 ms).coerceIn(120_000, 300_000)` — so a 15-chunk voice has 120 s grace and a 200-chunk voice has 300 s (cap). On `NotReady` within the window: log `MEDIA_RX chunk_not_ready_yet idx=N retry_in=Xms ageMs=Y windowMs=Z`, backoff `1s → 2s → 3s` cap, retry the same idx. On `NotReady` past the window: log `chunk_not_ready_deadline_exceeded`, then existing `markFailed(media_chunks_gone)`. `Terminal` outcomes fail immediately. Parameters locked by Vladislav 2026-05-19 after he rejected the original proposal of fixed 60 s + 10 retries × 2 s as too short for 100+-chunk voice (would false-fail the tail on Tele2 LTE). One small post-CI fixup added a separate `MEDIA_TX send_failed_after_early_manifest` log tag for the new edge case where the manifest already left but the tail upload failed, so diagnostics can distinguish it from the legacy pre-manifest failure. **Test #68** smoking gun confirmed end-to-end overlap. Sender 82-chunk voice: `chunk_split chunkCount=82` at 22:44:34 → `upload_progress sent=3 total=82` at 22:44:36 → `early_manifest_sent afterChunks=3 total=82` at 22:44:36 → `manifest_sent` at 22:44:37 → `upload_complete` at 22:45:35. Receiver in the same window (after the 5 h timezone offset): `manifest_acked_and_queued` at 03:44:38 (= sender's 22:44:38) → `fresh_window_ms windowMs=123000` → `download_progress received=1 total=82` at 03:44:38 → `download_complete` at 03:46:08. Receiver started downloading **~57 s before sender finished uploading**. Zero `media_chunks_gone`, zero `download_failed`, zero `decrypt_failed`, zero `sha256_mismatch`, zero `chunk_not_ready_yet` (sender was fast enough that the receiver never overtook upload — the fresh-task path is a safety net, not a hot path in this run). Voice playable on both endpoints. The architect's overall verdict: 82-chunk voice total wait dropped from ~150 s (`upload 60 s + download 90 s`) to ~90 s (`max(upload 60 s, download 90 s)`), roughly a 40 % improvement on long voice. Still subjectively long because per-chunk `~1.1 s` GET RTT on the receiver is the new bottleneck — M2e did not address it.

**Recorder re-entry root cause — separate from the three media PRs but caught during Test #67b.** A 334 ms re-entry pattern in the logcat showed `VOICE_REC complete duration=5600` followed by a fresh `VOICE_REC config` before `sendAudio` could even return. The recorder-stop and recorder-start branches in `ChatScreen.onMicClick` shared an `isRecording` state that was flipped synchronously without protecting against the in-flight stop / send path. The fix is a per-screen `voiceSendInProgress` Compose state that gates the mic button entirely while `sendAudio` is running, with a clear `VOICE_REC ignored_start reason=finalizing_or_sending` log line for diagnostics. The pattern matches the broader "UI-side state guards belong next to send-layer guards" principle used by the D2a/M1w voice gating layer.

**Visual style of the progress row — non-blocking follow-up.** Vladislav and the architect both flagged the original `Uploading N/M` rendering as too faint to read on the cyan sender bubble. The visual polish included in M2d.1b (BgDeep ink on sent, 12 sp + Medium weight, thicker rounded progress bar) improved contrast but the bubble is still considered "raw". This is part of a broader UI/UX debt: read receipts ambiguity, online/presence flakiness, message status icon consistency, message bubble layout, failed/retry visual states, loading animations, and message-row refresh consistency. Vladislav decided this UI work belongs to a separate dedicated track (PR-UI1 series, name TBD) rather than being interleaved with transport/protocol work. Locked: do not block transport progress on UI polish; do not bundle UI polish into transport PRs.

**Yellow-dot first-message delay on text messages — separate follow-up, NOT a regression.** Test #67b/67b.1/67b.2/68 all reproduced the "after adding a contact, first text message shows yellow dot for some seconds before delivering" symptom. Logs across the four tests pinpoint it at the `PREKEY_TRACE upload_fail ... SocketTimeoutException elapsedMs=184182` path. This is the same class as the H1/H1e half-open WS / bootstrap-publish failure modes already triaged. It is NOT caused by any of the three media PRs and was already there on master `2a9ba08c`. The architect labelled it as a separate prekey/ACK/bootstrap follow-up; not to be bundled with media PRs.

**Memory entries created.** `project_m2d_roadmap_2026_05_18.md` (locked sequence M2d.1a → M2d.1b → M2e → M2f → M2g), `project_m2e_locked_params_2026_05_19.md` (dynamic fresh-task window math + why fixed 60 s would false-fail).

**Next track.** PR-M2f — binary `application/octet-stream` `/media/v3/{mediaId}/{idx}` endpoint as the next real speed-step. Removes JSON + Base64 overhead (~33 % of wire body) so a `~1700 byte raw ciphertext` chunk turns into `1700 bytes on wire` instead of `~2388 body bytes`. On the Tele2 LTE full-roundtrip ceiling of ~2400 bytes, this gives headroom to raise raw ciphertext per chunk from ~1700 to ~2200-2300 (~25-35 % fewer chunks → 82 chunks → ~60-65 chunks for the same 35-sec voice). Will ship as an A/B endpoint alongside the existing JSON path so a single PR can be reverted cleanly if the binary path regresses on any device. After M2f probe data lands, PR-M2g (HTTP Range / 206 Partial Content download) becomes conditional — only if M2f shows the asymmetric pattern "large upload OK, small downloads OK".

### 2026-05-18 (mon, evening) · PR-M2c.0 cap probe complete — bigger chunks blocked on Tele2 Helsinki path, next track is route diversity

**Branch state.** PR #176 (M2b parallelism) stays closed/parked (revert merged 2026-05-18 as #178). PR #179 (relay media body cap is config-driven) merged to master as `c05f4127`. No further code shipped to master in this session beyond the relay fix. Probe-side Android changes preserved on retained branch `diag/m2c0-media-route-probe` for future Romania-path re-run.

**Investigation path.** Test #66 ran the original probe and got `413 outcome=relay_too_large` for every body size ≥ 5500. Architect read it as "Tele2 not letting us through", but a code trace found two cap-enforcement layers in the relay: the in-handler check (config-driven via `RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES`) and the axum `DefaultBodyLimit` middleware (hard-coded to the `MAX_MEDIA_UPLOAD_BODY_BYTES = 3072` constant at `services/relay/src/routes.rs:96`). The env-var override only lifted the in-handler check; the middleware still rejected anything > 3072. PR #179 routes both layers through `state.config.max_media_upload_body_bytes`, and ships two new tests (`test_http_body_limit_respects_config_override`, `test_http_body_limit_fires_above_config_override`) that catch any future regression of the same kind. VPS deploy with `--build` confirmed the fix: a smoke `curl` of a 5400-byte POST returned 401 (auth) instead of 413 (cap).

**Test #66.1** (probe v1 after the fix) lifted the 413 wall but exposed a probe-code bug: the same line could read `status=201 outcome=stored elapsedMs=15008 error=InterruptedIOException:timeout`. Architect-debriefed: status was captured before the body read, then `response.body?.string()` timed out at the OkHttp read-timeout, and the probe didn't distinguish "relay-side success but Tele2 dropped the response body" from "relay-side failure". Probe was rewritten as v2.1 with non-overlapping outcome enums (`STORED` / `DUPLICATE` / `STORED_RESPONSE_DROPPED` / `RELAY_TOO_LARGE` / `AUTH_FAIL` / `BAD_REQUEST` / `REQUEST_TIMEOUT` / `OTHER_HTTP` / `FAIL_EXCEPTION` for upload; `OK` / `SHA_MISMATCH` / `BODY_READ_FAILED` / `NOT_FOUND` / `AUTH_FAIL` / `OTHER_HTTP` / `REQUEST_TIMEOUT` / `SKIPPED` for download), a 2400-byte control row (current production wire size — sanity-checks the probe decoder + tells us whether Tele2 Layer B already bites at production size), and explicit SHA-256 hex prefixes in each result line for cross-referencing with relay logs. Final-verdict logic was switched from `largestStoredSafe` (upload-only) to `largestFullRoundtrip` (upload AND download) as the architect insisted: shipping a chunk size the receiver cannot download is the actual Test #62-#65 failure mode.

**Test #66.2 result** (probe v2.1):

| Body | relayStored | fullRoundtrip | upload avg ms | download avg ms | Verdict |
|---|---|---|---|---|---|
| 2400 (control) | 3/3 | **3/3** | 725 | 733 | stable_full |
| 5500 | 5/5 | 0/5 | ~15 000 | n/a | stored_but_resp_dropped |
| 6500 | 5/5 | 0/5 | ~15 000 | n/a | stored_but_resp_dropped |
| 7168 | 0/3 | 0/3 | timeout | n/a | unstable |

**M2C0_FINAL on Helsinki:** `stableMaxBodyFullRoundtrip=2400 largestUploadOnly=6500 recommendedRawChunkBytes=1700`.

**Locked decision.** Do not ship PR-M2c bigger chunks on the Helsinki path. Production `TARGET_RAW_CHUNK_BYTES` stays at 1700. The Tele2 LTE middlebox cleanly accepts requests up to ~6500 bytes (relay log proves chunks land), but reliably drops the 201-response body on upload and the GET-body on download for sizes > ~2400. Increasing chunk size would speed up senders but break receiver downloads. The audit document `docs/design/voice-delivery-audit-2026-05-18.md` Section 8 captures the data and the rejection.

**Next track: PR-INFRA-MediaRO.** Deploy a second `phantom-relay` (full binary, not bridge) at a different VPS/ASN (FlokiNET Romania candidate — bridge2 deployment exists as a reference). Allocate `media-ro.phntm.pro` (or similar). Decide auth model (federated identity vs per-relay registry). Re-run the probe with two `MediaProbeEndpoint` entries. If Romania gives `fullRoundtrip 5/5` at 5500 or 6500, design `mediaRelayId` extension to `VoiceManifestV2` and route media via the better path per-voice. Probe code retained on `diag/m2c0-media-route-probe` so the Romania re-run is one-config-edit-and-rebuild away, not a fresh probe rewrite.

**Architectural insight preserved as memory entry** (`feedback_tele2_media_path_ceiling_2026_05_18.md`): for the current single-relay `relay.phntm.pro` deployment, Tele2 LTE full-roundtrip ceiling on `/media/upload-chunk` + `/media/chunk/.../...` is approximately 2400 bytes wire body. Upload-only ceiling is ~6500 bytes (sender pushes successfully, but Tele2 drops the response on the way back, and the same path drops GET responses on download). Any future attempt to raise chunk size on this single endpoint will hit the same ceiling; the lever is route diversity, not chunk size, codec, parallelism, or in-handler cap.

### 2026-05-18 (mon, late) · PR-M2a merged — voice-note recorder profile shrinks 5-sec voice ~40 % (`bc94a4d4` on master)

**Branch state.** PR #174 squash-merged to master as `bc94a4d4`. Single commit on top of M1w. Branch deleted on remote.

**Scope.** Voice-note recorder profile only. No transport, manifest, crypto, DI, or parallelism changes. Both `ChatScreen.startChatRecording` and `GroupChatScreen.startGroupRecording` move from music-grade to voice-grade encoder settings:

| | Before (M1w) | After (M2a) |
|---|---|---|
| OPUS path (API 29+) | 48 kHz / mono / 48 kbps | 16 kHz / mono / 24 kbps |
| Fallback path (API 26-28) | AAC @ 44.1 kHz / 64 kbps | AAC_ELD @ 16 kHz / 24 kbps |
| Container | OGG / MPEG_4 | OGG / MPEG_4 (unchanged) |
| MIME | audio/ogg / audio/m4a | unchanged |
| 5-sec voice (measured Test #62) | 39-57 KB | 23.5 KB |
| 35-sec voice (measured Test #62) | ~270-400 KB est. | 150 KB / 89 chunks |

AAC_ELD (Enhanced Low Delay AAC) is the Android voice profile available since API 16, so safe at our minSdk=26. OPUS guard `Build.VERSION.SDK_INT >= VERSION_CODES.Q` stays exactly as before. Channels stay mono (intentional — speech is mono and the same bitrate sounds cleaner than stereo).

**Diagnostic logs added** (Test #62/63+ grep targets):
- `VOICE_REC config encoder=OPUS|AAC_ELD sampleRate=16000 bitrate=24000 channels=1 outputFormat=OGG|MPEG_4 mime=audio/...` (emitted before MediaRecorder.start() to confirm the recorder applied the chosen profile rather than silently defaulting). The group recorder log appends `scope=group` to distinguish.
- `VOICE_REC complete durationMs=... bytes=... bytesPerSec=... mime=...` (emitted at the call site after stop() to surface the real-world payload; `bytesPerSec` is integer-math with explicit `durationMs > 0` guard).

**MIME mapping fix.** `VoiceFileStore.mimeToExtension` now matches `audio/m4a` (what the AAC-fallback sender sets) in addition to the IANA-canonical `audio/mp4`. Before the fix the AAC-fallback receive path landed on a generic `audio` extension; MediaPlayer auto-detected from content so playback worked, but the file extension was misleading.

**Test #62 verdict.** Codec change applied and works end-to-end. 5-sec voice fell from 39-57 KB to 23.5 KB (~40-60 % reduction). 35-sec voice fell from ~270-400 KB estimate to 150 KB / 89 chunks. The architect-suggested M2a.1 follow-up (drop bitrate further 24 → 16 kbps to hit the strict ≤ 20 KB target) was prepared as commit `3a2689fc` and then **vetoed by Vladislav** ("нам надо, чтобы звук был идеальным, почти в HD") — the commit was reset on the feature branch with `git push --force-with-lease` before CI re-fired, and the PR shipped as the 24 kbps profile. The architectural call: quality > size for Phantom's premium-feel positioning. Size optimisation continues at the transport layer (PR-M2b) not the codec layer. The voice-quality preference is logged in agent memory as `feedback_voice_quality_priority.md` — 24 kbps is the floor, 16 kbps gated as a future "Data Saver" toggle.

**Out of scope, queued for next sessions.**
- PR-M2b — download parallelism = 2 (receiver UX; lowest-risk concurrency win on Tele2).
- PR-M2c — upload parallelism = 2 (only after M2b proven stable).
- PR-M2d — optional batch endpoint (`GET /media/chunks?from=N&count=4`), carefully because large POST bodies on Tele2 have given trouble before.

### 2026-05-18 (mon) · PR-M1w merged — encrypted media-upload voice messages live for 1:1 chats (`561de17c` on master, 7 commits squashed)

**Branch state.** PR #172 squash-merged to master as `561de17c`. Closes the long road from "voice = 150 ratchet envelopes ≈ 2 min on Tele2" (Test #55) to "voice = encrypted media upload + manifest envelope, end-to-end functional on Tele2 LTE" (Test #61). Branch `feat/pr-m1w-voice-upload` deleted on remote.

**Commits squashed into `561de17c`** (in order):
- 5 builder commits — M1 core (storage schema, sender/orchestrator, DMS handlers, UI bubbles). Effectively inert until integration.
- `53253d6d` — fix-blockers MB1-MB5 from initial security + architect review (sha256 decode fail-closed, MediaAuthException class semantics, markFailed signature simplification, test assertion fix, SQLDelight CREATE TABLE for CI).
- `40e98bb7` — CI-driven hidden-compile fixes (kotlinx-datetime out of storage module, opaque generated row class avoided via inline mapping, removed cross-module commonTest fakes).
- `115b442b` — **live wire-up** caught by Test #56: AppContainer never constructed `VoiceV2Sender` / `VoiceV2DownloadRepository` / `VoiceV2DownloadOrchestrator` (the previous security review's "P8 — AppContainer DI complete with lines 738-740 wiring all three" was a hallucination — `git log -- AppContainer.kt` showed last touch was PR-C1). This commit also flips `TransportCapabilitiesResolver.canSendVoice = true` for `RestActive` + `WsCandidate` (calls stay false in Limited realtime).
- `cc456a36` — MB6 + MB7 (Test #57): sender wraps `VoiceManifestV2` in `MessagePayload(type=TYPE_VOICE_V2)` so receiver's branch fires; `KtorMediaUploadTransport` wraps `SocketTimeoutException` as `MediaTransportException` so `VoiceV2Sender` retry loop fires.
- `9ef4a76e` — R4 (Test #58): `AndroidNativeOkHttpMediaUploadTransport` mirrors the locked R0.1/R0.3 pattern (HTTP/1.1 only, fresh client per call, `ConnectionPool(0)`, `retryOnConnectionFailure(false)`, `Connection: close`, 10 s timeouts); `sendAudioV2` Steps 4-7 wrapped in `scope.launch` so the upload survives Compose lifecycle changes. Closed the ~31 s per-chunk Tele2 stall the architect verified via VPS logs (relay served chunks in milliseconds; phone Ktor client waited a full timeout between GETs).
- `47a1b038` — R5 (Test #59): `runVoiceV2DownloadTask` re-reads the row and emits `IncomingMessage` after `download_complete` so `ChatScreen` transitions `[AUDIO_DOWNLOADING]` → playable voice without exit+reentry; `handleVoiceV2Manifest` threads `payload.senderUsername` so notification title shows the contact name not raw hex; sender row flips `UPLOADING → SENT` immediately after `manifest_sent` instead of waiting on ack flow. New logs: `MEDIA_RX message_ready`, `MEDIA_TX local_status_sent`.
- `0a48c8cf` — R6.1 (Test #60): `handleVoiceV2Manifest` upserts conversation with `unreadCount + 1` and preview `🎤 Voice message` so the Chats list shows the unread badge the moment the manifest arrives.

**Tests run on real hardware** (Tecno `103603734A004351` on Tele2 LTE Иркутская + `emulator-5554`):

| # | Failure surface | Fix landed |
|---|---|---|
| #56 | `MEDIA_RX no_download_repo` at startup; emulator sent legacy `audio_chunk`; Tecno blocked by C1 gate | DI wire-up + capability flip (`115b442b`) |
| #57 | Receiver decoded manifest as `MessagePayload(type=message, text="")` → empty chat bubble + envelope-id in notification | Manifest wrapper (`cc456a36`) |
| #58 | Phone download = 1 chunk / ~31 s on Tele2 LTE; phone upload died on Compose scope cancel | Native OkHttp + DMS scope (`9ef4a76e`) |
| #59 | Receiver bubble stuck on `[AUDIO_DOWNLOADING]` despite download_complete; sender bubble stuck on UPLOADING; notification showed raw hex | UI emit + sender label + status flip (`47a1b038`) |
| #60 | Chats list never showed unread badge for incoming voice | `unreadCount + 1` upsert (`0a48c8cf`) |
| #61 | — PASS — | (no fix needed) |

**Architect verdict on Test #61.** "Test #61 = PASS. PR #172 ready to merge." All five functional gates green: voice delivery, native media transport, AUDIO_LOCAL transition, unread badge, notification title.

**Known follow-up: PR-M2 performance pass.** Test #60 showed 5-sec voice = 39-57 KB and current sequential one-HTTP-RTT-per-chunk throughput would make 1-5 min voice take minutes to upload/download. Three M2 tracks queued:
- **M2a** — recorder bitrate / voice-note profile (mono Opus / AAC at 16-32 kbps; 5 sec voice ≈ 10-20 KB).
- **M2b** — download parallelism = 2 (receiver-side; lowest-risk concurrency win).
- **M2c** — upload parallelism = 2 or adaptive (only after Tele2 stability proven for M2b).
- **M2d** — optional batch endpoint (`GET /media/chunks?from=N&count=4`); later, carefully — large POST bodies on Tele2 have given trouble before.

**Lesson logged: reviewer hallucination on DI claims.** Test #56 caught a fabricated positive finding from the security-reviewer agent and a matching fabrication in the builder agent's commit-5 summary. Both claimed "AppContainer wired with `voiceV2Sender / Repository / Orchestrator` at lines 738-740" — `git log -- AppContainer.kt` proved the file was last touched in PR-C1, with no M1w changes at all. The runtime `MEDIA_RX no_download_repo` log line emitted by DMS init was the source of truth. Memory entry `feedback_reviewer_hallucination_2026_05_18.md` codifies the rule: any reviewer claim of "wired" / "integrated" / "passed" with specific line numbers requires `git log -p` + `grep` proof before trust. The third instance of "Ktor / persistent HTTP on Tele2 stalls; replace with native OkHttp fresh client per call" (after R0.1 and R0.3) is also logged as `project_tele2_media_path_2026_05_18.md`.

**Voice on Limited realtime is now production-functional**: voice records, encrypts, uploads chunks via native OkHttp + manifest envelope via ratchet, receiver downloads + decrypts + verifies sha256 + writes to `filesDir/voice/<mediaId>.<ext>`, chats list shows unread badge, notification shows contact name, bubble transitions to playable. The pre-existing audio_chunk path remains in code as the JVM/test fallback and for any in-flight Alpha-1 messages but is no longer the production sender path.

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
  - **M1r** — relay: `POST /media/upload-chunk` + `GET /media/chunk/{mediaId}/{idx}`, bearer-session auth, hard cap 3072 bytes (client target ≤2600), idempotency by `(media_id, idx, ciphertext_hash)`, in-memory `media_chunks` store, TTL=7d + sweeper, quotas `maxMediaChunks=256` / `maxMediaBytes=1MB`, no DELETE in round 1. `mediaId = base64url(random 32 bytes)` capability token.
  - **M1k** — Kotlin `MediaUploadTransport` + manifest model + XChaCha20-Poly1305 (AAD=mediaId), inert/not wired. Manifest `{type:"voice_v2", mediaId, mediaKey, nonce, alg:"xchacha20poly1305-v1", durationMs, mime, chunkCount, encryptedSizeBytes, plainSizeBytes, sha256(plaintext)}`.
  - **M1w** — wire `sendAudio` to M1 (upload all chunks → THEN send single manifest via `/relay/send`); receiver on `voice_v2` GETs chunks, reassembles, decrypts+verifies, inserts one voice row. D2b legacy parked.
  - Sequential upload, single voice at a time, 1:1 only.
  - Throughput: ~22–25 chunks vs current 150 → ~12–20 s on Tele2 (still REST-bound, not instant).
- **PR-C1 — calls capability gating (parallel track).** Small scope: `Stable realtime → calls enabled`, `Limited realtime / REST / Tor / Reality-without-realtime-proof → calls disabled`. Call button allowed only when `realtimeStable == true`. Voice in Limited realtime ALSO gated until M1w wires the new media-upload path (avoiding D2b regression). Two-layer guard: UI Snackbar + `CallManager.startCall` early return with `CALL_TX blocked_*` log. Full RealtimeProbe (upstream frame + downstream ack + 10–20 s hold + RTT/jitter/loss) lands later in C2/C3.
- **Goal.** Voice = async encrypted media (not ratchet envelopes); calls blocked until stable realtime transport exists.

**Execution complete 2026-05-17 same evening.**
- PR #169 M1k merged `14:37Z` after security-aware fixup (ionspin API names + MediaCryptoTest moved to `androidInstrumentedTest` + KDoc unclosed-comment fix + token: String threaded through `MediaUploadTransport`/`KtorMediaUploadTransport` with `Authorization: Bearer $token` header + 2 new header-assert tests). 12/12 unit tests green, 9 instrumented tests deferred to M1w preflight.
- PR #170 C1 merged `15:14Z` after architect catch on stale "voice allowed in Limited realtime" (Resolver flipped to `canSendVoice=false` for `WsCandidate`/`RestActive` until M1w; KDoc + onMicClick comment refreshed). 32 resolver tests + 4 `CallManagerGuardTest` tests green, `assembleDebug` green. Defence-in-depth: UI `CALL_CAPABILITY disabled source=ui` + CallManager `CALL_TX blocked_<reason> source=call_manager`. Unified Limited-realtime Snackbar copy locked by architect.
- PR #168 M1r merged `15:19Z` after security-reviewer pass closed 2 blockers (B1 total_mismatch enforcement, B2 route-level `DefaultBodyLimit::max(3072)`) + 5 non-blockers (sweeper lock scope, in-memory durability honesty, unused idempotency_key field removed, per-identity rate-limit TODO, conflict test asserts original ciphertext preserved). 11/11 endpoint tests green.
- **VPS deploy `15:21Z`**: `docker compose -f deploy/docker-compose.yml up -d --build relay` rebuilt + restarted cleanly on `relay.phntm.pro`. `phantom-relay starting host=0.0.0.0 port=8080 max_payload_kb=1024` confirmed in logs.
- **M1r smoke gate PASSED (limited scope) `15:24Z`** — happy-path (201/200/409) deferred to M1w because `/auth/session` Ed25519 challenge isn't shellable. Verified live:
  - `POST /media/upload-chunk` without/with invalid bearer → 401 + `{"error":"Authorization: Bearer <token> required"}` (route alive, auth middleware works).
  - `GET /media/chunk/anychunk/0` without/with invalid bearer → 401 same shape (GET route alive, same auth middleware).
  - **B2 route-level body limit VERIFIED LIVE**: 60 KB POST body → `HTTP/2 413 "Failed to buffer the request"` (HTTP framing layer rejects before handler allocation — the exact DoS-amplification fix the security reviewer flagged).
  - Relay logs clean: 2 startup lines, zero panics, zero SQLite errors, zero `mediaKey`/`ciphertext_b64=...`/full-mediaId leaks.

**Open follow-ups for M1w (preflight before live wire-up).**
- Run `:shared:core:crypto:connectedDebugAndroidTest` on Tecno or emulator to validate the 9 `MediaCryptoTest` cases against the real libsodium native binding (XChaCha20-Poly1305 round-trip + wrong mediaId/key/ciphertext failures + sha256 + mediaId uniqueness).
- M1w design pass: integrate `MediaCrypto` + `MediaChunker` + `KtorMediaUploadTransport` (token-aware) into `DefaultMessagingService.sendAudio`; receiver-side `voice_v2` manifest handler that GETs chunks, reassembles, decrypts, inserts one voice row; flip `Resolver.canSendVoice = true` for `WsCandidate`/`RestActive` once the new path is live. Old `audio_chunk` envelope path parked but kept for backward compat with in-flight messages.

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
