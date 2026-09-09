// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * WSS-3 §9 focused fixture 84 —
 * `DiagnosticProductionManifestOmitsAccessWifiStateTest`.
 *
 * Per REDLINE-5 blocker 4: reads `apps/android/src/androidMain/AndroidManifest.xml`
 * AND (if present) `apps/android/src/release/AndroidManifest.xml` as
 * plain XML files inside `testDebugUnitTest` and asserts
 * `ACCESS_WIFI_STATE` is absent from BOTH. Replaces the earlier
 * Round-4 wording that assumed a Gradle-generated
 * `merged_manifest/release` intermediate would exist under
 * `testDebugUnitTest` — that assumption did not hold in general.
 *
 * This is the source-set boundary check: as long as the debug source
 * set is the only place that declares the permission, the release
 * merged manifest cannot inherit it.
 */
class DiagnosticProductionManifestOmitsAccessWifiStateTest {

    private val moduleRoot: File = run {
        var candidate = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val probe = File(candidate, "apps/android/src/androidMain/AndroidManifest.xml")
            if (probe.exists()) return@run candidate
            candidate = candidate.parentFile ?: candidate
        }
        candidate
    }

    @Test
    fun androidMain_manifest_does_not_declare_ACCESS_WIFI_STATE() {
        val f = File(moduleRoot, "apps/android/src/androidMain/AndroidManifest.xml")
        assertTrue(f.exists(), "androidMain AndroidManifest.xml missing at ${f.path}")
        val text = f.readText()
        assertFalse(
            text.contains("ACCESS_WIFI_STATE"),
            "release-leak: ACCESS_WIFI_STATE must NOT appear in the androidMain manifest — " +
                "the WSS-3 permission ships debug-only per REDLINE-4 blocker 3. Path: ${f.path}",
        )
    }

    @Test
    fun release_manifest_absent_or_does_not_declare_ACCESS_WIFI_STATE() {
        val f = File(moduleRoot, "apps/android/src/release/AndroidManifest.xml")
        if (!f.exists()) {
            // Legitimate: many projects have no release-specific manifest overlay.
            // The androidMain check above is sufficient in that case.
            return
        }
        val text = f.readText()
        assertFalse(
            text.contains("ACCESS_WIFI_STATE"),
            "release-leak: ACCESS_WIFI_STATE must NOT appear in the release manifest overlay. Path: ${f.path}",
        )
    }
}
