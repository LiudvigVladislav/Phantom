# ADR-029: Pending / Active Session State Machine + OPK Reserve Protocol

**Status:** Proposed (Sprint 2b-B lands the storage half; Sprint 2b-C lands the runtime promotion half).
**Date:** 2026-06-15
**Author:** Vladislav Liudvig
**Reviewers:** Sprint 2b Council Layer 1 + Layer 2 (2026-06-15)

## Context

Sprint 2b-A (PR #315, master `30617121`) closed the publish-snapshot consistency gap behind the 2026-06-15 integration LTE smoke `errorClass=OpkNotFound action=hold` shape by re-snapshotting the prekey publish body on every retry attempt (L1 factory-lambda).

That fix closed the OBSERVED root cause but did not address the broader OPK lifecycle / consume / idempotency / restart-resilience mandate from the 2026-06-15 PR #313 lock. Sprint 2b-B + 2b-C close it. This ADR locks the protocol the two PRs implement.

### Pre-Sprint-2b lifecycle

`SessionManager.recipientBootstrapInMemory` (and its wrapper `recipientBootstrap`) consumed the referenced One-Time PreKey **eagerly** — `oneTimePreKeyRepository.deleteByKeyId(opkId)` ran BEFORE the X3DH 4-DH derivation, on every inbound bootstrap path. The justification recorded in the method's KDoc was "safe because we hold the only async reference; the pool is per-device, not concurrent."

That mental model was wrong. The publish-with-retry overlay (PR-R0 / PR-R0.1) made the relay-side bundle state externally observable through the wire body, and the publish retry loop + inbound bootstrap path raced on the OPK pool's externally-observable state via the (stale, pre-Sprint-2b-A) publish snapshot. Two failure modes followed:

1. **Field smoke (2026-06-15) — the publish-snapshot race.** A retry succeeded server-side AFTER a local OPK consume; the relay's atomic replace-wholesale publish semantics (server-contract pin #1 — `services/relay/src/prekeys.rs:273-276`) restored the relay-side pool to the stale 40-OPK snapshot that included the just-consumed OPK; a peer fetched that consumed OPK; the peer's `x3dhInit` referenced it; the local `oneTimePreKeyRepository.get(opkKeyIdHex)` returned `null`. **Closed by Sprint 2b-A L1.**

2. **The mid-derive crash carcass.** A process kill between `deleteByKeyId(opkId)` and X3DH derivation left the local OPK row gone but no session derived. The next inbound envelope referencing the same `opk_key_id_hex` produced `OpkNotFound` and held forever (debug builds) or destructively ack'd (release builds). Not seen in field smokes but a real reliability gap. **Closed by Sprint 2b-B L4.**

## Decision

### State machine

Three slots per conversation, each backed by its own SQLDelight table:

```
                    bootstrap +
                    candidate-decrypt OK         first inbound on
                                                 pending chain
   no slot  -----------------------------> pending -------------> active
              (L4 phase 3 success)                   (Sprint 2b-C
                                                      promotion —
                                                      M-2bC-1 / M-2bC-4)

   no slot  <----------------------------- pending
              (L4 phase 3 failure       (cap-8 LRU eviction
               OR L6 startup sweep)      OR pending-TTL expiry)
```

- **`ratchet_state`** — the existing active slot. One row per conversation. Read on every outbound encrypt and every inbound decrypt.
- **`pending_ratchet_state`** — NEW (Sprint 2b-B L3). One row per conversation. Holds candidate Double Ratchet state derived by an inbound bootstrap awaiting confirmation. The Sprint 2a outbound role guard at `DefaultMessagingService.kt:434` MUST NEVER read this table (M-2bB-2 pins the isolation invariant); the boundary is mechanical (different repository, different table).
- **`opk_reservation`** — NEW (Sprint 2b-B L5). Pins an OPK in flight for a derivation. Keyed on `opk_key_id_hex` (the same hex the inbound `X3dhInitHeader.opkKeyIdHex` carries) so the L4 phase 3 success / failure / L7 eviction paths can release the row without an extra lookup.

Sprint 2b-B lands all three tables; Sprint 2b-C wires the pending → active promotion runtime so first-successful-inbound-under-the-pending-chain triggers atomic promotion + OPK consume.

### L4 — Two-phase OPK consume protocol

The eager delete at `SessionManager.recipientBootstrapInMemory:357` is REPLACED with a three-phase protocol where OPK consumption is **DEFERRED to pending → active promotion**.

1. **Reserve.** Insert `opk_reservation(opk_key_id_hex, envelope_id, conversation_id, reserved_at_ms)` BEFORE the X3DH 4-DH derivation. `INSERT OR IGNORE` semantics: a pre-existing row with the same `opk_key_id_hex` (e.g. a crash-recovered carcass that the L6 sweep had not yet picked up) causes the insert to no-op; the caller treats this as an idempotent retry (`ReservationOutcome.AlreadyReserved` returns the existing row for log correlation).

2. **Derive + decrypt.** The existing X3DH 4-DH handshake + candidate-decrypt runs unchanged.

3. **Commit-or-rollback.**
   - **Success.** A single SQLDelight cross-table transaction (`SessionTransactionRepository.commitBootstrap`) reads the reservation row to confirm it still exists, then upserts the pending state via `PendingRatchetStateRepository.upsert` with the reservation's `reserved_at_ms`. The `local_one_time_pre_key` row and the `opk_reservation` row are **preserved**. If the reservation was released between phase 1 and phase 3 (e.g. an L7 cap eviction or L6 sweep raced), `commitBootstrap` returns `false`; the pending row is NOT written. The DMS caller has already written the advanced state to `ratchet_state` via `saveSession` BEFORE invoking `commitBootstrap` (dual-write — see below), so a `false` here just means Sprint 2b-C will not find a pending row for this conversation until the next inbound envelope re-derives. The DMS caller does not roll the active write back; the inbound-repair flow still emits `inbound_repair_ok` and returns the decrypted plaintext. The active `ratchet_state` row holds whatever `saveSession` left in it.
   - **Failure.** `OpkReservationRepository.release(opk_key_id_hex)` deletes ONLY the reservation row. `local_one_time_pre_key` row preserved; the OPK remains available for a future retry. The active `ratchet_state` row stays byte-identical (the DMS caller's `saveSession` runs only on candidate-decrypt success; the failure branch never reaches that point).

**Dual-write rationale (Sprint 2b-B only).** Under Sprint 2b-B the DMS:2569 success path writes the advanced state to BOTH the active `ratchet_state` row (via `saveSession`, for decrypt continuity until Sprint 2b-C wires the promotion path) AND the pending `pending_ratchet_state` row (via `commitBootstrap`, for Sprint 2b-C to consume at promotion). The pending row is a seed; the active row remains the source of truth for outbound + inbound decisions in Sprint 2b-B. Sprint 2b-C will remove the active write at this site, replacing it with promotion-on-first-inbound-under-pending-chain. M-2bB-5 pins the pending-write contract; the dual-write to active is verified indirectly by the existing wire-flow tests that continue to decrypt subsequent envelopes against `ratchet_state`.

**P1-1 conflict variant (PR #316 review — 2026-06-15).** `SessionBootstrapException.OpkReservationConflict` fires at L4 phase 1 when an `INSERT OR IGNORE` returns an existing row with a DIFFERENT `(conversation_id, envelope_id)` pair than this caller's. Proceeding into the X3DH derive in that case would let this caller upsert a pending row pointing to a reservation owned by another conversation; the L6 join + L7 cap LRU accounting would both silently corrupt. The DMS failure branch does NOT release on `OpkReservationConflict` — the reservation belongs to the other in-flight derivation; releasing it would compromise that flow.

**OPK consumption.** Sprint 2b-C wires the pending → active promotion path; that path is the SOLE site where the `local_one_time_pre_key` row and the `opk_reservation` row are deleted, atomically alongside the active `ratchet_state` replace and the pending row delete. Only at promotion is the OPK irreversibly consumed.

This deferred-consume model is the reconciliation of the L4 success path with the L7 eviction path: an evicted pending candidate's reservation is still in place (L4 success kept it), so eviction can release the reservation cleanly and the OPK row survives. Pre-amendment L4 (consume at decrypt success) made L7's "rollback" physically impossible because the OPK was already gone.

### L6 — Reservation TTL + pending-coordinated startup sweep

Reservations carry a 5-minute TTL — well above the expected derivation latency (< 1 s) and well below any user-facing reconnect cadence. On process startup, `AppContainer.initMessagingFromStorage` calls `OpkReservationRepository.sweepOrphanReservations(thresholdMs = now - 5 * 60 * 1000)` BEFORE constructing `DefaultMessagingService` (so no inbound handler can fire before the sweep finishes).

The sweep uses **join-based** semantics:

> For each `opk_reservation` row where `reserved_at_ms < thresholdMs`, check whether a `pending_ratchet_state` row exists with the same `conversation_id`. If NO matching pending row exists, the reservation is an orphan (a mid-derive crash carcass — state (a)) and is DELETED. If a matching pending row exists, the reservation is part of a live pending candidate (state (b)) and is SKIPPED regardless of age — eviction of stale pending candidates is the L7 cap-8 LRU's responsibility, not this sweep's.

The pre-amendment L6 used a blunt `deleteStaleOlderThan(...)` that did not distinguish the two states. Under the OLD L4 (consume at decrypt success) every alive reservation was guaranteed mid-derive, so the blunt sweep was safe. Under the amended L4, the blunt sweep would orphan pending rows whose reservations got swept and could leave the local pool out of sync with what the in-memory pending state expects. The join semantics close this gap.

### L7 — `PENDING_SESSION_CANDIDATE_CAP = 8` with LRU eviction (defense-in-depth)

The number of in-flight pending session candidates per device is capped at `PENDING_SESSION_CANDIDATE_CAP = 8`. After each successful `commitBootstrap`, `PendingSessionCapEnforcer.enforce()` checks the count; on overflow, the OLDEST pending candidate (by `reserved_at_ms`) is evicted. Each eviction transactionally:

- Resolves the `opk_reservation` row backing the evicted pending via `OpkReservationRepository.getByConversationId`.
- Releases the reservation (`local_one_time_pre_key` preserved — the OPK returns to the pool).
- Deletes the `pending_ratchet_state` row.

The cap is a **defense-in-depth** mitigation on top of the server-side rate limits (server-contract pins #2 / #3 / #4): publish 10/hour, fetch 60/min, WS message 60/min. The relay's existing rate limits already bound the realistic attack rate; this client cap bounds local memory + storage growth from a peer that happens to spam pending bootstraps within those rate envelopes.

The default `8` is an Alpha-locked design choice, NOT a derived constant. It is retunable post-merge based on field telemetry. The lock is on the existence of the cap, not on the specific value.

### Schema (Sprint 2b-B migration `21.sqm`)

```sql
CREATE TABLE opk_reservation (
    opk_key_id_hex  TEXT    NOT NULL PRIMARY KEY,
    envelope_id     TEXT    NOT NULL,
    conversation_id TEXT    NOT NULL,
    reserved_at_ms  INTEGER NOT NULL
);
CREATE INDEX idx_opk_reservation_conversation_id ON opk_reservation(conversation_id);
CREATE INDEX idx_opk_reservation_reserved_at_ms ON opk_reservation(reserved_at_ms);

CREATE TABLE pending_ratchet_state (
    conversation_id          TEXT    NOT NULL PRIMARY KEY,
    state_blob               TEXT    NOT NULL,
    reserved_at_ms           INTEGER NOT NULL,
    bootstrap_artifacts_blob TEXT
);
CREATE INDEX idx_pending_ratchet_state_reserved_at_ms ON pending_ratchet_state(reserved_at_ms);
```

- `pending_ratchet_state.state_blob` reuses the active `ratchet_state` codec (`RatchetStateStorageCodec` — `rs1:` + Base64 envelope over an Android-Keystore-wrapped JSON blob) AND the same Keystore alias (`phantom_ratchet_wrap_v1`). Identical threat model; no benefit from a separate alias at Alpha.
- `pending_ratchet_state.bootstrap_artifacts_blob` is the serialized `BootstrapArtifacts` JSON Sprint 2b-C uses for OUTBOUND reuse of an INITIATOR pending slot (the architect L-ARCH-3 contract — outbound send within PENDING_TTL reuses the cached `x3dhInit` instead of consuming a second OPK). The blob is opaque to the storage layer; messaging owns the typed `BootstrapArtifacts` class + the JSON serialization. Sprint 2b-B production callers pass NULL — Sprint 2b-C introduces non-NULL writes for INITIATOR pending rows.

### Repository contracts

```kotlin
interface OpkReservationRepository {
    suspend fun reserve(opkKeyIdHex, envelopeId, conversationId, nowMs): ReservationOutcome
    suspend fun release(opkKeyIdHex)
    suspend fun get(opkKeyIdHex): OpkReservation?
    suspend fun getByConversationId(conversationId): OpkReservation?   // L7 enforcer
    suspend fun getAll(): List<OpkReservation>
    suspend fun count(): Int
    suspend fun sweepOrphanReservations(thresholdMs): Int               // L6 join sweep
    suspend fun deleteAll()
}

interface PendingRatchetStateRepository {
    suspend fun get(conversationId): PendingRatchetStateEntity?
    suspend fun upsert(conversationId, stateBlob, reservedAtMs, bootstrapArtifactsBlob: String? = null)
    suspend fun delete(conversationId)
    suspend fun getAll(): List<PendingRatchetStateEntity>
    suspend fun count(): Int
    suspend fun getOldestConversationId(): OldestPendingPointer?         // L7 LRU
    suspend fun deleteAll()
}

interface SessionTransactionRepository {
    suspend fun commitBootstrap(opkKeyIdHex, conversationId, stateBlob, bootstrapArtifactsBlob: String? = null): Boolean
}
```

Sprint 2b-C extends `SessionTransactionRepository` with `promotePendingToActive(conversationId)` — the SOLE atomic cross-table site that consumes the OPK.

## Migration

Schema-only on Sprint 2b-B. No runtime behaviour change for installations that never enter the inbound-repair branch.

For installations whose last run wrote a session via the legacy eager-consume path, the active `ratchet_state` row is intact; the `local_one_time_pre_key` row has been deleted (legacy behaviour); no `opk_reservation` row exists. The Sprint 2b-B migration just adds the two new tables (no row movement). The Sprint 2b-C runtime change is forward-compatible: a conversation with no pending row + an active row simply uses the active row, as today.

A device mid-ratchet across the Sprint 2b-B schema migration (Round 2 BS-R2-3 blind spot) sees no pending row for an active session. That's exactly the steady-state shape Sprint 2b-C reads, so the absence is correct — no spurious re-key fires.

## Consequences

### Positive

- Closes the mid-derive crash carcass class of failure (the second pre-Sprint-2b lifecycle issue above).
- Decouples OPK consumption from candidate-decrypt success, enabling the Sprint 2b-C pending → active promotion path that retries gracefully on stale relay-restored OPKs.
- Defense-in-depth cap on local storage / memory growth from pending-bootstrap-spam (L7).
- Repository boundary at `PendingRatchetStateRepository` keeps the Sprint 2a outbound role guard mechanically isolated from pending state (M-2bB-1 + M-2bB-2 pin).

### Negative

- Two new tables in the SQLCipher-protected `phantom.db`. Storage cost: ~64 bytes per reservation + ~600 bytes per pending row (wrapped state_blob).
- One extra SQL transaction per inbound-repair branch (`commitBootstrap`). Sub-millisecond cost on modern Android storage; negligible compared to the X3DH 4-DH primitive.
- The L6 join sweep adds a small startup cost (one read on `opk_reservation` + one indexed read on `pending_ratchet_state` per orphan candidate). The threshold is 5 minutes, so the steady-state row count is bounded by recent crash-derive activity (typically zero).

### Neutral

- Reuses the existing Keystore alias `phantom_ratchet_wrap_v1` per L3 lock. No new alias to provision; no new threat-model branch.
- Sprint 2b-C will add `promotePendingToActive` + the `BootstrapArtifacts` typed class for INITIATOR pending reuse. Schema accommodates both without re-migration.

## Alternatives considered

### Alternative A — `pending_state` column on `ratchet_state`

Round 1 kmp-builder's proposal. Rejected at Round 2 architect lock: a column on the active row would force the Sprint 2a outbound role guard read path (currently `RatchetStateRepository.getRatchetState(conv)`) to discriminate active-vs-pending data inside the same row, multiplying read sites that need to know about the pending state and creating a clear surface for a future bug to leak pending data into outbound decisions. Companion table makes the isolation MECHANICAL.

### Alternative B — Eager OPK delete at decrypt success (pre-amendment L4)

The shape Round 1 architect proposed. Rejected at PR #314 review when the L7 cap-eviction contract was added: under eager consume, evicting a pending candidate would face "rollback to reserve" with the reservation already gone and the OPK row already gone — physically impossible. Defer-to-promotion model (the amended L4) makes L7 + L6 internally consistent.

### Alternative C — Sender signal envelope ("re-bootstrap, your OPK was unknown")

Considered at Round 1 + Round 2. Rejected (Round 2 verdict 3:1 against). Sprint 2b adds a recipient-side `opk_not_found_total{reason}` metric in 2b-C for debuggability ONLY; the sender signal is deferred to Sprint 2c / Sprint 3 behind three explicit entry criteria (signed under recipient Ed25519 + nonce-bound to `(envelope_id, opk_key_id_hex)` + Ghost-mode 30s+jitter delay).

## Implementation notes

- **Sprint 2b-B (this PR)** lands schema + repositories + SessionManager + DefaultMessagingService + AppContainer + KDoc rewrite. 8 commonTest cells M-2bB-1..8 + one androidInstrumentedTest cell for the schema migration.
- **Sprint 2b-C** lands `promotePendingToActive` + outbound INITIATOR pending reuse + the M-OPK-3 Wi-Fi field gate. Per L10 the gate sequence is: 2b-A merged → 2b-B merged (OPK lifecycle foundation) → 2b-C merged + M-OPK-3 PASS (Sprint 2b complete) → Stage 2B-D Tele2 LTE integration smoke PASS (PR #310 ready).

## References

- `docs/tracks/sprint-2b-opk-pending-session-scope.md` — binding scope-doc (PR #314 squash `cfa765d2`).
- `docs/adr/ADR-009-identity-prekey-separation.md` — Sprint 2b L1 amendment + the wider identity / SPK / OPK separation context.
- `docs/PROJECT_LOG.md` 2026-06-15 entry — integration LTE smoke evidence + Sprint 2b scope-lock.
- Sprint 2b-A merge commit `30617121` "feat(crypto): re-snapshot prekey publish body per retry (#315)" — the L1 publish-snapshot consistency closure that triggered this ADR's broader OPK-lifecycle scope.
- Council durables: `C:\temp\sprint-2b-{reconnaissance,council-layer1,council-layer1-round2}-2026-06-15\`.
