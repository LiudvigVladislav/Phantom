package phantom.android.diagnostic

/** Strict projection: raw log lines, payloads, addresses and peer/key identifiers never leave this parser. */
internal object BackgroundEventFilter {
    private val messagingEvents = setOf("attempt", "inbound_repair_armed", "inbound_repair_ok",
        "inbound_repair_fail", "pending_fallback_fail", "pending_fallback_ok", "inbound_commit",
        "inbound_commit_failed", "inbound_commit_promoted", "inbound_commit_not_taken",
        "fail_mac", "fail_other", "hold_recorded", "hold_storage_error", "suspect_write_failed",
        "receive_failed", "send_start", "bootstrap_path", "pending_reuse", "relay_send_return",
        "pending_reuse_decision", "repair_candidate_failed", "archived_receive_committed")
    private val wssEvents = setOf("sender_send_attempt_started", "sender_transport_decision",
        "sender_rest_post_completed", "sender_wss_frame_written", "sender_relay_ack_received",
        "recipient_deliver_received", "recipient_deliver_failed", "recipient_message_persisted",
        "recipient_ack_deliver_sent")
    private val restEvents = setOf("mode_switched", "presentation", "poll_started", "poll_stopped",
        "poll_error", "poll_failed", "ws_active_poll_failed", "send_response", "send_fail")
    private val managerEvents = setOf("chain_start", "prepare_start", "prepare_done", "prepare_fail",
        "probe_called", "probe_returned", "chain_attempt_success", "chain_attempt_failed", "chain_all_failed",
        "reality_filtered")
    private val enumValues = setOf("true", "false", "mac", "commit", "prekey", "hold", "hold_storage_error",
        "ack", "rethrow", "fall_through_to_hold", "fail_mac_existing_session", "mac_fail_under_pending",
        "inbound_idle_timeout", "ws_frame_text_received", "ws_alive_60s", "ws_active", "network_changed",
        "ws_session_ended", "active_outbound_ack_timeout", "responder_role_redirected",
        "outbound_pending_not_reusable", "candidate_commit_failed", "reservation_released_between_phases",
        "Committed", "PendingMissing", "ReservationMissing", "PendingBindingUnknown",
        "PendingBindingMismatch", "ArchiveUnavailable", "ReceiveSessionArchiveFull", "unsupported",
        "active_advance", "active_replace", "keep_active", "archive_activate", "archive_advance",
        "RestActive", "WsActive", "WsCandidate", "REST_ACTIVE", "WS_ACTIVE",
        "WS_CANDIDATE", "InboundSilence", "FailureOrUnknown", "accepted", "duplicate", "failed",
        "disabled_by_capability", "unknown", "fresh", "reack", "held", "threw", "unknown_processing_failure",
        "received", "decrypted", "persisted", "ledger_marked", "ack_sent", "direct", "reality", "tor", "wss", "rest",
        "OpkNotFound", "SpkNotFound", "OpkReservationConflict", "InvalidSignature", "IllegalArgumentException",
        "IllegalStateException", "IOException", "SocketTimeoutException", "UnknownHostException",
        "SQLiteException", "SQLiteFullException", "SQLiteDiskIOException", "SQLiteConstraintException",
        "InboundCommitFailed", "SerializationException", "Unknown", "Direct", "Reality", "Tor",
        "DIRECT_FIRST", "REALITY_FIRST", "TOR_FIRST", "vpn_active", "probe_failed",
        "bootstrap", "decrypt", "None", "InvalidSpkSignature", "MalformedBundle",
        "PreKeyReadFailed", "InvalidKeyException", "KeyStoreException", "UserNotAuthenticatedException",
        "KeyPermanentlyInvalidatedException", "AEADBadTagException", "BadPaddingException", "IllegalBlockSizeException",
        "NullPointerException", "NoSuchElementException", "IndexOutOfBoundsException",
        "ArrayIndexOutOfBoundsException", "UnsupportedOperationException", "ClassCastException",
        "NumberFormatException", "RuntimeException", "Exception", "Error",
        "NoClassDefFoundError", "UnsatisfiedLinkError", "StackOverflowError", "OutOfMemoryError")
    private val enumKeys = setOf("errorClass", "errorType", "outcome", "reason", "action", "promotion",
        "bootstrap", "sessionExists", "x3dhInitPresent", "ok", "mode", "from", "to", "cause",
        "inner_route", "outer_transport", "relay_acceptance", "deliver_failure", "deliver_stage", "dedup_gate",
        "kind", "strategy", "exception", "stage", "causeClass", "pendingPresent", "artifactsValid",
        "withinTtl", "recipientMatches", "suspect", "transactionAvailable", "reusable", "target")
    private val tokens = Regex("(?:^|\\s)([A-Za-z_][A-Za-z0-9_]*)=([^\\s]+)")
    private val id = Regex("(?:[a-fA-F0-9]{8}|[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})")

    fun project(source: String, line: String): Map<String, String>? {
        if (line.length > 8192) return null
        val fields = tokens.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        val event = when (source) {
            "messaging" -> line.split(' ', limit = 3).takeIf {
                it.size >= 2 && it[0] in setOf("DECRYPT_TRACE", "SEND_TRACE")
            }?.get(1)?.takeIf { it in messagingEvents }
            "wss" -> fields["event"]?.takeIf { it in wssEvents }
            "rest" -> line.split(' ', limit = 3).takeIf {
                it.size >= 2 && it[0] == "REST_TRACE"
            }?.get(1)?.takeIf { it in restEvents }
            "manager" -> line.split(' ', limit = 3).takeIf {
                it.size >= 2 && it[0] == "PROBE_TRACE"
            }?.get(1)?.takeIf { it in managerEvents }
            "relay" -> when {
                "WebSocket connected successfully" in line -> "ws_connected"
                "WebSocket connect FAILED" in line -> "ws_failed"
                "WebSocket closed by remote" in line -> "ws_closed_remote"
                "readLoop exited" in line -> "ws_read_ended"
                "idle_watchdog" in line -> "ws_idle_watchdog"
                else -> null
            }
            else -> null
        } ?: return null
        val result = linkedMapOf("source" to source, "event" to event)
        for (key in enumKeys) fields[key]?.let { result[key] = if (it in enumValues) it else "other" }
        val envelope = fields["correlation_id"] ?: fields["msgId"] ?: fields["id"]
        if (envelope != null && id.matches(envelope)) result["envelope"] = envelope.lowercase()
        fields["status"]?.takeIf { it.matches(Regex("[1-5][0-9]{2}")) }?.let { result["http_status"] = it }
        return result
    }
}
