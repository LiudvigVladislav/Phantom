// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.service

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N1-F3 — the retry wiring must stay wired, and the receiver must stay
 * out of the connect business.
 *
 * [ConnectRetryScheduler] is unit-tested directly and thoroughly; what
 * those tests cannot see is whether the service actually calls it, or
 * whether the receiver goes back to deciding for itself. Both are
 * source-level facts, and both are exactly what regressed into the
 * original defect: the receiver deferred to a service cadence that did
 * not exist.
 *
 * Recorded honestly as a tripwire: it proves the wiring is written, not
 * that it behaves. Constructing the service is not practical in a unit
 * test, which is the same reason the F-1b commit-wiring tripwire exists.
 */
class ConnectRetryWiringTest {

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

    private val serviceSource: String
        get() = source(
            "apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt",
        )

    private val receiverSource: String
        get() = source(
            "apps/android/src/androidMain/kotlin/phantom/android/service/PhantomWakeupReceiver.kt",
        )

    // ── the service is the retry owner ───────────────────────────────

    @Test
    fun the_service_arms_a_retry_when_the_chain_is_exhausted() {
        val code = serviceSource
        val branch = RetryWiringSourceScanner.blockAfter(
            code,
            "catch (e: NoTransportReachableException)",
        )
        assertTrue(branch != null, "the AllFailed catch is gone or was renamed")
        assertTrue(
            "armConnectRetry" in branch!!,
            "the chain-exhausted path must arm a retry. Without it this branch " +
                "logs, releases the CAS and returns -- which is the whole defect: " +
                "nothing else retries when the network has not changed.",
        )
    }

    /**
     * R-1. The one connect exit that must NOT arm the ladder.
     *
     * Every other exit here ends with a transport that is merely not
     * connected, and trying again later is right. An unsettled tor
     * lifecycle ends with a daemon nobody confirmed gone, or a host still
     * holding its threads. A timer would walk straight back into a start
     * the owner refuses by construction, and every pass would leave one
     * more unreleased host behind.
     *
     * The clause must also come BEFORE the catch-all, or the catch-all arms
     * it anyway and the exit is decorative.
     */
    @Test
    fun an_unsettled_tor_lifecycle_does_not_arm_a_retry() {
        val code = serviceSource
        val branch = RetryWiringSourceScanner.blockAfter(
            code,
            "catch (unsettled: TorLifecycleUnsettled)",
        )
        assertTrue(
            branch != null,
            "the unsettled-lifecycle exit is gone or was renamed, so an " +
                "unconfirmed daemon now falls into the catch-all -- which arms " +
                "a retry",
        )
        assertFalse(
            "armConnectRetry" in branch!!,
            "an unsettled lifecycle must not arm the ladder: $branch",
        )
        assertTrue(
            "releaseConnectOwnership" in branch,
            "it must still let go of the lease, or nothing else can ever claim it",
        )

        val unsettledAt = code.indexOf("catch (unsettled: TorLifecycleUnsettled)")
        val catchAllAt = code.indexOf("catch (t: Throwable)", unsettledAt - 1)
        assertTrue(
            unsettledAt in 0 until catchAllAt,
            "the unsettled exit must precede the catch-all, or it never runs",
        )
    }

    @Test
    fun a_successful_outer_connect_resets_the_backoff() {
        assertTrue(
            "retryScheduler.onOuterConnectSucceeded()" in serviceSource,
            "without the reset the ladder would keep climbing across successful " +
                "connects and a later failure would wait 15 minutes for no reason",
        )
    }

    @Test
    fun shutdown_cancels_the_pending_retry() {
        val branch = RetryWiringSourceScanner.blockAfter(serviceSource, "fun onDestroy()")
        assertTrue(branch != null, "onDestroy is gone or was renamed")
        assertTrue(
            "retryScheduler.invalidate" in branch!!,
            "an explicit stop must not leave a timer that restarts the service later. " +
                "Cancelling the job alone is not enough -- a timer already past its " +
                "delay could still claim -- so the epoch must be bumped.",
        )
    }

    @Test
    fun a_network_rewalk_invalidates_the_pending_retry() {
        val code = serviceSource
        assertTrue(
            "EXTRA_REWALK_RESTART" in code,
            "the rewalk re-entry path is gone or was renamed",
        )
        assertTrue(
            "cancelPendingRetry" in code,
            "the rewalk owns recovery from that point; both driving a chain walk " +
                "is the overlap this round exists to prevent",
        )
    }

    @Test
    fun the_rewalk_hands_the_lease_over_in_the_required_order() {
        // R-N1.16 P1-1. The order is the contract, not a detail:
        //   1. invalidate the pending retry, so it cannot drive a second
        //      walk or claim mid-handover;
        //   2. hand the lease over, which cancels the running walk and
        //      WAITS for it;
        //   3. only then may anyone claim.
        // Reversing 2 and 3 is precisely the defect that was found: a
        // successor entering while the displaced walk was still running.
        val code = serviceSource
        val cancelRetry = code.indexOf("cancelPendingRetry(")
        val handOver = code.indexOf("connectOwnership.handOver(")
        val claim = code.indexOf("connectOwnership.claim(")

        assertTrue(cancelRetry >= 0, "the pending retry is no longer invalidated")
        assertTrue(handOver >= 0, "the rewalk no longer hands the lease over")
        assertTrue(claim >= 0, "the ownership claim is gone or was renamed")

        assertTrue(
            cancelRetry < handOver,
            "the pending retry must be invalidated BEFORE the handover starts, or a " +
                "timer past its delay can claim while the handover is joining",
        )
        assertTrue(
            handOver < claim,
            "the lease must be handed over BEFORE anyone claims it",
        )
    }

    @Test
    fun the_lease_and_its_walk_are_taken_in_one_call() {
        // R-N1.16 P1-1. Claiming the lease and registering the walk used
        // to be two calls, and a handover could slip between them: it
        // captured `walk == null`, concluded there was nothing to stop,
        // and released the slot to a successor while the first walk was
        // still starting.
        //
        // The API no longer permits the split, and this is the tripwire
        // that keeps it that way.
        val code = serviceSource
        assertTrue(
            "attachWalk" !in code,
            "registering a walk separately from claiming the lease reopens the " +
                "window review R-N1.16 found",
        )
        val claimBlock = RetryWiringSourceScanner.blockAfter(code, "connectOwnership.claim(")
        assertTrue(claimBlock != null, "the ownership claim is gone or was renamed")
        assertTrue(
            "cancel" in claimBlock!! && "join" in claimBlock,
            "the claim must carry the handle that can cancel and join this walk; " +
                "block was: $claimBlock",
        )
    }

    @Test
    fun the_rewalk_coordinator_hands_over_before_it_releases() {
        // R-N1.16 P1-2. The service's own statement order is not enough:
        // the coordinator released the transport before it ever asked the
        // service to restart, so the handover ran after Tor and Xray were
        // already stopped. The ordering that matters is in the
        // coordinator, and its behavioural proof lives in
        // TransportRewalkCoordinatorTransactionTest.
        val coordinator = source(
            "apps/android/src/androidMain/kotlin/phantom/android/transport/" +
                "TransportRewalkCoordinator.kt",
        )
        val handOver = coordinator.indexOf("handOverConnectOwnership(")
        val release = coordinator.indexOf("releaseTransport()")

        assertTrue(handOver >= 0, "the coordinator no longer hands the lease over")
        assertTrue(release >= 0, "the release step is gone or was renamed")
        assertTrue(
            handOver < release,
            "the lease must be handed over before the transport is released, or " +
                "subsystems are torn down under a walk that is still using them",
        )

        val container = source(
            "apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt",
        )
        assertTrue(
            "handOverConnectOwnership" in container,
            "the coordinator's handover is not wired to the real lease, so it " +
                "silently defaults to doing nothing",
        )
    }

    @Test
    fun a_fail_closed_lease_has_a_recovery_path_that_can_actually_run() {
        // R-N1.16 P1-3. The package used to claim the alarm heartbeat
        // lifted a blocked lease. It could not: the nudge branch returns
        // before the claim path, the rewalk has invalidated the retry
        // slot, and after release the manager sits in Idle, which the
        // receiver does not nudge on at all.
        val code = serviceSource
        assertTrue(
            "handoffRecovery.arm(" in code,
            "there is no longer a recovery mechanism a blocked lease can rely on",
        )
        val timeoutBranch = RetryWiringSourceScanner.blockAfter(
            code,
            "is Handover.TimedOut ->",
        )
        assertTrue(timeoutBranch != null, "the handover-timeout branch is gone")
        assertTrue(
            "handoffRecovery.arm" in timeoutBranch!!,
            "going fail-closed without arming recovery leaves the app dark for good",
        )
        val nudge = RetryWiringSourceScanner.blockAfter(
            code,
            "private fun onExternalRetryNudge",
        )
        assertTrue(nudge != null, "the nudge handler is gone or was renamed")
        val recoverAt = nudge!!.indexOf("handoffRecovery.onSignal")
        val schedulerAt = nudge.indexOf("retryScheduler.claim(")
        assertTrue(
            recoverAt >= 0,
            "a nudge that arrives while the lease is blocked must be able to lift it",
        )
        assertTrue(schedulerAt >= 0, "the ordinary nudge path is gone")
        assertTrue(
            recoverAt < schedulerAt,
            "recovery must be attempted BEFORE the scheduler is consulted: after a " +
                "rewalk the retry slot is invalidated, so a nudge that asks the " +
                "scheduler first is refused and the blocked lease is never lifted",
        )
    }

    @Test
    fun a_blocked_lease_is_retried_on_every_allowed_signal() {
        // R-N1.16 review item 3. Fail-closed keeps the lease when a
        // handover cannot confirm the previous walk stopped. Something
        // has to be able to lift that, or a stable network leaves the
        // app dark permanently instead of briefly -- which is not the
        // trade that was agreed.
        //
        // The recovery attempt must come BEFORE the claim, because the
        // claim is what the block refuses.
        val code = serviceSource
        val recover = code.indexOf("handoffRecovery.onSignal(")
        val claim = code.indexOf("connectOwnership.claim(")

        assertTrue(recover >= 0, "a blocked lease can no longer be recovered at all")
        assertTrue(claim >= 0, "the ownership claim is gone or was renamed")
        assertTrue(
            recover < claim,
            "recovery must be attempted before the claim it exists to unblock",
        )
    }

    @Test
    fun the_service_drives_the_shared_recovery_component() {
        // R-N1.16 follow-up. The behavioural fixtures prove HandoffRecovery
        // works; this proves the service is the thing using it, rather
        // than keeping a second hand-rolled timer beside it.
        val code = serviceSource
        assertTrue(
            "phantom.core.transport.HandoffRecovery" in code,
            "the service no longer imports the recovery component",
        )
        assertEquals(
            1,
            Regex("""= HandoffRecovery\(""").findAll(code).count(),
            "there must be exactly one recovery instance; two would each arm their " +
                "own timer against the same lease",
        )
        assertTrue(
            "handoffRecovery.arm(" in code && "handoffRecovery.onSignal(" in code &&
                "handoffRecovery.standDown(" in code && "handoffRecovery.resume(" in code,
            "the service must arm, signal, stand down and resume through the " +
                "component -- `cancel` is gone because a shutdown and a service " +
                "recreation are different events and only one of them may restart",
        )
        assertTrue(
            "handoffRecovery.noteShutdownSettlement(" in code,
            "a shutdown that takes the outstanding obligation must report what its " +
                "own teardown achieved, or the obligation is lost with the instance",
        )
        assertTrue(
            "handoffRecoveryJob" !in code,
            "a hand-rolled recovery timer has come back beside the component",
        )
    }

    @Test
    fun the_service_opens_no_socket_outside_the_permit() {
        // R-N1.16 P1, second window. connect() returning proves the
        // policy was current at the instant of publication and nothing
        // more; a privacy switch can land while this coroutine is on its
        // way to the socket.
        //
        // R-N1.17: was `transportManager.isStillCurrent(connected)`, a
        // read-check. That method is gone and deliberately not replaced -
        // asking "is this still valid" and then acting on the answer is
        // the shape this round removed. The service registers the socket
        // with the authority instead, and the behaviour is proven in
        // TransportActivationTest and TransportSessionTest.
        //
        // This fixture used to assert that `activate` appeared before the
        // `connected.socksPort` read. That proxy died when the WSS open
        // moved INSIDE TransportSession: reading a port into a local is
        // not opening anything, and the activation now legitimately runs
        // after it. An ordering assertion whose subject is no longer the
        // open proves nothing, so it is replaced by the property that
        // actually matters and that only source can show - there is no
        // second way to open a socket.
        val code = serviceSource
        val sessionAt = code.indexOf("val session = TransportSession(")
        val openAt = code.indexOf("container.transport.connect(")
        val handedToPermitAt = code.indexOf("openSocket = { session.start() }")

        assertTrue(sessionAt >= 0, "the session no longer exists - fixture is stale")
        assertTrue(handedToPermitAt >= 0, "the permit no longer starts the session")
        assertEquals(
            1,
            Regex(Regex.escape("container.transport.connect(")).findAll(code).count(),
            "exactly one place may open the WSS socket",
        )
        assertEquals(
            1,
            Regex(Regex.escape("session.start()")).findAll(code).count(),
            "and exactly one place may start it - a second caller would open a " +
                "socket the permit never covered",
        )
        assertTrue(
            sessionAt < openAt && openAt < handedToPermitAt,
            "the open must sit inside the session's own loop, and that session must " +
                "be started by the permit's openSocket - not beside it",
        )
    }

    @Test
    fun the_session_close_neither_flushes_nor_leaves_the_loop_running() {
        // R-N1.17 P1, and source-level for the reason the class kdoc
        // already gives: constructing the service is not practical here.
        // What IS checkable is which close the session is handed, and that
        // is the whole defect - `disconnect()` routes to
        // `teardownAndJoin(flushBeforeClose = true)`, so it pushed queued
        // payloads through the very socket the switch was closing, and it
        // does not cancel the reconnect loop, which lives in the
        // transport's own scope. The permit was then released over a live
        // socket that no register knew about.
        //
        // The behavioural half - that a session cannot see past a close
        // which stops nothing - is in CampaignGapTest.
        val code = serviceSource
        assertTrue(
            "container.transport.disconnect()" !in code,
            "the flushing disconnect must not appear in the service at all: a " +
                "privacy switch that flushes sends the user's queued payloads over " +
                "the transport they just asked to stop",
        )
        val block = RetryWiringSourceScanner.blockAfter(code, "closeTransport = {")
        assertTrue(block != null, "the session's close is gone or was renamed")
        assertTrue(
            "disconnectAndConfirm(" in block!!,
            "the close must CONFIRM the socket close, not only the loop join: " +
                "disconnectAndJoin says nothing about the session and client closes, " +
                "which are dispatched to a cleanup scope; block was: $block",
        )
        assertTrue(
            "teardown.confirmed" in block,
            "and it must consult `confirmed`, not `loopJoined`; block was: $block",
        )
        assertTrue(
            "SessionNotStoppedException" in block,
            "a join it cannot confirm is not a close, and must fail the permit's " +
                "close rather than pass as one; block was: $block",
        )
    }

    @Test
    fun the_shutdown_disconnect_does_not_run_in_the_scope_being_cancelled() {
        // The original defect: the disconnect was `launch`ed into
        // `serviceScope` and that scope was cancelled on the very next
        // line, so the teardown was cancelled before it could run.
        //
        // The fix is NOT to join it there - an earlier revision did that
        // and put a multi-second block on the main thread. It is to run it
        // in the scope that outlives the instance, which
        // `the_shutdown_does_not_wait_on_the_main_thread` checks. What is
        // left for this one is the original property, stated so it cannot
        // be satisfied by putting the work back where it dies.
        val code = serviceSource
        val destroy = RetryWiringSourceScanner.blockAfter(code, "fun onDestroy()")
        assertTrue(destroy != null, "onDestroy is gone or was renamed")
        val at = destroy!!.indexOf("disconnectAndConfirm(")
        assertTrue(at >= 0, "the shutdown no longer disconnects")
        val enclosing = destroy.substring(0, at)
        assertTrue(
            "serviceScope.launch" !in enclosing,
            "the disconnect must not be launched into the scope this method " +
                "cancels a few lines later - it would be cancelled before it ran",
        )
        assertTrue(
            destroy.indexOf("recoveryScope.launch") < at,
            "it belongs in the scope that outlives this instance",
        )
    }

    @Test
    fun a_refused_permit_release_arms_a_sweep() {
        // The behavioural half is in CampaignGapTest: a permit over a
        // socket nobody confirmed closed stays registered. What only
        // source shows is that this service ACTS on the refusal instead of
        // discarding it - an obligation with no driver is the shape this
        // round has removed everywhere else.
        val code = serviceSource
        val block = RetryWiringSourceScanner.blockAfter(code, "session.afterCompletion {")
        assertTrue(block != null, "the release site is gone or was renamed")
        assertTrue(
            "if (!usePermit.closeAndRelease(" in block!!,
            "the socket must be closed THROUGH the permit, and the result consulted " +
                "- releasing on the owner's word is the claim that cannot be checked; " +
                "block was: $block",
        )
        assertTrue(
            "handoffRecovery.arm(" in block,
            "a refused release must arm a sweep, or nothing retries the close; " +
                "block was: $block",
        )
    }

    @Test
    fun the_shutdown_does_not_wait_on_the_main_thread() {
        // The disconnect must not be `runBlocking` inside onDestroy: that
        // is a multi-second block on the main thread, which is ANR
        // territory. It belongs in the scope that outlives the instance,
        // with its result owned rather than logged.
        val code = serviceSource
        val destroy = RetryWiringSourceScanner.blockAfter(code, "fun onDestroy()")
        assertTrue(destroy != null, "onDestroy is gone or was renamed")
        val at = destroy!!.indexOf("disconnectAndConfirm(")
        assertTrue(at >= 0, "the shutdown no longer disconnects")
        assertTrue(
            "onlyIfIdentity = ownedConnection" in destroy,
            "the deferred cleanup must be bound to the connection THIS instance " +
                "owned, or it cancels whatever the successor has since started",
        )
        val readIdentity = destroy.indexOf("container.transport.teardownIdentity")
        assertTrue(
            readIdentity in 0 until destroy.indexOf("recoveryScope.launch"),
            "the identity must be read while it is still ours - reading it inside " +
                "the deferred launch would read the successor's",
        )
        val before = destroy.substring(0, at)
        assertTrue(
            before.lastIndexOf("recoveryScope.launch") > before.lastIndexOf("runBlocking"),
            "the disconnect must run in the surviving recovery scope, not in a " +
                "runBlocking on the main thread",
        )
        assertTrue(
            "handoffRecovery.arm(" in destroy.substring(at),
            "and a join it could not confirm must arm a sweep rather than only log",
        )
    }

    @Test
    fun the_privacy_switch_tells_the_policy_gate() {
        // The gate's epoch only moves if the switch says so. A switch
        // that forgot would leave every in-flight walk looking current.
        // R-N1.17: the epoch moved from TransportManager to the single
        // authority, so the switch requests a mode rather than notifying
        // a gate.
        val container = source(
            "apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt",
        )
        // Scoped to setPrivacyMode's own body. Searching the whole file
        // stopped meaning anything once the deferred settlement started
        // re-running the teardown too - its call sits EARLIER in the file
        // than the switch this fixture is about, so a whole-file indexOf
        // compares two unrelated statements.
        val from = container.indexOf("suspend fun setPrivacyMode(")
        val to = container.indexOf("suspend fun applyPrivacyModeFromOnboarding(")
        assertTrue(from >= 0 && to > from, "setPrivacyMode was renamed - fixture is stale")
        val body = container.substring(from, to)

        val bump = body.indexOf("privacyModeCoordinator.requestMode(mode)")
        val teardown = body.indexOf("runPrivacyModeTeardown(")

        assertTrue(bump >= 0, "the privacy switch no longer goes through the authority")
        assertTrue(teardown >= 0, "the teardown call is gone or was renamed")
        assertTrue(
            bump < teardown,
            "the epoch must move BEFORE anything is torn down, or a walk can publish " +
                "under the old policy while the teardown is running",
        )
    }

    @Test
    fun revoking_the_token_alone_is_no_longer_a_handover() {
        // The API that made the defect expressible is gone. forceRelease
        // wrote `owner = null` and returned, leaving the displaced
        // coroutine running inside TransportManager.connect().
        val code = serviceSource
        assertTrue(
            "forceRelease" !in code,
            "the service must not revoke a token without stopping the walk that " +
                "holds it -- that was P1-1",
        )
        assertTrue(
            "runBlocking { connectOwnership" !in code.replace("  ", " "),
            "and the handover must not run on the main thread: it joins, and " +
                "joining in onStartCommand is an ANR",
        )
    }

    @Test
    fun a_handover_timeout_is_fail_closed() {
        // Availability yields to transport integrity. If we cannot prove
        // the previous walk stopped, no successor starts.
        val branch = RetryWiringSourceScanner.blockAfter(
            serviceSource,
            "is Handover.TimedOut ->",
        )
        assertTrue(branch != null, "the handover-timeout branch is gone or was renamed")
        assertTrue(
            "return@launch" in branch!!,
            "a handover that could not confirm quiescence must NOT fall through to " +
                "the claim below it",
        )
        assertTrue(
            "claim" !in branch,
            "and it certainly must not claim the lease itself",
        )

        val rawService = File(
            repoRoot,
            "apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt",
        ).readText()
        assertTrue(
            "NETWORK_TRACE handoff_timeout" in rawService,
            "the refusal must be a structured, greppable event rather than silence",
        )
    }

    // ── the receiver only delegates ──────────────────────────────────

    @Test
    fun the_receiver_delegates_and_does_not_reconnect_on_all_failed() {
        val branch = RetryWiringSourceScanner.blockAfter(
            receiverSource,
            "managerState is phantom.core.transport.ManagerState.AllFailed",
        )
        assertTrue(branch != null, "the receiver's AllFailed branch is gone or was renamed")

        assertTrue(
            "sendRetryNudge" in branch!!,
            "the receiver must deliver a nudge. Returning silently here is the " +
                "original defect: it deferred to a service cadence that did not exist.",
        )
        assertTrue(
            "forceReconnect" !in branch,
            "and it must NOT force a reconnect -- that tears down the OkHttp engine " +
                "an in-flight attempt is using, which is why the skip existed at all",
        )
        assertTrue(
            "transportManager.connect" !in branch,
            "nor may it start a chain walk itself: it has no generation, no CAS and " +
                "no view of whether a walk is already running",
        )
    }

    @Test
    fun the_nudge_is_a_signal_not_a_connect() {
        val code = serviceSource
        val branch = RetryWiringSourceScanner.blockAfter(code, "EXTRA_RETRY_NUDGE, false")
        assertTrue(branch != null, "the service does not accept a retry nudge")
        assertTrue(
            "onExternalRetryNudge" in branch!!,
            "a nudge must go through the scheduler's claim, not straight to a connect",
        )
        assertTrue(
            "transportManager.connect" !in branch,
            "the nudge branch must not call connect() directly -- due time, epoch and " +
                "single-flight are decided by the scheduler",
        )
    }

    // ── single entry point ───────────────────────────────────────────

    @Test
    fun there_is_still_exactly_one_production_caller_of_connect() {
        // The audit's central finding was that recovery had no owner.
        // The fix must not answer that by creating a second owner.
        val androidMain = File(repoRoot, "apps/android/src/androidMain")
        val shared = File(repoRoot, "shared")
        val callers = mutableListOf<String>()
        for (root in listOf(androidMain, shared)) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.path.replace('\\', '/').contains("/src/commonTest/") }
                .filterNot { it.path.replace('\\', '/').contains("Test/") }
                .forEach { f ->
                    val code = RetryWiringSourceScanner.codeOnly(f.readText())
                    if ("transportManager.connect()" in code) callers += f.name
                }
        }
        assertEquals(
            listOf("PhantomMessagingService.kt"),
            callers.distinct().sorted(),
            "TransportManager.connect() must have exactly one production caller; " +
                "found $callers",
        )
    }

    @Test
    fun the_transport_manager_did_not_grow_its_own_retry_loop() {
        // Contract item 1: connect() stays one walk. A loop or timer in
        // there would make the walk unbounded and put a second cadence
        // below the one this round just gave the service.
        val code = RetryWiringSourceScanner.codeOnly(
            File(
                repoRoot,
                "shared/core/transport/src/commonMain/kotlin/phantom/core/transport/TransportManager.kt",
            ).readText(),
        )
        for (forbidden in listOf("ConnectRetryScheduler", "armAfterFailure", "while (true)")) {
            assertTrue(
                forbidden !in code,
                "TransportManager must remain a single chain walk; found `$forbidden`",
            )
        }
    }

    @Test
    fun the_retry_re_enters_through_the_normal_connect_path() {
        // Contract item 7: every attempt re-reads the CURRENT privacy
        // mode. That is guaranteed structurally rather than by a cache
        // check -- the retry starts the service again, so it lands in
        // onStartCommand and reaches TransportManager.connect(), which
        // reads preferences.privacyMode on entry. Ghost therefore stays
        // Tor-only across a retry with no downgrade path.
        //
        // A mutation DOES cover this: MUT-F3-CACHE-STRATEGY introduces a
        // cache into TransportManager.connect() and drops the two Ghost /
        // Private fixtures in PrivacyModeRereadOnRetryTest. An earlier
        // version of this note claimed no such mutation could exist
        // "because there is no cache to disable" -- which had it backwards:
        // a mutation introduces the defect, so the absence of a cache is
        // the reason to write one, not a reason to skip it. This fixture
        // pins the structure; the mutation proves the behaviour.
        val branch = RetryWiringSourceScanner.blockAfter(serviceSource, "fun startSelfForRetry")
        assertTrue(branch != null, "startSelfForRetry is gone or was renamed")

        // Review P1-1. The first version of this fixture asserted the
        // OPPOSITE: it required EXTRA_REWALK_RESTART in the retry path,
        // and so pinned the defect in place. That extra clears the CAS
        // unconditionally, which is safe only after the rewalk
        // coordinator has disconnected and released. A retry has not, so
        // force-opening the CAS could start a second concurrent
        // TransportManager.connect().
        assertTrue(
            "EXTRA_REWALK_RESTART" !in branch!!,
            "a retry must NOT borrow the rewalk restart extra: that clears the CAS " +
                "without any quiesce and can start a second concurrent chain walk",
        )
        assertTrue(
            "EXTRA_RETRY_ATTEMPT" in branch,
            "the retry needs its own intent so it goes through the CAS like any " +
                "other start and is refused while a walk is running",
        )
        assertTrue(
            "startForegroundService" in branch || "startService" in branch,
            "the retry must go through a service start, not an inline connect",
        )

        val code = serviceSource
        for (forbidden in listOf(
            "var cachedStrategy",
            "var lastStrategy",
            "var cachedTransport",
            "var lastConnectedTransport",
        )) {
            assertTrue(
                forbidden !in code,
                "nothing may cache a strategy or a transport across attempts; found `$forbidden`",
            )
        }
    }


    @Test
    fun an_occupied_cas_refuses_the_retry_without_bypassing_the_guard() {
        // Review P1-1, the other half.
        //
        // The first version of this fixture demanded that a CAS-refused
        // retry be "put back" via restoreAfterFailedStart. That was
        // wrong, and worth recording rather than quietly deleting:
        //
        //  - this launch never held a grant, so there is no ClaimToken to
        //    restore with, and restore is token-guarded precisely so that
        //    state cannot be fabricated from nothing;
        //  - the retry is not lost either. A walk is already running, and
        //    that walk owns what happens next: it arms on its own
        //    AllFailed exit, or resets the ladder if it connects. The
        //    alarm heartbeat remains a backstop on top of that.
        //
        // What must hold is narrower and checkable: the retry path does
        // not force the CAS open, and it does not start a walk of its own.
        val branch = RetryWiringSourceScanner.blockAfter(
            serviceSource,
            "if (claimed == null)",
        )
        assertTrue(branch != null, "the ownership-claim guard is gone or was renamed")
        assertTrue(
            "forceRelease" !in branch!!,
            "a refused retry must never take the slot from the walk that holds it",
        )
        assertTrue(
            "startSelfForRetry" !in branch,
            "nor may it start a walk beside the one already running",
        )
        assertTrue(
            "isRetryAttempt" in branch,
            "the branch must distinguish a retry attempt, or it cannot treat one " +
                "differently from an ordinary duplicate start",
        )

        // The log TEXT cannot be asserted against the stripped code: the
        // scanner removes string literals, which is exactly what makes the
        // wiring assertions above trustworthy. A claim about a literal has
        // to read the raw source, and this is the one place that does --
        // stated rather than smuggled.
        val rawService = File(
            repoRoot,
            "apps/android/src/androidMain/kotlin/phantom/android/service/PhantomMessagingService.kt",
        ).readText()
        assertTrue(
            "RETRY_TRACE attempt_deferred" in rawService,
            "the deferral must be traceable, or a retry vanishing here is invisible",
        )
    }

    @Test
    fun only_the_current_generation_may_arm_a_retry() {
        // Review P1-2. resetConnectStartedIfCurrent knows whether this
        // generation is still the owner; arming ignored that verdict, so
        // a superseded generation could schedule a retry on top of live
        // work -- with a NEWER epoch than the live generation's.
        val code = serviceSource
        // The scanner strips string literals, so the reason argument is
        // gone by the time this sees the call. Assert on what survives:
        // the ownership verdict being passed through.
        val armCalls = Regex("""armConnectRetry\([^)]*\)""").findAll(code)
            .map { it.value }
            .filterNot { it.contains("reason: String") }   // the declaration
            .toList()
        assertEquals(
            2,
            armCalls.size,
            "expected exactly the AllFailed and connect-threw arm sites; got $armCalls",
        )
        assertTrue(
            armCalls.all { "stillOwner" in it },
            "every arm must be gated on still owning the generation; got $armCalls",
        )
        assertTrue(
            "connectGeneration.get()" in code,
            "the live generation must be passed to the scheduler so it can refuse " +
                "a stale arm or claim",
        )
    }

    @Test
    fun rewalk_invalidation_is_ordered_before_the_restart() {
        // Review P2-3. Invalidating on a background coroutine leaves the
        // rewalk and an already-due timer unordered: the timer can win and
        // start a second walk beside the rewalk.
        val branch = RetryWiringSourceScanner.blockAfter(serviceSource, "fun cancelPendingRetry")
        assertTrue(branch != null, "cancelPendingRetry is gone or was renamed")
        assertTrue(
            "runBlocking" in branch!!,
            "the epoch bump must complete before the restart proceeds",
        )
        assertTrue(
            "serviceScope.launch" !in branch,
            "an asynchronous invalidation does not order the two",
        )
    }

    @Test
    fun a_failed_service_start_restores_the_retry() {
        // Review P2-4.
        val branch = RetryWiringSourceScanner.blockAfter(serviceSource, "fun startSelfForRetry")
        assertTrue(branch != null)
        assertTrue(
            "restoreAfterFailedStart" in branch!!,
            "claim() already cleared the slot; a start that throws must put it back " +
                "or every later nudge sees NotPending",
        )
    }


    @Test
    fun ownership_is_one_atomic_claim_and_release() {
        // Review P1. The slot used to be an AtomicBoolean plus a separate
        // generation counter, and releasing read one then wrote the
        // other. That check-then-act let a displaced generation free a
        // slot its successor already held.
        val code = serviceSource
        for (retired in listOf("connectStarted", "resetConnectStartedIfCurrent")) {
            assertTrue(
                retired !in code,
                "`$retired` is the two-atomic mechanism this round replaced; its " +
                    "return means the check-then-act window is back",
            )
        }
        assertTrue(
            "ConnectOwnership(" in code,
            "ownership must go through the single-atomic lease",
        )
        assertTrue(
            "connectOwnership.release(" in code,
            "release must be the lease's compare-and-clear, not a bare write",
        )

        val release = RetryWiringSourceScanner.blockAfter(code, "fun releaseConnectOwnership")
        assertTrue(release != null, "releaseConnectOwnership is gone or was renamed")
        assertTrue(
            "connectGeneration.get()" !in release!!,
            "releasing must not read the generation separately -- that read is one " +
                "half of the race",
        )
    }

    @Test
    fun the_scan_is_not_vacuous() {
        assertTrue(serviceSource.length > 10_000, "service source looks empty")
        assertTrue(receiverSource.length > 5_000, "receiver source looks empty")
        assertTrue("class PhantomMessagingService" in serviceSource)
        assertTrue("class PhantomWakeupReceiver" in receiverSource)
    }
}
