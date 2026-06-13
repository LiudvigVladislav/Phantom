// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 9 P2.docs-hygiene) —
 * negative grep that fences the round-9 activity-based S6 trigger
 * surface against round-2/3/5/7 stale tokens.
 *
 * Round 9 replaced the BroadcastReceiver + sender-permission scheme
 * (rounds 3-8) with a no-display Activity launched via `adb shell
 * am start`. The S6 surface in `AppContainer.kt` and
 * `S6BreakerTriggerActivity.kt` must not carry any references to:
 *
 *   * `BuildConfig.DEBUG` — round-2 gate; round 3 switched to the
 *     dedicated `S6_DEBUG_TRIGGER_ENABLED` BuildConfig field.
 *   * `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` /
 *     `registerReceiver` / `BroadcastReceiver` — receiver-era
 *     wiring (rounds 3-8).
 *   * `RECEIVER_NOT_EXPORTED` — additionally flagged because
 *     round-2's silent-broadcast-drop bug was the original
 *     motivation for the export classification debate.
 *   * `android.permission.DUMP` — round-7 sender permission; round 9
 *     dropped it because TECNO BF7 shell does not hold DUMP in
 *     broadcast permission scope.
 *   * `phantom.android.dev.permission.TRIGGER_S6` — round-5 custom
 *     signature permission; obsolete from round 7 onwards.
 *
 * Scope: the activity source file plus the S6 surface block inside
 * `AppContainer.kt` (between `restOrchestratorRef` field declaration
 * and the orchestrator construction site). The file contains many
 * unrelated `BuildConfig.DEBUG` uses outside that block (RC-DIRECT
 * arms, etc.).
 */
class S6DocsHygieneNegativeGrepTest {

    private fun locate(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("apps/android/$relative"),
            File("../apps/android/$relative"),
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate `$relative` from the unit-test working directory. " +
                    "Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    @Test
    fun activity_source_does_not_reference_stale_round9_model_phrases() {
        // Round-10 KDoc-hygiene fence. Round 9's KDoc described
        // the activity as calling `finish()` "before returning"
        // and bounded the co-installed-app risk with prose
        // assuming the activity was exported with no sender
        // permission. Round 10 changed both: `finish()` runs
        // inside the launch's `finally` block (after the
        // suspending dispatch returns), and the debug-only
        // activity carries `android:permission="...
        // INTERACT_ACROSS_USERS_FULL"` so a third-party app
        // cannot launch it at all.
        //
        // A stale phrase in the KDoc misleads the next reader
        // about both the lifecycle and the threat model.
        val source = locate("src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerActivity.kt")
            .readText(Charsets.UTF_8)
        val stalePhrases = listOf(
            // Round-9 timing model: finish() was called BEFORE the
            // suspending dispatch returned. The phrase "before
            // returning to the caller" is the load-bearing wording
            // that misled future maintainers. Narrower than round-10b's
            // "before returning" to reduce false positives on innocent
            // English in unrelated KDoc additions.
            "before returning to the caller",
            // Round-9 threat-model wording on co-installed third-party
            // apps. Round 10 closed the vector with
            // INTERACT_ACROSS_USERS_FULL; the KDoc must not re-introduce
            // the round-9 risk description.
            "co-installed third-party app",
            "co-installed-app risk",
            "any `exported=\"true\"` activity is launchable cross-process",
            "transient, non-silencing",
            // Round-10c (security L2 P2): the activity is a single-
            // purpose debug operator trigger. It MUST NOT be described
            // as a cross-user or privileged capability — those are
            // collateral side effects of the chosen permission proxy,
            // not the intent. Stale phrasing that romanticises the
            // capability would over-describe the threat surface in
            // published docs and could mislead a future maintainer
            // into broadening the trigger.
            "across users",
            "cross-user",
            "privileged",
        )
        for (phrase in stalePhrases) {
            assertTrue(
                !source.contains(phrase),
                "S6BreakerTriggerActivity.kt MUST NOT contain the round-9 KDoc phrase " +
                    "`$phrase`. Round 10 invalidates it (finish() now runs in the " +
                    "launch's finally block; the activity carries " +
                    "INTERACT_ACROSS_USERS_FULL so third-party callers cannot launch it). " +
                    "Rewrite the KDoc to describe the round-10 model.",
            )
        }
    }

    @Test
    fun activity_source_does_not_reference_stale_receiver_or_permission_models() {
        val source = locate("src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerActivity.kt")
            .readText(Charsets.UTF_8)
        val staleTokens = listOf(
            "RECEIVER_NOT_EXPORTED",
            "RECEIVER_EXPORTED",
            "registerReceiver",
            "BroadcastReceiver",
            "android.permission.DUMP",
            "phantom.android.dev.permission.TRIGGER_S6",
        )
        for (token in staleTokens) {
            assertTrue(
                !source.contains(token),
                "S6BreakerTriggerActivity.kt MUST NOT reference `$token` " +
                    "(receiver-era token from rounds 3-8). Round 10 uses a debug-only " +
                    "manifest-declared Activity with INTERACT_ACROSS_USERS_FULL permission; " +
                    "carrying the old token in the code or doc misleads a future refactor.",
            )
        }
        // `BuildConfig.DEBUG` was the round-2 gate. The round-3+
        // gate is `BuildConfig.S6_DEBUG_TRIGGER_ENABLED`. The
        // activity source MUST NOT reference the round-2 gate
        // anywhere.
        assertTrue(
            !Regex("""\bBuildConfig\.DEBUG\b""").containsMatchIn(source),
            "S6BreakerTriggerActivity.kt MUST NOT reference `BuildConfig.DEBUG` " +
                "(round-2 gate). The current gate is " +
                "`BuildConfig.S6_DEBUG_TRIGGER_ENABLED == \"1\"`.",
        )
    }

    @Test
    fun app_container_S6_surface_does_not_reference_stale_receiver_or_permission_models() {
        val text = locate("src/androidMain/kotlin/phantom/android/di/AppContainer.kt")
            .readText(Charsets.UTF_8)
        // Anchor the S6 surface on the structural element that
        // marks its start (the `restOrchestratorRef` field
        // declaration) and the next major unrelated wire-up site
        // (the `mediaCryptoLocal` construction, which is the first
        // non-S6 thing after the assignment block).
        val refStart = text.indexOf("@Volatile private var restOrchestratorRef")
        assertTrue(refStart >= 0, "S6 surface anchor `restOrchestratorRef` not found.")
        val triggerMethodStart = text.indexOf("suspend fun triggerS6BreakerForDebug")
        assertTrue(triggerMethodStart >= 0, "S6 trigger method not found.")
        // Match the method-body closing brace with either LF or
        // CRLF line endings — the file is stored with CRLF on
        // Windows checkouts.
        val terminatorRegex = Regex("""\r?\n    \}\r?\n""")
        val terminatorMatch = terminatorRegex.find(text, startIndex = triggerMethodStart)
        assertTrue(
            terminatorMatch != null,
            "S6 trigger method body terminator not found (looked for `\\r?\\n    }\\r?\\n` " +
                "after `suspend fun triggerS6BreakerForDebug`).",
        )
        val triggerMethodEnd = terminatorMatch!!.range.last + 1
        val s6SurfaceA = text.substring(refStart, triggerMethodEnd)
        // Also include the S6 wire-up block at the orchestrator
        // construction site (between `restOrchestratorRef =
        // restOrchestrator` and the next major wire-up site).
        val wireStart = text.indexOf("restOrchestratorRef = restOrchestrator")
        assertTrue(wireStart >= 0, "S6 wire-up anchor `restOrchestratorRef = restOrchestrator` not found.")
        val wireEnd = text.indexOf("// PR-M1w wire-up", startIndex = wireStart)
        assertTrue(wireEnd >= 0, "S6 wire-up end anchor (PR-M1w) not found.")
        val s6SurfaceB = text.substring(wireStart, wireEnd)
        val combinedS6Surface = s6SurfaceA + "\n" + s6SurfaceB

        val staleTokens = listOf(
            "RECEIVER_NOT_EXPORTED" to "round-2/3 receiver export classification",
            "registerReceiver(" to "receiver-era dynamic registration",
            "S6BreakerTriggerReceiver" to "receiver-era class reference",
            "android.permission.DUMP" to "round-7 sender permission",
            "phantom.android.dev.permission.TRIGGER_S6" to "round-5 custom permission",
        )
        for ((token, rationale) in staleTokens) {
            assertTrue(
                !combinedS6Surface.contains(token),
                "AppContainer S6 surface MUST NOT reference `$token` ($rationale). " +
                    "Round 9 is activity-based; receiver-era artefacts are gone.",
            )
        }
        assertTrue(
            !Regex("""\bBuildConfig\.DEBUG\b""").containsMatchIn(combinedS6Surface),
            "AppContainer S6 surface MUST NOT reference `BuildConfig.DEBUG` — round 3 " +
                "decoupled the gate onto `BuildConfig.S6_DEBUG_TRIGGER_ENABLED`. The file " +
                "has legitimate `BuildConfig.DEBUG` uses elsewhere, but not in the S6 block.",
        )
    }

    @Test
    fun s6_surface_files_do_not_carry_reviewer_role_attribution_phrases() {
        // Round-10d (neutral-voice cleanup): the S6 surface files
        // must not document fixes by attributing them to specific
        // reviewer-role labels (e.g. "P1.architect",
        // "P2.implementation-risk", "kmp-builder P2", "Layer-2
        // reviewers flagged...", etc). The fixes are the load-
        // bearing artifacts; the review-process labels are not.
        // Stale attribution leaks internal review-process plumbing
        // into the persistent code surface and dates the comments
        // unnecessarily.
        val targets = listOf(
            "src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerActivity.kt",
            "src/androidUnitTest/kotlin/phantom/android/transport/S6BreakerTriggerActivityContractTest.kt",
            "src/androidUnitTest/kotlin/phantom/android/transport/S6ActivityManifestContractTest.kt",
            "src/debug/AndroidManifest.xml",
        )
        val forbiddenPhrases = listOf(
            "P1.architect",
            "P2.architect",
            "P1.implementation-risk",
            "P2.implementation-risk",
            "P1.security",
            "P2.security",
            "P1.tester",
            "P2.tester",
            "P1.kmp-builder",
            "P2.kmp-builder",
            "kmp-builder P",
            "Layer-1",
            "Layer-2",
            "reviewers flagged",
        )
        for (relative in targets) {
            val file = locate(relative)
            val source = file.readText(Charsets.UTF_8)
            for (phrase in forbiddenPhrases) {
                assertTrue(
                    !source.contains(phrase),
                    "${file.absolutePath} MUST NOT contain the reviewer-role attribution " +
                        "phrase `$phrase`. Round 10d neutral-voice policy: describe the fix, " +
                        "not the review process that surfaced it.",
                )
            }
        }
    }
}
