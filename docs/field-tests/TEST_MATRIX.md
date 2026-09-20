# PHANTOM field-test matrix

Last updated: 2026-09-17

This file is the public record behind every field-test claim PHANTOM makes.
If a claim is not supported by a row here, it should not appear on the website,
in the README, or in any investor material.

## What this matrix is and is not

Every run below was executed by the founder on the founder's own devices. None
of it is an independent benchmark, and none of it establishes universal
resistance to filtering. A result recorded on one carrier, in one region, on
one date tells you what happened in that session and nothing more.

Carrier networks change their filtering behaviour without notice, so an older
PASS is evidence about the past, not a guarantee about the present.

`not recorded` means the value was not captured at the time. It is left visible
rather than reconstructed, because a plausible guess in an evidence table is
worse than an honest gap.

## Privacy

No tester name, phone number, IP address, device serial, street address, or
other personal data appears in this file, and none may be added to it. Device
serial numbers and relay-side IP addresses exist in internal diagnostic records
and are deliberately excluded here.

## Matrix

| date | app commit | device | Android version | carrier | region | access type | VPN state | transport | test case | result | latency | known limitation | tester type |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-05-07 | not recorded | Tecno BF7-12 | not recorded | MTS | Irkutsk Region | mobile data | off | VLESS+REALITY | Reality outer transport reachable end to end (ADR-019 acceptance run) | PASS | not recorded | Single session; no repeat run recorded | founder |
| 2026-05-09 | not recorded | Tecno BF7-12 + Android emulator | not recorded | MTS | Irkutsk Region | mobile data | off | Direct WSS | Per-user Ed25519 signed-challenge authentication | PASS | not recorded | — | founder |
| 2026-05-15 | not recorded | Tecno BF7-12 | not recorded | T2 | Irkutsk Region | LTE | off | REST over TLS | `POST /prekeys/publish` with a 100-key OPK batch | FAIL | 30.05 s to timeout, 7/7 attempts | Request body truncated at exactly 8192 bytes by a middlebox; mitigated later by reducing the batch to 40 keys | founder |
| 2026-05-16 | not recorded | Tecno BF7-12 | not recorded | T2 | Irkutsk Region | LTE | off | Direct WSS | WebSocket text frames after a successful handshake | FAIL | session lifetime approx. 31 s | Uplink frames dropped; `pings_received=0` and `inbound_frames=0` across 20+ server-side sessions | founder |
| 2026-05-28 | 899d45bd | Tecno BF7-12 | not recorded | T2 | Irkutsk Region | LTE | off | Direct WSS + REST fallback | Network-change rewalk (test #88, scenario B) | PASS | not recorded | — | founder |
| 2026-06-09 | 63899f1f | Tecno BF7-12 + Android emulator | not recorded | T2 | Irkutsk Region | LTE | off | REST media path | 4 voice notes, 9-15 chunks each, both directions | PASS | not recorded | Voice travels the REST media path, not the WebSocket | founder |
| 2026-06-16 | not recorded | Tecno BF7-12 | not recorded | T2 | Irkutsk Region | LTE | off | long-poll backbone | Stage 2B-D one-time-prekey lifecycle integration smoke | PASS | not recorded | — | founder |
| 2026-06-21 | not recorded | Tecno BF7-12 | not recorded | T2, then Wi-Fi | Irkutsk Region | LTE to Wi-Fi handover | off | Direct WSS + REST fallback | Sticky-per-route degradation across a route change | PASS | degraded mode detected at 31 s; 60 s recovery probation | Feature ships disabled in release builds | founder |
| 2026-07-04 | 7207593b | Tecno BF7-12 + Android emulator | not recorded | T2 | Irkutsk Region | LTE | off | mixed | Direct-stability diagnostic window, 12:09-12:58 UTC | diagnostic, no pass/fail verdict | not recorded | Instrumentation run, not an acceptance test | founder |
| 2026-08-28 | c5c420c7 | Tecno BF7-12 | not recorded | Yota | Irkutsk Region | mobile data | off | Direct | WSS-3 carrier matrix, profile `yota-phone-off-host-on` | PASS 26/26 evidence, 8/8 package, 40/40 transport decisions | not recorded | Host-on profile only; the four host-off profiles were not run | founder |
| 2026-08-28 | c5c420c7 | Tecno BF7-12 | not recorded | Yota | Irkutsk Region | mobile data | on | Direct | WSS-3 carrier matrix, profile `yota-phone-on-host-on` | PASS 26/26, 8/8, 40/40 | not recorded | Host-on profile only | founder |
| 2026-08-29 | c5c420c7 | Tecno BF7-12 | not recorded | T2 | Irkutsk Region | mobile data | off | Direct | WSS-3 carrier matrix, profile `tele2-phone-off-host-on` | PASS 26/26, 8/8, 40/40 | not recorded | Host-on profile only | founder |
| 2026-08-29 | c5c420c7 | Tecno BF7-12 | not recorded | T2 | Irkutsk Region | mobile data, Wi-Fi off | on | Direct | WSS-3 carrier matrix, profile `tele2-phone-on-host-on` | PASS 26/26, 8/8, 40/40 | not recorded | Host-on profile only | founder |
| not recorded | not recorded | Tecno BF7-12 | not recorded | MTS | Irkutsk Region | mobile data | off | Tor v3 onion | Ghost mode bootstrap without a VPN (test #6) | PASS | approx. 6 min to bootstrap | Date not captured; single session; text only, no calls or media | founder |

## Coverage gaps

These are the gaps a reader should assume until a row above closes them.

- **One region, one handset model.** Every row is Irkutsk Region on a Tecno
  BF7-12, sometimes paired with an emulator. There is no coverage of other
  Russian regions, other countries, other manufacturers, or other Android
  versions.
- **Android version is not recorded anywhere.** This is a real recording
  failure in the process, not an omission from this file.
- **Latency is mostly unmeasured.** Only failure timings and a few bootstrap
  durations were captured. There is no delivery-latency distribution.
- **REALITY has one acceptance run and no repeat.** Carrying realtime
  WebSocket traffic inside REALITY on T2 LTE has been designed but not yet
  field-tested.
- **Tor has one undated run.** It is a text-only emergency path.
- **The four WSS-3 host-off profiles were never executed**, so the carrier
  matrix is four of eight profiles, not a complete matrix.
- **No independent party has reproduced any row.**

## How rows are added

1. Run the test and capture the raw evidence outside this file.
2. Add one row per run, filling `not recorded` where a value genuinely was not
   captured rather than inferring it.
3. Strip every identifier: no serials, no IP addresses, no tester identities.
4. Update any public claim that the new row changes, in the same change.
