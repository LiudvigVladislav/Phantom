// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Sprint 2b-C L3 — typed view of the
 * `pending_ratchet_state.bootstrap_artifacts_blob` TEXT column.
 *
 * The blob is opaque to the storage layer (storage just sees
 * `String?`); messaging owns the typed class + JSON serialization.
 * Sprint 2b-B production callers always passed NULL. Sprint 2b-C
 * introduces non-NULL writes ONLY on the OUTBOUND-INITIATOR path —
 * the pending row created by `DefaultMessagingService.encryptUnderLock`
 * bootstrap branch — so a subsequent outbound send within
 * `PENDING_TTL` can reuse the cached [x3dhInit] header without
 * consuming a second OPK from our pool. (The INBOUND case writes
 * NULL: the inbound path's pending row is the candidate state from
 * `recipientBootstrapInMemory`; the artifacts are not needed for
 * promotion.)
 *
 * **Owner contract.** The [x3dhInit]'s `opkKeyIdHex` references the
 * PEER's local OPK pool, NOT ours. The storage-layer
 * `SessionTransactionRepository.promotePendingToActive` honours this
 * with its reservation-optional shape: an outbound-initiator pending
 * row has no companion `opk_reservation`, so promotion is ratchet-
 * state-only with ZERO touches to `local_one_time_pre_key`. See
 * `SessionTransactionRepository.promotePendingToActive` KDoc for the
 * binding contract.
 *
 * [recipientPubkeyHex] is the X25519 hex identity of the peer we
 * bootstrapped against. Carried so the outbound-reuse path can
 * sanity-check the pending row still matches the conversation's
 * recipient before reusing it (defense-in-depth — same conversation
 * id should mean same recipient hex, but the explicit check guards
 * against a future code path that reuses conversation ids across
 * peers).
 */
@Serializable
data class BootstrapArtifacts(
    val x3dhInit: X3dhInitHeader,
    val recipientPubkeyHex: String,
) {

    /**
     * Serialise to the JSON shape stored in
     * `pending_ratchet_state.bootstrap_artifacts_blob`. The
     * representation is stable across releases — adding a new
     * nullable field is forward-compatible; renaming or removing a
     * field is a breaking change that requires a migration.
     */
    fun toBlob(json: Json): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * Decode a blob written by [toBlob]. Returns `null` on
         * malformed / missing input — the outbound-reuse path treats
         * a null parse the same as "no pending artifacts present"
         * and falls through to a fresh bootstrap. The Sprint 2b-B
         * commit boundary always wrote NULL, so legacy rows under
         * the Sprint 2b-B schema deserialize cleanly to null (no
         * blob present in the column).
         */
        fun fromBlob(json: Json, blob: String?): BootstrapArtifacts? {
            if (blob == null) return null
            return try {
                json.decodeFromString(serializer(), blob)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization sometimes surfaces malformed
                // JSON as IllegalArgumentException (numeric overflow,
                // unexpected nulls). Treat as null-parse.
                null
            }
        }
    }
}
