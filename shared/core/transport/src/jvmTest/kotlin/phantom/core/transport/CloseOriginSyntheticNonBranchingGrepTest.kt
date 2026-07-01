// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QUIESCENCE-VALIDATION-MC-HALF-MINI-LOCK §4.3 (L-13.3.4) —
 * `closeOrigin = "synthetic"` non-branching discipline pin (MC-3,
 * 2026-07-01).
 *
 * The L1 mini-lock §13.3.4 lock says: dispatcher and state machine
 * MUST NOT branch on `WsSessionLifecycleEvent.Ended.closeOrigin`. The
 * synthetic tag exists so post-mortem observers can distinguish
 * synthetic-triggered events from real network deaths WITHOUT the
 * production code paths taking different arms based on the tag. If
 * production code branched on `closeOrigin == "synthetic"`, the whole
 * "synthetic and real byte-identical downstream" claim of the L1
 * mini-lock §6 collapses: a synthetic could take a code path that a
 * real Mode 2 close would not, breaking the honesty of the field
 * validation the MB half established.
 *
 * The MB half's PR #353 acceptance matrix included this as an
 * observational assertion (grep the diff for
 * `if (event.closeOrigin == "synthetic")`). MC closes the structural
 * gap: enforcing the rule at code-review time on EVERY PR going
 * forward, not just at PR #353's merge point.
 *
 * Search scope (per L1 mini-lock §4.3 wording):
 *
 *   - `shared/core/transport/src/commonMain` — all Kotlin sources.
 *   - `shared/core/transport/src/androidMain` — Android-side transport.
 *   - `apps/android/src/androidMain` — Android app-layer sources.
 *   - `apps/android/src/debug` — debug-only sources.
 *
 * EXCLUDED from the ban (a match here does NOT fail the test):
 *
 *   - `src/commonTest` / `src/androidUnitTest` / `src/jvmTest` — tests
 *     legitimately assert on synthetic events including the
 *     `closeOrigin == "synthetic"` shape.
 *   - `KtorRelayTransport.debugForceMode2Synthetic` — the L1 producer
 *     path that ASSIGNS `closeOrigin = "synthetic"` on the outbound
 *     synthetic event. Setting the tag is not branching on it; the
 *     match on the SETTER side is expected and honest.
 *
 * If a future refactor adds `if (event.closeOrigin == "synthetic")` or
 * `when (closeOrigin) { "synthetic" -> ... }` or equivalent to any
 * production dispatcher / state machine / orchestrator path, this
 * test breaks before the branch reaches CI.
 */
class CloseOriginSyntheticNonBranchingGrepTest {

    private val roots = listOf(
        "shared/core/transport/src/commonMain",
        "shared/core/transport/src/androidMain",
        "apps/android/src/androidMain",
        "apps/android/src/debug",
    )

    /**
     * Regex patterns that indicate a BRANCH on `closeOrigin`. False
     * positives are eliminated by the assignment-side ALLOW pattern
     * below.
     */
    private val branchPatterns: List<Regex> = listOf(
        // `if (... closeOrigin == "synthetic" ...)` in any bracketing.
        Regex("""if\s*\([^)]*closeOrigin\s*==\s*"synthetic"[^)]*\)"""),
        // `when (closeOrigin) { ... "synthetic" -> ... }` — the when
        // subject is `closeOrigin`.
        Regex("""when\s*\(\s*(?:event\.)?closeOrigin\s*\)"""),
        // `event.closeOrigin.equals("synthetic")` calls.
        Regex("""closeOrigin\.equals\s*\(\s*"synthetic""""),
    )

    /**
     * Assignment-side ALLOW pattern. `closeOrigin = "synthetic"` on
     * the LEFT of `=` is the L1 mini-lock §6 producer setting the tag
     * on a synthetic event; NOT a branch. A match on this line SHOULD
     * survive the ban.
     */
    private val assignmentPattern: Regex =
        Regex("""closeOrigin\s*=\s*"synthetic"""")

    @Test
    fun no_production_source_branches_on_closeOrigin_synthetic() {
        val projectRoot = findProjectRoot()
        val violations = mutableListOf<String>()

        for (relRoot in roots) {
            val rootDir = File(projectRoot, relRoot)
            if (!rootDir.exists() || !rootDir.isDirectory) {
                continue
            }
            rootDir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .forEach { file ->
                    val content = file.readText(Charsets.UTF_8)
                    val lines = content.lines()
                    lines.forEachIndexed { idx, line ->
                        val isBranch = branchPatterns.any { it.containsMatchIn(line) }
                        if (!isBranch) return@forEachIndexed
                        // Assignment-side allow-list: the LHS of an
                        // assignment `closeOrigin = "synthetic"` is
                        // legitimate — the L1 producer sets the tag.
                        // Only strip that if the line has NO
                        // branch-indicating token. This is why we
                        // ALSO check for `if`/`when` keywords: an
                        // assignment line won't match those.
                        val looksLikeAssignmentOnly =
                            assignmentPattern.containsMatchIn(line) &&
                                !line.trim().startsWith("if") &&
                                !line.trim().startsWith("when")
                        if (looksLikeAssignmentOnly) return@forEachIndexed
                        val rel = file.relativeTo(projectRoot).path.replace('\\', '/')
                        violations.add("$rel:${idx + 1}: ${line.trim()}")
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Production sources MUST NOT branch on " +
                "`WsSessionLifecycleEvent.Ended.closeOrigin`. The L1 " +
                "mini-lock §13.3.4 non-branching discipline requires " +
                "synthetic and real Mode 2 events to take BYTE-IDENTICAL " +
                "downstream code paths — the synthetic tag exists ONLY " +
                "for post-mortem observability, NOT for runtime dispatch. " +
                "Violations found:\n" +
                violations.joinToString("\n") { "  - $it" } +
                "\n\nIf the branch is legitimate (e.g., new post-mortem " +
                "tooling that reads events off a channel), add the " +
                "specific line to the CloseOriginSyntheticNonBranchingGrepTest " +
                "allow-list — but expect operator review of the exemption.",
        )
    }

    private fun findProjectRoot(): File {
        // The `roots` list is expressed relative to the project root.
        // Try common working-directory shapes.
        val candidates = listOf(
            File("."),
            File(".."),
            File("../.."),
            File("../../.."),
            File("../../../.."),
        )
        val root = candidates.firstOrNull { c ->
            roots.all { rel -> File(c, rel).exists() }
        }
        assertNotNull(
            root,
            "Could not resolve project root from the unit-test working directory. " +
                "Expected to find the four search roots under one of: " +
                candidates.joinToString { it.absolutePath },
        )
        return root
    }
}
