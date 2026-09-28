// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.saved

internal fun savedScrollTarget(ids: List<String>, pendingId: String?, initial: Boolean): Int? =
    if (pendingId != null) ids.indexOf(pendingId).takeIf { it >= 0 }
    else ids.lastIndex.takeIf { initial && it >= 0 }
