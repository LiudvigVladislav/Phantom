// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GroupAudioReportAccumulatorTest {

    @Test
    fun every_recipient_is_classified_exactly_once() {
        val reports = GroupAudioReportAccumulator(listOf("alice", "bob", "carol", "alice"))
        reports.recordFailure("bob", 1, OutboundSubmissionException("socket refused"))
        reports.recordFailure("carol", 2, OutboundNotAttemptedException("encrypt failed"))

        val report = reports.build()
        assertEquals(3, report.recipientCount)
        assertEquals(GroupRecipientOutcome.SUBMITTED, report.recipientOutcomes["alice"])
        assertEquals(GroupRecipientOutcome.FAILED_TO_SUBMIT, report.recipientOutcomes["bob"])
        assertEquals(GroupRecipientOutcome.NOT_ATTEMPTED, report.recipientOutcomes["carol"])
        assertEquals(1, report.submittedCount)
        assertEquals(1, report.failedToSubmitCount)
        assertEquals(1, report.notAttemptedCount)
        assertEquals(2, report.attempted)
        assertEquals(1, report.submitted)
        assertEquals(1, report.failedToSubmit)
        assertEquals(1, report.notAttempted)
        assertEquals(report.chunkFailures, report.failures)
    }

    @Test
    fun a_failed_recipient_is_short_circuited_without_stopping_the_fanout() {
        val reports = GroupAudioReportAccumulator(listOf("alice", "bob", "carol"))
        reports.recordFailure("bob", 0, OutboundSubmissionException("not submitted"))

        assertFalse("bob" in reports.activeRecipientPublicKeys())
        assertTrue("alice" in reports.activeRecipientPublicKeys())
        assertTrue("carol" in reports.activeRecipientPublicKeys())
    }

    @Test
    fun encryption_failure_is_not_attempted_and_chunk_detail_is_retained() {
        val reports = GroupAudioReportAccumulator(listOf("alice"))
        reports.recordFailure("alice", 4, OutboundNotAttemptedException("barrier refused"))

        val report = reports.build()
        assertEquals(GroupRecipientOutcome.NOT_ATTEMPTED, report.recipientOutcomes.getValue("alice"))
        assertEquals(4, report.chunkFailures.single().chunkIndex)
        assertEquals(GroupRecipientOutcome.NOT_ATTEMPTED, report.chunkFailures.single().outcome)
    }

    @Test
    fun a_recipient_cannot_be_classified_twice() {
        val reports = GroupAudioReportAccumulator(listOf("alice"))
        reports.recordFailure("alice", 0, OutboundSubmissionException("queued"))

        assertFailsWith<IllegalStateException> {
            reports.recordFailure("alice", 1, OutboundNotAttemptedException("must not overwrite"))
        }
        assertEquals(1, reports.build().chunkFailures.size)
    }

    @Test
    fun fanout_short_circuits_only_the_failed_recipient_and_continues_others() = runTest {
        val calls = mutableMapOf<String, Int>()
        val report = collectGroupAudioReport(
            recipientPublicKeys = listOf("alice", "bob", "carol"),
            chunkCount = 3,
        ) { chunkIndex, active ->
            active.associateWith { recipient ->
                calls[recipient] = calls.getOrDefault(recipient, 0) + 1
                when {
                    recipient == "bob" && chunkIndex == 0 ->
                        Result.failure(OutboundSubmissionException("transport returned false"))
                    recipient == "carol" && chunkIndex == 1 ->
                        Result.failure(OutboundNotAttemptedException("encrypt failed"))
                    else -> Result.success(Unit)
                }
            }
        }

        assertEquals(mapOf("alice" to 3, "bob" to 1, "carol" to 2), calls)
        assertEquals(2, report.attempted)
        assertEquals(1, report.submitted)
        assertEquals(1, report.failedToSubmit)
        assertEquals(1, report.notAttempted)
        assertEquals(listOf(0, 1), report.failures.map { it.chunkIndex })
    }

    @Test
    fun fanout_rejects_a_chunk_result_that_omits_an_active_recipient() = runTest {
        assertFailsWith<IllegalStateException> {
            collectGroupAudioReport(listOf("alice", "bob"), chunkCount = 1) { _, _ ->
                mapOf("alice" to Result.success(Unit))
            }
        }
    }
}
