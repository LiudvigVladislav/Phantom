// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import phantom.core.xray.XrayService
import phantom.core.xray.XrayState

/**
 * Adaptive transport selection per ADR-020. Walks the [TransportStrategy]
 * chain implied by [TransportPreferences.privacyMode], starts the matching
 * subsystem, waits for SOCKS readiness, probes end-to-end reachability, and
 * returns the first [ConnectedTransport] that works. Records a hint so the
 * next connect tries the previously-successful path first.
 *
 * Lifecycle: [connect] is one-shot per call. The caller (foreground service)
 * passes the resulting `socksPort` to [RelayTransport.connect] for the actual
 * WebSocket. On disconnect, the foreground service calls [release] which
 * stops both subsystems.
 *
 * Threading: [connect] is a suspending function safe to call from any
 * coroutine context. Sequential per call — no internal concurrency.
 */
class TransportManager(
    private val torServiceProvider: () -> TorService,
    private val xrayServiceProvider: () -> XrayService,
    private val preferences: TransportPreferences,
    private val probe: TransportProbe,
    private val nowMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
    private val log: TransportManagerLog = TransportManagerLog.Noop,
    // Diagnostic-only for now (PR-A1): caller reports whether a system VPN is
    // active at the moment we walk the chain. We just log it. Behavioural
    // changes (e.g. skipping Reality when a VPN is active) are deferred to
    // PR-A2 once the audit confirms whether Reality+VPN is genuinely broken
    // by upstream / Hetzner exit-IP filtering or just slow.
    private val vpnDetector: () -> Boolean = { false },
    /**
     * The single authority for privacy-mode state.
     *
     * R-N1.17: this class used to keep its own policy epoch while the
     * effective mode lived in the Android container. Two owners of one
     * fact can disagree, which is the shape of every defect this round
     * closed. Required, with no default: a default would silently give
     * each construction site its own epoch, which is the disagreement
     * back again.
     */
    internal val policy: PrivacyModeCoordinator,
    /**
     * Where a mandatory teardown attempt RUNS, deliberately not the
     * caller's scope.
     *
     * The budget bounds how long a caller waits for a stop; it must never
     * bound the stop. Cancelling a teardown is not a teardown: it can end
     * the coroutine before the resource operation ever runs and leave
     * nothing behind that will finish it. And a blocking library close --
     * `LibXray.stopXray()` is one -- does not return because a coroutine
     * was cancelled, so a timeout wrapped around it bounds nothing at all
     * and the caller waits anyway.
     *
     * Issued here, the attempt survives the wait: the waiter comes back on
     * time, the OTHER subsystem still gets its turn, and the work finishes
     * when the library finally returns.
     *
     * The dispatcher is deliberately NOT inherited from the caller. The
     * shutdown path calls `release()` inside a `runBlocking` on the main
     * thread; issuing a blocking library close onto that dispatcher would
     * put the block straight back where it must never be.
     *
     * A test that runs on virtual time has to pass its own scope, because a
     * bounded wait on the test clock and work on a real one are two clocks
     * and the wait always wins.
     */
    private val teardownWorkers: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Test seam, a no-op in production. Runs inside the Xray teardown
     * worker after its record has been withdrawn and the gate handed back,
     * and before the worker completes.
     *
     * That gap is exactly where a new request must NOT be answered by this
     * attempt, and it is invisible from outside: by the time a caller can
     * observe the close returning, the worker has usually finished too.
     * The same shape as [TorLifecycleOwner]'s own parking seam.
     */
    private val afterXrayStopReleased: suspend () -> Unit = {},
) {
    /**
     * The teardown attempts this manager currently owns.
     *
     * One record per subsystem, not one per call. A budget elapsing ends a
     * WAIT and nothing else: the attempt is still running and still owned,
     * so the next caller joins it. Without this, every expired wait would
     * queue another close behind a subsystem that has not finished the
     * first, and "bounded by how often a teardown is issued" would bound the
     * rate of growth rather than the number of attempts held.
     */
    private val teardownLock = Any()

    /**
     * Orders a start against a teardown on the Xray subsystem.
     *
     * Checking for an outstanding claim and then starting is two steps, and
     * a claim issued between them is admitted by neither: the check saw
     * nothing, and the start had already been allowed. Whichever of the two
     * arrives first must therefore TAKE something the other cannot have.
     *
     * A claim takes it at claim time -- its worker begins undispatched, so a
     * free gate is acquired on the claiming thread before the claim is even
     * returned. A start takes it before touching the library. Neither holds
     * [teardownLock] while doing so, and neither blocks a thread: this
     * suspends.
     */
    private val xrayGate = Mutex()
    private var xrayStop: XrayStopInFlight? = null
    private var torStop: TorStopInFlight? = null

    /**
     * One claimed Xray teardown.
     *
     * Held in a box rather than as a bare `Deferred` so the record can be
     * withdrawn from the field the instant its resource operation ends,
     * while the work itself lives on for whoever is already waiting on it.
     */
    private class XrayStopInFlight {
        lateinit var work: Deferred<Result<Unit>>
    }

    /**
     * One claimed tor teardown: the registration, the wait that follows it,
     * and the attempt both belong to.
     */
    private class TorStopInFlight(
        /** Which generation this attempt is the teardown OF. */
        val generation: Long,
        val work: Deferred<Result<TorStopResponse>>,
    )

    private val _state = MutableStateFlow<ManagerState>(ManagerState.Idle)
    val state: StateFlow<ManagerState> = _state.asStateFlow()

    /**
     * Walk the strategy chain for the current [PrivacyMode] and return the
     * first working transport. On success records the hint + resets the
     * failure counter. On total failure throws [NoTransportReachableException]
     * (also bumps the failure counter).
     */
    /**
     * Serialises ENTRY into the chain walk.
     *
     * R-N1.16 P1-1 backstop. Ownership in the service is what normally
     * keeps a single walk running; this is the second line, so that a
     * path which somehow bypassed the lease still cannot interleave two
     * walks over the same subsystem start/stop and `_state`.
     *
     * [release] is deliberately NOT taken under this lock. A stuck walk
     * must remain stoppable, and a release that queued behind it could
     * never stop anything. Cancellation is what removes a walk from this
     * barrier: `withLock` releases the mutex when the holder is
     * cancelled, so the successor proceeds as soon as the previous walk
     * actually unwinds - which is exactly the ordering the handover
     * waits for.
     */
    private val connectEntry = Mutex()

    /**
     * Test seam, null in production: invoked after a probe succeeds and
     * BEFORE the validate-and-publish critical section.
     *
     * Parking here lets a fixture complete a whole privacy switch - which
     * takes the authority's lock - and then let the walk proceed, proving
     * the publication loses rather than racing it.
     */
    internal var onPolicyPublishSeam: (suspend () -> Unit)? = null

    /**
     * The policy reading a walk starts under.
     *
     * Read from the authority, not from preferences: the epoch and the
     * mode must come from the same place, or a walk can start under a
     * mode the epoch does not describe.
     */
    private suspend fun policySnapshot(): Pair<Long, TransportStrategy> {
        val snapshot = policy.state.value
        return policy.currentEpoch() to TransportStrategy.from(snapshot.requested)
    }

    suspend fun connect(): ConnectedTransport = connectEntry.withLock {
        walkChainOnce()
    }

    private suspend fun walkChainOnce(): ConnectedTransport {
        val (startEpoch, strategy) = policySnapshot()
        val baseChain = reorderChain(strategy)
        val vpnActive = runCatching { vpnDetector() }.getOrDefault(false)
        // Reality runs over the system network stack, so when an Android VPN
        // is active the REALITY-mirrored TLS traffic is tunnelled through the
        // VPN egress before reaching Hetzner. The 2026-05-11 audit cycle
        // (Test #3 + Caddy access-log review) confirmed those packets do not
        // arrive at the relay's edge — Caddy logged zero requests in the
        // probe window. The cause sits below us (VPN-side DPI / MTU /
        // Hetzner ingress policy), and the symptom is a 20 s timeout that
        // adds latency without ever succeeding.
        //
        // Drop Reality from the walked chain when a VPN is active so we
        // skip straight to the next viable transport (Tor for Private/Ghost,
        // Direct→Tor for Standard). The non-VPN path is unchanged and
        // Reality remains the privacy-preferred default there.
        val orderedChain = if (vpnActive) {
            baseChain.filterNot { it == TransportKind.Reality }
                .ifEmpty { baseChain } // safety net; current chains always include Tor
        } else {
            baseChain
        }
        val realityFiltered = vpnActive && baseChain.contains(TransportKind.Reality)
        log.info(
            "connect: mode=${policy.state.value.requested} strategy=$strategy " +
                "ordered=$orderedChain vpnActive=$vpnActive realityFiltered=$realityFiltered",
        )
        log.info(
            "PROBE_TRACE chain_start strategy=$strategy ordered=$orderedChain " +
                "vpnActive=$vpnActive realityFiltered=$realityFiltered",
        )
        // PR-LTE-NETCHANGE1 (2026-05-28): explicit attribution line when
        // Reality was removed from the chain. The chain_start log above
        // surfaces `realityFiltered=true` as a boolean, but a dedicated
        // line with the reason keeps Test #88 Scenario D readable: when
        // "Tor on LTE" happens, the immediate next log line explains
        // WHY Reality was not even attempted. Today the only reason is
        // VPN; if more reasons emerge later (carrier-side block, ADR-
        // motivated suspension), the same line format extends.
        if (realityFiltered) {
            log.info("PROBE_TRACE reality_filtered reason=vpn_active")
        }
        _state.value = ManagerState.Probing(orderedChain.first())

        val failures = mutableListOf<TransportAttemptFailure>()
        for (kind in orderedChain) {
            _state.value = ManagerState.Probing(kind)
            val prepareStartMs = nowMs()
            log.info("PROBE_TRACE prepare_start kind=$kind")
            val socksPort = try {
                val port = prepareTransport(kind)
                val elapsedMs = nowMs() - prepareStartMs
                log.info("PROBE_TRACE prepare_done kind=$kind socksPort=$port elapsedMs=$elapsedMs")
                port
            } catch (unsettled: TorLifecycleUnsettled) {
                // A lifecycle that did not settle is not an unreachable
                // transport. It does not join the failure ladder and it does
                // not become "no path reachable": it leaves the walk so the
                // caller can tell the two apart.
                log.warn(
                    "PROBE_TRACE walk_lifecycle_unsettled kind=$kind " +
                        "generation=${unsettled.attempt.generation} " +
                        "result=${unsettled.result::class.simpleName}",
                )
                throw unsettled
            } catch (t: TimeoutCancellationException) {
                // A prepare budget elapsing is a domain outcome, not a
                // stop signal - Reality rethrows its own timeout from
                // prepareTransport on purpose so the chain advances to
                // the next transport. This clause MUST stay above the
                // cancellation rethrow below, because
                // TimeoutCancellationException IS a CancellationException
                // and would otherwise abort the whole walk.
                recordPrepareFailure(kind, t, prepareStartMs, failures)
                continue
            } catch (ce: CancellationException) {
                // R-N1.16 P1-1: an ownership handover cancels this walk.
                // Swallowing that here turned "stop" into "try the next
                // transport", so a displaced walk kept marching down the
                // chain beside its successor. Cancellation leaves.
                log.warn("PROBE_TRACE walk_cancelled kind=$kind phase=prepare")
                throw ce
            } catch (t: Throwable) {
                recordPrepareFailure(kind, t, prepareStartMs, failures)
                continue
            }
            val outerTimeoutMs = probeTimeoutFor(kind)
            log.info("PROBE_TRACE probe_called kind=$kind socksPort=$socksPort outerTimeoutMs=$outerTimeoutMs")
            val probeStartMs = nowMs()
            val probeOk = try {
                val ok = withTimeout(outerTimeoutMs) { probe.reachable(kind, socksPort) }
                val elapsedMs = nowMs() - probeStartMs
                log.info("PROBE_TRACE probe_returned kind=$kind ok=$ok elapsedMs=$elapsedMs")
                ok
            } catch (_: TimeoutCancellationException) {
                val elapsedMs = nowMs() - probeStartMs
                log.warn("PROBE_TRACE probe_outer_timeout kind=$kind outerTimeoutMs=$outerTimeoutMs elapsedMs=$elapsedMs")
                false
            } catch (ce: CancellationException) {
                // R-N1.16 P1-1. The outer-timeout clause above already
                // took the domain case; anything left is a real stop.
                log.warn("PROBE_TRACE walk_cancelled kind=$kind phase=probe")
                throw ce
            } catch (t: Throwable) {
                val elapsedMs = nowMs() - probeStartMs
                log.warn(
                    "PROBE_TRACE probe_threw kind=$kind exception=${t::class.simpleName} " +
                        "message=${t.message} elapsedMs=$elapsedMs",
                )
                log.warn("$kind probe threw: ${t::class.simpleName}: ${t.message}")
                false
            }
            if (probeOk) {
                // Test seam: a fixture parks here to complete a whole
                // privacy switch before the critical section below runs.
                onPolicyPublishSeam?.invoke()
                // R-N1.16 P1: the policy gate. `strategy` was read once,
                // at the top of this walk. A privacy switch can land
                // while we are preparing or probing, and a walk that was
                // cancelled but wedged in a native call can resume long
                // after the switch completed. Publishing on the strategy
                // we started with would open exactly the egress the user
                // just turned off - a silent downgrade, arriving late.
                //
                // Fail-closed ownership does not cover this. It stops a
                // SUCCESSOR from starting; it says nothing about the
                // stale walk's own side effects, and on a handover
                // timeout the stale walk is precisely the one still
                // running.
                //
                // Checked here rather than earlier because this is the
                // moment of publication: nothing before it is visible to
                // the rest of the app.
                // Validate and publish as ONE step, inside the
                // authority's critical section. Re-reading the mode here
                // and then publishing would leave the same check-then-act
                // window one level down.
                val totalMs = nowMs() - prepareStartMs
                val published = policy.publishIfCurrent(startEpoch, kind) {
                    onSuccess(kind, strategy)
                    _state.value = ManagerState.Connected(kind)
                    log.info(
                        "PROBE_TRACE chain_attempt_success kind=$kind " +
                            "socksPort=$socksPort totalMs=$totalMs epoch=$startEpoch",
                    )
                    ConnectedTransport(kind, socksPort, startEpoch)
                }
                if (published == null) {
                    val liveNow = TransportStrategy.from(policy.state.value.requested)
                    log.warn(
                        "PROBE_TRACE chain_attempt_discarded kind=$kind " +
                            "reason=privacy_mode_changed startedUnder=$strategy " +
                            "liveNow=$liveNow startEpoch=$startEpoch",
                    )
                    throw TransportPolicyChangedException(kind, strategy, liveNow)
                }
                return published
            }
            log.warn("$kind probe returned false")
            log.warn("PROBE_TRACE chain_attempt_failed kind=$kind reason=probe_failed")
            failures += TransportAttemptFailure(kind, "probe failed")
        }

        val failureSummary = failures.joinToString(",") { "${it.kind}:${it.reason.substringBefore(':')}" }
        log.warn("PROBE_TRACE chain_all_failed attempts=${failures.size} failures=[$failureSummary]")
        onAllFailed()
        _state.value = ManagerState.AllFailed(failures.toList())
        throw NoTransportReachableException(failures)
    }

    /** Stop both subsystems. Idempotent; safe to call multiple times. */
    /**
     * Stop the proxy subsystems and return to [ManagerState.Idle].
     *
     * N1-F2 R-N1.5: this used to swallow both stop failures in bare
     * `runCatching` and return `Unit`, so a privacy-mode switch could
     * report a clean teardown while Xray or Tor was still running. It now
     * returns a structured [ReleaseOutcome]; the state still becomes
     * `Idle` either way, because a subsystem that refuses to stop is not
     * a reason to keep the manager pinned in a stale state — but the
     * failure is no longer invisible.
     *
     * Callers that do not care may ignore the result, which is what the
     * service-shutdown paths do.
     */
    suspend fun release(): ReleaseOutcome {
        // Both attempts are CLAIMED before either is waited on, and a claim
        // that is still in flight is joined rather than duplicated.
        //
        // Claiming is what binds the obligation to what is live NOW. It has
        // to happen here, in the caller, because a claim made later inside a
        // deferred worker would bind to whatever is live THEN -- and by then
        // this generation may have been stopped by someone else and a
        // successor started. A worker that arrives late must finish the
        // attempt it was given; it must never pick a new one.
        // Resolving a subsystem is part of THAT subsystem's attempt. A
        // provider that throws is its failure to report, not a reason the
        // other subsystem is never asked -- which is what happened while the
        // resolution sat outside any handler.
        val xrayWork = runCatching { claimXrayStop() }
        val torWork = runCatching { claimTorStop(TorBudget(SUBSYSTEM_STOP_TIMEOUT_MS)) }

        // Waiting is what the budget bounds, and it happens under
        // NonCancellable so a cancelled caller still reads the answers it
        // is owed rather than skipping them.
        val outcome = withContext(NonCancellable) {
            val xrayFailure = xrayWork.fold(
                onSuccess = { awaitStop("xray", it, SUBSYSTEM_STOP_TIMEOUT_MS) },
                onFailure = { it },
            )

            // Tor spends its own monotonic budget across both halves of its
            // teardown and answers by that deadline. This wait is the same
            // deadline plus a grace: it exists for a service that ignores
            // its budget, and without the grace the two would expire
            // together and a service answering exactly on time would be
            // called unconfirmed.
            val claimed = torWork.getOrNull()
            val settled = claimed?.let {
                withTimeoutOrNull(SUBSYSTEM_STOP_TIMEOUT_MS + STOP_GUARD_GRACE_MS) {
                    it.work.await()
                }
            }
            val response = settled?.getOrNull()
            val torResult = response?.result
            val torFailure = when {
                claimed == null -> torWork.exceptionOrNull()
                settled == null ->
                    SubsystemStopNotConfirmed("tor", SUBSYSTEM_STOP_TIMEOUT_MS)
                settled.isFailure -> settled.exceptionOrNull()
                response == null || torResult == null || torResult.isFree -> null
                else -> TorLifecycleUnsettled(response.attempt, torResult)
            }
            ReleaseOutcome(
                xrayFailure = xrayFailure,
                torFailure = torFailure,
                torResult = torResult,
            )
        }
        _state.value = ManagerState.Idle
        if (!outcome.clean) {
            log.warn(
                "TRANSPORT_MANAGER release_not_clean " +
                    "xray=${outcome.xrayFailure?.let { it::class.simpleName } ?: "ok"} " +
                    "tor=${outcome.torIncomplete ?: "ok"}",
            )
        }
        // The caller changing its mind is still the caller's to have -- just
        // not at the price of a subsystem that was never asked to stop.
        coroutineContext.ensureActive()
        return outcome
    }

    /**
     * One tor teardown, claimed against the generation that is live at the
     * moment of claiming, with only the waiting deferred.
     *
     * The registration runs HERE and uses a spent budget: it is the
     * synchronous half of [TorService.stop], the half that binds this
     * obligation to a generation. Everything deferred afterwards waits on
     * THAT attempt and can address no other, so a worker that runs late has
     * nothing to stop but the daemon it was issued for.
     *
     * An attempt already in flight is JOINED. Issuing a second close over
     * the first would register again -- against whatever is live by then --
     * and would queue another teardown behind a subsystem that has not
     * finished the first.
     */
    private fun claimTorStop(budget: TorBudget): TorStopInFlight {
        val tor = torServiceProvider()
        // Register first, undispatched, so the synchronous half of `stop`
        // runs on THIS thread and binds to the generation that is live now.
        // It is also the only thing that can say WHICH generation a stop now
        // addresses, and that is what decides whether a retained attempt may
        // be joined at all. For a generation whose stop is already
        // registered it is idempotent: no second teardown, nothing queued.
        val claim = teardownWorkers.async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { tor.stop(TorBudget(0)) }
        }
        val registered =
            if (claim.isCompleted) claim.getCompleted().getOrNull() else null

        return synchronized(teardownLock) {
            val existing = torStop
            val sameGeneration = registered != null &&
                existing != null &&
                existing.generation == registered.attempt.generation
            if (sameGeneration && existing!!.work.isActive) {
                // The retained attempt is for the generation this request is
                // about, so it IS this request's attempt.
                existing
            } else {
                // A retained attempt that is still running but belongs to a
                // generation that has been replaced must NOT answer for this
                // one. Joining it would report its `Free` for a daemon that
                // was never asked to stop. It keeps running and keeps its own
                // waiters; it simply stops being the current record.
                val work = teardownWorkers.async {
                    runCatching {
                        val response = claim.await().getOrThrow()
                        val result = if (response.result.isTerminal) {
                            response.result
                        } else {
                            tor.awaitRelease(response.attempt, budget)
                        }
                        TorStopResponse(response.attempt, result)
                    }
                }
                // A registration that has not returned yet leaves the
                // generation unknown, and an unknown generation matches
                // nothing: the next request will not join this record.
                val generation = registered?.attempt?.generation ?: GENERATION_UNKNOWN
                val record = TorStopInFlight(generation, work)
                // A registration that was delayed on its way here can arrive
                // after a NEWER one has already been recorded. It still owes
                // its own waiters an answer -- that is what it returns -- but
                // it must not become the current record, or the newer
                // generation would lose the attempt it is already running and
                // the next request for it would start a second one.
                val current = torStop
                val supersedes = current == null ||
                    !current.work.isActive ||
                    (generation != GENERATION_UNKNOWN && generation >= current.generation)
                if (supersedes) torStop = record
                record
            }
        }
    }

    /**
     * Wait for an outstanding Xray teardown to finish before anything is
     * started on that subsystem.
     *
     * Binding by instance is not identity: production keeps one
     * `XrayService` and starts a new resource through it, so a claim issued
     * for the old resource would close the new one just as readily. The
     * interface has nothing to tell the two apart, so the rule is enforced
     * here instead: no new start while an old claim can still reach it.
     *
     * Bounded, and a claim that does not finish refuses the start rather
     * than waiting on it -- the walk then advances to the next transport,
     * which is an ordinary outcome and not a new recovery mechanism.
     */
    /**
     * Take the Xray gate for a start, or refuse the start.
     *
     * Held only across the library call that creates the resource, not
     * across the whole Reality prepare: a teardown must not have to wait out
     * a bootstrap. A claim that already holds it is a teardown that has not
     * finished, and there is nothing safe to start under one -- so this
     * transport fails and the walk advances, which is an ordinary outcome.
     */
    private fun refuseXrayStart(): Nothing {
        log.warn("PROBE_TRACE xray_start_refused reason=teardown_outstanding")
        throw SubsystemStopNotConfirmed("xray", SUBSYSTEM_STOP_TIMEOUT_MS)
    }

    /**
     * One admission attempt. Returns true with the gate HELD, false if the
     * gate is busy and there is still budget left to wait, and refuses
     * outright once the budget has run out.
     *
     * Not suspending, so the whole decision -- take the gate, read the
     * deadline, keep it or hand it back -- happens without an interleaving
     * point inside it.
     */
    private fun tryAdmitXrayStart(budget: TorBudget): Boolean {
        if (xrayGate.tryLock()) {
            // The deadline is read HERE, with the gate already in hand.
            // Checking it only before each attempt lets a waiter that was
            // slow to resume find the gate free and start long after its
            // budget ran out -- admitted on the strength of a check made in
            // a different second. What it took, it hands back.
            if (!budget.expired) return true
            xrayGate.unlock()
            refuseXrayStart()
        }
        if (budget.expired) refuseXrayStart()
        return false
    }

    private suspend fun <T> withXrayStartAdmitted(block: suspend () -> T): T {
        // Acquired with `tryLock`, which does not suspend. That is the whole
        // point: there is no moment between HOLDING the gate and being
        // PROTECTED by the `finally` that releases it, so nothing can be
        // cancelled in between and leave it held for ever.
        //
        // Waiting with `withTimeoutOrNull { lock() }` cannot give that: the
        // timeout may fire after the lock has been taken but before the
        // caller is told, and the caller then returns without ever knowing
        // it owned anything. The waiting here happens where nothing is held.
        //
        // The wait is a bounded poll, not a retry loop: it adds no attempt
        // and no cadence of its own, and it ends either with the gate in
        // hand or with a refusal.
        val budget = TorBudget(SUBSYSTEM_STOP_TIMEOUT_MS)
        while (!tryAdmitXrayStart(budget)) {
            delay(GATE_POLL_MS)
        }
        return try {
            block()
        } finally {
            xrayGate.unlock()
        }
    }

    /**
     * The Xray half of the same rule.
     *
     * Xray has no generation identity to bind to, so the strongest binding
     * available is the instance: it is resolved HERE and handed to the
     * worker, which therefore cannot close whichever instance the provider
     * would hand back later. An attempt still in flight is joined, so a
     * close that has not returned never has a second one queued behind it.
     */
    private fun claimXrayStop(): Deferred<Result<Unit>> {
        val xray = xrayServiceProvider()
        return synchronized(teardownLock) {
            val existing = xrayStop
            if (existing != null && existing.work.isActive) {
                existing.work
            } else {
                val record = XrayStopInFlight()
                xrayStop = record
                // ONE coroutine owns the gate from `lock` to `unlock`, with
                // the `finally` opened immediately after acquiring it. A
                // handoff between two coroutines cannot do this: whoever is
                // cancelled between receiving the gate and entering its
                // protected body leaves it held with nobody to release it.
                //
                // UNDISPATCHED so a free gate is taken on THIS thread, at
                // claim time. The `yield` then leaves this thread before the
                // library call, because a blocking close must never run on
                // the caller -- the shutdown path calls this inside a
                // `runBlocking` on the main thread.
                record.work = teardownWorkers.async(
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    xrayGate.lock()
                    val closed = try {
                        yield()
                        runCatching { xray.stop() }
                    } finally {
                        // The record stops being joinable at the END OF THE
                        // RESOURCE OPERATION, before the gate is released --
                        // never at the end of this coroutine. A start
                        // admitted through the freed gate must not then be
                        // answered by this attempt: its success says a
                        // subsystem was stopped, and that was the previous
                        // one. Waiters already holding this work still get
                        // their result; only NEW requests are turned away.
                        synchronized(teardownLock) {
                            if (xrayStop === record) xrayStop = null
                        }
                        xrayGate.unlock()
                    }
                    afterXrayStopReleased()
                    closed
                }
                record.work
            }
        }
    }

    /**
     * Wait for an already-issued attempt, bounded. Returns what went
     * wrong, or null.
     *
     * A budget that elapses is reported rather than left as silence, and it
     * says only that the stop was not CONFIRMED in time. The attempt is
     * still running and still owned; nothing here interrupts it.
     */
    private suspend fun awaitStop(
        subsystem: String,
        work: Deferred<Result<Unit>>,
        budgetMs: Long,
    ): Throwable? {
        val settled = withTimeoutOrNull(budgetMs) { work.await() }
            ?: return SubsystemStopNotConfirmed(subsystem, budgetMs)
        return settled.exceptionOrNull()
    }

    /**
     * One teardown, one monotonic deadline across both of its halves: the
     * daemon going away and its host letting go. The budget bounds the
     * WAIT, never the work.
     */
    private suspend fun stopAndAwaitRelease(
        tor: TorService,
        budget: TorBudget = TorBudget(SUBSYSTEM_STOP_TIMEOUT_MS),
    ): TorStopResponse {
        val response = tor.stop(budget)
        if (response.result !is TorStopResult.Releasing) return response
        return TorStopResponse(response.attempt, tor.awaitRelease(response.attempt, budget))
    }

    /**
     * Per-subsystem outcome of [release]. Carries exception CLASSES only
     * through its rendering; no message text is logged, so a subsystem
     * error cannot leak configuration or endpoint detail.
     */
    data class ReleaseOutcome(
        val xrayFailure: Throwable?,
        val torFailure: Throwable?,
        val torResult: TorStopResult? = null,
    ) {
        /**
         * Both subsystems are confirmed stopped AND tor's host is free or
         * was never created.
         *
         * [torResult] is consulted rather than trusted to agree with
         * [torFailure]. This type is public and constructible by anyone;
         * an outcome carrying a `ReleaseFailed` result and no failure is a
         * contradiction, and every consumer downstream reads `clean` and not
         * the parts. It resolves against the structured answer, which is the
         * one that cannot be forgotten to fill in.
         */
        val clean: Boolean
            get() = xrayFailure == null &&
                torFailure == null &&
                (torResult == null || torResult.isFree)

        /**
         * Which half of tor's teardown is unfinished, when one is.
         *
         * A daemon nobody confirmed gone and a host that has not let go of
         * its threads are different facts with different consequences, and
         * one word for both would lose the distinction exactly where a
         * reader needs it. `null` means tor's half was clean.
         */
        val torIncomplete: String?
            get() = when {
                torResult != null -> torResult.label.takeIf { it != "free" }
                torFailure != null -> "stop_threw"
                else -> null
            }
    }

    // ── Chain ordering ────────────────────────────────────────────────────────

    /**
     * Recover the strategy's preferred chain order, optionally honouring a
     * recent successful-transport hint.
     *
     * PR-RECV-DIAG1 v1.7 (Vladislav-architect 2026-05-27): hint hoisting is
     * RESTRICTED to the primary transport for the active strategy. A fallback
     * transport that previously succeeded must NOT become sticky-primary,
     * because that turns a single network glitch into a 24h Tor lock-in:
     *
     *   1. Wi-Fi has a transient issue → Direct fails → Reality fails → Tor wins.
     *   2. onSuccess saves `lastWorkingTransport=Tor`, `lastSuccessAt=now`.
     *   3. Network recovers (or user switches back to a healthy Wi-Fi).
     *   4. Next app start: reorderChain hoists Tor to the front of the
     *      Standard chain → ordered=[Tor, Direct, Reality] → Tor probe
     *      succeeds (it always does) → Direct is never attempted.
     *   5. onSuccess re-saves Tor → cycle repeats for up to 24 hours.
     *
     * Test #84.8 reproduced this exactly: Tecno on a Wi-Fi where Direct
     * worked yesterday went immediately to Tor onion today because the
     * previous test session had left Tor as the cached hint.
     *
     * New rule: a hint is only kept if it matches the strategy's primary
     * (chain[0]). A primary-success hint just skips the no-op of probing
     * something we already know works. Any non-primary hint (fallback)
     * is treated as stale and cleared, so the next attempt always walks
     * the policy chain from its declared primary.
     */
    /**
     * Stop Tor during teardown without letting teardown become the new
     * way to hang.
     *
     * Two hazards meet here. [TorService.stop] suspends, so on a
     * cancelled coroutine a plain call throws at once and `runCatching`
     * hides it, leaving the wrapper running after a handover - which is
     * a privacy leak, not an untidy log. That argues for
     * [NonCancellable]. But an unbounded `NonCancellable` block is worse
     * in the other direction: nothing can interrupt it, so a wedged
     * native stop() would hang the walk forever, the ownership join
     * would time out, and the lease would go fail-closed because of our
     * own cleanup rather than because of anything the transport did.
     *
     * So: non-cancellable, and bounded. R-N1.16 review item 6.
     */
    private suspend fun stopQuietlyBounded(tor: TorService, site: String): TorStopResult {
        // Same rule as [release]: claimed here against the live generation,
        // only the wait deferred, and an attempt already in flight joined
        // rather than issued over.
        val budget = TorBudget(SUBSYSTEM_STOP_TIMEOUT_MS)
        val inFlight = claimTorStop(budget)
        val settled = withContext(NonCancellable) {
            withTimeoutOrNull(budget.remainingMs() + STOP_GUARD_GRACE_MS) {
                inFlight.work.await()
            }
        }
        val result = settled?.getOrNull()?.result
        when {
            settled == null -> log.warn(
                "PROBE_TRACE tor_stop_timeout site=$site " +
                    "budgetMs=$SUBSYSTEM_STOP_TIMEOUT_MS",
            )
            result == null -> log.warn(
                "PROBE_TRACE tor_stop_not_free site=$site result=threw",
            )
            !result.isFree -> log.warn(
                "PROBE_TRACE tor_stop_not_free site=$site result=${result.label}",
            )
        }
        // Neither an elapsed wait nor a throw is evidence the daemon is gone.
        return result ?: TorStopResult.NotConfirmed(0L, TorStopReason.Unknown, cause = null)
    }

    /**
     * Stop tor before the rotation does anything else with it.
     *
     * A profile that did not come up is an ordinary failure and the walk
     * advances. A lifecycle that did not settle is not: nothing may start
     * another daemon while the previous one is unconfirmed, or while its
     * host still holds threads, so that leaves as a domain failure of its
     * own rather than as one more unreachable profile.
     */
    private suspend fun stopBeforeNextProfile(tor: TorService) {
        val response = stopAndAwaitRelease(tor)
        if (!response.result.isFree) {
            throw TorLifecycleUnsettled(response.attempt, response.result)
        }
    }

    /**
     * Record a failed prepare and let the chain advance.
     *
     * Extracted so the timeout clause and the generic clause cannot
     * drift apart: both are "this transport did not come up", and only
     * the cancellation clause between them means "stop walking".
     */
    private fun recordPrepareFailure(
        kind: TransportKind,
        t: Throwable,
        prepareStartMs: Long,
        failures: MutableList<TransportAttemptFailure>,
    ) {
        val elapsedMs = nowMs() - prepareStartMs
        val reason = "${t::class.simpleName}: ${t.message ?: "<no message>"}"
        log.warn(
            "PROBE_TRACE prepare_fail kind=$kind exception=${t::class.simpleName} " +
                "message=${t.message ?: "<no message>"} elapsedMs=$elapsedMs",
        )
        log.warn("$kind subsystem prepare failed: $reason")
        failures += TransportAttemptFailure(kind, reason)
    }

    private fun reorderChain(strategy: TransportStrategy): List<TransportKind> {
        val baseChain = strategy.chain
        val primary = baseChain.first()
        val hint = preferences.lastWorkingTransport
        val hintAt = preferences.lastSuccessAt
        log.info(
            "PROBE_TRACE hint_read hint=$hint hintAt=$hintAt primary=$primary " +
                "privacyMode=${policy.state.value.requested} base=$baseChain",
        )
        if (hint == null || hintAt == null) return baseChain

        val ageMs = nowMs() - hintAt
        val isFresh = ageMs in 0 until TransportPreferences.LAST_SUCCESS_TTL_MS
        if (!isFresh) {
            log.info(
                "PROBE_TRACE hint_ignored hint=$hint reason=stale ageMs=$ageMs",
            )
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            return baseChain
        }
        if (hint !in baseChain) {
            log.info(
                "PROBE_TRACE hint_ignored hint=$hint reason=out_of_chain base=$baseChain",
            )
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            return baseChain
        }
        if (hint != primary) {
            log.info(
                "PROBE_TRACE hint_ignored hint=$hint reason=fallback_hint " +
                    "primary=$primary ageMs=$ageMs",
            )
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            return baseChain
        }
        log.info(
            "PROBE_TRACE hint_kept hint=$hint reason=primary_match ageMs=$ageMs",
        )
        return baseChain
    }

    // ── Subsystem lifecycle ───────────────────────────────────────────────────

    /**
     * Start the subsystem matching [kind] and return the SOCKS port the
     * caller should tunnel WSS through. Direct returns null without any
     * subsystem work. Tor / Xray suspend until the underlying state machine
     * reports `Ready`, bounded by a per-kind budget that allows for cold
     * native-init time:
     *
     *  - Reality (libXray gomobile JNI): up to [REALITY_PREPARE_TIMEOUT_MS]
     *    on first launch (~30 s for the 45 MB native lib). Warm restarts
     *    return immediately because the service is already in `Ready`.
     *  - Tor (Briar wrapper + bridges): walked through the
     *    [BRIDGE_ROTATION_ORDER] (PR-C) — each profile gets its own
     *    per-attempt budget. The executable table is
     *    [BRIDGE_ROTATION_ORDER]: 600 + 420 + 180 + 60 = 1260 s
     *    worst-case if every profile must be tried. Warm restarts
     *    return inside the first profile in seconds.
     *
     *    This line used to read "180 / 120 / 180 / 240 = 720 s", which
     *    had drifted from the table below and was copied into N1-F3's
     *    documentation as fact. Read the budgets off
     *    BRIDGE_ROTATION_ORDER, not off this sentence.
     *
     * The probe phase that follows has its own budget, read per kind
     * from [probeTimeoutFor]: Direct 25 s, Reality 30 s, Tor 90 s.
     *
     * This line used to say "[PROBE_TIMEOUT_MS] (5 s)". That constant is
     * kept only for callers and tests that import it directly; the
     * runtime path has not used it for the walk since the per-kind
     * budgets were introduced. R-N1.16 P3 - the same drift as the
     * rotation-budget sentence a few lines above, which was corrected
     * without anyone noticing its neighbour said the same kind of thing.
     */
    private suspend fun prepareTransport(kind: TransportKind): Int? = when (kind) {
        TransportKind.Direct -> null
        TransportKind.Tor -> prepareTorWithRotation()
        TransportKind.Reality -> {
            val xray = xrayServiceProvider()
            // A teardown claim that has not returned still has the power to
            // call `stop` on this subsystem. Starting a new resource under
            // it would hand that power over the NEW one: `XrayService` has no
            // generation to tell them apart, and the same instance serves
            // both. Until the claim is finished, there is nothing safe to
            // start, so this transport fails and the walk advances.
            val xrayStartMs = nowMs()
            log.info("PROBE_TRACE xray_prepare_start")
            withXrayStartAdmitted { xray.start() }
            log.info(
                "PROBE_TRACE xray_state state=Starting elapsedMs=${nowMs() - xrayStartMs}",
            )
            val terminal = try {
                withTimeout(REALITY_PREPARE_TIMEOUT_MS) {
                    xray.state.first { st ->
                        when (st) {
                            is XrayState.Starting -> {
                                // Emit state transitions as they arrive so we can see
                                // how long the native init phase takes.
                                log.info(
                                    "PROBE_TRACE xray_state state=Initialising " +
                                        "elapsedMs=${nowMs() - xrayStartMs}",
                                )
                                false
                            }
                            is XrayState.Ready, is XrayState.Failed -> true
                            else -> false
                        }
                    }
                }
            } catch (t: TimeoutCancellationException) {
                val elapsedMs = nowMs() - xrayStartMs
                log.warn(
                    "PROBE_TRACE xray_prepare_done ok=false reason=timeout totalMs=$elapsedMs",
                )
                throw t
            }
            when (terminal) {
                is XrayState.Ready -> {
                    val elapsedMs = nowMs() - xrayStartMs
                    log.info(
                        "PROBE_TRACE xray_state state=Ready socksPort=${terminal.socksPort} " +
                            "elapsedMs=$elapsedMs",
                    )
                    log.info(
                        "PROBE_TRACE xray_prepare_done ok=true socksPort=${terminal.socksPort} " +
                            "totalMs=$elapsedMs",
                    )
                    terminal.socksPort
                }
                is XrayState.Failed -> {
                    val elapsedMs = nowMs() - xrayStartMs
                    log.warn(
                        "PROBE_TRACE xray_state state=Failed message=${terminal.message} " +
                            "elapsedMs=$elapsedMs",
                    )
                    log.warn(
                        "PROBE_TRACE xray_prepare_done ok=false reason=failed totalMs=$elapsedMs",
                    )
                    error("Xray Failed: ${terminal.message}")
                }
                else -> error("Xray returned unexpected state: $terminal")
            }
        }
    }

    // ── Tor bridge rotation (PR-C, 2026-05-11) ────────────────────────────────

    /**
     * Walk [BRIDGE_ROTATION_ORDER] sequentially, calling [prepareTorOnce]
     * for each profile with its per-profile budget. Return the first
     * profile that reaches [TorState.Ready] within its budget. Between
     * attempts, fully stop tor so each profile starts from a clean
     * wrapper state (Briar's `enableBridges` can be called on a running
     * wrapper but the bridge re-selection only takes effect after a
     * full restart of tor's network state — stop+start is the safe
     * primitive).
     *
     * Why per-profile timeouts instead of one big budget:
     *   The 2026-05-11 audit cycle showed Tor on МТС can sit on a single
     *   percent for 3-4 minutes mid-bootstrap. With one 600 s budget we
     *   would burn the entire window on a stuck obfs4 attempt before
     *   noticing snowflake might have worked in 60 s. Splitting into
     *   shorter per-profile slots gives a stuck profile time to make a
     *   serious attempt while still letting the next profile try inside
     *   the user's patience window.
     *
     * Why obfs4 first (not webtunnel as the architect originally proposed):
     *   Empirical: Test 13 (2026-05-06) confirmed our WebTunnel handshakes
     *   trip the TSPU 16-KB curtain on Hetzner-hosted bridges. obfs4's
     *   uniform-random byte stream wire signature dodges that classifier
     *   entirely. Observed 2026-05-09 onwards on МТС: obfs4 to FlokiNET
     *   is the most reliable single-PT path. Webtunnel/snowflake follow
     *   as fallbacks for networks where obfs4 ports are blocked.
     */
    private suspend fun prepareTorWithRotation(): Int {
        val tor = torServiceProvider()
        val total = BRIDGE_ROTATION_ORDER.size
        var lastError: Throwable? = null
        for ((index, attempt) in BRIDGE_ROTATION_ORDER.withIndex()) {
            val attemptNum = index + 1
            log.info(
                "Tor rotation: attempt=$attemptNum/$total profile=${attempt.profile.displayName} " +
                    "budgetMs=${attempt.budgetMs}",
            )
            log.info(
                "PROBE_TRACE tor_rotation_start attempt=$attemptNum/$total " +
                    "profile=${attempt.profile.displayName} budgetMs=${attempt.budgetMs}",
            )
            // Defensive: ensure no leftover tor from a previous profile.
            // First iteration the service is already Off (chain walker
            // calls release() between connect generations); later
            // iterations need this stop to flip the wrapper out of any
            // half-bootstrapped state from the previous profile.
            if (index > 0) {
                stopBeforeNextProfile(tor)
            }
            val socksPort = try {
                prepareTorOnce(
                    tor = tor,
                    profile = attempt.profile,
                    perProfileBudgetMs = attempt.budgetMs,
                    attemptNum = attemptNum,
                    totalAttempts = total,
                )
            } catch (ce: CancellationException) {
                // R-N1.16 P1-1, the worst instance of this defect:
                // rotation budgets total far more than any other phase,
                // so a swallowed cancellation left a displaced walk
                // grinding through bridge profiles for minutes beside
                // its successor. prepareTorOnce already converts its own
                // per-profile budget to null, so nothing that reaches
                // here is a domain timeout.
                //
                // The stop() runs NonCancellable on purpose: TorService
                // .stop() suspends, and in a cancelled coroutine a plain
                // call would throw at once and be swallowed by
                // runCatching, leaving Tor running after handover.
                log.warn(
                    "PROBE_TRACE tor_rotation_cancelled attempt=$attemptNum " +
                        "profile=${attempt.profile.displayName}",
                )
                stopQuietlyBounded(tor, "rotation_cancelled")
                throw ce
            } catch (unsettled: TorLifecycleUnsettled) {
                // Not a profile that failed to come up: nothing may start
                // another daemon while this one is unconfirmed or its host
                // still holds threads. It leaves the rotation as itself.
                log.warn(
                    "PROBE_TRACE tor_lifecycle_unsettled attempt=$attemptNum " +
                        "generation=${unsettled.attempt.generation} " +
                        "result=${unsettled.result::class.simpleName}",
                )
                throw unsettled
            } catch (t: Throwable) {
                log.warn(
                    "Tor rotation: attempt=$attemptNum/$total profile=${attempt.profile.displayName} " +
                        "raised ${t::class.simpleName}: ${t.message}",
                )
                log.warn(
                    "PROBE_TRACE tor_rotation_done attempt=$attemptNum " +
                        "profile=${attempt.profile.displayName} ok=false reason=${t::class.simpleName}",
                )
                lastError = t
                stopBeforeNextProfile(tor)
                null
            }
            if (socksPort != null) {
                log.info(
                    "Tor rotation: attempt=$attemptNum/$total profile=${attempt.profile.displayName} " +
                        "READY socksPort=$socksPort",
                )
                log.info(
                    "PROBE_TRACE tor_rotation_done attempt=$attemptNum " +
                        "profile=${attempt.profile.displayName} ok=true socksPort=$socksPort",
                )
                return socksPort
            }
            log.warn(
                "Tor rotation: attempt=$attemptNum/$total profile=${attempt.profile.displayName} " +
                    "did not reach Ready in ${attempt.budgetMs} ms",
            )
            log.warn(
                "PROBE_TRACE tor_rotation_done attempt=$attemptNum " +
                    "profile=${attempt.profile.displayName} ok=false reason=budget_exhausted",
            )
        }
        // All profiles exhausted — surface a diagnostic error so the
        // outer chain walker logs "Tor subsystem prepare failed" with
        // a useful reason. Last error (if any) gives the most recent
        // upstream signal.
        val msg = "All ${total} bridge profiles exhausted" +
            (lastError?.let { " (last: ${it::class.simpleName}: ${it.message})" } ?: "")
        error(msg)
    }

    /**
     * Try one bridge profile. Returns the SOCKS port on success, or
     * null when the per-profile budget elapses without reaching Ready.
     * Throws on hard failure (Tor reported `Failed`, prepare-coroutine
     * cancelled, etc.) so the rotation walker can decide whether to
     * propagate or move on.
     *
     * The percent-streaming + time-keyed stage poller from PR-B is kept
     * here verbatim — it now also publishes the active [profile] +
     * [attemptNum] / [totalAttempts] into [TorProbingStatus] so the
     * notification text can show "Trying webtunnel… 50% (2/4) · Ghost".
     */
    private suspend fun prepareTorOnce(
        tor: TorService,
        profile: BridgeProfile,
        perProfileBudgetMs: Long,
        attemptNum: Int,
        totalAttempts: Int,
    ): Int? {
        // The clock starts before the launch, not after it: the launch is
        // part of what this profile is being given time for.
        val torStartMs = nowMs()
        log.info(
            "PROBE_TRACE tor_state profile=${profile.displayName} state=Starting elapsedMs=0",
        )
        val terminal: TorState = try {
            coroutineScope {
                var lastPercent = 0
                val pollerJob: Job = launch {
                    while (isActive) {
                        publishTorProbing(
                            percent = lastPercent,
                            torStartMs = torStartMs,
                            profile = profile,
                            attemptNum = attemptNum,
                            totalAttempts = totalAttempts,
                        )
                        delay(TOR_STAGE_POLL_INTERVAL_MS)
                    }
                }
                try {
                    withTimeout(perProfileBudgetMs) {
                        // The budget covers the launch too. The library call
                        // behind it can block on installing files and spawning
                        // the process, and outside the budget that time was
                        // unbounded. Expiring here does not interrupt the
                        // launch -- the owner runs it outside this caller -- so
                        // the generation is still there for the teardown below
                        // to close.
                        log.info("Tor: start(profile=${profile.displayName})")
                        tor.start(profile)
                        tor.state.first { st ->
                            when (st) {
                                is TorState.Bootstrapping -> {
                                    if (st.percent != lastPercent) {
                                        val elapsedMs = nowMs() - torStartMs
                                        log.info(
                                            "Tor bootstrap: profile=${profile.displayName} percent=${st.percent}",
                                        )
                                        log.info(
                                            "PROBE_TRACE tor_state profile=${profile.displayName} " +
                                                "state=Bootstrap percent=${st.percent} elapsedMs=$elapsedMs",
                                        )
                                        lastPercent = st.percent
                                        publishTorProbing(
                                            percent = st.percent,
                                            torStartMs = torStartMs,
                                            profile = profile,
                                            attemptNum = attemptNum,
                                            totalAttempts = totalAttempts,
                                        )
                                    }
                                    false
                                }
                                is TorState.Ready -> {
                                    val elapsedMs = nowMs() - torStartMs
                                    log.info(
                                        "Tor bootstrap: profile=${profile.displayName} Ready " +
                                            "socksPort=${st.socksPort}",
                                    )
                                    log.info(
                                        "PROBE_TRACE tor_state profile=${profile.displayName} " +
                                            "state=Ready socksPort=${st.socksPort} elapsedMs=$elapsedMs",
                                    )
                                    true
                                }
                                is TorState.Failed -> {
                                    val elapsedMs = nowMs() - torStartMs
                                    log.warn(
                                        "Tor bootstrap: profile=${profile.displayName} Failed " +
                                            "message=${st.message}",
                                    )
                                    log.warn(
                                        "PROBE_TRACE tor_state profile=${profile.displayName} " +
                                            "state=Failed message=${st.message} elapsedMs=$elapsedMs",
                                    )
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                } finally {
                    pollerJob.cancel()
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Per-profile budget elapsed. Not an error — caller advances
            // to the next profile. Defensive stop() so the next profile
            // does not inherit a half-bootstrapped wrapper.
            val elapsedMs = nowMs() - torStartMs
            log.warn(
                "Tor: profile=${profile.displayName} budget elapsed (${perProfileBudgetMs} ms)",
            )
            log.warn(
                "PROBE_TRACE tor_state profile=${profile.displayName} " +
                    "state=BudgetElapsed elapsedMs=$elapsedMs",
            )
            stopBeforeNextProfile(tor)
            return null
        } catch (t: Throwable) {
            log.warn(
                "Tor: profile=${profile.displayName} prepare aborted " +
                    "(${t::class.simpleName}: ${t.message})",
            )
            stopQuietlyBounded(tor, "prepare_aborted")
            throw t
        }
        return when (terminal) {
            is TorState.Ready -> terminal.socksPort
            is TorState.Failed -> {
                stopBeforeNextProfile(tor)
                // Hard failure for this profile but not for the rotation
                // — return null so the walker can advance.
                null
            }
            else -> {
                stopBeforeNextProfile(tor)
                null
            }
        }
    }

    /**
     * Push a fresh [ManagerState.Probing] with the current Tor probe
     * snapshot. Centralised so the percent-update path and the time-tick
     * path build the [TorProbingStatus] identically.
     */
    private fun publishTorProbing(
        percent: Int,
        torStartMs: Long,
        profile: BridgeProfile,
        attemptNum: Int,
        totalAttempts: Int,
    ) {
        val elapsedMs = nowMs() - torStartMs
        _state.value = ManagerState.Probing(
            kind = TransportKind.Tor,
            torStatus = TorProbingStatus(
                percent = percent,
                stage = TorBootstrapStage.forElapsedMs(elapsedMs),
                elapsedMs = elapsedMs,
                bridgeProfile = profile,
                attempt = attemptNum,
                totalAttempts = totalAttempts,
            ),
        )
    }

    // ── Hint maintenance ──────────────────────────────────────────────────────

    /**
     * Persist a hint about the just-succeeded transport — but ONLY if it
     * matches the strategy's primary. PR-RECV-DIAG1 v1.7
     * (Vladislav-architect 2026-05-27): a fallback success must not
     * become sticky-primary on subsequent app starts; see [reorderChain]
     * KDoc for the full Tor-lock-in cycle this prevents.
     *
     * Always resets `transportFailureCount` regardless of which transport
     * succeeded — any working transport is proof that we recovered from
     * whatever the failure streak was tracking.
     */
    private fun onSuccess(kind: TransportKind, strategy: TransportStrategy) {
        val primary = strategy.chain.first()
        if (kind == primary) {
            preferences.lastWorkingTransport = kind
            preferences.lastSuccessAt = nowMs()
            log.info("PROBE_TRACE hint_saved kind=$kind reason=primary_success")
        } else {
            preferences.lastWorkingTransport = null
            preferences.lastSuccessAt = null
            log.info(
                "PROBE_TRACE hint_not_saved kind=$kind reason=fallback_success primary=$primary",
            )
        }
        preferences.transportFailureCount = 0
    }

    private fun onAllFailed() {
        preferences.transportFailureCount += 1
    }

    /**
     * Outer probe budget per kind. Each entry MUST be at least as large
     * as the corresponding `KtorTransportProbe.callTimeoutFor()` value
     * — otherwise the outer `withTimeout` here cancels the inner OkHttp
     * `callTimeout` and the inner-budget bump never gets to fire.
     *
     * 2026-05-11 regression: Track A bumped Reality call-timeout to
     * 20 s but the outer budget here was still 10 s, so the inner bump
     * silently had no effect — every Reality probe under VPN failed
     * with `CancellationException: Timed out waiting for 10000 ms`.
     *
     * Buffers (outer = inner + 5 s) so a slow probe gets the inner
     * budget plus a small grace window for response decode + flow
     * resumption.
     *
     *   Direct  inner=10 s × 2 attempts + 400ms backoff  outer=25 s
     *   Reality inner=20 s  outer=30 s
     *   Tor     inner=60 s  outer=90 s
     */
    private fun probeTimeoutFor(kind: TransportKind): Long = when (kind) {
        TransportKind.Direct  -> 25_000L
        TransportKind.Reality -> 30_000L
        TransportKind.Tor     -> 90_000L
    }

    companion object {
        /**
         * Default probe budget — kept for callers / tests that import the
         * constant directly. The runtime path uses [probeTimeoutFor] so
         * the per-kind budget above is what actually fires.
         */
        const val PROBE_TIMEOUT_MS: Long = 5_000L

        /**
         * Budget for a non-cancellable subsystem stop during teardown.
         *
         * Deliberately short: it runs while a handover is waiting for
         * this walk to finish, and overshooting it turns our own cleanup
         * into the reason the lease goes fail-closed.
         */
        const val SUBSYSTEM_STOP_TIMEOUT_MS: Long = 3_000L

        /**
         * Slack between a budget a subsystem is given and the guard that
         * catches a subsystem which ignores it. Without it the two expire
         * together and a service answering exactly on time races its own
         * watchdog.
         */
        const val STOP_GUARD_GRACE_MS: Long = 250L

        /**
         * The generation of a teardown whose registration has not returned
         * yet. It matches no real generation, so such a record is never
         * joined by a later request.
         */
        const val GENERATION_UNKNOWN: Long = -1L

        /**
         * How often a start re-tries the gate while waiting for it.
         *
         * Polling rather than suspending on the lock is deliberate: it is
         * what removes the gap between acquiring the gate and being
         * protected by the block that releases it. Contention here is rare
         * -- a start and a teardown at the same moment -- and the wait is
         * bounded, so the cost is a handful of wake-ups.
         */
        const val GATE_POLL_MS: Long = 25L



        /**
         * Reality (libXray) prepare-phase budget. The libXray gomobile JNI
         * is a ~45 MB native library; first-launch class-init + DNS resolve
         * + REALITY handshake to the operator endpoint can take 15–30 s on
         * slow mobile carriers. After warm-up the same path is sub-second.
         * 30 s is the worst-case cold-start cap from the pre-ADR-020 single-
         * shot path (`XRAY_START_TIMEOUT_MS`) — preserving it ensures no
         * regression for the RU MTS Tecno baseline (Test 14, 2026-05-07).
         */
        const val REALITY_PREPARE_TIMEOUT_MS: Long = 30_000L

        /**
         * Tor stage-poller tick (PR-B). Re-emits ManagerState.Probing with
         * the current [TorBootstrapStage] every 5 s so the foreground
         * notification + UI advance from "Connecting…" to "Searching for a
         * reachable route…" / "Slow…" / "Throttled…" even when the
         * underlying Tor percent is stalled. 5 s is fast enough to feel
         * responsive without flooding the StateFlow.
         */
        const val TOR_STAGE_POLL_INTERVAL_MS: Long = 5_000L

        /**
         * Bridge rotation order for [prepareTorWithRotation].
         *
         * Re-tuned 2026-05-12 (PR-E) after the Briar bridge-strategy
         * audit. The new ordering puts the kitchen-sink profile first
         * — Briar's empirical winning strategy — followed by the two
         * single-PT profiles whose bridge pools we just expanded with
         * Briar's `bridges-s-ru` (snowflake with Google AMP cache
         * front) and `bridges-n-zz` (9 non-default obfs4 entries).
         *
         * Why this beats PR-D's order:
         *
         *   - KitchenSink hands tor every bridge entry across every
         *     transport in one `enableBridges` call. Tor's own
         *     path-selection logic picks whichever bridge the network
         *     does not block — strictly better than serializing
         *     per-PT attempts because a network that allows e.g.
         *     snowflake-AMP-cache but blocks obfs4 bypasses the wait.
         *   - Snowflake (now Briar's RU-tuned set) is the second best
         *     stand-alone shot — the AMP-cache fronts on
         *     `www.google.com`, which a censor cannot block without
         *     breaking the local internet.
         *   - Obfs4 (now PHANTOM FlokiNET + 9 Briar non-default) gets
         *     a longer 180 s budget because there are now 10 obfs4
         *     bridges to walk through — empirically each takes ~10 s
         *     to fail-and-move-on.
         *   - MeekLite is the wholly-different-wire-signature
         *     fallback (HTTPS to phpmyadmin.net front via cdn77).
         *     Slow latency-wise but censors that block all of the
         *     above sometimes leave HTTPS-to-CDNs alone.
         *
         * WebTunnel is dropped from the rotation entirely. Test #5
         * showed it stalls at 10 % on TSPU-active networks, and
         * Briar deliberately does not configure it. It remains in the
         * `BridgeProfile` enum and `bridgesFor()` for future use /
         * manual testing but is not in the default walk.
         *
         * Mixed (the pre-PR-E historical "all our PHANTOM-controlled
         * bridges" profile) is dropped from the default walk in
         * favour of the strictly larger KitchenSink set.
         *
         * Total worst-case walk = 600 + 420 + 180 + 60 = 1260 s
         * (21 min). On healthy networks the kitchen-sink reaches
         * Ready in 60-180 s and the rotation ends.
         */
        val BRIDGE_ROTATION_ORDER: List<BridgeRotationAttempt> = listOf(
            BridgeRotationAttempt(BridgeProfile.KitchenSink,   budgetMs = 600_000L),
            BridgeRotationAttempt(BridgeProfile.SnowflakeOnly, budgetMs = 420_000L),
            BridgeRotationAttempt(BridgeProfile.Obfs4Only,     budgetMs = 180_000L),
            BridgeRotationAttempt(BridgeProfile.MeekLite,      budgetMs = 60_000L),
        )
    }
}

/**
 * One step in the [TransportManager.BRIDGE_ROTATION_ORDER] walk: which
 * [BridgeProfile] to try and how long to give it before advancing to
 * the next profile. Per-profile budgets keep a single stuck profile
 * from monopolising the user's patience window.
 */
data class BridgeRotationAttempt(
    val profile: BridgeProfile,
    val budgetMs: Long,
)

/** [TransportManager] state for foreground-service notification + UI. */
sealed class ManagerState {
    object Idle : ManagerState()

    /**
     * Currently probing [kind]. For Tor, [torStatus] carries the
     * bootstrap percent + the time-based [TorBootstrapStage] so the UI
     * can render a meaningful message during the multi-minute bridge
     * negotiation. Null for Direct / Reality probes (they are sub-second
     * and do not need staged messaging) and for the very first emission
     * before the poller has run.
     */
    data class Probing(
        val kind: TransportKind,
        val torStatus: TorProbingStatus? = null,
    ) : ManagerState()

    data class Connected(val kind: TransportKind) : ManagerState()
    data class AllFailed(val attempts: List<TransportAttemptFailure>) : ManagerState()
}

/**
 * Tor bootstrap snapshot for [ManagerState.Probing]. PR-B (2026-05-11)
 * lets the UI surface what Tor is actually doing during the long
 * bridge-negotiation window instead of a silent "Connecting via Tor…".
 *
 * `percent` is the last value reported by the Tor wrapper (0–100).
 * `stage` is derived purely from `elapsedMs` so it advances even when
 * the underlying percent stalls — that is the situation we most want to
 * surface ("This network is slowing Tor connections…" after 2 min, etc).
 * `elapsedMs` is the ms since the prepare-Tor branch started, useful
 * for the UI to show "Tor • 1:34 • 50 %" if it wants finer-grained
 * timing without re-deriving it from logs.
 */
data class TorProbingStatus(
    val percent: Int,
    val stage: TorBootstrapStage,
    val elapsedMs: Long,
    /**
     * Which bridge profile is currently being attempted. PR-C
     * (2026-05-11) walks profiles sequentially with their own per-
     * profile timeout; surfacing the active profile in the UI lets
     * the user see "Trying webtunnel… 50%" → "Trying snowflake…" so
     * the rotation is not invisible during a long bootstrap.
     */
    val bridgeProfile: BridgeProfile,
    /**
     * 1-based attempt index for the current rotation walk (1 = first
     * profile, 2 = second, …). Surfaced in logs / notification copy
     * so a user / reviewer can see "attempt 3 of 4" without re-deriving
     * it from the profile order. Useful in support diagnostics.
     */
    val attempt: Int,
    /** Total number of profiles in the rotation order (typically 4). */
    val totalAttempts: Int,
)

/**
 * Time-keyed Tor bootstrap stages for user-facing messages. Thresholds
 * picked from the architect-suggested staging (PR-B 2026-05-11):
 *
 *   0–15 s    Initial         "Connecting to Tor network…"
 *   15–45 s   Negotiating     "Negotiating censorship-resistant bridge…"
 *   45–120 s  Searching       "Searching for a reachable route…"
 *   120–240 s Slow            "This network is slowing Tor connections…"
 *   ≥ 240 s   Throttled       "Tor is heavily throttled on this network. VPN may improve connection speed."
 *
 * Wording is deliberate: never say "blocked" without certainty (we do
 * not have certainty without explicit DPI fingerprints), prefer "slow",
 * "restricted", "throttled", "difficult network". The "VPN may improve"
 * line on the Throttled stage is the one piece of actionable advice we
 * have for the user — it is consistent with what the 2026-05-11 audit
 * cycle empirically observed (Tor under VPN: ~2-7 min; without VPN on
 * МТС: 10+ min and frequent timeout).
 */
enum class TorBootstrapStage(val userText: String) {
    Initial("Connecting to Tor network…"),
    Negotiating("Negotiating censorship-resistant bridge…"),
    Searching("Searching for a reachable route…"),
    Slow("This network is slowing Tor connections…"),
    Throttled("Tor is heavily throttled on this network. VPN may improve connection speed."),
    ;

    companion object {
        fun forElapsedMs(elapsedMs: Long): TorBootstrapStage = when {
            elapsedMs < 15_000L -> Initial
            elapsedMs < 45_000L -> Negotiating
            elapsedMs < 120_000L -> Searching
            elapsedMs < 240_000L -> Slow
            else -> Throttled
        }
    }
}

/**
 * End-to-end reachability probe. Implementations make a cheap GET against the
 * relay (typically `/health`) through the supplied SOCKS port and return true
 * iff the response is 200. Non-200 / network error / timeout → false.
 *
 * Lives behind an interface so the manager stays platform-neutral; the Android
 * wiring builds an OkHttp-via-SOCKS probe.
 */
fun interface TransportProbe {
    suspend fun reachable(kind: TransportKind, socksPort: Int?): Boolean
}

/** Small log abstraction; default is Noop so unit tests stay quiet. */
interface TransportManagerLog {
    fun info(msg: String)
    fun warn(msg: String)

    object Noop : TransportManagerLog {
        override fun info(msg: String) {}
        override fun warn(msg: String) {}
    }
}

/**
 * A subsystem teardown was not CONFIRMED within the budget the caller had
 * to wait.
 *
 * The absence of a confirmation, and nothing more. The attempt is still
 * running and still owned; it was not cancelled, not abandoned, and it may
 * yet finish. Named rather than left as an absent result, because silence
 * would read as success.
 */
class SubsystemStopNotConfirmed(
    val subsystem: String,
    val budgetMs: Long,
) : Exception("$subsystem stop not confirmed within $budgetMs ms")
