// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import android.app.Application
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.briarproject.onionwrapper.TorWrapper
import kotlin.concurrent.Volatile

/** What one generation owns, from the service's point of view. */
internal interface TorHost {
    val token: GenerationToken
    val wrapper: TorWrapper
    suspend fun release(): ReleaseResult
}

/** A generation's host could not be built, or was refused. */
internal class TorStartRefusedException(reason: String) :
    IllegalStateException("tor start refused: $reason")

/**
 * Android [TorService], wired onto [TorLifecycleOwner].
 *
 * The service keeps no lifecycle state of its own. It does not decide when
 * a generation is running, it does not publish from the return value of an
 * old call, and it never writes [TorState] outside the owner's monitor. Its
 * whole job is a short projection: the owner says what the live generation
 * is, and this maps that onto the state the rest of the app reads.
 *
 * Three consequences are deliberate:
 *
 * - `Off` is published only from an outcome that means no daemon is left.
 *   The library's own `STOPPING` and `DISABLED` events never publish it —
 *   they arrive while a daemon is still winding down.
 * - a stop that came back unconfirmed does not read as stopped, and the
 *   next start is refused until it settles and its host lets go;
 * - a late confirmation reaches the state through the owner's projection,
 *   so no second stop is needed to notice it.
 */
internal class TorServiceAndroid(
    private val config: TorServiceConfig,
    private val hostFactory: (GenerationToken, TorWrapper.Observer) -> TorHost,
    workers: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val log: TorServiceLog = AndroidTorServiceLog,
    /** Where the blocking library calls run. Injected so host tests can
     *  keep one clock instead of mixing virtual and real time. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TorService {

    private val _state = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = _state.asStateFlow()

    private val owner = TorLifecycleOwner(
        handleFactory = ::buildHandle,
        workers = workers,
        project = ::project,
    )

    private fun buildHandle(token: GenerationToken): TorProcessHandle {
        val host = hostFactory(token, GenerationObserver(token))
        return AndroidTorProcessHandle(
            wrapper = host.wrapper,
            io = io,
            releaseHost = { host.release() },
        )
    }

    /**
     * Called inside the owner's monitor. Short by contract: a mapping and a
     * field write, no logging, no waiting, no library calls.
     */
    private fun project(status: GenerationStatus) {
        val outcome = status.outcome
        _state.value = when (status.phase) {
            Phase.Launching -> TorState.Bootstrapping(percent = 0)
            Phase.Idle, Phase.Running, Phase.Stopping -> _state.value
            Phase.Settled ->
                if (outcome != null && outcome.permitsNextGeneration) TorState.Off
                else TorState.Failed(STOP_NOT_CONFIRMED)
        }
    }

    override suspend fun start(bridgeProfile: BridgeProfile) {
        // Idempotency is not read off the published state: a teardown may
        // already have invalidated it while Ready was still showing. The
        // owner decides, in the same section that owns the phase.
        val bridges = if (config.useBridges) bridgesFor(bridgeProfile) else null
        log.write(
            TorLogLevel.Info,
            "start profile=${bridgeProfile.displayName} bridges=${bridges?.size ?: 0}",
            null,
        )
        when (val result = owner.start(bridges)) {
            is StartResult.Started, is StartResult.AlreadyRunning -> Unit
            is StartResult.ConfigurationFailed ->
                throw TorStartRefusedException("configuration failed after launch")

            is StartResult.RefusedHostNotReleased ->
                throw TorStartRefusedException("previous host has not been released")

            is StartResult.RefusedUnsettled ->
                throw TorStartRefusedException("previous stop is not confirmed")

            is StartResult.LaunchFailed, is StartResult.Skipped, is StartResult.Abandoned ->
                throw TorStartRefusedException(result::class.simpleName ?: "start failed")
        }
    }

    override suspend fun stop(budget: TorBudget): TorStopResponse {
        val response = owner.stop(budget)
        publish(response.result)
        return response
    }

    override suspend fun awaitRelease(
        attempt: TorStopAttempt,
        budget: TorBudget,
    ): TorStopResult {
        val result = owner.awaitRelease(attempt, budget)
        publish(result)
        return result
    }

    /**
     * A teardown result moves the state away from stopped only when the
     * DAEMON is unconfirmed. A host that has not let go of its threads does
     * not make a daemon that is provably gone look alive, so `Releasing`
     * and `ReleaseFailed` publish nothing here — they reach the caller in
     * the result instead.
     *
     * Addressed by the generation the result came from, not by whichever
     * is live now: a stop that waited while its own generation finished and
     * a successor started must not mark the successor failed.
     */
    private fun publish(result: TorStopResult) {
        if (result !is TorStopResult.NotConfirmed) return
        owner.onGeneration(result.generation) { phase ->
            if (phase != Phase.Settled) _state.value = TorState.Failed(STOP_NOT_CONFIRMED)
        }
        log.write(TorLogLevel.Warn, "stop not confirmed: ${result.reason}", null)
    }

    /**
     * Bridges Briar's callbacks onto the owner's gate.
     *
     * Every instance belongs to exactly one generation and holds its token
     * from birth, so an event that the library delivers late carries the
     * identity of the generation that produced it rather than whichever one
     * happens to be live at delivery time.
     */
    private inner class GenerationObserver(
        private val token: GenerationToken,
    ) : TorWrapper.Observer {

        /** This generation's own progress; never read by another. */
        private var bootstrapPercent = 0

        override fun onState(s: TorWrapper.TorState) {
            owner.onLiveGeneration(token) { phase ->
                if (phase != Phase.Running) return@onLiveGeneration
                when (s) {
                    TorWrapper.TorState.CONNECTED ->
                        _state.value = TorState.Ready(socksPort = SOCKS_PORT)

                    TorWrapper.TorState.STARTING,
                    TorWrapper.TorState.STARTED,
                    TorWrapper.TorState.CONNECTING ->
                        _state.value = TorState.Bootstrapping(bootstrapPercent)

                    // Never Off from an event: the daemon may still be
                    // winding down, and only a recorded outcome says it is
                    // gone.
                    TorWrapper.TorState.NOT_STARTED,
                    TorWrapper.TorState.STOPPED,
                    TorWrapper.TorState.STOPPING,
                    TorWrapper.TorState.DISABLED -> Unit
                }
            }
        }

        override fun onBootstrapPercentage(percentage: Int) {
            owner.onLiveGeneration(token) { phase ->
                // Written inside the gate: a late percentage from a closed
                // generation must not become the number a live one reports.
                bootstrapPercent = percentage
                if (phase == Phase.Running && _state.value !is TorState.Ready) {
                    _state.value = TorState.Bootstrapping(percentage)
                }
            }
        }

        override fun onHsDescriptorUpload(onion: String) = Unit

        override fun onClockSkewDetected(skewSeconds: Long) = Unit
    }

    /**
     * Resolve a [BridgeProfile] to the concrete bridge lines tor will try,
     * in priority order. Order within a list matters: tor walks entries
     * top-down.
     */
    private fun bridgesFor(profile: BridgeProfile): List<String> = when (profile) {
        BridgeProfile.Obfs4Only ->
            OperatorBridges.OBFS4 + OperatorBridges.OBFS4_NON_DEFAULT
        BridgeProfile.WebtunnelOnly -> OperatorBridges.WEBTUNNEL
        BridgeProfile.SnowflakeOnly -> SnowflakeBridges.RU_TUNED
        BridgeProfile.MeekLite -> MeekBridges.DEFAULT
        BridgeProfile.Mixed ->
            OperatorBridges.OBFS4 + OperatorBridges.WEBTUNNEL + SnowflakeBridges.DEFAULT
        BridgeProfile.KitchenSink ->
            OperatorBridges.OBFS4 +
                OperatorBridges.OBFS4_NON_DEFAULT +
                OperatorBridges.WEBTUNNEL +
                SnowflakeBridges.RU_TUNED +
                MeekBridges.DEFAULT
    }

    internal companion object {
        const val STOP_NOT_CONFIRMED = "stop not confirmed"
        const val STOP_BUDGET_MS = 15_000L

        // Fixed ports for the embedded tor (Briar's wrapper requires these
        // at construction; it does not auto-pick like kmp-tor did). Reusing
        // them across generations is safe only because no generation starts
        // while the previous one is unsettled.
        const val SOCKS_PORT = 39050
        const val CONTROL_PORT = 39051
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
    return TorServiceAndroid(
        config = config,
        hostFactory = { token, observer ->
            TorGenerationHost.create(
                token = token,
                application = application,
                config = config,
                socksPort = TorServiceAndroid.SOCKS_PORT,
                controlPort = TorServiceAndroid.CONTROL_PORT,
                observer = observer,
            )
        },
    )
}
