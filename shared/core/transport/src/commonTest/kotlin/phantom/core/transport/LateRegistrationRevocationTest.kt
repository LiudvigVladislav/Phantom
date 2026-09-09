// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N1-F2 R-N1.4 P1-1 — the `cancelAll` snapshot race.
 *
 * R-N1.3's [EgressCallRegistry.cancelAll] took a one-shot snapshot of
 * the live handles and cancelled those. A dispatch that had already been
 * authorized under the old posture, but had not yet reached its
 * `register` call, was absent from that snapshot. It registered
 * afterwards and went on to open the socket. Cancelling its coroutine
 * `Job` changed nothing, because production transports block in
 * `Call.execute()` and ignore coroutine cancellation.
 *
 * The interleaving under test is exactly:
 *
 * ```
 * dispatch authorized (lease = N)
 *   ... parked before register ...
 *                                   revokeAndJoin completes
 *   ... resumes, registers, executes ...   <-- Direct I/O after revocation
 * ```
 *
 * The registry is now generation-aware: `cancelAll` records the revoked
 * lease and takes its snapshot under the same lock a `register` must
 * hold, so a late registration is either in the snapshot or refused.
 * There is no third case.
 *
 * These fixtures drive the real [RestEgressGate] and
 * [EgressCallRegistry] — no fakes for the mechanism under test.
 */
class LateRegistrationRevocationTest {

    private class Mode(var value: PrivacyMode?)

    /** Stands in for the blocking native call an adapter would open. */
    private class FakeNativeCall {
        var started = false
        var cancelled = false
        fun cancel() {
            cancelled = true
        }
    }

    // ── The headline: a registration arriving after a completed
    //    revocation must be refused, and the call must never start.
    @Test
    fun a_registration_after_a_completed_revocation_is_refused() = runTest {
        val mode = Mode(PrivacyMode.Standard)
        val registry = EgressCallRegistry()
        val gate = RestEgressGate(
            PrivacyModeRestEgressPolicy { mode.value },
            callRegistry = registry,
        )

        val insideBlock = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val call = FakeNativeCall()
        var refused = false

        val worker = launch(StandardTestDispatcher(testScheduler)) {
            runCatching {
                gate.dispatch("send") {
                    // The adapter has been authorized and is now running.
                    // Everything from here to execute() is NonCancellable
                    // because a real adapter blocks in okhttp3
                    // Call.execute(), which ignores coroutine
                    // cancellation. Modelling that matters: park OUTSIDE
                    // the block instead and the gate's own Job
                    // cancellation stops the block from ever running, so
                    // the fixture would measure Job cancellation — the
                    // thing this finding says is insufficient — and would
                    // stay green with the generation check removed.
                    withContext(NonCancellable) {
                        insideBlock.complete(Unit)
                        release.await()
                        val token = registry.register { call.cancel() }
                        if (token == null) {
                            refused = true
                        } else {
                            call.started = true
                            registry.unregister(token)
                        }
                    }
                }
            }
        }
        runCurrent()
        assertTrue(insideBlock.isCompleted, "the dispatch must be authorized and running")

        // The user leaves Standard. Revocation runs to completion while
        // the adapter sits between authorization and its register call.
        mode.value = PrivacyMode.Private
        val result = gate.revokeAndJoin("privacy_mode_change", joinTimeoutMs = 50L)
        assertEquals(
            0, result.nativeCallsCancelled,
            "nothing was registered yet — this is precisely the gap the old snapshot left open",
        )

        release.complete(Unit)
        runCurrent()
        worker.join()

        assertFalse(
            call.started,
            "a Direct call started AFTER the revocation completed. The registration arrived " +
                "too late for the snapshot and was not refused either, so the request went out " +
                "under a revoked posture.",
        )
        assertTrue(refused, "the late registration must be explicitly refused")
        assertTrue(call.cancelled, "the refused handle must be aborted, not merely rejected")
        assertEquals(0, registry.liveCount(), "no handle may be left registered")
    }

    // ── Non-vacuity control: with no revocation, the identical
    //    interleaving registers and starts the call normally.
    @Test
    fun without_revocation_the_same_late_registration_succeeds() = runTest {
        val registry = EgressCallRegistry()
        val gate = RestEgressGate(
            RestEgressPolicy { RestEgressDecision.DirectAllowed },
            callRegistry = registry,
        )
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gate.afterAuthorizationHook = {
            parked.complete(Unit)
            release.await()
        }

        val call = FakeNativeCall()
        val worker = launch(StandardTestDispatcher(testScheduler)) {
            gate.dispatch("send") {
                val token = registry.register { call.cancel() }
                assertTrue(token != null, "an un-revoked lease must be accepted")
                call.started = true
                registry.unregister(token!!)
            }
        }
        runCurrent()
        assertTrue(parked.isCompleted)
        release.complete(Unit)
        runCurrent()
        worker.join()

        assertTrue(call.started, "the control path must actually open the call")
        assertEquals(0, registry.liveCount())
    }

    // ── A call registered BEFORE the revocation is cancelled by the
    //    snapshot, which is the other half of the guarantee.
    @Test
    fun a_registration_before_the_revocation_is_cancelled_by_the_snapshot() = runTest {
        val registry = EgressCallRegistry()
        val call = FakeNativeCall()
        val token = registry.register { call.cancel() }
        assertTrue(token != null)

        val cancelled = registry.cancelAll("privacy_mode_change", revokedThroughLease = 0L)

        assertEquals(1, cancelled)
        assertTrue(call.cancelled, "an already-registered call must be aborted")
        registry.unregister(token!!)
    }

    // ── A dispatch issued AFTER the revocation holds a newer lease and
    //    must not be refused by the stale revocation marker.
    @Test
    fun a_newer_lease_is_not_refused_by_an_older_revocation() = runTest {
        val registry = EgressCallRegistry()
        val gate = RestEgressGate(
            RestEgressPolicy { RestEgressDecision.DirectAllowed },
            callRegistry = registry,
        )
        gate.revokeAndJoin("first_switch")

        val call = FakeNativeCall()
        gate.dispatch("send") {
            val token = registry.register { call.cancel() }
            assertTrue(
                token != null,
                "a lease issued after the revocation must still be usable — otherwise the " +
                    "gate is permanently closed after any mode change",
            )
            call.started = true
            registry.unregister(token!!)
        }
        assertTrue(call.started)
    }

    // ── A caller with no lease in scope (a transport used directly,
    //    outside any gate) is never refused: it holds nothing to revoke.
    @Test
    fun a_caller_without_a_lease_is_never_refused() = runTest {
        val registry = EgressCallRegistry()
        registry.cancelAll("switch", revokedThroughLease = Long.MAX_VALUE)

        val call = FakeNativeCall()
        val token = registry.register { call.cancel() }
        assertTrue(
            token != null,
            "an un-gated caller holds no lease and must not be refused by a past revocation",
        )
        registry.unregister(token!!)
    }

    // ── registerOrRefuse turns the refusal into a failure the transports
    //    already know how to handle, so a caller cannot proceed by
    //    ignoring a null.
    @Test
    fun registerOrRefuse_throws_for_a_revoked_lease() = runTest {
        val mode = Mode(PrivacyMode.Standard)
        val registry = EgressCallRegistry()
        val gate = RestEgressGate(
            PrivacyModeRestEgressPolicy { mode.value },
            callRegistry = registry,
        )
        val insideBlock = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val call = FakeNativeCall()
        var thrown: Throwable? = null

        val worker = launch(StandardTestDispatcher(testScheduler)) {
            runCatching {
                gate.dispatch("media_upload") {
                    withContext(NonCancellable) {
                        insideBlock.complete(Unit)
                        release.await()
                        try {
                            registry.registerOrRefuse("media_chunk") { call.cancel() }
                            call.started = true
                        } catch (t: Throwable) {
                            thrown = t
                        }
                    }
                }
            }
        }
        runCurrent()
        assertTrue(insideBlock.isCompleted)
        mode.value = PrivacyMode.Ghost
        gate.revokeAndJoin("privacy_mode_change", joinTimeoutMs = 50L)
        release.complete(Unit)
        runCurrent()
        worker.join()

        assertFalse(call.started, "the call must not start under a revoked lease")
        assertTrue(
            thrown is RestEgressBlockedException,
            "registerOrRefuse must throw so a caller cannot proceed by ignoring a null, " +
                "got $thrown",
        )
    }
}
