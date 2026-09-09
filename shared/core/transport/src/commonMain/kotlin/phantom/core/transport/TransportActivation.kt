// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * The step between "the chain walk produced a transport" and "a socket
 * is open over it".
 *
 * R-N1.16 P1. This exists as a component rather than as a few lines
 * inside the Android service for one reason: review was right that a
 * source tripwire proves the service *calls* something, not that the
 * boundary behaves. The service cannot be constructed in a unit test, so
 * anything that lives only there is asserted from text. Moving the step
 * here means the fixtures drive the same code production does.
 *
 * What it guarantees, and what the guarantee rests on:
 *
 *  - the permit is acquired atomically with the policy check that
 *    authorises it, so a privacy switch either lands before the permit
 *    exists or finds it;
 *  - the socket is opened INSIDE [TransportUsePermit.useToOpen], so a
 *    revocation cannot land between the permission and the open;
 *  - the permit stays registered for as long as the socket is up, so a
 *    switch arriving later still finds it and tears it down.
 *
 * The last point is the one that is easy to get wrong by tidying up: a
 * permit deregistered on a successful open would leave an already-open
 * Direct socket invisible to the next privacy switch.
 */
class TransportActivation(
    /**
     * The manager whose walks this activates.
     *
     * R-N1.17: the privacy authority is taken FROM it rather than passed
     * in beside it. A separate parameter would still compile if someone
     * handed the two components different coordinators, and then a
     * privacy switch would revoke permits in one register while the
     * sockets lived in another - the two-owners defect this round is
     * about, reintroduced by a wiring mistake no type would catch.
     *
     * With one reference there is nothing to get wrong.
     */
    private val manager: TransportManager,
    /**
     * Open the socket. Runs under the permit, so a revocation is either
     * already visible (and this is never called) or waits behind it.
     */
    private val openSocket: suspend (ConnectedTransport) -> Unit,
    /** Tear the socket down. Called by a revocation, never speculatively. */
    private val closeSocket: suspend () -> Unit,
    private val log: ((String) -> Unit)? = null,
) {
    /**
     * Test seam, null in production: invoked after the permit is granted
     * and BEFORE the socket is opened.
     *
     * This is the window review named. Parking inside [openSocket]
     * instead would hold the permit's lock, so a revocation would queue
     * behind the open and the open would win - correct behaviour, but a
     * different branch. The window that matters is the one where the
     * permission is already in hand and nothing is holding anything.
     */
    internal var onBeforeOpenSeam: (suspend () -> Unit)? = null

    sealed interface Outcome {
        /**
         * The socket is open. [permit] stays live until the socket
         * closes; call [TransportUsePermit.release] then, and NOT
         * before - releasing early is what makes an open socket
         * invisible to the next switch.
         */
        data class Opened(val permit: TransportUsePermit) : Outcome

        /** The result was already stale; no permit was issued. */
        data object RefusedStale : Outcome

        /**
         * The permit was issued and revoked before the socket opened -
         * a privacy switch landed in exactly the window this class
         * exists for. Nothing was opened.
         */
        data object RevokedBeforeOpen : Outcome
    }

    /**
     * Take permission and open the socket, or refuse.
     *
     * There is deliberately no variant that opens without a permit.
     */
    suspend fun activate(connected: ConnectedTransport): Outcome {
        val permit = manager.policy.acquirePermit(connected.kind, connected.policyEpoch) {
            log?.invoke("ACTIVATION revoked kind=${connected.kind} — closing the socket")
            closeSocket()
        }
        if (permit == null) {
            log?.invoke("ACTIVATION refused kind=${connected.kind} reason=stale")
            return Outcome.RefusedStale
        }
        onBeforeOpenSeam?.invoke()
        val opened = permit.useToOpen { openSocket(connected) }
        if (opened == null) {
            log?.invoke(
                "ACTIVATION refused kind=${connected.kind} reason=revoked_before_open",
            )
            return Outcome.RevokedBeforeOpen
        }
        log?.invoke("ACTIVATION opened kind=${connected.kind} epoch=${permit.epoch}")
        return Outcome.Opened(permit)
    }
}
