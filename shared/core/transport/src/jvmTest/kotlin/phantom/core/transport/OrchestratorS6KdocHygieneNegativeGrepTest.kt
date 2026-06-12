// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 5 P2.architect) — negative
 * grep over the S6-trigger KDoc surface inside
 * `RestFallbackOrchestrator.kt`. Complements
 * `S6DocsHygieneNegativeGrepTest` in the `:apps:android` module by
 * extending the doc-hygiene gate into `:shared:core:transport`.
 *
 * Round 4 added the Android-side hygiene gate but left
 * `RestFallbackOrchestrator.kt`'s `s6DebugTriggerEnabled` constructor
 * KDoc + `forceBreakerTripForS6TestTrigger()` KDoc on the round-2
 * `BuildConfig.DEBUG` story. Round 5 rewrites both to reference the
 * round-3 dedicated flag (`BuildConfig.S6_DEBUG_TRIGGER_ENABLED`).
 *
 * This test fences the rewrite so a future maintainer cannot
 * regress the doc to the stale model without tripping the build.
 *
 * Scope: the two S6-bearing KDoc blocks inside
 * `RestFallbackOrchestrator.kt`. The file contains many unrelated
 * KDoc blocks across ~3700 lines; this test extracts just the two
 * blocks anchored on the symbols below and grep-fences each one.
 */
class OrchestratorS6KdocHygieneNegativeGrepTest {

    private val orchestratorSource: File by lazy {
        val candidates = listOf(
            File("src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt"),
            File("shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt"),
            File("../../shared/core/transport/src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate RestFallbackOrchestrator.kt. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    /**
     * Extract the KDoc block (between `/**` and `*/`) that
     * IMMEDIATELY precedes the symbol [symbolAnchor]. Returns the
     * KDoc body including the `/**` and `*/` fences so the negative
     * grep can detect stale tokens in comment text.
     */
    private fun kdocPreceding(source: String, symbolAnchor: String): String {
        val symbolIdx = source.indexOf(symbolAnchor)
        assertTrue(
            symbolIdx >= 0,
            "Could not anchor KDoc lookup on symbol `$symbolAnchor` — " +
                "the symbol was not found in the orchestrator source.",
        )
        val kdocEnd = source.lastIndexOf("*/", startIndex = symbolIdx)
        assertTrue(
            kdocEnd >= 0 && kdocEnd < symbolIdx,
            "Could not find the closing `*/` of the KDoc preceding `$symbolAnchor`.",
        )
        val kdocStart = source.lastIndexOf("/**", startIndex = kdocEnd)
        assertTrue(
            kdocStart >= 0 && kdocStart < kdocEnd,
            "Could not find the opening `/**` of the KDoc preceding `$symbolAnchor`.",
        )
        return source.substring(kdocStart, kdocEnd + 2)
    }

    @Test
    fun s6DebugTriggerEnabled_kdoc_does_not_reference_stale_BuildConfig_DEBUG() {
        val text = orchestratorSource.readText(Charsets.UTF_8)
        val kdoc = kdocPreceding(text, "private val s6DebugTriggerEnabled: Boolean = false")
        assertTrue(
            !Regex("""\bBuildConfig\.DEBUG\b""").containsMatchIn(kdoc),
            "The KDoc preceding `s6DebugTriggerEnabled` MUST NOT reference " +
                "`BuildConfig.DEBUG` — the round-3 wire-up consumes " +
                "`BuildConfig.S6_DEBUG_TRIGGER_ENABLED == \"1\"`. A stale reference here " +
                "encourages a future refactor to either redundantly conjunct " +
                "`BuildConfig.DEBUG` OR drop the dedicated flag assuming it duplicates " +
                "DEBUG. KDoc body:\n$kdoc",
        )
    }

    @Test
    fun forceBreakerTripForS6TestTrigger_kdoc_does_not_reference_stale_BuildConfig_DEBUG() {
        val text = orchestratorSource.readText(Charsets.UTF_8)
        val kdoc = kdocPreceding(text, "suspend fun forceBreakerTripForS6TestTrigger()")
        assertTrue(
            !Regex("""\bBuildConfig\.DEBUG\b""").containsMatchIn(kdoc),
            "The KDoc preceding `forceBreakerTripForS6TestTrigger()` MUST NOT reference " +
                "`BuildConfig.DEBUG` — the round-3 wire-up consumes " +
                "`BuildConfig.S6_DEBUG_TRIGGER_ENABLED`. KDoc body:\n$kdoc",
        )
    }
}
