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
 * Trek 2 Stage 2B-A — M1 wire-level contract test.
 *
 * Pins the structure of [phantom.core.transport.AndroidNativeOkHttpRestFallbackTransport]'s
 * `buildPollRequest(...)` helper so the L1 invariant cannot drift:
 *
 *   1. There is exactly ONE `if (longPollOptIn)` block in the source.
 *   2. Inside that block, BOTH [LONG_POLL_OPT_IN_HEADER] and
 *      [PADDED_POLL_OPT_IN_HEADER] are emitted via `builder.header(...)`.
 *   3. Neither header constant is referenced ANYWHERE ELSE in the file
 *      (i.e. no unconditional emission, no second code path).
 *
 * Failure of any one of these would let a future caller silently
 * produce an LP-alone or PP-alone wire shape that scope lock L1
 * forbids. Together with [LongPollOptInHeaderSeamTest] (which pins
 * the header-name strings) and [LongPollOptInOrchestratorSeamTest]
 * (which pins orchestrator → transport plumbing), this completes the
 * M1 contract gate without needing a MockWebServer dependency.
 */
class LongPollOptInWireRequestTest {

    private val transportSource: File by lazy {
        val candidates = listOf(
            File("../../shared/core/transport/src/androidMain/kotlin/phantom/core/transport/AndroidNativeOkHttpRestFallbackTransport.kt"),
            File("shared/core/transport/src/androidMain/kotlin/phantom/core/transport/AndroidNativeOkHttpRestFallbackTransport.kt"),
            File("../shared/core/transport/src/androidMain/kotlin/phantom/core/transport/AndroidNativeOkHttpRestFallbackTransport.kt"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate AndroidNativeOkHttpRestFallbackTransport.kt from the " +
                    "unit-test working directory. Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    @Test
    fun build_poll_request_has_exactly_one_long_poll_opt_in_branch() {
        val source = transportSource.readText(Charsets.UTF_8)
        // Match the actual code form `if (longPollOptIn) {` — excludes
        // kdoc mentions of the pattern (which don't end with `) {`).
        val branchCount = Regex("""if \(longPollOptIn\) \{""").findAll(source).count()
        assertEquals(
            1,
            branchCount,
            "AndroidNativeOkHttpRestFallbackTransport must contain EXACTLY ONE " +
                "`if (longPollOptIn) {` block. Found $branchCount — a second branch " +
                "would let one path emit headers without the other.",
        )
    }

    @Test
    fun the_one_branch_emits_both_long_poll_and_padded_poll_headers() {
        val source = transportSource.readText(Charsets.UTF_8)
        // Use the actual code form to skip the kdoc mention.
        val branchStart = source.indexOf("if (longPollOptIn) {")
        assertNotNull(
            branchStart.takeIf { it >= 0 },
            "Expected `if (longPollOptIn) {` block in transport source.",
        )
        val branchBlock = extractBalancedBraceBlock(source, branchStart)
            ?: fail("Could not extract balanced braces for the if-branch.")
        assertTrue(
            branchBlock.contains("LONG_POLL_OPT_IN_HEADER"),
            "The `if (longPollOptIn)` block must emit LONG_POLL_OPT_IN_HEADER. Block was:\n$branchBlock",
        )
        assertTrue(
            branchBlock.contains("PADDED_POLL_OPT_IN_HEADER"),
            "The `if (longPollOptIn)` block must emit PADDED_POLL_OPT_IN_HEADER. Block was:\n$branchBlock",
        )
        // Both must be set via `builder.header(...)` calls, not some other API.
        val headerCalls = Regex("""builder\.header\(""").findAll(branchBlock).count()
        assertTrue(
            headerCalls >= 2,
            "Expected at least two `builder.header(...)` calls inside the " +
                "`if (longPollOptIn)` block; found $headerCalls. Block was:\n$branchBlock",
        )
    }

    @Test
    fun header_constants_are_not_referenced_anywhere_else_in_file() {
        val source = transportSource.readText(Charsets.UTF_8)
        val longPollOccurrences = source.split("LONG_POLL_OPT_IN_HEADER").size - 1
        val paddedPollOccurrences = source.split("PADDED_POLL_OPT_IN_HEADER").size - 1
        // The constants are imported from commonMain (so each name is allowed
        // to appear in `import` lines and docstrings, but the actual code use
        // must be confined to the single `if (longPollOptIn)` block). The
        // simplest invariant that catches an unconditional emission is: the
        // header constants must appear ONLY inside the if-branch when used in
        // a `builder.header(...)` argument position. We assert that constraint
        // by extracting every `builder.header(...)` call and asserting the two
        // long-poll constants appear in exactly ONE such call each.
        val headerCalls = Regex("""builder\.header\(([^)]*)\)""").findAll(source).toList()
        val longPollUseCount = headerCalls.count {
            it.groupValues[1].contains("LONG_POLL_OPT_IN_HEADER")
        }
        val paddedPollUseCount = headerCalls.count {
            it.groupValues[1].contains("PADDED_POLL_OPT_IN_HEADER")
        }
        assertEquals(
            1,
            longPollUseCount,
            "LONG_POLL_OPT_IN_HEADER must be emitted from EXACTLY ONE " +
                "`builder.header(...)` call. Found $longPollUseCount.",
        )
        assertEquals(
            1,
            paddedPollUseCount,
            "PADDED_POLL_OPT_IN_HEADER must be emitted from EXACTLY ONE " +
                "`builder.header(...)` call. Found $paddedPollUseCount.",
        )
        // Defensive: confirm the constants appear at least once in total — if
        // both are zero, the file got refactored to a different mechanism and
        // these tests have stopped covering the actual emission path.
        assertTrue(longPollOccurrences > 0)
        assertTrue(paddedPollOccurrences > 0)
    }

    /**
     * Return the substring between the matching `{` and `}` of the
     * code block starting at [startIdx]. The function locates the
     * first `{` at or after [startIdx] and walks the braces.
     */
    private fun extractBalancedBraceBlock(source: String, startIdx: Int): String? {
        val openIdx = source.indexOf('{', startIndex = startIdx)
        if (openIdx < 0) return null
        var depth = 1
        var i = openIdx + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        if (depth != 0) return null
        return source.substring(openIdx + 1, i - 1)
    }
}
