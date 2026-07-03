// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * expect/actual seam for capturing coroutine + JVM thread stack
 * snapshots from within `commonTest` scope during hang investigations.
 *
 * Used by `BodyTimeoutContractTest` on the
 * `diagnostic/body-timeout-hang-dump-commontest` branch to produce
 * `DebugProbes.dumpCoroutines()` + `Thread.getAllStackTraces()` output
 * to stderr on CI Ubuntu at a 60 s wall-clock cadence, so a hang
 * that only reproduces from the `commonTest` source set (per PR #362
 * Option D verdict: CANCELLED at 30-min job timeout on head `d2ba8056`)
 * can be observed at the wire level from the ACTUAL failing source set,
 * not from a `jvmTest` verbatim duplicate.
 *
 * Contract:
 *
 *   - [beginHangDiagnostic] MUST be called BEFORE the code that may
 *     hang. It starts a JVM daemon thread that prints a dump every
 *     60 s of real time until [endHangDiagnostic] is called.
 *   - [endHangDiagnostic] cancels the daemon and clears platform state.
 *   - Reentrant `begin` without matching `end` is not supported;
 *     actuals may print a warning and forcibly stop the prior handle.
 *   - Test isolation is per-`@Test` cell; concurrent invocations across
 *     cells do not occur in `runTest` blocks.
 *
 * Actuals:
 *
 *   - `jvmTest`: real DebugProbes + Thread daemon.
 *   - `androidUnitTest` (if compiled): matching implementation (Android
 *     unit tests run on host JVM anyway).
 *
 * NOT for merge to master via this branch. Diagnostic only.
 */
expect fun beginHangDiagnostic(cellName: String)

expect fun endHangDiagnostic()
