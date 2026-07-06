// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostics

import android.content.Context
import android.content.SharedPreferences
import phantom.android.BuildConfig

/**
 * B2-K8 client-side hold-override diagnostic seam.
 *
 * Companion to relay-side PR #370 (`RELAY_DIAG_WS_K8_CLIENT_HOLD_OVERRIDE_ENABLED`
 * env var, squash `c5e077db`). Both ends default off; enabling the client
 * override alone with the relay env unset is a no-op (server ignores
 * `?hold=` when the diag flag is off).
 *
 * Lives under `src/debug/kotlin` so the class is physically absent from
 * the release compilation unit — `commonMain` never references this
 * object. The resolved effective value is threaded to the transport via
 * a provider lambda captured at `AppContainer` construction time.
 *
 * ### Resolution order (at every `/relay/poll` request build)
 *
 *  1. Shared-prefs key `debug_k8_hold_override_seconds` (Int) — wins if
 *     present AND non-sentinel. Read at each poll (not cached) so the
 *     operator can change the hold value between polls in the same
 *     runner session without rebuilding the APK.
 *  2. `BuildConfig.DEBUG_K8_HOLD_OVERRIDE_SECONDS` — the String is
 *     parsed to Int at read time (fails safe to `-1` sentinel on any
 *     parse failure).
 *  3. Sentinel `-1` → NO `?hold` param is appended to `/relay/poll`;
 *     URL is byte-identical to pre-K8.
 *
 * Server-side clamps to `[0, 30]`; client sends raw integer without
 * pre-clamp so a value of `100` reaches the relay verbatim (and gets
 * clamped to `30`) — the discrimination surface for a "did the client
 * or the server enforce the clamp" question later.
 *
 * ### Prefs discipline
 *
 * Shares the app-wide `phantom_prefs` file (ADR-020 privacy-mode +
 * last-working-transport hint, chat-list UI state, existing
 * `debug_media_chunk_size` — see `ChunkSizeProbe`). Adding a new
 * prefs file would introduce a new storage abstraction the codebase
 * does not otherwise use.
 */
internal object K8HoldOverride {
    /** SharedPreferences file name — shared with the rest of the app. */
    const val PREFS_FILE: String = "phantom_prefs"

    /** Int prefs key holding the hold-seconds override. `-1` = unset. */
    const val PREF_KEY_HOLD_SECONDS: String = "debug_k8_hold_override_seconds"

    /** Boolean prefs key toggling the `Connection: close` interceptor. */
    const val PREF_KEY_CONNECTION_CLOSE: String = "debug_k8_connection_close"

    /** Sentinel "no override" value for the Int hold-seconds field. */
    const val SENTINEL_UNSET: Int = -1

    /**
     * Resolve the effective hold-override value at a single point in
     * time. Prefs wins if present AND non-sentinel; otherwise
     * BuildConfig; otherwise sentinel.
     */
    fun currentHoldOverride(prefs: SharedPreferences): Int {
        val prefsValue = prefs.getInt(PREF_KEY_HOLD_SECONDS, SENTINEL_UNSET)
        val buildConfigValue =
            BuildConfig.DEBUG_K8_HOLD_OVERRIDE_SECONDS.toIntOrNull() ?: SENTINEL_UNSET
        return resolveHoldOverride(prefsValue = prefsValue, buildConfigValue = buildConfigValue)
    }

    /**
     * Resolve the effective `Connection: close` interceptor toggle.
     * Prefs contains-key wins; otherwise BuildConfig String `"1"`.
     */
    fun currentConnectionClose(prefs: SharedPreferences): Boolean {
        if (prefs.contains(PREF_KEY_CONNECTION_CLOSE)) {
            return prefs.getBoolean(PREF_KEY_CONNECTION_CLOSE, false)
        }
        return BuildConfig.DEBUG_K8_CONNECTION_CLOSE == "1"
    }

    /**
     * Pure resolver — prefs wins when non-sentinel, otherwise BuildConfig.
     * Extracted for unit-test coverage of the four-case matrix without
     * needing SharedPreferences or BuildConfig indirection.
     */
    internal fun resolveHoldOverride(prefsValue: Int, buildConfigValue: Int): Int =
        if (prefsValue != SENTINEL_UNSET) prefsValue else buildConfigValue

    /**
     * Operator-facing setter used by future Settings-Diagnostics UI.
     * Kept internal so only debug-source-set callers can flip the flag.
     */
    fun setHoldOverride(prefs: SharedPreferences, seconds: Int) {
        prefs.edit().putInt(PREF_KEY_HOLD_SECONDS, seconds).apply()
    }

    fun setConnectionClose(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_KEY_CONNECTION_CLOSE, enabled).apply()
    }

    /** Convenience for opening the shared `phantom_prefs` file. */
    fun openPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
