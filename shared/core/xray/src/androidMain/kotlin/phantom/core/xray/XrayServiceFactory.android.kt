// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.xray

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libXray.LibXray
import java.io.File

/**
 * Android implementation of [XrayService] (ADR-019 Stage 5E.B).
 *
 * Wraps libXray's gomobile JNI bridge. Xray-core runs *inside* our JVM via
 * `libgojni.so`; there is no child process and no IPC hop. Lifecycle is
 * therefore bound to whatever foreground service keeps the PHANTOM process
 * alive (in production, `PhantomMessagingService`).
 *
 * State derivation:
 *  - `runXrayFromJSON` is synchronous: it returns once Xray's inbound
 *    listeners are bound (or with an error). We map success → [Ready], all
 *    other paths → [Failed].
 *  - There is no progress event surface. REALITY's TLS handshake is fast
 *    enough that a "Bootstrapping %" UI would be misleading; we just block
 *    in [Starting] for the JNI call's duration (typically <1 s on a
 *    healthy link, multi-second on TSPU's worst days).
 *
 * Idempotency: [start] short-circuits when already [Ready] — checked via
 * `LibXray.getXrayState()` so we never double-start the in-process daemon
 * (a second call would return an "already running" error and clobber our
 * state machine).
 *
 * Thread-safety: libXray is in-process and not concurrency-safe across
 * start/stop calls; we serialise them with [lifecycleMutex]. The state
 * transitions all happen inside the mutex, so observers never see a torn
 * state (e.g. Ready → Off mid-stop).
 */
internal class XrayServiceAndroid(
    private val config: XrayServiceConfig,
) : XrayService {

    private val _state = MutableStateFlow<XrayState>(XrayState.Off)
    override val state: StateFlow<XrayState> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()

    override suspend fun start() = lifecycleMutex.withLock {
        if (_state.value is XrayState.Ready) return@withLock
        _state.value = XrayState.Starting
        try {
            withContext(Dispatchers.IO) { startBlocking() }
            _state.value = XrayState.Ready(socksPort = config.socksPort)
            Log.i(LOG_TAG, "started; SOCKS5 listener on 127.0.0.1:${config.socksPort}")
        } catch (t: Throwable) {
            val msg = t.message?.lineSequence()?.firstOrNull() ?: t::class.simpleName.orEmpty()
            Log.e(LOG_TAG, "start failed: $msg", t)
            _state.value = XrayState.Failed(message = msg)
            // Do not rethrow — callers compose this through a state observer,
            // not via try/catch on start(). Throwing here would break the
            // single-source-of-truth invariant of [_state] for failures.
        }
    }

    override suspend fun stop() = lifecycleMutex.withLock {
        if (_state.value is XrayState.Off) return@withLock
        try {
            withContext(Dispatchers.IO) {
                val responseB64 = LibXray.stopXray()
                val parsed = decodeAndParse(responseB64)
                if (!parsed.success) {
                    Log.w(LOG_TAG, "stopXray returned failure: ${parsed.data}")
                }
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "stopXray threw: ${t.message}", t)
        } finally {
            _state.value = XrayState.Off
        }
    }

    /**
     * Synchronous JNI call sequence executed on an IO thread. Splitting this
     * out keeps the coroutine layer above readable.
     *
     * The libXray entry point we use, [LibXray.runXrayFromJSON], expects a
     * base64-encoded JSON envelope shaped like
     * `{"datDir":..., "mphCachePath":..., "configJSON":...}` and returns a
     * base64-encoded `{"success":bool,"data":string}` envelope. The builder
     * helpers below match that contract exactly.
     */
    private fun startBlocking() {
        // Ensure the runtime's working directory exists; libXray will not
        // create missing parents.
        val dirFile = File(config.dataDirectoryPath).apply { mkdirs() }
        val datDir = dirFile.absolutePath
        val mphCachePath = File(datDir, "mph.cache").absolutePath

        // Diagnostic snapshot of the runtime environment libXray will see.
        // Surfaced because cross-device test 2026-05-10 had libXray return
        // `success=false` with an empty `data` field on every fresh install
        // — the bare error message gave us nothing to act on. Logging the
        // dir state + config size + pre-call XrayState lets the next test
        // pinpoint whether it's a missing asset, a config oversize, or a
        // stale in-process state (the gomobile binding is per-process).
        val dirContents = runCatching {
            dirFile.list()?.toList()?.sorted()?.joinToString(",") ?: "<null>"
        }.getOrElse { "<list-failed: ${it.message}>" }
        val preState = runCatching { LibXray.getXrayState() }.getOrElse { "<state-call threw>" }
        Log.i(
            LOG_TAG,
            "startBlocking: datDir=$datDir exists=${dirFile.exists()} writable=${dirFile.canWrite()} " +
                "contents=[$dirContents] preLibXrayState=$preState",
        )

        val xrayConfigJson = buildXrayClientConfig(config)
        Log.i(
            LOG_TAG,
            "startBlocking: configLen=${xrayConfigJson.length} configHead=" +
                xrayConfigJson.take(240).replace("\n", "\\n"),
        )

        val requestB64 = LibXray.newXrayRunFromJSONRequest(datDir, mphCachePath, xrayConfigJson)
        Log.i(LOG_TAG, "startBlocking: requestB64Len=${requestB64.length}")

        val responseB64 = LibXray.runXrayFromJSON(requestB64)
        // Always log the raw response — the only authoritative source for
        // libXray's own error reporting. Truncated to 4 KB so we never
        // spam the buffer if libXray returns a massive blob.
        val rawResponse = runCatching {
            String(Base64.decode(responseB64, Base64.NO_WRAP or Base64.URL_SAFE))
        }.getOrElse { "<decode-failed: ${it.message}>" }
        Log.i(
            LOG_TAG,
            "startBlocking: responseB64Len=${responseB64.length} " +
                "decodedLen=${rawResponse.length} decoded=${rawResponse.take(4_096)}",
        )

        val parsed = decodeAndParse(responseB64)
        if (!parsed.success) {
            error("runXrayFromJSON failed: ${parsed.data ?: "no detail"} (rawResponse=${rawResponse.take(512)})")
        }
        // Defensive cross-check — getXrayState should report true after a
        // successful run. If it doesn't, the JNI succeeded but the inbound
        // failed to bind (port collision is the usual cause), which we want
        // to surface as a failure rather than a falsely-Ready state.
        if (!LibXray.getXrayState()) {
            error("runXrayFromJSON returned success but getXrayState() is false (port collision?)")
        }
    }

    private fun decodeAndParse(b64: String): XrayResponse {
        val decoded = try {
            String(Base64.decode(b64, Base64.NO_WRAP or Base64.URL_SAFE))
        } catch (t: Throwable) {
            return XrayResponse(success = false, data = "base64 decode failed: ${t.message}")
        }
        return parseXrayResponse(decoded)
    }

    private companion object {
        private const val LOG_TAG = "PhantomXray"
    }
}

actual fun createXrayService(config: XrayServiceConfig): XrayService =
    XrayServiceAndroid(config)
