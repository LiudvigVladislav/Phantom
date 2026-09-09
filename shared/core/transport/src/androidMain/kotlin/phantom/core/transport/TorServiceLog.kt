// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

import android.util.Log

/**
 * The one place [TorServiceAndroid] writes a log line.
 *
 * It exists so host tests can run the real service without turning
 * `android.util.Log` into a returning stub for the whole test target: a
 * blanket `returnDefaultValues` would also silence every other Android API
 * the service is not supposed to be touching, and hide it if it started.
 */
internal fun interface TorServiceLog {
    fun write(level: TorLogLevel, message: String, error: Throwable?)
}

internal enum class TorLogLevel { Info, Warn, Error }

/** Production sink. Never called from inside the lifecycle monitor. */
internal object AndroidTorServiceLog : TorServiceLog {
    private const val TAG = "PhantomTor"

    override fun write(level: TorLogLevel, message: String, error: Throwable?) {
        when (level) {
            TorLogLevel.Info -> Log.i(TAG, message)
            TorLogLevel.Warn -> Log.w(TAG, message)
            TorLogLevel.Error -> Log.e(TAG, message, error)
        }
    }
}
