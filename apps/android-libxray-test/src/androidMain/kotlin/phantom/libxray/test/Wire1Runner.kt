// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.libxray.test

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import phantom.core.xray.OperatorXrayConfig
import phantom.core.xray.XrayState
import phantom.core.xray.createXrayService
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Per-iteration test runner for RC-LIBXRAY-REALITY-WIRE1.
 *
 * Each iteration:
 *   1. Allocate a fresh `xray-data` subdirectory so libXray cannot pick
 *      up state from a previous iteration.
 *   2. Construct an `XrayService` with the variant's config and start it.
 *   3. Wait for `XrayState.Ready` (or `Failed`) under a 15-second timeout.
 *   4. Build a fresh `OkHttpClient` with the SOCKS5 proxy bound to the
 *      Ready state's `socksPort`.
 *   5. Open one GET call to `https://relay.phntm.pro/health` with a
 *      conservative callTimeout (20 s) so a hung Reality handshake
 *      surfaces as `health_threw` rather than blocking the iteration.
 *   6. Log the response status + elapsed_ms (or exception class + message),
 *      stop the XrayService, evict OkHttp pool, and pause briefly before
 *      the next iteration.
 *
 * Per the Trek 1 mini-lock first-test-order gate, Variant 1 (Baseline)
 * is expected to reproduce the single-segment Reality ClientHello stall
 * documented in `project_arm_g_v10v11_libxray_android_write_stall_2026_06_08.md`.
 * If Baseline runs do NOT show that pattern in the server-side tcpdump,
 * the matrix HALTS and the investigation pivots to Arm G harness
 * integration — do not proceed to Variants 2-4.
 */
internal object Wire1Runner {

    private const val TAG = "LIBXRAY_WIRE1"
    private const val XRAY_READY_TIMEOUT_MS = 15_000L
    private const val INTER_ITERATION_DELAY_MS = 2_000L
    private const val HEALTH_URL = "https://relay.phntm.pro/health"

    /**
     * Run [iterations] fresh-XrayService iterations of [variantId], logging
     * each iteration's result line through [logger]. The [context] is used
     * only for `filesDir` to allocate per-iteration `xray-data` subdirs.
     *
     * This function is suspending so the calling Activity scope can cancel
     * a long run cleanly via back-button or onDestroy.
     */
    suspend fun run(
        context: Context,
        variantId: String,
        iterations: Int,
        logger: suspend (String) -> Unit,
    ) {
        val variant = Wire1Variants.bySlug(variantId)
            ?: run {
                logger("FATAL: unknown variant=$variantId")
                Log.e(TAG, "Unknown variant id: $variantId")
                return
            }

        logger(
            "=== RUN START variant=${variant.slug} iterations=$iterations " +
                "flow=${variant.flowOrEmpty()} network=${variant.network} ===",
        )

        var passCount = 0
        var failCount = 0
        for (i in 1..iterations) {
            if (!coroutineContext.isActive) {
                logger("Run cancelled by scope at iter=$i")
                return
            }
            val result = runIteration(context, variant, i, logger)
            when (result) {
                IterationOutcome.PASS -> passCount++
                IterationOutcome.FAIL -> failCount++
            }
            if (i < iterations && coroutineContext.isActive) {
                delay(INTER_ITERATION_DELAY_MS)
            }
        }

        logger(
            "=== RUN DONE variant=${variant.slug} " +
                "pass=$passCount fail=$failCount total=$iterations ===",
        )
    }

    private suspend fun runIteration(
        context: Context,
        variant: Wire1Variant,
        iter: Int,
        logger: suspend (String) -> Unit,
    ): IterationOutcome {
        val xrayWaitStartMs = System.currentTimeMillis()
        val dataDir = File(context.filesDir, "xray-wire1-data-$iter").absolutePath
        // libXray won't create missing parents; guarantee the dir exists
        // before we hand the path to runXrayFromJSON via XrayServiceAndroid.
        File(dataDir).mkdirs()

        val xrayConfig = variant.toXrayServiceConfig(dataDir)
        val xrayService = createXrayService(xrayConfig)

        try {
            logger("iter=$iter xray_start_requested")
            val startResult = runCatching { xrayService.start() }
            if (startResult.isFailure) {
                val t = startResult.exceptionOrNull()
                logger("iter=$iter xray_start_threw t=${t?.let { it::class.simpleName }}")
                return IterationOutcome.FAIL
            }

            val socksPort: Int = try {
                withTimeout(XRAY_READY_TIMEOUT_MS) {
                    val state = xrayService.state.first {
                        it is XrayState.Ready || it is XrayState.Failed
                    }
                    when (state) {
                        is XrayState.Ready -> state.socksPort
                        is XrayState.Failed -> {
                            logger(
                                "iter=$iter xray_failed message=${state.message?.take(120)}",
                            )
                            return@withTimeout -1
                        }
                        else -> error("unreachable")
                    }
                }
            } catch (t: Throwable) {
                logger(
                    "iter=$iter xray_ready_timeout elapsed_ms=" +
                        "${System.currentTimeMillis() - xrayWaitStartMs}",
                )
                return IterationOutcome.FAIL
            }

            if (socksPort < 0) return IterationOutcome.FAIL

            val xrayReadyElapsedMs = System.currentTimeMillis() - xrayWaitStartMs
            logger(
                "iter=$iter xray_ready socksPort=$socksPort elapsed_ms=$xrayReadyElapsedMs",
            )

            val client = OkHttpClient.Builder()
                .proxy(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress("127.0.0.1", socksPort),
                    ),
                )
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()

            val req = Request.Builder()
                .url(HEALTH_URL)
                .build()

            val started = System.currentTimeMillis()
            val outcome: IterationOutcome = try {
                client.newCall(req).execute().use { resp ->
                    val elapsed = System.currentTimeMillis() - started
                    logger(
                        "iter=$iter health_result status=${resp.code} elapsed_ms=$elapsed",
                    )
                    if (resp.isSuccessful) IterationOutcome.PASS else IterationOutcome.FAIL
                }
            } catch (t: Throwable) {
                val elapsed = System.currentTimeMillis() - started
                logger(
                    "iter=$iter health_threw t=${t::class.simpleName} " +
                        "msg=${t.message?.take(160)} elapsed_ms=$elapsed",
                )
                IterationOutcome.FAIL
            }

            runCatching {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }

            return outcome
        } finally {
            // Always stop xray, even if the iteration's call throws. The
            // gomobile libXray runtime is per-process, so a leaked Ready
            // instance would block the next iteration's start.
            runCatching {
                runBlocking { xrayService.stop() }
            }.onFailure { t ->
                logger("iter=$iter xray_stop_threw t=${t::class.simpleName}")
            }
        }
    }

    private fun Wire1Variant.flowOrEmpty(): String = flow.ifEmpty { "<empty>" }

    private enum class IterationOutcome { PASS, FAIL }

    private fun Wire1Variant.toXrayServiceConfig(dataDir: String) =
        when (slug) {
            "baseline" -> OperatorXrayConfig.toConfig(dataDir)
            "drop-vision" -> OperatorXrayConfig.toConfig(dataDir).copy(
                // Variant 2 discriminator: plain VLESS without
                // XTLS-Vision (per the Trek 1 mini-lock + claude.md
                // splice-race hypothesis). Server-side coordination
                // required: a separate diagnostic Reality inbound on
                // :8444 with `clients[].flow = ""` is brought up
                // alongside (NOT replacing) the production `:8443`
                // inbound via the `deploy/docker-compose.wire1-test.yml`
                // overlay; otherwise the server's VLESS auth rejects
                // the flow mismatch and the test cannot distinguish a
                // splice-race fix from an auth-level rejection.
                serverPort = serverPort,
                flow = flow,
            )
            // Variants 3-4 (`xhttp`, `httpupgrade`) are added in
            // subsequent commits ONLY after Variant 2 field result is in.
            // The skeleton fails fast here so a misconfigured run cannot
            // silently produce evidence under the wrong config shape.
            else -> error(
                "Variant '$slug' not yet wired in this skeleton; the Trek 1 " +
                    "mini-lock first-test-order gate requires Variant 2 result " +
                    "before adding Variants 3-4. See " +
                    "project_trek1_rc_libxray_reality_wire1_minilock_2026_06_09.md.",
            )
        }
}
