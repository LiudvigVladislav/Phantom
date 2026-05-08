# PHANTOM Security Roadmap

> **Living document.** Lists every security finding the project knows
> about, what we have already closed, and what is scheduled. Read
> alongside [`docs/threat-model/Threat_Model_v0.md`](../threat-model/Threat_Model_v0.md)
> (English exec summary at the top, formal model body in Russian
> below) and the running development journal at [`docs/PROJECT_LOG.md`](../PROJECT_LOG.md).
>
> **Honest scope statement.** PHANTOM is in active Alpha. The
> findings below are not surprises: they were surfaced by the
> project's own internal audits (`docs/audit/*` on disk) and by the
> design review that produced ADR-009 / ADR-016 / ADR-019 / ADR-023.
> We publish this roadmap so an external reviewer, contributor, or
> security researcher does not have to reverse-engineer our priority
> stack. If you spot a finding that is not listed, please report it
> per [`SECURITY.md`](../../SECURITY.md).

---

## Status legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Mitigated. Implementation merged on `master`; reference commit / ADR cited. |
| 🟡 | Partially mitigated. Some user-visible attack surface closed; the finding's full closure lands in the cited follow-up. |
| 🟦 | Scheduled. Design captured (ADR draft or `ARCHITECTURAL_DECISIONS_TODO.md` entry); implementation queued. |
| ⬜ | Acknowledged, not yet scheduled. |

---

## Mitigated findings (closed)

| ID | What | Closure |
|----|------|---------|
| **F11** | Identity X25519 private key was plaintext on disk | Wrapped via Android Keystore AES-256-GCM (master key never leaves secure hardware). Same primitive as the one this roadmap's outstanding F8 will reuse. |
| **F12 (partial)** | SignedPreKey signature verification + OneTimePreKey atomic single-use missing | SPK Ed25519 signature verified at bundle fetch (`SessionManager.kt`); OPK atomic single-use enforced at relay (`prekeys.rs::consume_bundle`). Remaining F12 work is in the OPK-rotation cadence and is bundled with the Beta security pass. |
| **F15** | Identity key reused as ratchet key | Fixed via fresh ephemeral DH for each new conversation. See [ADR-009](../adr/ADR-009-identity-prekey-separation.md). |
| **F17** | Notification callback exception was swallowed silently | Logged at `WARN` and surfaced through the foreground-service notification updater. |
| **F22** | Local SPK + OPK private bytes were plaintext in SQLite | Wrapped via Android Keystore AES-256-GCM (alias `phantom_prekey_wrap_v1`). See [ADR-023](../adr/ADR-023-Local-Prekey-Keystore-Wrap.md). Lazy migration: rows rewrite themselves on the next `maybeReplenishOneTimePreKeys` / `maybeRotateSignedPreKey` cycle. |
| **Sealed Sender envelope** | Was missing on some 1:1 message paths | Now applied to every regular 1:1 message type. The relay sees only routing metadata, not sender identity. |
| **F8** | Double Ratchet state was plaintext in SQLite | Wrapped via Android Keystore AES-256-GCM (alias family `phantom_ratchet_wrap_v1`). See [ADR-024](../adr/ADR-024-Ratchet-State-Keystore-Wrap.md). |
| **F19 + F20** | WebRTC call signalling (SDP / ICE / hangup) was sent unencrypted with `from`/`to` visible to the relay | Routed through the same Double Ratchet + Sealed Sender pipeline as regular messages. See [ADR-025](../adr/ADR-025-Call-Signaling-E2EE.md). |
| **F2 + F13** | Dead-code SenderKey signing path retained an Ed25519 keypair on every group entry | Signing path removed entirely. Migration `14.sqm` drops the dead columns. See [ADR-017](../adr/ADR-017-senderkey-signing-removal.md) (Status: Accepted). |
| **F1** | Group control messages (invite / SKD / add-member / leave) and per-recipient group ciphertext envelopes travelled outside the Double Ratchet | All outgoing group-related envelopes now route through `MessagingService.sendGroupControlMessage` → DR + Sealed Sender. The relay no longer sees chain keys, member rosters, or group activity timing. See [ADR-026](../adr/ADR-026-Group-Control-Messages-E2EE.md). |
| **F3** | Group SenderKey KDF used bare `SHA256(chainKey \|\| tag)` | Replaced with RFC 5869 HKDF-SHA256 with the iteration counter bound into the salt and `_v2` info-string suffixes. Built on `phantom.core.crypto.Hkdf` (the same primitive X3DH already uses). |
| **F4** | Member-leave did not rotate remaining members' keys (the leaver retained chain keys forever) | `handleLeave` now generates a fresh local SenderKey and broadcasts a fresh SKD to every remaining member before any post-leave send. Convergent: each remaining member runs the same handler, no coordinator state needed. |
| **F11 + F26** | Shared `?token=` WS auth (single secret across all installs; leaks via APK extraction + reverse-proxy access logs) | Replaced with per-user Ed25519 signed-challenge handshake bound to the existing identity signing keypair. TOFU first connect, 1:1 binding enforced thereafter (mirrors the `publish_prekeys` invariant). `RELAY_TOKEN` `BuildConfig` field removed. See [ADR-027](../adr/ADR-027-Per-User-Signed-Challenge-Auth.md). Production-validated on Tecno МТС + emulator 2026-05-09. |

## Acknowledged — Beta-tier hardening

These are recorded in the project's running findings list but are
neither scheduled nor blocking. They become real work during the
Beta polish window.

| ID | What | Plan |
|----|------|------|
| P2 batch (F6, F7, F9, F10, F12 retry, F14, F18, F23, F25) | Lower-severity findings (logging, edge cases, validation tightening) | Cleanup pass during Beta polish. No standalone PR planned; rolled into routine work. |
| Calls audio plumbing (PR 2.6) | One-way audio observed in 2026-05-09 cross-device QA — `JavaAudioDeviceModule` + `AudioFocus` integration deferred when calls were marked experimental | Bundled with the broader calls-leave-experimental milestone. |

---

## How this list is derived

Three inputs feed this roadmap:

1. **Internal audits on disk:** `docs/audit/SECURITY_AUDIT_2026_05_01.md`,
   `docs/audit/ARCHITECTURE_AUDIT_2026_05_01.md`,
   `docs/audit/RELAY_AUDIT_2026_05_01.md` — three independent
   review passes done on 2026-05-01 / 02 against the codebase as it
   stood after the X3DH 4-DH bootstrap landed. Findings are
   numbered F1 through F26 across those three documents.
2. **ADR design review:** every accepted ADR triggers a "what does
   this open up?" review pass; the security implications are
   captured in the ADR's Threat Model section and propagate here
   if they need follow-up implementation.
3. **External review:** when an outside auditor, security researcher,
   or contributor reports a finding through `security@phntm.pro` (see
   [`SECURITY.md`](../../SECURITY.md)), it lands here with full
   credit and a public timeline.

---

## Maintenance discipline

- Move a finding from "Scheduled" to "Mitigated" only after the
  closing PR lands on `master`. Cite the commit / ADR.
- Keep severity assessments honest. If a finding's exploitability
  worsens after new context arrives, move it up the queue and
  document why in the same edit.
- This document is itself the audit trail when an external reviewer
  asks "what do you know that you have not fixed yet?". Maintain
  the review-readiness implied by that question.

---

*Last reviewed: 2026-05-09. The post-Alpha-2 security pass landed
ahead of schedule: F8 + F19+F20 + F2+F13 + F1 + F3 + F4 + F11+F26
all closed in the 2026-05-08 → 2026-05-09 sprint. Next review:
when the calls subsystem leaves experimental status (PR 2.6 audio
plumbing) or when an external auditor surfaces new findings.*
