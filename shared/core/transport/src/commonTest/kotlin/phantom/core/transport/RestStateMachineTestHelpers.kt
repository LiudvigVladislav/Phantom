// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import kotlinx.coroutines.runBlocking

/**
 * RC-RECONNECT-QUIESCENCE1 (2026-06-22) commonTest helper.
 *
 * [RestStateMachine.onEvent] became `suspend` so the gate-mutating
 * event handlers can acquire the gateLock atomically. Most existing
 * tests are written as plain non-suspend `@Test fun` bodies. To avoid
 * a mass rewrite of those tests, this helper wraps each call in a
 * `runBlocking`. Use only in tests that are NOT already inside a
 * coroutine context (most pre-2026-06-22 tests fall in this bucket).
 *
 * Tests that already use `runTest` / `runBlocking` should call
 * `sm.onEvent(...)` directly.
 */
internal fun RestStateMachine.onEventNow(event: RestStateMachine.Event) {
    runBlocking { onEvent(event) }
}

/**
 * Same shape for [RestFallbackOrchestrator.submitEvent], which also
 * became `suspend` as the gate machinery propagated up.
 */
internal fun RestFallbackOrchestrator.submitEventNow(event: RestStateMachine.Event) {
    runBlocking { submitEvent(event) }
}
