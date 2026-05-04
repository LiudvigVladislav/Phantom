// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import android.util.Log
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.resource.noexec.tor.ResourceLoaderTorNoExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorListeners
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.OnFailure
import io.matthewnelson.kmp.tor.runtime.core.OnSuccess
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android implementation of [TorService] (ADR-016 Stage 2B).
 *
 * Wraps kmp-tor's [TorRuntime] with the bundled tor 0.4.9.5 binary supplied
 * via `resource-noexec-tor` (in-process JNI — no child process). Tor lives
 * inside the host JVM, so its lifecycle is bound to whatever foreground
 * service is keeping the PHANTOM process alive (in production, that's
 * `PhantomMessagingService`). We deliberately do NOT use kmp-tor's own
 * `runtime-service` foreground-service module — running two foreground
 * services on aggressive OEMs would split notifications and double the
 * surface for the OS to mis-account our process.
 *
 * State derivation:
 *  - [RuntimeEvent.LISTENERS] is the source of truth for "tor is usable":
 *    when the listener set contains a SOCKS address, we know tor has
 *    bootstrapped, opened its proxy port, and is ready to accept circuits.
 *    Empty listener set = teardown → state goes back to Off.
 *  - [RuntimeEvent.ERROR] is mapped to [TorState.Failed] with a single-line
 *    summary; full diagnostics flow to RelayLog at the call site.
 *
 * Bootstrap progress percentages (the `Bootstrapped 5% … 100%` notice
 * lines) are not parsed in Stage 2B. The Privacy-Mode UI in Stage 4 will
 * surface them; until then [TorState.Bootstrapping] holds at percent=0
 * between [start] and the first LISTENERS event.
 *
 * Idempotency: [start] is a no-op when already [TorState.Ready]; [stop]
 * is a no-op when already [TorState.Off]. Both wrap kmp-tor's callback
 * `enqueue` in [suspendCancellableCoroutine], so callers compose under
 * the usual coroutine cancellation rules.
 */
internal class TorServiceAndroid(
    private val config: TorServiceConfig,
) : TorService {

    private val _state = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = _state.asStateFlow()

    private val runtime: TorRuntime by lazy { buildRuntime() }

    private fun buildRuntime(): TorRuntime {
        val workDirectory = config.dataDirectoryPath.toFile()
        val cacheDirectory = config.cacheDirectoryPath.toFile()

        val environment = TorRuntime.Environment.Builder(
            workDirectory = workDirectory,
            cacheDirectory = cacheDirectory,
            loader = ResourceLoaderTorNoExec::getOrCreate,
        )

        return TorRuntime.Builder(environment) {
            val executor = OnEvent.Executor.Immediate

            // SOCKS listener up = tor is fully usable. Empty listener set =
            // daemon stopped or DisableNetwork toggled. The bound port is
            // what Stage 2C hands to OkHttp as a Proxy.SOCKS.
            observerStatic(RuntimeEvent.LISTENERS, executor) { listeners: TorListeners ->
                val sockAddr = listeners.socks.firstOrNull()
                _state.value = if (sockAddr == null) {
                    Log.i(LOG_TAG, "LISTENERS: socks set empty → state Off")
                    TorState.Off
                } else {
                    Log.i(LOG_TAG, "LISTENERS: socks bound on port ${sockAddr.port.value} → state Ready")
                    TorState.Ready(socksPort = sockAddr.port.value)
                }
            }

            // Hard runtime errors → Failed. Single-line summary; full log
            // detail flows through the LOG.* observers below.
            observerStatic(RuntimeEvent.ERROR, executor) { throwable ->
                Log.e(LOG_TAG, "ERROR: ${throwable.shortMessage()}", throwable)
                _state.value = TorState.Failed(message = throwable.shortMessage())
            }

            // Stage 2C.5 observability — pipe kmp-tor's internal log streams
            // into Android Logcat (tag = "PhantomTor") so a USB-attached
            // logcat session sees the full bootstrap conversation. Without
            // these, kmp-tor's logs vanish into /dev/null and a stuck
            // bootstrap is invisible.
            observerStatic(RuntimeEvent.LOG.WARN, executor) { line ->
                Log.w(LOG_TAG, "WARN: $line")
                maybeUpdateBootstrapPercent(line)
            }
            observerStatic(RuntimeEvent.LOG.INFO, executor) { line ->
                Log.i(LOG_TAG, "INFO: $line")
                maybeUpdateBootstrapPercent(line)
            }
            observerStatic(RuntimeEvent.LOG.DEBUG, executor) { line ->
                // Debug is loud — keep it at Log.d so a default logcat
                // filter (showing INFO+) doesn't drown in it.
                Log.d(LOG_TAG, "DEBUG: $line")
                maybeUpdateBootstrapPercent(line)
            }

            config { _ ->
                // Auto-pick an ephemeral SOCKS port; we discover the chosen
                // port from the LISTENERS event above. Hard-coding 9050 risks
                // colliding with an Orbot install or a prior session that
                // didn't release the port cleanly.
                TorOption.__SocksPort.configure { auto() }
            }

            // Always subscribe to ERR/WARN at the control protocol level so
            // tor surfaces them via RuntimeEvent listeners regardless of
            // whether the user has any other observers attached.
            required(TorEvent.ERR)
            required(TorEvent.WARN)
        }
    }

    /**
     * If the line emitted by kmp-tor contains tor's `Bootstrapped N%` notice,
     * promote that progress into [_state] as [TorState.Bootstrapping]. We
     * never overwrite [TorState.Ready] — once LISTENERS gave us a SOCKS
     * port, late-arriving bootstrap notices (e.g. tor finishing background
     * directory work after circuits are usable) must not regress the state.
     */
    private fun maybeUpdateBootstrapPercent(line: Any?) {
        val text = line?.toString() ?: return
        val percent = BOOTSTRAP_REGEX.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)
            ?: return
        if (_state.value !is TorState.Ready) {
            _state.value = TorState.Bootstrapping(percent)
        }
    }

    override suspend fun start() {
        if (_state.value is TorState.Ready) return
        _state.value = TorState.Bootstrapping(percent = 0)
        try {
            runtime.enqueueAction(Action.StartDaemon)
        } catch (t: Throwable) {
            _state.value = TorState.Failed(message = t.shortMessage())
            throw t
        }
    }

    override suspend fun stop() {
        if (_state.value is TorState.Off) return
        try {
            runtime.enqueueAction(Action.StopDaemon)
        } finally {
            // The LISTENERS observer normally drives state → Off when tor
            // tears its sockets down; this final assignment is a safety net
            // in case the listener event is lost (e.g. process death race).
            _state.value = TorState.Off
        }
    }

    private companion object {
        private const val LOG_TAG = "PhantomTor"
        // Matches tor's notice/info bootstrap line, e.g.
        // "Bootstrapped 5% (conn): Connecting to a relay" or
        // "Bootstrapped 100% (done): Done".
        private val BOOTSTRAP_REGEX = Regex("""Bootstrapped\s+(\d{1,3})%""")
    }
}

/**
 * Suspend wrapper around kmp-tor's callback-based [TorRuntime.enqueue].
 *
 * kmp-tor's runtime exposes `startDaemonAsync` / `stopDaemonAsync` as
 * extension functions in a separate artifact; depending on the bare
 * `enqueue` member keeps our gradle dependency surface minimal. The wrapper
 * resumes the coroutine on either branch and surfaces any failure as a
 * normal exception.
 */
private suspend fun TorRuntime.enqueueAction(action: Action) {
    suspendCancellableCoroutine<Unit> { cont ->
        val job = enqueue(
            action,
            OnFailure { t -> if (cont.isActive) cont.resumeWithException(t) },
            OnSuccess<Unit> { if (cont.isActive) cont.resume(Unit) },
        )
        cont.invokeOnCancellation { job.cancel(null) }
    }
}

private fun Throwable.shortMessage(): String =
    message?.lineSequence()?.firstOrNull() ?: this::class.simpleName.orEmpty()

actual fun createTorService(config: TorServiceConfig): TorService =
    TorServiceAndroid(config)
