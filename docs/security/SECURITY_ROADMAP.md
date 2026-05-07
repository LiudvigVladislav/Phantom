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

## Scheduled — post-Alpha-2 security pass

These are the four remaining substantial items from the project's
running security findings list. They are scheduled rather than
shipped in the Alpha-2 release because the Council-revised release
plan deliberately kept the security workload to one item (F22) and
deferred the rest to the next release window — a five-item security
sprint inside one month was assessed as the wrong use of a single
maintainer's time, and the honest roadmap below is the explicit
trade.

### F8 — RatchetState plaintext in SQLite 🟦

**What.** The Double Ratchet state (root key, chain keys, message
counters, header keys, current and previous DH ratchets) is
serialized into a `BLOB` column on the `ratchet_state` table without
the same Keystore wrap that F11 and F22 already apply to identity
and prekey private bytes. A memory image of an unlocked device that
captures the SQLCipher decryption window can read the entire
ratchet state and decrypt every past-and-future message in every
active conversation.

**Why it sits behind F22 in the queue.** The OPK / SPK private
bytes (F22) are read on every X3DH bootstrap — every first-contact
ever made. The ratchet state is read on every message receive but
the *attack* against it requires both an unlocked-device memory
image AND a captured ratchet snapshot for the right conversation
window. The attacker surface is smaller, the implementation is
larger (every ratchet step persists; the wrap path has to be
hot-path friendly).

**Plan.** Reuse `KeystoreBlobCipher` from ADR-023 — the same
helper, the same alias family, no new crypto primitive. Add a
`RatchetState` codec analogous to `PrivateKeyStorageCodec` that
wraps the BLOB on persist and unwraps on load. Lazy migration via
the same "rewrite on next ratchet step" pattern. Estimated
1.5-2 working days end-to-end.

**Target window.** Post-Alpha-2 security pass (next release cycle).

### F19 + F20 — Call signalling not wrapped in Double Ratchet, no Sealed Sender 🟦

**What.** WebRTC call setup messages (SDP offers / answers, ICE
candidates) currently flow through the relay as their own envelope
type, outside the Double Ratchet that protects regular text
messages. They are TLS-encrypted on the wire, but the relay sees
who is calling whom (caller pubkey + callee pubkey, plus call IDs
that link the entire session). On a compromised relay, the call
metadata graph is fully visible.

**Why it sits behind F22.** Calls in PHANTOM are explicitly
labelled experimental in `KNOWN_ISSUES.md` ISSUE-014; the bigger
calls reliability work (PR 2.6, deferred until the underlying
transport is stable on aggressive-OEM devices) sits under it as a
prerequisite. Wrapping signalling in the ratchet is structurally
similar to wrapping any other message type — but doing it before
the calls subsystem is past its experimental tag mostly produces
churn rather than value.

**Plan.** Wrap SDP / ICE payloads inside the Double Ratchet message
type already used for regular 1:1 text. Add Sealed Sender envelope
at the same time so the relay sees only `to=<recipient>` for call
signalling (same posture every regular message type already has).
Estimated 3-4 working days.

**Target window.** Post-Alpha-2 security pass, after the calls
subsystem leaves experimental status.

### F2 + F13 — Dead-code SenderKey signing path 🟦

**What.** The Group Sender Keys subsystem carries a signing path
that is no longer reachable from any production code path
(superseded by an earlier refactor). It still allocates and stores
an Ed25519 keypair on every group entry, increasing the attack
surface (an extra long-lived key on disk that nothing reads) and
the maintenance surface (anyone reading the code wonders why it is
there).

**Why it sits in the queue.** Removal is not user-visible, so it
does not buy a new release headline. The cleanup is small but
needs migration code — existing group entries carry the dead key
material and the next-launch read path must handle the absent
column gracefully.

**Plan.** Implementation already drafted in
[ADR-017 (SenderKey signing removal)](../adr/ADR-017-senderkey-signing-removal.md);
this roadmap entry is the schedule, not the design. Estimated
2-3 working days including the migration test.

**Target window.** Post-Alpha-2, bundled with the group-chat
hardening pass.

---

## Acknowledged — Beta-tier hardening

These are recorded in the project's running findings list but are
neither scheduled nor blocking. They become real work after the
post-Alpha-2 security pass above lands.

| ID | What | Plan |
|----|------|------|
| F1 | Group control messages travel outside the Double Ratchet | Wrap SKD / leave / add control messages inside the ratchet. ~3 working days. Bundled with the broader group-chat hardening pass. |
| F3 | Group SenderKey KDF uses bare SHA-256 | Replace with HKDF-SHA256 with explicit domain separation. ~1 working day. Same group-chat pass. |
| F4 | Member-leave event does not rotate group keys | Implement full key rotation on `leave` and `remove`. ~2 working days. Same pass. |
| F11 + F26 | Shared relay token across all installs | Per-user signed challenge replaces the shared token. ~3 working days. Standalone PR. |
| P2 batch (F6, F7, F9, F10, F12 retry, F14, F18, F23, F25) | Lower-severity findings (logging, edge cases, validation tightening) | Cleanup pass during Beta polish. No standalone PR planned; rolled into routine work. |

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

*Last reviewed: 2026-05-08. Next review: when the post-Alpha-2
security pass kicks off.*
