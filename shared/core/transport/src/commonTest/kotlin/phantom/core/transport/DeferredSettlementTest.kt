// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R-N1.17 P1 - the deferred half of a privacy switch.
 *
 * Four defects lived in the gaps between the three components, and none
 * of them was visible to a fixture that drove only one:
 *
 *  - a new epoch inherited `walkQuiesced = true` from the last completed
 *    switch, so a socket recovery could publish Applied before this
 *    epoch had attempted its own handover;
 *  - a successful handover cancelled the shared timer, destroying a
 *    recovery armed a moment earlier for unclosed sockets;
 *  - a timer armed because the successor failed to START asked
 *    `recoverIfBlocked`, got null, and exited without starting anything;
 *  - a parked legacy migration could land after a newer request and
 *    write the old mode back into storage.
 */
class DeferredSettlementTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }

    @Test
    fun aNewEpochHasConfirmedNothingYet() = runBlocking {
        // requestMode -> failed close -> retryPending, with NO complete()
        // in between. The mode must stay Blocked: this epoch has never
        // attempted its handover, whatever the last one achieved.
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)

        // A first switch that completes cleanly, so walkQuiesced is true.
        val first = policy.requestMode(PrivacyMode.Private)
        policy.complete(first, policyChangeComplete = true, walkQuiesced = true)
        assertTrue(policy.state.value.walkQuiesced)

        // Now a socket that will not close on the first attempt.
        var wedged = true
        val permit = policy.acquirePermit(TransportKind.Tor, policy.currentEpoch()) {
            if (wedged) error("close failed")
        }
        assertNotNull(permit)
        permit.useToOpen { }

        policy.requestMode(PrivacyMode.Ghost)
        assertFalse(
            policy.state.value.walkQuiesced,
            "a new epoch must not inherit the previous switch's confirmation",
        )

        wedged = false
        policy.retryPending()

        assertEquals(
            PrivacyModeStatus.Blocked,
            policy.state.value.status,
            "the sockets closed, but this epoch never handed the lease over",
        )
        assertEquals(PrivacyMode.Private, policy.state.value.effective)
    }

    @Test
    fun aSuccessfulHandoverDoesNotDestroyASocketRecovery() = runBlocking {
        // Both jobs share one timer. A handover that settles the LEASE
        // must not stand down a recovery armed for unclosed SOCKETS.
        var next = 0L
        val ownership = ConnectOwnership(nextToken = { ++next })
        var sweepCalls = 0
        var sweepDirty = true
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { true },
            intervalMs = 40L,
            sweepPending = {
                sweepCalls += 1
                sweepDirty
            },
        )

        recovery.arm("privacy_switch_incomplete")
        assertTrue(recovery.isArmed())

        // A clean handover: nothing owns the lease, so this succeeds.
        assertTrue(recovery.handOverOrArm("privacy_mode_change"))

        assertTrue(
            recovery.isArmed(),
            "the socket recovery must survive a handover that only settled the lease",
        )

        sweepDirty = false
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && recovery.isArmed()) {
            delay(20)
        }
        assertFalse(recovery.isArmed(), "and it stands down once its own work is done")
        assertTrue(sweepCalls > 0, "having actually swept: $sweepCalls")
    }

    @Test
    fun aFailedStartIsRetriedEvenThoughNothingIsBlocked() = runBlocking {
        // The lease is free, so `recoverIfBlocked` returns null. An
        // ordinary arm() exits through NotBlocked without starting
        // anything - which is precisely the state a failed successor
        // start leaves behind.
        var next = 0L
        val ownership = ConnectOwnership(nextToken = { ++next })
        val starts = SafeSteps()
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { starts.add(it); true },
            intervalMs = 40L,
        )

        recovery.armStartRetry("successor_start_failed")

        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && starts.isEmpty()) {
            delay(20)
        }

        assertEquals(
            listOf("successor_start_failed"),
            starts.toList(),
            "a start that failed must actually be retried, exactly once",
        )
    }

    @Test
    fun anOrdinaryArmOnAFreeLeaseStillStartsNothing() = runBlocking {
        // The control for the fixture above: without it, a change that
        // started a connect on every tick would look correct.
        var next = 0L
        val ownership = ConnectOwnership(nextToken = { ++next })
        val starts = SafeSteps()
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { starts.add(it); true },
            intervalMs = 40L,
        )

        recovery.arm("nothing_is_wrong")
        delay(300)

        assertTrue(
            starts.isEmpty(),
            "an unblocked lease with no owed start needs no connect: $starts",
        )
    }

    @Test
    fun aLateCloseRunsTheWholeDeferredHalfExactlyOnce() = runBlocking {
        // End to end, through the SAME function production calls.
        //
        // The earlier version of this fixture supplied its own callback
        // whose "release" and "successor" were `steps.add(...)`, so a
        // stale obligation or a double start in production stayed
        // invisible. It drove nothing that ships.
        val steps = SafeSteps()
        var next = 0L
        val ownership = ConnectOwnership(nextToken = { ++next })
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)

        var wedged = true
        val permit = policy.acquirePermit(TransportKind.Direct, 0L) {
            if (wedged) error("close failed") else steps.add("socket_closed")
        }
        assertNotNull(permit)
        permit.useToOpen { }

        val epoch = policy.requestMode(PrivacyMode.Ghost)
        assertTrue(policy.state.value.stillOpen > 0, "precondition: the close failed")

        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { steps.add("bare_start"); true },
            intervalMs = 40L,
            sweepPending = { !policy.retryPending().isComplete },
            finishDeferredSwitch = { owedEpoch ->
                settleDeferredPrivacySwitch(
                    epoch = owedEpoch,
                    coordinator = policy,
                    retryTeardown = {
                        val quiesced =
                            when (ownership.handOver("deferred_privacy_settlement")) {
                                is Handover.Quiesced, Handover.NothingToStop -> true
                                else -> false
                            }
                        if (quiesced) steps.add("released")
                        TeardownAttempt(walkQuiesced = quiesced, confirmed = quiesced)
                    },
                    startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
                ).isDischarged
            },
        )

        recovery.armDeferredSettlement("privacy_switch_incomplete", epoch = epoch)

        delay(200)
        assertTrue(
            steps.none { it == "released" },
            "the release must wait for the socket: $steps",
        )
        assertEquals(PrivacyModeStatus.Blocked, policy.state.value.status)

        wedged = false
        val deadline = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < deadline &&
            policy.state.value.status != PrivacyModeStatus.Applied
        ) {
            delay(25)
        }

        assertEquals(
            PrivacyModeStatus.Applied,
            policy.state.value.status,
            "the late close must finish the switch: $steps",
        )
        assertEquals(PrivacyMode.Ghost, policy.state.value.effective)
        assertTrue(steps.contains("socket_closed"), "the socket really closed: $steps")
        assertTrue(steps.contains("released"), "and the subsystems were released: $steps")
        assertEquals(
            1,
            steps.count { it == "successor_started" },
            "exactly one successor: $steps",
        )
        assertTrue(
            steps.none { it == "bare_start" },
            "and the lease recovery did NOT start a second one beside it: $steps",
        )
        assertTrue(
            steps.indexOf("released") < steps.indexOf("successor_started"),
            "released before the successor started: $steps",
        )
    }

    @Test
    fun aSettlementFromASupersededEpochDoesNothing() = runBlocking {
        // The stale-obligation case, through the production function: a
        // timer armed by switch A must not release, complete or start
        // anything once switch B has taken over.
        val steps = SafeSteps()
        val policy = PrivacyModeCoordinator(PrivacyMode.Standard)
        val staleEpoch = policy.requestMode(PrivacyMode.Ghost)
        policy.requestMode(PrivacyMode.Private)

        val outcome = settleDeferredPrivacySwitch(
            epoch = staleEpoch,
            coordinator = policy,
            retryTeardown = {
                steps.add("teardown_retried")
                TeardownAttempt(walkQuiesced = true, confirmed = true)
            },
            startSuccessor = { steps.add("successor_started"); HandoffRecovery.SuccessorOutcome.Started },
        )

        assertEquals(SettlementPhase.Superseded, outcome.phase)
        assertTrue(steps.isEmpty(), "a superseded obligation touches nothing: $steps")
        assertEquals(PrivacyMode.Private, policy.state.value.requested)
        assertEquals(
            PrivacyMode.Standard,
            policy.state.value.effective,
            "and it certainly commits no mode",
        )
    }

    @Test
    fun missingSettlementWiringIsNotTreatedAsSuccess() = runBlocking {
        // Fail-closed: deleting the production wiring must not silently
        // drop the deferred half. Without a callback the obligation is
        // simply never discharged, and the timer keeps it.
        var next = 0L
        val ownership = ConnectOwnership(nextToken = { ++next })
        val recovery = HandoffRecovery(
            ownership = ownership,
            scope = scope(),
            startOneOrdinaryConnect = { true },
            intervalMs = 40L,
        )

        recovery.armDeferredSettlement("privacy_switch_incomplete", epoch = 1L)
        delay(300)

        assertTrue(
            recovery.isArmed(),
            "an unwired settlement stays owed rather than reporting itself done",
        )
        assertTrue(
            recovery.standDown("test_teardown") is HandoffRecovery.StandDown.SettlementOwed,
            "and a shutdown may not discard it: it is handed to the shutdown, which " +
                "is the only thing that can finish the switch and lower the fence",
        )
    }

    @Test
    fun aParkedMigrationIsRefusedOnceTheUserHasChosen() = runBlocking {
        // startup legacy=Standard -> migration parks -> user picks Ghost
        // -> the migration lands. It must write nothing: the running
        // process would look right and the next start would come up
        // Standard.
        val written = SafeList<PrivacyMode>()
        val policy = PrivacyModeCoordinator(
            initialMode = PrivacyMode.Standard,
            persistRequested = { mode -> written.add(mode) },
        )

        policy.requestMode(PrivacyMode.Ghost)

        val applied = policy.migrateStoredMode(PrivacyMode.Standard, expectedEpoch = 0L)

        assertFalse(applied, "a migration from the startup epoch is stale now")
        assertFalse(
            written.contains(PrivacyMode.Standard),
            "and it must not have written the old mode: $written",
        )
        assertEquals(PrivacyMode.Ghost, policy.state.value.requested)
    }

    @Test
    fun aMigrationThatBeatsTheUserIsWritten() = runBlocking {
        // The control.
        val written = SafeList<PrivacyMode>()
        val policy = PrivacyModeCoordinator(
            initialMode = PrivacyMode.Standard,
            persistRequested = { mode -> written.add(mode) },
        )

        assertTrue(policy.migrateStoredMode(PrivacyMode.Ghost, expectedEpoch = 0L))
        assertEquals(listOf(PrivacyMode.Ghost), written.toList())
    }
}
