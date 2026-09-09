// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * WSS-3 §9 focused fixture 83 —
 * `DiagnosticDebugManifestContainsAccessWifiStateTest`.
 *
 * Per REDLINE-5 blocker 4: reads
 * `apps/android/src/debug/AndroidManifest.xml` as a plain XML file
 * (no Gradle-built merged manifest required, no
 * `processReleaseMainManifest` gate needed) and asserts
 * `<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />`
 * is present exactly once. Runs under `testDebugUnitTest` without any
 * dependency on `assembleRelease` intermediates.
 */
class DiagnosticDebugManifestContainsAccessWifiStateTest {

    private val moduleRoot: File = run {
        var candidate = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val probe = File(candidate, "apps/android/src/debug/AndroidManifest.xml")
            if (probe.exists()) return@run candidate
            candidate = candidate.parentFile ?: candidate
        }
        candidate
    }

    @Test
    fun debug_manifest_declares_ACCESS_WIFI_STATE_exactly_once() {
        val manifest = File(moduleRoot, "apps/android/src/debug/AndroidManifest.xml")
        assertTrue(manifest.exists(), "debug AndroidManifest.xml missing at ${manifest.path}")
        val text = manifest.readText()
        val pattern = Regex(
            """<uses-permission\s+android:name\s*=\s*"android\.permission\.ACCESS_WIFI_STATE"\s*/>""",
        )
        val hits = pattern.findAll(text).count()
        assertEquals(
            1,
            hits,
            "expected exactly one ACCESS_WIFI_STATE uses-permission line in debug manifest; found $hits (REDLINE-4 blocker 3)",
        )
    }
}
