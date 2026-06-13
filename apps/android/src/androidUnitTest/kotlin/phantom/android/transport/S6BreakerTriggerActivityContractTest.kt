// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 9 P1.evidence) — contract
 * test that pins the activity-based S6 controllable trigger surface.
 *
 * Round 9 dropped the BroadcastReceiver + sender-permission scheme
 * because TECNO BF7-RU shell does not hold DUMP in broadcast
 * permission scope (verified at the wire). The replacement is a
 * no-display Activity launched via `adb shell am start`.
 *
 * Pins:
 *
 *   * Activity source has a BuildConfig.S6_DEBUG_TRIGGER_ENABLED
 *     gate in onCreate.
 *   * Activity source routes the dispatch through
 *     `container.triggerS6BreakerForDebug()`.
 *   * Activity source calls `finish()` to keep the no-display
 *     semantics.
 *   * Receiver source is GONE from the dev package (no
 *     S6BreakerTriggerReceiver.kt file).
 */
class S6BreakerTriggerActivityContractTest {

    private val activitySource: File by lazy {
        val candidates = listOf(
            File("src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerActivity.kt"),
            File("apps/android/src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerActivity.kt"),
            File("../apps/android/src/androidMain/kotlin/phantom/android/dev/S6BreakerTriggerActivity.kt"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate S6BreakerTriggerActivity.kt. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    private val devPackageDir: File by lazy {
        val candidates = listOf(
            File("src/androidMain/kotlin/phantom/android/dev"),
            File("apps/android/src/androidMain/kotlin/phantom/android/dev"),
            File("../apps/android/src/androidMain/kotlin/phantom/android/dev"),
        )
        candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: fail(
                "Could not locate phantom.android.dev package. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    private fun source(): String = activitySource.readText(Charsets.UTF_8)

    @Test
    fun activity_onCreate_gates_on_S6_DEBUG_TRIGGER_ENABLED_flag() {
        val text = source()
        val gateRegex = Regex(
            """if\s*\(\s*phantom\.android\.BuildConfig\.S6_DEBUG_TRIGGER_ENABLED\s*!=\s*"1"\s*\)"""
        )
        assertTrue(
            gateRegex.containsMatchIn(text),
            "S6BreakerTriggerActivity.onCreate must short-circuit on " +
                "`BuildConfig.S6_DEBUG_TRIGGER_ENABLED != \"1\"`. Without this gate a " +
                "release build that somehow declared the activity would still dispatch.",
        )
    }

    @Test
    fun activity_dispatches_through_triggerS6BreakerForDebug() {
        val text = source()
        assertTrue(
            text.contains("container.triggerS6BreakerForDebug()"),
            "S6BreakerTriggerActivity must route the dispatch through " +
                "`container.triggerS6BreakerForDebug()` so the AppContainer-level gate runs " +
                "before the orchestrator-level gate. Found activity source:\n$text",
        )
        // Dispatch must run on container.appScope (the orchestrator's
        // suspending work cannot run on the activity's onCreate
        // thread directly).
        assertTrue(
            text.contains("container.appScope.launch"),
            "S6BreakerTriggerActivity must dispatch on `container.appScope.launch { ... }`.",
        )
    }

    @Test
    fun activity_finish_is_called_for_no_display_semantics() {
        val text = source()
        // `finish()` must be called on EVERY exit path so the
        // activity never lingers on the back stack regardless of
        // which gate refused.
        assertTrue(
            Regex("""\bfinish\(\)""").findAll(text).count() >= 2,
            "S6BreakerTriggerActivity must call `finish()` on at least two distinct " +
                "exit paths (gate-refused + dispatched). Found activity source:\n$text",
        )
    }

    @Test
    fun activity_finish_after_dispatch_inside_try_finally_on_appScope() {
        val text = source()
        // Round-10 lifecycle-safety pin — `finish()` MUST be scheduled
        // inside the launch's `finally` block (via runOnUiThread)
        // so the activity stays alive until the suspending
        // `triggerS6BreakerForDebug()` actually returns. The
        // round-9 wiring called `finish()` synchronously after the
        // `launch` returned, which let the OS sweep the process
        // before the dispatch landed on devices without an active
        // foreground service.
        assertTrue(
            text.contains("runOnUiThread { finish() }"),
            "S6BreakerTriggerActivity must schedule `finish()` via `runOnUiThread { " +
                "finish() }` inside the coroutine `finally` block so the dispatch completes " +
                "before the activity finishes. Found activity source:\n$text",
        )
        // The `try { ... } finally { ... }` shape must wrap the
        // dispatch + log line. Pin the structural anchor.
        assertTrue(
            Regex("""try\s*\{[\s\S]*?triggerS6BreakerForDebug\(\)[\s\S]*?\}\s*catch[\s\S]*?\}\s*finally\s*\{""")
                .containsMatchIn(text),
            "S6BreakerTriggerActivity dispatch must be wrapped in " +
                "`try { triggerS6BreakerForDebug() ... } catch (...) { ... } finally { ... }` " +
                "so unhandled exceptions still finish the activity.",
        )
    }

    @Test
    fun activity_checks_isCancelled_on_launch_to_avoid_silent_drop() {
        val text = source()
        // Round-10 cancellation-safety guard — if appScope is already
        // cancelled when launch is called, the coroutine body never
        // runs and the activity would hang without the explicit
        // check below.
        assertTrue(
            text.contains("job.isCancelled"),
            "S6BreakerTriggerActivity must check `job.isCancelled` after `appScope.launch` " +
                "and call `finish()` from the main thread if the scope was already " +
                "cancelled. Without this guard the activity hangs silently when AppContainer " +
                "is mid-shutdown.",
        )
    }

    @Test
    fun receiver_source_no_longer_exists_in_dev_package() {
        val receiverFile = File(devPackageDir, "S6BreakerTriggerReceiver.kt")
        assertTrue(
            !receiverFile.exists(),
            "Round 9 removed the BroadcastReceiver entry point. " +
                "`S6BreakerTriggerReceiver.kt` MUST NOT exist in the dev package. " +
                "Found at: ${receiverFile.absolutePath}",
        )
    }
}
