# Direct WSS Yota-First — Operator Runbook (Mac)

**Contract:** [`../direct-wss-yota-contract.md`](../direct-wss-yota-contract.md)

## Prerequisites (Mac)

- `adb` — Android platform-tools on PATH.
- `python3` >= 3.9.
- `bash`, `jq` (`brew install jq` if missing).
- One physical Android phone with **Yota** as the default-data SIM.
- One running Android Emulator (any recent API 33+).

## Phone radio setup

Before starting:

- Yota SIM is the DEFAULT DATA subscription.
- Wi-Fi OFF.
- VPN / proxy / private DNS OFF.
- Automatic data switching OFF.
- Any second SIM (e.g. Tele2) with mobile data OFF.

Preflight will call the app's default-data-operator API and print the observed operator numeric; confirm it matches Yota when prompted.

## First-time bootstrap

```bash
cd operator-package
./run-yota-wss-diagnostic.sh preflight       # detects devices + prep
./run-yota-wss-diagnostic.sh bootstrap --fresh
```

`bootstrap --fresh` uninstalls the app from both devices (no `-k`, all data lost), verifies absence, verifies the APK SHA-256 against `android-debug-diagnostic.apk.sha256`, then reinstalls.

After bootstrap:
1. On the phone AND the emulator, run the production onboarding UI. Create two real Phantom identities.
2. On the phone AND the emulator, open Profile → **My Phantom QR** → **Share my Phantom contact**. Scan the QR from one device on the other. This creates the paired conversation each side needs.
3. Confirm each device shows exactly ONE conversation with the other as peer.

## Matrix run

```bash
./run-yota-wss-diagnostic.sh preflight   # re-run for fresh evidence dir
./run-yota-wss-diagnostic.sh matrix
```

The matrix drives 8 directed cells × 5 envelopes = 40 envelopes (contract §9.3). No manual `am` invocation, no logcat capture, no envelope-ID grep — the runner + verifier handle everything.

REST capability is determined by preflight (Method B controlled fail-closed probe). If REST is disabled on the target relay, cells #7 and #8 are stamped `BLOCKED` and skipped.

## Reading the report

```
open evidence/yota-wss-<UTC-STAMP>/verification-report.md
```

The report contains two independent results:

- `evidence_integrity` — bundle completeness. **GREEN** means the WSS_DIAG events were captured cleanly and the schema is intact. Fully-collected failure evidence is `evidence_integrity = GREEN`.
- `product_outcome` — per-cell classification: `Recovered` (Priority 1) / `Delivered once` (Priority 2) / `Unresolved` (Priority 3) / `BLOCKED` (REST capability off). Aggregate is RED if any non-blocked cell is not `Delivered once` or `Recovered`.

`evidence_integrity=GREEN, product_outcome=RED` is a **successful diagnostic run** — architect can now see what's broken.

## Tele2 follow-up

Once a Yota bundle is captured cleanly:

- Do NOT re-bootstrap.
- Switch the phone's default-data SIM to Tele2.
- Wi-Fi OFF, VPN OFF, other SIM's mobile data OFF.
- `./run-yota-wss-diagnostic.sh preflight` — the dual-SIM check now reads the Tele2 operator; confirm.
- `./run-yota-wss-diagnostic.sh matrix` — writes a fresh evidence dir.

Same APK, same identities, same paired conversation — only the radio is different.

## Hard rules

- No manual logcat command.
- No manual envelope correlation.
- No `am force-stop`.
- No airplane-mode toggle.
- No arbitrary send text (the debug receiver refuses).
- No operator-supplied contact alias (the debug receiver refuses; conversation is auto-selected).
- No release APK — preflight refuses.
- No VPS action.
