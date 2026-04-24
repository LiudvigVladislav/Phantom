package phantom.core.messaging

internal actual fun messagingLog(
    level: MessagingLogLevel,
    message: String,
    throwable: Throwable?,
) {
    println("PhantomMessaging [${level.name}]: $message")
    throwable?.printStackTrace()
}
