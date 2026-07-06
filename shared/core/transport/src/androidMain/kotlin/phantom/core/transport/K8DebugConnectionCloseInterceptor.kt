// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.Response

/**
 * B2-K8 diagnostic interceptor (design note §2.4 + §6.1, 2026-07-06).
 *
 * Injects a `Connection: close` request header on every intercepted
 * request AND evicts the passed [ConnectionPool] after every response
 * is returned to the caller, forcing a full TCP+TLS teardown between
 * consecutive polls.
 *
 * Companion to relay-side PR #370 (`RELAY_DIAG_WS_K8_CLIENT_HOLD_OVERRIDE_ENABLED`
 * env var, squash `c5e077db`). The interceptor is attached to the
 * `/relay/poll` OkHttp client ONLY when the K8
 * [k8ConnectionCloseProvider] returns `true`; in release builds the
 * provider path is dead (`BuildConfig.DEBUG_K8_CONNECTION_CLOSE = "0"`
 * hardpinned + Settings-Diagnostics UI absent from the release
 * compilation unit), so no interceptor is ever installed.
 *
 * ### R8 strip discipline
 *
 * The class name prefix `K8Debug*` is on the
 * [phantom.android.build.gradle.kts:verifyR8StripsTestSeams] deny-list
 * so the release-APK verification task fails if a K8-diagnostic class
 * survives R8. Combined with the provider-returns-false gate (which
 * makes the interceptor path dead code in release), R8 dead-code
 * elimination removes the interceptor class entirely from the release
 * compilation unit.
 *
 * ### Scope
 *
 * Narrow to `op == "poll"` per [AndroidNativeOkHttpRestFallbackTransport.buildClient].
 * Send / ack / auth OkHttp clients are unaffected regardless of the
 * K8 provider's return — a K8 field session only affects the poll
 * shape and does not risk collateral impact on the message-send path.
 *
 * ### Interaction with existing `Connection: close` header
 *
 * `AndroidNativeOkHttpRestFallbackTransport.buildPollRequest()`
 * already sets `Connection: close` on `/relay/poll` requests via the
 * OkHttp request builder. This interceptor is belt-and-braces —
 * `Request.Builder.header(...)` overwrites (does not append) so
 * duplication is harmless. The distinguishing behaviour is the
 * post-response [ConnectionPool.evictAll] call, which guarantees the
 * next poll opens a fresh TCP+TLS connection even if the pool
 * configuration drifts from its intended `ConnectionPool(0, 1ms)`
 * shape.
 *
 * ### Read cadence
 *
 * The interceptor is constructed once per OkHttp client build (per
 * poll iteration), and each intercepted request runs the header
 * injection + eviction unconditionally. The K8
 * [k8ConnectionCloseProvider] gate at construction time is the sole
 * on/off switch — no per-request evaluation.
 */
internal class K8DebugConnectionCloseInterceptor(
    private val pool: ConnectionPool,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Connection", "close")
            .build()
        val response = chain.proceed(request)
        // Evict AFTER response is returned to the caller. The eviction
        // only affects idle sockets in the pool — the active response
        // body stream stays valid because OkHttp does not put an
        // in-flight connection back into the pool until the body is
        // fully consumed (or the response is closed by the caller).
        pool.evictAll()
        return response
    }
}
