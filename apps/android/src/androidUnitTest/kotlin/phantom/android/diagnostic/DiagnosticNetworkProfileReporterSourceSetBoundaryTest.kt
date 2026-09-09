// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test

/**
 * WSS-3 §9 focused fixture 86 —
 * `DiagnosticNetworkProfileReporterSourceSetBoundaryTest`.
 *
 * Extends the accepted [DiagnosticSourceSetBoundaryTest] discipline
 * with three additional invariants that specifically cover the
 * WSS-3-added [DiagnosticNetworkProfileReporter] helper class:
 *
 *   1. Source-set location: `DiagnosticNetworkProfileReporter.kt`
 *      exists ONLY under `apps/android/src/debug/kotlin/phantom/android/diagnostic/`.
 *   2. Zero `androidMain` references: no file under
 *      `apps/android/src/androidMain/` references
 *      `DiagnosticNetworkProfileReporter` by fully-qualified or
 *      simple name.
 *   3. No new manifest components: the debug manifest still declares
 *      only the pre-existing WSS-2 receiver + boot-init provider
 *      (the reporter piggybacks on the receiver; it is NOT a manifest
 *      component). The release manifest overlay path either does not
 *      exist or does not declare the reporter.
 */
class DiagnosticNetworkProfileReporterSourceSetBoundaryTest {

    private val moduleRoot: File = run {
        var candidate = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val probe = File(candidate, "apps/android/src/debug/kotlin/phantom/android/diagnostic")
            if (probe.exists()) return@run candidate
            candidate = candidate.parentFile ?: candidate
        }
        candidate
    }

    private val debugDir = File(moduleRoot, "apps/android/src/debug/kotlin/phantom/android/diagnostic")
    private val mainDir = File(moduleRoot, "apps/android/src/androidMain/kotlin")
    private val reporterFileName = "DiagnosticNetworkProfileReporter.kt"

    @Test
    fun reporter_source_lives_only_under_src_debug() {
        val debugFile = File(debugDir, reporterFileName)
        assertTrue(
            debugFile.exists(),
            "expected $reporterFileName at ${debugFile.path}",
        )
        val leak = File(mainDir, "phantom/android/diagnostic/$reporterFileName")
        assertFalse(
            leak.exists(),
            "release-leak: $reporterFileName found under androidMain at ${leak.path} — MUST live only under src/debug/",
        )
    }

    @Test
    fun no_androidMain_file_references_the_reporter_by_name() {
        if (!mainDir.exists()) fail("androidMain source directory not found at ${mainDir.path}")
        val hits = mutableListOf<String>()
        mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            .forEach { f ->
                if (f.readText().contains("DiagnosticNetworkProfileReporter")) {
                    hits.add(f.path)
                }
            }
        assertTrue(
            hits.isEmpty(),
            "release-leak: androidMain files reference DiagnosticNetworkProfileReporter: $hits — " +
                "the helper must NOT be pulled into the release variant's dex",
        )
    }

    @Test
    fun debug_manifest_declares_no_new_manifest_component_for_the_reporter() {
        // The reporter is a helper class NOT a manifest component.
        // The debug manifest continues to declare only the WSS-2
        // receiver + boot-init provider — no new <receiver> or
        // <provider> or <service> tag names the reporter.
        val debugManifest = File(moduleRoot, "apps/android/src/debug/AndroidManifest.xml")
        assertTrue(debugManifest.exists(), "debug AndroidManifest.xml missing at ${debugManifest.path}")
        val text = debugManifest.readText()
        val badTags = Regex(
            """<(receiver|provider|service|activity)[^>]*android:name\s*=\s*"[^"]*DiagnosticNetworkProfileReporter[^"]*"""",
        )
        assertFalse(
            badTags.containsMatchIn(text),
            "release-scope leak: debug manifest declares a manifest component that references DiagnosticNetworkProfileReporter. " +
                "The reporter must piggyback on the existing DiagnosticCommandReceiver, NOT ship as its own component. " +
                "Manifest: ${debugManifest.path}",
        )
    }

    @Test
    fun release_manifest_overlay_absent_or_omits_the_reporter() {
        val release = File(moduleRoot, "apps/android/src/release/AndroidManifest.xml")
        if (!release.exists()) return
        val text = release.readText()
        assertFalse(
            text.contains("DiagnosticNetworkProfileReporter"),
            "release-leak: release manifest overlay references DiagnosticNetworkProfileReporter. Path: ${release.path}",
        )
    }
}
