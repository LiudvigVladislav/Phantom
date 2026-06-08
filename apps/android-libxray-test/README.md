# `apps:android-libxray-test` — RC-LIBXRAY-REALITY-WIRE1 standalone diagnostic APK

> **STATUS: DIAGNOSTIC-ONLY. NOT FOR PRODUCTION. NOT SHIPPED.**
>
> This module exists solely to discriminate the leading hypothesis from
> the Arm G v10/v11 wire-stall verdict (memory pointer
> `project_arm_g_v10v11_libxray_android_write_stall_2026_06_08.md`,
> public brief `docs/project/TRANSPORTS_STATUS_2026_06_09.md` §6).
>
> It MUST NOT be promoted to `master` as a shipping app. It MUST NOT be
> built by CI as a shipping artefact. It MUST NOT depend on
> `:apps:android`. Per the Trek 1 mini-lock
> (`project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md`) the
> `release` build is configured to fail-loud (unsigned APK) rather than
> silently produce a shippable artefact.

## What this is

A minimal Android Activity that, for `iterations` fresh iterations,
constructs a single libXray `XrayService` with one Reality variant,
opens an OkHttp `/health` GET through the embedded SOCKS5 inbound, and
logs the result. The server-side `tcpdump` on the diagnostic Reality
container captures the wire pattern for cross-comparison with the
known Arm G v10 single-segment stall.

The test runs **outside the PhantomMessagingService harness** — no
identity, no `AppContainer`, no signed-challenge auth, no `Ktor`
WebSocket. This isolates whether the stall observed in Arm G v10/v11
is a libXray gomobile-level bug (reproduces here) or an Arm G
integration-level bug (does NOT reproduce here).

## Variants (Trek 1 mini-lock matrix)

| # | Slug          | `flow`              | `network`     | Status            |
|---|---------------|---------------------|---------------|-------------------|
| 1 | `baseline`    | `xtls-rprx-vision`  | `tcp`         | wired in this commit |
| 2 | `drop-vision` | `""`                | `tcp`         | added after baseline reproduces stall |
| 3 | `xhttp`       | `""`                | `xhttp`       | added after Variant 2 |
| 4 | `httpupgrade` | `""`                | `httpupgrade` | added after Variant 3 (or 2) |

Per the **first-test-order gate** in the mini-lock: if Variant 1 on this
standalone APK does NOT reproduce the single-segment stall observed in
the Arm G v10 server-side tcpdump (`/tmp/armg-v10-tecno-8443.pcap` on
phantom-relay-01), the matrix HALTS and the investigation pivots to an
Arm G harness-integration bug. Do NOT proceed to Variants 2-4 until
baseline confirms the standalone APK reproduces the stall.

## How to run

```bash
# Build
./gradlew :apps:android-libxray-test:assembleDebug

# Install on Tecno (or any test device on the same Wi-Fi as the Arm G
# tests of 2026-06-08). The applicationId is `phantom.libxray.test` so
# it co-exists with the production `phantom.android` install.
adb -s 103603734A004351 install -r \
  apps/android-libxray-test/build/outputs/apk/debug/android-libxray-test-debug.apk

# Launch with defaults (variant=baseline, iterations=50):
adb -s 103603734A004351 shell am start \
  -n phantom.libxray.test/.MainActivity

# Or pass explicit extras:
adb -s 103603734A004351 shell am start \
  -n phantom.libxray.test/.MainActivity \
  --es variant baseline \
  --ei iterations 50

# Watch logcat for per-iteration events under tag LIBXRAY_WIRE1:
adb -s 103603734A004351 logcat LIBXRAY_WIRE1:V PhantomXray:V GoLog:V *:S
```

Concurrently, run a server-side `tcpdump` on the diagnostic Reality
container (separate `:8444` overlay per Arm A.2 pattern, NOT production
`:8443`):

```bash
# On phantom-relay-01:
sudo tcpdump -ni any "tcp port 8444 and host <TECNO_EGRESS_IP>" \
  -ttt -v -s 2000 -w /tmp/wire1-baseline-tecno.pcap
```

After 50 iterations (or earlier if the pattern is conclusive), Ctrl-C
the tcpdump and compare the first-data-segment shape to the Arm G v10
baseline.

## Acceptance criteria (Trek 1 mini-lock)

For Variants 2 / 3 / 4 (when added):

- `/health` returns HTTP/2 200 reliably
- NO single-segment incomplete-TLS-record stall pattern
- Server-side tcpdump shows complete handshake / request body
- ≥ 50 consecutive iterations pass the above three checks

For Variant 1 / Baseline:

- MUST reproduce the single-segment stall (negative acceptance — this
  is the gate that proves the standalone APK is a faithful surface for
  the Arm G v10 bug). If it does NOT reproduce → HALT, investigate Arm G
  harness integration.

PSH flags on continuation segments are evidence, not a hard gate.
Wire shape may differ across variants (HTTP-framed `xhttp` /
`httpupgrade` have different segmentation patterns than raw TCP).

## Files

```
apps/android-libxray-test/
├── README.md                                — this file
├── build.gradle.kts                         — diagnostic-only module config
└── src/androidMain/
    ├── AndroidManifest.xml                  — single Activity + INTERNET
    └── kotlin/phantom/libxray/test/
        ├── MainActivity.kt                  — UI + scope lifecycle
        ├── Wire1Runner.kt                   — per-iteration test loop
        └── Wire1Variants.kt                 — variant catalogue (baseline only in this commit)
```

## Cross-references

- Trek 1 mini-lock (memory): `project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md`
- Parent plan: `project_arm_g_subtracks_locked_2026_06_09.md`
- Arm G v10/v11 wire-stall verdict: `project_arm_g_v10v11_libxray_android_write_stall_2026_06_08.md`
- Public transport status brief: `docs/project/TRANSPORTS_STATUS_2026_06_09.md`
- Architect analysis behind the splice-race hypothesis: `claude.md` (provided 2026-06-09)
