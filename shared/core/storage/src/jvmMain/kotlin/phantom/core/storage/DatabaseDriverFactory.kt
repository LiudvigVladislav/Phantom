package phantom.core.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * JVM actual for DatabaseDriverFactory.
 *
 * The JVM target exists to satisfy compilation for commonTest and jvmTest.
 * In the JVM target there is no production database (all test code uses in-memory
 * repository fakes). Calling createDriver() at runtime will throw.
 *
 * Post Alpha-0: wire in app.cash.sqldelight:sqlite-driver for desktop/JVM builds
 * when a standalone desktop target is added to the roadmap.
 */
actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver =
        throw UnsupportedOperationException(
            "DatabaseDriverFactory is not implemented for the JVM target. " +
            "Use in-memory repository fakes in JVM tests."
        )
}
