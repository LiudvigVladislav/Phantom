// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.messaging

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sprint 2b-C — `opk_not_found_total{reason}` debuggability seam (scope-doc L8).
 *
 * Minimal in-memory counter wired into the recipient-side
 * [SessionManager.recipientBootstrapInMemory] failure paths so a
 * post-merge log dump can correlate `OpkNotFound` rates against the
 * Sprint 2b-C pending-fallback + inbound-repair flows.
 *
 * **Scope.** This is a counter ONLY. Sprint 2b-C does NOT wire export
 * to logcat / Statsig / Grafana / any external telemetry sink. The
 * counter exists so:
 *  - the deferred sender-signal work (named in ADR-029 §Alternative C)
 *    can consume the count when its three entry criteria are met;
 *  - tests and operators inspecting an in-memory dump can observe the
 *    rate without standing up telemetry infrastructure;
 *  - a future PR that DOES introduce telemetry can flip a single
 *    on-write callback here without touching the call sites.
 *
 * Thread-safety: backed by [kotlinx.coroutines.sync.Mutex]. The
 * record / get / snapshot methods are suspend so callers run from a
 * coroutine context (every Sprint 2b-C call site already does).
 *
 * Process-local — no persistence. A process restart resets every
 * bucket. This is intentional; persistence belongs to the telemetry
 * layer that will consume the counter later.
 */
class OpkNotFoundMetric {

    private val mutex = Mutex()
    private val counters = mutableMapOf<Reason, Long>()

    /**
     * Reason discriminator for the `opk_not_found_total{reason=...}`
     * metric. Add new variants ONLY when the corresponding production
     * call site exists — drift between the enum and the call sites is
     * what telemetry mismatches look like at this scale.
     */
    enum class Reason {
        /**
         * `recipientBootstrapInMemory` looked up
         * `oneTimePreKeyRepository.get(opkKeyIdHex)` and got null.
         * The exact field-smoke shape the 2026-06-15 integration LTE
         * smoke captured (closed by Sprint 2b-A L1 publish-snapshot
         * consistency on the publish side; the recipient side keeps
         * the counter for residual-rate observation).
         */
        INBOUND_REPAIR_OPK_NOT_FOUND,
    }

    suspend fun recordOpkNotFound(reason: Reason) {
        mutex.withLock {
            counters[reason] = (counters[reason] ?: 0L) + 1L
        }
    }

    suspend fun get(reason: Reason): Long = mutex.withLock { counters[reason] ?: 0L }

    suspend fun snapshot(): Map<Reason, Long> = mutex.withLock { counters.toMap() }
}
