// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.migration

import android.app.Application
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.StartupRouteDecision
import phantom.android.screens.onboarding.v2.decideStartupRoute
import phantom.android.service.RetryWiringSourceScanner
import phantom.core.identity.IdentityRecord
import phantom.core.messaging.MigrationProgress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationStartupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pending_marker_routes_a_backfilled_identity_to_migration_not_chat() = runTest {
        val dir = temporary.newFolder()
        AndroidMigrationProgressStore(dir).write("id", MigrationProgress.IN_PROGRESS)
        val record = IdentityRecord("id", "test", "11".repeat(32), "22".repeat(32), 0L,
            "33".repeat(32), "44".repeat(64))
        val decision = decideStartupRoute(
            markerRead = { false }, loadIdentity = { record }, initMessaging = { true },
            needsMigration = { AndroidMigrationProgressStore(dir).read("id") == MigrationProgress.IN_PROGRESS },
        )
        assertEquals(StartupRouteDecision.Migration, decision)
    }

    // Source tripwires, not an execution of the complete Android service/container graph.
    private fun source(path: String): String = RetryWiringSourceScanner.codeOnly(
        File("src/androidMain/kotlin/phantom/android/$path").readText(),
    )

    private fun assertMigrationBarrier(code: String) {
        val build = checkNotNull(RetryWiringSourceScanner.blockAfter(code, "private suspend fun buildMessagingStack"))
        val guard = "if (checkNotNull(migrationManager).needsMigration())"
        val at = build.indexOf(guard)
        assertTrue(at >= 0)
        val body = checkNotNull(RetryWiringSourceScanner.blockAfter(build, guard))
        assertTrue("messagingInit = MessagingInit.AwaitingMigration" in body)
        assertTrue(Regex("\\breturn\\b").containsMatchIn(body))
        for (operation in listOf("opkReservationRepo.sweepOrphanReservations(", "RestFallbackOrchestrator(", "hybrid.startWsPassthroughCollectors()",
                "lifecycleService.bootstrapForNewIdentity()", "DefaultMessagingService(")) {
            assertTrue(build.indexOf(operation) > at, "Migration must precede $operation")
        }
    }

    @Test fun initialization_barrier_precedes_background_work_and_control_detects_its_removal() {
        val code = source("di/AppContainer.kt")
        assertMigrationBarrier(code)
        val marker = "messagingInit = MessagingInit.AwaitingMigration\n            return"
        assertTrue(marker in code)
        assertFailsWith<AssertionError> { assertMigrationBarrier(code.replace(marker,
            "messagingInit = MessagingInit.AwaitingMigration")) }
    }

    @Test fun foreground_service_cannot_start_receiving_while_awaiting_migration() {
        val code = source("service/PhantomMessagingService.kt")
        val init = code.indexOf("container.initMessagingFromStorage()")
        val guard = code.indexOf("container.messagingInit is AppContainer.MessagingInit.AwaitingMigration", init)
        val receive = code.indexOf("messaging.startReceiving()", init)
        assertTrue(init >= 0 && guard > init && receive > guard)
        val body = checkNotNull(RetryWiringSourceScanner.blockAfter(code,
            "if (container.messagingInit is AppContainer.MessagingInit.AwaitingMigration)"))
        assertTrue("return@serviceStartupOrNull null" in body)
        val container = source("di/AppContainer.kt")
        assertTrue("if (messagingInit !is MessagingInit.AwaitingMigration) messagingInit = MessagingInit.Ready" in container)
    }
}
