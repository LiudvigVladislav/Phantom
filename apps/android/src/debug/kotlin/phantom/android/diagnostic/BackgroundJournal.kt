package phantom.android.diagnostic

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Bounded asynchronous diagnostic I/O. A process kill can lose the not-yet-written queue. */
internal class BackgroundJournal(
    private val directory: File,
    private val wall: () -> Long,
    private val elapsed: () -> Long,
    private val pid: Int,
    private val maxBytes: Long = 1_048_576,
    capacity: Int = 512,
    private val beforeWrite: () -> Unit = {},
) : AutoCloseable {
    private val dropped = AtomicLong()
    private val suppressed = AtomicLong()
    private val sequence = AtomicLong()
    private val seen = LinkedHashMap<String, Boolean>()
    @Volatile var failed = false
        private set
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(capacity), { task -> Thread(task, "background-journal").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    @Synchronized
    fun record(fields: Map<String, String>) {
        if (failed || writer.isShutdown) return
        // Only repeated envelope events are suppressed. A screen/network cycle must remain visible.
        val key = fields["envelope"]?.let { fields.toSortedMap().toString() }
        if (key != null && seen.containsKey(key)) { suppressed.incrementAndGet(); return }
        val record = JSONObject(fields).apply {
            put("wall_ms", wall()); put("elapsed_ms", elapsed()); put("pid", pid)
            put("sequence", sequence.incrementAndGet())
            put("dropped", dropped.get()); put("suppressed", suppressed.get())
        }.toString() + "\n"
        try {
            writer.execute {
                if (!failed) try {
                    beforeWrite()
                    append(JSONObject(record).put("dropped", dropped.get())
                        .put("suppressed", suppressed.get()).toString() + "\n")
                }
                catch (_: Exception) { failed = true; dropped.incrementAndGet() }
            }
            if (key != null) {
                seen[key] = true
                if (seen.size > 4096) seen.remove(seen.keys.first())
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun append(line: String) {
        check(directory.isDirectory || directory.mkdirs())
        val active = File(directory, "events.jsonl")
        val bytes = line.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes)
        if (active.length() + bytes.size > maxBytes) {
            val oldest = File(directory, "events.jsonl.3")
            check(!oldest.exists() || oldest.delete())
            for (i in 2 downTo 0) {
                val from = File(directory, if (i == 0) "events.jsonl" else "events.jsonl.$i")
                val to = File(directory, "events.jsonl.${i + 1}")
                check(!from.exists() || from.renameTo(to))
            }
        }
        FileOutputStream(active, true).use { output -> output.write(bytes); output.fd.sync() }
    }

    override fun close() { writer.shutdown() }
    fun awaitClosed(timeoutMs: Long): Boolean = writer.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    fun droppedCount(): Long = dropped.get()
}
