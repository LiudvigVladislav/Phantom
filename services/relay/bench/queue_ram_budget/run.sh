#!/usr/bin/env bash
# PR-2 M5b — queue-ram-budget versioned runner.
#
# Builds the opt-in bench image, then launches five clean
# `--memory 512m` linux/amd64 containers (ONE per scenario), and
# collects the five NDJSON lines into `${OUT}` via temporary
# file → rename in FIXED scenario order. Every mandatory
# Linux/cgroup probe is fail-loud validated with `jq` before the
# `.tmp` → final `mv`; runner metadata lives in a sidecar
# `${OUT}.meta` so the primary file is valid, unadorned
# NDJSON.
#
# The runner is deliberately opinionated:
#   * `--memory 512m` matches step 5's authorised limit;
#   * `--memory-swap 512m` prevents silent swap use skewing RSS;
#   * `--cpus 2.0` mirrors production container sizing;
#   * `--platform linux/amd64` pins arch even when the operator's
#     host is Apple Silicon (M5 authorisation ordered Mac/Docker
#     evidence).
#
# Runner versioning: bump SCRIPT_VERSION when the invocation
# shape or the validation set changes so step 5 evidence can
# label which runner produced the JSONL.

set -euo pipefail

SCRIPT_VERSION="m5b-runner-v2"
IMAGE_TAG="phantom-relay-bench:m5b"
OUT="${1:-m5b-scenarios.jsonl}"
TMP="${OUT}.tmp"
META="${OUT}.meta"
EXPECTED_MEMORY_MAX_BYTES=536870912

SCENARIOS=(
  small-narrow
  small-broad
  large-narrow
  large-broad
  mixed-tombstoned
)

# Mandatory non-null Linux/cgroup probes: any null here means the
# harness could not measure what step 6 relies on.
REQUIRED_NON_NULL=(
  rss_baseline_bytes
  rss_post_seed_bytes
  rss_quiescent_bytes
  rss_peak_baseline_bytes
  rss_peak_post_seed_bytes
  cgroup_memory_current_baseline_bytes
  cgroup_memory_current_quiescent_bytes
  cgroup_memory_peak_baseline_bytes
  cgroup_memory_peak_quiescent_bytes
  cgroup_memory_max_bytes
)

if ! command -v jq >/dev/null 2>&1; then
  echo "[${SCRIPT_VERSION}] ERROR: jq is required on the operator's host" >&2
  echo "                        install with 'brew install jq' (macOS) or 'apt install jq' (Debian/Ubuntu)" >&2
  exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "[${SCRIPT_VERSION}] ERROR: docker is required" >&2
  exit 2
fi

# Repo-root detection: this script lives at
# services/relay/bench/queue_ram_budget/run.sh; the docker build
# context must be the repository root so `services/` is reachable.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
DOCKERFILE="${SCRIPT_DIR}/Dockerfile.bench"

echo "[${SCRIPT_VERSION}] repo root:  ${REPO_ROOT}"
echo "[${SCRIPT_VERSION}] dockerfile: ${DOCKERFILE}"
echo "[${SCRIPT_VERSION}] out:        ${OUT}"
echo "[${SCRIPT_VERSION}] tmp:        ${TMP}"
echo "[${SCRIPT_VERSION}] meta:       ${META}"

echo "[${SCRIPT_VERSION}] building bench image ${IMAGE_TAG} ..."
docker build \
  --platform linux/amd64 \
  -f "${DOCKERFILE}" \
  -t "${IMAGE_TAG}" \
  "${REPO_ROOT}"

: > "${TMP}"

for scenario in "${SCENARIOS[@]}"; do
  echo "[${SCRIPT_VERSION}] === scenario ${scenario} ==="
  docker run \
    --rm \
    --platform linux/amd64 \
    --memory 512m \
    --memory-swap 512m \
    --cpus 2.0 \
    --stop-signal SIGTERM \
    "${IMAGE_TAG}" \
    --scenario "${scenario}" \
    >> "${TMP}"
done

# Sanity: exactly 5 lines, every one a valid JSON object with the
# right scenario id in the right slot.
tmp_line_count=$(wc -l < "${TMP}" | tr -d ' ')
if [ "${tmp_line_count}" -ne 5 ]; then
  echo "[${SCRIPT_VERSION}] ERROR: expected 5 lines in ${TMP}, got ${tmp_line_count}" >&2
  exit 1
fi

line_index=0
for scenario in "${SCENARIOS[@]}"; do
  line_index=$((line_index + 1))
  line=$(sed -n "${line_index}p" "${TMP}")

  if ! printf '%s' "${line}" | jq -e . >/dev/null 2>&1; then
    echo "[${SCRIPT_VERSION}] ERROR: line ${line_index} (scenario ${scenario}) is not valid JSON" >&2
    exit 1
  fi

  actual_scenario=$(printf '%s' "${line}" | jq -r '.scenario_id')
  if [ "${actual_scenario}" != "${scenario}" ]; then
    echo "[${SCRIPT_VERSION}] ERROR: line ${line_index} scenario_id=${actual_scenario}, expected ${scenario}" >&2
    exit 1
  fi

  platform=$(printf '%s' "${line}" | jq -r '.platform')
  if [ "${platform}" != "linux" ]; then
    echo "[${SCRIPT_VERSION}] ERROR: scenario ${scenario} platform=${platform}, expected 'linux'" >&2
    exit 1
  fi

  memory_max=$(printf '%s' "${line}" | jq -r '.cgroup_memory_max_bytes')
  if [ "${memory_max}" != "${EXPECTED_MEMORY_MAX_BYTES}" ]; then
    echo "[${SCRIPT_VERSION}] ERROR: scenario ${scenario} cgroup_memory_max_bytes=${memory_max}, expected ${EXPECTED_MEMORY_MAX_BYTES} (512 MiB)" >&2
    exit 1
  fi

  oom_delta=$(printf '%s' "${line}" | jq -r '.cgroup_oom_kill_delta')
  if [ "${oom_delta}" != "0" ]; then
    echo "[${SCRIPT_VERSION}] ERROR: scenario ${scenario} cgroup_oom_kill_delta=${oom_delta}, expected 0 (any OOM invalidates the measurement)" >&2
    exit 1
  fi

  for field in "${REQUIRED_NON_NULL[@]}"; do
    value=$(printf '%s' "${line}" | jq -r ".${field}")
    if [ "${value}" = "null" ]; then
      echo "[${SCRIPT_VERSION}] ERROR: scenario ${scenario} field ${field} is null (mandatory Linux/cgroup probe unavailable)" >&2
      exit 1
    fi
  done
done

# All 5 scenarios validated — atomically publish the primary
# file and write the runner-metadata sidecar.
mv "${TMP}" "${OUT}"
printf 'runner=%s image=%s built_at=%s\n' \
  "${SCRIPT_VERSION}" \
  "${IMAGE_TAG}" \
  "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  > "${META}"

echo "[${SCRIPT_VERSION}] wrote ${OUT} (5 scenarios, validated)"
echo "[${SCRIPT_VERSION}] wrote ${META}"
