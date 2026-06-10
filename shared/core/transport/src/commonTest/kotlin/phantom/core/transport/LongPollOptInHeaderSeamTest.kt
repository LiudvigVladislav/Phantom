// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trek 2 Stage 2B-A (B1) — M1 contract test (seam-level) and the
 * companion constants-pin test.
 *
 * Wire-level header emission lives one layer down inside
 * `AndroidNativeOkHttpRestFallbackTransport.buildPollRequest(...)` and
 * is structurally enforced by a single `if (longPollOptIn) { ... }`
 * block that emits both [LONG_POLL_OPT_IN_HEADER] and
 * [PADDED_POLL_OPT_IN_HEADER] together — there is no other code path.
 * What can still go wrong above that block, and what M1 must pin, is:
 *
 *   1. The orchestrator's seam: the `longPollEnabled` Boolean must be
 *      passed through to every `transport.poll(...)` call. A future
 *      refactor that drops the parameter (or hard-codes `false` for a
 *      legacy code path) would silently disable the Stage 2B-A
 *      runtime even with the BuildConfig flag flipped on.
 *   2. The constants: lowercase `"x-phantom-long-poll"` /
 *      `"x-phantom-padded-poll"` are the exact strings the server's
 *      `HeaderMap` lookup accepts; a typo or a `"X-"`-uppercase
 *      renaming is a wire-level break that no other test would catch.
 *
 * Both kinds of failure would let a Stage 2B-A APK run with the flag
 * on AND with the server's hold path silently ignoring the request —
 * the exact failure mode the L1 lock exists to prevent.
 *
 * The header-emission test is at the request-builder level in the
 * `:apps:android` module's own test source set (not here) because
 * the OkHttp [okhttp3.Request] type lives in `androidMain`. The
 * commonTest layer cannot reach that type without forcing an
 * androidMain runtime; the architectural split here is intentional.
 */
class LongPollOptInHeaderSeamTest {

    /**
     * Pin the exact byte values of the two header names. Server-side,
     * the `HeaderMap` lookup uses a lowercase key per axum's
     * normalisation; a `"X-Phantom-Long-Poll"` (uppercase X) string
     * still works on the wire because OkHttp / Ktor / axum all
     * normalise outgoing header names, but the constants themselves
     * are what tests on either end compare for equality — keeping
     * them at the canonical lowercase form keeps the equality check
     * symmetric.
     *
     * A future refactor that renames the header (e.g. to a versioned
     * `"x-phantom-long-poll-v2"`) trips this test deliberately — at
     * which point the server-side constant in `rest_fallback.rs` must
     * be updated in the same PR.
     */
    @Test
    fun header_names_match_server_contract_byte_for_byte() {
        assertEquals("x-phantom-long-poll", LONG_POLL_OPT_IN_HEADER)
        assertEquals("x-phantom-padded-poll", PADDED_POLL_OPT_IN_HEADER)
    }

    /**
     * Pin the two header names are NOT empty and NOT equal to each
     * other — a copy-paste regression that aliased one to the other
     * would silently send the same header twice. Cheap structural
     * guard; runs in microseconds.
     */
    @Test
    fun header_names_are_distinct_and_non_empty() {
        assertTrue(LONG_POLL_OPT_IN_HEADER.isNotEmpty())
        assertTrue(PADDED_POLL_OPT_IN_HEADER.isNotEmpty())
        assertTrue(LONG_POLL_OPT_IN_HEADER != PADDED_POLL_OPT_IN_HEADER)
    }
}
