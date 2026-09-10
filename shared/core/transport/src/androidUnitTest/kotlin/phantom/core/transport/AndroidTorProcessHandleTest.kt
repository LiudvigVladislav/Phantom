// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

/**
 * Tests [AndroidTorProcessHandle] itself. Only the library interface beneath
 * it is substituted, so the classification, the split between launch and
 * configuration, and the delegation are all really exercised.
 */
class AndroidTorProcessHandleTest {

    // ── what a launch attempt is allowed to conclude ──────────────────────

    @Test
    fun a_returned_start_is_a_launch() = runTest {
        val wrapper = FakeTorWrapper()
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        assertEquals(LaunchAttestation.Launched, handle.launch())
        assertEquals(1, wrapper.callCount("start"))
    }

    @Test
    fun an_io_exception_from_start_attests_the_resource_was_reaped() = runTest {
        val wrapper = FakeTorWrapper()
        val refusal = IOException("tor could not be started")
        wrapper.startFailure = refusal
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        // The library's own handler covers IOException, and it destroys and
        // awaits the process before rethrowing; that is what makes this the
        // one failure allowed to say no daemon is left.
        val attestation = handle.launch()

        assertIs<LaunchAttestation.FailedResourceReaped>(attestation)
        assertSame(refusal, attestation.cause)
    }

    @Test
    fun an_interrupted_start_leaves_the_resource_unknown() = runTest {
        val wrapper = FakeTorWrapper()
        val interrupted = InterruptedException("waitFor interrupted")
        wrapper.startFailure = interrupted
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        val attestation = handle.launch()

        // InterruptedException bypasses the library's IOException handler, so
        // its process reference survives and its state stays part-way.
        assertIs<LaunchAttestation.FailedResourceUnknown>(attestation)
        assertSame(interrupted, attestation.cause)
    }

    @Test
    fun any_other_failure_from_start_leaves_the_resource_unknown() = runTest {
        val wrapper = FakeTorWrapper()
        val failure = IllegalStateException("no supported ABI")
        wrapper.startFailure = failure
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        val attestation = handle.launch()

        assertIs<LaunchAttestation.FailedResourceUnknown>(attestation)
        assertSame(failure, attestation.cause)
    }

    @Test
    fun cancellation_from_start_is_never_classified() = runTest {
        val wrapper = FakeTorWrapper()
        val cancellation = CancellationException("caller went away")
        wrapper.startFailure = cancellation
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        // Cancellation is not a library failure: it must leave as itself
        // rather than become an attestation about the daemon.
        val thrown = assertFailsWith<CancellationException> { handle.launch() }

        assertCarries(cancellation, thrown)
    }

    // ── configuration is not part of the launch verdict ───────────────────

    @Test
    fun a_bridge_failure_after_a_launch_is_not_an_absent_resource() = runTest {
        val wrapper = FakeTorWrapper()
        val refusal = IOException("bridges refused")
        wrapper.enableBridgesFailure = refusal
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        assertEquals(LaunchAttestation.Launched, handle.launch())

        // It propagates as a plain failure. It produces no attestation, so it
        // cannot be mistaken for a launch that left nothing behind.
        val thrown = assertFailsWith<IOException> { handle.configureBridges(listOf("bridge")) }
        assertCarries(refusal, thrown)
        assertEquals(1, wrapper.callCount("start"))
    }

    @Test
    fun an_enable_network_failure_after_a_launch_is_not_an_absent_resource() = runTest {
        val wrapper = FakeTorWrapper()
        val refusal = IOException("network refused")
        wrapper.enableNetworkFailure = refusal
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        assertEquals(LaunchAttestation.Launched, handle.launch())

        val thrown = assertFailsWith<IOException> { handle.enableNetwork(enabled = true) }
        assertCarries(refusal, thrown)
    }

    // ── delegation ────────────────────────────────────────────────────────

    @Test
    fun bridges_are_applied_and_an_absent_list_disables_them() = runTest {
        val wrapper = FakeTorWrapper()
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        handle.configureBridges(listOf("obfs4 one", "obfs4 two"))
        assertEquals(listOf("obfs4 one", "obfs4 two"), wrapper.bridgesApplied)

        handle.configureBridges(emptyList())
        handle.configureBridges(null)

        assertEquals(1, wrapper.callCount("enableBridges"))
        assertEquals(2, wrapper.callCount("disableBridges"))
    }

    @Test
    fun enable_network_carries_the_flag_through() = runTest {
        val wrapper = FakeTorWrapper()
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        handle.enableNetwork(enabled = true)
        handle.enableNetwork(enabled = false)

        assertEquals(listOf("enableNetwork:true", "enableNetwork:false"), wrapper.recorded())
    }

    @Test
    fun terminate_quiesces_the_network_before_stopping() = runTest {
        val wrapper = FakeTorWrapper()
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        val report = handle.terminate()

        assertEquals(listOf("enableNetwork:false", "stop"), wrapper.recorded())
        assertEquals(null, report.quiesceFailure)
    }

    @Test
    fun a_failing_network_disable_still_attempts_the_stop_and_reports_the_failure() = runTest {
        val wrapper = FakeTorWrapper()
        val refusal = IOException("network refused")
        wrapper.enableNetworkFailure = refusal
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        // The process exited, so this is still a teardown that finished —
        // the quiesce failure rides along instead of erasing it.
        val report = handle.terminate()

        assertEquals(1, wrapper.callCount("stop"))
        assertCarries(refusal, assertNotNull(report.quiesceFailure))
    }

    @Test
    fun a_failing_stop_is_not_a_confirmation_and_keeps_the_earlier_failure() = runTest {
        val wrapper = FakeTorWrapper()
        val quiesce = IOException("network refused")
        val refusal = IOException("stop refused")
        wrapper.enableNetworkFailure = quiesce
        wrapper.stopFailure = refusal
        val handle = AndroidTorProcessHandle(wrapper, StandardTestDispatcher(testScheduler))

        val thrown = assertFailsWith<IOException> { handle.terminate() }

        assertCarries(refusal, thrown)
        assertTrue(
            refusal.suppressedExceptions.any { it === quiesce },
            "the network-disable failure was dropped",
        )
    }
}

/**
 * Assert that [expected] is the failure that came out, allowing for the copy
 * kotlinx.coroutines makes when an exception crosses a `withContext`
 * boundary: stack-trace recovery rethrows a clone and keeps the original as
 * its cause, so identity has to be looked for along that chain rather than
 * on the top-level object.
 */
private fun assertCarries(expected: Throwable, thrown: Throwable) {
    val original = generateSequence(thrown) { it.cause }.firstOrNull { it === expected }
    assertSame(expected, original, "the original failure did not survive the boundary")
}
