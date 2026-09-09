// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.briarproject.onionwrapper.TorWrapper
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioural tests of the real [TorServiceAndroid]. Only the library
 * boundary is substituted: each generation gets a host whose wrapper is a
 * [FakeTorWrapper] and whose release result the test controls.
 */
class TorServiceAndroidTest {

    @Test
    fun a_start_applies_bridges_before_enabling_the_network() = runTest {
        val fixture = fixture()

        fixture.service.start(BridgeProfile.Obfs4Only)

        assertEquals(
            listOf("start", "enableBridges", "enableNetwork:true"),
            fixture.hosts.single().wrapper.recorded(),
        )
    }

    @Test
    fun a_confirmed_stop_quiesces_then_publishes_off() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)

        assertTrue(fixture.service.stopFully().isFree)

        val wrapper = fixture.hosts.single().wrapper
        assertEquals(
            listOf("start", "enableBridges", "enableNetwork:true", "enableNetwork:false", "stop"),
            wrapper.recorded(),
        )
        assertEquals(TorState.Off, fixture.service.state.value)
    }

    @Test
    fun an_unconfirmed_stop_publishes_failed_rather_than_off() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        fixture.hosts.single().wrapper.stopFailure = IOException("stop refused")

        assertUnknown(fixture.service.stopFully())

        assertIs<TorState.Failed>(fixture.service.state.value)
    }

    @Test
    fun an_unconfirmed_stop_refuses_the_next_start() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        fixture.hosts.single().wrapper.stopFailure = IOException("stop refused")
        assertUnknown(fixture.service.stopFully())

        assertFailsWith<TorStartRefusedException> { fixture.service.start(BridgeProfile.Obfs4Only) }

        // No second daemon was ever asked for.
        assertEquals(1, fixture.hosts.size)
    }

    @Test
    fun a_stopping_event_never_publishes_off() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        val host = fixture.hosts.single()
        host.observer.onState(TorWrapper.TorState.CONNECTED)
        assertIs<TorState.Ready>(fixture.service.state.value)

        // The daemon may still be winding down; only a recorded outcome
        // says it is gone.
        host.observer.onState(TorWrapper.TorState.STOPPING)
        host.observer.onState(TorWrapper.TorState.DISABLED)

        assertIs<TorState.Ready>(fixture.service.state.value)
    }

    @Test
    fun ordinary_events_of_the_live_generation_are_applied() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        val host = fixture.hosts.single()

        host.observer.onBootstrapPercentage(45)
        assertEquals(TorState.Bootstrapping(45), fixture.service.state.value)

        host.observer.onState(TorWrapper.TorState.CONNECTED)
        assertEquals(TorState.Ready(socksPort = 39050), fixture.service.state.value)
    }

    @Test
    fun an_event_produced_by_a_closed_generation_does_not_change_the_new_one() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        val closed = fixture.hosts.single()
        fixture.service.stopFully()

        fixture.service.start(BridgeProfile.Obfs4Only)
        runCurrent()
        val beforeTheLateEvent = fixture.service.state.value
        assertEquals(2, fixture.hosts.size)

        // The closed generation's own observer fires after the new one is
        // live. Its token is not the live one, so it changes nothing.
        closed.observer.onState(TorWrapper.TorState.CONNECTED)
        closed.observer.onBootstrapPercentage(99)

        assertEquals(beforeTheLateEvent, fixture.service.state.value)

        // Positive control on the same state: the live generation's own
        // observer still works.
        fixture.hosts[1].observer.onState(TorWrapper.TorState.CONNECTED)
        assertIs<TorState.Ready>(fixture.service.state.value)
    }

    @Test
    fun a_connected_event_after_the_generation_settled_does_not_publish_ready() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        val host = fixture.hosts.single()
        fixture.service.stopFully()
        assertEquals(TorState.Off, fixture.service.state.value)

        // Same generation, still the live one — only the phase has moved on.
        // The token check cannot catch this; the phase is what must.
        host.observer.onState(TorWrapper.TorState.CONNECTED)
        host.observer.onBootstrapPercentage(80)

        assertEquals(TorState.Off, fixture.service.state.value)
    }

    @Test
    fun a_second_start_while_running_is_a_no_op() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)

        fixture.service.start(BridgeProfile.Obfs4Only)

        assertEquals(1, fixture.hosts.size)
    }

    @Test
    fun a_late_percentage_from_a_closed_generation_is_not_reported_by_the_new_one() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        val closed = fixture.hosts.single()
        closed.observer.onBootstrapPercentage(77)
        fixture.service.stopFully()
        fixture.service.start(BridgeProfile.Obfs4Only)
        runCurrent()

        // The closed generation reports again, late, and then the live one
        // reports plain progress. The number the live one publishes must be
        // its own.
        closed.observer.onBootstrapPercentage(99)
        fixture.hosts[1].observer.onState(TorWrapper.TorState.CONNECTING)

        assertEquals(TorState.Bootstrapping(0), fixture.service.state.value)
    }

    @Test
    fun an_unreleased_host_blocks_the_next_generation() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        fixture.hosts.single().releaseResult =
            ReleaseResult.NotReleased(IllegalStateException("executor did not terminate"))

        val result = assertIs<TorStopResult.ReleaseFailed>(fixture.service.stopFully())
        assertNotNull(result.releaseFailure)
        assertEquals(TorState.Off, fixture.service.state.value)

        // The daemon is gone, so the state is Off — but the threads are not,
        // so no successor is created.
        val refusal = assertFailsWith<TorStartRefusedException> {
            fixture.service.start(BridgeProfile.Obfs4Only)
        }
        assertTrue(refusal.message.orEmpty().contains("host"))
        assertEquals(1, fixture.hosts.size)
    }

    @Test
    fun a_released_host_allows_the_next_generation() = runTest {
        val fixture = fixture()
        fixture.service.start(BridgeProfile.Obfs4Only)
        fixture.service.stopFully()

        fixture.service.start(BridgeProfile.Obfs4Only)

        assertEquals(2, fixture.hosts.size)
    }

    // ── fixture ──────────────────────────────────────────────────────────

    private class Fixture(val service: TorServiceAndroid, val hosts: MutableList<FakeHost>)

    private class FakeHost(
        override val token: GenerationToken,
        val observer: TorWrapper.Observer,
    ) : TorHost {
        override val wrapper = FakeTorWrapper()
        var releaseResult: ReleaseResult = ReleaseResult.Released
        override suspend fun release(): ReleaseResult = releaseResult
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val hosts = mutableListOf<FakeHost>()
        val service = TorServiceAndroid(
            config = TorServiceConfig(
                dataDirectoryPath = "unused",
                cacheDirectoryPath = "unused",
                useBridges = true,
            ),
            hostFactory = { token, observer -> FakeHost(token, observer).also { hosts += it } },
            workers = backgroundScope,
            log = { _, _, _ -> },
            io = StandardTestDispatcher(testScheduler),
        )
        return Fixture(service, hosts)
    }

    /**
     * A teardown taken all the way, the way a real caller takes it: one
     * budget across the daemon going away and its host letting go.
     */
    private suspend fun TorService.stopFully(): TorStopResult {
        val budget = TorBudget(30_000)
        val response = stop(budget)
        if (response.result !is TorStopResult.Releasing) return response.result
        return awaitRelease(response.attempt, budget)
    }
}
