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
- ⏳ NLnet grant submission (deadline 2026-06-01)
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
- NLnet specifically funds privacy/freedom tech. A Google dependency in
  the core delivery path damages the grant story.
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

## Session journal

Reverse-chronological. Each entry: **goal · outcome · key commits ·
follow-ups** in compact form. Cross-reference the Decision log above
when an entry mentions a rejected approach.

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
    creates a reproducibility hole and a bad first impression for the
    NLnet reviewer. 180 MB one-time cost is acceptable; rebuild from
    `XTLS/libXray@<sha>` is deterministic via the workflow.
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
  - **ADR-019 Xray REALITY rationale** — write it before NLnet body
    needs it: threat model, license posture (MPL-2.0 Xray-core
    aggregation cleanly composable with our AGPL at the docker-compose
    level), Beta-time multi-server fan-out plan. ~1 hour.
  - **PR to master** — open once the three above are done, so master
    doesn't carry the temporarily relaxed routing.

### 2026-04-27 (sat, evening) · Licence hygiene before NLnet submission

- **Goal:** establish formal AGPL-3.0 licensing across the repo so the
  NLnet grant reviewer (deadline 2026-06-01) does not see absent /
  ambiguous licensing as a red flag. The user reported a perception
  that the previous LICENSE file carried a Vercel copyright (likely a
  half-remembered reference to the `prototype/web` Next.js scaffold);
  on inspection there was no top-level LICENSE at all, which is
  arguably worse for a grant submission.
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
    and `docs/initial-drafts` (NLnet-ready public docs).
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
