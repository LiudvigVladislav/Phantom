package phantom.core.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import phantom.core.storage.db.PhantomDatabase

/**
 * iOS implementation of DatabaseDriverFactory.
 *
 * Uses the SQLite native driver bundled with the OS — no extra pod required.
 *
 * TODO(hardening): integrate SQLCipher for iOS at-rest encryption.
 *   Steps required:
 *   1. Add `pod("SQLCipher")` to the iOS app target's Podfile.
 *   2. Replace NativeSqliteDriver with a SQLCipher-backed driver bridge.
 *   Until then, the database is unencrypted on iOS.
 */
actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(PhantomDatabase.Schema, "phantom.db")
}
