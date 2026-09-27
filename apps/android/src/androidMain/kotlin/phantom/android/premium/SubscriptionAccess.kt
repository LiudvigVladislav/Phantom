// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.premium

import phantom.core.transport.PrivacyMode

/** No purchase or verified entitlement source exists in this build. */
object SubscriptionAccess {
    fun hasVerifiedPro(): Boolean = false

    fun permits(mode: PrivacyMode): Boolean =
        mode != PrivacyMode.Ghost || hasVerifiedPro()

    fun requiresModeChoice(mode: PrivacyMode, repairRequired: Boolean?): Boolean =
        repairRequired == false && !permits(mode)
}
