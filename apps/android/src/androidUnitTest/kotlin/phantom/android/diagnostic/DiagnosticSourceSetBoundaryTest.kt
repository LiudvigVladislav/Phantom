// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test

/**
 * Direct WSS Yota-First — §12 Round-1 audit P1 fix for the
 * release-manifest test.
 *
 * The prior manifest-based check passed through an opt-out marker,
 * so release absence was never actually proven. This test replaces
 * the manifest introspection with a source-set boundary check:
 *
 *   1. The debug-only Kotlin files (`DiagnosticCommandReceiver.kt`,
 *      `DiagnosticBootInit.kt`, `DiagnosticSendCoordinator.kt`,
 *      `DiagnosticTransportPinStore.kt`) MUST live under
 *      `apps/android/src/debug/` and NOT under `apps/android/src/androidMain/`
 *      or any other production source set.
 *   2. No file under `apps/android/src/androidMain/` may reference
 *      `DiagnosticCommandReceiver` by fully-qualified name (that would
 *      pull it into the release variant's dex).
 *
 * These properties are independent of `assembleRelease` and cannot
 * be silently bypassed. A regression that leaks the receiver into
 * production code fails-red here.
 */
class DiagnosticSourceSetBoundaryTest {

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

    private val debugOnlyFiles = listOf(
        "DiagnosticCommandReceiver.kt",
        "DiagnosticBootInit.kt",
        "DiagnosticSendCoordinator.kt",
        "DiagnosticTransportPinStore.kt",
    )

    @Test
    fun debug_only_diagnostic_files_exist_in_debug_source_set() {
        for (name in debugOnlyFiles) {
            val f = File(debugDir, name)
            assertTrue(f.exists(), "expected $name at ${f.path}")
        }
    }

    @Test
    fun debug_only_diagnostic_files_do_NOT_exist_in_main_source_set() {
        for (name in debugOnlyFiles) {
            val leak = File(mainDir, "phantom/android/diagnostic/$name")
            assertFalse(
                leak.exists(),
                "release-leak: $name found under androidMain at ${leak.path} — the receiver/coordinator/pin-store/boot-init MUST live only under src/debug/",
            )
        }
    }

    @Test
    fun main_source_set_contains_no_reference_to_DiagnosticCommandReceiver() {
        if (!mainDir.exists()) {
            fail("androidMain source directory not found at ${mainDir.path}")
        }
        val hits = mutableListOf<String>()
        mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            .forEach { f ->
                val text = f.readText()
                if (text.contains("DiagnosticCommandReceiver")) {
                    hits.add(f.path)
                }
            }
        assertTrue(
            hits.isEmpty(),
            "release-leak: androidMain files reference DiagnosticCommandReceiver: $hits",
        )
    }

    @Test
    fun debug_manifest_grants_DUMP_permission_to_the_receiver() {
        val manifest = File(moduleRoot, "apps/android/src/debug/AndroidManifest.xml")
        assertTrue(manifest.exists(), "debug AndroidManifest.xml missing at ${manifest.path}")
        val text = manifest.readText()
        assertTrue(
            text.contains("DiagnosticCommandReceiver"),
            "debug manifest MUST declare DiagnosticCommandReceiver",
        )
        assertTrue(
            Regex("""android:permission\s*=\s*"android\.permission\.DUMP"""").containsMatchIn(text),
            "debug manifest MUST gate DiagnosticCommandReceiver on android.permission.DUMP — the AMS-boundary caller enforcement per §12 Round-1 audit P0-6",
        )
    }
}
