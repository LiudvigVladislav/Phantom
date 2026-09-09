package phantom.core.storage

/** Outbound never reads this table. Only authenticated inbound can select a stored chain. */
data class ReceiveSessionArchive(
    val id: Long,
    val revision: Long,
    val stateBlob: String,
    val expiresAtMs: Long,
)

data class ReceiveSessionArchiveVersion(val id: Long, val revision: Long)

sealed interface InboundStateTarget {
    data object Active : InboundStateTarget
    /** An authenticated new bootstrap replaces, rather than advances, active. */
    data object ReplaceActive : InboundStateTarget
    /** A held bootstrap can complete without selecting its chain over the live one. */
    data object KeepActive : InboundStateTarget
    data class Archive(val id: Long, val revision: Long, val activate: Boolean = false) : InboundStateTarget
}

object ReceiveSessionArchivePolicy {
    const val RETENTION_MS = 24L * 60L * 60L * 1_000L
    // Resource bound, not an eviction rule. A full archive refuses replacement;
    // it never silently deletes an unexpired chain or acknowledges that message.
    const val MAX_PER_CONVERSATION = 256L
}

class ReceiveSessionArchiveFull : IllegalStateException("receive session archive is full")
