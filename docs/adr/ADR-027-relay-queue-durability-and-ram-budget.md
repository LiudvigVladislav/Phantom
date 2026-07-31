# ADR-027: Relay queue durability and RAM budget

**Status:** Accepted
**Date:** 2026-07-31
**Owner:** Relay maintainer (solo)
**Layer:** services/relay (Rust)
**Supersedes / relates to:** ADR-004 (Relay trust model), RC-RELAY-STATE-DIR-REPAIR (PR-1a/PR-1b, `docs/tracks/rc-relay-state-dir-repair.md`)

## Context

Alpha 1 through PR-1b shipped the relay with an in-process store: envelopes queued in `Arc<RwLock<HashMap<String, Vec<Envelope>>>>` plus a JSONL log of side-effects (reports, blocklist, push tokens, prekeys). This kept the on-wire behaviour boring but had three tightly-linked failure modes:

1. **Any relay crash lost queued envelopes.** The HashMap was RAM-only; nothing on disk survived a process restart. Clients replayed sends only if they hadn't yet received a 2xx response — a race any operator restart could hit.
2. **Envelope byte / envelope count / RAM occupancy were unbounded.** A cheap sender could pile up traffic to a recipient who was offline; the relay grew to whatever RSS the host tolerated.
3. **Mutation was scattered.** REST handlers, WS handlers, and background sweeps all wrote to the same shared `RwLock` guard. Adding a durability layer without corralling mutation would have multiplied write paths across every module.

PR-2 (RC-RELAY-QUEUE-DURABILITY) fixes all three across six milestones (M1–M6) landed as a single reviewable stack. This ADR documents the two decisions the reviewer needs to understand before reading the diff:

- **What durability shape we chose** — disk-first shard-worker actors with atomic writes and a capacity ledger.
- **How we picked the resulting RAM budget** — an opt-in benchmark harness (M5b), a five-scenario matrix under the target container's memory limit, and a fixed calibration formula.

## Decision

### Durability shape

- **Disk-first, RAM-derived.** Every envelope that admits into the queue is written to `RELAY_STATE_DIR/queue/<shard-2-hex>/<recipient-hex>/<sha256_hex(envelope_id)>.json` via `atomic_write::write_atomic` (staging temp-file → fsync → rename → parent-fsync). The on-disk filename is the SHA-256 of the envelope id (`services/relay/src/persistence.rs::record_filename`, design v4 §8 replay rule 3) so a casual filesystem enumeration cannot correlate envelope ids to shard dirs. The in-memory `HashMap` becomes a projection of what's on disk, not the source of truth. Restart = boot-time replay of the same directory tree; nothing is lost.
- **Shard-worker actor per recipient.** A fixed pool of 64 mpsc-backed workers (`REST_WORKER_COUNT`) owns per-recipient serialisation. Every mutation — `Send`, `Ack`, `Sweep` — is a `RestOp` sent on the owning worker's channel. Enforcement is layered: (a) the M4 handler cutover routes the primary transport paths (WS + `/relay/*` REST fallback) through `WorkerRuntime::try_send`; (b) M6-3 removed the last four legacy admin writers in `routes.rs` and made the WS reconnect expiry path read-only, so no in-tree handler mutates `state.store` outside `rest_workers.rs`; (c) the M6-2 CI tripwire (`scripts/relay_invariants.py` I2) is a lexical grep that fires on any new `.store.write()` outside the shard-worker file. The `[[bin]] [required-features]` gate is a separate mechanism -- it isolates the M5b benchmark binary from production builds, not writer ownership -- and does NOT contribute to the shard-worker contract.
- **Boot generation + seq-MAC-key fingerprint.** Every `Queued` record carries a per-recipient monotonic `seq`, a `body_hash`, and a `seq_mac` HMAC. `AckedTombstone` records DO NOT carry a `seq_mac` -- they preserve `seq`, `body_hash`, and the dedup horizon only. The root key comes from the operator-provided `RELAY_SEQ_MAC_KEY` env (`services/relay/src/config.rs:load_seq_mac_root_key_from_env`); it is NOT boot-random -- boot-generation numbering is orthogonal, driven by `queue_meta.boot_generation` for crash-recovery ordering. At boot the loader verifies that `queue_meta.seq_mac_key_fingerprint` matches the current root-key fingerprint; a mismatch on a non-empty queue exits `EXIT_SEQ_MAC_KEY_MISMATCH` -- a boot-generation bump does NOT bypass this, so rotating `RELAY_SEQ_MAC_KEY` requires an empty queue (either drain then rotate, or reset). Inside the runtime, `seq_mac_bytes = verify_key.compute_seq_mac(...)` is called ONLY at fresh-record creation in `do_send` (`services/relay/src/rest_workers.rs:1034`); no path re-verifies the stored MAC -- `do_send` computes a new one for the new record, `sweep` compares only `id/seq/body_hash/expires_at` metadata (via `verify_durable_queued`), and boot's replay walker does not touch the MAC field. The stored `seq_mac` is out-of-band verification material sent to the client on `/relay/poll` alongside each envelope; the client's `SeqMacVerifier` recomputes and checks it there. `/relay/ack-deliver` carries only the envelope id (no MAC field), so the ack-deliver stream is not part of the MAC-verification path.
- **Capacity ledger with `preflight >= runtime` invariant.** `GlobalCapacityGate` holds three caps: `max_envelopes`, `max_bytes`, `ram_budget`. Preflight caps must dominate runtime caps (M4-2b enforces at boot; a mismatch trips FATAL + `exit(11)` at `services/relay/src/main.rs:85`, same code the fail-closed parse path uses). Every `Send` reserves capacity before disk commit; drop-on-error rollback via RAII.
- **Tombstones with dedup horizon.** An `Ack` transitions the record to `AckedTombstone` on disk (same directory, different variant). The tombstone survives `RELAY_TOMBSTONE_DEDUP_HORIZON_SECS` seconds; within that horizon a client that re-`Send`s the same envelope receives `SendDisposition::TombstoneReplay` (original `seq` echoed, no new record) and an ack of an already-acked envelope receives `AckOutcome::Idempotent`. Sweep reclaims tombstones past their `dedup_until`.
- **Unified shutdown with a hard deadline.** `RELAY_SHUTDOWN_DEADLINE_SECS` bounds ordered drain; overshoot trips FATAL + exit 1 (M4-4).
- **Loopback health surface.** M4-4 added `RELAY_HEALTH_PORT` running `/live`, `/ready`, `/status` on 127.0.0.1 only, deliberately separate from the public listener so drain flips `/ready` 200 → 503 while the public listener finishes in-flight requests.

### RAM budget

The runtime capacity gate's `ram_budget` cap and the preflight cap that dominates it need a value operators can trust under the compose `--memory 512m` ceiling. Options considered:

1. **Pick a round number below 512 MiB** (e.g. 384 MiB, which the pre-M6 defaults used). Rejected by measurement — see M5b results below. Ignores the fact that the OOM killer fires on cgroup memory, not RSS, and cgroup includes filesystem page cache the atomic-write fsync path produces.
2. **Static analysis of the record types.** Rejected — Rust structs land in the allocator's actual segments plus heap fragmentation plus per-projection overhead; the estimator `record_ram_estimate(disk_bytes) = disk_bytes * 2 + RAM_STRUCT_OVERHEAD_BYTES * 2` is a lower bound, not a ceiling on process footprint.
3. **Empirical calibration under the target container limit** — chosen. A dedicated benchmark harness (M5b) loads N envelopes into a real `WorkerRuntime` inside a `docker run --memory 512m linux/amd64` container, samples VmRSS / VmHWM / cgroup memory.current / memory.peak / oom_kill counter, and emits NDJSON. Step 6 (this ADR) computes the calibration formula and picks the budget.

Single Rust constant, gated tests, cross-surface CI verification:

```rust
// services/relay/src/main.rs
const DEFAULT_RELAY_RAM_BUDGET_BYTES: u64 = 80 * 1024 * 1024;
```

Both `preflight_caps_from_env()` and `capacity_caps_from_env()` consume this constant for their `ram_budget` default. `deploy/docker-compose.yml` pins both env vars to the exact byte count (`"83886080"`). CI enforces three-way equality (Rust constant vs both compose env values) via `scripts/relay_invariants.py` I3 (structural regex) and `deploy-lint.yml ram-budget-invariants` (normalised through `docker compose config --format json`). Any drift between the three surfaces fails CI before merge.

## Calibration methodology

For each of five representative queue-occupancy scenarios (see matrix below), a fresh `linux/amd64` container was launched under `docker run --memory 512m --memory-swap 512m --cpus 2.0`. Inside the container, the M5b bench binary:

1. Took a baseline sample (VmRSS, VmHWM, cgroup memory.current, memory.peak, memory.events oom_kill) immediately after `spawn_worker_runtime`.
2. Seeded N envelopes deterministically (fixed body per index; recipients round-robin across a fixed pool).
3. Took a post-seed sample immediately after the last send reply.
4. Slept 2 seconds (fixed quiescence).
5. Took a quiescent sample.
6. Cross-checked runtime index counts vs disk walker vs ledger. Any mismatch, backpressure, timeout, unexpected disposition, non-zero OOM count, or missing Linux probe value trips a non-zero exit.
7. Emitted one NDJSON line with all sample values, ledger counters, and scenario parameters.

Step 6 (this ADR) computes the calibration formula. For each scenario:

```
process_peak_delta = VmHWM_after - VmRSS_baseline
cgroup_peak_delta  = memory.peak_after - memory.current_baseline
observed_ratio     = max(process_peak_delta, cgroup_peak_delta) / ledger_ram_bytes
candidate_budget   = (memory_max - cgroup_baseline - safety_margin) / observed_ratio
```

Two intentional differences from the naive `HWM_after - HWM_baseline` shape:

1. **Baseline is `VmRSS`, not `VmHWM`.** VmHWM is a monotonic historical peak; any startup transient the runtime already released still sits in `HWM_baseline`. Subtracting HWM from HWM therefore hides growth attributable to the seeded queue. Anchoring on the smaller `VmRSS` baseline is the conservative choice.
2. **cgroup baseline is `memory.current`, not `memory.peak`.** Container `--memory 512m` is enforced against cgroup-inclusive growth (working set plus filesystem page cache plus kernel slabs attributed to the cgroup). The M5b harness observes that `memory.peak_after - memory.current_baseline` was consistently larger than `VmHWM_after - VmRSS_baseline`; the harness records both but does not attempt to attribute the delta to any specific kernel bucket. Without a `memory.stat` snapshot (not captured in the M5b bundle) we cannot claim page cache alone accounts for the gap -- so this ADR treats the cgroup delta as "cgroup-inclusive growth" and lets the calibration formula pick whichever probe is more restrictive.

Safety margin (128 MiB in this calibration) covers allocator fragmentation over long uptime, spikes not exercised by the 10 000-envelope-per-scenario shape, and headroom above the actual OOM trigger. The final budget is `min(candidate_budget_i)` across the five scenarios — worst-case scenario sets the cap.

## Calibration results (M5b, 2026-07-31)

Container limit: **536 870 912 bytes** (512 MiB). Safety margin: **134 217 728 bytes** (128 MiB). Source HEAD: **`ff9d296185a0fdb5af5346b0e2605d0334dd4396`**. Bench-image digest: **`sha256:950b41fca5257d18ca8b1a6aa72370e2236837e3b42dbb1a180ba310bd0cadf7`** (linux/amd64, user `phantom`, built 2026-07-31T02:21:26Z). Runner: **`m5b-runner-v2`**. Full evidence bundle SHA-256: **`ba20705ac2e861e34e9d29afe6203921186f556f6fe171d8032a53f8e6138002`**.

Per-scenario:

| Scenario           | Ledger MiB | Process delta MiB | Cgroup delta MiB | Limiting probe | Observed ratio | Candidate MiB |
| ------------------ | ---------: | ----------------: | ---------------: | :------------: | -------------: | ------------: |
| small-narrow       |      19.53 |             14.57 |            82.32 |     cgroup     |         4.2150 |         89.81 |
| **small-broad**    |      19.51 |             17.92 |            87.63 |   **cgroup**   |     **4.4908** |     **84.37** |
| large-narrow       |      95.21 |             91.22 |           201.80 |     cgroup     |         2.1194 |        178.58 |
| large-broad        |      95.20 |             92.17 |           204.25 |     cgroup     |         2.1455 |        176.38 |
| mixed-tombstoned   |      70.89 |             66.55 |           163.02 |     cgroup     |         2.2998 |        164.55 |

Cgroup-inclusive growth was the limiting probe in every scenario -- cgroup delta consistently exceeded the process RSS delta. Whether the gap is page cache, kernel slab, or another cgroup-attributed bucket is out of scope for M5b (the harness would need a `memory.stat` snapshot to attribute it). The 384 MiB defaults the pre-M6 code shipped are not safe under a 512 MiB container regardless of the attribution.

Worst-case scenario **`small-broad`** with `observed_ratio = 4.4908`. Candidate budget 84.37 MiB. Floored to a conservative 16 MiB step:

```
RELAY_QUEUE_RAM_BUDGET_BYTES = 83_886_080   (exactly 80 MiB)
RELAY_PREFLIGHT_RAM_BUDGET   = 83_886_080
```

Both env vars must move together so preflight continues to dominate runtime.

## Consequences

- **Operators shipping the compose stack unchanged inherit 80 MiB by default.** Anyone overriding either env var must ensure preflight >= runtime; boot trips FATAL + `exit(11)` otherwise (same code the fail-closed env parsers use, so operator misconfiguration surfaces consistently).
- **An 80 MiB LEDGER budget does NOT translate to 80 MiB of container memory used** -- 80 MiB is the accounting cap the runtime enforces against the ledger's projected RAM, not the physical container footprint. At the limiting `small-broad` ratio of 4.4908×, an 80 MiB ledger projects to roughly `80 MiB × 4.4908 ≈ 359.3 MiB` of cgroup-inclusive growth. Adding the M5b baseline (~5-6 MiB immediately after `spawn_worker_runtime`) gives roughly `365 MiB` of expected cgroup occupancy at the ledger cap, leaving roughly `512 MiB − 365 MiB ≈ 147 MiB` of headroom in the container for network buffers, TLS session state, observability overhead, and transient spikes. The 128 MiB safety margin the calibration formula subtracted before division sits inside that headroom.
- **How much slack the calibration actually leaves** -- if `observed_ratio` for `small-broad` climbs above **~6.34**, the projected cgroup growth at the 80 MiB cap crosses the raw OOM boundary (`(memory_max − cgroup_baseline) / ledger_at_cap`). That's only **~1.41×** the currently-measured 4.4908 ratio. If instead the operator wants to keep the full 128 MiB margin retained, the ratio can climb only to **~4.74** before the margin is consumed -- **~1.055×** the current ratio, i.e. essentially no slack. The 80 MiB value is not "safe until the ratio doubles"; it is "safe against the specific measurements above with the specific 128 MiB margin subtracted". Any of the re-calibration triggers below (larger records, added runtime layers, new envelope shape) can plausibly push the ratio past the margin-retained ceiling and requires a fresh benchmark, not an ad-hoc raise.
- **Cross-surface drift is caught before merge.** Three CI gates enforce the three-way equality: I3 in `scripts/relay_invariants.py` (structural regex), `ram-budget-invariants` in `deploy-lint.yml` (normalised via `docker compose config --format json`), and two Rust unit tests pinning both defaults to `DEFAULT_RELAY_RAM_BUDGET_BYTES` AND the literal `83_886_080`.
- **The bench harness is opt-in.** `[[bin]] required-features = ["queue-ram-budget-bench"]` in `services/relay/Cargo.toml` plus a separate `Dockerfile.bench` mean a bare `cargo build --release` or a compose rebuild never accidentally ships the harness into production images. CI I4 catches any code / deploy / workflow file that references bench-harness needles.

## Re-calibration triggers

Re-run the M5b benchmark and land a fresh `DEFAULT_RELAY_RAM_BUDGET_BYTES` when any of the following changes:

- **Container memory limit changes.** `deploy/docker-compose.yml` `services.relay.deploy.resources.limits.memory` no longer resolves to 536 870 912. The `ram-budget-invariants` CI gate fails immediately in this case and blocks the change until the calibration is redone.
- **Runtime baseline changes materially.** New TLS stack, new observability layer, additional feature that occupies ~10+ MiB of steady-state RSS after `spawn_worker_runtime`.
- **New scenario the matrix does not cover.** A new envelope shape (larger sealed_sender, different payload distribution, higher recipient count than 1 000, higher tombstone ratio than 30 %) that plausibly changes the worst-case ratio.
- **A measurable production RAM leak** — the observed ratio would drift over uptime, invalidating the one-shot calibration.
- **A change to `record_ram_estimate`** (the ledger's per-record RAM projection). The calibration compares process/cgroup delta to ledger `ram_bytes`; changing the estimator changes the ratio.

Follow the procedure in `docs/tracks/rc-relay-queue-ram-recalibration.md`. The bench harness itself lives under `services/relay/bench/queue_ram_budget/`; the CI gates and Rust tests catch the resulting three-way drift automatically.

## What this ADR is NOT

- **Not a threat model.** Ciphertext-at-rest guarantees are unchanged from ADR-004 (the relay never sees plaintext; disk-first only extends the ciphertext-only property to persistence).
- **Not a commitment to a specific storage backend beyond flat files.** The M5b calibration was measured against the atomic-write + directory-tree layout; a future migration (SQLite, sled, custom KV) would require its own ADR and its own calibration.
- **Not a promise that 80 MiB survives every operational shape.** It survives the five scenarios above at the shipped safety margin. Everything outside that envelope is a re-calibration trigger.
