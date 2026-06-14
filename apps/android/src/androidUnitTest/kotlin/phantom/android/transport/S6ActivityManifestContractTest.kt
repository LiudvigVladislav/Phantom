// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 10) — manifest
 * contract test for the activity-based S6 trigger surface.
 *
 * Round 10 moved the activity declaration from
 * `androidMain/AndroidManifest.xml` to
 * `apps/android/src/debug/AndroidManifest.xml` (debug-variant
 * source set). Release APKs no longer carry the declaration at all
 * — the activity is invisible to `pm list packages -f` and
 * `dumpsys package` on release. The `androidMain` manifest MUST NOT
 * declare the activity; the `debug` manifest MUST declare it with
 * the round-10 attributes.
 *
 * Pins:
 *
 *   * `src/debug/AndroidManifest.xml` declares
 *     `phantom.android.dev.S6BreakerTriggerActivity` with
 *     `android:exported="true"`, `android:theme="@android:style/
 *     Theme.NoDisplay"`, AND
 *     `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`.
 *   * `androidMain/AndroidManifest.xml` does NOT declare the
 *     activity (the round-9 regression where the activity lived
 *     in the shared manifest is fenced).
 *   * Neither manifest carries the round-5 custom permission name
 *     or the round-7 DUMP `<uses-permission>`.
 */
class S6ActivityManifestContractTest {

    private val debugManifest: File by lazy {
        val candidates = listOf(
            File("src/debug/AndroidManifest.xml"),
            File("apps/android/src/debug/AndroidManifest.xml"),
            File("../apps/android/src/debug/AndroidManifest.xml"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate debug-variant AndroidManifest.xml. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    private val mainManifest: File by lazy {
        val candidates = listOf(
            File("src/androidMain/AndroidManifest.xml"),
            File("apps/android/src/androidMain/AndroidManifest.xml"),
            File("../apps/android/src/androidMain/AndroidManifest.xml"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate androidMain AndroidManifest.xml. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    @Test
    fun debug_manifest_declares_activity_with_round_10_attributes() {
        val text = debugManifest.readText(Charsets.UTF_8)
        val activityRegex = Regex(
            """<activity\b[^>]*?android:name="phantom\.android\.dev\.S6BreakerTriggerActivity"[^>]*?(?:/>|>)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = activityRegex.find(text)
        assertTrue(
            match != null,
            "Debug-variant manifest MUST declare `<activity android:name=\"phantom.android.dev." +
                "S6BreakerTriggerActivity\" ... />`. This is the round-10 entry point for the " +
                "S6 controllable breaker trigger.",
        )
        val element = match!!.value
        assertTrue(
            element.contains("""android:exported="true""""),
            "Activity MUST be declared `android:exported=\"true\"` so `adb shell am start` " +
                "can launch it cross-process. Found element: $element",
        )
        assertTrue(
            element.contains("""android:theme="@android:style/Theme.NoDisplay""""),
            "Activity MUST use `android:theme=\"@android:style/Theme.NoDisplay\"`. " +
                "Found element: $element",
        )
        assertTrue(
            element.contains("""android:permission="android.permission.INTERACT_ACROSS_USERS_FULL""""),
            "Round 10 sender-permission lock — Activity MUST declare `android:permission=\"android." +
                "permission.INTERACT_ACROSS_USERS_FULL\"` so co-installed third-party apps " +
                "cannot launch it. The shell uid (which is what `adb shell am start` runs " +
                "as) satisfies this permission; verified on TECNO BF7-12 round 10. Found " +
                "element: $element",
        )
    }

    @Test
    fun main_manifest_does_not_declare_S6_activity() {
        val text = mainManifest.readText(Charsets.UTF_8)
        val activityRegex = Regex(
            """<activity\b[^>]*?android:name="phantom\.android\.dev\.S6BreakerTriggerActivity"[^>]*?(?:/>|>)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            !activityRegex.containsMatchIn(text),
            "androidMain/AndroidManifest.xml MUST NOT declare `S6BreakerTriggerActivity`. " +
                "Round 10 moved the declaration to the debug-variant overlay at " +
                "`src/debug/AndroidManifest.xml` so release APKs do not carry the component. " +
                "A leftover declaration in androidMain regresses to the round-9 manifest-" +
                "surface leak.",
        )
    }

    @Test
    fun no_round5_custom_permission_remains_in_either_manifest() {
        for (manifest in listOf(mainManifest, debugManifest)) {
            val text = manifest.readText(Charsets.UTF_8)
            assertTrue(
                !text.contains("phantom.android.dev.permission.TRIGGER_S6"),
                "AndroidManifest.xml at ${manifest.absolutePath} MUST NOT reference the " +
                    "round-5 custom permission `phantom.android.dev.permission.TRIGGER_S6`. " +
                    "Round 10 uses `android.permission.INTERACT_ACROSS_USERS_FULL` on the " +
                    "debug-only activity.",
            )
        }
    }

    @Test
    fun no_round7_DUMP_uses_permission_remains_in_either_manifest() {
        val dumpRegex = Regex(
            """<uses-permission\b[^>]*?android:name="android\.permission\.DUMP"[^>]*?/>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (manifest in listOf(mainManifest, debugManifest)) {
            val text = manifest.readText(Charsets.UTF_8)
            assertTrue(
                !dumpRegex.containsMatchIn(text),
                "AndroidManifest.xml at ${manifest.absolutePath} MUST NOT declare " +
                    "`<uses-permission android:name=\"android.permission.DUMP\" />`. " +
                    "Round 7 used DUMP as the receiver's sender-permission; round 9 dropped " +
                    "the receiver and the permission requirement together; round 10 uses " +
                    "INTERACT_ACROSS_USERS_FULL on the activity instead.",
            )
        }
    }
}
