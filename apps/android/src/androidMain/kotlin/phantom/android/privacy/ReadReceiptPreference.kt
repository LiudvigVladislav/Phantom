// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.android.privacy

import android.content.Context

/** A local restriction only: it never grants permission denied by PrivacyMode. */
internal object ReadReceiptPreference {
    private const val KEY = "read_receipts"

    fun enabled(context: Context): Boolean = try {
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE).getBoolean(KEY, true)
    } catch (_: ClassCastException) {
        false
    }

    fun allowed(context: Context, privacyAllows: Boolean): Boolean = privacyAllows && enabled(context)

    fun write(context: Context, enabled: Boolean): Boolean {
        val prefs = context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
        if (prefs.edit().putBoolean(KEY, enabled).commit()) return true
        // commit may update memory even if persistence fails. Never leave a failed
        // enabling operation granting permission for the remainder of this process.
        prefs.edit().putBoolean(KEY, false).commit()
        return false
    }
}
