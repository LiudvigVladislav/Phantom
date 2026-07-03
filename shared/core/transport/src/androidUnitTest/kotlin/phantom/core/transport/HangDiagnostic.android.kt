// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Android unit test `actual` for the `HangDiagnostic` expect-fun seam.
 *
 * NO-OP. The BodyTimeoutContractTest hang under investigation only
 * reproduces on CI Ubuntu against the `jvmTest` compilation of the
 * `commonTest` source set. `androidUnitTest` runs on the host JVM
 * but has not been observed to reproduce the hang in prior CI runs.
 * A no-op actual lets Android unit tests compile without pulling
 * `kotlinx-coroutines-debug` into the Android test classpath.
 *
 * If a future finding shows the hang also reproduces from
 * `androidUnitTest` compilation, this file becomes a copy of the
 * `jvmTest` actual (both source sets are host-JVM so the same
 * `DebugProbes` + `Thread.getAllStackTraces` implementation works).
 */
actual fun beginHangDiagnostic(cellName: String) {
    // no-op on Android unit tests
}

actual fun endHangDiagnostic() {
    // no-op on Android unit tests
}
