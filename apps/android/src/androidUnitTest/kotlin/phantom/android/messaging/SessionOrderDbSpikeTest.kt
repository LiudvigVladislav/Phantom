// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.messaging

import android.app.Application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.core.storage.SqlDelightConversationRepository
import phantom.core.storage.SqlDelightDecryptFailedEnvelopeRepository
import phantom.core.storage.SqlDelightMessageRepository
import phantom.core.storage.SqlDelightOpkReservationRepository
import phantom.core.storage.SqlDelightPendingRatchetStateRepository
import phantom.core.storage.SqlDelightProcessedEnvelopeRepository
import phantom.core.storage.SqlDelightRatchetStateRepository
import phantom.core.storage.SqlDelightSessionTransactionRepository
import phantom.core.storage.db.PhantomDatabase
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Feasibility spike for the full-stack session-order rig: can a host test
 * build a REAL [PhantomDatabase] and the production repositories, instead
 * of substituting fakes?
 *
 * Nothing about ordering is asserted here. This exists so the rig's
 * foundation is proven separately from the behaviour it will measure.
 */
@RunWith(RobolectricTestRunner::class)
// The real PhantomApplication loads the SQLCipher native library on
// create, which a host JVM does not have. The rig needs Android types,
// not the app object, so it runs under a bare Application.
@Config(sdk = [35], application = Application::class)
class SessionOrderDbSpikeTest {

    @Test
    fun a_real_database_and_the_production_repositories_can_be_built_in_memory() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        PhantomDatabase.Schema.create(driver)
        val db = PhantomDatabase(driver)
        try {
            val processed = SqlDelightProcessedEnvelopeRepository(db)
            val held = SqlDelightDecryptFailedEnvelopeRepository(db)
            val messages = SqlDelightMessageRepository(db)
            val conversations = SqlDelightConversationRepository(db)
            val ratchet = SqlDelightRatchetStateRepository(db)
            val pending = SqlDelightPendingRatchetStateRepository(db)
            val reservations = SqlDelightOpkReservationRepository(db)
            val sessionTx = SqlDelightSessionTransactionRepository(db)

            assertNotNull(processed)
            assertNotNull(held)
            assertNotNull(messages)
            assertNotNull(conversations)
            assertNotNull(ratchet)
            assertNotNull(pending)
            assertNotNull(reservations)
            assertNotNull(sessionTx)

            // One real query, so the schema is proven to exist rather
            // than merely to have compiled.
            kotlinx.coroutines.runBlocking {
                assertFalse(processed.exists("no-such-envelope"))
            }
        } finally {
            driver.close()
        }
    }
}
