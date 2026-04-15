package phantom.core.storage

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import phantom.core.storage.db.PhantomDatabase

/**
 * Android implementation of DatabaseDriverFactory.
 *
 * Alpha-0: uses standard AndroidSqliteDriver (unencrypted).
 *
 * TODO(hardening): replace AndroidSqliteDriver with a SQLCipher-backed driver
 *   for at-rest encryption. The net.zetetic:sqlcipher-android dependency is
 *   already present in build.gradle.kts. Steps required:
 *   1. Derive or retrieve the DB passphrase from Android Keystore.
 *   2. Use SupportFactory from net.sqlcipher.database.SupportFactory.
 *   3. Pass the factory to AndroidSqliteDriver(... factory = supportFactory).
 *   Reference: ADR-006.
 */
actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(PhantomDatabase.Schema, context, "phantom.db")
}
