// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Cross-table atomic operations on the session lifecycle tables —
 * Sprint 2b-B L4 (commit-bootstrap + cap-eviction) + Sprint 2b-C
 * (pending->active promotion).
 *
 * Each method runs as a single SQLDelight transaction so a process
 * kill mid-operation cannot leave the active / pending / reservation /
 * local OPK rows out of sync.
 *
 * Sprint 2b-B introduced [commitBootstrap] + [evictPendingCandidate].
 * Sprint 2b-C adds [promotePendingToActive] alongside the runtime
 * wiring (M-2bC-1 + M-2bC-4 cells). Promotion is the SOLE atomic
 * site where the OPK is permanently consumed.
 *
 * See [`docs/tracks/sprint-2b-opk-pending-session-scope.md`](../../../../../../docs/tracks/sprint-2b-opk-pending-session-scope.md)
 * L4 + ADR-029 for the full state machine.
 */
interface SessionTransactionRepository {

    /**
     * L4 phase 3 success — commit the candidate bootstrap into the
     * pending slot.
     *
     * Atomically:
     *  - READ the `opk_reservation` row keyed by [opkKeyIdHex]; if
     *    no row exists (the reservation was released by an L7 cap
     *    eviction or L6 sweep between phase 1 and phase 3), return
     *    `false` and DO NOT write the pending row.
     *  - UPSERT the `pending_ratchet_state` row for [conversationId]
     *    with the reservation's `reserved_at_ms` (so pending and
     *    reservation share a single timestamp — a precondition for
     *    the L7 LRU eviction ordering to be unambiguous).
     *
     * Does NOT delete the `opk_reservation` row. Does NOT touch the
     * `local_one_time_pre_key` row. Does NOT read or write the active
     * `ratchet_state` row. The amended L4 contract defers OPK
     * consumption to Sprint 2b-C pending->active promotion; at the
     * success of this call the OPK is still in the pool.
     *
     * [stateBlob] is plaintext JSON; implementations wrap it through
     * [RatchetStateStorageCodec] before persisting (same envelope as
     * the active `ratchet_state` table, reusing the same Keystore
     * alias per the L3 lock).
     *
     * [bootstrapArtifactsBlob] is the opaque messaging-layer JSON
     * (the Sprint 2b-C `BootstrapArtifacts` typed class). Sprint 2b-B
     * production callers pass NULL; Sprint 2b-C introduces non-NULL
     * writes for INITIATOR pending rows.
     *
     * **Active row interaction (PR #317 — 2026-06-16).** This method
     * does NOT touch `ratchet_state` on either branch. Under
     * Sprint 2b-C the DMS:2569 inbound-repair caller no longer
     * pre-writes `saveSession(advancedState)` to active before
     * invoking `commitBootstrap` (the Sprint 2b-B dual-write was
     * removed in Slice 4 + further hardened by PR #317 review P1-2 —
     * see ADR-029 §"Inbound repair — `commitBootstrap →
     * promotePendingToActive` (dual-write removed)"). The Sprint 2b-C
     * runtime treats `commitBootstrap` + `promotePendingToActive`
     * as the SOLE path that updates the active row from an inbound
     * bootstrap.
     *
     * A `false` return here therefore leaves the active `ratchet_state`
     * row stale by design — whatever it held pre-receive (typically
     * the pre-bootstrap RESPONDER row). The DMS caller logs
     * `DECRYPT_TRACE inbound_repair_commit_skip
     * reason=reservation_released_between_phases` and still emits
     * `inbound_repair_ok ... promotion=false reason=...` so the
     * decrypted plaintext is returned to the user (Slice 4 lock —
     * successful decrypt is never held). Recovery is peer-conditional:
     * the next inbound envelope under the new chain re-fires this
     * branch only if the peer attaches `x3dhInit` again; without
     * `x3dhInit` the active row MAC-fails on every subsequent inbound
     * → `fail_mac action=hold` until either side's Sprint 2a guard
     * re-fires or the user re-pairs.
     *
     * **PR #317 review P1-1 (2026-06-15) cleanup.** Before the
     * pending UPSERT, implementations MUST release any prior
     * `opk_reservation` rows for the same `conversationId` whose
     * `opk_key_id_hex` differs from this caller's. Such priors
     * become orphan the moment the pending row is overwritten — the
     * L6 sweep would not pick them up because the pending companion
     * still matches. After the cleanup the invariant "at most one
     * reservation per (conversation_id) AND that row's
     * `opk_key_id_hex` matches the pending row's bootstrap-backing
     * OPK" holds for both `promotePendingToActive` and
     * `evictPendingCandidate` lookups.
     *
     * @return true if the reservation was present and the pending
     *         row was upserted; false if the reservation was missing.
     *         Cell M-2bB-5 pins the success path; the race branch
     *         test `commitBootstrap_returnsFalse_whenReservationReleasedBetweenPhases`
     *         covers the false return.
     */
    suspend fun commitBootstrap(
        opkKeyIdHex: String,
        conversationId: String,
        stateBlob: String,
        bootstrapArtifactsBlob: String? = null,
    ): Boolean

    /**
     * L7 cap-8 eviction — atomic cross-table deletion. PR #316 review
     * P1-2 (2026-06-15): the pre-fix `PendingSessionCapEnforcer.enforce`
     * issued the reservation release and the pending row delete as
     * two independent repository calls, so a process kill between
     * them could leave a pending row whose `opk_reservation`
     * companion was already released. The L6 sweep would skip such
     * a row (its join semantics see the pending row and treat the
     * reservation as live), the L7 enforcer would count the row
     * against the cap, and the Sprint 2b-C promotion would later
     * fail to consume the matching OPK / reservation atomically. The
     * row would sit there forever.
     *
     * This method runs both deletes in a single SQLDelight
     * transaction so a crash mid-eviction leaves either both rows in
     * place (caller retries on the next commitBootstrap) or both
     * rows gone (the OPK row is preserved by the L4 contract — eager
     * delete would have happened only at Sprint 2b-C promotion,
     * which evicted candidates never reach).
     *
     * The implementation:
     *   1. Looks up the `opk_reservation` row by `conversation_id`
     *      (uses the [OpkReservationRepository.getByConversationId]
     *      query in production; the index
     *      `idx_opk_reservation_conversation_id` backs the lookup).
     *   2. If a reservation exists, releases it (deletes the
     *      `opk_reservation` row only — the `local_one_time_pre_key`
     *      row is preserved per the L4 deferred-consume contract).
     *   3. Deletes the `pending_ratchet_state` row.
     *
     * Idempotent: a re-call after a successful eviction is a no-op
     * (no reservation row, no pending row). Cell M-2bB-6 + M-2bB-7
     * + a new M-2bB-7-crash test pin this atomicity contract.
     */
    suspend fun evictPendingCandidate(conversationId: String)

    /**
     * Sprint 2b-C L4 promotion — atomic pending->active transition.
     *
     * The SOLE site in the protocol where the OPK is permanently
     * consumed (when the pending was created by an INBOUND
     * `recipientBootstrapInMemory` derivation that placed a
     * reservation; see "Reservation-optional" below for the
     * outbound-initiator case).
     *
     * Atomically inside a single SQLDelight transaction:
     *  1. READ `pending_ratchet_state` row by [conversationId]. If
     *     null, return `false` and DO NOT touch any other row.
     *  2. UPSERT `ratchet_state` for [conversationId] with the
     *     pending row's `state_blob` copied verbatim (both tables
     *     share the same `rs1:` + Base64 + Keystore-wrap envelope per
     *     the L3 alias-reuse lock — no decode/re-encode needed).
     *  3. DELETE the `pending_ratchet_state` row.
     *  4. READ `opk_reservation` row by [conversationId]:
     *      - If a reservation exists (the INBOUND case — the
     *        pending was created by `recipientBootstrapInMemory`
     *        which placed a reservation in L4 phase 1): DELETE the
     *        `local_one_time_pre_key` row by `opk_key_id_hex` AND
     *        DELETE the `opk_reservation` row. This is where the OPK
     *        is permanently consumed.
     *      - If NO reservation exists (the OUTBOUND-INITIATOR case
     *        — the pending was created by `initiatorBootstrap` which
     *        references the PEER's OPK by id and has no local OPK
     *        to delete): the promotion runs ratchet-state-only with
     *        ZERO touches to `local_one_time_pre_key` /
     *        `opk_reservation`. **Critical: do NOT
     *        `oneTimePreKeyRepository.deleteByKeyId(opkKeyId)` from
     *        the pending row's bootstrap_artifacts_blob — that
     *        opkKeyId points at the peer's pool, not ours.**
     *  5. Return `true`.
     *
     * **Reservation-optional shape (PR #316/#317 review lock —
     * 2026-06-15).** The earlier draft tied promotion to a
     * mandatory reservation. The outbound-initiator path has no
     * local reservation (the `x3dhInit.opkKeyIdHex` field refers
     * to the recipient's local pool, not the sender's). Tying
     * promotion to a mandatory reservation would either crash the
     * outbound promotion or — worse — silently delete a local OPK
     * row by the wrong id. The reservation-optional shape covers
     * both the inbound case (consume on promote) and the outbound
     * case (promote ratchet only).
     *
     * Idempotency: a second call after a successful promotion is a
     * no-op (no pending row, returns false). Cell M-2bC-4 pins the
     * RESPONDER-with-reservation path; a companion cell pins the
     * INITIATOR-without-reservation path.
     *
     * @return true if a pending row existed and was promoted;
     *         false otherwise (idempotent re-call yields false).
     */
    suspend fun promotePendingToActive(conversationId: String): Boolean

    /**
     * Sprint 2b-C — OUTBOUND-INITIATOR pending commit.
     *
     * Transactional cleanup of same-conv stale `opk_reservation`
     * rows + UPSERT `pending_ratchet_state` for [conversationId]
     * with the supplied [stateBlob] (the advanced RatchetState the
     * outbound encrypt produced) and the [bootstrapArtifactsBlob]
     * (the [phantom.core.messaging.BootstrapArtifacts] JSON
     * carrying the cached `X3dhInitHeader` + recipient identity hex
     * for subsequent outbound reuse within `PENDING_TTL_MS`). The
     * UPSERT uses INSERT OR REPLACE semantics, so an expired
     * pending row is overwritten verbatim by the new bootstrap.
     *
     * **Does NOT touch:**
     *  - `ratchet_state` — the outbound bootstrap no longer writes
     *    to the active slot. The peer's reply (the first inbound
     *    under the new chain) triggers promotion via
     *    [promotePendingToActive], at which point the pending
     *    state moves to active.
     *  - `local_one_time_pre_key` — the outbound-initiator path
     *    references the PEER's OPK pool by id; we have no local
     *    OPK to delete here. The peer-pool OPK is the relay's
     *    concern (consumed at bundle-fetch time per server-contract
     *    pin #1).
     *
     * **DOES release stale `opk_reservation` rows for the conversation
     * (PR #317 review P1 — 2026-06-16).** Outbound bootstrap itself
     * places no reservation, but a PRIOR inbound bootstrap on the
     * same conversation may have left a reservation row alive (its
     * pending companion is about to be overwritten by this call).
     * Implementations MUST release any `opk_reservation` rows keyed
     * by [conversationId] inside the same transaction as the pending
     * UPSERT, deleting the reservation row only (NOT the
     * `local_one_time_pre_key` row — the L4 deferred-consume contract
     * keeps the OPK in the pool). Without this cleanup, the orphan
     * reservation would survive until a future
     * [promotePendingToActive] read it by conversation_id and
     * deleted an unrelated local OPK row by the inbound-bootstrap's
     * `opk_key_id_hex`. After the cleanup the invariant "at most one
     * reservation per conversation_id, and any such row matches the
     * pending row's bootstrap-backing OPK" holds — empty for
     * outbound INITIATOR pending, one matching row for inbound
     * RESPONDER pending.
     *
     * [bootstrapArtifactsBlob] is REQUIRED non-null on this path —
     * the outbound-reuse path keys on its presence to discriminate
     * INITIATOR pending rows from the RESPONDER (inbound) pending
     * rows written by [commitBootstrap] (which always pass null
     * under Sprint 2b-C).
     *
     * **Cross-table cleanup, no commit failure.** Unlike
     * [commitBootstrap] this method has no precondition that can
     * fail at runtime (no reservation read), so the return type is
     * [Unit]. The cross-table reservation cleanup described above is
     * a strict-superset write — implementations release zero or more
     * orphan reservation rows then UPSERT pending unconditionally.
     * An L7 cap-enforcer call from the messaging layer SHOULD follow
     * a successful commit (same shape as the inbound flow's
     * post-commit enforce).
     *
     * @param conversationId the outbound conversation
     * @param stateBlob the plaintext JSON of the post-encrypt
     *        RatchetState (the impl wraps it through
     *        `RatchetStateStorageCodec` before persisting, same as
     *        the active table)
     * @param bootstrapArtifactsBlob the [phantom.core.messaging.BootstrapArtifacts]
     *        JSON; REQUIRED non-null
     * @param nowMs the reserved-at timestamp for this pending row
     *        (drives `PENDING_TTL_MS` expiry on the outbound-reuse
     *        path; pass the bootstrap time so subsequent reuses
     *        don't refresh the TTL)
     */
    suspend fun commitInitiatorPending(
        conversationId: String,
        stateBlob: String,
        bootstrapArtifactsBlob: String,
        nowMs: Long,
    )
}
