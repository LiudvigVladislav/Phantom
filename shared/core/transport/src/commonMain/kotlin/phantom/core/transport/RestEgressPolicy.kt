// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

/**
 * N1-F2 (2026-08-29) — the single fail-closed authority for REST egress.
 *
 * ## Why this exists
 *
 * The REST bootstrap and fallback clients historically egressed over the
 * raw Direct network regardless of the user's privacy posture: the
 * `socksProxyPort` parameter on [RestFallbackOrchestrator] was declared
 * and never wired ("Stage 2A/2B" deferral), so in Private and Ghost
 * modes `/auth/challenge`, `/auth/session`, poll, send and ack could all
 * leave with the user's real IP bound to their long-term identity.
 *
 * ## Owner-approved invariant — "No Silent Downgrade"
 *
 * Private and Ghost MUST NEVER silently downgrade REST traffic to the
 * Direct network. Until an anonymous REST egress binding exists, every
 * REST operation in those modes fails CLOSED before any HTTP request is
 * created or dispatched. Failed user sends stay durably queued; nothing
 * is discarded. The long-term target architecture is full REST feature
 * parity over the same bound anonymous egress/SOCKS transport that WSS
 * uses — this policy is the boundary that future work plugs into, not a
 * removal of REST from the product.
 *
 * ## Authority
 *
 * Decisions derive from the PRODUCTION privacy posture
 * ([TransportPreferences.privacyMode]) read live at every call. The
 * diagnostic latched outer-arm value (DiagnosticTransportGuard) is NOT
 * a security authority and MUST NOT be consulted here: it reports the
 * last chain-walk result, not the user's chosen posture.
 *
 * ## Future extension
 *
 * A future `AnonymousBound(socksPort, generation)` decision can be added
 * to [RestEgressDecision] without rewriting REST semantics: call sites
 * only branch on "is this [RestEgressDecision.DirectAllowed]" today, so
 * a new allowed-with-binding variant extends the sealed hierarchy and
 * the dispatch code consumes the binding where it builds the client.
 * Implementing that binding (Tor/Reality SOCKS lifecycle for REST) is
 * explicitly out of scope for N1-F2.
 */
sealed class RestEgressDecision {
    /**
     * Standard mode: Direct egress is the user's chosen posture. All
     * existing Direct REST behaviour is unchanged.
     */
    data object DirectAllowed : RestEgressDecision()

    /**
     * Private mode (and any unreadable-state fallback): REST is only
     * permitted over an anonymous egress, and no such binding exists
     * yet. Every REST operation fails closed before dispatch.
     */
    data object AnonymousRequiredButUnavailable : RestEgressDecision()

    /**
     * Ghost mode: no user messaging REST traffic is allowed at all,
     * and unconditional REST bootstrap is forbidden. There is NO
     * generic "system traffic" escape hatch; a future control-plane
     * allowlist is a separate architecture decision.
     */
    data object GhostUserTrafficBlocked : RestEgressDecision()
}

/**
 * The policy interface consulted at every REST choke point. `decide()`
 * is called per operation (not once at cold start) so a runtime mode
 * switch takes effect on the very next request or poll iteration, and a
 * stale session or client created under Standard cannot be used after
 * entering Private/Ghost.
 */
fun interface RestEgressPolicy {
    fun decide(): RestEgressDecision
}

/**
 * Production policy: maps the live privacy mode to a decision.
 *
 * Fail-closed contract: if [privacyModeProvider] throws or returns
 * `null` (state cannot be read), the decision is
 * [RestEgressDecision.AnonymousRequiredButUnavailable] — never
 * [RestEgressDecision.DirectAllowed].
 */
class PrivacyModeRestEgressPolicy(
    private val privacyModeProvider: () -> PrivacyMode?,
) : RestEgressPolicy {
    override fun decide(): RestEgressDecision {
        val mode = try {
            privacyModeProvider()
        } catch (_: Throwable) {
            null
        }
        return when (mode) {
            PrivacyMode.Standard -> RestEgressDecision.DirectAllowed
            PrivacyMode.Private -> RestEgressDecision.AnonymousRequiredButUnavailable
            PrivacyMode.Ghost -> RestEgressDecision.GhostUserTrafficBlocked
            null -> RestEgressDecision.AnonymousRequiredButUnavailable
        }
    }
}
