# queue-ram-budget bench

PR-2 M5b harness. Measures the ratio between the relay's
runtime-reported RAM estimate (`GlobalCapacityInner.ram_bytes`)
and actual process RSS + cgroup memory under five representative
queue occupancy patterns. Step 6 consumes the JSONL output to
pick `RELAY_QUEUE_RAM_BUDGET_BYTES`.

## Layout

```
bench/queue_ram_budget/
├── main.rs          — CLI + orchestrator + per-scenario execution
├── metrics.rs       — /proc/self/status + cgroup v2 readers (Linux only)
├── Dockerfile.bench — opt-in bench image (NOT touched by docker-compose)
├── run.sh           — versioned runner: 5 clean containers, NDJSON via tmpfile→rename
└── README.md        — this file
```

## Opt-in gates

- **Cargo**: `[[bin]]` entry gated by `required-features = ["queue-ram-budget-bench"]`. A bare `cargo build --release` skips it entirely; production image untouched.
- **Docker**: `Dockerfile.bench` is separate from `services/relay/Dockerfile` and only referenced from `run.sh`; production compose files never see it.

## Scenarios (fixed matrix, deterministic seeds)

| id                | envelopes | recipients | payload B | sealed B | tombstone % |
| ----------------- | --------: | ---------: | --------: | -------: | ----------: |
| small-narrow      |    10 000 |         10 |       128 |       64 |          0  |
| small-broad       |    10 000 |      1 000 |       128 |       64 |          0  |
| large-narrow      |    10 000 |         10 |     4 096 |       64 |          0  |
| large-broad       |    10 000 |      1 000 |     4 096 |       64 |          0  |
| mixed-tombstoned  |    10 000 |        100 |     4 096 |       64 |         30  |

Each scenario runs in a fresh process and fresh `TempDir`. The mixed scenario ends with exactly 7 000 Queued + 3 000 AckedTombstone records; any drift trips a fail-loud panic and the process exits non-zero.

## Local (Windows / Mac dev host) — one scenario

```
cd services
cargo run --release --package phantom-relay \
    --features queue-ram-budget-bench \
    --bin queue_ram_budget_bench -- \
    --scenario small-narrow
```

Emits one NDJSON line on stdout with all RSS/cgroup fields set to `null` on non-Linux hosts (`platform = "unsupported"`). Useful for a compile-check and to smoke-test the setup code.

## Local — all five scenarios (orchestrator mode)

```
cargo run --release --package phantom-relay \
    --features queue-ram-budget-bench \
    --bin queue_ram_budget_bench -- --all
```

`--all` spawns five child processes (one per scenario) and streams their NDJSON lines to stdout in fixed order. Meant for dev iteration; the operator's evidence path uses `run.sh` inside Docker.

## Official Docker path (step 5 of the M5 authorisation)

```
services/relay/bench/queue_ram_budget/run.sh m5b-scenarios.jsonl
```

Requires `jq` on the operator's host (`brew install jq` on macOS, `apt install jq` on Debian/Ubuntu). The runner refuses to start if `jq` is missing.

The runner:
1. Builds `phantom-relay-bench:m5b` from `Dockerfile.bench` with the repository root as the build context.
2. Launches five clean containers (`--platform linux/amd64 --memory 512m --memory-swap 512m --cpus 2.0`), one per scenario, in fixed order.
3. Writes each container's stdout (one NDJSON line) to `m5b-scenarios.jsonl.tmp`.
4. Validates every line with `jq` — see "run.sh fail-loud probes" below. Any invalid line aborts the run and leaves `.tmp` in place for inspection.
5. Renames `.tmp` → final path on success.
6. Writes a sidecar `<OUT>.meta` file (single line: `runner=... image=... built_at=...`) so the primary `<OUT>` is a valid, unadorned NDJSON file the standard parsers can consume without a skip-`^#` rule.

Output shape:
```
m5b-scenarios.jsonl        — five NDJSON lines, no headers, valid JSON Lines
m5b-scenarios.jsonl.meta   — one text line with runner + image + build timestamp
```

## run.sh fail-loud probes

For each of the five NDJSON lines, the runner asserts (via `jq`):

- `platform == "linux"` — Windows-fallback nulls must never survive into a step-5 evidence bundle.
- `cgroup_memory_max_bytes == 536870912` — the `--memory 512m` cap must actually be enforced on the container; a missing or drifted cgroup v2 mount silently reads `null` on the harness side.
- `cgroup_oom_kill_delta == 0` — a non-zero OOM count means the container hit the limit mid-run and the resulting RSS/cgroup samples are truncated by kernel back-pressure, not by the workload.
- Every mandatory Linux/cgroup probe non-null: `rss_baseline_bytes`, `rss_post_seed_bytes`, `rss_quiescent_bytes`, `rss_peak_baseline_bytes`, `rss_peak_post_seed_bytes`, `cgroup_memory_current_baseline_bytes`, `cgroup_memory_current_quiescent_bytes`, `cgroup_memory_peak_baseline_bytes`, `cgroup_memory_peak_quiescent_bytes`, `cgroup_memory_max_bytes`. Any `null` in this set aborts the run — step 6 has no way to distinguish "probe unavailable" from "value literally zero" and would silently produce an incorrect calibration.

Any single failing assertion aborts the run before the `mv` step, so `<OUT>` is never overwritten with a degraded measurement.

## NDJSON schema (v1)

Every scenario line carries:

| field | type | notes |
|-------|------|-------|
| `schema_version` | `u32` | bumps on breaking change |
| `scenario_id` | `string` | matrix key |
| `params` | `object` | envelopes / recipients / payload_bytes / sealed_bytes / tombstone_pct |
| `platform` | `"linux" \| "unsupported"` | non-linux → all mem fields null |
| `quiescence_secs` | `u64` | fixed 2 s between post-seed sample and quiescent sample |
| `elapsed_seed_secs` | `f64` | wall-clock of the seeding loop |
| `state_dir` | `string` | TempDir path (removed on exit) |
| `envelopes_seeded` | `u64` | scenario's `envelopes` |
| `queued_count` | `u64` | disk walker: Queued records |
| `tombstone_count` | `u64` | disk walker: AckedTombstone records |
| `sum_disk_bytes` | `u64` | disk walker: total record bytes |
| `ledger_active_bytes` | `u64` | `capacity().snapshot().active_bytes` |
| `ledger_tombstone_bytes` | `u64` | `capacity().snapshot().tombstone_bytes` |
| `ledger_ram_bytes` | `u64` | `capacity().snapshot().ram_bytes` — the estimator step 6 calibrates against |
| `rss_baseline_bytes` | `u64 \| null` | `VmRSS` after runtime spawn, before seed |
| `rss_post_seed_bytes` | `u64 \| null` | `VmRSS` immediately after last reply |
| `rss_quiescent_bytes` | `u64 \| null` | `VmRSS` after 2 s quiescence |
| `rss_peak_baseline_bytes` | `u64 \| null` | `VmHWM` before seed |
| `rss_peak_post_seed_bytes` | `u64 \| null` | `VmHWM` after quiescence (monotonic since baseline) |
| `cgroup_memory_current_baseline_bytes` | `u64 \| null` | cgroup v2 `memory.current` before seed |
| `cgroup_memory_current_quiescent_bytes` | `u64 \| null` | cgroup v2 `memory.current` after quiescence |
| `cgroup_memory_peak_baseline_bytes` | `u64 \| null` | cgroup v2 `memory.peak` before seed |
| `cgroup_memory_peak_quiescent_bytes` | `u64 \| null` | cgroup v2 `memory.peak` after quiescence |
| `cgroup_memory_max_bytes` | `u64 \| null` | cgroup v2 `memory.max` (the container's `--memory` value); `null` if `"max"` |
| `cgroup_oom_kill_delta` | `u64 \| null` | `memory.events oom_kill` after − before |

## Step 6 derived quantities

The harness does not compute the calibration formula — that stays in step 6 so the intermediate values are audit-able. Step 6 reads the JSONL and computes, per scenario:

```
process_peak_delta = rss_peak_post_seed - rss_baseline
cgroup_peak_delta  = cgroup_memory_peak_quiescent - cgroup_memory_current_baseline
observed_ratio     = max(process_peak_delta, cgroup_peak_delta) / ledger_ram_bytes
```

Two intentional differences from the naive `HWM_after − HWM_baseline` shape:

1. **Baseline is RSS, not HWM.** `VmHWM` is a per-process monotonic historical peak — a startup transient allocation the runtime has since released still sits in `HWM_baseline`. Subtracting HWM baseline from HWM after therefore underreports the growth attributable to the seeded queue. Anchoring the delta on the smaller `VmRSS` baseline is the conservative choice.

2. **cgroup baseline is `memory.current`, not `memory.peak`.** The container's `--memory 512m` limit is enforced against cgroup total (which includes page cache the relay's `atomic_write` produces from record fsyncs) — cgroup peak is what would actually trip the OOM killer, RSS alone does not. Taking `max(process_peak_delta, cgroup_peak_delta)` picks whichever of the two is more restrictive.

The worst (highest) `observed_ratio` across the five scenarios sets the calibration point. Budget headroom is computed against the cgroup baseline (not RSS baseline) because that is what the operator's memory limit is enforced against:

```
RELAY_QUEUE_RAM_BUDGET_BYTES =
    (cgroup_memory_max - cgroup_memory_current_baseline - safety_margin)
    / observed_ratio
```

The safety margin is step-6-tunable but is expected to include (a) allocator fragmentation growth over long uptime, (b) headroom for spikes not exercised by the 10 000-envelope-per-scenario shape, and (c) a nonzero buffer between the calibrated cap and the actual OOM trigger.

## Fail-loud invariants

Any of the following exits the scenario process non-zero:

- `RuntimeSendError::Full` on any `try_send` — dispatch mpsc must not backpressure under the generous caps
- Reply oneshot timeout or drop
- `SendError::{CapacityExceeded, PerRecipientQueueFull, …}` — infrastructure regression under generous caps
- Any `SendDisposition` other than `Fresh` — envelopes are unique
- Any `AckOutcome` other than `Acked` — envelopes are freshly-sent before ack
- Any fatal broadcast during the run
- Walker vs ledger vs runtime index count / bytes mismatch
- `mixed-tombstoned` final shape != (7 000 Queued, 3 000 AckedTombstone)
- Any residual `.staging-*` file on disk
- `drain_handles` returning a `!is_clean()` worker outcome

Panics abort the scenario process only; the runner (`run.sh`) picks up the non-zero exit and aborts the overall JSONL production.

## Non-scope

- No production `capacity_ledger` / `rest_workers` changes.
- No `criterion` dev-dep; the harness measures memory, not latency.
- No CI integration (M6 handles any grep-gates / gated CI jobs).
- The actual `RELAY_QUEUE_RAM_BUDGET_BYTES` value is picked in step 6, not here.
