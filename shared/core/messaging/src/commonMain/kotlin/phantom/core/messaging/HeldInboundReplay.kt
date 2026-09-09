package phantom.core.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import phantom.core.storage.DecryptFailedEnvelopeRepository

/** One owned consumer; commits coalesce, and storage failures get a paced retry. */
internal class HeldInboundReplay(
    private val repository: DecryptFailedEnvelopeRepository,
    private val receive: suspend (DecryptFailedEnvelopeRepository.Entry) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val beforePass: suspend () -> Unit = {},
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun request() { requests.trySend(Unit) }

    fun start(scope: CoroutineScope) = scope.launch {
        request()
        while (isActive) {
            withTimeoutOrNull(RETRY_INTERVAL_MS) { requests.receive() }
            try {
                beforePass()
                repository.deleteOlderThan(Clock.System.now().toEpochMilliseconds() -
                    DefaultMessagingService.HELD_ENVELOPE_TTL_MS)
                for (conversation in repository.countByConversation().keys) {
                    for (entry in repository.listByConversation(conversation)) receive(entry)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onFailure(failure)
            }
        }
    }

    companion object {
        const val RETRY_INTERVAL_MS = 5_000L
    }
}
