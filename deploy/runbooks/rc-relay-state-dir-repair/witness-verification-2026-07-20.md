# PR-1b local witness verification - PASS

Verified on 2026-07-20 from the evidence produced on 2026-07-19.

## Scope

- Relay source: `27a06c8aa06d15f2a152268e00c606a5cefff828` (PR #389).
- Relay image: `sha256:a07e53098650b6cac3585da3751d72c159b7ddce4d2b6a874078d1f6526198ce`, `linux/amd64`.
- Witness image: `sha256:3d08d6ea00763de0c6936e01b8ffe6c8c89b52e2cfdbbb6e574d22a5fb39d4be`, `linux/amd64`.
- Topology: isolated Mac Docker Desktop fixture, no host ports, no VPS or production access.
- Accepted residual: Docker Desktop VM and amd64 emulation do not witness native VPS topology or performance.

## Gates

1. Fresh-volume gate: PASS. Fresh volume became `phantom:phantom:750`, `.lock` existed, and `state_dir_preflight_ok` was emitted.
2. Read-only fixture: PASS. The relay refused boot on a read-only state volume, emitted `FATAL: preflight:`, and exited non-zero (`101`) within the bounded window.
3. Two-hour Mac-local integration witness: PASS.

## Two-hour evidence

- Observation: epoch `1784470079` through `1784477281` (7202 seconds).
- T0 functional gate: PASS.
- Scheduled probes: 8/8 PASS at 900-second intervals.
- Final active probe: PASS.
- Planned force-recreate: PASS.
- Post-recreate functional and structural re-attestation: PASS.
- Handler results: 44/44 successful (4 handlers across 11 phases).
- Persistence growth: all four state files grew in every phase; 0 failures across 11 phases.
- Original container CID remained unchanged throughout observation.
- Original container RestartCount: `0` at baseline and final pin.
- Recreated container CID differed from the original, proving recreate was not a no-op.
- Full-window anomaly rescan: PASS; no panic, FATAL, permission error, persist failure, torn-line event, OOM, SIGSEGV, ERROR-level token, or HTTP 5xx match.
- Evidence manifest: every entry in `sha256sums.txt` verified successfully.
- Launcher exit: `0`.
- Final verdict: `PASS: T0 gate + 2h observation + wall-clock floor + planned recreate + post-recreate re-attest all clean`.

## Final runbook hashes

- `README.md`: `36bed48a59e374cf5c010a578959643c491f0c0b697f517be340da9ad8c14e12`
- `NEXT-STEPS.md`: `4b01a81efdcde71a0b3282e0900980f9f9ca7e2266ef8730108ff21d925d1307`
- `run-pr1b-mac-local.sh`: `0b06e881ab3af2b1cf285bf226f74cd1582bf374ac5ef03e8f48e121cd9588d2`
- `pr1b-mac-local-witness.sh`: `a82ad2b5317112e3dbeb765f2a9c99a26aebed46d0b96979b8c53a098687ebd7`
- `pr1b-mac-local-compose.yml`: `4566f40b33bdbe7e17f0dcaef93abaeef74bfc99ba28f1b84bb3d9a414273f20`

## Decision

The proportional PR-1b local witness gate is satisfied. This permits preparation and review of the separate Ops PR in the locked sequence. It does not authorize a VPS or production deployment, production-volume mutation, release-flag change, or PR-2 merge.
