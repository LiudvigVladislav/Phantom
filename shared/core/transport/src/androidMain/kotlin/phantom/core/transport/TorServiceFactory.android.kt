// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManagerFactory
import org.briarproject.onionwrapper.AndroidTorWrapper
import org.briarproject.onionwrapper.TorWrapper
import org.briarproject.onionwrapper.TorWrapper.Observer

/**
 * Android implementation of [TorService] (ADR-018, replacing ADR-016 Stage 2's
 * kmp-tor implementation).
 *
 * Wraps Briar's `AndroidTorWrapper` (org.briarproject:onionwrapper-android),
 * which bundles `tor-android` (the tor binary) plus `lyrebird-android`
 * (`libLyrebird.so` containing Snowflake / WebTunnel / obfs4 / meek
 * pluggable transports). Briar's stack has been production-tested for ten
 * years against the same hostile networks PHANTOM targets — see ADR-018
 * for the migration rationale.
 *
 * State derivation (mapping Briar's [TorWrapper.TorState] to ours):
 *  - `NOT_STARTED`, `STOPPED`, `STOPPING`, `DISABLED` → [TorState.Off]
 *  - `STARTING`, `STARTED`, `CONNECTING` → [TorState.Bootstrapping] with
 *    the most recent percentage from [Observer.onBootstrapPercentage]
 *  - `CONNECTED` → [TorState.Ready] with the fixed [SOCKS_PORT]
 *
 * Lifecycle pattern (Briar requires this two-step):
 *   start():  wrapper.start()  → wrapper.enableNetwork(true)
 *   stop():   wrapper.enableNetwork(false) → wrapper.stop()
 *
 * Idempotency: [start] short-circuits when already in [TorState.Ready];
 * [stop] short-circuits when already in [TorState.Off].
 *
 * Threading: Briar's wrapper is constructed with two single-thread
 * executors — one for I/O (binary extraction, control socket, etc.) and
 * one for delivering Observer callbacks. Our `_state` flow updates on the
 * event executor; downstream collectors should not assume Main dispatch.
 */
internal class TorServiceAndroid(
    private val application: Application,
    private val config: TorServiceConfig,
) : TorService {

    private val _state = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = _state.asStateFlow()

    @Volatile private var lastBootstrapPercent: Int = 0

    // The wrapper is constructed lazily on first start() — until then we
    // do not pay the cost of spinning up Briar's executors or extracting
    // the bundled tor binary from the APK.
    private val wrapper: AndroidTorWrapper by lazy { buildWrapper() }

    private fun buildWrapper(): AndroidTorWrapper {
        val torDirectory = File(config.dataDirectoryPath).apply { mkdirs() }
        val ioExecutor = Executors.newSingleThreadExecutor()
        val eventExecutor = Executors.newSingleThreadExecutor()
        val wakeLockManager = AndroidWakeLockManagerFactory
            .createAndroidWakeLockManager(application)
        val architecture = Build.SUPPORTED_ABIS.firstOrNull()
            ?: error("No supported ABI on this device — cannot run Briar tor wrapper")

        Log.i(LOG_TAG, "Constructing AndroidTorWrapper: arch=$architecture, dir=$torDirectory, socks=$SOCKS_PORT, control=$CONTROL_PORT")

        return AndroidTorWrapper(
            application,
            wakeLockManager,
            ioExecutor,
            eventExecutor,
            architecture,
            torDirectory,
            SOCKS_PORT,
            CONTROL_PORT,
        ).apply {
            setObserver(BriarObserver())
        }
    }

    override suspend fun start(bridgeProfile: BridgeProfile) {
        if (_state.value is TorState.Ready) return
        _state.value = TorState.Bootstrapping(percent = 0)
        try {
            withContext(Dispatchers.IO) {
                Log.i(LOG_TAG, "wrapper.start() bridgeProfile=${bridgeProfile.displayName}")
                wrapper.start()
                // ADR-018 Stage 5B: configure bridges BEFORE enableNetwork.
                // Once the network goes up, tor picks its first guard —
                // if bridges were not configured yet it would attempt a
                // vanilla TLS handshake to a hardcoded directory authority,
                // which TSPU/GFW classes drop. Order matters; reordering
                // breaks censored-network connectivity.
                if (config.useBridges) {
                    val bridges = bridgesFor(bridgeProfile)
                    if (bridges.isEmpty()) {
                        // PR-C: an empty list for a single-PT profile
                        // (e.g. Obfs4Only on a build without provisioned
                        // obfs4) would tell tor to use no bridges at all,
                        // i.e. the direct-guards path, which fails fast
                        // on censored networks. Honest signal back to the
                        // rotation walker — disable bridges for this
                        // attempt so tor surfaces Failed quickly instead
                        // of stalling indefinitely on a misconfiguration.
                        Log.w(
                            LOG_TAG,
                            "bridgeProfile=${bridgeProfile.displayName} resolved to empty list — " +
                                "calling disableBridges() so the attempt fails fast",
                        )
                        wrapper.disableBridges()
                    } else {
                        Log.i(
                            LOG_TAG,
                            "wrapper.enableBridges(profile=${bridgeProfile.displayName}, count=${bridges.size})",
                        )
                        wrapper.enableBridges(bridges)
                    }
                } else {
                    Log.i(LOG_TAG, "wrapper.disableBridges() (direct guards path)")
                    wrapper.disableBridges()
                }
                Log.i(LOG_TAG, "wrapper.enableNetwork(true)")
                wrapper.enableNetwork(true)
            }
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "start() failed: ${t.message}", t)
            _state.value = TorState.Failed(message = t.shortMessage())
            throw t
        }
    }

    /**
     * Resolve a [BridgeProfile] to the concrete bridge lines tor will
     * try, in priority order. Single-PT profiles return only their PT;
     * [BridgeProfile.Mixed] returns the full pre-PR-C stack (obfs4 →
     * webtunnel → snowflake) so the all-of-the-above fallback matches
     * historical behaviour. Order within the list matters: tor tries
     * entries top-down, so the most-likely-to-work entry should appear
     * first within each profile (already the case in the source lists).
     */
    private fun bridgesFor(profile: BridgeProfile): List<String> = when (profile) {
        // PR-E: obfs4 profile now combines our FlokiNET bridge with
        // Briar's 9 non-default obfs4 bridges so a censor that has
        // catalogued our single bridge can still be defeated by any
        // of the additional 9 reaching tor first.
        BridgeProfile.Obfs4Only ->
            OperatorBridges.OBFS4 + OperatorBridges.OBFS4_NON_DEFAULT
        BridgeProfile.WebtunnelOnly -> OperatorBridges.WEBTUNNEL
        // PR-E: snowflake profile is now Briar's RU-tuned set with the
        // Google AMP cache fallback (`front=www.google.com`). The older
        // PHANTOM-default set is still available as `SnowflakeBridges.DEFAULT`
        // for future locale-aware selection.
        BridgeProfile.SnowflakeOnly -> SnowflakeBridges.RU_TUNED
        BridgeProfile.MeekLite -> MeekBridges.DEFAULT
        BridgeProfile.Mixed ->
            OperatorBridges.OBFS4 + OperatorBridges.WEBTUNNEL + SnowflakeBridges.DEFAULT
        // PR-E: every bridge entry across every transport in one call.
        // Mirrors Briar's empirical strategy — let tor's own path
        // selection pick whichever bridge the network does not block.
        BridgeProfile.KitchenSink ->
            OperatorBridges.OBFS4 +
                OperatorBridges.OBFS4_NON_DEFAULT +
                OperatorBridges.WEBTUNNEL +
                SnowflakeBridges.RU_TUNED +
                MeekBridges.DEFAULT
    }

    override suspend fun stop() {
        if (_state.value is TorState.Off) return
        try {
            withContext(Dispatchers.IO) {
                Log.i(LOG_TAG, "wrapper.enableNetwork(false)")
                runCatching { wrapper.enableNetwork(false) }
                Log.i(LOG_TAG, "wrapper.stop()")
                runCatching { wrapper.stop() }
            }
        } finally {
            _state.value = TorState.Off
        }
    }

    /**
     * Bridges Briar's [TorWrapper.Observer] callbacks onto our [_state] flow.
     * Logs every event with tag "PhantomTor" so a USB-attached logcat
     * session sees the full bootstrap conversation.
     */
    private inner class BriarObserver : Observer {
        override fun onState(s: TorWrapper.TorState) {
            Log.i(LOG_TAG, "onState: $s (last bootstrap %=$lastBootstrapPercent)")
            _state.value = mapBriarState(s, lastBootstrapPercent)
        }

        override fun onBootstrapPercentage(percentage: Int) {
            Log.i(LOG_TAG, "onBootstrapPercentage: $percentage%")
            lastBootstrapPercent = percentage
            // Do not regress Ready back to Bootstrapping — late notices
            // can arrive after CONNECTED if tor finishes background work.
            if (_state.value !is TorState.Ready) {
                _state.value = TorState.Bootstrapping(percentage)
            }
        }

        override fun onHsDescriptorUpload(onion: String) {
            Log.i(LOG_TAG, "onHsDescriptorUpload: $onion")
            // Hidden-service hosting is ADR-016 future work; ignored for now.
        }

        override fun onClockSkewDetected(skewSeconds: Long) {
            Log.w(LOG_TAG, "onClockSkewDetected: $skewSeconds seconds — system clock disagrees with Tor consensus")
            // Surfacing this to the user is Stage 4 UI work.
        }
    }

    private fun mapBriarState(s: TorWrapper.TorState, percent: Int): TorState = when (s) {
        TorWrapper.TorState.NOT_STARTED,
        TorWrapper.TorState.STOPPED,
        TorWrapper.TorState.STOPPING,
        TorWrapper.TorState.DISABLED -> TorState.Off

        TorWrapper.TorState.STARTING,
        TorWrapper.TorState.STARTED,
        TorWrapper.TorState.CONNECTING -> TorState.Bootstrapping(percent)

        TorWrapper.TorState.CONNECTED -> TorState.Ready(socksPort = SOCKS_PORT)
    }

    private fun Throwable.shortMessage(): String =
        message?.lineSequence()?.firstOrNull() ?: this::class.simpleName.orEmpty()

    private companion object {
        private const val LOG_TAG = "PhantomTor"

        // Fixed ports for the embedded tor (Briar's wrapper requires these
        // at construction; it does not auto-pick like kmp-tor did). Picked
        // above the ephemeral range to avoid colliding with system services
        // and below 49152 so they are not in the OS-assigned dynamic range.
        // Mirrors what Briar themselves use internally.
        private const val SOCKS_PORT = 39050
        private const val CONTROL_PORT = 39051
    }
}

actual fun createTorService(
    config: TorServiceConfig,
    platformContext: Any?,
): TorService {
    val application = platformContext as? Application
        ?: error(
            "Android TorService requires the host Application as platformContext, " +
                "got ${platformContext?.let { it::class.simpleName } ?: "null"}",
        )
    return TorServiceAndroid(application, config)
}
