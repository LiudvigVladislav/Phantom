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
   - **Success.** A single SQLDelight cross-table transaction (`SessionTransactionRepository.commitBootstrap`) reads the reservation row to confirm it still exists, then upserts the pending state via `PendingRatchetStateRepository.upsert` with the reservation's `reserved_at_ms`. The `local_one_time_pre_key` row and the `opk_reservation` row are **preserved** (the OPK is consumed only at `promotePendingToActive` per §"Inbound repair — `commitBootstrap → promotePendingToActive`" amendment below; the reservation is released at the same site). The DMS caller does NOT pre-write `saveSession(advancedState)` to `ratchet_state` before invoking `commitBootstrap`; the active row is only updated by the subsequent `promotePendingToActive` call. On the normal success path the inbound-repair flow chains `commitBootstrap → promotePendingToActive` atomically across the two calls and emits `inbound_repair_ok ... promotion=true`. If the reservation was released between phase 1 and phase 3 (e.g. an L7 cap eviction or L6 sweep raced), `commitBootstrap` returns `false`; the pending row is NOT written and the active row stays whatever it held pre-receive — typically the pre-bootstrap RESPONDER row. The DMS caller logs `DECRYPT_TRACE inbound_repair_commit_skip reason=reservation_released_between_phases`, still emits `inbound_repair_ok ... promotion=false reason=reservation_released_between_phases`, and returns the decrypted plaintext (Slice 4 lock — successful decrypt is never held). Recovery is peer-conditional and described in §"Honest consequence of removing the safety-net" below.
   - **Failure.** `OpkReservationRepository.release(opk_key_id_hex)` deletes ONLY the reservation row. `local_one_time_pre_key` row preserved; the OPK remains available for a future retry. The active `ratchet_state` row stays byte-identical (the DMS caller never wrote to active on the candidate-decrypt-fail branch; the inbound-repair flow returned `inbound_repair_decrypt_fail` and fell through to the hold path).

**Note on the Sprint 2b-B intermediate dual-write.** The Sprint 2b-B merge briefly carried a dual-write at the inbound-repair success path: the DMS caller wrote the advanced state to `ratchet_state` via `saveSession` BEFORE invoking `commitBootstrap`, so the active row preserved decrypt continuity while the pending row served as Sprint 2b-C's seed. Sprint 2b-C (this PR #317) removes that dual-write; the success path is now `commitBootstrap → promotePendingToActive` end-to-end as described above. The dual-write is documented here only so historical `2b-B-only` commits remain interpretable; the binding contract is the no-dual-write model.

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

## Amendment — Sprint 2b-C runtime wiring (2026-06-15)

Sprint 2b-B landed the storage half of the L3 / L4 / L5 / L6 / L7 contracts above + the in-place dual-write scaffolding that kept the inbound-repair flow functional while the runtime promotion path was not yet wired. Sprint 2b-C wires the runtime: outbound bootstraps land in pending only, outbound within `PENDING_TTL_MS` reuses pending, and inbound active MAC failure tries pending before falling through to inbound repair. The historical 2b-B intermediate is documented in the §L4 "Note on the Sprint 2b-B intermediate dual-write" above; the binding runtime model is the no-dual-write model described below.

### Outbound — bootstrap writes pending, not active

The DMS:434 outbound path's bootstrap branch (the branch that fires when no INITIATOR active row exists or the Sprint 2a guard redirects RESPONDER away) no longer calls `SessionManager.saveSession`. Instead it constructs a `phantom.core.messaging.BootstrapArtifacts(x3dhInit, recipientPubkeyHex)`, serialises it with `BootstrapArtifacts.toBlob(json)`, and writes the advanced RatchetState + the artifacts blob to `pending_ratchet_state` via the new `SessionTransactionRepository.commitInitiatorPending(conversationId, stateBlob, bootstrapArtifactsBlob, nowMs)` — a transactional cleanup-of-same-conv-stale-reservations + pending UPSERT (see the P1 paragraph below for the cleanup scope). `ratchet_state` is NOT touched on this path — the active row is replaced only at promotion time.

`commitInitiatorPending` is intentionally separated from `commitBootstrap` (the inbound-repair phase 3 method introduced in 2b-B). The two write into the same physical table but carry different ownership contracts:

- `commitBootstrap(opkKeyIdHex, ...)` requires a local `opk_reservation` row (the L4 phase 1 reservation that `recipientBootstrapInMemory` placed before deriving the candidate); it reads that row inside its transaction to share the `reserved_at_ms` timestamp with the pending row + verify the reservation still exists.
- `commitInitiatorPending(...)` takes NO `opkKeyIdHex`. The OUTBOUND-INITIATOR path references the PEER's `opk_key_id_hex` (carried in `x3dhInit`); that id refers to the PEER's local OPK pool, not ours. There is no local reservation to read and no local OPK row to consume; the operation is a pending-table upsert that additionally releases stale local reservations (see below).

**PR #317 review P1 (2026-06-16) — `commitInitiatorPending` stale-reservation cleanup.** Before the pending UPSERT, the operation releases ANY `opk_reservation` rows keyed by the `conversation_id`, in the same SQLDelight transaction. The scenario this closes is: a prior INBOUND bootstrap on the same conversation left both a pending row and a backing `opk_reservation(opk_X)` row; the inbound never promoted (peer never replied, L7 cap raced, processed-envelopes ledger gap); local side now initiates a fresh OUTBOUND bootstrap → `commitInitiatorPending` overwrites the pending row to OUTBOUND-INITIATOR but the reservation row survives. The next `promotePendingToActive` on this conversation reads `opk_reservation` by `conversation_id`, matches the orphan row, and silently deletes the unrelated local OPK row keyed by `opk_X` — corrupting our pool. The cleanup releases reservation rows ONLY (NOT `local_one_time_pre_key`, per the §L4 deferred-consume contract: reservation release returns the OPK to the pool). After the cleanup, the invariant "at most one `opk_reservation` per `conversation_id`, and any such row matches the pending row's bootstrap-backing OPK" holds — empty for outbound INITIATOR pending, one matching row for inbound RESPONDER pending. The storage test cell `commitInitiatorPending_releasesPriorInboundReservation_preservingLocalOpk_thenPromoteDoesNotDeleteOpk` pins the contract.

A `PendingSessionCapEnforcer.enforce()` call follows the outbound `commitInitiatorPending` for symmetry with the inbound `commitBootstrap` flow. Repeated outbound bootstraps to the same conversation upsert the same pending row (INSERT OR REPLACE), so the cap counts distinct conversations, not repeats; the enforcer is a no-op when count ≤ cap.

### Outbound reuse within `PENDING_TTL_MS`

`PENDING_TTL_MS = 10 * 60 * 1000` (10 minutes, Alpha-locked, retunable). The DMS:434 outbound path reads `pending_ratchet_state` BEFORE consulting `SessionManager.tryLoadSession`. A pending row is reusable when ALL of:

- `bootstrap_artifacts_blob` is non-null (only INITIATOR pending rows carry artifacts; RESPONDER pending rows written by `commitBootstrap` have null artifacts and are not reusable on the outbound path);
- the `BootstrapArtifacts.fromBlob(json, blob)` decode succeeds (tolerant — a null parse falls through to the next branch rather than crashing);
- `BootstrapArtifacts.recipientPubkeyHex == recipientPublicKeyHex` (defense-in-depth — same `conversation_id` should imply same recipient, but the explicit check guards against any future code path that recycles conversation ids);
- `(nowMs - reservedAtMs) < PENDING_TTL_MS`;
- `sessionSuspect` is false (a suspect conversation forces a fresh bootstrap regardless of TTL).

On reuse the encrypt path decodes the pending `state_blob` into `RatchetState`, runs `ratchet.encrypt(pendingState, plaintext)`, attaches the cached `x3dhInit` from the artifacts blob to the new `WireFrame`, writes the advanced pending state back via `commitInitiatorPending` with `nowMs = pendingEntity.reservedAtMs` (TTL anchor preserved across reuse — refreshing it on every send would defeat the 10-minute bound), and returns. No OPK is consumed.

When the pending row is expired (TTL crossed) the path falls through to the bootstrap branch above; the bootstrap branch's `commitInitiatorPending` overwrites the stale pending row verbatim under INSERT OR REPLACE semantics — no separate cleanup step is needed.

### Inbound — active MAC fail tries pending before repair

The DMS:2455 inbound flow gains a new fallback layer between the existing-session decrypt and the `inboundX3dhInit != null` repair branch. After the active decrypt throws an `IllegalArgumentException("MAC...")`, the catch branch reads `pendingRatchetStateRepository.get(conversationId)` and — if a pending row exists — tries `ratchet.decrypt(pendingState, encrypted)`. On success the path:

1. Upserts the advanced pending state into `pending_ratchet_state` via `pendingRatchetStateRepository.upsert(conv, advancedStateJson, pending.reservedAtMs, pending.bootstrapArtifactsBlob)`. The TTL anchor and artifacts blob are PRESERVED — even though the receive path doesn't read them, leaving them intact keeps outbound reuse semantics consistent across in-flight envelopes.
2. Calls `sessionTransactionRepository.promotePendingToActive(conv)`. Under the reservation-optional contract from §L4 the promotion succeeds whether the pending was INBOUND-RESPONDER (reservation present → OPK consumed at this site) or OUTBOUND-INITIATOR (no reservation → ratchet-state-only promotion; the artifacts blob's `opkKeyIdHex` refers to the peer's pool and MUST NOT delete a local row).
3. Logs `DECRYPT_TRACE pending_fallback_ok msgId=... promotion=true` and returns the decrypted plaintext through the normal `markProcessed → return@withLock` chain.

If pending decrypt also fails MAC, the catch branch logs `DECRYPT_TRACE pending_fallback_fail reason=mac_fail_under_pending` and falls through to the existing `inboundX3dhInit != null` repair branch. The pending row is left untouched (no spurious upsert).

The two-step `upsert(advancedState) → promote` is NOT one transaction. A crash between leaves pending updated with the post-decrypt state + no active update; the next inbound retries the fallback, which is idempotent at the `processed_envelopes` ledger boundary (`markProcessed` ran for the previous successful return). Acceptable degraded state vs. extending the storage contract with a single-tx `promotePendingToActiveWithAdvancedState(conv, stateBlob)` — the latter was considered and rejected as overfitting one runtime path while complicating the otherwise-narrow §L4 contract.

### Inbound repair — `commitBootstrap → promotePendingToActive` (dual-write removed)

The Sprint 2b-B dual-write at DMS:2569 success (`saveSession` to active + `commitBootstrap` to pending) is REPLACED with `commitBootstrap → promotePendingToActive`. The candidate's advanced state goes through pending; promotion atomically replaces the active row + deletes the pending row + consumes the OPK (the SOLE site where the OPK is permanently consumed under the §L4 deferred-consume contract).

**PR #317 review P1-2 (2026-06-15) — safety-net `saveSession` REMOVED from both `commit=false` and `promote=false` branches.** The pre-amendment text described a fallback that wrote the advanced chain into `ratchet_state` whenever `commitBootstrap` or `promotePendingToActive` returned false, so the active row could not "go missing". That fallback violated the §L4 deferred-consume invariant: it adopted a new chain into the active slot WITHOUT routing through the promote-time consume site, leaving the backing OPK in the local pool — republishable on the next bundle refresh and reusable by a different peer. The runtime now follows a stricter rule:

- **`commitBootstrap` returned false** (reservation released between L4 phase 1 and 3 — `reservation_released_between_phases` log) → log + return decrypted plaintext + do NOT update `ratchet_state`.
- **`promotePendingToActive` returned false** (pending evicted between commit and promote — `pending_evicted_between_commit_and_promote` log) → log + return decrypted plaintext + do NOT update `ratchet_state`.
- **`sessionTransactionRepository` is null** (legacy test fixtures without the Sprint 2b-C repos wired — runtime is configured with the SqlDelight-backed instance via `AppContainer`) → `sessionManager.saveSession(advancedState)` runs UNCHANGED. There is no atomic promote available in this fixture path, so the legacy direct-write is the only honest behaviour; production never enters this branch.

In every branch the path emits `DECRYPT_TRACE inbound_repair_ok msgId=... bootstrap=true ... promotion=(true|false reason=...)` and returns the decrypted plaintext. The Slice 4 false-branch rule is binding: **successful decrypt is never held; the log line carries the diagnostic verdict.**

**PR #317 review P2-B (2026-06-16) — branch matrix for the inbound-repair runtime.** The DMS:2569 inbound-repair branch has THREE distinct execution paths after the candidate decrypt succeeds; they share a `saveSession` call site but carry different OPK lifecycle semantics and emit different log reasons:

1. **4-DH production path (`opkKeyIdHex != null && sessionTransactionRepository != null`).** The normal Sprint 2b-C `commitBootstrap → promotePendingToActive` flow with deferred consume. Log reasons: `promotion=true` on success; `promotion=false reason=reservation_released_between_phases` on `commit=false`; `promotion=false reason=pending_evicted_between_commit_and_promote` on `commit=true promote=false`.
2. **3-DH production path (`opkKeyIdHex == null && sessionTransactionRepository != null`).** The peer's `x3dhInit` carried no `opkKeyIdHex` — the relay handed them a 3-DH bundle because our published prekey bundle had no OPKs available when they fetched it (server-contract pin #1 falls back to 3-DH X3DH when the OPK pool is depleted). No OPK to reserve, no OPK to consume — the deferred-consume protocol is moot. The path calls `saveSession(advancedState)` directly to active (no pending row, no promotion attempt). Log reason: `promotion=skipped reason=no_opk_in_x3dh_init`.
3. **Legacy fixture path (`sessionTransactionRepository == null`).** Production never enters this branch — `AppContainer` wires the SqlDelight-backed `SessionTransactionRepository` unconditionally. Tests with pre-2b-C fixtures fall into this branch. Log reason: `promotion=skipped reason=legacy_fixture`.

The distinct `promotion=skipped` log reasons matter for post-merge telemetry — without them, a 3-DH path would have logged `reason=reservation_released_between_phases` (the next branch's reason that fell through the bottom of the original `when {}`), implying a race that never happened. The branch-matrix is mirrored verbatim in the inline DMS comment above the runtime block.

**Honest consequence of removing the safety-net.** On 4-DH path `commit=false` or `promote=false` the active `ratchet_state` row stays stale by design (whatever it held before the inbound envelope arrived). What happens next depends on the peer:

- If the peer's NEXT outbound envelope to this conversation carries `x3dhInit` (Sprint 2a guard fires on its side — e.g. the peer also sees a role/state condition that forces a fresh bootstrap), the inbound-repair branch fires again with a fresh `reserve → commit → promote` cycle and the active row is replaced cleanly.
- If the peer treats its bootstrap as complete and sends a plain envelope WITHOUT `x3dhInit`, the local active row's MAC fails on every subsequent inbound → the envelope hits the existing `fail_mac action=hold` path. Recovery in that case waits for either side's Sprint 2a guard to fire on a future outbound and re-attach `x3dhInit`, or for an operator-initiated re-pair. The Sprint 2b-C runtime does NOT add a "stuck active" detector — the residual hold rate is the observable signal, and the `opk_not_found_total{reason}` metric stub (§ below) is the post-merge correlation hook.

The tradeoff is intentional: a stale active that holds is recoverable (peer re-bootstrap OR re-pair); a silently-adopted new active over an un-consumed OPK is a stealthy crypto-invariant breach. The deferred-consume rule chooses the recoverable failure mode.

### `opk_not_found_total{reason}` metric stub (L8 debuggability seam)

Sprint 2b-C ships a minimal in-memory counter wired into the recipient-side `recipientBootstrapInMemory` failure paths so post-merge log telemetry can correlate `OpkNotFound` rates against the pending-fallback + repair flows. The stub does NOT carry separate telemetry infrastructure — it exists as an observability seam only; the deferred sender-signal work named in §"Alternative C" will consume this counter when its entry criteria are met. Wiring beyond the in-memory counter (export to logcat / Statsig / Grafana) is explicitly out of Sprint 2b-C scope.

### Test coverage trail

| Cell | Layer | Pins |
| --- | --- | --- |
| Storage `promotePendingToActive_atomicallyConsumesOpkAndPromotesState` | storage | §L4 INBOUND case (reservation present → OPK consumed) |
| Storage `promotePendingToActive_returnsFalse_whenNoPendingRow` | storage | idempotent re-call |
| Storage `promotePendingToActive_promotesWithoutOpkDelete_whenNoReservation` | storage | §L4 OUTBOUND-INITIATOR case (no reservation → local OPK row preserved) |
| Storage `commitInitiatorPending_writesPendingRow_doesNotTouchActiveOpkOrReservation` | storage | outbound pending UPSERT leaves UNRELATED active / OPK / reservation rows untouched (PR #317 Round 3 narrowed: same-conv stale reservations ARE released — see next row) |
| Storage `commitInitiatorPending_releasesPriorInboundReservation_preservingLocalOpk_thenPromoteDoesNotDeleteOpk` | storage | PR #317 Round 3 P1 — transactional cleanup of same-conv stale `opk_reservation` rows so a subsequent `promotePendingToActive` does not silently delete an unrelated local OPK |
| Storage `commitInitiatorPending_overwritesExistingExpiredRow` | storage | INSERT OR REPLACE behaviour for expired pending |
| Messaging `bootstrapArtifacts_jsonRoundTrip_*` + null/malformed | messaging | typed BootstrapArtifacts JSON contract |
| DMS `encryptUnderLock_withinPendingTtl_reusesCachedX3dhInit_noBundleFetch_noOpkChange` | runtime | outbound reuse path |
| DMS `encryptUnderLock_afterPendingTtl_runsFreshBootstrap_andOverwritesPending` | runtime | TTL expiry → fresh bootstrap |
| DMS `handleDeliver_firstInboundOnPendingChain_promotesPendingToActive` | runtime | pending fallback success + two-step contract |
| DMS `handleDeliver_pendingFallbackPromoteRaceLost_returnsPlaintext_andLogsPromotionFalse` | runtime | promotion false-branch — plaintext returned, no hold |

The M-OPK-3 Wi-Fi field gate ships as an `androidInstrumentedTest` harness that requires a connected device or emulator + relay deploy + APK install + adb pairing. It is NOT executed by the local sweep; it is a manual acceptance gate per scope-doc L10. Source-only landing in 2b-C; the harness itself does not produce a deterministic PASS/FAIL until run against the device matrix.

## References

- `docs/tracks/sprint-2b-opk-pending-session-scope.md` — binding scope-doc (PR #314 squash `cfa765d2`).
- `docs/adr/ADR-009-identity-prekey-separation.md` — Sprint 2b L1 amendment + the wider identity / SPK / OPK separation context.
- `docs/PROJECT_LOG.md` 2026-06-15 entry — integration LTE smoke evidence + Sprint 2b scope-lock.
- Sprint 2b-A merge commit `30617121` "feat(crypto): re-snapshot prekey publish body per retry (#315)" — the L1 publish-snapshot consistency closure that triggered this ADR's broader OPK-lifecycle scope.
- Council durables: `C:\temp\sprint-2b-{reconnaissance,council-layer1,council-layer1-round2}-2026-06-15\`.
