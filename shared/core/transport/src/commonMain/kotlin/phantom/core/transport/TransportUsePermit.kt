// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Permission to actually USE a [ConnectedTransport] - to open a socket
 * over it - held for as long as that socket is open.
 *
 * ## Why a permit and not another check
 *
 * R-N1.16 P1, and the round met this shape four times before it stopped
 * reaching for another check:
 *
 *  1. read the generation, then write the ownership flag;
 *  2. read the privacy mode, then publish the transport;
 *  3. ask `isStillCurrent()`, then open the socket;
 *  4. bound the revocation, then report success anyway.
 *
 * The first three are checks followed by actions, and no care in the
 * check closes the gap after it. The fourth is subtler and worse: it
 * turns a failure into a success, so the switch reports itself complete
 * while the old egress is still live.
 *
 * A permit is different in kind. Acquiring it REGISTERS the intent with
 * the thing that can invalidate it, so a privacy switch is not racing an
 * unknown reader - it either happens before the permit is issued, or it
 * finds the permit. And a revocation that cannot confirm the socket
 * closed says so, rather than letting the switch proceed.
 *
 * ## What makes it atomic
 *
 * [useToOpen] and [revoke] take the same lock, so for any socket:
 *
 *  - the revoke lands first and the open is refused outright; or
 *  - the open lands first and the revoke tears it down.
 */
class TransportUsePermit internal constructor(
    /** The policy epoch this permit was issued under. */
    val epoch: Long,
    /** The transport it permits. */
    val kind: TransportKind,
    /**
     * Stop whatever [useToOpen] started. May be invoked more than once
     * across retries, but never after it has reported success.
     */
    private val stopWhatWasOpened: suspend () -> Unit,
    private val log: ((String) -> Unit)? = null,
    /**
     * Drop this permit from the issuer's register. Called only by
     * [release] - a permit whose close failed stays registered on
     * purpose, so it can be retried rather than lost.
     */
    private val onReleased: (suspend (TransportUsePermit) -> Unit)? = null,
    /**
     * Called when a release was REFUSED because the socket is not
     * confirmed closed. The issuer moves the permit to the set a sweep
     * retries, so the obligation to close it has somewhere to live.
     */
    private val onUnclosed: (suspend (TransportUsePermit) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private var revoked = false
    private var opened = false
    private var closed = false

    /**
     * Open the socket under this permit, or refuse.
     *
     * Returns null when the permit has already been revoked - the caller
     * must then open nothing. The check and the open are one critical
     * section, which is the whole point: a revocation cannot land
     * between them.
     */
    suspend fun <T> useToOpen(open: suspend () -> T): T? = mutex.withLock {
        if (revoked) {
            log?.invoke("PERMIT use_refused kind=$kind epoch=$epoch reason=revoked")
            return@withLock null
        }
        val result = open()
        opened = true
        log?.invoke("PERMIT used kind=$kind epoch=$epoch")
        result
    }

    /**
     * Revoke the permit and try to stop anything opened under it.
     *
     * Returns true only when there is nothing left open. False means the
     * socket may still be up, and the caller must treat whatever it was
     * doing as unsuccessful - it must NOT report a completed privacy
     * switch over a live socket from the previous policy.
     *
     * A [CancellationException] raised by the teardown itself leaves
     * here, but the sweeper above treats it as a failed close rather
     * than as its own cancellation - a teardown quirk must not be able
     * to masquerade as the caller going away.
     */
    internal suspend fun revoke(reason: String): Boolean = mutex.withLock {
        revoked = true
        closeIfNeeded(reason)
    }

    /**
     * Try again to close a socket whose earlier teardown failed or timed
     * out. The permit is already revoked; only the close is retried.
     */
    internal suspend fun retryClose(reason: String): Boolean = mutex.withLock {
        closeIfNeeded(reason)
    }

    /**
     * Close the socket THROUGH the permit, and release it if that
     * succeeded.
     *
     * R-N1.17 P1: the only way an owner may hand back a permit it opened
     * a socket under.
     *
     * `release()` used to be that way, and it worked by writing
     * `closed = true` - a claim, made by the one party that cannot check
     * it. Cancelling an owner ends the coroutine waiting on the transport,
     * not necessarily the transport, so the claim was routinely false and
     * a possibly-live socket left every register.
     *
     * Here the close is performed, and only a close that actually
     * succeeded permits the release. One that did not leaves the permit
     * registered - moved to the set a sweep retries - and returns false so
     * the caller can arm that sweep.
     */
    suspend fun closeAndRelease(reason: String): Boolean {
        val closedCleanly = mutex.withLock { closeIfNeeded(reason) }
        if (closedCleanly) return release()
        mutex.withLock { revoked = true }
        log?.invoke(
            "PERMIT close_and_release_refused kind=$kind epoch=$epoch reason=$reason",
        )
        onUnclosed?.invoke(this)
        return false
    }

    private suspend fun closeIfNeeded(reason: String): Boolean {
        if (!opened || closed) {
            log?.invoke("PERMIT revoked_nothing_open kind=$kind epoch=$epoch reason=$reason")
            return true
        }
        return try {
            stopWhatWasOpened()
            closed = true
            log?.invoke("PERMIT closed kind=$kind epoch=$epoch reason=$reason")
            true
        } catch (ce: CancellationException) {
            // Not a failed close. Let it out so the caller's cancellation
            // is not silently converted into a completed teardown.
            throw ce
        } catch (t: Throwable) {
            log?.invoke(
                "PERMIT close_failed kind=$kind epoch=$epoch reason=$reason " +
                    "error=${t::class.simpleName} — the socket may still be up",
            )
            false
        }
    }

    /** True while a socket opened under this permit has not been closed. */
    suspend fun needsClose(): Boolean = mutex.withLock { opened && !closed }

    /** For assertions and diagnostics. */
    suspend fun isRevoked(): Boolean = mutex.withLock { revoked }

    /** Whether a socket was actually opened under this permit. */
    suspend fun wasUsed(): Boolean = mutex.withLock { opened }

    /**
     * Release the permit because the socket closed for ordinary reasons.
     *
     * Deregisters, and returns whether it did. Only a socket that is
     * CONFIRMED closed may be released: releasing one that is still open
     * is what makes it invisible to the next privacy switch.
     */
    suspend fun release(): Boolean {
        val dropped = mutex.withLock {
            if (opened && !closed) {
                // R-N1.17 P1. This used to refuse only when the permit had
                // been REVOKED, and to write `closed = true` on every other
                // path - so an ordinary end of session claimed a close that
                // nobody had performed.
                //
                // That is the larger half of the same defect. Cancelling
                // the owner ends the coroutine WAITING on the transport; it
                // does not, by itself, end the loop the transport runs in
                // its own scope. The release that followed then dropped the
                // permit from the register, and a socket that might still
                // be up became invisible to every switch and every sweep.
                //
                // A permit whose socket is not confirmed closed is not
                // releasable, however it came to be here. It stays
                // registered - moved to the unclosed set, so a sweep will
                // retry the close - until a close actually succeeds.
                log?.invoke(
                    "PERMIT release_refused kind=$kind epoch=$epoch " +
                        "revoked=$revoked reason=socket_not_confirmed_closed",
                )
                revoked = true
                false
            } else {
                revoked = true
                closed = true
                true
            }
        }
        // Outside the permit lock: both callbacks take the issuer's lock,
        // and a revocation in flight already holds ours.
        if (dropped) onReleased?.invoke(this) else onUnclosed?.invoke(this)
        return dropped
    }
}

/**
 * What a privacy-mode change actually achieved.
 *
 * R-N1.16 P1: a change that could not close every socket opened under
 * the previous policy is NOT complete, and the caller must not release
 * subsystems, hand the connect lease on, or start anything new. Reporting
 * plain success there would leave the user looking at Ghost while a
 * Direct socket carried on.
 */
data class PolicyChangeOutcome(
    val epoch: Long,
    val revoked: Int,
    val failed: Int,
    val timedOut: Int,
    /** Permits still holding an open socket. They remain registered. */
    val stillOpen: List<TransportUsePermit>,
) {
    val isComplete: Boolean
        get() = failed == 0 && timedOut == 0 && stillOpen.isEmpty()
}

/**
 * What a privacy-mode switch achieved, from the caller's point of view.
 *
 * R-N1.16 P1. The REQUESTED mode must take effect for new chain walks
 * the instant the user asks for it - otherwise a walk started a moment
 * later would still choose Direct. But the EFFECTIVE mode, the one the
 * app may present as active, must not move while a socket opened under
 * the previous policy is still up. Showing Ghost over a live Direct
 * socket is the silent downgrade written into the UI.
 */
sealed interface PrivacyModeChangeResult {
    /** Everything from the old policy is closed. The mode is now active. */
    data class Applied(val mode: PrivacyMode) : PrivacyModeChangeResult

    /**
     * A newer request overtook this one, so its completion must not be
     * committed.
     *
     * R-N1.16 P1: a slow Standard -> Ghost switch finishing AFTER the
     * user has already asked for Private would otherwise write
     * effective = Ghost - a mode nobody currently wants, installed by a
     * late arrival. The epoch the switch bumped to is compared with the
     * live one, so lateness is detected rather than assumed away.
     */
    data class Superseded(
        val requested: PrivacyMode,
        val effective: PrivacyMode,
        val changeEpoch: Long,
        val liveEpoch: Long,
    ) : PrivacyModeChangeResult

    /**
     * The new mode governs new walks, but the switch is NOT complete:
     * something opened under the old policy could not be confirmed
     * closed. The effective mode therefore stays [effective], nothing was
     * released, no successor started, and [stillOpen] sockets remain
     * registered for the recovery sweep to close.
     */
    data class Blocked(
        val requested: PrivacyMode,
        val effective: PrivacyMode,
        val stillOpen: Int,
        val reason: String,
    ) : PrivacyModeChangeResult
}

/**
 * Decide what a privacy-mode switch may claim.
 *
 * Extracted so the decision can be driven by a fixture rather than
 * asserted from the text of an Android container: R-N1.16 kept finding
 * that a source tripwire proves a call exists, not that the boundary
 * behaves.
 *
 * The rule is deliberately conjunctive. A switch may present the new
 * mode as active only when BOTH the sockets from the old policy are
 * confirmed closed AND the chain walk that owned the connect lease is
 * confirmed stopped. Either one outstanding means something from the
 * previous posture may still be running, and calling that a completed
 * switch is the silent downgrade written into the UI.
 */
fun decidePrivacyModeChange(
    requested: PrivacyMode,
    effective: PrivacyMode,
    policyChangeComplete: Boolean,
    walkQuiesced: Boolean,
    stillOpen: Int,
    /** The epoch this switch bumped the policy to. */
    changeEpoch: Long = 0L,
    /** The epoch in force now. Different means a newer request overtook us. */
    liveEpoch: Long = changeEpoch,
): PrivacyModeChangeResult = when {
    // Checked FIRST: a superseded switch must not commit anything, however
    // cleanly it finished. Its own success is exactly what makes it
    // dangerous - it would install a mode the user has since replaced.
    changeEpoch != liveEpoch -> PrivacyModeChangeResult.Superseded(
        requested = requested,
        effective = effective,
        changeEpoch = changeEpoch,
        liveEpoch = liveEpoch,
    )
    policyChangeComplete && walkQuiesced -> PrivacyModeChangeResult.Applied(requested)
    !policyChangeComplete -> PrivacyModeChangeResult.Blocked(
        requested = requested,
        effective = effective,
        stillOpen = stillOpen,
        reason = "sockets_from_previous_policy_still_open",
    )
    else -> PrivacyModeChangeResult.Blocked(
        requested = requested,
        effective = effective,
        stillOpen = stillOpen,
        reason = "chain_walk_not_quiesced",
    )
}
