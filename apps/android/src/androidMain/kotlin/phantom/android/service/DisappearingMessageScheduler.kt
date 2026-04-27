package phantom.android.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import phantom.core.storage.MessageRepository

/**
 * Runs a coroutine loop that wakes at the next message expiry time,
 * deletes all expired messages, then sleeps until the following expiry.
 *
 * When no messages have an expiry set the loop sleeps for 60 s and rechecks.
 * This runs on [scope] — pass the application-lifetime scope so it survives
 * screen rotations and navigation.
 *
 * Decision: coroutine-based (no WorkManager) for Alpha-1 because the app
 * must be in the foreground to decrypt messages anyway. WorkManager is the
 * correct future upgrade when background delivery lands.
 */
class DisappearingMessageScheduler(
    private val messageRepo: MessageRepository,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    val nextExpiry = messageRepo.getNextExpiry()
                    if (nextExpiry == null) {
                        delay(60_000L)
                        continue
                    }
                    val delayMs = nextExpiry - System.currentTimeMillis()
                    if (delayMs > 0L) delay(delayMs)
                    messageRepo.deleteExpiredMessages()
                } catch (e: Exception) {
                    Log.w("PHANTOM", "DisappearingMessageScheduler: DB error, retrying in 60s — ${e.message}")
                    delay(60_000L)
                }
            }
        }
    }
}
