// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK §5.2 layers 1 + 2 manifest
 * contract test (2026-06-30). Mirror of
 * [S6ActivityManifestContractTest] for the new
 * `DebugForceMode2Activity` surface.
 *
 * Pins three properties:
 *
 *   1. `apps/android/src/debug/AndroidManifest.xml` declares
 *      `phantom.android.dev.DebugForceMode2Activity` with
 *      `android:exported="true"`,
 *      `android:theme="@android:style/Theme.NoDisplay"`, and
 *      `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`
 *      (signature-scoped permission; shell uid satisfies — co-installed
 *      third-party apps cannot).
 *   2. `apps/android/src/androidMain/AndroidManifest.xml` does NOT
 *      declare the activity. Release APKs MUST NOT carry the entry;
 *      its presence in the main manifest would make the component
 *      enumerable via `pm list packages -f` and `dumpsys package` on
 *      release even though the runtime BuildConfig gate would refuse
 *      to dispatch.
 *   3. Neither manifest carries a custom permission name or extra
 *      `<uses-permission>` declarations for this surface — the L1
 *      mini-lock §5.2 mandates the standard signature-scoped
 *      `INTERACT_ACROSS_USERS_FULL` ONLY, mirroring S6.
 */
class DebugForceMode2ActivityManifestContractTest {

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
    fun debug_manifest_declares_debug_force_mode_2_activity_with_required_attributes() {
        val xml = debugManifest.readText(Charsets.UTF_8)
        assertTrue(
            xml.contains("phantom.android.dev.DebugForceMode2Activity"),
            "Debug manifest MUST declare `phantom.android.dev.DebugForceMode2Activity` " +
                "as the L1 mini-lock §5.2 layer-1 entry point.",
        )
        // Extract the activity element by walking from the
        // android:name attribute back to the opening `<activity` and
        // forward to the next `/>` or `</activity>`. This guards
        // against false positives from comments earlier in the file
        // that mention the Activity name as documentation.
        val nameIdx = xml.indexOf("\"phantom.android.dev.DebugForceMode2Activity\"")
        val openBack = xml.lastIndexOf("<activity", startIndex = nameIdx)
        val closeForward = xml.indexOf("/>", startIndex = openBack).let {
            if (it < 0) xml.indexOf("</activity>", startIndex = openBack) else it
        }
        val element = xml.substring(openBack, closeForward + 2)
        assertTrue(
            element.contains("android:exported=\"true\""),
            "DebugForceMode2Activity MUST declare `android:exported=\"true\"` so " +
                "`adb shell am start` can reach it. Element:\n$element",
        )
        assertTrue(
            element.contains("android:permission=\"android.permission.INTERACT_ACROSS_USERS_FULL\""),
            "DebugForceMode2Activity MUST be gated by " +
                "`android:permission=\"android.permission.INTERACT_ACROSS_USERS_FULL\"`. " +
                "The permission is signature-scoped to the system signing certificate; " +
                "the shell uid satisfies it, a co-installed third-party app cannot. " +
                "Element:\n$element",
        )
        assertTrue(
            element.contains("android:theme=\"@android:style/Theme.NoDisplay\""),
            "DebugForceMode2Activity MUST declare `android:theme=\"@android:style/Theme.NoDisplay\"` " +
                "so the operator sees no window flash on trigger. Element:\n$element",
        )
    }

    @Test
    fun androidmain_manifest_does_not_declare_debug_force_mode_2_activity() {
        val xml = mainManifest.readText(Charsets.UTF_8)
        assertTrue(
            !xml.contains("DebugForceMode2Activity"),
            "androidMain manifest MUST NOT declare `DebugForceMode2Activity`. " +
                "Release APKs include the androidMain manifest; declaring the activity " +
                "there would expose it to `pm list packages -f` and `dumpsys package` " +
                "on release builds even with the runtime BuildConfig gate refusing to " +
                "dispatch. The L1 mini-lock §5.2 layer 1 requires debug-overlay-only.",
        )
    }

    @Test
    fun neither_manifest_carries_a_custom_permission_for_debug_force_mode_2() {
        val debugXml = debugManifest.readText(Charsets.UTF_8)
        val mainXml = mainManifest.readText(Charsets.UTF_8)
        for ((name, xml) in listOf("debug" to debugXml, "androidMain" to mainXml)) {
            // Allow comments / KDoc mentioning the activity name; only
            // forbid live <permission> / <uses-permission> elements that
            // name the debug-force-mode-2 surface specifically.
            val customPermissionForSurface = Regex(
                pattern = "<(permission|uses-permission)[^>]*android:name=\"[^\"]*DebugForceMode2[^\"]*\"",
            )
            assertTrue(
                customPermissionForSurface.containsMatchIn(xml).not(),
                "$name manifest MUST NOT declare a custom `<permission>` or " +
                    "`<uses-permission>` for the debug-force-mode-2 surface. The L1 " +
                    "mini-lock §5.2 layer 2 mandates the standard signature-scoped " +
                    "`android.permission.INTERACT_ACROSS_USERS_FULL` ONLY (mirrors S6).",
            )
        }
    }
}
