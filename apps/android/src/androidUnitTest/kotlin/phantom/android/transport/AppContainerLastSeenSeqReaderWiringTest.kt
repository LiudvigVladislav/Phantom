// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-A (B3, L4) — review-fix contract test.
 *
 * Pins that `AppContainer.kt` actually passes a non-null
 * `lastSeenSeqReader` into `RestFallbackOrchestrator(...)`. Without
 * this wiring, the parallel `wsActivePollJob` would always poll with
 * `since_seq = null` regardless of any persisted cursor — defeating
 * the L4 cursor-read invariant in production even though the unit
 * tests still see it (they wire a fake reader directly into the
 * constructor).
 *
 * The test parses the AppContainer source: locates the SOLE
 * `phantom.core.transport.RestFallbackOrchestrator(...)` construction
 * inside the file, extracts its balanced-paren argument list, and
 * asserts the argument list contains `lastSeenSeqReader = ` followed
 * by an expression that references a non-null SQLDelight-backed
 * repository.
 *
 * A future refactor that deletes the wiring or replaces it with
 * `null` trips this test deliberately at PR-test time.
 */
class AppContainerLastSeenSeqReaderWiringTest {

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
    fun app_container_passes_non_null_last_seen_seq_reader_to_orchestrator() {
        val args = orchestratorConstructionArguments()
        assertTrue(
            args.contains("lastSeenSeqReader = "),
            "AppContainer's RestFallbackOrchestrator construction must pass " +
                "`lastSeenSeqReader = <expr>` explicitly. The orchestrator " +
                "constructor has a default of `null` for this parameter; " +
                "letting the default win means the production parallel " +
                "`wsActivePollJob` polls with `since_seq=null` always, which " +
                "exercises the L4 read path in tests but never in production. " +
                "Argument list was:\n$args",
        )
        // The wired expression must NOT be the literal `null`. A future
        // refactor that flips the bridge to `null` (e.g. during a
        // back-out attempt) would silently restore the broken
        // production behaviour without breaking unit tests, since unit
        // tests pass their own reader directly.
        val readerArgStart = args.indexOf("lastSeenSeqReader = ") +
            "lastSeenSeqReader = ".length
        // Extract a small window — until the next comma at the top
        // bracket level — and assert it does NOT begin with `null`.
        val windowEnd = findTopLevelComma(args, readerArgStart).let {
            if (it < 0) args.length else it
        }
        val readerArgExpr = args.substring(readerArgStart, windowEnd).trim()
        assertTrue(
            !readerArgExpr.startsWith("null"),
            "AppContainer must not pass `lastSeenSeqReader = null`. The " +
                "wired value must reference a non-null SQLDelight-backed " +
                "repository so the production path actually exercises L4's " +
                "read-only cursor seam. Found expression:\n  $readerArgExpr",
        )
        // Sanity: the wired expression should mention the repository
        // field by name. This catches a refactor that wires a stub
        // returning a constant (which would also break the
        // production L4 read path even if non-null).
        assertTrue(
            readerArgExpr.contains("lastSeenSeqRepo"),
            "Expected the lastSeenSeqReader expression to bridge through " +
                "`lastSeenSeqRepo` (the SQLDelight-backed " +
                "LastSeenSeqRepository field on AppContainer). Found:\n  " +
                "$readerArgExpr",
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
                "orchestrator's `lastSeenSeqReader`. The declaration was " +
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
    fun reader_lambda_only_calls_get_last_seen_seq() {
        // Lock L4 belt-and-braces: even though the
        // `LongPollCursorReader` interface has no write method, this
        // test pins that the bridge lambda inside AppContainer
        // references only the `getLastSeenSeq` method on the
        // repository. A bridge that grew an `upsertLastSeenSeq` call
        // (e.g. as part of a misguided "advance cursor on poll"
        // refactor) would trip this test deliberately — long before
        // the resulting production behaviour could ship.
        val args = orchestratorConstructionArguments()
        val readerArgStart = args.indexOf("lastSeenSeqReader = ")
        if (readerArgStart < 0) fail("Test prerequisite: lastSeenSeqReader assignment must be present.")
        val readerWindowEnd = findTopLevelComma(args, readerArgStart).let {
            if (it < 0) args.length else it
        }
        val readerExpr = args.substring(readerArgStart, readerWindowEnd)
        assertTrue(
            readerExpr.contains("getLastSeenSeq"),
            "Bridge lambda must call `getLastSeenSeq`. Found:\n  $readerExpr",
        )
        assertEquals(
            0,
            Regex("""upsertLastSeenSeq""").findAll(readerExpr).count(),
            "Bridge lambda must NOT call `upsertLastSeenSeq` — Stage 2B-A " +
                "is read-only on the cursor (lock L4). Found:\n  $readerExpr",
        )
    }
}
