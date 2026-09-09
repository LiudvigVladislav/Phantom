// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.di

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import phantom.core.transport.RestEgressGate
import phantom.core.transport.TransportManager

/**
 * N1-F2 R-N1.4/R-N1.5 — the privacy-mode transition, as one transaction.
 *
 * R-N1.3 ran the REST revocation under `NonCancellable` and then
 * re-asserted the caller's cancellation with `ensureActive()` — but it
 * did so BEFORE the socket teardown. A user who switched to
 * Private/Ghost and then left the screen could therefore cancel the
 * transition in the gap: the preference was already persisted and the UI
 * already showed the restrictive mode, while the old Direct WSS
 * connection was never torn down. R-N1.4 made revoke, disconnect and
 * release one `NonCancellable` block.
 *
 * ## R-N1.5: the transaction could still report a clean switch it had
 * not achieved
 *
 * Two of the three steps threw their outcome away:
 *
 *  - `KtorRelayTransport.disconnect()` called `teardownAndJoin` and
 *    DISCARDED its Boolean, so a join that timed out was
 *    indistinguishable from a clean teardown;
 *  - `TransportManager.release()` wrapped both subsystem stops in bare
 *    `runCatching` and returned `Unit`, so a Xray or Tor that refused to
 *    stop was invisible.
 *
 * Both now return structured outcomes, and `clean` is false if the WSS
 * join times out or either subsystem fails to stop.
 *
 * ## R-N1.5: no user payload over the old Direct transport
 *
 * `disconnect()` routes to `teardownAndJoin(flushBeforeClose = true)`,
 * which spends up to three seconds best-effort flushing `pendingOutbox`
 * and `pendingAcks` through `sendRaw` on the still-live socket BEFORE
 * closing it. That is correct for logout and shutdown. It is exactly
 * wrong for a privacy switch: the user has just asked for Direct traffic
 * to stop, and this would keep sending their queued payloads over it.
 *
 * The teardown therefore uses `disconnectAndJoin(timeoutMs)`, which is
 * `flushBeforeClose = false`. Pending items stay in their stores and are
 * delivered by the new transport once it is up — nothing is dropped, and
 * nothing leaves over the old posture.
 *
 * This lives outside `AppContainer` so it can be tested. Constructing an
 * `AppContainer` in a unit test is not practical, and R-N1.3 fell back
 * to a source-text tripwire for exactly that reason.
 */
internal data class PrivacyTeardownOutcome(
    val revocation: RestEgressGate.RevocationResult?,
    val revokeFailure: Throwable?,
    /** `true` only if the socket teardown joined within its budget. */
    val disconnectJoinedCleanly: Boolean?,
    val disconnectFailure: Throwable?,
    val release: TransportManager.ReleaseOutcome?,
    val releaseFailure: Throwable?,
    /**
     * `true` when the chain walk holding the connect lease was cancelled
     * and confirmed finished before the release ran.
     *
     * `false` means quiescence could not be confirmed and the release was
     * therefore SKIPPED - see the note on [runPrivacyModeTeardown].
     */
    val walkQuiesced: Boolean = true,
) {
    /**
     * Every step ran, the egress revocation joined cleanly, the WSS
     * teardown joined within its budget, and both proxy subsystems
     * stopped.
     *
     * A `false` here does NOT mean the posture is open: new work is
     * already refused by the live policy read. It means some part of the
     * old connection may have outlived the switch, and that must be
     * reported rather than assumed away.
     */
    val clean: Boolean
        get() = revokeFailure == null &&
            disconnectFailure == null &&
            releaseFailure == null &&
            revocation?.joinedCleanly != false &&
            disconnectJoinedCleanly != false &&
            release?.clean != false

    /**
     * Whether this teardown may be treated as a COMPLETED switch.
     *
     * R-N1.17 P1: the switch decision used to look at the subsystem
     * release alone, so a REST egress revocation that did not join, or a
     * WSS disconnect that timed out, still produced `Applied` and a
     * successor - old-policy I/O outliving a switch the UI showed as
     * done. That is the same silent downgrade as a proxy that would not
     * stop; [clean] already knows about all of them.
     *
     * `release != null` on top of [clean], because a release that was
     * SKIPPED - the walk would not quiesce, so the teardown correctly
     * refused to run it - passes `clean` on a null and is not a confirmed
     * teardown by any reading.
     */
    val confirmed: Boolean
        get() = clean && release != null
}

/** Join budget for the WSS teardown during a privacy-mode switch. */
internal const val PRIVACY_SWITCH_DISCONNECT_TIMEOUT_MS: Long = 10_000L

/**
 * Run the three teardown steps as one non-cancellable transaction.
 *
 * Every step runs even if an earlier one failed: a revocation that
 * throws must not leave the old socket connected, and a disconnect that
 * throws must not skip the release. Failures are collected and returned
 * rather than thrown, so the caller can report a partial teardown
 * instead of pretending the switch was clean.
 *
 * [disconnectAndJoin] must NOT flush the outbox over the old transport —
 * see the class kdoc.
 */
internal suspend fun runPrivacyModeTeardown(
    revoke: suspend () -> RestEgressGate.RevocationResult?,
    disconnectAndJoin: suspend () -> Boolean,
    /**
     * Cancel the chain walk that owns the connect lease and wait for it
     * to finish. Returns true only on confirmed quiescence.
     *
     * R-N1.16 P1: this step did not exist, and its absence was a No
     * Silent Downgrade violation rather than an ordering nicety.
     * `disconnectAndJoin` tears down the WSS socket; it says nothing
     * about the OUTER chain walk, which reads the privacy mode once at
     * its start and is not serialised against `release()`. So a
     * Standard -> Ghost switch could leave a Direct walk in flight that
     * went on to open a Direct WSS after the user had already been shown
     * Ghost. The REST revocation does not prevent that: it covers
     * REST/media leases, not the socket the walk is about to open.
     */
    handOverWalk: suspend () -> Boolean,
    release: suspend () -> TransportManager.ReleaseOutcome?,
): PrivacyTeardownOutcome = withContext(NonCancellable) {
    var revocation: RestEgressGate.RevocationResult? = null
    var revokeFailure: Throwable? = null
    var disconnectJoined: Boolean? = null
    var disconnectFailure: Throwable? = null
    var releaseOutcome: TransportManager.ReleaseOutcome? = null
    var releaseFailure: Throwable? = null

    try {
        revocation = revoke()
    } catch (t: Throwable) {
        revokeFailure = t
    }
    try {
        disconnectJoined = disconnectAndJoin()
    } catch (t: Throwable) {
        disconnectFailure = t
    }
    // Cancel and JOIN the outer walk before anything is released. On a
    // failed join the release is skipped entirely: stopping subsystems
    // under a walk we could not stop is the defect, not the recovery.
    val quiesced = try {
        handOverWalk()
    } catch (t: Throwable) {
        releaseFailure = t
        false
    }

    if (quiesced) {
        try {
            releaseOutcome = release()
        } catch (t: Throwable) {
            releaseFailure = t
        }
    }

    PrivacyTeardownOutcome(
        revocation = revocation,
        revokeFailure = revokeFailure,
        disconnectJoinedCleanly = disconnectJoined,
        disconnectFailure = disconnectFailure,
        release = releaseOutcome,
        releaseFailure = releaseFailure,
        walkQuiesced = quiesced,
    )
}
