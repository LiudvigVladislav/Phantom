// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.storage

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import phantom.core.storage.db.PhantomDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        val passphrase = DatabasePassphraseManager.getOrCreatePassphrase(context)
        val factory = SupportOpenHelperFactory(passphrase)
        passphrase.fill(0)
        return AndroidSqliteDriver(
            schema = PhantomDatabase.Schema,
            context = context,
            name = "phantom.db",
            factory = factory,
        )
    }
}
