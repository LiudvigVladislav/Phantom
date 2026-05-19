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
     * Candidate sizes in display order. 1700 = current production baseline
     * (no v3 win yet). 2200 / 2300 = expected safe candidates. 2400 = ceiling
     * candidate. 2600 = negative / borderline control — likely fails on
     * Tele2 LTE per the M2c.0 probe data, included so the FAIL state is
     * empirically observed rather than assumed.
     */
    val CANDIDATES: List<Int> = listOf(1700, 2200, 2300, 2400, 2600)

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
