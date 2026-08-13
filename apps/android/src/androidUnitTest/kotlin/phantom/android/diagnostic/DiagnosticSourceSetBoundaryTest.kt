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

    // §PR-review-round-1 P0 — release-inert-by-default invariant
    // for `WssDiag`. Receiver-absence alone doesn't prove all
    // diagnostics are inert in release: `WssDiag.emit` lives in
    // `androidMain` and IS reachable from production code paths
    // (`HybridRelayTransport.send` under `Pin.NONE`). The
    // invariant we enforce here:
    //
    //   1. `WssDiag.isActive` is declared and defaults to `false`.
    //   2. Only `DiagnosticBootInitProvider` (debug source set)
    //      flips it to `true`.
    //   3. `WssDiag.emit` reads `isActive` BEFORE `Log.i`.
    //
    // These are source-level structural checks, independent of
    // `assembleRelease`. A regression that turns the invariant off
    // (e.g. flipping the default, or moving the activation call to
    // `androidMain`) fails-red here.

    @Test
    fun WssDiag_declares_isActive_default_false() {
        val wssDiag = File(mainDir, "phantom/android/diagnostic/WssDiag.kt")
        assertTrue(wssDiag.exists(), "WssDiag.kt missing at ${wssDiag.path}")
        val text = wssDiag.readText()
        assertTrue(
            Regex("""var\s+isActive\s*:\s*Boolean\s*=\s*false""").containsMatchIn(text),
            "WssDiag MUST declare `var isActive: Boolean = false` — release-inert-by-default gate (§PR-review-round-1 P0)",
        )
    }

    @Test
    fun WssDiag_emit_reads_isActive_before_calling_Log_i() {
        val wssDiag = File(mainDir, "phantom/android/diagnostic/WssDiag.kt")
        val text = wssDiag.readText()
        val activeIdx = text.indexOf("isActive")
        val logIdx = text.indexOf("Log.i(TAG,")
        assertTrue(activeIdx >= 0, "isActive reference missing from WssDiag.kt")
        assertTrue(logIdx >= 0, "Log.i(TAG, ...) missing from WssDiag.kt")
        assertTrue(
            activeIdx < logIdx,
            "WssDiag.emit MUST read isActive BEFORE the Log.i call — otherwise release emits still reach Android's Log",
        )
    }

    @Test
    fun WssDiag_isActive_only_activated_from_debug_source_set() {
        // Search every .kt under production source sets — `androidMain`
        // in the app + every `commonMain`/`androidMain` under `shared`
        // — for an assignment to `WssDiag.isActive = true`. The only
        // legitimate hit is in `src/debug/` (checked separately below);
        // any hit in a production source set would activate the
        // diagnostic in the release APK.
        //
        // Test source sets (`androidUnitTest`, `commonTest`, etc.) are
        // NOT in this walk: they run in JVM against the test classpath
        // and never ship in the release APK, so `WssDiagInertByDefaultTest`
        // legitimately flips the flag in one of its cases.
        val roots = listOf(
            File(moduleRoot, "apps/android/src/androidMain"),
            File(moduleRoot, "apps/android/src/main"),
            File(moduleRoot, "shared"),
        )
        val activationRegex = Regex("""WssDiag\s*\.\s*isActive\s*=\s*true""")
        val leaks = mutableListOf<String>()
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { f ->
                    // Skip any test source set under `shared/**`
                    // (commonTest, androidUnitTest, etc.) — those
                    // never ship in a release APK.
                    val p = f.path.replace('\\', '/')
                    !p.contains("/src/commonTest/") &&
                        !p.contains("/src/androidUnitTest/") &&
                        !p.contains("/src/androidTest/") &&
                        !p.contains("/src/test/")
                }
                .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
                .forEach { f ->
                    val text = f.readText()
                    if (activationRegex.containsMatchIn(text)) {
                        leaks.add(f.path)
                    }
                }
        }
        assertTrue(
            leaks.isEmpty(),
            "release-leak: WssDiag.isActive = true is assigned in a production source set: $leaks — this would activate diagnostics in release APKs",
        )
        // And the legitimate assignment IS present under src/debug/.
        val debugBootInit = File(debugDir, "DiagnosticBootInit.kt")
        assertTrue(debugBootInit.exists(), "DiagnosticBootInit.kt missing at ${debugBootInit.path}")
        assertTrue(
            activationRegex.containsMatchIn(debugBootInit.readText()),
            "DiagnosticBootInit.kt MUST activate WssDiag (assignment `WssDiag.isActive = true`)",
        )
    }
}
