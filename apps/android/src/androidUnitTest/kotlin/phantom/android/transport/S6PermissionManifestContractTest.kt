// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 7 P1.evidence) — contract
 * test that pins the `android.permission.DUMP` declaration in
 * `AndroidManifest.xml` and ASSERTS that no leftover custom round-5
 * permission declaration remains.
 *
 * The S6 controllable trigger receiver registration in `AppContainer`
 * passes this permission name as the `broadcastPermission` argument
 * to `registerReceiver`. The manifest must:
 *
 *   * Declare `<uses-permission android:name="android.permission.DUMP" />`
 *     so the platform permission framework treats the app as
 *     intending to consume the platform permission. (The receiver
 *     registration itself does NOT need the app to HOLD the
 *     permission; the `<uses-permission>` is for framework-side
 *     consistency.)
 *   * NOT declare a custom
 *     `<permission android:name="phantom.android.dev.permission.TRIGGER_S6" />`
 *     element. Round-5 used a custom signature permission scoped to
 *     the APK's own signing certificate — the shell uid cannot
 *     satisfy that scope and the broadcast would silently drop. Round
 *     7 switched to the platform `DUMP` permission instead; any
 *     leftover custom-permission declaration silently re-introduces
 *     the round-5 delivery failure on the Tecno.
 */
class S6PermissionManifestContractTest {

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
    fun uses_permission_dump_is_declared() {
        val text = manifest.readText(Charsets.UTF_8)
        val usesRegex = Regex(
            """<uses-permission\b[^>]*?android:name="android\.permission\.DUMP"[^>]*?/>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            usesRegex.containsMatchIn(text),
            "AndroidManifest.xml MUST contain a `<uses-permission " +
                "android:name=\"android.permission.DUMP\" />` element. The S6 controllable " +
                "trigger receiver registration passes `android.permission.DUMP` as the " +
                "broadcast-permission argument so the shell uid can deliver the trigger " +
                "and co-installed third-party apps cannot.",
        )
    }

    @Test
    fun no_round5_custom_permission_declared() {
        val text = manifest.readText(Charsets.UTF_8)
        // The round-5 custom permission was an APK-cert-scoped
        // signature permission that the shell uid could not satisfy.
        // Its presence anywhere in the manifest (a `<permission>`
        // declaration OR a `<uses-permission>` reference) would
        // either silently revive the round-5 delivery failure or
        // confuse future operators reading the manifest.
        val customNameRegex = Regex("""phantom\.android\.dev\.permission\.TRIGGER_S6""")
        assertTrue(
            !customNameRegex.containsMatchIn(text),
            "AndroidManifest.xml MUST NOT contain any reference to the round-5 custom " +
                "permission `phantom.android.dev.permission.TRIGGER_S6`. Round 7 replaced " +
                "it with `android.permission.DUMP` because the custom-signature scope was " +
                "not satisfiable by the shell uid.",
        )
    }
}
