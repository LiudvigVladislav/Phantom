// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The answer a fake tor gives when there is nothing left of a daemon and
 * nothing holding threads.
 *
 * Fixtures that care about an unsettled lifecycle build their own answer;
 * this is only for the doubles whose subject is something else entirely.
 */
internal fun freeStopResponse(generation: Long = 0L): TorStopResponse =
    torStopNothingToStop(generation)

/**
 * The answer a fake tor gives when the attempt ended without being able to
 * say the daemon is gone. The release is never reached, so it stays
 * [ReleaseObservation.NotStarted] rather than claiming a failure of its own.
 */
internal fun unconfirmedStopResponse(generation: Long = 0L, cause: Throwable? = null): TorStopResponse {
    val observation = TorStopObservation(
        generation = generation,
        stop = StopOutcome.Unknown(generation, cause),
        release = ReleaseObservation.NotStarted,
    )
    return TorStopResponse(
        TorStopAttempt(generation, CompletableDeferred(observation)) { observation },
        observation.toResult(),
    )
}

/** The attempt is still running: no confirmation yet, and no claim beyond that. */
internal fun notConfirmedYet(generation: Long) =
    TorStopResult.NotConfirmed(generation, TorStopReason.NotConfirmedYet, cause = null)

/**
 * The attempt ended without being able to say the daemon is gone.
 *
 * Returns the result so a caller can go on to check WHY, which is the
 * difference between a test that pins a reason and one that accepts any
 * stumble at all.
 */
internal fun assertUnknown(result: TorStopResult): TorStopResult.NotConfirmed {
    val unconfirmed = assertIs<TorStopResult.NotConfirmed>(result)
    assertEquals(TorStopReason.Unknown, unconfirmed.reason)
    return unconfirmed
}
