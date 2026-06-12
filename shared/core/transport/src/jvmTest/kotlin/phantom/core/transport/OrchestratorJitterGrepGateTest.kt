// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trek 2 Stage 2B-B (C6, L10, M15) — source-level grep gate over
 * [RestFallbackOrchestrator.kt].
 *
 * Scope §L10: "All eleven existing `Random.Default.nextDouble()`
 * sites in `RestFallbackOrchestrator` migrate to draws from the
 * existing `Csprng` interface ... NO `Random.Default`,
 * `java.util.Random`, or `SecureRandom` references remain in the
 * orchestrator after this stage."
 *
 * This test reads the source file directly (JVM has direct file
 * access — running under Gradle's `:shared:core:transport:jvmTest`
 * task the working directory is the module root). It fails the
 * build the moment any legacy RNG class name reappears, which is
 * a stronger guarantee than a behavioural pin (the behavioural
 * pin in [RestFallbackOrchestratorC6Test] catches missed sites
 * only if they execute on the tested path).
 *
 * If a future commit genuinely needs to use one of the banned
 * references (e.g. a non-security shuffle), the gate must be
 * updated together with the production change — there is no
 * implicit allow-list.
 */
class OrchestratorJitterGrepGateTest {

    private val sourceFile: File =
        File("src/commonMain/kotlin/phantom/core/transport/RestFallbackOrchestrator.kt")

    @Test
    fun source_file_is_readable_from_jvmTest_working_directory() {
        // Sanity — if the working directory ever drifts off the
        // module root, the rest of this file's assertions are
        // vacuously true. This pin catches that drift.
        assertTrue(
            sourceFile.exists(),
            "expected ${sourceFile.absolutePath} to exist under the test working directory " +
                "(${File(".").absolutePath}). Gradle test runner sets working dir to the module " +
                "root; if this assertion fails, the build script has changed and the gate is " +
                "running against a different path.",
        )
    }

    @Test
    fun orchestrator_source_contains_no_kotlin_random_Random_references() {
        // `Random.Default` is the JVM linear-congruential PRNG, NOT a
        // CSPRNG. Stage 2B-B explicitly forbids it for every
        // security-sensitive draw in the poll path.
        assertNoMatchInCode(
            pattern = Regex("""\bRandom\.Default\b"""),
            classOfReference = "kotlin.random.Random.Default",
        )
        assertNoMatchInCode(
            pattern = Regex("""\bimport\s+kotlin\.random\.Random\b"""),
            classOfReference = "kotlin.random.Random import",
        )
    }

    @Test
    fun orchestrator_source_contains_no_java_util_Random_references() {
        assertNoMatchInCode(
            pattern = Regex("""\bjava\.util\.Random\b"""),
            classOfReference = "java.util.Random",
        )
    }

    @Test
    fun orchestrator_source_contains_no_SecureRandom_references() {
        assertNoMatchInCode(
            pattern = Regex("""\bSecureRandom\b"""),
            classOfReference = "java.security.SecureRandom (or any import alias)",
        )
    }

    @Test
    fun orchestrator_source_routes_every_jitter_call_through_nextJitterFactor_helper() {
        // The migration target per scope §L10: every jitter site
        // uses the [nextJitterFactor] helper, which is the only
        // call site of [Csprng.uniformLong] in the orchestrator.
        // A future direct call to `csprng.uniformLong(...)` from
        // anywhere other than the helper itself indicates a missed
        // routing — flag it here so the abstraction doesn't decay.
        val text = stripCommentsAndStrings(sourceFile.readText())
        val directUniformLongCalls = Regex("""\bcsprng\.uniformLong\b""").findAll(text).count()
        // Exactly one in-code reference is allowed: the
        // `nextJitterFactor()` body. Anything else means a poll
        // loop or send-path reached around the helper.
        assertEquals(
            1,
            directUniformLongCalls,
            "expected exactly one `csprng.uniformLong` call in the orchestrator source " +
                "(the body of `nextJitterFactor()`); got $directUniformLongCalls. " +
                "Every jitter site must go through the helper so the discretisation + " +
                "band mapping live in one place.",
        )
    }

    /**
     * Read the source file with comments + string literals
     * stripped, then assert [pattern] does not match. Stripping
     * comments avoids false-positives from KDoc/inline comments
     * that legitimately mention the class name in a migration
     * note.
     */
    private fun assertNoMatchInCode(pattern: Regex, classOfReference: String) {
        val codeOnly = stripCommentsAndStrings(sourceFile.readText())
        val matches = pattern.findAll(codeOnly).toList()
        assertTrue(
            matches.isEmpty(),
            "L10 grep gate failed: found ${matches.size} reference(s) to $classOfReference " +
                "in production code of `RestFallbackOrchestrator.kt`. Match(es): " +
                matches.take(3).joinToString { it.value } +
                ". Scope §L10 forbids legacy RNG references in the orchestrator.",
        )
    }

    /**
     * Crude but effective stripper for KDoc / line comments /
     * string literals so the grep gate doesn't false-positive on
     * documentation. Not a full Kotlin parser — but a Kotlin
     * source file with these classes referenced ONLY in comments
     * still passes the gate, which is the correct behaviour.
     */
    private fun stripCommentsAndStrings(text: String): String {
        var out = text
        // Block comments (KDoc + /* */).
        out = out.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        // Line comments.
        out = out.replace(Regex("""//[^\n]*"""), "")
        // Triple-quoted strings.
        out = out.replace(Regex(""""{3}[\s\S]*?"{3}"""), "")
        // Regular string literals (single-line). Greedy but
        // closes on the next non-escaped quote.
        out = out.replace(Regex(""""(?:\\.|[^"\\])*""""), "")
        return out
    }
}
