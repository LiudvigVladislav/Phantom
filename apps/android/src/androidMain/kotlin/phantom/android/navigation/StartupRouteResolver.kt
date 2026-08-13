// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.navigation

import phantom.android.screens.onboarding.v2.StartupRouteDecision

/**
 * Final Stabilization Mini-Block 2026-08-11 — pure resolver for the
 * post-startup route assignment.
 *
 * The prior shape captured a stale `currentScreen` (always `null` at
 * cold start) inside the startup [kotlinx.coroutines.CoroutineScope]
 * and unconditionally wrote `Screen.ChatList` when the decision was
 * healthy. After a rotation that restored `currentScreen = Profile`
 * via [ScreenSaver], the still-in-flight startup coroutine finished
 * a few seconds later and clobbered the restored route with
 * `Screen.ChatList` — dropping the user out of Profile into Chats.
 *
 * The fix is a pure resolver called with the *live* value of
 * `currentScreen` at the moment the startup coroutine completes:
 *
 * ```
 * RepairQuarantine        -> Screen.Onboarding   (forced repair path)
 * TransientStartupFailure -> Screen.StartupError (forced retry path)
 * FreshOnboarding         -> Screen.Onboarding
 * Migration               -> Screen.Migration
 * ChatList                -> currentOrRestoredScreen ?: Screen.ChatList
 * ```
 *
 * Load-bearing invariant:
 *
 * > A healthy `ChatList` decision MUST NOT clobber a route the user
 * > was already on (either explicitly navigated to before the
 * > coroutine finished, or restored by [ScreenSaver] after a
 * > recreation).
 *
 * The four security-critical decisions (`RepairQuarantine`,
 * `TransientStartupFailure`, `FreshOnboarding`, `Migration`) retain
 * their right to a forced route — they signal deterministic
 * startup-state conditions that always override user navigation.
 *
 * No timers, no delays, no rotation-specific branching — the fix is
 * a single value snapshot at coroutine completion.
 *
 * Side-effect note: the durable-marker write for `RepairQuarantine`
 * is still driven by
 * `phantom.android.screens.onboarding.v2.applyStartupDecision`
 * upstream of this resolver. The resolver only decides the final
 * [Screen] to render; it does not touch disk, preferences, or the
 * in-memory quarantine flag.
 *
 * The transient-failure branch reads
 * [StartupRouteDecision.TransientStartupFailure.reason]'s `name` so
 * the surface can display the stable copy without leaking the
 * underlying throwable message (matching the prior mapping in
 * `MainActivity`).
 */
internal fun resolveScreenAfterStartup(
    decision: StartupRouteDecision,
    currentOrRestoredScreen: Screen?,
): Screen = when (decision) {
    is StartupRouteDecision.RepairQuarantine ->
        Screen.Onboarding
    is StartupRouteDecision.TransientStartupFailure ->
        Screen.StartupError(decision.reason.name)
    StartupRouteDecision.FreshOnboarding ->
        Screen.Onboarding
    StartupRouteDecision.Migration ->
        Screen.Migration
    StartupRouteDecision.ChatList ->
        currentOrRestoredScreen ?: Screen.ChatList
}
