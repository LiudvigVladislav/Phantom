package phantom.android.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T> awaitReadyValue(ready: Deferred<Unit>, value: () -> T): T {
    ready.await()
    currentCoroutineContext().ensureActive()
    return value()
}

/** Failure is not an absent identity. The caller terminates this start, without provisioning. */
internal suspend fun <T> serviceStartupOrNull(
    onFailure: (Throwable) -> Unit,
    start: suspend () -> T,
): T? = try {
    start()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    onFailure(failure)
    null
}
