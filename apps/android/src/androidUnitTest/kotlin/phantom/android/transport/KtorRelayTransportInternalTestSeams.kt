// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import phantom.core.transport.KtorRelayTransport
import phantom.core.transport.RelayMessage

/**
 * RC-RECONNECT-QUIESCENCE1 commit 2e fix-round-1 P1 (2026-06-22).
 *
 * `KtorRelayTransport.AckPending` / `OutboxEntry` data classes plus the
 * `seedPendingAckForTest` / `seedOutboxForTest` / `snapshotPendingAcksForTest`
 * / `snapshotOutboxForTest` / `runReconnectMergeAndFlushForTest` helpers
 * are declared `internal` in `shared:core:transport` so they do NOT
 * surface as Kotlin source-level API to a sibling Gradle module. The
 * suffix `ForTest` alone does not restrict access; the `internal`
 * keyword does.
 *
 * `HybridRelayTransportIntegrationTest20` lives in `apps:android`'s
 * `androidUnitTest` source set — a different Gradle module from
 * `shared:core:transport`. To reach the `internal` seams without
 * widening their declared visibility, this file uses
 * `kotlin.reflect.full.callSuspend` plus the standard Kotlin
 * reflection metadata layer (which transparently handles the JVM
 * name mangling that `internal` applies to function names).
 *
 * Note that reflection can in principle reach any internal member of
 * any class on the classpath — kotlin-reflect bypasses
 * source-level visibility entirely. What the `internal` keyword DOES
 * give us is the guarantee that no straight-line Kotlin call site
 * from a sibling module can compile. The helpers in this file are
 * the deliberately small "reflection bridge" the integration test
 * uses; the bridge file lives in `androidUnitTest`, which is
 * excluded from any APK.
 */

internal suspend fun KtorRelayTransport.seedAckPendingForIntegrationTest(
    message: RelayMessage.Send,
    sequenceTs: Long,
    queuedAtMs: Long,
) {
    val ackPendingKClass = Class.forName(
        "phantom.core.transport.KtorRelayTransport\$AckPending",
    ).kotlin
    val ctor = ackPendingKClass.constructors.first()
    ctor.isAccessible = true
    val ackPendingInstance = ctor.call(
        message,
        kotlin.time.TimeSource.Monotonic.markNow(),
        sequenceTs,
        queuedAtMs,
    )
    val seedFn = KtorRelayTransport::class.declaredMemberFunctions
        .first { it.name == "seedPendingAckForTest" }
    seedFn.isAccessible = true
    seedFn.callSuspend(this, ackPendingInstance)
}

internal suspend fun KtorRelayTransport.seedOutboxForIntegrationTest(
    message: RelayMessage.Send,
    sequenceTs: Long,
    queuedAtMs: Long,
) {
    val outboxEntryKClass = Class.forName(
        "phantom.core.transport.KtorRelayTransport\$OutboxEntry",
    ).kotlin
    val ctor = outboxEntryKClass.constructors.first()
    ctor.isAccessible = true
    val outboxEntryInstance = ctor.call(message, sequenceTs, queuedAtMs)
    val seedFn = KtorRelayTransport::class.declaredMemberFunctions
        .first { it.name == "seedOutboxForTest" }
    seedFn.isAccessible = true
    seedFn.callSuspend(this, outboxEntryInstance)
}

internal suspend fun KtorRelayTransport.snapshotPendingAcksCountForIntegrationTest(): Int {
    val fn = KtorRelayTransport::class.declaredMemberFunctions
        .first { it.name == "snapshotPendingAcksForTest" }
    fn.isAccessible = true
    val list = fn.callSuspend(this) as List<*>
    return list.size
}

internal suspend fun KtorRelayTransport.snapshotOutboxCountForIntegrationTest(): Int {
    val fn = KtorRelayTransport::class.declaredMemberFunctions
        .first { it.name == "snapshotOutboxForTest" }
    fn.isAccessible = true
    val list = fn.callSuspend(this) as List<*>
    return list.size
}

/**
 * Drives the real reconnect-side merge/flush sequence
 * (`mergeUnackedIntoOutboxOrdered` then `flushPendingOutbox`) so the
 * integration test exercises the same code path
 * `KtorRelayTransport.runReconnectLoop` runs once a fresh WS session
 * is up.
 */
internal suspend fun KtorRelayTransport.runReconnectMergeAndFlushForIntegrationTest(
    mySession: Long,
) {
    val fn = KtorRelayTransport::class.declaredMemberFunctions
        .first { it.name == "runReconnectMergeAndFlushForTest" }
    fn.isAccessible = true
    fn.callSuspend(this, mySession)
}

/**
 * Installs a wire-write recorder via the `sendRawAttemptForTest`
 * mutable internal property. The lambda is invoked by the production
 * `sendRaw` once per attempted WS write, BEFORE the null-session
 * guard — so the recorder fires whether or not a real session is
 * planted.
 */
internal fun KtorRelayTransport.installSendRawRecorderForIntegrationTest(
    recorder: (RelayMessage) -> Unit,
) {
    val prop = KtorRelayTransport::class.memberProperties
        .first { it.name == "sendRawAttemptForTest" }
    prop.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    (prop as kotlin.reflect.KMutableProperty1<KtorRelayTransport, ((RelayMessage) -> Unit)?>)
        .set(this, recorder)
}

/**
 * Returns `true` iff the per-instance `cleanupScope` was cancelled
 * AND `cleanupInflight` reached zero within
 * [awaitInflightTimeoutMs]. Callers MUST observe the return value
 * and fail by name on `false` so a stuck cleanup-scope cannot
 * silently leave a sweep-blocking worker behind.
 */
internal suspend fun KtorRelayTransport.closeForIntegrationTest(
    awaitInflightTimeoutMs: Long = 5_000L,
): Boolean {
    val fn = KtorRelayTransport::class.declaredMemberFunctions
        .first { it.name == "closeForTest" }
    fn.isAccessible = true
    return fn.callSuspend(this, awaitInflightTimeoutMs) as Boolean
}

internal suspend fun KtorRelayTransport.cleanupInflightCountForIntegrationTest(): Int {
    val fn = KtorRelayTransport::class.declaredMemberFunctions
        .first { it.name == "cleanupInflightForTest" }
    fn.isAccessible = true
    return fn.callSuspend(this) as Int
}

