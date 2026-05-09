// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import android.content.SharedPreferences

/**
 * SharedPreferences-backed implementation of [TransportPreferences].
 *
 * Lives in the existing `phantom_prefs` SharedPreferences (same file the
 * onboarding identity / username already use) under keys prefixed with
 * `transport.*`. Migration is non-destructive: missing keys read as defaults
 * so existing installations transparently pick up [PrivacyMode.Standard]
 * and null hints on first launch after the upgrade (ADR-020).
 */
class TransportPreferencesAndroid(
    private val prefs: SharedPreferences,
) : TransportPreferences {

    override var privacyMode: PrivacyMode
        get() = prefs.getString(KEY_PRIVACY_MODE, null)
            ?.let { runCatching { PrivacyMode.valueOf(it) }.getOrNull() }
            ?: PrivacyMode.Standard
        set(value) {
            prefs.edit().putString(KEY_PRIVACY_MODE, value.name).apply()
        }

    override var lastWorkingTransport: TransportKind?
        get() = prefs.getString(KEY_LAST_WORKING, null)
            ?.let { runCatching { TransportKind.valueOf(it) }.getOrNull() }
        set(value) {
            prefs.edit().run {
                if (value == null) remove(KEY_LAST_WORKING) else putString(KEY_LAST_WORKING, value.name)
                apply()
            }
        }

    override var lastSuccessAt: Long?
        get() = if (prefs.contains(KEY_LAST_SUCCESS_AT)) prefs.getLong(KEY_LAST_SUCCESS_AT, 0L) else null
        set(value) {
            prefs.edit().run {
                if (value == null) remove(KEY_LAST_SUCCESS_AT) else putLong(KEY_LAST_SUCCESS_AT, value)
                apply()
            }
        }

    override var transportFailureCount: Int
        get() = prefs.getInt(KEY_FAILURE_COUNT, 0)
        set(value) {
            prefs.edit().putInt(KEY_FAILURE_COUNT, value).apply()
        }

    private companion object {
        const val KEY_PRIVACY_MODE     = "transport.privacy_mode"
        const val KEY_LAST_WORKING     = "transport.last_working"
        const val KEY_LAST_SUCCESS_AT  = "transport.last_success_at_ms"
        const val KEY_FAILURE_COUNT    = "transport.failure_count"
    }
}
