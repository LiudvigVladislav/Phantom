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
            callback = object : AndroidSqliteDriver.Callback(PhantomDatabase.Schema) {
                override fun onOpen(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
                    super.onOpen(db)
                    // Reduce PBKDF2 iterations to 1 — DB is already open/keyed at this point,
                    // so this sets the iteration count for subsequent operations.
                    // Encryption is still active; kdf_iter only affects key-derivation speed.
                    db.execSQL("PRAGMA kdf_iter = 1;")
                }
            },
            factory = factory,
        )
    }
}
