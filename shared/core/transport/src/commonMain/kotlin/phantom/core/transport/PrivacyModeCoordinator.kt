// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where a privacy-mode request currently stands. */
enum class PrivacyModeStatus {
    /** requested == effective. Nothing from a previous policy is live. */
    Applied,

    /**
     * The request governs new chain walks, but something opened under the
     * previous policy could not be confirmed closed. `effective` is still
     * the OLD mode and must be presented as such.
     */
    Blocked,

    /** A newer request overtook this one. It committed nothing. */
    Superseded,
}

/**
 * One atomic view of the privacy posture.
 *
 * Deliberately a single snapshot rather than separate requested,
 * effective and status flows: with independent streams a screen can
 * observe a new `requested` beside a stale `Applied` and tell the user
 * the new mode is active while the old socket is still up. That is the
 * silent downgrade moved into the UI, which is exactly what this round
 * is closing.
 */
data class PrivacyModeSnapshot(
    /** Bumped by every request. Identifies which request a result belongs to. */
    val epoch: Long,
    /** What the user asked for. Governs new chain walks immediately. */
    val requested: PrivacyMode,
    /** What is actually in force, and all the app may present as active. */
    val effective: PrivacyMode,
    val status: PrivacyModeStatus,
    /** Sockets from a superseded policy that are still open. */
    val stillOpen: Int,
    /** Why the request is not applied, when it is not. */
    val reason: String? = null,
    /**
     * Whether the chain walk that owned the connect lease is confirmed
     * stopped.
     *
     * R-N1.17 P1: kept as its OWN field. The two blockers - open sockets
     * and a running walk - used to collapse into a single `reason`
     * string, and when both were outstanding only the socket one
     * survived. A later socket recovery then read that reason, concluded
     * the walk must have been fine, and published Applied over a walk
     * nobody had stopped.
     *
     * Two facts need two fields.
     */
    val walkQuiesced: Boolean = true,
) {
    /**
     * Whether read receipts may be sent right now.
     *
     * R-N1.17 P1. The coordinator hands out a CAPABILITY, not a mode
     * string for someone else to interpret. A second reader parsing the
     * mode is a second privacy-policy engine, and it will disagree
     * eventually - `ChatScreen` read a legacy string with a "Standard"
     * default, so a missing or unreadable key meant "send receipts".
     *
     * During a switch the answer is the INTERSECTION of the two postures,
     * which is what makes it correct in both directions:
     *
     *  - Standard -> Ghost: the Ghost restriction applies immediately,
     *    before the switch completes. Waiting for `effective` would keep
     *    leaking receipts for as long as the teardown took;
     *  - Ghost -> Standard: the relaxation waits for `Applied`. Acting on
     *    `requested` would start leaking receipts while the Ghost socket
     *    was still up.
     *
     * Neither posture alone is safe to read, which is exactly why this is
     * computed here rather than left to the caller.
     */
    val maySendReadReceipts: Boolean
        get() = allowsReadReceipts(requested) && allowsReadReceipts(effective)

    private fun allowsReadReceipts(mode: PrivacyMode): Boolean =
        mode == PrivacyMode.Standard
}

/**
 * R-N1.17 - the single authority for privacy-mode state.
 *
 * ## Why one owner
 *
 * The policy epoch used to live in `TransportManager` while the
 * effective mode lived in the Android container. Two pieces of state
 * describing one fact can disagree, and the whole of R-N1.16 was a
 * sequence of exactly that: a value read here, acted on there, and a
 * window in between. So there is one epoch, held here, and everything
 * that needs it asks.
 *
 * What that buys:
 *
 *  - the publication gate, the use permits and the UI all move on the
 *    same epoch, so a result cannot be current for one and stale for
 *    another;
 *  - a late completion is detected by comparing epochs rather than by
 *    hoping it does not happen;
 *  - the screen observes one snapshot, so it cannot see a new request
 *    beside an old verdict.
 *
 * ## What it owns
 *
 * The epoch, the requested and effective modes, the status, and the
 * register of live use permits. `TransportManager` walks chains and
 * `TransportActivation` opens sockets; neither keeps policy state of its
 * own.
 */
class PrivacyModeCoordinator(
    initialMode: PrivacyMode,
    /**
     * Persist the requested mode. Called inside the critical section, so
     * a new walk started immediately afterwards reads the new value.
     */
    /**
     * Persist the requested mode. SUSPEND on purpose: the store commits
     * synchronously, and the dispatcher boundary belongs here - at the
     * persistence contract every writer goes through - rather than at
     * each UI call site remembering to wrap its own call.
     */
    private val persistRequested: suspend (PrivacyMode) -> Unit = {},
    private val log: ((String) -> Unit)? = null,
) {
    private val mutex = Mutex()

    /**
     * Serialises a whole privacy TRANSITION - request, teardown, release,
     * completion - against any other transition.
     *
     * R-N1.17 P1: sequential epoch reads do not close the window. A
     * deferred settlement checked the epoch, then suspended in
     * `releaseSubsystems()`; a new request could run its entire
     * transaction and bring a transport up in that gap, and the old
     * release would then stop the NEW subsystems. The later `complete()`
     * correctly reported Superseded, but the damage was already done.
     *
     * Distinct from [mutex], which guards the snapshot for readers. This
     * one is held across suspending teardown work, so it must never be
     * the lock a status read waits on.
     */
    private val transitionMutex = Mutex()

    /**
     * Run a whole transition under the transition lock.
     *
     * Both `setPrivacyMode` and the deferred settlement go through here,
     * so a release can never overlap another switch's startup.
     */
    suspend fun <T> withTransition(block: suspend () -> T): T =
        transitionMutex.withLock { block() }

    private val _state = MutableStateFlow(
        PrivacyModeSnapshot(
            epoch = 0L,
            requested = initialMode,
            effective = initialMode,
            status = PrivacyModeStatus.Applied,
            stillOpen = 0,
        ),
    )

    /** The one snapshot everything observes. */
    val state: StateFlow<PrivacyModeSnapshot> = _state.asStateFlow()

    private val livePermits = mutableListOf<TransportUsePermit>()
    private val unclosed = mutableListOf<TransportUsePermit>()

    /**
     * Permits taken out of the register for a sweep that has not finished
     * yet.
     *
     * R-N1.17 P1: `requestMode` and `retryPending` used to clear the
     * register BEFORE revoking, and put the failures back afterwards.
     * Between those two moments the register looked empty, so
     * [acquirePermit] happily issued a new socket beside one that was
     * still up - the two-concurrent-sockets defect, reached through the
     * bookkeeping instead of through a race.
     *
     * A permit in flight is still a permit. It stays visible here for as
     * long as the sweep is running.
     */
    private val revoking = mutableListOf<TransportUsePermit>()

    /** The epoch in force right now. */
    suspend fun currentEpoch(): Long = mutex.withLock { _state.value.epoch }

    /** The mode new chain walks must obey. */
    suspend fun requestedMode(): PrivacyMode = mutex.withLock { _state.value.requested }

    /**
     * Record a new request. Returns the epoch it created.
     *
     * The requested mode takes effect for new walks at once - a walk
     * started a moment later must not still choose Direct - while the
     * effective mode stays where it was until [complete] says otherwise.
     */
    suspend fun requestMode(mode: PrivacyMode): Long {
        val doomed: List<TransportUsePermit>
        val epoch: Long
        mutex.withLock {
            val previous = _state.value
            epoch = previous.epoch + 1
            persistRequested(mode)
            doomed = livePermits.toList() + unclosed.toList()
            livePermits.clear()
            unclosed.clear()
            // Still tracked while the sweep runs - see `revoking`.
            revoking += doomed
            _state.value = previous.copy(
                epoch = epoch,
                requested = mode,
                status = PrivacyModeStatus.Blocked,
                reason = "switch_in_progress",
                // R-N1.17 P1: a NEW epoch has confirmed nothing. Copying
                // the previous value carried a `true` from the last
                // Applied switch, so a socket recovery could publish
                // Applied before this epoch had even attempted its own
                // handover.
                walkQuiesced = false,
            )
            log?.invoke("PRIVACY requested mode=$mode epoch=$epoch permits=${doomed.size}")
        }
        val outcome = revokeAll(doomed, "privacy_mode_changed")
        mutex.withLock {
            if (_state.value.epoch == epoch) {
                // NOT auto-applied here. An empty permit register means
                // no SOCKET is open; it says nothing about the chain walk
                // that owns the connect lease, which only the caller can
                // report. Declaring Applied on the register alone would
                // announce the new posture over a walk still running
                // under the old one.
                //
                // `complete()` stays the single place the effective mode
                // moves. R-N1.17 P2 was that one caller never called it.
                // R-N1.17 P1: a sweep started by an EARLIER request may
                // still be running. Its permits are in `revoking`, not in
                // this outcome, and ignoring them would report
                // stillOpen=0 - so this switch would look complete, its
                // caller would not arm recovery, and the earlier sweep's
                // sockets would be left with nothing watching them.
                _state.value = _state.value.copy(
                    stillOpen = outcome.stillOpen.size + revoking.size,
                )
            }
        }
        return epoch
    }

    /**
     * Report what a switch achieved. Changes the effective mode ONLY for
     * the epoch that is still current.
     *
     * A slow Standard -> Ghost finishing after the user has asked for
     * Private would otherwise install Ghost: a mode nobody currently
     * wants, written by an arrival that lost the race.
     */
    suspend fun complete(
        epoch: Long,
        policyChangeComplete: Boolean,
        walkQuiesced: Boolean,
    ): PrivacyModeChangeResult = mutex.withLock {
        val now = _state.value
        if (now.epoch != epoch) {
            log?.invoke(
                "PRIVACY superseded completingEpoch=$epoch liveEpoch=${now.epoch} " +
                    "— committing nothing",
            )
            return@withLock PrivacyModeChangeResult.Superseded(
                requested = now.requested,
                effective = now.effective,
                changeEpoch = epoch,
                liveEpoch = now.epoch,
            )
        }
        val stillOpen = unclosed.size + revoking.size
        val result = decidePrivacyModeChange(
            requested = now.requested,
            effective = now.effective,
            policyChangeComplete = policyChangeComplete && stillOpen == 0,
            walkQuiesced = walkQuiesced,
            stillOpen = stillOpen,
            changeEpoch = epoch,
            liveEpoch = now.epoch,
        )
        _state.value = when (result) {
            is PrivacyModeChangeResult.Applied -> now.copy(
                effective = now.requested,
                status = PrivacyModeStatus.Applied,
                stillOpen = 0,
                reason = null,
                walkQuiesced = true,
            )
            is PrivacyModeChangeResult.Blocked -> now.copy(
                status = PrivacyModeStatus.Blocked,
                stillOpen = stillOpen,
                reason = result.reason,
                walkQuiesced = walkQuiesced,
            )
            is PrivacyModeChangeResult.Superseded -> now
        }
        log?.invoke(
            "PRIVACY complete epoch=$epoch result=${result::class.simpleName} " +
                "effective=${_state.value.effective} status=${_state.value.status}",
        )
        result
    }

    /**
     * Publish a walk's result, but only if the policy it was decided
     * under is still in force.
     *
     * [publish] runs INSIDE this authority's critical section, so a
     * request cannot land between the check and the publication. A
     * re-read followed by a publish would leave exactly that window -
     * the shape this round has been closing all the way down.
     *
     * Returns null when the walk is superseded; the caller must then
     * publish nothing and hand back nothing.
     */
    suspend fun <T> publishIfCurrent(
        startedUnderEpoch: Long,
        kind: TransportKind,
        publish: () -> T,
    ): T? = mutex.withLock {
        val now = _state.value
        if (startedUnderEpoch != now.epoch ||
            kind !in TransportStrategy.from(now.requested).chain
        ) {
            log?.invoke(
                "PRIVACY publish_refused kind=$kind startedUnder=$startedUnderEpoch " +
                    "liveEpoch=${now.epoch} liveMode=${now.requested}",
            )
            return@withLock null
        }
        publish()
    }

    // -- use permits ---------------------------------------------------

    /**
     * Register a socket about to be opened, atomically with the policy
     * check authorising it.
     *
     * Refuses while any socket from a superseded policy is still open:
     * opening another beside it is the downgrade with company.
     */
    suspend fun acquirePermit(
        kind: TransportKind,
        validatedUnderEpoch: Long,
        stopWhatWasOpened: suspend () -> Unit,
    ): TransportUsePermit? {
        // A best-effort sweep of anything left over, OUTSIDE the lock.
        // Its result is advisory only: the decision below re-checks
        // everything, because between this and the issue a recovery can
        // move a permit into `revoking` without the epoch changing.
        if (mutex.withLock { unclosed.isNotEmpty() && revoking.isEmpty() }) {
            retryPending()
        }
        return mutex.withLock {
            // ONE final critical section over EVERY blocker. Splitting
            // these across separate sections is what let a permit be
            // issued against state that had moved since it was checked -
            // the check-then-act shape, one layer down.
            val now = _state.value
            when {
                revoking.isNotEmpty() -> {
                    log?.invoke(
                        "PRIVACY permit_refused kind=$kind reason=sweep_in_flight " +
                            "n=${revoking.size}",
                    )
                    null
                }
                unclosed.isNotEmpty() -> {
                    log?.invoke(
                        "PRIVACY permit_refused kind=$kind reason=unclosed_sockets " +
                            "n=${unclosed.size}",
                    )
                    null
                }
                validatedUnderEpoch != now.epoch -> {
                    log?.invoke(
                        "PRIVACY permit_refused kind=$kind validatedUnder=" +
                            "$validatedUnderEpoch liveEpoch=${now.epoch}",
                    )
                    null
                }
                kind !in TransportStrategy.from(now.requested).chain -> {
                    log?.invoke(
                        "PRIVACY permit_refused kind=$kind reason=not_in_chain " +
                            "mode=${now.requested}",
                    )
                    null
                }
                else -> {
                    val permit = TransportUsePermit(
                        epoch = now.epoch,
                        kind = kind,
                        stopWhatWasOpened = stopWhatWasOpened,
                        log = log,
                        onReleased = { released -> deregister(released) },
                        onUnclosed = { stranded -> moveToUnclosed(stranded) },
                    )
                    livePermits += permit
                    log?.invoke("PRIVACY permit_issued kind=$kind epoch=${now.epoch}")
                    permit
                }
            }
        }
    }


    private suspend fun deregister(permit: TransportUsePermit) {
        mutex.withLock {
            livePermits.remove(permit)
            unclosed.remove(permit)
        }
    }

    /**
     * A permit whose owner has gone but whose socket is not confirmed
     * closed.
     *
     * R-N1.17 P1: it must not simply stay in `livePermits`. Nothing sweeps
     * that set - it is the set of sockets believed healthy - so the
     * obligation to close this one would have no driver and no counter.
     * Moving it here puts it in `stillOpen`, where a switch can see it,
     * and in reach of `retryPending`, which is what actually retries the
     * close.
     */
    private suspend fun moveToUnclosed(permit: TransportUsePermit) {
        mutex.withLock {
            livePermits.remove(permit)
            if (permit !in unclosed && permit !in revoking) {
                unclosed += permit
                log?.invoke("PRIVACY permit_stranded kind=${permit.kind} epoch=${permit.epoch}")
            }
        }
    }

    /**
     * Try again to close sockets whose teardown failed or timed out, and
     * fold the result into the snapshot.
     *
     * Driven by the recovery timer, so it needs no user action and no
     * network nudge - on a stable network neither arrives.
     */
    suspend fun retryPending(): PolicyChangeOutcome {
        // The list and the epoch are captured in ONE critical section.
        // Two sections meant a request could land between them and the
        // sweep would then believe it belonged to the newer epoch.
        val pending: List<TransportUsePermit>
        val sweptEpoch: Long
        mutex.withLock {
            if (revoking.isNotEmpty()) {
                // R-N1.17 P1: a sweep is already running. Its permits are
                // out of `unclosed`, so proceeding would find an empty
                // list, report complete, and publish Applied over sockets
                // that are still being torn down.
                log?.invoke("PRIVACY retry_skipped reason=sweep_in_flight n=${revoking.size}")
                return PolicyChangeOutcome(
                    epoch = _state.value.epoch,
                    revoked = 0,
                    failed = 0,
                    timedOut = revoking.size,
                    stillOpen = revoking.toList(),
                )
            }
            pending = unclosed.toList()
            unclosed.clear()
            revoking += pending
            sweptEpoch = _state.value.epoch
        }
        val outcome = revokeAll(pending, "retry_pending")
        mutex.withLock {
            val now = _state.value
            if (now.epoch != sweptEpoch) {
                // R-N1.17 P1: a newer request landed while this sweep ran.
                // Applying its result would let an old epoch's success
                // publish the mode that epoch wanted.
                log?.invoke(
                    "PRIVACY retry_superseded sweptEpoch=$sweptEpoch liveEpoch=${now.epoch}",
                )
                return@withLock
            }
            // R-N1.17 P1: a socket retry closes sockets. It does NOT
            // complete the switch.
            //
            // It used to publish Applied as soon as the last socket
            // closed - and then the deferred settlement, arriving a
            // moment later to release the subsystems and start the
            // successor, found `status != Blocked`, concluded there was
            // nothing to do and returned success. The release and the
            // successor were skipped every time, deterministically.
            //
            // One owner completes a switch: `complete()`. This only
            // reports what is still open.
            _state.value = now.copy(stillOpen = outcome.stillOpen.size)
        }
        return outcome
    }

    /**
     * Persist a mode that was only ever read, not chosen - the legacy
     * migration.
     *
     * Refused once the epoch has moved: a migration parked behind a slow
     * disk could otherwise land after the user picked something else and
     * write the OLD mode into both keys. The running process would look
     * right and the next start would come up under a posture nobody
     * asked for.
     *
     * Returns true when it was written.
     */
    suspend fun migrateStoredMode(mode: PrivacyMode, expectedEpoch: Long): Boolean =
        mutex.withLock {
            val now = _state.value
            if (now.epoch != expectedEpoch) {
                log?.invoke(
                    "PRIVACY migration_refused mode=$mode expected=$expectedEpoch " +
                        "live=${now.epoch}",
                )
                return@withLock false
            }
            persistRequested(mode)
            log?.invoke("PRIVACY migration_written mode=$mode epoch=${now.epoch}")
            true
        }

    /** Whether anything from a superseded policy is still open. */
    suspend fun hasUnclosed(): Boolean =
        mutex.withLock { unclosed.isNotEmpty() || revoking.isNotEmpty() }

    private suspend fun revokeAll(
        doomed: List<TransportUsePermit>,
        reason: String,
    ): PolicyChangeOutcome {
        if (doomed.isEmpty()) {
            return PolicyChangeOutcome(
                epoch = mutex.withLock { _state.value.epoch },
                revoked = 0, failed = 0, timedOut = 0, stillOpen = emptyList(),
            )
        }
        var failed = 0
        var timedOut = 0
        val remaining = doomed.toMutableList()
        val stillOpen = mutableListOf<TransportUsePermit>()
        val callerJob = currentCoroutineContext()[Job]

        suspend fun sweep(list: List<TransportUsePermit>) {
            for (permit in list) {
                val ok = try {
                    withTimeoutOrNull(PERMIT_ATTEMPT_TIMEOUT_MS) { permit.revoke(reason) }
                        ?: run {
                            log?.invoke("PRIVACY permit_attempt_timeout kind=${permit.kind}")
                            false
                        }
                } catch (t: Throwable) {
                    // Includes a CancellationException raised by the
                    // teardown itself. Inside NonCancellable that cannot
                    // be our own cancellation, so it is a failed close -
                    // rethrowing would let a teardown quirk masquerade as
                    // the caller going away.
                    log?.invoke(
                        "PRIVACY permit_revoke_failed kind=${permit.kind} " +
                            "error=${t::class.simpleName}",
                    )
                    false
                }
                remaining.remove(permit)
                if (!ok) {
                    failed += 1
                    stillOpen += permit
                }
            }
        }

        // Non-cancellable and doubly bounded: a per-attempt budget so one
        // wedged teardown cannot starve the sockets behind it, and one
        // overall deadline because a per-attempt budget bounds nothing
        // when the number of permits is unbounded.
        withContext(NonCancellable) {
            withTimeoutOrNull(REVOKE_DEADLINE_MS) { sweep(doomed.toList()) }
        }
        if (remaining.isNotEmpty()) {
            timedOut = remaining.size
            stillOpen += remaining
            log?.invoke("PRIVACY permit_revoke_deadline unfinished=${remaining.size}")
        }
        withContext(NonCancellable) {
            mutex.withLock {
                revoking.removeAll(doomed)
                unclosed += stillOpen
            }
        }
        val outcome = PolicyChangeOutcome(
            epoch = withContext(NonCancellable) { mutex.withLock { _state.value.epoch } },
            revoked = doomed.size - stillOpen.size,
            failed = failed,
            timedOut = timedOut,
            stillOpen = stillOpen.toList(),
        )
        // Only now, with every socket swept and the register updated,
        // does the caller's cancellation take effect.
        if (callerJob?.isCancelled == true) {
            throw CancellationException("privacy-mode change cancelled by its caller")
        }
        return outcome
    }

    companion object {
        /** Budget for ONE teardown attempt, inside the overall deadline. */
        const val PERMIT_ATTEMPT_TIMEOUT_MS: Long = 1_000L

        /** Budget for revoking every permit of one switch. */
        const val REVOKE_DEADLINE_MS: Long = 3_000L
    }
}
