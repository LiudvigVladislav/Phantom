# Relay environment reference

**Purpose:** canonical index of every `RELAY_*` environment variable the relay reads at runtime. Enforced by the M6-2 CI gate `I5` in `scripts/relay_invariants.py`: every string-literal `RELAY_*` in `services/relay/src/**` production code must appear somewhere in this document, otherwise the CI job fails.

**Format:** one row per env var in each section's index table (name, default, brief). Under each section is an expanded per-var block with the accepted value shape, effect on runtime behaviour, re-calibration / re-tuning trigger where applicable, and cross-refs to owning ADRs / runbooks. The CI gate only greps for the literal name, so the file can be reshaped freely as long as every name still appears.

**Parse discipline:** the "shape" column below describes what the parser accepts. Two parse patterns are in use side by side:

- **Fail-closed FATAL exit 11:** the preflight and queue caps (`RELAY_PREFLIGHT_*`, `RELAY_QUEUE_*`), the ownership vars (`RELAY_EXPECTED_UID/GID`, `RELAY_MODE_FORBIDDEN`), the shutdown deadline (`RELAY_SHUTDOWN_DEADLINE_SECS`), and the health port (`RELAY_HEALTH_PORT`) all classify the env value via `classify_u64_env` / `classify_octal_env`. Absent uses the caller's default; a present-but-malformed / non-UTF-8 / out-of-range value calls `eprintln!("FATAL: ...")` and `std::process::exit(11)`. **The additional preflight-covers-runtime cross-check runs after both parsers succeed; any preflight cap smaller than its runtime counterpart also exits 11.** `RELAY_SEQ_MAC_KEY` is fail-closed on a different exit path (config parser aborts startup with a distinct error) because a missing MAC key voids the seq-MAC contract.
- **Silent fall-back to default:** the legacy config vars parsed inside `RelayConfig::from_env` (`RELAY_HOST`, `RELAY_PORT`, `RELAY_MAX_PAYLOAD_BYTES`, `RELAY_ENVELOPE_TTL_SECS`, `RELAY_MAX_ENVELOPES_PER_RECIPIENT`, `RELAY_RATE_LIMIT_*`, `RELAY_MAX_MEDIA_*`, `RELAY_MEDIA_TTL_SECS`, `RELAY_POLL_HOLD_SECS`) use `.parse().unwrap_or(<default>)` -- a malformed value silently falls back. The diagnostic toggles use a strict `"1"` parse (anything else = `false`).

**Test-only env vars** (excluded from I5's required set by explicit allowlist in `scripts/relay_invariants.py`): `RELAY_PARSE_U64_DEFAULT_KEY`, `RELAY_PARSE_U64_VALID_KEY`. Adding a new production `RELAY_*_KEY` requires a row in this file.

---

## Runtime capacity + preflight

Enforce the shared `preflight >= runtime` invariant across three orthogonal caps: envelope count, byte occupancy, RAM footprint. `preflight_caps_from_env()` and `capacity_caps_from_env()` in `services/relay/src/main.rs` are the parse sites; a boot-time cross-check trips FATAL + `exit(11)` if any preflight cap is smaller than its runtime counterpart (`services/relay/src/main.rs:85` -- same exit code as the fail-closed parse path so operator config errors surface consistently).

| env var | default | brief |
|---------|--------:|-------|
| `RELAY_QUEUE_RAM_BUDGET_BYTES` | 83_886_080 (80 MiB) | Runtime capacity-ledger RAM cap; calibrated M5b. |
| `RELAY_QUEUE_MAX_BYTES` | 402_653_184 (384 MiB) | Runtime capacity-ledger disk-queue byte cap. |
| `RELAY_QUEUE_MAX_ENVELOPES` | 100_000 | Runtime capacity-ledger envelope-count cap. |
| `RELAY_PREFLIGHT_RAM_BUDGET` | 83_886_080 (80 MiB) | Preflight RAM cap; must match runtime. |
| `RELAY_PREFLIGHT_MAX_BYTES` | 402_653_184 (384 MiB) | Preflight byte cap; must dominate runtime. |
| `RELAY_PREFLIGHT_MAX_ENVELOPES` | 100_000 | Preflight envelope-count cap; must dominate runtime. |
| `RELAY_MAX_ENVELOPES_PER_RECIPIENT` | 500 | Per-recipient queue depth cap (M4-2b). |
| `RELAY_TOMBSTONE_DEDUP_HORIZON_SECS` | 172_800 (48 h) | Tombstone dedup horizon (M3b-2a). |

### `RELAY_QUEUE_RAM_BUDGET_BYTES` / `RELAY_PREFLIGHT_RAM_BUDGET`

- **Shape:** `u64` decimal byte count, parsed via `parse_u64_env` (FATAL on non-UTF-8 or non-decimal).
- **Effect:** upper bound on `GlobalCapacityGate` `ram_bytes` (see `services/relay/src/capacity_ledger.rs`). `Send` reservations that would push `ram_bytes` above the cap return `SendError::CapacityExceeded`. The `preflight >= runtime` invariant checked at boot means both must equal the same number in practice; compose pins both explicitly to the calibrated 80 MiB value.
- **Re-calibration trigger:** any of ADR-027's five triggers (container memory limit change, runtime baseline drift, new envelope shape, production leak, `record_ram_estimate` change). Procedure: `docs/tracks/rc-relay-queue-ram-recalibration.md`.
- **Cross-refs:** [ADR-027](../adr/ADR-027-relay-queue-durability-and-ram-budget.md), `services/relay/src/main.rs` `DEFAULT_RELAY_RAM_BUDGET_BYTES`.

### `RELAY_QUEUE_MAX_BYTES` / `RELAY_PREFLIGHT_MAX_BYTES`

- **Shape:** `u64` decimal byte count, `parse_u64_env` (FATAL on malformed).
- **Effect:** upper bound on `GlobalCapacityGate` `active_bytes + tombstone_bytes` (sum of on-disk record sizes). Distinct from `ram_budget` — this caps DISK occupancy, not projected RAM. M5b did not calibrate this cap; the 384 MiB default is a pre-M6 placeholder held over until a disk-shape benchmark lands.
- **Re-tune trigger:** operator changes underlying state-dir volume size. No CI enforcement (unlike the RAM budget).
- **Cross-refs:** `services/relay/src/capacity_ledger.rs` `CapacityCaps.max_bytes`.

### `RELAY_QUEUE_MAX_ENVELOPES` / `RELAY_PREFLIGHT_MAX_ENVELOPES`

- **Shape:** `u64` decimal, `parse_u64_env` (FATAL on malformed).
- **Effect:** upper bound on `GlobalCapacityGate` `active_envelopes + tombstone_records`. `Send` beyond the cap returns `CapacityExceeded`. 100 000 is deliberately larger than any expected steady-state population; the byte / RAM caps hit first under normal workloads.
- **Re-tune trigger:** operator observed steady-state envelope count approaches 50 k+.
- **Cross-refs:** `services/relay/src/capacity_ledger.rs` `CapacityCaps.max_envelopes`.

### `RELAY_MAX_ENVELOPES_PER_RECIPIENT`

- **Shape:** `usize` decimal, `parse().unwrap_or(500)`.
- **Effect:** per-recipient queue depth cap enforced inside each shard-worker actor. `Send` beyond the cap returns `SendError::PerRecipientQueueFull`; operator sees HTTP 429. Prevents a single misbehaving recipient exhausting the global caps.
- **Re-tune trigger:** support for high-fan-in recipients (bots, service accounts). Never raise past `RELAY_QUEUE_MAX_ENVELOPES / expected_recipient_count`.
- **Cross-refs:** `services/relay/src/config.rs`, `services/relay/src/rest_workers.rs` `PerRecipientQueueFull`.

### `RELAY_TOMBSTONE_DEDUP_HORIZON_SECS`

- **Shape:** `u32` seconds, `parse` (FATAL if malformed / zero / above `MAX_HORIZON_SECS = 10 * 365 * 86_400`).
- **Effect:** tombstone retention window after `Ack`. Semantics per M3b-2b/M3b-3:
    - A client that RE-`Send`s an already-acked envelope within the horizon receives `SendDisposition::TombstoneReplay` -- the tombstone's original `seq` is echoed back, no new record is written.
    - An `Ack` of an already-acked envelope receives `AckOutcome::Idempotent` (with the tombstone's `seq`) rather than a mutation.
    - Past the horizon the sweep reclaims the tombstone; a fresh `Send` for the same id then behaves as `SendDisposition::Fresh`.
  Longer horizon = larger disk footprint, better dedup guarantee under long client offline periods.
- **Re-tune trigger:** operator sees legitimate ack-replay bugs (client offline > horizon) — raise. Operator sees disk usage dominated by tombstones — lower.
- **Cross-refs:** `services/relay/src/tombstone_config.rs`, ADR-027 §"Tombstones with dedup horizon".

---

## Lifecycle + listeners

| env var | default | brief |
|---------|--------:|-------|
| `RELAY_HOST` | `0.0.0.0` | Public listener bind host. |
| `RELAY_PORT` | 8080 | Public listener bind port. |
| `RELAY_HEALTH_PORT` | 8081 | M4-4 loopback health listener port. |
| `RELAY_SHUTDOWN_DEADLINE_SECS` | 45 | M4-4 unified shutdown deadline. |
| `RELAY_STATE_DIR` | `/var/phantom` | Base directory the relay writes queue + reports + prekeys under. |

### `RELAY_HOST`

- **Shape:** free string, `unwrap_or_else(|_| "0.0.0.0".into())`.
- **Effect:** hostname / IP the public axum listener binds to. `0.0.0.0` accepts all interfaces; compose relies on Docker's network isolation to keep the port off the host without an explicit loopback prefix (see `deploy-lint.yml` `Inv-BypassIsLoopbackOnly` for the loopback-required diagnostic-port shape).
- **Re-tune trigger:** operator wants to restrict to a specific interface.

### `RELAY_PORT`

- **Shape:** `u16` decimal, `parse` (defaults to 8080 on any parse failure).
- **Effect:** public listener port. Compose maps container 8080 → internal Docker bridge; Caddy terminates TLS and forwards.
- **Re-tune trigger:** operator changes reverse-proxy topology.

### `RELAY_HEALTH_PORT`

- **Shape:** `u16` decimal, `parse_health_port_env` (FATAL on malformed / zero / out-of-range).
- **Effect:** M4-4 loopback health listener port (`127.0.0.1:<port>` only). Serves `/live`, `/ready`, `/status`. Deliberately separate from the public listener so ordered shutdown flips `/ready` 200 → 503 while the public listener drains in-flight requests.
- **Re-tune trigger:** port conflict on the host.
- **Cross-refs:** `services/relay/src/health_listener.rs`, ADR-027 §"Loopback health surface".

### `RELAY_SHUTDOWN_DEADLINE_SECS`

- **Shape:** `u64` seconds, validated by `shutdown::validate_shutdown_deadline_secs`. Accepted range is `1..=45`. Zero, above 45, or malformed → FATAL exit 11.
- **Effect:** hard cap on ordered shutdown. Public listener stops accepting → `/ready` flips → worker pool drains → deadline reached → FATAL + `exit(1)` if any worker is still dirty when the deadline elapses (note: the *shutdown-overshoot* fail-stop is `exit(1)`; the *parse-time / range-time* fail-closed is `exit(11)`). 45 s covers ordinary drain of the 64-worker fleet with headroom.
- **Re-tune trigger:** operator observes clean-drain latency approaching the cap under load; raising above 45 requires code change plus new validation range.
- **Cross-refs:** `services/relay/src/shutdown.rs`, `docs/tracks/rc-relay-state-dir-repair.md`.

### `RELAY_STATE_DIR`

- **Shape:** absolute filesystem path. Config parser returns `ConfigError::InvalidStateDir` if empty, not absolute, or contains a `ParentDir` component (PR-0 A-5). `RelayConfig::from_env` propagates the error, which surfaces as a startup abort in `main`.
- **Default:** `/var/phantom` if the env var is absent. Compose sets the same value explicitly so operator overrides never race with a code-level default drift.
- **Effect:** root of the on-disk state tree. Layout:
    - `queue/<shard-2-hex>/<recipient-hex>/<sha256_hex(envelope_id)>.json` -- durable envelopes; the on-disk filename is the SHA-256 of the envelope id, not the raw id (per `services/relay/src/persistence.rs::record_filename`, design v4 §8 replay rule 3 -- prevents casual filesystem enumeration from correlating envelope ids to shard dirs).
    - `.lock` -- PR-1b singleton advisory lock.
    - `reports.jsonl`, `blocklist.txt`, `push_tokens.jsonl`, `prekeys.jsonl` -- legacy JSONL side-effects.
    - `queue-meta.v1` -- PR-2 M2 QueueMeta (boot generation, seq-MAC fingerprint).
- **Re-tune trigger:** operator changes the mounted volume path in `deploy/docker-compose.yml`. Both must move together -- deploy-lint `Inv-2 / Inv-3` catch the mismatch.
- **Cross-refs:** `docs/tracks/rc-relay-state-dir-repair.md`, PR-0 witness runbook.

---

## Ownership attestation

Enforce that the mounted state-dir matches the container user's uid/gid/mode. Any mismatch trips FATAL at boot.

| env var | default | brief |
|---------|--------:|-------|
| `RELAY_EXPECTED_UID` | 10001 | Expected owner uid on state-dir preflight. |
| `RELAY_EXPECTED_GID` | 10001 | Expected owner gid on state-dir preflight. |
| `RELAY_MODE_FORBIDDEN` | 0o027 | Octal mask of forbidden mode bits. |

### `RELAY_EXPECTED_UID` / `RELAY_EXPECTED_GID`

- **Shape:** `u32` decimal, `parse_uid_env`. Absent env → returns the caller's default (`Some(10001)` in production). Present-but-empty string, non-UTF-8, non-numeric, or value > `u32::MAX` → FATAL exit 11. There is no "empty = None" opt-out; setting these vars to the empty string aborts startup.
- **Effect:** boot preflight `stat`s `RELAY_STATE_DIR` and refuses to start unless the owner uid/gid matches. Defaults 10001 pinned by `services/relay/Dockerfile` `groupadd --gid 10001` / `useradd --uid 10001 --gid 10001`.
- **Re-tune trigger:** operator changes container user (rare -- image lints protect this too, see `deploy-lint.yml` `Inv-5a / Inv-5b`).

### `RELAY_MODE_FORBIDDEN`

- **Shape:** octal literal (`0o027` or `027`), `classify_octal_env` (FATAL exit 11 on non-UTF-8 or malformed).
- **Effect:** bitmask of mode bits that MUST NOT be set on the state-dir. Default `0o027` = `--- -w- rwx` (octal digits: 0 = owner rwx unrestricted; 2 = group-write bit set → forbidden; 7 = all three world bits set → forbidden). Group-read (`r--`) and group-execute-search (`--x`) are NOT in the mask and are therefore PERMITTED, so a monitoring uid in the phantom group can `ls` and `cd` into the state-dir without gaining write access. The sidecar-seeded mode `0750` combined with this mask keeps group-read/execute allowed and blocks everything else the mask covers.
- **Re-tune trigger:** operator wants group-write (loosen; risky) or wants to block group-read as well (tighten to `0o077`).

---

## Envelope / media caps

| env var | default | brief |
|---------|--------:|-------|
| `RELAY_ENVELOPE_TTL_SECS` | 604_800 (7 d) | Absolute server-imposed envelope TTL. |
| `RELAY_MAX_PAYLOAD_BYTES` | 65_536 | Per-envelope payload byte cap. |
| `RELAY_MEDIA_TTL_SECS` | 604_800 (7 d) | Media object TTL. |
| `RELAY_MAX_MEDIA_BYTES` | 1_048_576 (1 MiB) | Per-`media_id` byte cap. No global media-store cap today. |
| `RELAY_MAX_MEDIA_CHUNKS` | 256 | Max chunks per media upload. |
| `RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES` | 3_072 | Per-request media upload body cap. |

### `RELAY_ENVELOPE_TTL_SECS`

- **Shape:** `u64` seconds, `parse().unwrap_or(7 * 24 * 3600)`.
- **Effect:** absolute server-imposed TTL for queued envelopes. `Envelope::new` stamps `expires_at = now + ttl_secs` at admit time; the client's own expiry preference (if any) is NOT consulted at this layer. Sweep reclaims envelopes past `expires_at`.
- **Re-tune trigger:** operator wants tighter or looser server-side retention. Longer TTL = larger disk footprint per idle recipient; shorter TTL = higher probability of an envelope being reclaimed before an offline client returns.

### `RELAY_MAX_PAYLOAD_BYTES`

- **Shape:** `usize` bytes, `parse().unwrap_or(65_536)`.
- **Effect:** per-`Send` payload cap. 64 KiB matches the sealed-sender ciphertext ceiling the client stack targets. `Send` above the cap → `SendError::Serialize` → HTTP 400.
- **Re-tune trigger:** client stack changes maximum ciphertext size.

### `RELAY_MEDIA_TTL_SECS` / `RELAY_MAX_MEDIA_BYTES` / `RELAY_MAX_MEDIA_CHUNKS`

- **Shape:** `u64` seconds / `u64` bytes / `u32` count. All `parse().unwrap_or(<crate::media::CONST>)`.
- **Effect:** media retention window plus per-`media_id` byte and chunk caps -- `MAX_MEDIA_BYTES` and `MAX_MEDIA_CHUNKS` bound the total ciphertext and chunk count belonging to a SINGLE media object, not the whole media store. **There is no global cap on the in-memory media store today.** The media upload handlers do NOT consult the `/relay/send` rate limiter (`AppState::rate_limiter`) either -- they carry their own per-request body-size cap via `RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES` and the middleware, but no bytes-per-store or objects-per-store ceiling. A well-behaved client is bounded by TTL-driven reclamation; a hostile client uploading many small distinct `media_id`s can grow the in-memory store until the container's RAM cap (`--memory 512m` in production compose) trips. Tracked as a follow-up out of PR-2 scope; the ADR-027 re-calibration triggers do NOT cover this shape.
- **Re-tune trigger:** operator wants to admit larger single media objects or more chunks per object. A global cap needs its own tracked change.
- **Cross-refs:** `services/relay/src/media.rs` (`sum(chunks.ciphertext.len()) <= MAX_MEDIA_BYTES`, `chunks.len() <= MAX_MEDIA_CHUNKS`).

### `RELAY_MAX_MEDIA_UPLOAD_BODY_BYTES`

- **Shape:** `usize`, `parse().unwrap_or(3_072)`.
- **Effect:** per-request body-size ceiling for media uploads (small because uploads are chunked; each request carries one chunk plus envelope overhead).
- **Re-tune trigger:** chunk framing changes.

---

## Rate limiting

| env var | default | brief |
|---------|--------:|-------|
| `RELAY_RATE_LIMIT_PER_WINDOW` | 60 | Requests admitted per window. |
| `RELAY_RATE_LIMIT_WINDOW_SECS` | 60 | Rate-limit window length. |

### `RELAY_RATE_LIMIT_PER_WINDOW`

- **Shape:** `u32`, `parse().unwrap_or(60)`.
- **Effect:** hard ceiling on requests per identity per fixed `RELAY_RATE_LIMIT_WINDOW_SECS` bucket. The `RateEntry` in `services/relay/src/state.rs` is a fixed-window counter (bucket start + count), NOT a sliding window and NOT a token bucket; on window rollover the first request AFTER the boundary opens a fresh window with `count = 1` (it does not observe an empty bucket -- the reset happens as part of admitting that request). Default 60 per 60-second bucket admits 60 requests total in the first second of a window and then rejects until the window resets, so the practical steady-state throughput a client can sustain is `RELAY_RATE_LIMIT_PER_WINDOW / RELAY_RATE_LIMIT_WINDOW_SECS` averaged across many windows, not a smooth requests-per-second guarantee.
- **Re-tune trigger:** operator observes rate-limit false positives.

### `RELAY_RATE_LIMIT_WINDOW_SECS`

- **Shape:** `u64` seconds, `parse().unwrap_or(60)`.
- **Effect:** fixed-window length paired with the per-window count. Larger window = smoother average admit rate but longer punishment for bursts that overflow.
- **Re-tune trigger:** paired with above.

---

## Push / notification

| env var | default | brief |
|---------|--------:|-------|
| `RELAY_NTFY_URL` | none (`None`) | Base URL of the ntfy push gateway. |

### `RELAY_NTFY_URL`

- **Shape:** string URL. `std::env::var().ok()` — no default, `None` if unset.
- **Effect:** when set, the relay forwards push notifications to this ntfy endpoint (compose default `http://ntfy:80` via the Docker bridge). Unset = no push.
- **Re-tune trigger:** operator relocates the ntfy service.

---

## Diagnostics (opt-in, off by default in production)

All diagnostic gates use a strict `"1"` parse: any other value (including `"true"`, `"yes"`, `"0"`, empty, unset) fails closed to `false`. Mutually-exclusive gates (M13) trip `std::process::exit(2)` if both are `"1"`.

| env var | default | brief |
|---------|--------:|-------|
| `RELAY_T2_DIAG` | false | T2 carrier-ceiling diagnostic. Mutex with SLOW_POST. |
| `RELAY_ENABLE_DIAG_SHAPE` | false | Diagnostic shape endpoint. Mutex with POLL_CHUNKED_FLUSH. |
| `RELAY_ENABLE_HEARTBEAT_ECHO` | false | Arm D heartbeat echo diagnostic. |
| `RELAY_ENABLE_SLOW_POST_DIAG` | false | T2 slow-POST diagnostic hook. Mutex with T2_DIAG. |
| `RELAY_POLL_HOLD_SECS` | 0 (short-poll) | REST long-poll hold interval (capped at 480 s). |
| `RELAY_POLL_CHUNKED_FLUSH` | false | Round-14 paced padded poll. Mutex with DIAG_SHAPE. |
| `RELAY_DIAG_POLL_SHAPE_ECHO_ENABLED` | false | B2-K11 §5B poll-shape echo. |
| `RELAY_DIAG_WS_K8_CLIENT_HOLD_OVERRIDE_ENABLED` | false | B2-K8 recon WS client-hold override. |
| `RELAY_DIAG_WS_K9_DOWNLINK_PROBE_ENABLED` | false | B2-K9 recon WS downlink probe. |

### General diagnostic gate contract

- **Shape:** strict `"1"` — anything else is `false`.
- **Effect:** each gate registers additional routes / instrumentation when true. When false, the corresponding route returns 404 (not 405) as a defence-in-depth measure per Vladislav 2026-06-06 hard gate B.
- **Re-tune trigger:** individual recon phases (T2, K8, K9, K11) — flip on for the phase, back off after.
- **Cross-refs:** `services/relay/src/config.rs` parsers; `services/relay/src/diag_poll_shape.rs`; `services/relay/src/t2_diag.rs`.

### `RELAY_POLL_HOLD_SECS`

- **Shape:** `u32` seconds, `parse().unwrap_or(0).min(MAX_POLL_HOLD_SECS_CAP=480)`.
- **Effect:** REST long-poll hold interval announced to clients via `SessionResponse.poll_hold_secs`. 0 = short-poll (existing behaviour). Operator opts-in by setting a non-zero value in `.env`; the runtime clamp inside `poll_hold_loop` mirrors the parse-time clamp so a future bypass can still not exceed 480 s.
- **Re-tune trigger:** Trek 2 Stage 1 kill switch: set back to 0 to revert to short-poll without a redeploy.
- **Cross-refs:** `services/relay/src/rest_fallback.rs` `MAX_POLL_HOLD_SECS_CAP`.

### Mutual-exclusion pairs

- `RELAY_T2_DIAG` vs `RELAY_ENABLE_SLOW_POST_DIAG` — both `"1"` → `std::process::exit(2)` with a clear FATAL message.
- `RELAY_ENABLE_DIAG_SHAPE` vs `RELAY_POLL_CHUNKED_FLUSH` — same shape.
- Both mutex pairs enforced in the config parser at startup; the M13 diagnostic-mode-exclusion contract is unit-tested via the parse helpers.

---

## Secrets

| env var | default | brief |
|---------|--------:|-------|
| `RELAY_SECRET_TOKEN` | none (`None`) | Long-lived shared secret for privileged internal endpoints. |
| `RELAY_SEQ_MAC_KEY` | **REQUIRED** — no default | Root key seed for the seq-MAC HMAC chain. |

### `RELAY_SECRET_TOKEN`

- **Shape:** string, `std::env::var().ok()` — no default; unset = no privileged endpoints available.
- **Effect:** required to call operator-only internal endpoints. Compose provisions via `.env`; the deploy-lint invariant ensures the token is not accidentally committed.
- **Re-tune trigger:** rotation policy or leak.

### `RELAY_SEQ_MAC_KEY`

- **Shape:** hex string (64 hex chars = 32 bytes), `load_seq_mac_root_key_from_env` (FATAL if absent, empty, or not exactly 64 hex chars).
- **Effect:** ROOT key for the seq-MAC HMAC chain. Each `Queued.seq_mac` field is computed ONCE at fresh-record creation inside `do_send` (`services/relay/src/rest_workers.rs:1034`) and written to disk as opaque bytes. `AckedTombstone` records DO NOT carry `seq_mac` at all. The relay does NOT recompute or verify per-record MACs at boot, in `do_send` replay handling, or in `sweep` -- the stored `seq_mac` is out-of-band verification material sent to the client on `/relay/poll` alongside each envelope; the client's `SeqMacVerifier` recomputes and checks it there. Boot verifies only that `queue_meta.seq_mac_key_fingerprint` matches the current root-key fingerprint; a mismatch on a non-empty queue exits `EXIT_SEQ_MAC_KEY_MISMATCH`. `queue_meta.boot_generation` is orthogonal (crash-recovery ordering) and does NOT bypass a fingerprint mismatch.
- **Re-tune trigger:** compromise or scheduled rotation. Rotation on a non-empty queue is refused at boot; drain the queue first (or reset the state-dir), then rotate + restart. Never bump silently.
- **The relay refuses to start without this var** — this is the only required env var without a fallback. Rationale: a silent no-MAC boot would violate the queue's integrity contract to downstream clients (whose `SeqMacVerifier` would receive unverifiable envelopes); a startup crash is the honest failure mode.
- **Generate with:** `openssl rand -hex 32`.
- **Cross-refs:** `services/relay/src/seq_mac.rs`, `services/relay/src/rest_workers.rs:1034` (compute site), `services/relay/src/boot_loader.rs:583` (fingerprint check), ADR-027 §"Boot generation + seq-MAC-key fingerprint".
