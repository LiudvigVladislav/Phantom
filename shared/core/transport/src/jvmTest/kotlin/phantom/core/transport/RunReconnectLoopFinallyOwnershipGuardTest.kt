// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK MC-2 round-2 P1 fix (2026-07-01)
 * structural pin.
 *
 * `KtorRelayTransport.runReconnectLoop` has an outer `while` loop whose
 * `finally` block closes the per-generation `HttpClient` synchronously
 * after cancelling the per-generation `SupervisorJob`. On master before
 * this fix, that synchronous close was UNCONDITIONAL.
 *
 * The bug: `disconnectAndJoin(timeoutMs)` detaches
 * `currentGenerationClient` (nulls the field so a new generation cannot
 * race in mid-cleanup) and queues the blocking `HttpClient.close()`
 * onto `cleanupScope` under `cleanupCap = 8` back-pressure. This is the
 * whole reason `cleanupScope` + `cleanupCap` exist — Ktor's blocking
 * `HttpClient.close()` cannot be interrupted by cooperative
 * cancellation, so a stuck close would otherwise leak Dispatchers.IO
 * threads and file descriptors indefinitely.
 *
 * If the reconnect loop's `finally` ALSO calls `generationClient.close()`
 * synchronously on the SAME client instance, three regressions land at
 * once:
 *
 *   1. Double-close on the same `HttpClient`: undefined behaviour depending
 *      on the Ktor engine (some throw, some deadlock, some no-op).
 *   2. The synchronous close inside `finally` blocks the reconnect job's
 *      completion — the same completion that `disconnectAndJoin`'s
 *      bounded `job.join()` is waiting on. A hung blocking close
 *      inside the finally then times out the join and
 *      `disconnectAndJoin` returns `false`.
 *   3. `false` from `disconnectAndJoin` leaves `reconnectJob` alive (by
 *      contract: preserved for re-await). A subsequent
 *      `connect(...)` / `forceReconnect()` on the same instance falls
 *      into the "reconnect loop already alive" branch (see
 *      `KtorRelayTransport.kt` `connect` implementation) indefinitely.
 *      This is the exact class of hangs `cleanupScope + cleanupCap = 8`
 *      were introduced to prevent.
 *
 * The fix gates the synchronous close behind an ownership check:
 *
 * ```kotlin
 * if (currentGenerationClient === generationClient) {
 *     runCatching { generationClient.close() }
 *         .onFailure { … log … }
 *     currentGenerationClient = null
 * } else {
 *     relayLog(RelayLogLevel.INFO, "… Skipping synchronous generationClient.close() — teardown detached …")
 * }
 * ```
 *
 * This source-grep pin ensures a future refactor that removes the
 * ownership guard (dropping the identity check or removing the else
 * branch) breaks THIS test before it can regress the runtime contract.
 * Runtime exercise of the finally block requires driving the real
 * `runReconnectLoop` iteration to completion — heavy for a unit test
 * given the real `webSocket{}` body — so the pragmatic pin is
 * structural.
 */
class RunReconnectLoopFinallyOwnershipGuardTest {

    @Test
    fun runReconnectLoop_finally_synchronous_close_is_ownership_guarded() {
        val src = loadKtorRelayTransportSource()

        // The guard's identity-check header. `===` is Kotlin's referential
        // equality operator — exactly what we want here: same object
        // instance, not equal-by-content.
        val guardHeader = "if (currentGenerationClient === generationClient) {"

        val idx = src.indexOf(guardHeader)
        assertTrue(
            idx >= 0,
            "runReconnectLoop finally block MUST wrap the synchronous " +
                "generationClient.close() call in an ownership check: " +
                "`$guardHeader`. Without this guard, disconnectAndJoin's " +
                "cleanupScope-scheduled close races with a synchronous close " +
                "inside the reconnect job's finally block on the SAME " +
                "HttpClient instance — a hung close inside finally then " +
                "blocks the very job.join() disconnectAndJoin is waiting on, " +
                "and disconnectAndJoin returns false with reconnectJob still " +
                "alive. Subsequent connect() calls fall into the " +
                "'reconnect loop already alive' branch indefinitely.",
        )

        // Grab a slice AFTER the guard to assert the actual close sits
        // inside the guard's body. The window must be wide enough to
        // reach the else branch too — indented KDoc + long identifiers
        // + multi-line runCatching/onFailure block push the else past
        // the ~500-char mark, so we use 1500 to comfortably include it.
        val bodyWindow = src.substring(idx, minOf(idx + 1500, src.length))
        assertTrue(
            bodyWindow.contains("runCatching { generationClient.close() }"),
            "The ownership guard MUST wrap the runCatching { generationClient.close() } " +
                "call, not sit adjacent to it. Found the guard header but no " +
                "synchronous close inside the ~500-char window that follows.",
        )
        assertTrue(
            bodyWindow.contains("currentGenerationClient = null"),
            "Inside the guard body, currentGenerationClient MUST be nulled by " +
                "the reconnect loop after the synchronous close so a new " +
                "generation cannot mistakenly reuse the closed instance. " +
                "Found the guard header + close but no null-assignment in " +
                "the same window.",
        )

        // The else branch is honest observability — a log line proving the
        // guard fired on the teardown path (as opposed to being silently
        // skipped by a bug).
        assertTrue(
            bodyWindow.contains("Skipping synchronous generationClient.close()"),
            "The ownership guard SHOULD carry an else branch that logs the " +
                "skip (teardown owns the close via cleanupScope). Without " +
                "this log line, post-mortem cannot distinguish 'guard " +
                "correctly skipped the close because teardown owns it' from " +
                "'finally silently never ran the close'.",
        )
    }

    private fun loadKtorRelayTransportSource(): String {
        val candidates = listOf(
            File("shared/core/transport/src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt"),
            File("../shared/core/transport/src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt"),
            File("../../../commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt"),
            File("src/commonMain/kotlin/phantom/core/transport/KtorRelayTransport.kt"),
        )
        val f = candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate KtorRelayTransport.kt from the unit-test " +
                    "working directory. Tried: ${candidates.joinToString { it.absolutePath }}",
            )
        assertNotNull(f, "KtorRelayTransport source path could not be resolved.")
        return f.readText(Charsets.UTF_8)
    }
}
