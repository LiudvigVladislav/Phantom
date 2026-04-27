// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import phantom.core.storage.db.PhantomDatabase

/**
 * Holds the single PhantomDatabase instance for a process lifetime.
 *
 * Inject this as a singleton via your DI graph. Do not create multiple instances —
 * SQLDelight drivers are not designed for concurrent schema initialization.
 */
class PhantomDatabaseHolder(driverFactory: DatabaseDriverFactory) {
    val database: PhantomDatabase = PhantomDatabase(driverFactory.createDriver())
}
