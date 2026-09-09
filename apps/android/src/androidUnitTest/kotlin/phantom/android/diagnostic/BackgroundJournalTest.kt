package phantom.android.diagnostic

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackgroundJournalTest {
    private fun files(directory: File) = directory.listFiles()!!.filter { it.name.startsWith("events.jsonl") }
    private fun journal(directory: File, beforeWrite: () -> Unit = {}) =
        BackgroundJournal(directory, { 1000L }, { 2000L }, 7, beforeWrite = beforeWrite)

    @Test fun committed_record_survives_a_new_writer_without_a_logcat_reader() {
        val dir = Files.createTempDirectory("background-journal").toFile()
        try {
            val first = journal(dir)
            first.record(mapOf("event" to "fail_mac", "envelope" to "a4a20d0a"))
            first.close(); assertTrue(first.awaitClosed(5000))
            val second = journal(dir)
            second.record(mapOf("event" to "process_start"))
            second.close(); assertTrue(second.awaitClosed(5000))
            val rows = File(dir, "events.jsonl").readLines().map(::JSONObject)
            assertEquals(listOf("fail_mac", "process_start"), rows.map { it.getString("event") })
            assertEquals(2000L, rows.first().getLong("elapsed_ms"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun disk_failure_does_not_escape_to_the_caller() {
        val file = Files.createTempFile("not-directory", ".tmp").toFile()
        val writer = journal(file)
        try {
            writer.record(mapOf("event" to "fail_mac"))
            writer.close(); assertTrue(writer.awaitClosed(5000))
            assertTrue(writer.failed)
            assertEquals(1L, writer.droppedCount())
        } finally { writer.close(); assertTrue(writer.awaitClosed(5000)); file.delete() }
    }

    @Test fun blocked_disk_does_not_block_producers_and_overflow_is_visible() {
        val dir = Files.createTempDirectory("background-queue").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = BackgroundJournal(dir, { 1L }, { 2L }, 7, capacity = 1, beforeWrite = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test disk gate timed out" }
        })
        try {
            writer.record(mapOf("event" to "first"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            writer.record(mapOf("event" to "second"))
            writer.record(mapOf("event" to "third"))
            assertEquals(1L, writer.droppedCount())
            release.countDown()
            writer.close(); assertTrue(writer.awaitClosed(5000))
            assertEquals(2, File(dir, "events.jsonl").readLines().size)
            assertEquals(1L, JSONObject(File(dir, "events.jsonl").readLines().last()).getLong("dropped"))
        } finally {
            release.countDown(); writer.close()
            assertTrue(writer.awaitClosed(5000)); dir.deleteRecursively()
        }
    }

    @Test fun rotation_is_bounded_and_a_new_record_is_kept() {
        val dir = Files.createTempDirectory("background-rotation").toFile()
        val writer = BackgroundJournal(dir, { 1L }, { 2L }, 7, maxBytes = 256)
        try {
            repeat(20) { writer.record(mapOf("event" to "network_changed", "number" to it.toString())) }
            writer.close(); assertTrue(writer.awaitClosed(5000))
            assertEquals(4, files(dir).size)
            assertTrue(files(dir).all { it.length() <= 256 })
            assertEquals("19", JSONObject(File(dir, "events.jsonl").readLines().last()).getString("number"))
        } finally { writer.close(); assertTrue(writer.awaitClosed(5000)); dir.deleteRecursively() }
    }

    @Test fun identical_envelope_events_are_suppressed_but_network_cycles_are_not() {
        val dir = Files.createTempDirectory("background-dedup").toFile()
        val writer = journal(dir)
        try {
            repeat(10) { writer.record(mapOf("event" to "held", "envelope" to "a4a20d0a")) }
            writer.record(mapOf("event" to "network_available"))
            writer.record(mapOf("event" to "network_lost"))
            writer.record(mapOf("event" to "network_available"))
            writer.close(); assertTrue(writer.awaitClosed(5000))
            val lines = File(dir, "events.jsonl").readLines()
            assertEquals(4, lines.size)
            assertEquals(9L, JSONObject(lines.last()).getLong("suppressed"))
        } finally { writer.close(); assertTrue(writer.awaitClosed(5000)); dir.deleteRecursively() }
    }

    @Test fun malformed_expired_and_unbounded_arming_are_refused() {
        assertNull(BackgroundRecorder.remainingArmMs("secret", 1000))
        assertNull(BackgroundRecorder.remainingArmMs("999", 1000))
        assertNull(BackgroundRecorder.remainingArmMs(Long.MAX_VALUE.toString(), 1000))
        assertEquals(5000L, BackgroundRecorder.remainingArmMs("6000\n", 1000))
    }

    @Test fun idle_unarmed_start_does_not_install_a_sink() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        File(ctx.noBackupFilesDir, "background-diagnostic/armed-until").delete()
        BackgroundTrace.sink = null
        BackgroundRecorder.start(ctx)
        assertNull(BackgroundTrace.sink)
    }

    @Test fun diagnostic_sink_failures_cannot_escape_the_log_bridges() {
        try {
            BackgroundTrace.sink = { _, _ -> error("sink failure") }
            phantom.core.messaging.MessagingDiagnosticObserver.sink = { error("sink failure") }
            phantom.core.transport.RelayDiagnosticObserver.sink = { error("sink failure") }
            BackgroundTrace.observe("rest", "test")
            phantom.core.messaging.MessagingDiagnosticObserver.observe("test")
            phantom.core.transport.RelayDiagnosticObserver.observe("test")
        } finally {
            BackgroundTrace.sink = null
            phantom.core.messaging.MessagingDiagnosticObserver.sink = null
            phantom.core.transport.RelayDiagnosticObserver.sink = null
        }
    }
}
