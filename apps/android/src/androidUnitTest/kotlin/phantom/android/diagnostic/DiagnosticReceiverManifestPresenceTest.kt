// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Direct WSS Yota-First — §11 lock 4 test #4.
 *
 * Introspects the AGP-generated merged manifests to prove:
 *   - `DiagnosticCommandReceiver` IS declared in the debug variant's
 *     merged manifest.
 *   - `DiagnosticCommandReceiver` is ABSENT from the release variant's
 *     merged manifest.
 *
 * The generated manifest files live under `apps/android/build/
 * intermediates/merged_manifests/{debug|release}/AndroidManifest.xml`
 * after `assembleDebug` / `assembleRelease` runs.
 *
 * The test is conditional: it SKIPS with a clear message when the
 * manifest artefacts have not been generated yet (the test file
 * compiles + runs even before the first `assembleDebug`). Running
 * this test after `assembleDebug` (planned in the WSS-1 handoff)
 * asserts the debug side. The release side asserts only when both
 * variants have been assembled — otherwise it also skips. A CI job
 * that runs `assembleDebug` + `assembleRelease` before this test
 * gets full coverage.
 */
class DiagnosticReceiverManifestPresenceTest {

    private val moduleRoot: File = File("..").canonicalFile.let { root ->
        // The unit test runs with cwd = `apps/android/` — the module
        // root sits one up. When invoked from Gradle the cwd is the
        // project root; either way, the `apps/android/build/…` path
        // resolves via a small walk.
        var candidate = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val probe = File(candidate, "apps/android/build/intermediates/merged_manifests")
            if (probe.exists()) return@let candidate
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
            message = "Debug merged manifest MUST contain DiagnosticCommandReceiver — " +
                "the receiver drives the operator matrix. Manifest: ${debug.path}",
        )
    }

    @Test
    fun receiver_absent_from_release_merged_manifest_when_assembleRelease_has_run() {
        val release = releaseManifest() ?: run {
            println(
                "SKIPPED: release merged manifest not present — run " +
                    "`./gradlew :apps:android:assembleRelease` before this test",
            )
            return
        }
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
