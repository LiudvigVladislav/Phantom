// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.core.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CLIENT-PREKEY-SELFHEAL classifier + external-cancellation acceptance
 * tests (docs/tracks/client-prekey-selfheal.md §7 rows T12, T12b, T12c,
 * T-F11c).
 *
 * Layer: `shared/core/transport/src/jvmTest/` — exercises the JVM/Android
 * actual [classifyNetworkFailure] via real `javax.net.ssl.*` /
 * `java.net.*` constructors, and the external-cancellation semantics
 * for the fetchStatus retry loop (must NOT convert a parent-initiated
 * CancellationException into a synthesized
 * [FetchStatusDeadlineExceededException]).
 */
class ClassifyNetworkFailureJvmTest {

    // ═══════════════════════════════════════════════════════════════════════
    // T12 — SSLPeerUnverifiedException in the cause chain classifies as
    // TerminalTls.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun classifyNetworkFailure_SSLPeerUnverifiedInCauseChain_returnsTerminalTls() {
        val cause = SSLPeerUnverifiedException("cert unverified")
        val t = SSLException("wrapper", cause)

        val decision = classifyNetworkFailure(t)

        assertEquals(
            RetryDecision.TerminalTls, decision,
            "SSLPeerUnverifiedException in cause chain must classify as TerminalTls",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T12b — bare SSLException without any transient signal classifies as
    // TerminalTls.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun classifyNetworkFailure_bareSSLExceptionNoTransientSignal_returnsTerminalTls() {
        val t = SSLException("unknown SSL error")

        val decision = classifyNetworkFailure(t)

        assertEquals(
            RetryDecision.TerminalTls, decision,
            "bare SSLException with no transient signal in message/cause must classify as TerminalTls",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T12c — SSLException wrapping a SocketTimeoutException classifies as
    // RetryableTransient (transient transport signal in cause chain).
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun classifyNetworkFailure_SSLExceptionWithSocketTimeoutCause_returnsRetryableTransient() {
        val t = SSLException("io error", SocketTimeoutException("read timed out"))

        val decision = classifyNetworkFailure(t)

        assertEquals(
            RetryDecision.RetryableTransient, decision,
            "SSLException wrapping SocketTimeoutException must classify as RetryableTransient",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T12d (round-1 review, TLS priority) — terminal TLS ALWAYS wins over a
    // transient class deeper in the same chain. Binding doc D5 mandates
    // two-pass cause-chain recognition: first the whole chain is scanned
    // for TLS-terminal classes, then only if none are found the chain is
    // scanned for transient classes. Without this ordering an SSLException
    // whose real cause is a cert failure but which was itself wrapped over
    // a SocketTimeoutException could yield RetryableTransient (from the
    // deeper socket-timeout) — masking the terminal cert failure.
    //
    // Failure mode this regression catches: if the classifier switches
    // back to a single-pass walk that returns the first match, it will
    // return RetryableTransient here (from cause[1] = SocketTimeout) and
    // the terminal SSLPeerUnverifiedException at cause[0] will be masked.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun classifyNetworkFailure_mixedChainTerminalTlsBeforeTransient_returnsTerminalTls() {
        val deepestTransient = SocketTimeoutException("read timed out")
        val terminalTls = SSLPeerUnverifiedException("cert unverified")
        // Cause chain:
        //   [0] outer SSLException
        //   [1] SSLPeerUnverifiedException  ← terminal TLS
        //   [2] SocketTimeoutException      ← would classify transient if scanned first
        (terminalTls as Throwable).initCause(deepestTransient)
        val t = SSLException("outer", terminalTls)

        val decision = classifyNetworkFailure(t)

        assertEquals(
            RetryDecision.TerminalTls, decision,
            "mixed chain (TLS terminal + deeper transient) MUST classify as " +
                "TerminalTls — single-pass first-match walk would incorrectly " +
                "return RetryableTransient (regression guard for two-pass ordering)",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // T-F11c — external cancellation propagates as CancellationException;
    // MUST NOT be converted to FetchStatusDeadlineExceededException.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun fetchStatus_externalCancellation_propagatesNotConvertedToTransient() = runBlocking {
        // Handler hangs forever — the only way fetchStatus can complete is
        // via internal deadline OR external cancellation. We test the
        // latter: cancelling the launching coroutine while fetchStatus is
        // in its first `httpClient.get()` must surface a CancellationException
        // (not FetchStatusDeadlineExceededException).
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    suspendCancellableCoroutine<HttpResponseData> {
                        // never resume — waits for cancellation
                    }
                }
            }
        }
        val api = PreKeyApiClient(
            httpClient = client,
            relayBaseUrl = "https://relay.test",
        )
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var thrown: Throwable? = null
        val job = scope.launch {
            try {
                api.fetchStatus("aa".repeat(32))
            } catch (t: Throwable) {
                thrown = t
            }
        }
        // Give the launched coroutine time to reach the suspendCancellableCoroutine
        // inside the MockEngine handler before we cancel.
        delay(300)
        job.cancelAndJoin()

        assertNotNull(thrown, "expected an exception in the cancelled coroutine")
        assertTrue(
            thrown is CancellationException,
            "external cancellation must propagate as CancellationException; got ${thrown!!::class.simpleName}",
        )
        assertFalse(
            thrown is FetchStatusDeadlineExceededException,
            "external cancellation MUST NOT be converted to FetchStatusDeadlineExceededException",
        )
    }
}
