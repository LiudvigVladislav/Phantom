// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package phantom.core.transport

import kotlinx.coroutines.debug.DebugProbes
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

private data class WatchdogHandle(
    val thread: Thread,
    val done: AtomicBoolean,
    val cellName: String,
)

@Volatile private var activeHandle: WatchdogHandle? = null

actual fun beginHangDiagnostic(cellName: String) {
    val existing = activeHandle
    if (existing != null) {
        System.err.println(
            "HangDiagnostic: WARNING — begin called with existing active handle for " +
                "cell=${existing.cellName}; forcing prior stop before starting cell=$cellName",
        )
        forceStop(existing)
    }
    DebugProbes.install()
    val done = AtomicBoolean(false)
    val startWallMs = System.currentTimeMillis()
    val thread = Thread {
        var iter = 0
        while (!done.get()) {
            try {
                Thread.sleep(60_000L)
            } catch (t: InterruptedException) {
                return@Thread
            }
            if (done.get()) return@Thread
            iter += 1
            dumpAllThreadStates(iter, cellName, startWallMs)
        }
    }.apply {
        isDaemon = true
        name = "HangDiagnostic-Watchdog"
    }
    thread.start()
    activeHandle = WatchdogHandle(thread, done, cellName)
}

actual fun endHangDiagnostic() {
    val handle = activeHandle ?: return
    forceStop(handle)
    activeHandle = null
    try {
        DebugProbes.uninstall()
    } catch (t: Throwable) {
        // Uninstall can throw if install was not called; safe to ignore
        // in the diagnostic path.
    }
}

private fun forceStop(handle: WatchdogHandle) {
    handle.done.set(true)
    handle.thread.interrupt()
}

private fun dumpAllThreadStates(iter: Int, cellName: String, startWallMs: Long) {
    val elapsedS = (System.currentTimeMillis() - startWallMs) / 1000L
    val err: PrintStream = System.err
    synchronized(err) {
        err.println()
        err.println("╔══════════════════════════════════════════════════════════════════════════════")
        err.println("║ HANG-DIAG WATCHDOG FIRE #$iter  cell=$cellName  wall_clock_elapsed_s=$elapsedS")
        err.println("╚══════════════════════════════════════════════════════════════════════════════")
        err.println("─── DebugProbes.dumpCoroutines ───")
        try {
            DebugProbes.dumpCoroutines(err)
        } catch (t: Throwable) {
            err.println("  (DebugProbes.dumpCoroutines threw: ${t.javaClass.simpleName}: ${t.message})")
        }
        err.println()
        err.println("─── Thread.getAllStackTraces ───")
        val stacks = Thread.getAllStackTraces()
        for ((thread, frames) in stacks) {
            err.println()
            err.println("Thread \"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}")
            for (frame in frames) {
                err.println("  at $frame")
            }
        }
        err.println("─── end HANG-DIAG dump #$iter ───")
        err.println()
    }
}
