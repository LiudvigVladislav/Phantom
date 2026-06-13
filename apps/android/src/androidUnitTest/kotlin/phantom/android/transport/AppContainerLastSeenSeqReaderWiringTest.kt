// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C3, L4 + OQ-6 LOCK) — review-fix contract test.
 *
 * Pins that `AppContainer.kt` actually passes a non-null
 * `cursorRepository` into `RestFallbackOrchestrator(...)` and that
 * the bridge implements BOTH `getLastSeenSeq` AND `upsertLastSeenSeq`
 * by delegating to the SQLDelight-backed repository field. Without
 * this wiring, the orchestrator's `ackInboundAndAdvanceCursor` would
 * silently no-op the persistent write half of the L4 contract and
 * the cursor would stay at `null` in production even after a
 * successful ack.
 *
 * **Why a textual contract test on the AppContainer source.** The
 * orchestrator constructor parameter has a default of `null`, so a
 * refactor that drops the wiring (or flips it to `null` during a
 * back-out attempt) compiles cleanly but breaks the production L4
 * write path. Unit tests pass their own `cursorRepository` directly
 * into the constructor so they never see the production bridge. The
 * textual assertion below trips at PR-test time on any such
 * regression.
 *
 * The test parses the AppContainer source: locates the SOLE
 * `phantom.core.transport.RestFallbackOrchestrator(...)` construction
 * inside the file, extracts its balanced-paren argument list, and
 * asserts the argument list contains `cursorRepository = ` followed
 * by an expression that (a) is NOT `null` and (b) references the
 * SQLDelight-backed `lastSeenSeqRepo` field for BOTH read and write
 * methods.
 *
 * Filename retained as `AppContainerLastSeenSeqReaderWiringTest.kt`
 * for git history continuity — the class name moved to reflect the
 * Stage 2B-B contract.
 */
class AppContainerCursorRepositoryWiringTest {

    private val appContainerSource: File by lazy {
        val candidates = listOf(
            File("src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
            File("apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
            File("../apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate AppContainer.kt from the unit-test working " +
                    "directory. Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    private fun orchestratorConstructionArguments(): String {
        val source = appContainerSource.readText(Charsets.UTF_8)
        val constructionStart = source.indexOf(
            "phantom.core.transport.RestFallbackOrchestrator(",
        )
        assertNotNull(
            constructionStart.takeIf { it >= 0 },
            "AppContainer must contain at least one " +
                "`phantom.core.transport.RestFallbackOrchestrator(...)` " +
                "construction site.",
        )
        val openIdx = source.indexOf('(', startIndex = constructionStart)
        var depth = 1
        var i = openIdx + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        if (depth != 0) fail("Unbalanced parens in AppContainer RestFallbackOrchestrator construction.")
        return source.substring(openIdx + 1, i - 1)
    }

    @Test
    fun app_container_passes_non_null_cursor_repository_to_orchestrator() {
        val args = orchestratorConstructionArguments()
        assertTrue(
            args.contains("cursorRepository = "),
            "AppContainer's RestFallbackOrchestrator construction must pass " +
                "`cursorRepository = <expr>` explicitly. The orchestrator " +
                "constructor has a default of `null` for this parameter; " +
                "letting the default win means the production " +
                "`ackInboundAndAdvanceCursor` no-ops the persistent write " +
                "and the cursor stays at `null` after a successful ack. " +
                "Argument list was:\n$args",
        )
        // The wired expression must NOT be the literal `null`. A future
        // refactor that flips the bridge to `null` (e.g. during a
        // back-out attempt) would silently restore the broken
        // production behaviour without breaking unit tests, since unit
        // tests pass their own repository directly.
        val argStart = args.indexOf("cursorRepository = ") +
            "cursorRepository = ".length
        // Extract a window until the next top-bracket-level comma and
        // assert the expression does NOT begin with `null`.
        val windowEnd = findTopLevelComma(args, argStart).let {
            if (it < 0) args.length else it
        }
        val argExpr = args.substring(argStart, windowEnd).trim()
        assertTrue(
            !argExpr.startsWith("null"),
            "AppContainer must not pass `cursorRepository = null`. The " +
                "wired value must reference a non-null SQLDelight-backed " +
                "repository so the production path actually exercises L4's " +
                "read AND write cursor seam. Found expression:\n  $argExpr",
        )
        // Sanity: the wired expression should mention the repository
        // field by name. This catches a refactor that wires a stub
        // returning a constant (which would also break the
        // production L4 read/write path even if non-null).
        assertTrue(
            argExpr.contains("lastSeenSeqRepo"),
            "Expected the cursorRepository expression to bridge through " +
                "`lastSeenSeqRepo` (the SQLDelight-backed " +
                "LastSeenSeqRepository field on AppContainer). Found:\n  " +
                "$argExpr",
        )
    }

    @Test
    fun app_container_declares_a_sql_delight_backed_last_seen_seq_repo_field() {
        val source = appContainerSource.readText(Charsets.UTF_8)
        // The field declaration is what makes the production path
        // actually persist the cursor (in Stage 2B-B). Pinning the
        // declaration here guards against a refactor that removes the
        // field but leaves the wiring lambda referencing it (which
        // would be a compile error, but better to fail fast with a
        // clear message at this test layer too).
        assertTrue(
            source.contains("SqlDelightLastSeenSeqRepository(dbHolder.database)"),
            "AppContainer must construct `SqlDelightLastSeenSeqRepository(" +
                "dbHolder.database)` so the production path has a real " +
                "SQLDelight-backed repository to bridge through to the " +
                "orchestrator's `cursorRepository`. The declaration was " +
                "not found in AppContainer.kt.",
        )
    }

    /**
     * Find the position of the next comma at the top bracket nesting
     * level starting at [start]. Returns -1 if no such comma exists
     * before the end of the source.
     */
    private fun findTopLevelComma(source: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    @Test
    fun cursor_repository_bridge_implements_both_read_and_write_methods() {
        // L4 + OQ-6 LOCK belt-and-braces: a Stage 2B-B bridge MUST
        // implement BOTH `getLastSeenSeq` AND `upsertLastSeenSeq` so
        // the orchestrator's `ackInboundAndAdvanceCursor` has a real
        // persistent write target. A bridge that only implements
        // read (e.g. accidentally rebuilt as a SAM for the legacy
        // `LongPollCursorReader` interface) would compile cleanly
        // because the orchestrator parameter type accepts any
        // `LongPollCursorRepository?` — but the resulting object
        // could not be constructed against the two-method interface
        // anyway. This test catches a regression at PR-test time
        // BEFORE the orchestrator-side write path becomes a no-op.
        val args = orchestratorConstructionArguments()
        val argStart = args.indexOf("cursorRepository = ")
        if (argStart < 0) fail("Test prerequisite: cursorRepository assignment must be present.")
        val windowEnd = findTopLevelComma(args, argStart).let {
            if (it < 0) args.length else it
        }
        val argExpr = args.substring(argStart, windowEnd)
        assertTrue(
            argExpr.contains("getLastSeenSeq"),
            "Bridge must implement `getLastSeenSeq`. Found:\n  $argExpr",
        )
        assertTrue(
            argExpr.contains("upsertLastSeenSeq"),
            "Bridge MUST implement `upsertLastSeenSeq` so the L4 + OQ-6 " +
                "LOCK write half is actually wired. A bridge missing this " +
                "method would silently leave production cursors unpersisted " +
                "after a successful ack. Found:\n  $argExpr",
        )
    }
}
