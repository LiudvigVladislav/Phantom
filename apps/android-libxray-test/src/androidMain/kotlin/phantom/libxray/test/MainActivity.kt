// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.libxray.test

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RC-LIBXRAY-REALITY-WIRE1 (Trek 1) — Standalone diagnostic Activity.
 *
 * Launched via `adb shell am start -n phantom.libxray.test/.MainActivity`,
 * optionally with intent extras:
 *   `--es variant baseline`             — variant id (default: baseline)
 *   `--ei iterations 50`                — number of fresh-XrayService iterations (default: 50)
 *
 * Per the Trek 1 mini-lock (file
 * `project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md`), this
 * Activity runs the configured variant exactly `iterations` times in a
 * single foreground execution. Each iteration constructs a fresh
 * `XrayService`, waits for Ready, opens a single OkHttp `/health` call
 * through the SOCKS5 inbound, logs the result, and tears down the
 * service before the next iteration. The server-side tcpdump captures
 * the wire pattern for cross-comparison with the Arm G v10 baseline.
 *
 * Why a plain `Activity` (no androidx, no Compose): the test is a single
 * read-only progress view; an external dependency on AndroidX lifecycle
 * would add 1+ MB to the APK and a layer of state we do not need. The
 * UI is built programmatically (no `res/layout` XML) to keep the module
 * file count minimal and obvious-by-reading.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val variantId = intent.getStringExtra("variant") ?: "baseline"
        val iterations = intent.getIntExtra("iterations", 50)

        // Build a single scrolling TextView wrapped in a vertical LinearLayout.
        // The TextView shows iteration progress; full event detail goes to
        // logcat under tag LIBXRAY_WIRE1 (read via adb logcat).
        logView = TextView(this).apply {
            text = "LIBXRAY_WIRE1 variant=$variantId iterations=$iterations\n\n" +
                "Running on standalone test APK (apps/android-libxray-test).\n" +
                "See logcat tag LIBXRAY_WIRE1 for full per-iteration detail.\n\n"
            textSize = 12f
            setPadding(40, 40, 40, 40)
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(logView)
        }
        setContentView(root)

        Log.i(TAG, "MainActivity.onCreate variant=$variantId iterations=$iterations")

        // Launch the test run on Dispatchers.IO so the OkHttp blocking calls
        // and the XrayService lifecycle (libXray JNI calls) do not pin the
        // main thread. The scope is cancelled in onDestroy so a back-button
        // exit cleanly tears down any in-flight iteration.
        scope.launch {
            try {
                Wire1Runner.run(
                    context = applicationContext,
                    variantId = variantId,
                    iterations = iterations,
                    logger = { line -> appendLog(line) },
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Wire1Runner threw", t)
                appendLog("RUNNER THREW: ${t::class.simpleName} ${t.message?.take(200)}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MainActivity.onDestroy — cancelling test scope")
        scope.cancel()
    }

    private suspend fun appendLog(line: String) {
        // Append to logcat first (durable + greppable), then to the TextView
        // on the main thread for in-session readability.
        Log.i(TAG, line)
        withContext(Dispatchers.Main) {
            logView.append(line + "\n")
        }
    }

    private companion object {
        private const val TAG = "LIBXRAY_WIRE1"
    }
}
