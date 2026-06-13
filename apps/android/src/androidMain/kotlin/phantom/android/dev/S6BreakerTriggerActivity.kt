// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.dev

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.launch
import phantom.android.PhantomApplication

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 10) — no-display Activity
 * that routes an `adb shell am start` invocation into the
 * orchestrator's S6 controllable breaker trigger.
 *
 * **Trigger recipe.** Tecno operator from connected laptop:
 *
 * ```
 * adb shell am start -n phantom.android/phantom.android.dev.S6BreakerTriggerActivity
 * ```
 *
 * The activity opens with `Theme.NoDisplay` so the user sees no
 * window flash; `noHistory="true"` keeps it off the back stack;
 * `excludeFromRecents="true"` keeps it out of the recents UI. The
 * dispatch runs on `AppContainer.appScope`; the activity calls
 * `finish()` from inside the launch's `finally` block (via
 * `runOnUiThread`) so the activity stays alive until the suspending
 * `triggerS6BreakerForDebug()` actually returns. A `job.isCancelled`
 * check immediately after the launch finishes the activity from the
 * main thread when the `appScope` was already cancelled, so the
 * activity never hangs.
 *
 * **Four-layer defence-in-depth.**
 *
 *   1. **Manifest-side debug-only declaration.** The activity is
 *      declared in `apps/android/src/debug/AndroidManifest.xml`
 *      ONLY. Release APKs do NOT carry the declaration —
 *      the component is invisible to `pm list packages -f` and
 *      `dumpsys package` on release.
 *   2. **Sender permission `INTERACT_ACROSS_USERS_FULL`.** The
 *      debug-only declaration carries
 *      `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`.
 *      The shell uid (which is what `adb shell am start` runs as)
 *      satisfies this permission; verified at the wire on
 *      TECNO BF7-12 (round 10). A third-party app installed
 *      alongside PHANTOM on a debug device CANNOT satisfy the
 *      permission — it is
 *      signature-scoped to the system signing certificate, so
 *      neither the implicit nor the explicit-component path is
 *      available to a third-party caller.
 *   3. **Activity onCreate flag gate.** Short-circuits when
 *      `BuildConfig.S6_DEBUG_TRIGGER_ENABLED != "1"`. The release
 *      variant pins the BuildConfig field to "0"; the activity is
 *      a runtime no-op in release.
 *   4. **AppContainer + orchestrator constructor gate.**
 *      [phantom.android.di.AppContainer.triggerS6BreakerForDebug]
 *      re-checks the same flag, and the orchestrator constructor
 *      flag `s6DebugTriggerEnabled` is the load-bearing inner gate
 *      inside `:shared:core:transport`. Even if a caller reached
 *      past both Android-side gates, the orchestrator-side gate
 *      refuses and emits `REST_TRACE breaker_test_trigger_refused
 *      reason=disabled_in_release`.
 *
 * The first two layers prevent cross-process invocation entirely
 * in release / by third-party callers; the inner two are the
 * runtime safety net for debug builds that legitimately receive
 * the `am start` call.
 */
class S6BreakerTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Defence-in-depth layer 2: re-check the BuildConfig flag
        // even though the manifest declaration is debug-only. A
        // future variant that flipped the manifest gate without
        // removing this activity from the codebase still no-ops
        // here.
        if (phantom.android.BuildConfig.S6_DEBUG_TRIGGER_ENABLED != "1") {
            Log.w(
                TAG,
                "onCreate refused: BuildConfig.S6_DEBUG_TRIGGER_ENABLED=" +
                    "${phantom.android.BuildConfig.S6_DEBUG_TRIGGER_ENABLED}",
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
        // `container` is `lateinit` — initialised asynchronously by
        // PhantomApplication on a background scope. If the activity
        // is launched before the container is ready, the lateinit
        // throws on access. Catch + log so the operator sees a
        // clear cause and can re-launch the app first.
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
        Log.i(TAG, "S6 breaker trigger activity invoked; dispatching on AppContainer.appScope")
        // Round-10 P1.architect — finish() is now scheduled INSIDE
        // the launch's finally block (on the main thread via
        // runOnUiThread), so the activity stays alive until the
        // dispatch completes (or fails). Without this, the OS could
        // sweep the process between finish() and the suspending
        // call resuming — the round-9 wiring only worked because
        // PHANTOM's foreground service kept the process alive in
        // every observed run.
        //
        // Round-10 P2.implementation-risk — guard against a
        // cancelled appScope. `launch { ... }` on a cancelled scope
        // returns a cancelled Job and the body never runs; without
        // the `isCancelled` check below the activity would hang
        // forever (no finish() ever fires). We log loudly and
        // finish() from the main thread.
        val job = container.appScope.launch {
            try {
                val dispatched = container.triggerS6BreakerForDebug()
                Log.i(TAG, "triggerS6BreakerForDebug() returned dispatched=$dispatched")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Round-10c (implementation L2 P2): re-throw structured
                // cancellation explicitly so the coroutine machinery
                // observes it as a clean cancel, not a swallowed
                // exception. A single `catch (Throwable)` with an
                // `is CancellationException` guard could swallow a
                // wrapped `CancellationException` (e.g., one delivered
                // through a `CompletionHandlerException`) and mask a
                // structural cancel failure on the parent scope.
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "S6 dispatch threw before completion", t)
            } finally {
                runOnUiThread { finish() }
            }
        }
        if (job.isCancelled) {
            Log.w(
                TAG,
                "appScope was already cancelled at launch time — finishing without dispatch",
            )
            finish()
        }
    }

    companion object {
        private const val TAG: String = "Phantom/S6Debug"
    }
}
