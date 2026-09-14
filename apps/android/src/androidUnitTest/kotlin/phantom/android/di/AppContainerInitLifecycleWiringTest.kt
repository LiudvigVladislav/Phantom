// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.di

import phantom.android.service.RetryWiringSourceScanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Stage 2 B1 (2026-09-13): the messaging stack is built as a transaction.
 *
 * Constructing a real [AppContainer] needs a keystore, a database and an
 * Android context, which is why the wiring of this graph has always been
 * pinned at source level (the same reason [AppContainerCommitWiringTest]
 * exists). Recorded honestly as a tripwire: it proves the transaction is
 * written, not that it behaves. The behaviour of the pieces it composes
 * is covered where they live —
 * `HybridSessionSignalOwnershipTest` for the Hybrid's ownership and
 * close, `WsSessionSignalContractTest` for the single consumer, and
 * `RestHealthTest` for the orchestrator's own lifecycle.
 *
 * The defect being pinned: before Stage 2 the only init state was
 * `messagingService != null`, and nothing cleaned up a failure midway. An
 * exception after the orchestrator ref was published left an orchestrator
 * — and possibly a Hybrid consuming the transport's signals — alive with
 * `messagingService == null`, which is exactly the condition the storage
 * wrapper reads as "not initialised". The next attempt then built a
 * SECOND set against the same transport.
 */
class AppContainerInitLifecycleWiringTest {

    private val repoRoot: File = run {
        var candidate = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(candidate, "apps/android/src/androidMain").exists() &&
                File(candidate, "shared/core/transport/src/commonMain").exists()
            ) {
                return@run candidate
            }
            candidate = candidate.parentFile ?: candidate
        }
        candidate
    }

    private fun source(relative: String): String {
        val f = File(repoRoot, relative)
        assertTrue(f.exists(), "source not found: ${f.path}")
        return RetryWiringSourceScanner.codeOnly(f.readText())
    }

    private val containerSource: String
        get() = source("apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt")

    @Test
    fun there_is_one_guarded_entry_and_one_locked_body() {
        val code = containerSource
        val guarded = RetryWiringSourceScanner.blockAfter(code, "suspend fun initMessaging(")
        assertTrue(guarded != null, "the guarded entry is gone or was renamed")
        assertTrue(
            "initMessagingMutex.withLock" in guarded!! && "initMessagingLocked" in guarded,
            "the public entry must take the mutex and delegate to the locked body",
        )
        val storage = RetryWiringSourceScanner.blockAfter(code, "suspend fun initMessagingFromStorage()")
        assertTrue(storage != null, "the storage path is gone or was renamed")
        assertTrue(
            "initMessagingLocked" in storage!!,
            "the storage path already holds the mutex; calling the guarded entry would " +
                "take a non-reentrant mutex twice and deadlock",
        )
        assertTrue(
            "initMessagingMutex.withLock { initMessagingLocked" in guarded,
            "the guarded entry is the ONLY place that acquires the mutex for a build",
        )
    }

    @Test
    fun a_failed_stage_is_cleaned_up_in_reverse_order_and_leaves_no_refs() {
        val code = containerSource
        val locked = RetryWiringSourceScanner.blockAfter(code, "private suspend fun initMessagingLocked")
        assertTrue(locked != null, "the locked body is gone or was renamed")
        assertTrue(
            "closePartialMessagingStack" in locked!!,
            "a failure midway must close what it built; leaving it alive is the defect",
        )
        assertTrue(
            "MessagingInit.Failed" in locked,
            "and it must be recorded, so the next attempt rebuilds rather than inheriting",
        )

        val cleanup = RetryWiringSourceScanner.blockAfter(code, "private suspend fun closePartialMessagingStack")
        assertTrue(cleanup != null, "the cleanup is gone or was renamed")
        val hybridAt = cleanup!!.indexOf("closeAndJoin")
        val orchestratorAt = cleanup.indexOf("orchestrator.close")
        assertTrue(hybridAt >= 0, "the Hybrid owns collectors and the signal subscription; it must be closed")
        assertTrue(orchestratorAt >= 0, "the orchestrator owns its own jobs; it must be closed")
        assertTrue(
            hybridAt < orchestratorAt,
            "reverse construction order: the Hybrid collects the orchestrator's inbound flow, " +
                "so closing the orchestrator first leaves a collector on a dead producer",
        )
        for (ref in listOf("messagingService = null", "hybridTransport = null", "restOrchestratorRef = null")) {
            assertTrue(ref in cleanup, "the cleanup must clear every published ref; missing `$ref`")
        }
        assertTrue(
            "NonCancellable" in cleanup,
            "a cancelled caller must not be able to skip the cleanup",
        )
    }

    @Test
    fun the_signal_subscription_is_attached_inside_the_transaction() {
        val code = containerSource
        val attachAt = code.indexOf("hybrid.startWsPassthroughCollectors()")
        val bootstrapAt = code.indexOf("hybrid.bootstrapAndStart()")
        assertTrue(attachAt >= 0, "stage 2 no longer attaches the transport's signal consumer")
        assertTrue(bootstrapAt >= 0, "the async REST bootstrap is gone or was renamed")
        assertTrue(
            attachAt < bootstrapAt,
            "the attach must be synchronous and inside the transaction: doing it only inside " +
                "the async bootstrap would leave it outside the cleanup, and it would then " +
                "land on an abandoned Hybrid",
        )
    }

    @Test
    fun the_ready_state_is_what_decides_already_initialised() {
        val code = containerSource
        val storage = RetryWiringSourceScanner.blockAfter(code, "suspend fun initMessagingFromStorage()")
        assertTrue(storage != null)
        assertTrue(
            "messagingInit is MessagingInit.Ready" in storage!!,
            "`messagingService != null` was true for a half-built stack; the state machine " +
                "is what distinguishes Ready from Failed-and-cleaned-up",
        )
    }

    @Test
    fun presentation_reads_rest_health_and_the_transport_epoch() {
        val code = containerSource
        val declaredAt = code.indexOf("val connectionUiState")
        assertTrue(declaredAt >= 0, "the presentation combine is gone or was renamed")
        // Two different places, and they need separating: the flows the
        // combine SUBSCRIBES to sit in its argument list, before the
        // lambda arrow, while the values it reads at derivation time sit
        // inside the lambda. `blockAfter` returns the lambda body, so the
        // subscription list has to be read from the header.
        val arrowAt = code.indexOf("->", declaredAt)
        assertTrue(arrowAt > declaredAt, "the combine lambda is gone or was renamed")
        val header = code.substring(declaredAt, arrowAt)
        for (input in listOf("connectionRestHealth", "recoveryActivityState")) {
            assertTrue(
                input in header,
                "B9 needs `$input` as a combine INPUT: a value that is not subscribed " +
                    "cannot re-derive the banner when it changes. Header was: $header",
            )
        }
        val body = RetryWiringSourceScanner.blockAfter(code, "val connectionUiState")
        assertTrue(body != null, "the combine lambda body is gone")
        assertTrue(
            "wsTransport.currentSessionEpoch" in body!!,
            "the transport epoch is read at derivation time, together with the wsState " +
                "it belongs to; without it `Online` cannot require a matching session",
        )
        assertTrue(
            "restHealth =" in body && "recovery =" in body,
            "and both must reach the derivation, or the banner cannot tell a dead socket " +
                "with a working fallback from nothing working at all",
        )
    }

    @Test
    fun a_handed_over_tor_obligation_blocks_the_deferred_settlement() {
        val code = containerSource
        assertTrue(
            "fun recordPendingTorSettlement" in code,
            "the coordinator must be able to hand the obligation over rather than drop it",
        )
        val settle = RetryWiringSourceScanner.blockAfter(code, "private suspend fun settleDeferredSwitchLocked")
        assertTrue(settle != null, "the deferred settlement is gone or was renamed")
        assertTrue(
            "torSettlementOutstanding()" in settle!!,
            "a switch must not declare itself settled over a daemon nobody confirmed gone",
        )
        val outstanding = RetryWiringSourceScanner.blockAfter(code, "internal fun torSettlementOutstanding")
        assertTrue(outstanding != null)
        assertTrue(
            "settlementFor" in outstanding!!,
            "and it must clear by the SAME authoritative per-generation check the coordinator uses",
        )
    }

    @Test
    fun an_aborted_rewalk_nudges_the_single_recovery_coordinator() {
        val code = containerSource
        assertTrue(
            "onRewalkAborted" in code,
            "before Stage 2 an aborted rewalk left recovery to nobody: the pending retry was " +
                "invalidated on the restart branch while every abort branch returned silently",
        )
        assertTrue(
            "nudgeRecovery" in code,
            "the abort must reach the service's coordinator, which is the only place that can decide",
        )
        assertEquals(
            1,
            Regex(Regex.escape("onRewalkAborted =")).findAll(code).count(),
            "exactly one wiring of the abort nudge",
        )
    }
}
