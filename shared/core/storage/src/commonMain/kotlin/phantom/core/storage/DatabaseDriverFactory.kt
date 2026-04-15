package phantom.core.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific factory for the SQLDelight SqlDriver.
 *
 * Android actual: uses AndroidSqliteDriver for Alpha-0.
 *   SQLCipher replacement is deferred to the hardening phase — dependency is
 *   already present in build.gradle.kts. See ADR-006.
 *
 * iOS actual: uses NativeSqliteDriver.
 *   SQLCipher iOS pod integration is deferred — add `pod("SQLCipher")` to the
 *   iOS app's Podfile and replace NativeSqliteDriver when the pod is wired.
 *   TODO(hardening): integrate SQLCipher iOS pod for at-rest encryption on iOS.
 */
expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
