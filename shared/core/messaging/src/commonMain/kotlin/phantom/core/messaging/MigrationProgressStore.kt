// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

/** Identity-scoped, durable local progress. Read/write failures must throw, never imply completion. */
interface MigrationProgressStore {
    suspend fun read(identityId: String): MigrationProgress
    suspend fun write(identityId: String, progress: MigrationProgress)
}

enum class MigrationProgress { NOT_STARTED, IN_PROGRESS, COMPLETE }
