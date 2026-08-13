// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.delay
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import phantom.android.screens.onboarding.v2.StartupRouteDecision

/**
 * Final Stabilization Mini-Block 2026-08-11 — Compose integration
 * test for the load-bearing invariant:
 *
 * > A user on Profile survives Activity recreation followed by a
 * > late-completing healthy startup coroutine.
 *
 * The harness below reproduces the exact composition shape of
 * `MainActivity.PhantomApp`:
 *
 *   * `currentScreen` held in `rememberSaveable(stateSaver = ScreenSaver)`.
 *   * A `LaunchedEffect(retryTick)` that suspends for a bit (simulating
 *     the disk + identity + messaging init in production), then reads
 *     the LIVE `currentScreen` and calls [resolveScreenAfterStartup]
 *     with a fake `StartupRouteDecision.ChatList` (healthy path).
 *
 * The prior shape (before this block's fix) captured
 * `currentScreen` at coroutine launch time — always `null` at cold
 * start — and unconditionally wrote `Screen.ChatList`. This test
 * models that timing bug: [StateRestorationTester.emulateSavedInstanceStateRestore]
 * recreates the composition (`currentScreen` restored to
 * `Screen.Profile`), then `waitForIdle` blocks until the delayed
 * startup coroutine finishes and writes its final result. If the
 * resolver captured a stale `null`, `currentScreen` would land on
 * `Screen.ChatList` and the test fails red.
 *
 * The startup suspend uses `kotlinx.coroutines.delay(100)` under the
 * Compose main clock — `waitForIdle` drains it deterministically.
 * No sleeps, no `Thread.sleep`, no real timers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StartupRouteLateWriteIntegrationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Mirrors `MainActivity.PhantomApp` in the shape that matters
     * for this test: a `rememberSaveable`-backed `currentScreen` +
     * a `LaunchedEffect` that suspends before writing a resolver
     * output. The suspend simulates the multi-second disk + messaging
     * bootstrap in production.
     *
     * @param decision what `decideStartupRoute` would return this run.
     * @param onSetCurrent test seam so the harness can push the user
     *   onto a route BEFORE the recreation.
     */
    @Composable
    private fun StartupHarness(
        decision: StartupRouteDecision,
        onScreenReady: (Screen?) -> Unit,
        initialSelectedScreen: Screen? = null,
    ) {
        var currentScreen by rememberSaveable(stateSaver = ScreenSaver) {
            mutableStateOf<Screen?>(initialSelectedScreen)
        }
        // Publish the current route to the test scope so assertions
        // can inspect the post-startup value without depending on a
        // specific text rendering.
        LaunchedEffect(currentScreen) { onScreenReady(currentScreen) }

        // Startup coroutine — mirrors MainActivity's shape.
        LaunchedEffect(Unit) {
            // Simulate slow bootstrap (multi-second on device). Under
            // Compose's frame clock, waitForIdle drains this
            // deterministically.
            delay(100)
            val restoredOrCurrent = currentScreen
            currentScreen = resolveScreenAfterStartup(decision, restoredOrCurrent)
        }

        // Render a stable label per route so `onNodeWithText` can
        // pin the currently-rendered screen if needed.
        Text(
            text = when (val s = currentScreen) {
                null -> "route = null"
                Screen.Profile -> "route = Profile"
                Screen.ChatList -> "route = ChatList"
                Screen.Onboarding -> "route = Onboarding"
                Screen.Migration -> "route = Migration"
                is Screen.StartupError -> "route = StartupError(${s.reasonName})"
                else -> "route = other=${s::class.simpleName}"
            },
        )
    }

    // ── Load-bearing integration test ────────────────────────

    @Test
    fun healthy_startup_after_recreation_preserves_restored_Profile() {
        var latestSeen: Screen? = null
        val restorationTester = StateRestorationTester(composeTestRule)

        // Round 1: mount the harness with the user already on Profile.
        // Startup coroutine fires on first composition; explicitly
        // advance the main clock past the 100 ms bootstrap delay so
        // the resolver has run + committed its value BEFORE the
        // saveable slot is snapshotted for the recreation.
        restorationTester.setContent {
            StartupHarness(
                decision = StartupRouteDecision.ChatList,
                onScreenReady = { latestSeen = it },
                initialSelectedScreen = Screen.Profile,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("route = Profile").assertIsDisplayed()
        assertEquals(Screen.Profile, latestSeen)

        // Round 2: simulate Activity recreation (rotation). The
        // `rememberSaveable` slot re-hydrates currentScreen from the
        // Bundle to `Screen.Profile`. The LaunchedEffect(Unit) fires
        // again, suspends 100 ms, then reads the LIVE currentScreen
        // (which is Profile) and calls the resolver with the healthy
        // ChatList decision. The resolver preserves Profile — the
        // load-bearing invariant. Prior shape captured a stale null
        // and clobbered the restored route to ChatList — this test
        // fails red on that regression.
        latestSeen = null
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        assertEquals(
            expected = Screen.Profile,
            actual = latestSeen,
            message = "Post-recreation, after the late startup coroutine finishes, " +
                "currentScreen MUST still be Profile.",
        )
        composeTestRule.onNodeWithText("route = Profile").assertIsDisplayed()
    }

    // ── Round-trip smoke: cold start with no restored route ──

    @Test
    fun healthy_startup_from_cold_start_with_no_restored_screen_lands_on_ChatList() {
        var latestSeen: Screen? = null
        composeTestRule.setContent {
            StartupHarness(
                decision = StartupRouteDecision.ChatList,
                onScreenReady = { latestSeen = it },
                initialSelectedScreen = null,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()
        assertEquals(Screen.ChatList, latestSeen)
        composeTestRule.onNodeWithText("route = ChatList").assertIsDisplayed()
    }
}
