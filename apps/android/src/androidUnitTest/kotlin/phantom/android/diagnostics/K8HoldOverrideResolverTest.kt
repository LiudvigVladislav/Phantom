// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B2-K8 pure-resolver contract tests (design note §2.6 tests 1-3,
 * 2026-07-06). Exercises the `K8HoldOverride.resolveHoldOverride`
 * helper across the four (prefsValue × buildConfigValue) branches so
 * a future refactor cannot silently drift the resolution order.
 *
 * The helper is the recon-critical decision point: the operator flips
 * `debug_k8_hold_override_seconds` between polls in the runner and the
 * DI wiring in `AppContainer` reads the pure result on the very next
 * `poll(...)` invocation. Any regression here would either
 *  * silently override BuildConfig-set values when prefs is unset →
 *    K8 field session becomes untrustworthy; OR
 *  * fail to strip the `?hold=N` param when both sides are sentinel →
 *    release APK stops being byte-identical to pre-K8.
 *
 * The test file lives in `androidUnitTest` so `testDebugUnitTest`
 * compiles the debug source set (where `K8HoldOverride` lives) alongside
 * the test — the resolver is `internal` so cross-source-set visibility
 * requires this placement.
 */
class K8HoldOverrideResolverTest {

    @Test
    fun `prefs present beats buildconfig when non sentinel`() {
        // §2.6 test 1: prefs_present_beats_buildconfig_when_non_sentinel.
        // The recon-critical case — operator flips prefs mid-session
        // to try a different hold value without an APK rebuild. The
        // BuildConfig value stays as the compile-time default; the
        // runtime override must win.
        assertEquals(
            10,
            K8HoldOverride.resolveHoldOverride(prefsValue = 10, buildConfigValue = 5),
        )
    }

    @Test
    fun `prefs absent falls back to buildconfig`() {
        // §2.6 test 2: prefs_absent_falls_back_to_buildconfig. Absent
        // is represented as the sentinel (`-1`) at the resolver's
        // input boundary — DI wiring converts a missing shared-prefs
        // key to `-1` via `getInt(key, -1)`.
        assertEquals(
            5,
            K8HoldOverride.resolveHoldOverride(
                prefsValue = K8HoldOverride.SENTINEL_UNSET,
                buildConfigValue = 5,
            ),
        )
    }

    @Test
    fun `prefs sentinel falls back to buildconfig`() {
        // §2.6 test 3: prefs_sentinel_falls_back_to_buildconfig.
        // Identical in behaviour to `prefs_absent_falls_back_...` at
        // the pure-resolver layer, kept as a separate cell because
        // the DI-wiring layer treats "key missing" and "key set to
        // -1" as distinct read paths — the resolver contract must
        // collapse them to a single result.
        assertEquals(
            5,
            K8HoldOverride.resolveHoldOverride(
                prefsValue = K8HoldOverride.SENTINEL_UNSET,
                buildConfigValue = 5,
            ),
        )
    }

    @Test
    fun `both sentinel returns sentinel so URL builder skips hold`() {
        // Both sides unset → the composer's `holdOverride >= 0` branch
        // fails and `?hold=N` is NOT appended. This is the "release
        // APK unchanged" load-bearing case; if this cell fails, the
        // release APK's `/relay/poll` URL diverges from pre-K8 master
        // and the design note §1.4 non-goal fires.
        assertEquals(
            K8HoldOverride.SENTINEL_UNSET,
            K8HoldOverride.resolveHoldOverride(
                prefsValue = K8HoldOverride.SENTINEL_UNSET,
                buildConfigValue = K8HoldOverride.SENTINEL_UNSET,
            ),
        )
    }

    @Test
    fun `prefs zero is not sentinel and is preferred over buildconfig`() {
        // `0` is the K8 field-session recon-critical hold value
        // (Run 2 and Run 3 both fix `hold=0` per design note §3.1
        // and the `hold=0` split §4). Zero MUST NOT be treated as
        // sentinel — the resolver distinguishes on `!= -1`, not on
        // truthiness.
        assertEquals(
            0,
            K8HoldOverride.resolveHoldOverride(prefsValue = 0, buildConfigValue = 15),
        )
    }
}
