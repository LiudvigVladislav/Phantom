// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test

/**
 * Direct WSS Yota-First — §11 lock 4 test #4, hardened per §12 P1.
 *
 * Prior shape silently SKIPPED when the release merged manifest was
 * absent — a broken test that reported GREEN in the exact scenario
 * that matters (release APK was never assembled, so nothing checked
 * whether the debug receiver leaked). Repaired shape:
 *
 *   - The DEBUG-manifest assertion still permits SKIP with a clear
 *     "run assembleDebug first" message. Skipping is safe there
 *     because a debug-manifest absence would mean the receiver is
 *     not shipping in the diagnostic APK at all — the operator
 *     preflight catches that via `dumpsys package | grep
 *     DiagnosticCommandReceiver`.
 *   - The RELEASE-manifest assertion is now MANDATORY: absence is a
 *     test failure. An explicit opt-out marker file (see below) may
 *     bypass it in environments that cannot build release (e.g.
 *     no release keystore available). That marker is the ONLY way
 *     to bypass — a missing file no longer defaults to pass.
 *
 * Opt-out marker:
 *
 *   `apps/android/build/intermediates/merged_manifests/.release-manifest-not-required.marker`
 *
 * The marker's mere presence (empty file allowed) bypasses the
 * release check. CI must not create this marker; local test runs
 * that can't build release can `touch` it manually.
 */
class DiagnosticReceiverManifestPresenceTest {

    private val moduleRoot: File = run {
        var candidate = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val probe = File(candidate, "apps/android/build/intermediates/merged_manifests")
            if (probe.exists()) return@run candidate
            candidate = candidate.parentFile ?: candidate
        }
        candidate
    }

    private fun debugManifest(): File? {
        val f = File(moduleRoot, "apps/android/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        if (f.exists()) return f
        val alt = File(moduleRoot, "apps/android/build/intermediates/merged_manifests/debug/AndroidManifest.xml")
        return if (alt.exists()) alt else null
    }

    private fun releaseManifest(): File? {
        val f = File(moduleRoot, "apps/android/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml")
        if (f.exists()) return f
        val alt = File(moduleRoot, "apps/android/build/intermediates/merged_manifests/release/AndroidManifest.xml")
        return if (alt.exists()) alt else null
    }

    private fun optOutMarker(): File =
        File(moduleRoot, "apps/android/build/intermediates/merged_manifests/.release-manifest-not-required.marker")

    @Test
    fun receiver_present_in_debug_merged_manifest_when_assembleDebug_has_run() {
        val debug = debugManifest() ?: run {
            println(
                "SKIPPED: debug merged manifest not present — run " +
                    "`./gradlew :apps:android:assembleDebug` before this test",
            )
            return
        }
        val text = debug.readText()
        assertTrue(
            actual = text.contains("phantom.android.diagnostic.DiagnosticCommandReceiver"),
            message = "Debug merged manifest MUST contain DiagnosticCommandReceiver. Manifest: ${debug.path}",
        )
    }

    @Test
    fun receiver_absent_from_release_merged_manifest_is_mandatory() {
        val release = releaseManifest()
        if (release == null) {
            if (optOutMarker().exists()) {
                println(
                    "OPT-OUT: release manifest absent AND opt-out marker present at " +
                        "${optOutMarker().path} — release-side assertion skipped by explicit " +
                        "operator/CI decision.",
                )
                return
            }
            fail(
                "release merged manifest not present and no opt-out marker: run " +
                    "`./gradlew :apps:android:assembleRelease` (or `touch " +
                    "${optOutMarker().path}` for environments without a release keystore) " +
                    "before this test — a silent skip here would let the debug receiver leak " +
                    "into a release build undetected.",
            )
        } else {
            val text = release.readText()
            assertFalse(
                actual = text.contains("phantom.android.diagnostic.DiagnosticCommandReceiver"),
                message = "Release merged manifest MUST NOT contain DiagnosticCommandReceiver — " +
                    "any receiver leaked into release is a diagnostic exfil vector. Manifest: ${release.path}",
            )
            assertFalse(
                actual = text.contains("phantom.android.diagnostic.DiagnosticBootInitProvider"),
                message = "Release merged manifest MUST NOT contain the debug ContentProvider either.",
            )
        }
    }
}
