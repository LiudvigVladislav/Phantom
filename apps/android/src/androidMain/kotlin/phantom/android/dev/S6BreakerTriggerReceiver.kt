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
 * Trek 2 Stage 2B-B (C6 review-fix round 2 P1.1) — debug-only
 * `BroadcastReceiver` that routes an ADB-broadcast intent into the
 * orchestrator's S6 controllable breaker trigger.
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
 * `breaker_test_trigger_refused` line if the gate held in a release
 * build).
 *
 * **Defence-in-depth.** This receiver is registered DYNAMICALLY from
 * `AppContainer.initMessaging` iff `BuildConfig.DEBUG` is `true` — so
 * a release APK never registers the receiver at all and the intent
 * dispatch is a no-op on the device side. The receiver's `onReceive`
 * ALSO checks `BuildConfig.DEBUG` defensively, and the orchestrator
 * constructor flag `s6DebugTriggerEnabled` is the load-bearing third
 * gate. Three independent checks must all pass before the breaker
 * mutates.
 *
 * The receiver is registered with `RECEIVER_NOT_EXPORTED` on
 * `Build.VERSION_CODES.TIRAMISU` (API 33) and above so only the app's
 * own process (or `adb shell` running as the system shell user) can
 * dispatch the intent. Pre-Tiramisu the receiver flag is omitted; the
 * action namespace (`phantom.android.dev.*`) is sufficient hardening
 * combined with the `DEBUG` gates.
 */
class S6BreakerTriggerReceiver(
    private val container: AppContainer,
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (!phantom.android.BuildConfig.DEBUG) {
            Log.w(
                TAG,
                "onReceive() refused: not a DEBUG build (the receiver should never have been " +
                    "registered in this build mode)",
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
