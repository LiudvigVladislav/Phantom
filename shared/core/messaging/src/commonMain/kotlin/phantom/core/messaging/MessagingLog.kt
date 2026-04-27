package phantom.core.messaging

/**
 * Platform-agnostic logger for the messaging pipeline.
 *
 * Android actual routes to android.util.Log with tag "PhantomMessaging"
 * so logcat filters like `adb logcat PhantomMessaging:V *:S` capture
 * every step of the incoming-envelope pipeline (decrypt, parse, store,
 * notify UI).
 *
 * JVM actual writes to stdout for tests.
 */
internal enum class MessagingLogLevel { INFO, WARN, ERROR }

internal expect fun messagingLog(
    level: MessagingLogLevel,
    message: String,
    throwable: Throwable? = null,
)
