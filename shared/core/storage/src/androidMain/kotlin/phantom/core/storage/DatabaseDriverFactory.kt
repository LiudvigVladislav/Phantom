package phantom.core.storage

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.sqlcipher.database.SupportFactory
import phantom.core.storage.db.PhantomDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        val passphrase = DatabasePassphraseManager.getOrCreatePassphrase(context)
        val supportFactory = SupportFactory(passphrase)
        passphrase.fill(0)
        return AndroidSqliteDriver(
            schema = PhantomDatabase.Schema,
            context = context,
            name = "phantom.db",
            callback = AndroidSqliteDriver.Callback(PhantomDatabase.Schema),
            factory = supportFactory,
        )
    }
}
