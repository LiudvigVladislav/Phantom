package phantom.android.diagnostic

import kotlin.test.*
import org.junit.Test

class BackgroundEventFilterTest {
    @Test fun archive_diagnostics_keep_only_the_target_label_and_envelope() {
        val projected = BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE inbound_commit msgId=a4a20d0a outcome=Committed target=keep_active state=PRIVATE")!!
        assertEquals("keep_active", projected["target"])
        assertFalse(projected.toString().contains("PRIVATE"))
        assertEquals("archived_receive_committed", BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE archived_receive_committed msgId=a4a20d0a")!!["event"])
        assertEquals("ReceiveSessionArchiveFull", BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE inbound_commit_failed errorClass=ReceiveSessionArchiveFull")!!["errorClass"])
    }
    @Test fun pending_expiry_is_not_conflated_with_suspect_or_missing_artifacts() {
        val fields = BackgroundEventFilter.project("messaging",
            "SEND_TRACE pending_reuse_decision pendingPresent=true artifactsValid=true withinTtl=false " +
                "recipientMatches=true suspect=false transactionAvailable=true reusable=false key=PRIVATE")!!
        assertEquals("false", fields["withinTtl"])
        assertEquals("false", fields["suspect"])
        assertEquals("true", fields["artifactsValid"])
        assertEquals("true", fields["recipientMatches"])
        assertEquals("false", fields["reusable"])
        assertFalse(fields.toString().contains("PRIVATE"))
    }
    @Test fun repair_stage_and_known_cause_are_preserved_without_exception_messages() {
        val fields = BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE repair_candidate_failed msgId=de283094 stage=bootstrap " +
                "errorClass=NullPointerException causeClass=OpkNotFound message=PRIVATE")!!
        assertEquals("bootstrap", fields["stage"])
        assertEquals("NullPointerException", fields["errorClass"])
        assertEquals("OpkNotFound", fields["causeClass"])
        assertFalse(fields.toString().contains("PRIVATE"))
        val unknown = BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE repair_candidate_failed stage=PRIVATE errorClass=PRIVATE causeClass=PRIVATE")!!
        assertEquals("other", unknown["stage"])
        assertEquals("other", unknown["causeClass"])
        assertFalse(unknown.toString().contains("PRIVATE"))
    }
    @Test fun transport_selection_is_observed_without_recording_endpoints() {
        assertEquals(mapOf("source" to "manager", "event" to "prepare_start", "kind" to "Reality"),
            BackgroundEventFilter.project("manager", "PROBE_TRACE prepare_start kind=Reality url=PRIVATE"))
        assertEquals("DIRECT_FIRST", BackgroundEventFilter.project("manager",
            "PROBE_TRACE chain_start strategy=DIRECT_FIRST ordered=[Direct, Reality, Tor]")!!["strategy"])
    }
    @Test fun first_repair_failure_keeps_only_the_reason_and_envelope() {
        assertEquals(mapOf("source" to "messaging", "event" to "inbound_repair_fail",
            "errorClass" to "OpkNotFound", "action" to "fall_through_to_hold", "envelope" to "a4a20d0a"),
            BackgroundEventFilter.project("messaging", "DECRYPT_TRACE inbound_repair_fail msgId=a4a20d0a " +
                "sender=PRIVATE conv=PRIVATE errorClass=OpkNotFound action=fall_through_to_hold key=PRIVATE"))
    }
    @Test fun arbitrary_values_and_raw_payloads_are_not_recorded() {
        val projection = BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE fail_mac msgId=PRIVATE errorClass=PRIVATE reason=PRIVATE plaintext=PRIVATE url=PRIVATE")!!
        assertFalse(projection.toString().contains("PRIVATE"))
        assertNull(BackgroundEventFilter.project("messaging", "message text: PRIVATE"))
        assertNull(BackgroundEventFilter.project("wss", "event=PRIVATE correlation_id=a4a20d0a"))
    }
    @Test fun idle_cause_and_http_acceptance_remain_distinct() {
        assertEquals("InboundSilence", BackgroundEventFilter.project("rest",
            "REST_TRACE presentation mode=WsCandidate cause=InboundSilence")!!["cause"])
        assertEquals("201", BackgroundEventFilter.project("rest",
            "REST_TRACE send_response id=a4a20d0a status=201")!!["http_status"])
    }
    @Test fun receive_ack_and_hold_are_separate_events() {
        for (event in listOf("recipient_deliver_received", "recipient_deliver_failed", "recipient_ack_deliver_sent")) {
            assertEquals(event, BackgroundEventFilter.project("wss", "event=$event correlation_id=a4a20d0a")!!["event"])
        }
        assertEquals("mac", BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE hold_recorded msgId=a4a20d0a errorType=mac")!!["errorType"])
    }
    @Test fun close_reason_text_never_reaches_the_projection() {
        assertEquals(mapOf("source" to "relay", "event" to "ws_closed_remote", "reason" to "other"),
            BackgroundEventFilter.project("relay", "[gen=1 s=1] WebSocket closed by remote reason=PRIVATE"))
    }
    @Test fun prekey_read_failure_is_distinct_without_exporting_key_or_exception_text() {
        val fields = BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE repair_candidate_failed msgId=0574e3f3 stage=bootstrap " +
                "errorClass=PreKeyReadFailed causeClass=InvalidKeyException message=PRIVATE key=PRIVATE")!!
        assertEquals("PreKeyReadFailed", fields["errorClass"])
        assertEquals("InvalidKeyException", fields["causeClass"])
        assertFalse(fields.toString().contains("PRIVATE"))
        assertEquals("prekey", BackgroundEventFilter.project("messaging",
            "DECRYPT_TRACE hold_recorded msgId=0574e3f3 errorType=prekey")!!["errorType"])
    }
}
