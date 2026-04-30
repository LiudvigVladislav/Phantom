// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Persists the user's local OneTimePreKey pool — one row per OPK.
 *
 * Lookup by `keyIdHex` is O(1) so the recipient bootstrap path can
 * resolve a peer's `x3dhInit.opkKeyIdHex` to a private half quickly.
 * On successful X3DH 4-DH the matching entry is deleted so the OPK
 * cannot be reused — that is what makes them "one-time" and the
 * forward-secrecy guarantee meaningful.
 */
interface LocalOneTimePreKeyRepository {
    suspend fun get(keyIdHex: String): LocalOneTimePreKeyEntity?

    /** All OPKs in the pool, oldest first. Used by the lifecycle service. */
    suspend fun getAll(): List<LocalOneTimePreKeyEntity>

    /** Number of remaining OPKs — drives the "<20 → replenish" threshold. */
    suspend fun count(): Int

    suspend fun insert(entity: LocalOneTimePreKeyEntity)

    /**
     * Insert a batch atomically. The lifecycle service generates and
     * publishes 100 OPKs at a time; storing them in a single transaction
     * avoids a partial-state window if the app is killed mid-insert.
     */
    suspend fun insertAll(entities: List<LocalOneTimePreKeyEntity>)

    /**
     * Delete one OPK by ID. Called after the recipient bootstrap path
     * successfully consumes the OPK to derive a session — single-use
     * lifecycle.
     */
    suspend fun deleteByKeyId(keyIdHex: String)

    /**
     * Wipe the pool. Called by the Alpha 1 → Alpha 2 migration; the
     * fresh OPKs will be generated and republished. Idempotent.
     */
    suspend fun clear()
}
