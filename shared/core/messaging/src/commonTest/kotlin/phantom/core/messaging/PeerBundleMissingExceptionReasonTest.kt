// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the [PeerBundleMissingException.Reason] surface added to fix the
 * DEFERRED log misdirection observed during the 2026-07-10 yellow-dot
 * recon (`project_prekey_bootstrap_contamination_2026_07_10`). Before
 * this fix, all four fetch-failure branches threw the same shape of
 * exception, and the terminal `send DEFERRED` log always printed
 * "has no bundle (404 from /prekeys/bundle)" — even when the real
 * cause was a `TimeoutCancellationException` (`elapsedMs=8000`) or a
 * `429 rate limit`. Operators grepping for "404 from /prekeys/bundle"
 * were pointed at a peer-publish-missing bug when the underlying issue
 * was a client-side timeout on an already-published bundle. This test
 * class prevents that class of misdirection from regressing.
 *
 * Contract pinned here:
 *
 *   1. Backward compatibility — a call site that still constructs
 *      `PeerBundleMissingException(hex)` without a reason gets
 *      [PeerBundleMissingException.Reason.NotPublished] and the
 *      pre-fix `Exception.message` byte-for-byte.
 *   2. Each of the four [PeerBundleMissingException.Reason] variants
 *      surfaces a distinct `toLogTag()` — the log-scraper anchor for
 *      the DEFERRED line.
 *   3. Each of the four variants surfaces a `toLogDetails()` string
 *      that carries the load-bearing numeric context (elapsedMs /
 *      budgetMs / http status) — the values operators need to
 *      distinguish "network timeout on a published bundle" from
 *      "peer never published".
 *   4. `Exception.message` for a non-`NotPublished` reason no longer
 *      hard-codes "404 from /prekeys/bundle" — it reflects the actual
 *      reason.
 */
class PeerBundleMissingExceptionReasonTest {

    private val recipient = "aa".repeat(32)

    // ── Backward compatibility (contract 1) ───────────────────────────────

    @Test
    fun ctor_without_reason_defaults_to_not_published() {
        val e = PeerBundleMissingException(recipient)
        assertEquals(
            PeerBundleMissingException.Reason.NotPublished,
            e.reason,
            "call sites that omit the reason MUST get NotPublished — this is " +
                "the load-bearing source-compat guarantee for pre-fix callers.",
        )
    }

    @Test
    fun ctor_without_reason_preserves_pre_fix_exception_message() {
        // Pre-fix message was:
        //   "peer <16hex>… has no published prekey bundle (404 from /prekeys/bundle). Message will retry on reconnect."
        // The default-NotPublished branch of buildExceptionMessage MUST
        // reproduce it byte-for-byte so any consumer that grepped
        // Exception.message on the pre-fix build continues to see the
        // same string.
        val e = PeerBundleMissingException(recipient)
        assertEquals(
            "peer ${recipient.take(16)}… has no published prekey bundle " +
                "(404 from /prekeys/bundle). Message will retry on reconnect.",
            e.message,
        )
    }

    // ── Log tag distinctness (contract 2) ────────────────────────────────

    @Test
    fun each_reason_variant_has_a_distinct_log_tag() {
        val tags = setOf(
            PeerBundleMissingException.Reason.NotPublished.toLogTag(),
            PeerBundleMissingException.Reason.Timeout(elapsedMs = 8000, budgetMs = 8000).toLogTag(),
            PeerBundleMissingException.Reason.RateLimited(elapsedMs = 500).toLogTag(),
            PeerBundleMissingException.Reason.HttpError(elapsedMs = 500, status = 503).toLogTag(),
        )
        assertEquals(
            4,
            tags.size,
            "each Reason variant must produce a distinct grep-anchor log tag " +
                "so a scraper regex `reason=<tag>` can discriminate — otherwise " +
                "the fix regresses back into the ambiguous pre-fix state.",
        )
    }

    @Test
    fun log_tags_are_stable_grep_friendly_snake_case() {
        // Snake-case pinned so a future refactor that reformats to
        // e.g. camelCase or hyphenation breaks this test loudly rather
        // than silently invalidating an operator's log-scraper regex.
        assertEquals(
            "no_published_prekeys",
            PeerBundleMissingException.Reason.NotPublished.toLogTag(),
        )
        assertEquals(
            "prekey_fetch_timeout",
            PeerBundleMissingException.Reason.Timeout(elapsedMs = 0, budgetMs = 0).toLogTag(),
        )
        assertEquals(
            "prekey_fetch_rate_limited",
            PeerBundleMissingException.Reason.RateLimited(elapsedMs = 0).toLogTag(),
        )
        assertEquals(
            "prekey_fetch_http_error",
            PeerBundleMissingException.Reason.HttpError(elapsedMs = 0, status = 0).toLogTag(),
        )
    }

    // ── Log details carry numeric context (contract 3) ────────────────────

    @Test
    fun not_published_details_report_status_404() {
        val details = PeerBundleMissingException.Reason.NotPublished.toLogDetails()
        assertEquals("status=404", details)
    }

    @Test
    fun timeout_details_report_elapsed_and_budget() {
        val reason = PeerBundleMissingException.Reason.Timeout(elapsedMs = 8003, budgetMs = 8000)
        val details = reason.toLogDetails()
        assertTrue(
            details.contains("elapsedMs=8003"),
            "timeout details must expose elapsedMs so operators can see " +
                "whether the fetch missed the budget by ms (network flake) " +
                "or by seconds (client hang). Actual: $details",
        )
        assertTrue(
            details.contains("budgetMs=8000"),
            "timeout details must expose budgetMs so operators can spot a " +
                "budget-config drift (e.g. accidentally raised to 30s and " +
                "still timing out). Actual: $details",
        )
    }

    @Test
    fun rate_limited_details_report_elapsed_and_status_429() {
        val reason = PeerBundleMissingException.Reason.RateLimited(elapsedMs = 412)
        val details = reason.toLogDetails()
        assertTrue(details.contains("elapsedMs=412"), details)
        assertTrue(
            details.contains("status=429"),
            "rate-limited details must include the literal status=429 so a log " +
                "grep for HTTP status codes captures rate-limit events too. " +
                "Actual: $details",
        )
    }

    @Test
    fun http_error_details_report_elapsed_and_actual_status() {
        val reason = PeerBundleMissingException.Reason.HttpError(elapsedMs = 250, status = 503)
        val details = reason.toLogDetails()
        assertTrue(details.contains("elapsedMs=250"), details)
        assertTrue(
            details.contains("status=503"),
            "HttpError details must carry the ACTUAL status the relay returned " +
                "so operators can spot recurring specific codes (503 upstream " +
                "outage vs 500 relay bug vs 502 gateway). Actual: $details",
        )
    }

    // ── Exception.message reflects actual reason (contract 4) ─────────────

    @Test
    fun timeout_exception_message_does_not_hard_code_404() {
        // The load-bearing regression guard: a Timeout-cause exception
        // MUST NOT include the substring "404 from /prekeys/bundle" in
        // its message. Operators grepping exception messages for that
        // literal (the pre-fix world) should NOT get a false hit for a
        // timeout case — that misdirection is exactly what the 2026-07-10
        // yellow-dot recon suffered from.
        val e = PeerBundleMissingException(
            recipient,
            PeerBundleMissingException.Reason.Timeout(elapsedMs = 8005, budgetMs = 8000),
        )
        val msg = e.message
        assertNotNull(msg)
        assertTrue(
            !msg.contains("404"),
            "Timeout-cause exception message MUST NOT contain the literal " +
                "'404' — that hard-coding was the root of the 2026-07-10 " +
                "misdirection. Actual: $msg",
        )
        assertTrue(
            msg.contains("timed out"),
            "Timeout-cause message must state the actual reason. Actual: $msg",
        )
        assertTrue(
            msg.contains("8005ms") && msg.contains("8000ms"),
            "Timeout-cause message must include the elapsed + budget " +
                "numeric context. Actual: $msg",
        )
    }

    @Test
    fun rate_limited_exception_message_reports_429_not_404() {
        val e = PeerBundleMissingException(
            recipient,
            PeerBundleMissingException.Reason.RateLimited(elapsedMs = 412),
        )
        val msg = e.message
        assertNotNull(msg)
        assertTrue(!msg.contains("404"), "RateLimited must NOT surface as 404. Actual: $msg")
        assertTrue(msg.contains("429"), "RateLimited must surface the 429 status. Actual: $msg")
    }

    @Test
    fun http_error_exception_message_reports_actual_status_not_404() {
        val e = PeerBundleMissingException(
            recipient,
            PeerBundleMissingException.Reason.HttpError(elapsedMs = 250, status = 503),
        )
        val msg = e.message
        assertNotNull(msg)
        assertTrue(!msg.contains("404"), "HttpError with 503 must NOT surface as 404. Actual: $msg")
        assertTrue(msg.contains("503"), "HttpError must surface the actual status. Actual: $msg")
    }

    // ── Exception carries the recipient hex on every variant ─────────────

    @Test
    fun recipient_pubkey_hex_preserved_across_all_reasons() {
        // The DEFERRED handler uses `e.recipientPubKeyHex.take(16)` to
        // tag the log line. Every construction path MUST preserve the
        // hex so a WAITING message can be correlated back to its peer
        // regardless of which fetch branch fired.
        listOf(
            PeerBundleMissingException.Reason.NotPublished,
            PeerBundleMissingException.Reason.Timeout(1, 2),
            PeerBundleMissingException.Reason.RateLimited(1),
            PeerBundleMissingException.Reason.HttpError(1, 500),
        ).forEach { reason ->
            val e = PeerBundleMissingException(recipient, reason)
            assertEquals(recipient, e.recipientPubKeyHex, "reason=$reason")
        }
    }
}
