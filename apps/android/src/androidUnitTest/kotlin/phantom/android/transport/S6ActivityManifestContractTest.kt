// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 9 P1.evidence) — manifest
 * contract test for the activity-based S6 trigger surface.
 *
 * Pins:
 *
 *   * `S6BreakerTriggerActivity` is declared in the manifest with
 *     `android:exported="true"` (required so `adb shell am start`
 *     can launch it cross-process).
 *   * The declaration uses `android:theme="@android:style/Theme.NoDisplay"`
 *     so the activity opens without a window flash.
 *   * The receiver-era artefacts (round 5 custom permission,
 *     round 7 DUMP uses-permission, receiver source) are gone.
 *
 * Together with `S6BreakerTriggerActivityContractTest` this fences
 * the round-9 wire-up against doc/manifest drift.
 */
class S6ActivityManifestContractTest {

    private val manifest: File by lazy {
        val candidates = listOf(
            File("src/androidMain/AndroidManifest.xml"),
            File("apps/android/src/androidMain/AndroidManifest.xml"),
            File("../apps/android/src/androidMain/AndroidManifest.xml"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate AndroidManifest.xml. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    @Test
    fun s6_activity_declared_with_exported_true_and_no_display_theme() {
        val text = manifest.readText(Charsets.UTF_8)
        // Locate the <activity> element by FQCN. The Android Gradle
        // plugin namespace expansion turns relative names (.dev.X)
        // into FQCN at build time; the source manifest can use
        // either form. The contract requires the FQCN form so
        // future class moves trip the test rather than silently
        // resolving against the application namespace default.
        val activityRegex = Regex(
            """<activity\b[^>]*?android:name="phantom\.android\.dev\.S6BreakerTriggerActivity"[^>]*?(?:/>|>)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = activityRegex.find(text)
        assertTrue(
            match != null,
            "AndroidManifest.xml MUST declare `<activity android:name=\"phantom.android.dev." +
                "S6BreakerTriggerActivity\" ... />`. The activity is the round-9 entry " +
                "point for the S6 controllable breaker trigger.",
        )
        val element = match!!.value
        assertTrue(
            element.contains("""android:exported="true""""),
            "S6BreakerTriggerActivity MUST be declared `android:exported=\"true\"` so " +
                "`adb shell am start` can launch it cross-process. Found element: $element",
        )
        assertTrue(
            element.contains("""android:theme="@android:style/Theme.NoDisplay""""),
            "S6BreakerTriggerActivity MUST use `android:theme=\"@android:style/" +
                "Theme.NoDisplay\"` so the trigger does not flash a window on the " +
                "operator's screen. Found element: $element",
        )
    }

    @Test
    fun no_round5_custom_permission_remains_in_manifest() {
        val text = manifest.readText(Charsets.UTF_8)
        assertTrue(
            !text.contains("phantom.android.dev.permission.TRIGGER_S6"),
            "AndroidManifest.xml MUST NOT reference the round-5 custom permission " +
                "`phantom.android.dev.permission.TRIGGER_S6`. Round 9 dropped the " +
                "receiver+permission scheme entirely.",
        )
    }

    @Test
    fun no_round7_DUMP_uses_permission_remains_in_manifest() {
        val text = manifest.readText(Charsets.UTF_8)
        val dumpRegex = Regex(
            """<uses-permission\b[^>]*?android:name="android\.permission\.DUMP"[^>]*?/>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            !dumpRegex.containsMatchIn(text),
            "AndroidManifest.xml MUST NOT declare `<uses-permission " +
                "android:name=\"android.permission.DUMP\" />`. Round 7 used DUMP as the " +
                "receiver's sender-permission; round 9 dropped the receiver and the " +
                "permission requirement together. A leftover declaration enlarges the " +
                "production permission set without purpose.",
        )
    }
}
