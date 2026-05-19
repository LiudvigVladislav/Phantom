// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostics

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import phantom.android.BuildConfig
import phantom.core.messaging.MediaChunker

/**
 * PR-M2f.1 — debug-only chunk-size probe.
 *
 * Surfaces a `Settings → Diagnostics → Media chunk size` selector so the
 * Tele2 full-roundtrip ceiling can be probed across 1700 / 2200 / 2300 /
 * 2400 / 2600 byte raw-ciphertext-per-chunk values in a single APK,
 * without rebuilding per row.
 *
 * The value is persisted in `phantom_prefs` under [PREF_KEY] and read by
 * [phantom.core.messaging.VoiceV2Sender.chunkSizeProvider] (wired in
 * `AppContainer`). Production callers should never reach this code path
 * because [isProbeAvailable] gates the UI behind `BuildConfig.DEBUG` and
 * the provider falls back to [MediaChunker.TARGET_RAW_CHUNK_BYTES] when
 * the preference is absent.
 *
 * Test #70 acceptance: each candidate size must yield a playable voice
 * end-to-end without `sha256_mismatch` / `decrypt_failed` /
 * `media_chunks_gone`. Production ship value follows the locked margin
 * policy (max stable - one tier; see PR-M2f.1 review).
 */
object ChunkSizeProbe {

    /** SharedPreferences key. Same prefs file as everything else (`phantom_prefs`). */
    const val PREF_KEY: String = "debug_media_chunk_size"

    /**
     * Candidate sizes in display order.
     *
     * First five (1700 / 2200 / 2300 / 2400 / 2600) — Test #70 matrix
     * (PR-M2f.1). All four candidates above 1700 passed on Tele2 LTE with
     * the v3 binary path, zero `sha256_mismatch` / `decrypt_failed` /
     * `media_chunks_gone`, and the architect-predicted "Tele2 ceiling at
     * 2400" turned out to apply only to v2 JSON+Base64 — v3 sends raw
     * ciphertext on the wire, freeing ~33 % of the previously consumed
     * body envelope. 2600 was originally seeded as a borderline negative
     * control and surprisingly passed full roundtrip.
     *
     * Last four (2800 / 3000 / 3200 / 3500) — Test #70.1 extended probe
     * (PR-M2f.1b, this commit). Vladislav locked path B 2026-05-19:
     * find the real v3 ceiling instead of shipping 2400 by old margin.
     * Production policy stays "do not ship the maximum that passed once":
     *   2800 stable, 3000 unstable → ship 2800
     *   3000 stable, 3200 unstable → ship 3000
     *   3200 stable, 3500 unstable → ship 3000
     *   3500 stable → max 3200 after retest, never 3500 from one session
     */
    val CANDIDATES: List<Int> = listOf(
        1700, 2200, 2300, 2400, 2600,
        2800, 3000, 3200, 3500,
    )

    /** Selector visible only on debug builds. */
    val isProbeAvailable: Boolean = BuildConfig.DEBUG

    fun currentValue(prefs: SharedPreferences): Int =
        prefs.getInt(PREF_KEY, MediaChunker.TARGET_RAW_CHUNK_BYTES)

    fun setValue(prefs: SharedPreferences, bytes: Int) {
        prefs.edit().putInt(PREF_KEY, bytes).apply()
    }

    /** Convenience for the Settings Compose tree. */
    @androidx.compose.runtime.Composable
    fun rememberSelectedChunkSize(context: Context): Pair<Int, (Int) -> Unit> {
        val prefs = context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
        var state by remember { mutableIntStateOf(currentValue(prefs)) }
        return state to { v ->
            setValue(prefs, v)
            state = v
        }
    }
}
