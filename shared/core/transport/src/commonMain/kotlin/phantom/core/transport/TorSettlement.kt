// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC
package phantom.core.transport

/** Which fact a generation is still missing before a successor is admissible. */
public enum class TorSettlementGap {
    /** The generation asked about is not the one this owner speaks for. */
    OtherGeneration,

    /** That generation exists but has not reached its settled phase. */
    PhaseNotSettled,

    /** It settled without positive evidence about the daemon (pending or unknown). */
    OutcomeInadmissible,

    /** The daemon is provably gone but its host never confirmed its resources are. */
    HostNotReleased,
}

/**
 * Stage 2 B7c (2026-09-13): the authoritative answer to "may a walk that
 * left generation N unsettled try again".
 *
 * Read under the lifecycle owner's own monitor, about ONE generation.
 * [TorService.status]-style notifications cannot answer it: they carry
 * whichever generation is live and say nothing about whether the host of
 * the generation in question let go. Only [Settled] lifts the obligation
 * the walk registered; every other value names the missing fact so the
 * log distinguishes an unconfirmed daemon from a host still holding its
 * threads.
 */
public sealed interface TorSettlement {
    /** This generation settled with an admissible outcome AND released its host. */
    public data object Settled : TorSettlement

    /** Not settled; [gap] is the fact that is missing. */
    public data class NotSettled(val gap: TorSettlementGap) : TorSettlement
}
