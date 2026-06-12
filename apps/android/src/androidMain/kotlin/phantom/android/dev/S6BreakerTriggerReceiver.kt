// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import phantom.android.di.AppContainer

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 3 P1.2) — `BroadcastReceiver`
 * that routes an ADB-broadcast intent into the orchestrator's S6
 * controllable breaker trigger. Registered DYNAMICALLY from
 * `AppContainer.initMessaging` iff
 * `BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"`.
 *
 * **Why a receiver, not a debug menu UI.** The Tele2 LTE smoke is run
 * on a connected Tecno device alongside a laptop attached over USB.
 * An ADB-dispatched broadcast is the lightest possible debug surface —
 * no APK rebuild, no UI menu, no DebugActivity. The Tecno operator
 * fires:
 *
 * ```
 * adb shell am broadcast -a phantom.android.dev.S6_BREAKER_TRIGGER
 * ```
 *
 * from the connected laptop; logcat shows the resulting
 * `REST_TRACE breaker_test_trigger_fired` log line (or the
 * `breaker_test_trigger_refused` line if the trigger-flag gate held in
 * a release build).
 *
 * **Defence-in-depth.** Three independent checks must all pass before
 * the breaker mutates:
 *
 *   * The AppContainer-level registration block is gated on the
 *     dedicated `BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"`
 *     buildConfigField (decoupled from `isDebuggable` so a future beta
 *     variant can opt into the trigger via a gradle property).
 *     Release builds pin the field to `"0"` unconditionally; the
 *     receiver is never registered in release.
 *   * The receiver's [onReceive] re-checks
 *     `BuildConfig.S6_DEBUG_TRIGGER_ENABLED == "1"` defensively before
 *     dispatching.
 *   * The orchestrator constructor flag `s6DebugTriggerEnabled` is the
 *     load-bearing third gate inside the `:shared:core:transport`
 *     module. Even if a caller reached past both Android-side gates,
 *     the orchestrator-side helper would refuse and emit
 *     `REST_TRACE breaker_test_trigger_refused
 *     reason=disabled_in_release`.
 *
 * **Receiver export classification (API 33+).** Registered with
 * `RECEIVER_EXPORTED` so `adb shell am broadcast` — which dispatches
 * as the system shell user, NOT the registering app — can deliver the
 * intent. The runtime safety story does NOT rely on the export
 * classification; the three independent flag gates above are what hold
 * the production safety contract. Pre-Tiramisu the receiver flag is
 * omitted; the action namespace (`phantom.android.dev.*`) is
 * sufficient hardening combined with the trigger-flag gates.
 */
class S6BreakerTriggerReceiver(
    private val container: AppContainer,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (phantom.android.BuildConfig.S6_DEBUG_TRIGGER_ENABLED != "1") {
            Log.w(
                TAG,
                "onReceive() refused: BuildConfig.S6_DEBUG_TRIGGER_ENABLED=" +
                    "${phantom.android.BuildConfig.S6_DEBUG_TRIGGER_ENABLED} (the receiver " +
                    "should never have been registered with this flag value)",
            )
            return
        }
        if (intent?.action != ACTION) {
            Log.w(TAG, "onReceive() ignored: unexpected action `${intent?.action}`")
            return
        }
        Log.i(TAG, "S6 breaker trigger broadcast received; dispatching on AppContainer.appScope")
        container.appScope.launch {
            val dispatched = container.triggerS6BreakerForDebug()
            Log.i(TAG, "triggerS6BreakerForDebug() returned dispatched=$dispatched")
        }
    }

    companion object {

        const val ACTION: String = "phantom.android.dev.S6_BREAKER_TRIGGER"

        private const val TAG: String = "Phantom/S6Debug"
    }
}
