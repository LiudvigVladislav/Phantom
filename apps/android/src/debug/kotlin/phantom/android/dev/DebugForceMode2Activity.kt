// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.dev

import android.app.Activity
import android.os.Bundle
import android.util.Log
import phantom.android.PhantomApplication
import phantom.core.transport.RestStateMachine
import phantom.core.transport.SyntheticTriggerResult

/**
 * QUIESCENCE-VALIDATION-L1-SYNTHETIC-MINI-LOCK §5.2 layer 3 — no-display
 * Activity that routes an `adb shell am start` invocation into
 * `AppContainer.triggerDebugForceMode2()`, the AppContainer-side wrapper
 * for the L1 synthetic Mode 2 trigger
 * [phantom.core.transport.KtorRelayTransport.debugForceMode2Synthetic].
 *
 * **Trigger recipe.** Tecno operator from connected laptop:
 *
 * ```
 * adb shell am start \
 *   -n phantom.android/phantom.android.dev.DebugForceMode2Activity \
 *   --el durationMs 45000
 * ```
 *
 * `--el durationMs <ms>` is optional; default is `45000` ms (mid-range
 * of the Mode 2 signature window
 * [RestStateMachine.MODE_2_MIN_DURATION_MS]..[RestStateMachine.MODE_2_MAX_DURATION_MS]).
 * Out-of-range values produce [SyntheticTriggerResult.RefusedDurationOutOfRange]
 * with diagnostic fields the operator can inspect via logcat.
 *
 * The activity opens with `Theme.NoDisplay` so the operator sees no
 * window flash; `noHistory="true"` keeps it off the back stack;
 * `excludeFromRecents="true"` keeps it out of the recents UI. The
 * trigger dispatch is synchronous (the typed [SyntheticTriggerResult]
 * return is logged immediately) so `finish()` runs from `onCreate`
 * without coroutine machinery — unlike `S6BreakerTriggerActivity`,
 * `triggerDebugForceMode2` is a non-suspending function.
 *
 * **Four-layer defence-in-depth.** Mirrors
 * `S6BreakerTriggerActivity`'s pattern verbatim.
 *
 *   1. **Manifest-side debug-only declaration.** The activity is
 *      declared in `apps/android/src/debug/AndroidManifest.xml` ONLY.
 *      Release APKs do not carry the declaration — the component is
 *      invisible to `pm list packages -f` and `dumpsys package` on
 *      release.
 *   2. **Sender permission `INTERACT_ACROSS_USERS_FULL`.** The debug-
 *      only declaration carries
 *      `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`.
 *      The shell uid (which is what `adb shell am start` runs as)
 *      satisfies this permission; the permission is signature-scoped to
 *      the system signing certificate, so a co-installed third-party
 *      app cannot satisfy it.
 *   3. **Activity onCreate flag gate.** Short-circuits when
 *      `BuildConfig.DEBUG_FORCE_MODE_2_DETECTION != "1"` OR
 *      `BuildConfig.DEBUG == false`. The release variant pins the
 *      BuildConfig field to `"0"` AND sets `BuildConfig.DEBUG = false`;
 *      the activity is a runtime no-op in release even if its manifest
 *      entry somehow survived a build accident.
 *   4. **AppContainer + KtorRelayTransport constructor gate.**
 *      [phantom.android.di.AppContainer.triggerDebugForceMode2] re-checks
 *      the same BuildConfig field plus the orchestrator's
 *      [RestStateMachine.isStickyOrRecoveryActive] state, and the
 *      `debugForceMode2Enabled` Boolean injected into
 *      `KtorRelayTransport`'s constructor is `false` in release as a
 *      final backstop. Even if a caller reached past the Android-side
 *      gates, the transport-side gate returns
 *      [SyntheticTriggerResult.RefusedDisabled].
 */
class DebugForceMode2Activity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Defence-in-depth layer 3a: BuildConfig field check. A future
        // variant that flipped the manifest gate without removing this
        // activity from the codebase still no-ops here.
        if (phantom.android.BuildConfig.DEBUG_FORCE_MODE_2_DETECTION != "1") {
            Log.w(
                TAG,
                "onCreate refused: BuildConfig.DEBUG_FORCE_MODE_2_DETECTION=" +
                    "${phantom.android.BuildConfig.DEBUG_FORCE_MODE_2_DETECTION}",
            )
            finish()
            return
        }
        // Defence-in-depth layer 3b: BuildConfig.DEBUG conjunction.
        // Release builds set DEBUG=false; even if a release APK
        // somehow shipped with DEBUG_FORCE_MODE_2_DETECTION="1" the
        // surface stays inert. Belt-and-suspenders on top of the
        // BuildConfig field check above.
        if (!phantom.android.BuildConfig.DEBUG) {
            Log.w(
                TAG,
                "onCreate refused: BuildConfig.DEBUG=${phantom.android.BuildConfig.DEBUG}",
            )
            finish()
            return
        }
        val app = application as? PhantomApplication
        if (app == null) {
            Log.w(TAG, "onCreate refused: application is not PhantomApplication")
            finish()
            return
        }
        val container = try {
            app.container
        } catch (uninitialized: UninitializedPropertyAccessException) {
            Log.w(
                TAG,
                "onCreate refused: AppContainer not initialised yet — launch the PHANTOM " +
                    "app first and wait for the home screen, then retry the trigger",
            )
            finish()
            return
        }
        val durationMs = intent
            ?.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        Log.i(
            TAG,
            "DebugForceMode2 activity invoked; durationMs=$durationMs — " +
                "calling AppContainer.triggerDebugForceMode2()",
        )
        val result = try {
            container.triggerDebugForceMode2(durationMs)
        } catch (t: Throwable) {
            Log.w(TAG, "triggerDebugForceMode2 threw", t)
            finish()
            return
        }
        Log.i(TAG, "triggerDebugForceMode2() returned result=$result")
        finish()
    }

    companion object {
        private const val TAG: String = "Phantom/DebugForceMode2"

        /**
         * Intent extra carrying the synthetic event's `durationMs` so the
         * operator can override the default mid-range value. Pass via
         * `adb shell am start --el durationMs <value>`. The transport-side
         * gate refuses with [SyntheticTriggerResult.RefusedDurationOutOfRange]
         * if the value lies outside
         * [RestStateMachine.MODE_2_MIN_DURATION_MS]..[RestStateMachine.MODE_2_MAX_DURATION_MS].
         */
        const val EXTRA_DURATION_MS: String = "durationMs"

        /**
         * Mid-range of the Mode 2 signature window
         * [RestStateMachine.MODE_2_MIN_DURATION_MS]..[RestStateMachine.MODE_2_MAX_DURATION_MS]
         * (25_000 .. 65_000). Used when the `--el durationMs <value>` extra
         * is absent. Sits comfortably inside the window so the synthetic
         * event always satisfies the 3-condition signature check.
         */
        const val DEFAULT_DURATION_MS: Long = 45_000L
    }
}
