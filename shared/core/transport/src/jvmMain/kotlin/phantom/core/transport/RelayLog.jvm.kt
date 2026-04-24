package phantom.core.transport

internal actual fun relayLog(
    level: RelayLogLevel,
    message: String,
    throwable: Throwable?,
) {
    println("PhantomRelay [${level.name}]: $message")
    throwable?.printStackTrace()
}
