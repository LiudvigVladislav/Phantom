// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.2 (2026-08-29) — the revocable [RestEgressGate] unit
 * surface: generation invalidation and cancel/join of dispatches that
 * are SUSPENDED at the network boundary.
 *
 * These prove the second half of "No Silent Downgrade": leaving
 * Standard must invalidate in-flight Direct authorizations so a request
 * already parked at dispatch neither completes nor returns a result to
 * its caller. Verified independently of the orchestrator so the
 * mechanism is pinned on its own.
 */
class RestEgressGateRevocationTest {

    private class Mode(var value: PrivacyMode?)

    private fun gate(mode: Mode, logs: MutableList<String> = mutableListOf()) =
        RestEgressGate(PrivacyModeRestEgressPolicy { mode.value }, log = { logs.add(it) })

    // ── New work fails closed immediately after leaving Standard ──────
    @Test
    fun dispatch_fails_closed_immediately_when_mode_is_not_standard() = runTest {
        val mode = Mode(PrivacyMode.Private)
        val g = gate(mode)
        val ex = runCatching { g.dispatch("op") { 42 } }.exceptionOrNull()
        assertIs<RestEgressBlockedException>(ex)
        assertIs<RestEgressDecision.AnonymousRequiredButUnavailable>(ex.decision)
    }

    // ── A suspended dispatch is cancelled AND joined by revokeAndJoin ─
    @Test
    fun revokeAndJoin_cancels_and_joins_a_suspended_dispatch() = runTest {
        val mode = Mode(PrivacyMode.Standard)
        val g = gate(mode)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var completedNormally = false
        var cancelled = false

        val worker = launch(StandardTestDispatcher(testScheduler)) {
            try {
                g.dispatch("suspended_op") {
                    entered.complete(Unit)
                    release.await() // park at the network boundary
                    completedNormally = true
                    "done"
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
        }
        runCurrent()
        assertTrue(entered.isCompleted, "dispatch must have entered the block")
        assertEquals(1, g.activeDispatchCount(), "the lease must be registered")

        // Runtime switch, then revoke.
        mode.value = PrivacyMode.Private
        g.revokeAndJoin("switch")
        runCurrent()

        assertFalse(completedNormally, "a revoked dispatch must not complete its block")
        assertTrue(cancelled, "the suspended dispatch must observe cancellation")
        assertEquals(0, g.activeDispatchCount(), "revokeAndJoin must join to zero active")
        worker.join()
    }

    // ── A result produced under a stale lease is discarded ────────────
    @Test
    fun invalidate_discards_a_result_produced_under_a_stale_lease() = runTest {
        val mode = Mode(PrivacyMode.Standard)
        val g = gate(mode)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var observed: String? = null
        var cancelled = false

        val worker = launch(StandardTestDispatcher(testScheduler)) {
            try {
                observed = g.dispatch("op") {
                    entered.complete(Unit)
                    release.await()
                    "late-result"
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
        }
        runCurrent()
        assertTrue(entered.isCompleted)

        // Invalidate WITHOUT cancelling, then let the block finish.
        mode.value = PrivacyMode.Private
        g.invalidate("switch")
        release.complete(Unit)
        runCurrent()
        worker.join()

        assertEquals(null, observed, "a stale-lease result must not reach the caller")
        assertTrue(cancelled, "the completing dispatch must throw on stale lease")
    }

    // ── decide() fails closed on a throwing policy ────────────────────
    @Test
    fun decide_fails_closed_on_throwing_policy() {
        val g = RestEgressGate(RestEgressPolicy { error("boom") })
        assertIs<RestEgressDecision.AnonymousRequiredButUnavailable>(g.decide())
    }

    // ── Standard dispatch runs and returns normally ───────────────────
    @Test
    fun standard_dispatch_runs_and_returns() = runTest {
        val g = gate(Mode(PrivacyMode.Standard))
        assertEquals(7, g.dispatch("op") { 7 })
        assertEquals(0, g.activeDispatchCount())
    }
}
