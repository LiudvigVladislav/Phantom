// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

/**
 * Persists the user's local SignedPreKey (and optionally a previous
 * generation kept for the 14-day rotation retention window).
 *
 * Singleton — one row per device. Backed by a single-row SQLite table
 * (see `LocalSignedPreKey.sq`).
 */
interface LocalSignedPreKeyRepository {
    suspend fun get(): LocalSignedPreKeyEntity?
    suspend fun upsert(entity: LocalSignedPreKeyEntity)
    /**
     * Wipe both current and previous SPK rows. Called by the Alpha 1 →
     * Alpha 2 migration flow before the user republishes a fresh bundle.
     * Idempotent.
     */
    suspend fun clear()
}
