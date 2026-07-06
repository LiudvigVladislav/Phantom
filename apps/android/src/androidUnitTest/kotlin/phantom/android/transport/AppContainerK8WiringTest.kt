// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * B2-K8 AppContainer DI wiring contract test (design note §2.6 test 7,
 * 2026-07-06). Structural test that grep-asserts the load-bearing
 * strings in `AppContainer.kt` source so a future refactor cannot
 * silently drop a wire-up and ship the K8 client patch as an inert
 * no-op.
 *
 * Mirrors [AppContainerDebugForceMode2WiringTest] file-locator +
 * source-read pattern for the DEBUG_FORCE_MODE_2_DETECTION wire-up.
 * The wiring contract pinned here:
 *
 *  1. AppContainer declares `k8HoldOverrideProvider: () -> Int` at the
 *     RestOrchestrator setup site — the provider is the recon read
 *     path that resolves shared-prefs THEN BuildConfig on every
 *     `/relay/poll` call.
 *  2. AppContainer declares `k8ConnectionCloseProvider: () -> Boolean`
 *     for the OkHttp interceptor path.
 *  3. Both providers are threaded into `createRestFallbackTransport(...)`
 *     via NAMED constructor arguments (positional would silently drift
 *     if the factory signature reorders).
 *  4. The prefs read targets `debug_k8_hold_override_seconds` and
 *     `debug_k8_connection_close` — the exact keys the runner script
 *     and future Settings-Diagnostics UI will write.
 *
 * The test is structural — it reads AppContainer.kt as text. A runtime
 * integration test would need the full Android application context
 * stack which is overkill for pinning constructor argument names.
 */
class AppContainerK8WiringTest {

    private val appContainerSource: File by lazy {
        val candidates = listOf(
            File("src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
            File("apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
            File("../apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate AppContainer.kt from the unit-test working directory. " +
                    "Tried: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    private fun source(): String = appContainerSource.readText(Charsets.UTF_8)

    @Test
    fun appcontainer_declares_k8_hold_override_provider() {
        val text = source()
        assertTrue(
            text.contains("val k8HoldOverrideProvider: () -> Int"),
            "AppContainer MUST declare `val k8HoldOverrideProvider: () -> Int = { ... }` " +
                "near the RestOrchestrator construction site. The lambda is the load-" +
                "bearing K8 read path that resolves shared-prefs first and BuildConfig " +
                "second on EVERY `/relay/poll` invocation (design note §2.3 + §3.1). " +
                "If this declaration is missing the K8 client patch is inert.",
        )
        assertTrue(
            text.contains("k8Prefs.getInt(\"debug_k8_hold_override_seconds\", -1)"),
            "AppContainer's K8 hold-override provider MUST read the shared-prefs key " +
                "`debug_k8_hold_override_seconds` (Int) with `-1` as the absent-default. " +
                "The runner script and future Settings-Diagnostics UI both target that " +
                "exact key; drift here silently breaks the K8 recon window.",
        )
        assertTrue(
            text.contains("phantom.android.BuildConfig.DEBUG_K8_HOLD_OVERRIDE_SECONDS"),
            "AppContainer's K8 hold-override provider MUST fall back to " +
                "`phantom.android.BuildConfig.DEBUG_K8_HOLD_OVERRIDE_SECONDS.toIntOrNull()` " +
                "when the prefs key is absent or sentinel. Release APK hardpins this to " +
                "\"-1\" via buildTypes.release so the fallback returns the sentinel and " +
                "the composer skips the `?hold` param. Missing this fallback would " +
                "either always send `?hold=N` (release regression) or never send it " +
                "(field-session-broken).",
        )
    }

    @Test
    fun appcontainer_declares_k8_connection_close_provider() {
        val text = source()
        assertTrue(
            text.contains("val k8ConnectionCloseProvider: () -> Boolean"),
            "AppContainer MUST declare `val k8ConnectionCloseProvider: () -> Boolean = { ... }` " +
                "for the K8 Connection: close interceptor path (design note §2.4 + §6.1). " +
                "The interceptor is added only when this provider returns true — a " +
                "missing declaration means the interceptor is never wired into the " +
                "OkHttp client and the K8 recon cannot exercise TCP+TLS teardown per " +
                "poll.",
        )
        assertTrue(
            text.contains("k8Prefs.contains(\"debug_k8_connection_close\")"),
            "AppContainer's K8 Connection: close provider MUST first check " +
                "`k8Prefs.contains(\"debug_k8_connection_close\")` before reading the " +
                "boolean value. The `contains` check distinguishes 'operator explicitly " +
                "set false to override BuildConfig true' from 'key absent, fall through " +
                "to BuildConfig'.",
        )
        assertTrue(
            text.contains("phantom.android.BuildConfig.DEBUG_K8_CONNECTION_CLOSE == \"1\""),
            "AppContainer's K8 Connection: close provider MUST fall back to " +
                "`phantom.android.BuildConfig.DEBUG_K8_CONNECTION_CLOSE == \"1\"` when " +
                "the prefs key is absent. Release APK hardpins the BuildConfig to \"0\" " +
                "so the fallback returns false and the interceptor is never installed. " +
                "R8 dead-code eliminates the K8DebugConnectionCloseInterceptor class " +
                "entirely (verified by verifyR8StripsTestSeams `K8Debug.*` deny " +
                "pattern).",
        )
    }

    @Test
    fun createrestfallbacktransport_wires_k8HoldOverrideProvider_named() {
        val text = source()
        val constructionStart = text.indexOf("createRestFallbackTransport(")
        assertTrue(
            constructionStart >= 0,
            "AppContainer must contain a `createRestFallbackTransport(...)` " +
                "construction site — the K8 wire-up depends on it.",
        )
        val openIdx = text.indexOf('(', startIndex = constructionStart)
        var depth = 1
        var i = openIdx + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        if (depth != 0) {
            fail("Unbalanced parens in AppContainer createRestFallbackTransport construction.")
        }
        val args = text.substring(openIdx + 1, i - 1)
        assertTrue(
            args.contains("k8HoldOverrideProvider = k8HoldOverrideProvider"),
            "AppContainer's `createRestFallbackTransport(...)` MUST pass " +
                "`k8HoldOverrideProvider = k8HoldOverrideProvider` as a NAMED " +
                "constructor argument. The factory signature has two K8-related nullable " +
                "provider params (see RelayTransportFactory.kt commonMain expect); " +
                "positional binding would silently drift if the factory param order " +
                "reorders. Argument list slice:\n$args",
        )
    }

    @Test
    fun createrestfallbacktransport_wires_k8ConnectionCloseProvider_named() {
        val text = source()
        val constructionStart = text.indexOf("createRestFallbackTransport(")
        val openIdx = text.indexOf('(', startIndex = constructionStart)
        var depth = 1
        var i = openIdx + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        if (depth != 0) {
            fail("Unbalanced parens in AppContainer createRestFallbackTransport construction.")
        }
        val args = text.substring(openIdx + 1, i - 1)
        assertTrue(
            args.contains("k8ConnectionCloseProvider = k8ConnectionCloseProvider"),
            "AppContainer's `createRestFallbackTransport(...)` MUST pass " +
                "`k8ConnectionCloseProvider = k8ConnectionCloseProvider` as a NAMED " +
                "constructor argument. Missing this named wire-up would drop the " +
                "Connection: close + evictAll interceptor from the OkHttp client and " +
                "the K8 recon cannot exercise TCP+TLS teardown per poll. Argument " +
                "list slice:\n$args",
        )
    }
}
