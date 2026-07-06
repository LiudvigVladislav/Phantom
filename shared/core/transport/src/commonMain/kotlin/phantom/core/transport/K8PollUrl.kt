// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * B2-K8 client-side URL composer for `/relay/poll` (design note §2.2 +
 * §5.1, 2026-07-06). Placed in `commonMain` as a pure `internal fun` so
 * `commonTest` can pin the URL contract without booting OkHttp or the
 * Android runtime — the actual `/relay/poll` requests are still built
 * only by the Android transport actual, but the composition rule is
 * platform-agnostic string manipulation and belongs at the shared
 * layer.
 *
 * Lives in its own file (rather than as a top-level fun in
 * `RelayTransportFactory.kt`) so the JVM compilation does not clash
 * with the `jvmMain/RelayTransportFactory.kt` actuals — two files
 * with the same name in commonMain and jvmMain both produce a
 * `RelayTransportFactoryKt` class facade unless the file names differ.
 *
 * ### Contract
 *
 *  * `sinceSeq == null && holdOverride < 0` → `baseUrl` verbatim,
 *    byte-identical to pre-K8. This is the default flag-off wire shape
 *    on every existing call site.
 *  * `sinceSeq != null && holdOverride < 0` → `"$baseUrl?since_seq=$s"`.
 *    Matches the pre-K8 legacy shape exactly.
 *  * `holdOverride >= 0` → appends `hold=$h` after `since_seq` (both
 *    params joined by `&`, always `?since_seq=...` first when present
 *    so log-grepper regexes anchored to that prefix keep matching).
 *  * `sinceSeq == null && holdOverride >= 0` → `"$baseUrl?hold=$h"`.
 *
 * The caller sends the raw `holdOverride` integer without any clamp —
 * the relay clamps `[0, 30]` server-side (PR #370 squash `c5e077db`).
 * Sending `hold=100` reaches the relay verbatim so the "did the client
 * or the server enforce the clamp" discrimination is testable in the
 * field.
 *
 * The `-1` sentinel (rather than any negative integer) is the canonical
 * "no override" value throughout the K8 codebase — BuildConfig defaults
 * `"-1"`, shared-prefs defaults `-1`, the resolver returns `-1` when
 * both are unset. Any negative value here still short-circuits to the
 * no-`?hold` branch because "absent" is the semantic; the tests pin
 * the exact contract.
 */
internal fun composeK8PollUrl(baseUrl: String, sinceSeq: Long?, holdOverride: Int): String {
    val hasSince = sinceSeq != null
    val hasHold = holdOverride >= 0
    return when {
        hasSince && hasHold -> "$baseUrl?since_seq=$sinceSeq&hold=$holdOverride"
        hasSince -> "$baseUrl?since_seq=$sinceSeq"
        hasHold -> "$baseUrl?hold=$holdOverride"
        else -> baseUrl
    }
}
