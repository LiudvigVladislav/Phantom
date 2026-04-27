// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import phantom.core.identity.IdentityRepository

/**
 * Marks the contract for the storage-layer implementation of IdentityRepository.
 *
 * No new methods are needed beyond what IdentityRepository declares.
 * SqlDelightIdentityRepository implements this interface.
 *
 * createIdentity() is intentionally NOT handled by storage — key generation is
 * IdentityManager's responsibility. The SQL implementation throws
 * UnsupportedOperationException to make that boundary explicit.
 */
interface IdentityStorageRepository : IdentityRepository
