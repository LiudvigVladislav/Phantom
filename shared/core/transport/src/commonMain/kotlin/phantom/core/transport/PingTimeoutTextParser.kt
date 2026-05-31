// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * Pure parser for OkHttp's WS ping-timeout exception text. Extracts the
 * `N` from `"sent ping but didn't receive pong within 15000ms (after N
 * successful ping/pongs)"` so the client-side diagnostic log can publish
 * the OkHttp-internal counter as a structured field rather than burying
 * it inside a Throwable's message string.
 *
 * PR-WS-HEALTH-STATE1 Commit 3.3 (2026-05-31). Design note locked in
 * `docs/tracks/ws-health-state.md` § Commit 3.3 design note rev2.
 *
 * Why this is necessary: per findings A/B/C of the design note audit,
 * the only non-lying ping/pong signal currently available client-side
 * is the integer OkHttp embeds in the SocketTimeoutException message at
 * close time. Every other counter on the client side
 * (`SessionStats.pingsSent`, `SessionStats.pongsReceived`,
 * `lastPongMark` consumed by the idle_watchdog log) is structurally
 * dead — declared and consumed by an app-level RelayMessage.Ping/Pong
 * loop whose sender was removed in PR-H1e. Without this parser the
 * field-test analyst has to grep through the exception text by hand;
 * with it, `ws_ping_timeout_diag okhttp_successful_ping_pongs=<n>` is a
 * single grep token.
 *
 * Returns `-1` on no match so the caller can distinguish "OkHttp didn't
 * use this idiom" from "OkHttp used the idiom with count = 0". `-1`
 * sorts low if anyone aggregates these numerically; the field-test
 * analysis section in the design note explicitly relies on that.
 *
 * Pattern: case-insensitive `(after <N> successful ping/pongs)` with N
 * being one or more digits. The wording is OkHttp's own — see
 * `okhttp3.internal.ws.RealWebSocket.writePingFrame$okhttp` — and has
 * been stable across OkHttp 4.x releases.
 */
internal object PingTimeoutTextParser {

    private val regex = Regex(
        """\(after (\d+) successful ping/pongs\)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Extract the `N` from OkHttp's `after N successful ping/pongs`
     * phrasing inside [text]. Returns the parsed integer on a match,
     * or `-1` if [text] is null or the pattern does not match.
     *
     * Safe against `null`, empty input, partial text, OkHttp-version
     * drift that adds extra punctuation around the parenthesised
     * phrase, and absurdly large numbers (only the first match is
     * consumed; on overflow the result is clamped to [Int.MAX_VALUE]
     * via `Long.toInt()` semantics — fine for diagnostics since
     * a session that survived `Int.MAX_VALUE` ping/pongs has not
     * been observed in any field run).
     */
    fun parseSuccessfulPingPongs(text: String?): Int {
        if (text.isNullOrEmpty()) return -1
        val match = regex.find(text) ?: return -1
        val raw = match.groupValues.getOrNull(1) ?: return -1
        return raw.toLongOrNull()?.toInt() ?: -1
    }
}
