# Relay queue RAM budget re-calibration runbook

**Owner:** Relay maintainer (solo)
**Related:** [ADR-027](../adr/ADR-027-relay-queue-durability-and-ram-budget.md), `services/relay/bench/queue_ram_budget/README.md`

## When to run this procedure

Re-calibrate `DEFAULT_RELAY_RAM_BUDGET_BYTES` when any of the following changes. Every trigger below is either caught automatically by CI (blocks merge until the new number is landed) or noticed at review time.

| Trigger | Signal |
|---------|--------|
| Compose memory limit != 512 MiB | `deploy-lint.yml` `ram-budget-invariants` fails (`memory limit != 536870912`). |
| Runtime baseline drifts >= 10 MiB (new TLS stack, observability, etc.) | Manual — noticed at PR review. |
| New envelope shape outside the 5-scenario matrix | Manual — noticed when adding tests / handlers. |
| Measurable production RAM leak | Manual — noticed from operator dashboards. |
| Change to `record_ram_estimate` | Manual — noticed at PR review; the ledger↔RSS ratio no longer holds. |

Do not run this procedure "just to check" outside a trigger. The bench container is destructive to the local `phantom-relay-bench:m5b` image tag and wastes ~2 minutes per run.

## Prerequisites

The bench runs on a Mac / Linux host with the following (Windows dev host is compile-only — the `platform="unsupported"` fallback of the bench binary is for smoke-testing, not for producing evidence):

- Docker + docker-compose plugin
- `jq` (`brew install jq` on macOS, `apt install jq` on Debian/Ubuntu)
- The full repo tree (build context is the repo root; the harness copies `services/Cargo.toml` + `services/relay/**` into the builder stage)
- Free 512 MiB memory + ~1 GiB disk for the image
- Working directory positioned at the repo root

## Procedure

### 1. Pin the source commit

Check out the exact HEAD you intend to calibrate against. Stamp it — the evidence bundle will record it and reviewers will cross-check.

```bash
git -C <repo> log -1 --format='%H %s' > /tmp/m5b-head.txt
cat /tmp/m5b-head.txt
```

### 2. Run the versioned bench

The runner script:
- Builds `phantom-relay-bench:m5b` from `Dockerfile.bench` with the repo root as build context.
- Launches five clean `--memory 512m --memory-swap 512m --cpus 2.0 --platform linux/amd64` containers in fixed scenario order.
- Validates every line via `jq`: `.scenario_id` matches slot; `.platform == "linux"`; `.cgroup_memory_max_bytes == 536870912`; `.cgroup_oom_kill_delta == 0`; ten mandatory Linux/cgroup probes non-null.
- Writes NDJSON to `<OUT>.tmp`, renames to `<OUT>` on success, produces sidecar `<OUT>.meta` with runner + image + timestamp.

```bash
cd <repo>
./services/relay/bench/queue_ram_budget/run.sh m5b-scenarios.jsonl
```

If any assertion fails, the script exits non-zero and leaves `m5b-scenarios.jsonl.tmp` for inspection. Common failure modes:

- **`cgroup_memory_max_bytes != 536870912`** — the container's `--memory` did not resolve to exactly 512 MiB. Check that Docker Desktop's cgroup v2 is on (macOS 4.3+) and that the runner script's args were not modified.
- **`cgroup_oom_kill_delta > 0`** — the workload overshot the container limit mid-run. The RSS/cgroup samples that follow are truncated by kernel back-pressure, not by the workload; the calibration would be invalid. Investigate whether a scenario's `envelopes` count needs to shrink OR whether the runtime baseline drifted above the safety margin.
- **`platform != "linux"`** — Docker ran the binary against Docker Desktop's Rosetta shim or the harness was run outside Docker. Confirm `--platform linux/amd64` reached the container.

### 3. Stage the evidence directory (source + docker inspects)

Snapshot the raw inputs a reviewer needs. Do NOT tar or hash yet -- `m5b-calculation.json` is computed in step 4 and MUST land inside the same bundle.

```bash
BUNDLE_DIR=/tmp/m5b-evidence-$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$BUNDLE_DIR"
cp m5b-scenarios.jsonl "$BUNDLE_DIR/"
cp m5b-scenarios.jsonl.meta "$BUNDLE_DIR/"
git -C <repo> log -1 --format='%H%n%s' > "$BUNDLE_DIR/HEAD.txt"
git -C <repo> status --short > "$BUNDLE_DIR/git-status.txt"
docker image inspect phantom-relay-bench:m5b > "$BUNDLE_DIR/docker-image-inspect.json"
docker image inspect phantom-relay-bench:m5b --format '{{.Id}}' > "$BUNDLE_DIR/docker-image-id.txt"
```

`git-status.txt` MUST be empty. A dirty tree invalidates the calibration -- the source HEAD in `HEAD.txt` does not describe what ran.

### 4. Compute the calibration and write `m5b-calculation.json`

For each of the five NDJSON lines, compute:

```
process_peak_delta = rss_peak_post_seed_bytes - rss_baseline_bytes
cgroup_peak_delta  = cgroup_memory_peak_quiescent_bytes - cgroup_memory_current_baseline_bytes
observed_ratio     = max(process_peak_delta, cgroup_peak_delta) / ledger_ram_bytes
candidate_budget   = (cgroup_memory_max_bytes - cgroup_memory_current_baseline_bytes - SAFETY_MARGIN)
                     / observed_ratio
```

`SAFETY_MARGIN` is 134 217 728 bytes (128 MiB) by default; ADR-027 documents the rationale. Adjust only if the ADR's "Consequences" section changes.

`final_budget = min(candidate_budget_i)` across all five scenarios -- the worst-case scenario is the calibration point.

Floor the result to a conservative step (16 MiB used in M5b's original calibration). This absorbs measurement noise and produces a value operators can memorise.

Run the calculator directly against the NDJSON so the output is deterministic and reviewer-reproducible. `jq` is the operational floor; if the eventual step-6 workflow lands a dedicated calculator binary, swap it here and pin the version.

The command has two hard preconditions:

1. `-s` (slurp) is required -- without it, bare `inputs` silently DROPS the first NDJSON line (jq consumes it as the primary input) and the calibration would compute against four scenarios instead of five.
2. The full expected scenario set (`small-narrow` / `small-broad` / `large-narrow` / `large-broad` / `mixed-tombstoned`) MUST be present. A missing or extra `scenario_id` aborts the run BEFORE any calculation is written, so a corrupted NDJSON can never produce a downstream number.

```bash
set -euo pipefail

SAFETY_MARGIN=134217728
FLOOR_STEP=16777216
EXPECTED_SCENARIOS='["small-narrow","small-broad","large-narrow","large-broad","mixed-tombstoned"]'
CALC_FINAL="$BUNDLE_DIR/m5b-calculation.json"
CALC_TMP="${CALC_FINAL}.tmp"

# `if !` on the jq call keeps `set -e` intact but lets us react
# to a non-zero exit (scenario_id mismatch trips `error(...)`
# in the jq program) BEFORE the tmpfile is renamed. `> "$CALC_TMP"`
# always creates the tmpfile, so an empty output on jq failure
# also has to be caught -- `test -s` fires after the jq check.
if ! jq -s -c \
     --argjson margin "$SAFETY_MARGIN" \
     --argjson floor "$FLOOR_STEP" \
     --argjson expected "$EXPECTED_SCENARIOS" '
  def compute(s):
    (s.rss_peak_post_seed_bytes - s.rss_baseline_bytes) as $proc
    | (s.cgroup_memory_peak_quiescent_bytes - s.cgroup_memory_current_baseline_bytes) as $cg
    | (if $proc > $cg then $proc else $cg end) as $peak
    | ($peak / s.ledger_ram_bytes) as $r
    | (((s.cgroup_memory_max_bytes - s.cgroup_memory_current_baseline_bytes - $margin) / $r) | floor) as $cand
    | { scenario_id: s.scenario_id,
        process_peak_delta_bytes: $proc,
        cgroup_peak_delta_bytes: $cg,
        limiting_probe: (if $proc > $cg then "process" else "cgroup" end),
        observed_ratio: $r,
        candidate_budget_bytes: $cand };

  (map(.scenario_id) | sort) as $actual
  | (($expected | sort) == $actual) as $ok
  | if $ok | not then
      error("scenario_id set mismatch: actual=\($actual) expected=\($expected | sort)")
    else . end
  | map(compute(.)) as $scenarios
  | ($scenarios | min_by(.candidate_budget_bytes)) as $limiting
  | (($limiting.candidate_budget_bytes / $floor) | floor) as $floored
  | { schema_version: 1,
      safety_margin_bytes: $margin,
      floor_step_bytes: $floor,
      scenarios: $scenarios,
      limiting_scenario: $limiting.scenario_id,
      minimum_candidate_budget_bytes: $limiting.candidate_budget_bytes,
      recommended_budget_bytes: ($floored * $floor) }
' "$BUNDLE_DIR/m5b-scenarios.jsonl" > "$CALC_TMP"; then
  rm -f "$CALC_TMP"
  echo "ERROR: jq calibration calculator failed (scenario_id mismatch or malformed NDJSON); tmpfile removed" >&2
  exit 1
fi

# jq exit 0 does not guarantee non-empty output (some malformed
# inputs produce an empty stream instead of an `error(...)`); a
# zero-byte tmpfile also has to be caught before the rename.
if ! test -s "$CALC_TMP"; then
  rm -f "$CALC_TMP"
  echo "ERROR: jq calibration calculator produced empty output; tmpfile removed" >&2
  exit 1
fi

mv "$CALC_TMP" "$CALC_FINAL"

jq '.recommended_budget_bytes' "$CALC_FINAL"
```

The `if ! jq ... ; then rm; exit 1 ; fi` + `test -s` + `mv` shell shape is REQUIRED, not decorative. Prior versions of this runbook relied on `set -e` to catch jq's non-zero exit, but the redirect `> "$CALC_TMP"` always creates the tmpfile, and a bare `mv` on the next line unconditionally renamed a zero-byte file into the manifest input -- checksum manifest and archive would then hash an empty calculation. The explicit `rm -f` on the failure path plus the `test -s` gate close both failure modes before the manifest step runs. The recommended value cross-checks against the M5b original run (`83886080` for the shipped 80 MiB calibration).

### 5. Finalise manifest + archive

Now that every artifact is settled, compute the SHA-256 manifest and tar the whole directory.

```bash
cd "$BUNDLE_DIR" && sha256sum * > sha256sums.txt
tar -czf "$BUNDLE_DIR.tar.gz" -C "$(dirname "$BUNDLE_DIR")" "$(basename "$BUNDLE_DIR")"
sha256sum "$BUNDLE_DIR.tar.gz"
```

The manifest MUST include `m5b-calculation.json` -- if it doesn't, step 4 aborted or was skipped; do NOT publish the bundle in that state.

### 6. Land the new value

Single-commit shape. Every surface listed below must move in the same commit so the CI gate does not straddle a broken intermediate state:

1. **Rust constant** — `services/relay/src/main.rs`, `DEFAULT_RELAY_RAM_BUDGET_BYTES`. Update both the value and the doc-comment (which quotes MiB for humans).
2. **Rust unit tests** — `preflight_caps_defaults_are_below_compose_ceiling` and `capacity_caps_defaults_are_below_compose_ceiling` in `services/relay/src/main.rs`. Both pin the literal byte count in addition to the constant reference; update both literal assertions.
3. **Compose** — `deploy/docker-compose.yml`, `services.relay.environment.RELAY_QUEUE_RAM_BUDGET_BYTES` and `.RELAY_PREFLIGHT_RAM_BUDGET`. Both quoted strings; both equal the new byte count.
4. **ADR-027** — refresh "Calibration results (M5b, YYYY-MM-DD)" with the new per-scenario table, image digest, source HEAD, and bundle SHA. Do NOT delete the previous section; keep the history in place as a subsection so a future reader can diff.
5. **Env reference** — `docs/operations/relay-env-reference.md`, the two RAM budget rows. Update the "default" cell.

Then locally, before committing:

```bash
cargo test -p phantom-relay --release --no-fail-fast
cargo test -p phantom-relay              --no-fail-fast
python scripts/relay_invariants.py
python -m unittest discover -s scripts/tests -v
```

The invariants script's I3 check MUST pass on the new commit; failure means the compose value drifted from the Rust constant.

### 7. Verify on push

The CI gates run automatically:

- `Relay CI / build-test` — the Rust suites must stay green with the new defaults.
- `Relay invariants` — I3 confirms structural Rust-vs-compose equality; I5 confirms the two env-reference rows still list both env vars.
- `Deploy lint / ram-budget-invariants` — `docker compose config --format json` normalises the compose; the runner script strict-compares to the Rust-constant value AND `cgroup_memory_max_bytes == 536870912`.

Any gate red = the calibration wasn't landed atomically. Fix by amending the commit, not by pushing a follow-up.

## Notes for the reviewer

- The bench harness is opt-in at both Cargo (`--features queue-ram-budget-bench`) and Docker (`Dockerfile.bench`, separate from the production `Dockerfile`). A bare `cargo build --release` or a production compose rebuild never includes the harness. CI I4 catches any leak into production surface.
- The M5b image digest is not reproducible bit-for-bit across rebuilds (base image `rust:1.88-slim-bookworm` moves); this is expected. What must match is the invariants above — cgroup limit, oom delta, non-null probes — not the digest itself.
- Historical calibrations stay in ADR-027 for audit; do not overwrite them.
- If a re-calibration lowers the budget, drop the old default in the same commit — never leave a compose that admits more than the runtime cap allows.
- If a re-calibration raises the budget, the CI three-way check catches any surface that lags; that's the intended fail-loud behaviour.
