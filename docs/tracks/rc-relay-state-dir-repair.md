# Track: `RC-RELAY-STATE-DIR-REPAIR` — mini-lock

**Type:** Docs / scope-lock. NOT a code change.
**Status:** LOCKED 2026-07-12 (architect + operator sign-off).
**Opened:** 2026-07-11 after 6-lens verification confirmed 4-file JSONL
persistence silently no-ops in production against master `a94c4931`.
**Supersedes:** `RC-RELAY-RESTART-CURSOR-DESYNC` mini-lock (parked per
§12).

---

## §1 Goal

Fix relay state-file persistence so writes actually reach the writable
named volume. Four files affected: `reports.jsonl`, `blocklist.txt`,
`push_tokens.jsonl`, `prekeys.jsonl`. Every write silently swallows
`Err(EROFS)` today because container CWD is `/`, rootfs is
`read_only: true`, and paths are bare filenames. Each
`docker compose up -d --force-recreate relay` wipes the assumed state
because that state never landed on disk in the first place.

Foundation only. Queue durability (`rest_store` persistence, boot-epoch
seq encoding, graceful shutdown, fsync policy) is the follow-up track
`RC-RELAY-QUEUE-DURABILITY` (§11). Foundation must land and soak first
because any attempt to add state before then hits the same silent-EROFS
channel.

## §2 What this mini-lock is NOT

- Not a queue durability fix. Acked envelopes still lost on restart
  after PR-1b — that is `RC-RELAY-QUEUE-DURABILITY` scope.
- Not a client-side change. Server-only. No BuildConfig, no wire
  shape, no cursor semantics touched.
- Not a B2 addendum. K11 5B/5C, K8 microsoak, K9 FAIL, DWS park all
  unchanged.
- Not a re-open of frozen tracks (B1 Wi-Fi, K8, K9 stay closed).
- Not a Sprint 2b re-scope. `OpkNotFound` and DEFERRED log fix already
  shipped (PR #374). This track fixes a plausible upstream (verified
  post-hoc in PR-1c).
- Not a release-flag rollout. Three RC quiescence flags stay `"0"`.
  `LONGPOLL_V2_ENABLED` stays `"1"`. LTE gate in force.

## §3 Code-recon (verified against master `a94c4931`)

### §3.1 Container FS posture forbids relative-path writes

- `services/relay/Dockerfile:48-65` — Stage 2 sets `USER phantom` and
  `ENTRYPOINT` but no `WORKDIR`. Stage 1's `WORKDIR /build` does not
  cross the `FROM` boundary. Docker default CWD = `/`.
- `deploy/docker-compose.yml:76` — `read_only: true` on rootfs. Only
  writable paths: `/tmp` (tmpfs 16MB) and `/var/phantom` (named volume
  `phantom-reports`, declared line 79-80).

### §3.2 Four state files use bare relative paths, silent-swallow shape

Path constants:
- `services/relay/src/state.rs:296` — `REPORTS_FILE = "reports.jsonl"`
- `services/relay/src/state.rs:297` — `BLOCKLIST_FILE = "blocklist.txt"`
- `services/relay/src/state.rs:298` — `PUSH_TOKENS_FILE = "push_tokens.jsonl"`
- `services/relay/src/prekeys.rs:90` — `PREKEYS_FILE = "prekeys.jsonl"`

`grep -r "/var/phantom" services/relay/src/` = zero matches; the volume
is unused by code.

Four write sites, identical shape `if let Ok(mut f) = OpenOptions::new()
.create(true).append(true).open(FILE) { let _ = writeln!(f, "{}", line); }`:

- `services/relay/src/state.rs:326-329` (`append_report_to_disk`)
- `services/relay/src/state.rs:333-336` (`append_block_to_disk`)
- `services/relay/src/state.rs:357-361` (`append_push_token_to_disk`)
- `services/relay/src/prekeys.rs:671-677` (`append_to_disk`)

Outer `if let Ok(...)` drops `Err` on else; inner `let _ = writeln!`
drops short-writes. Both layers no-op silently under EROFS.

### §3.3 Cascade result

By construction from §3.1 ∧ §3.2: all four persistence writes silently
no-op in prod. Boot loaders (`state.rs:309, 316, 339`; `prekeys.rs:656`)
return empty maps. Runtime state is session-lived; every restart starts
from zero.

### §3.4 Sprint 2b-C `OpkNotFound` and TOFU regression are downstream of §3.3

- **`OpkNotFound`.** `services/relay/src/prekeys.rs:305-321` self-
  documents the invariant "no two consumes get the same OPK across
  restart". The mechanism is a no-op under §3.3. Two paths converge:
  (a) restart wipes pool, A does not re-publish, C fetches → 404 →
  DEFERRED (PR #374 reason surface); (b) re-publish after restart
  hands same client-derived OPK to multiple senders → receiver X3DH
  fails with GC'd private half → DEFERRED. Resolution verification
  belongs to PR-1c.
- **TOFU-window regression.** `state.rs:229-232`
  (`rebuild_signing_keys_from_prekeys`) called from `main.rs:24` seeds
  `SigningKeyBindings` from an empty store under §3.3. Every WS
  handshake after restart re-runs first-write-wins TOFU registration
  in `auth.rs:164 get_or_register_tofu`. Documented invariant
  regression against F11+F26. Verification belongs to PR-1c.

### §3.5 `disk_writes` counter is a lie

`services/relay/src/prekeys.rs:187, 293, 320, 404`. Bumps on write-
attempt, not persist-success. Any test asserting on it asserts a lie.
Rename + split belongs to PR-1b.

### §3.6 `deploy/README.md:56` misleads operators

`sudo mkdir -p /var/phantom && sudo chown 10001:10001 /var/phantom` on
the host is inert for a named volume. Docker stores the volume under
`/var/lib/docker/volumes/deploy_phantom-reports/_data`; the host path
`/var/phantom` is not the volume backing store. Remediation in PR-1a.

### §3.7 Workspace-pollution artifact hides the bug in CI

`services/relay/prekeys.jsonl` exists at repo root, 19,021 bytes,
mtime 2026-07-09. Tests run with workspace CWD (writable on developer
host), so writes succeed against the workspace file; assertions pass;
the container-CWD-and-`read_only` gap is invisible. `.gitignore:86-88`
covers `prekeys.jsonl`, `reports.jsonl`, `blocklist.txt`.
`push_tokens.jsonl` is not ignored — a future test could commit it by
accident. Cleanup belongs to PR-1a.

## §4 Fix-space

### §4.1 Path-resolution shape (LOCKED — env-driven config field)

`RelayConfig.state_dir: PathBuf`, sourced at boot from
`std::env::var("RELAY_STATE_DIR")` with default
`PathBuf::from("/var/phantom")`. Threaded into `AppState::new(cfg)` and
every loader / writer. `PreKeyStore::new(&Path)` gains an argument.

Rejected:
- **Absolute constants without env** — not test-tunable without global
  `env::set_var`, which races across test workers.
- **`WORKDIR /var/phantom` only** — fragile; ship WORKDIR as defence-
  in-depth (§5.1), not sole mechanism.
- **`PHANTOM_STATE_DIR`** — project convention is `RELAY_*` for
  relay-scoped env (matches `RELAY_NTFY_URL`,
  `RELAY_DIAG_POLL_SHAPE_ECHO_ENABLED`, `RELAY_SEQ_MAC_KEY`).

### §4.2 Per-file failure policy split (LOCKED)

**Audit / recovery tier** — `reports.jsonl`, `blocklist.txt`,
`push_tokens.jsonl`:
- Runtime persist error → `tracing::error!` + counter increment.
- Caller semantics preserved (handlers still return 2xx).
- Rationale: losing these is recoverable through operator/client re-
  action. Amplifying disk-pressure into HTTP 5xx costs more than it
  prevents.

**Correctness / security tier** — `prekeys.jsonl`:
- Runtime persist error → HTTP 500 with structured JSON
  `{"error":"prekey_persist_failed","reason":"<eio_shape>"}` plus
  tracing/metric hooks.
- Applies to both `publish_prekeys` and `fetch_bundle` consume path.
- **Atomicity requirement (LOCKED).** A persist failure on either
  path MUST leave zero observable in-memory mutation. Two shapes are
  acceptable; PR-1b picks one and pins it in a code comment:
  - `fetch_bundle` consume — either **persist-first** (compute the
    post-consume snapshot without touching RAM; persist; on Ok pop
    the OPK under the write lock and return the bundle; on Err drop
    the lock without mutation and return 500), OR **rollback-on-
    failure** (snapshot RAM under the write lock; pop; try persist;
    on Err restore the snapshot before dropping the lock; on Ok
    drop and return). Concretely: no code path may return 500 with
    an OPK already popped from RAM — otherwise a caller retry loop
    burns the whole pool without any peer receiving a bundle.
  - `publish_prekeys` — either persist-first over the intended new
    `StoredPreKeyState` OR rollback-on-failure that restores the
    identity's prior state (or removes the identity entry entirely
    when there was no prior state) before dropping the lock.
- Rationale: prekey persistence is load-bearing for OPK "no double-
  serve across restart" invariant (Sprint 2b-C correctness). Silent-
  swallow here is the direct upstream of observed `OpkNotFound` and
  TOFU regression classes. A 500 without atomic rollback is worse
  than the pre-fix silent-swallow — retries burn the pool faster than
  silent-swallow ever could.

Applies uniformly in PR-1b. Neither behaviour ships in PR-1a (§6).

## §5 Deploy topology

### §5.1 Dockerfile changes (PR-1a)

`services/relay/Dockerfile` Stage 2. Between `useradd` at line 54 and
`COPY --from=builder` at line 56, add:

```dockerfile
RUN mkdir -p /var/phantom \
    && chown phantom:phantom /var/phantom \
    && chmod 750 /var/phantom
```

After the `COPY`, add: `WORKDIR /var/phantom`.

Rationale: `mkdir + chown + chmod` seeds the volume with correct
ownership/permission on FIRST mount to an empty volume (fresh-VPS
bootstrap). `WORKDIR` is defence-in-depth against future refactor
reintroducing relative-path write. Both are inert on already-populated
volumes (§5.3).

### §5.2 docker-compose changes (PR-1a)

`deploy/docker-compose.yml` relay service block:
- Add `RELAY_STATE_DIR: /var/phantom` under `environment:`.
- Inline comment next to `phantom-reports:/var/phantom` (line 80)
  tying the volume path to `RELAY_STATE_DIR` so a future rename
  surfaces the mismatch on review.

Retain `read_only: true`.

### §5.3 Existing-volume repair (PR-1a operator runbook — BLOCKING)

Docker seeds a named volume from the image ONLY on first mount to an
empty volume. The production `phantom-reports` volume was created for
the pre-fix image (where `/var/phantom` did not exist in the image
layer) and its root directory is owned `root:root 0755`. The Dockerfile
chown in §5.1 does NOT propagate to an already-populated volume.

Operator MUST run one of the following BEFORE
`docker compose up -d --force-recreate relay`:

**Path A — one-shot sidecar, non-destructive (mandated for prod):**

```bash
docker run --rm \
  -v deploy_phantom-reports:/d \
  alpine:3.20 \
  sh -c 'chown -R 10001:10001 /d && chmod 750 /d'
```

`chmod 750` is load-bearing here: §5.1 Dockerfile chmod seeds only on
first mount to an empty volume; existing volume keeps default `0755`,
and §7.3 witness expects `phantom:phantom:750`.

**Path B — `docker compose down -v` (destructive; fresh-VPS only):**

Kills every named volume in the project including `caddy-data` (LE
cert cache, 50-issuance/week rate-limited), `phantom-tor-data` (v3
onion identity), `webtunnel-tor-state` (bridge fingerprint clients
have pinned). Requires backups first. Not the prod path.

PR-1a body MUST cite the Path A invocation output alongside the deploy
witness (§7.3).

### §5.4 `deploy/README.md:56` remediation (PR-1a)

Delete the misleading `sudo mkdir -p /var/phantom && sudo chown
10001:10001 /var/phantom` line OR gate behind an explicit "IF you
switch from a named volume to a host bind-mount…" block. Ships in
PR-1a alongside compose edit so operator docs never suggest a no-op
again.

## §6 Sequencing

Three PRs (1a introduce, 1b fail-loud, 1c confirm) plus a pointer to
the separate `RC-RELAY-QUEUE-DURABILITY` track.

### §6.1 `PR-1a` — RC-RELAY-STATE-DIR-INTRODUCE

Deliverables: `RelayConfig.state_dir: PathBuf` from `RELAY_STATE_DIR`
env, default `/var/phantom` (§4.1); `StatePaths` threaded into
`AppState::new(cfg)` + `PreKeyStore::new(&Path)`; four loaders +
writers read injected paths, not file-name constants; Dockerfile
changes (§5.1); docker-compose changes (§5.2); `deploy/README.md:56`
remediation (§5.4); workspace cleanup (`git rm services/relay/prekeys.jsonl`;
add `push_tokens.jsonl` to `.gitignore`); operator Path A sidecar +
deploy witness in PR body (§7).

RETAINED for PR-1a (DO NOT SHIP in 1a): silent-swallow shape untouched;
no fail-loud preflight; no grep-gate CI check; no `fs2` singleton
lock; no prekey fail-closed. Rationale: §5.3 existing-volume situation
means fail-loud preflight would refuse to boot until Path A runs;
adopting it in the same PR compresses two independent verification
cycles into one.

### §6.2 `PR-1b` — RC-RELAY-STATE-DIR-FAIL-LOUD

Merge only after PR-1a has soaked in prod ≥ 3 days with deploy witness
confirming all four files land on the volume under normal traffic.

Deliverables: silent-swallow flipped per §4.2; boot-time writable
preflight in `main.rs` before `AppState::new` (`create_dir_all` +
sentinel write-then-unlink, panic-loud); `fs2::try_lock_exclusive` on
`state_dir/.lock` (exit 2 if held); CI grep-gate (new
`.github/workflows/relay-hygiene.yml` or extend `deploy-lint.yml`, §8);
`disk_writes` counter rename per §3.5; Rust integration tests (§8).

### §6.3 `PR-1c` — RC-RELAY-STATE-DIR-CONFIRM (non-blocking follow-up)

- Verify Sprint 2b-C `OpkNotFound` incident rate drops post-1b. Recon:
  clean-stand E2E send/receive with pm-clear + fresh pair; simulate
  relay restart; measure re-publish → next-fetch behaviour.
- Verify TOFU regression (§3.4) resolved: after restart, existing
  identity WS handshake replays `SigningKeyMismatch` gate instead of
  re-registering.
- Add `services/relay/tests/state_dir_privacy_gate.rs` — allowlist
  test for persisted files.

### §6.4 `PR-2` — RC-RELAY-QUEUE-DURABILITY pointer (separate track)

Full mini-lock in a separate document opened in parallel to this one
but implementation gated behind PR-1a and PR-1b landing. Preview:

- Persist `rest_store` as durability unit under `state_dir`.
- Seq encoding: `boot_epoch_ms << 24 | per_recipient_counter` primary
  shape. Derive-`max(seq)`-from-queue was refuted: queue can be empty
  at boot (post-TTL/ack steady state) and would regress the target bug
  through a new vector.
- `.with_graceful_shutdown()` on `axum::serve` (`main.rs:193`).
- Explicit fsync policy (per-envelope `sync_data` OR group-commit 100ms
  batch — choose, document, test).
- `state.store` (WS store) treatment: persist alongside OR document
  "WS reconnect falls back to REST poll" explicitly.
- `RELAY_SEQ_MAC_KEY` fingerprint marker to detect key rotation.
- Idempotency cache persist OR document as accepted residual.
- `RestEnvelope.from == ""` serialize-time assertion (Privacy caller-
  preserved invariant becomes schema-enforced).
- ADR entry acknowledging at-rest metadata expansion; security-reviewer
  sign-off required (ADR-004 amendment scope).

## §7 Acceptance gates for PR-1a (blocking)

### §7.1 Rust integration tests

Add `services/relay/tests/state_persistence.rs`. Uses
`RelayConfig.state_dir` field injection — NOT `env::set_var` (test-
worker race hazard).

- `write_then_reload_round_trip` — for each of the four state files,
  drive the corresponding handler, assert the file exists under a
  `tempfile::tempdir()` state_dir, drop `AppState`, construct a fresh
  `AppState` against the same dir, assert the record loads back.
- `state_dir_config_not_env` — meta-test scoped to persistence-tier
  test files (`state_persistence.rs`, `prekey_endpoints.rs`, any
  successor): they MUST inject `RelayConfig.state_dir: PathBuf`, MUST
  NOT call `env::set_var("RELAY_STATE_DIR")`. Config-parse unit tests
  that legitimately exercise env parsing are out of grep scope.

Add `tempfile = "3"` to `services/relay/Cargo.toml`
`[dev-dependencies]`.

### §7.2 Deploy-lint expansion

Extend `.github/workflows/deploy-lint.yml`. Both `RELAY_STATE_DIR` env
AND `WORKDIR /var/phantom` are locked deliverables (§5.1 + §5.2) —
neither may be silently lost, so the check uses `and`, not `or`:

```python
relay = compose["services"]["relay"]
assert relay.get("read_only") is True
assert any("/var/phantom" in v for v in relay.get("volumes", []))
env = relay.get("environment", {}) or {}
dockerfile = open("services/relay/Dockerfile").read()
assert env.get("RELAY_STATE_DIR") == "/var/phantom", \
    "compose env RELAY_STATE_DIR regression"
assert "WORKDIR /var/phantom" in dockerfile, \
    "Dockerfile WORKDIR /var/phantom regression"
```

Extend `paths:` filter to include `services/relay/Dockerfile`.

### §7.3 Deploy witness (PR body — BLOCKING)

PR body MUST include outputs of the following against the VPS AFTER
`docker compose up -d --force-recreate relay`:

- `git log -1 --oneline` — commit sha.
- `docker inspect phantom-relay --format "{{.Created}} {{.Image}}"` —
  image sha and creation time.
- `docker exec phantom-relay id` — expect uid=10001(phantom).
- `docker exec phantom-relay stat -c "%U:%G:%a" /var/phantom` — expect
  `phantom:phantom:750`.
- After driving **all four** persistence-family writes (one each of
  `POST /prekeys/publish`, `POST /report`, `POST /admin/block`,
  `POST /push/register` — the four handlers are the only writers of
  the four state files), `docker exec phantom-relay test -s
  /var/phantom/prekeys.jsonl && test -s /var/phantom/reports.jsonl &&
  test -s /var/phantom/blocklist.txt && test -s
  /var/phantom/push_tokens.jsonl` — MUST exit 0 (all four present
  and non-empty). A `publish + report + push_register` alone would
  only cover 3 of the 4 files; the missing `admin/block` is
  load-bearing because `blocklist.txt` is the only file the other
  three writes never touch.
- `docker exec phantom-relay ls -la /var/phantom` — list all four
  files with sizes and mtimes for the reviewer.
- `docker exec phantom-relay ls / | grep -Ei "\.jsonl|blocklist"` —
  MUST be empty (no relative-path spill onto the read-only rootfs).

Path A sidecar invocation output (§5.3) MUST also appear in the PR
body, timestamped before the `up -d --force-recreate` step.

### §7.4 Workspace cleanup verification

`git ls-files | grep -E '\.jsonl|blocklist\.txt'` returns empty in the
PR diff. `.gitignore` grep confirms `push_tokens.jsonl` covered.

## §8 Acceptance gates for PR-1b (blocking)

**Rust tests** (extend §7.1 suite):
- `preflight_fails_on_readonly_state_dir` (`#[cfg(unix)]`) — chmod
  tempdir read-only, boot preflight `.expect()` panics.
- `preflight_fails_when_parent_is_readonly` — target dir does not
  exist AND parent is read-only, so `create_dir_all` cannot make it.
  (Bare "nonexistent path with writable parent" is a no-op assertion
  because preflight's own `create_dir_all(&state_dir)` succeeds.)
- `state_dir_lock_prevents_second_instance` — `fs2::try_lock` semantics.
- `load_skips_torn_last_line_and_logs` — write valid + half-line,
  loader returns valid + skipped-count metric fires.
- `prekey_write_failure_returns_500` — inject persist error, assert
  HTTP 500 with structured JSON body.
- `prekey_consume_persist_failure_preserves_opk` — pins the §4.2
  atomicity requirement. Publish a bundle with a known OPK `K`;
  inject persist error on the consume path; call
  `GET /prekeys/bundle/:identity` and assert HTTP 500; call again
  with the persist error cleared and assert the returned OPK's
  `key_id_hex` equals `K`'s (i.e. `K` was NOT popped from RAM by
  the failed call).
- `prekey_publish_persist_failure_preserves_previous_state` — pins
  the §4.2 atomicity requirement for publish. Publish state `S1`
  successfully; inject persist error and publish state `S2`; assert
  HTTP 500; clear the persist error and call
  `GET /prekeys/bundle/:identity`; assert the returned bundle
  matches `S1` (not `S2`, not 404). Companion no-prior-state case:
  first-ever publish with persist error → subsequent fetch returns
  404 (identity NOT partially inserted).
- `report_write_failure_preserves_2xx_and_logs_error` — audit tier
  path: 2xx preserved, `tracing::error!` fired, counter incremented.

**CI grep-gate**: two `rg --pcre2` patterns return zero hits under
`services/relay/src/` (excluding `tests/`) — `if let Ok(...) =
(std::)?fs::OpenOptions` and `let _ = writeln!`. Gate ships in the
same PR that removes the last silent-swallow hit so baseline is zero.

**Prod deploy witness (PR body — BLOCKING)**: §7.3 shape plus log grep
`state_dir_preflight_ok` shown once at boot.

**Fresh-volume gate — STAGING / LOCAL / DISPOSABLE-VPS ONLY, NEVER
PROD (PR body — BLOCKING)**: on a fresh compose stack, run
`docker compose stop relay && docker volume rm deploy_phantom-reports
&& docker compose up -d --force-recreate relay`; assert
`stat -c "%U:%G:%a" /var/phantom` returns `phantom:phantom:750`.
`docker volume rm` is a state-loss operation and out-of-bounds on the
prod volume.

**Simulated-failure witness — CI / LOCAL COMPOSE FIXTURE, NEVER PROD
(PR body — BLOCKING)**: local/CI fixture downgrades the volume mount
to `:ro`, `up -d --force-recreate`, expect exit 1 within 5s with
`state_dir_preflight_failed`. Manual mutation of prod compose is
out-of-bounds; prod witness shows `state_dir_preflight_ok` only.

## §9 Rollback

**PR-1a:** revert restores CWD=/ + relative paths + silent-swallow. If
any state was written under the fix, the reverted binary ignores
`/var/phantom` (constants point at CWD). No client-side rollback.

**PR-1b:** revert restores silent-swallow; fail-loud preflight and
prekey HTTP-500 policy disappear. Boot succeeds without sentinel
check. No client-side rollback.

## §10 Release-flag posture

Unchanged. Three RC quiescence flags stay literal `"0"`.
`LONGPOLL_V2_ENABLED` stays `"1"`. LTE gate in force.

No new BuildConfig field. No new relay env flag except
`RELAY_STATE_DIR` (server-side only, default resolves to correct prod
path, opt-out for tests only).

## §11 Pointer forward — `RC-RELAY-QUEUE-DURABILITY`

Separate mini-lock, drafted in parallel but implementation gated behind
PR-1a and PR-1b landing. Load-bearing anchors: seq encoding
`boot_epoch_ms << 24 | per_recipient_counter` primary (derive-from-
queue-max refuted); `.with_graceful_shutdown()` non-negotiable + SIGKILL
fixture test via `docker kill -s KILL`; explicit fsync policy required;
`RestEnvelope.from == ""` invariant becomes schema-enforced; ADR entry
required (ADR-004 amendment scope, not routine).

## §12 What this mini-lock supersedes

`RC-RELAY-RESTART-CURSOR-DESYNC` mini-lock is PARKED without
implementation. Rationale in the 6-lens synthesis; short form: Option A
no-ops under §3.3, removes the only observable signal (`cursor_noop`),
and fails to close the acked-envelope-loss path; Option B UUID alone is
detection without recovery; correct primary shape is `boot_epoch_ms`-
prefixed seq in the queue-durability track on top of a repaired
foundation. The problem class that mini-lock targeted (cursor desync
after restart) is subsumed by `RC-RELAY-QUEUE-DURABILITY` via
`boot_epoch_ms` seq encoding.

## §13 Out of scope

- `rest_store` / `state.store` queue persistence.
- Seq encoding, MAC key handling, client-side cursor semantics.
- Any B2 transport work.
- Any Sprint 2b crypto reliability work beyond §3.7 verification in
  PR-1c.
- Prometheus/OpenTelemetry metric plane bring-up.
- Multi-region / blue-green deploy topology.
- Backup / snapshot policy for the `phantom-reports` volume.

## §14 Do-not-do list (locked)

1. No docs PR for the parked `RC-RELAY-RESTART-CURSOR-DESYNC`.
2. No SeqCounter-only persistence in any form.
3. No fifth relative-path JSONL file. New persistent state MUST resolve
   against `state_dir` and appear in a registry test.
4. No monolith PR-1. PR-1a introduce vs PR-1b fail-loud split is
   load-bearing on prod safety.
5. No PR-1a deploy without §5.3 Path A sidecar (chown + chmod 750)
   preceding the compose recreate.
6. No `docker compose restart relay` for deploy — must be
   `up -d --force-recreate`. Restart re-uses current image.
7. No `env::set_var("RELAY_STATE_DIR")` in persistence-tier tests —
   inject via `RelayConfig.state_dir: PathBuf` (config-parse unit
   tests exempt).
8. No delivery-durability claim from cursor-only or state-dir-only
   fixes. Delivery durability lands only after `rest_store` persistence
   + `boot_epoch_ms` seq + graceful shutdown + explicit fsync policy +
   SIGKILL-restart-poll test in `RC-RELAY-QUEUE-DURABILITY`.
9. No delete of `cursor_noop` client signal in
   `RestFallbackOrchestrator.kt` until PR-2 durability proven ≥ 1 week
   in field.
10. No `rest_store` persistence without ADR entry acknowledging at-rest
    metadata expansion + security-reviewer sign-off.
11. No silent-swallow of state write errors in PR-1b. Audit tier
    logs+metric; correctness tier fails closed AND leaves zero
    observable in-memory mutation on persist Err (§4.2 atomicity
    requirement — persist-first or rollback-on-failure).
12. No `docker volume rm` witness on the prod `phantom-reports` volume
    — that gate is staging/local/disposable-VPS only.
13. No manual mutation of prod docker-compose for `:ro` simulated-
    failure witness — CI/local fixture only.

## §15 Related

- Supersedes the earlier `RC-RELAY-RESTART-CURSOR-DESYNC` mini-lock,
  which was drafted 2026-07-11 and is parked without implementation
  per §12.
- Prior JSONL persistence pattern: `state.rs:296-361`, `prekeys.rs:90,
  671-677`.
- DEFERRED log fix (PR #374, `a94c4931`) is the user-visible symptom of
  the persistence gap this track fixes at the relay side.
