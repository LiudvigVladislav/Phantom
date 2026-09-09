// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import org.briarproject.onionwrapper.TorWrapper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Substitutes Briar's `TorWrapper` — the library interface itself — so
 * [AndroidTorProcessHandle] is the code under test rather than something
 * standing in for it.
 *
 * Only the five calls the handle makes are implemented. Every other member of
 * the interface fails loudly: if the handle ever grows a call this fixture
 * does not know about, that must surface as a failure and not as a silent
 * default.
 */
internal class FakeTorWrapper : TorWrapper {

    val calls = mutableListOf<String>()

    /** Thrown from `start()`, whatever it is. */
    var startFailure: Throwable? = null
    var enableBridgesFailure: Throwable? = null
    var disableBridgesFailure: Throwable? = null
    var enableNetworkFailure: Throwable? = null
    var stopFailure: Throwable? = null

    /** Held by `stop()` until released, to model the blocking teardown. */
    var stopGate: CountDownLatch? = null

    /**
     * Guard budget for [stopGate]. Exceeding it is a fault in the fixture,
     * never a teardown that finished, so it is kept short enough that a test
     * which forgets to release the parking fails quickly instead of waiting.
     */
    var stopGateBudgetMs: Long = 30_000

    /** Signals that `stop()` has actually entered the blocking wait. */
    val stopEntered = CountDownLatch(1)

    /**
     * Signals that `stop()` has LEFT, by any route — returned, threw, or was
     * interrupted. It says nothing about whether the daemon closed, and no
     * assertion may read it as success.
     */
    val stopReturned = CountDownLatch(1)

    /**
     * Whether the blocking wait was interrupted. The real `wrapper.stop()`
     * waits on `Process.waitFor()`, and interrupting that is the path where
     * the library drops its process reference while a daemon may still live,
     * so nothing above may interrupt it.
     */
    @Volatile
    var observedInterrupt = false

    var bridgesApplied: List<String>? = null

    override fun start() {
        synchronized(calls) { calls.add("start") }
        startFailure?.let { throw it }
    }

    override fun stop() {
        synchronized(calls) { calls.add("stop") }
        stopEntered.countDown()
        try {
            // Blocking on purpose: the real one waits on Process.waitFor().
            val gate = stopGate
            if (gate != null && !gate.await(stopGateBudgetMs, TimeUnit.MILLISECONDS)) {
                // The guard budget running out means the fixture failed to
                // release the parking. Returning normally here would let the
                // owner record a Confirmed stop for a teardown that never
                // finished, which is the one lie this fixture must not tell.
                error("the stop parking was not released within $stopGateBudgetMs ms")
            }
            stopFailure?.let { throw it }
        } catch (interrupt: InterruptedException) {
            observedInterrupt = true
            throw interrupt
        } finally {
            stopReturned.countDown()
        }
    }

    override fun enableBridges(bridges: MutableList<String>?) {
        synchronized(calls) { calls.add("enableBridges") }
        bridgesApplied = bridges?.toList()
        enableBridgesFailure?.let { throw it }
    }

    override fun disableBridges() {
        synchronized(calls) { calls.add("disableBridges") }
        disableBridgesFailure?.let { throw it }
    }

    override fun enableNetwork(enabled: Boolean) {
        synchronized(calls) { calls.add("enableNetwork:$enabled") }
        enableNetworkFailure?.let { throw it }
    }

    fun callCount(name: String): Int = synchronized(calls) { calls.count { it == name } }

    fun recorded(): List<String> = synchronized(calls) { calls.toList() }

    // ── not used by the handle; loud rather than silent ───────────────────

    override fun setObserver(observer: TorWrapper.Observer?) = unused("setObserver")

    override fun getTorState(): TorWrapper.TorState = unused("getTorState")

    override fun isTorRunning(): Boolean = unused("isTorRunning")

    override fun publishHiddenService(
        port: Int,
        remotePort: Int,
        privateKey: String?,
    ): TorWrapper.HiddenServiceProperties = unused("publishHiddenService")

    override fun removeHiddenService(onion: String?) = unused("removeHiddenService")

    override fun enableConnectionPadding(enabled: Boolean) = unused("enableConnectionPadding")

    override fun enableIpv6(enabled: Boolean) = unused("enableIpv6")

    override fun getLyrebirdExecutableFile(): File = unused("getLyrebirdExecutableFile")

    private fun unused(name: String): Nothing =
        error("$name is outside the boundary the handle uses")
}
