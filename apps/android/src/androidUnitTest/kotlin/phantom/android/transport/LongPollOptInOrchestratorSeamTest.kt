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
 * Trek 2 Stage 2B-A — M1 seam contract test (orchestrator → transport).
 *
 * Pins that `RestFallbackOrchestrator.kt` carries exactly ONE
 * `transport.poll(...)` call site, and that call site forwards the
 * orchestrator's `longPollEnabled` Boolean to the transport as the
 * `longPollOptIn` parameter. A future refactor that introduces a
 * second call site, or drops the parameter on the existing one, would
 * silently break the L1 lock (header emission no longer follows the
 * flag).
 *
 * Why a parse test instead of a running orchestrator: the running
 * `pollLoop` is gated by `RestStateMachine` mode transitions which
 * need a full bootstrap + capability=true + mode=RestActive setup to
 * exercise. The seam this test actually pins — "the one call site
 * propagates the flag" — is structural and a parse test catches it
 * faster than driving the state machine. Wire-level header emission
 * is locked separately by [LongPollOptInWireRequestTest] and the
 * constants are pinned by [LongPollOptInHeaderSeamTest] in the
 * commonTest source set.
 */
class LongPollOptInOrchestratorSeamTest {

    /**
     * The orchestrator source path, resolved relative to the JVM
     * unit-test working directory. Gradle's
     * `:apps:android:testDebugUnitTest` runs with the module
     * directory as cwd, so the relative path traverses up to the
     * repo root and then into `shared/core/transport`.
     */
    private val orchestratorSource: File by lazy {
        val candidates = listOf(
            File("../../shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt"),
            File("shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt"),
            File("../shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate RestFallbackOrchestrator.kt from the unit-test " +
                    "working directory. Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    @Test
    fun orchestrator_has_exactly_two_transport_poll_call_sites_legacy_plus_parallel() {
        val source = orchestratorSource.readText(Charsets.UTF_8)
        // Match the actual call shape (`transport.poll(` immediately
        // followed by a newline — i.e. multi-line argument list) so
        // KDoc mentions of `transport.poll(...)` are not counted.
        //
        // Trek 2 Stage 2B-A (B3) — there are now exactly TWO call
        // sites: the legacy `pollLoop()` and the parallel
        // `wsActivePollLoop()`. Both must forward the L1 / L2
        // invariants (asserted by the next two tests). A third call
        // site would mean a new code path that needs reviewing for
        // those invariants — the count assert is the gate that flags
        // it for explicit attention.
        val callSites = Regex("""transport\.poll\(\r?\n""").findAll(source).count()
        assertEquals(
            2,
            callSites,
            "RestFallbackOrchestrator must carry exactly TWO `transport.poll(...)` " +
                "call sites (legacy pollLoop + parallel wsActivePollLoop). Found " +
                "$callSites — anything else would mean either the parallel job has " +
                "been removed (L3 violation) or a new third call site has been " +
                "added without verifying L1 / L2 forwarding.",
        )
    }

    @Test
    fun all_call_sites_forward_long_poll_enabled() {
        val source = orchestratorSource.readText(Charsets.UTF_8)
        val matches = Regex("""transport\.poll\(\r?\n""").findAll(source).toList()
        assertTrue(matches.isNotEmpty(), "Expected at least one transport.poll(...) call form.")
        for ((idx, match) in matches.withIndex()) {
            val callSiteBlock = extractBalancedParenBlock(source, match.range.first)
                ?: fail("Could not extract balanced parens for call site #$idx at offset ${match.range.first}.")
            assertTrue(
                callSiteBlock.contains("longPollOptIn = longPollEnabled"),
                "Call site #$idx must forward `longPollOptIn = longPollEnabled` " +
                    "verbatim. Found call body:\n  transport.poll($callSiteBlock)\n" +
                    "A future refactor that hard-codes `longPollOptIn = false` or " +
                    "drops the parameter would silently bypass the L1 flag.",
            )
        }
    }

    @Test
    fun all_call_sites_forward_computed_read_timeout() {
        // Trek 2 Stage 2B-A (B2) + (B3) — every call site must funnel
        // its timeout through the L2 helper, NOT hard-code `null` and
        // NOT compute the value inline. Pins the helper as the single
        // mathematical entry point M2 covers.
        val source = orchestratorSource.readText(Charsets.UTF_8)
        val matches = Regex("""transport\.poll\(\r?\n""").findAll(source).toList()
        assertTrue(matches.isNotEmpty())
        for ((idx, match) in matches.withIndex()) {
            val callSiteBlock = extractBalancedParenBlock(source, match.range.first)
                ?: fail("Could not extract balanced parens for call site #$idx.")
            assertTrue(
                callSiteBlock.contains("readTimeoutMs = computeLongPollReadTimeoutMs("),
                "Call site #$idx must forward `readTimeoutMs = " +
                    "computeLongPollReadTimeoutMs(...)`. Found call body:\n" +
                    "  transport.poll($callSiteBlock)",
            )
            assertTrue(
                callSiteBlock.contains("longPollEnabled = longPollEnabled"),
                "Call site #$idx L2 gate must take `longPollEnabled` as the " +
                    "first input — found call body:\n$callSiteBlock",
            )
            assertTrue(
                callSiteBlock.contains("pollHoldSecs = "),
                "Call site #$idx L2 gate must take `pollHoldSecs` as the " +
                    "second input — found call body:\n$callSiteBlock",
            )
        }
    }

    /**
     * Return the substring between the matching `(` and `)` of the
     * `transport.poll(` call starting at [startIdx] (which points at
     * the `t`). Returns `null` if the parentheses never balance.
     */
    private fun extractBalancedParenBlock(source: String, startIdx: Int): String? {
        val openIdx = source.indexOf('(', startIndex = startIdx)
        if (openIdx < 0) return null
        var depth = 1
        var i = openIdx + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        if (depth != 0) return null
        return source.substring(openIdx + 1, i - 1)
    }
}
