# RC-LIBXRAY-REALITY-WIRE1 (Trek 1) — Stage 1 outcome

> **Status: app-level matrix CLOSED 2026-06-09. All three variants FAIL with
> byte-identical wire signature. No further app-level Reality stream-transport
> variants. V4 (httpupgrade) NOT pursued. Reality on Android is demoted to a
> diagnostic / future-fix track; the production realtime substrate decision
> moves to Trek 2 (`SHORT-CYCLE-LONGPOLL1`).**

## What was tested

A standalone diagnostic Android APK (`apps/android-libxray-test/`, build path
`apps:android-libxray-test:assembleDebug`, NOT in CI, NOT depending on
`:apps:android`) ran fresh-process libXray Reality outbound iterations against
the production Reality keypair via a separate diagnostic Xray container
brought up via `deploy/docker-compose.wire1-test.yml` (production `:8443`
container untouched the entire run, sha256-verified pre/post).

| Variant | Client `flow` | `streamSettings.network` | Server diag port | Field network | Iterations | Result |
|---|---|---|---|---|---|---|
| V1 `baseline`             | `xtls-rprx-vision` | `tcp`   | host `:8443` (production keys, production inbound) | Tecno + Tele2 LTE | 50 | **0 PASS / 50 FAIL** |
| V2 `drop-vision`          | `""`               | `tcp`   | host `:8444` → container `:8443` (diagnostic inbound) | Tecno + Tele2 LTE | 50 | **0 PASS / 50 FAIL** |
| V3 `drop-vision-xhttp`    | `""`               | `xhttp` | host `:8445` → container `:8445` (diagnostic inbound) | Tecno + Wi-Fi      | 50 | **0 PASS / 50 FAIL** |
| V4 `httpupgrade`          | — | — | — | — | — | **NOT pursued** (per upstream Xray transport docs, `realitySettings` is not valid with `network=httpupgrade`; running it would produce an ambiguous FAIL). |

## Wire signature (identical across V1 / V2 / V3)

Captured host-side via `tcpdump -ni any "tcp port <diag-port>" -s 0` and
decoded with a raw pcap parser independent of the third-party architect
review. Every measurement matched the architect's reading exactly.

- TCP 3-way handshake completes
- libXray sends **exactly one 1440-byte payload segment**
- The TLS handshake record header inside that segment advertises **1727 / 1759 / 1791 / 1823** bytes total (distribution across the 50 V3 flows: 15 / 13 / 13 / 9)
- The remaining **287 / 319 / 351 / 383** bytes of the outer Reality / TLS ClientHello are **never sent**
- Server sends **zero payload bytes**
- Server FINs first at ~10 – 13 seconds after handshake completion
- Server-side `phantom-xray-wire1` (Xray 26.3.27) logs zero errors during the run — this is normal Reality anti-probe behaviour for an incomplete ClientHello, not a server-side rejection

## What this rules out

- **XTLS-Vision splice handoff race as the sole cause.** V2 dropped Vision (`flow=""`) on both client and server, on the SAME raw TCP stream transport, and reproduced the stall byte-for-byte.
- **Raw TCP stream transport write completion path as the sole cause.** V3 kept `flow=""` and swapped the stream transport to `xhttp`. The stall reproduced at the same byte boundary, on the outer Reality / TLS ClientHello, BEFORE the xhttp framing layer has any opportunity to act. The bug therefore lives BELOW `streamSettings.network`.
- **Carrier-class throttling / mobile-uplink byte budget.** V1 and V2 ran on Tele2 LTE; V3 ran on Wi-Fi. Same shape, same byte counts, same flow distribution. The previous "carrier discriminates" framing is not load-bearing for this failure mode.
- **Server config / REALITY keys / diagnostic image.** `xray run -test` validated the rendered `config.json` before bring-up; the diagnostic container reused the production `XRAY_PRIVATE_KEY` / `XRAY_UUID` / `XRAY_SHORT_ID`; the production container's `config.json` sha256 was verified unchanged pre/post.
- **PHANTOM application code.** The diagnostic APK uses a separate `applicationId` (`phantom.libxray.test`), pulls only `:shared:core:xray`, and has no dependency on `:apps:android`. The stall reproduces in this minimal harness.

## What this localizes

The failure is in libXray's Android gomobile bridge write path for the
**outer Reality / TLS ClientHello**, upstream of `streamSettings.network`
selection. The vendored libXray ref at the time of the field runs is
`9a86646da8d8` bundling Xray-core v1.260327.0 = v26.3.27 (see
[`apps/android/build.gradle.kts`](../../apps/android/build.gradle.kts) and
[`shared/core/xray/build.gradle.kts`](../../shared/core/xray/build.gradle.kts)
for the actual versions on this branch).

A non-Android control we already have (WSL2 Linux Xray, same Reality keys,
same external network path) passes through to the production `:8443` cleanly
with a multi-segment ClientHello and a completed handshake. This rules out
the network path and the server, and isolates the regression to the Android
runtime side.

## Decisions

1. **Trek 1 Stage 1 app-level matrix is closed.** No further app-level Reality
   variants. No V4 `httpupgrade`.
2. **Reality on Android is demoted** to a diagnostic / future-fix track. It is
   NOT a production-stable realtime transport at the libXray ref we currently
   vendor.
3. **Direct WSS remains the fast path** for realtime where the network allows
   it; the WS reliability work continues on its own track.
4. **The production realtime substrate decision moves to Trek 2
   (`SHORT-CYCLE-LONGPOLL1`).** Scope and timeline for Trek 2 are a separate
   Council decision, not bundled into this closure.
5. Optional cheap follow-up, **timeboxed 1 – 2 days, separate decision**:
   bump the vendored libXray to a newer / different gomobile build and rerun
   the same V3 APK against the same diagnostic V3 inbound. If the 1440-byte
   stall disappears, Reality returns as a candidate; if not, the libXray
   write path needs source-level investigation upstream of this repo.

## What this commit does and does not change

This commit is a documentation-only Stage 1 closure:

- **Adds** this brief.
- **Does NOT** change any production code path.
- **Does NOT** remove the diagnostic harness — `apps/android-libxray-test/`,
  `deploy/xray-wire1-test/`, and `deploy/docker-compose.wire1-test.yml` stay
  in tree so a future libXray version discriminator can reuse the exact
  same V3 scenario without re-deriving the runbook.
- **Does NOT** activate Trek 2. That is a separate decision.

## Evidence

Field-test artefacts (pcap, logcat, server-container logs) are kept locally
by the analyst and not committed to the repo. Locations on the development
machine for traceability:

- `C:\temp\wire1-v3-tecno-8445.pcap` — 405 KB, LINUX_SLL2, 2238 packets, 50 client-initiated TCP sessions
- `C:\temp\wire1-v3-tecno-logcat.log` — Android logcat from the V3 run (RUN START → RUN DONE)
- `C:\temp\wire1-v3-container-logs.txt` — `docker logs phantom-xray-wire1` for the V3 run window

## Operator runbook (still valid, kept for the libXray version-bump follow-up)

[`deploy/xray-wire1-test/README.md`](../../deploy/xray-wire1-test/README.md)
remains the authoritative operator runbook for bringing up the diagnostic
inbound, running tcpdump in a `tmux` session (to survive SSH disconnects),
launching the APK, and tearing down cleanly. The runbook already documents
the V3 verdict matrix and the V4 deferral.

## Cross-references

- Parent two-track plan: original Arm G subtracks lock 2026-06-09 (kept in
  the analyst's memory)
- Wire-stall original evidence (Arm G v10 / v11, 2026-06-08): observation
  that surfaced the single-segment Reality ClientHello pattern that V1
  / V2 / V3 then confirmed in a clean minimal harness
- Operator runbook: [`deploy/xray-wire1-test/README.md`](../../deploy/xray-wire1-test/README.md)
