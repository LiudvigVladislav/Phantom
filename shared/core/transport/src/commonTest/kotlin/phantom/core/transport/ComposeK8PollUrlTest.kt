// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B2-K8 URL composer contract tests (design note §2.6 tests 4 + 5,
 * 2026-07-06). Pins the `composeK8PollUrl` helper against the four
 * (sinceSeq × holdOverride) branches so a future refactor cannot
 * silently drift the wire shape of `/relay/poll`.
 *
 * Every recon window relies on this contract:
 *  * With the K8 flag off (holdOverride = -1) the URL is byte-identical
 *    to pre-K8 master → design note §1.4 non-goal.
 *  * With the K8 flag on and a valid hold, `?hold=N` is appended after
 *    `?since_seq=` if the latter is present → grep-friendly log shape
 *    for the K8 verdict aggregation.
 */
class ComposeK8PollUrlTest {

    private companion object {
        const val BASE_URL = "https://relay.example/relay/poll"
    }

    @Test
    fun `sentinel and null sinceSeq returns base url verbatim`() {
        // §2.6 non-K8-active path: byte-identical to pre-K8 when neither
        // param is set. Load-bearing for the "release APK unchanged"
        // claim (release BuildConfig pins holdOverride to -1).
        assertEquals(
            BASE_URL,
            composeK8PollUrl(baseUrl = BASE_URL, sinceSeq = null, holdOverride = -1),
        )
    }

    @Test
    fun `sinceSeq only appends since_seq without hold`() {
        // §2.6 test 4: buildconfig_sentinel_omits_hold_param — the
        // legacy shape with only `?since_seq=` must remain intact when
        // the K8 flag is off but the caller has an anchor cursor.
        assertEquals(
            "$BASE_URL?since_seq=42",
            composeK8PollUrl(baseUrl = BASE_URL, sinceSeq = 42L, holdOverride = -1),
        )
    }

    @Test
    fun `hold only appends hold when sinceSeq is null`() {
        // Edge case not enumerated in §2.6 but the composer must handle
        // it: the client's very first poll after login has no
        // `sinceSeq` yet the K8 override still applies.
        assertEquals(
            "$BASE_URL?hold=10",
            composeK8PollUrl(baseUrl = BASE_URL, sinceSeq = null, holdOverride = 10),
        )
    }

    @Test
    fun `both params compose since_seq first then hold`() {
        // §2.6 test 5: prefs_present_non_sentinel_appends_hold_param.
        // Parameter order is load-bearing so grep-anchored log
        // regexes that pin `?since_seq=...` as the URL prefix keep
        // matching without a K8-specific fork.
        assertEquals(
            "$BASE_URL?since_seq=42&hold=10",
            composeK8PollUrl(baseUrl = BASE_URL, sinceSeq = 42L, holdOverride = 10),
        )
    }

    @Test
    fun `hold zero is treated as an active override`() {
        // `hold=0` is the K8 field-session recon-critical value
        // (design note §3.1 Run 2 = `hold=0` keep-alive, Run 3 =
        // `hold=0` close). Zero MUST NOT be confused with the
        // sentinel `-1`; the composer branch is `holdOverride >= 0`.
        assertEquals(
            "$BASE_URL?since_seq=1&hold=0",
            composeK8PollUrl(baseUrl = BASE_URL, sinceSeq = 1L, holdOverride = 0),
        )
        assertEquals(
            "$BASE_URL?hold=0",
            composeK8PollUrl(baseUrl = BASE_URL, sinceSeq = null, holdOverride = 0),
        )
    }

    @Test
    fun `hold beyond server clamp is forwarded verbatim without pre-clamp`() {
        // Client does NOT pre-clamp; the relay clamps [0, 30]
        // server-side (PR #370 squash c5e077db). Sending `hold=100`
        // must reach the relay verbatim so the K8 field session can
        // later discriminate "did the client or the server enforce
        // the clamp" without a separate probe.
        assertEquals(
            "$BASE_URL?hold=100",
            composeK8PollUrl(baseUrl = BASE_URL, sinceSeq = null, holdOverride = 100),
        )
    }
}
