// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import phantom.core.transport.EgressCallRegistry
import phantom.core.transport.RestEgressBlockedException
import phantom.core.transport.RestEgressGate
import java.net.HttpURLConnection
import java.net.URL

/**
 * N1-F2 R-N1.3 — the ONLY approved place for product HTTP outside the
 * transport adapters in `shared/core/transport`.
 *
 * Two product features held raw `HttpURLConnection` calls inside Compose
 * screens and therefore bypassed the fail-closed authority completely:
 * abuse reporting (which sends both parties' public keys) and link
 * preview (which fetched an attacker-chosen URL automatically on
 * render). Both now run here, and
 * `ProductHttpSinkInventoryTest` fails the build if any other
 * production file constructs an HTTP client again.
 *
 * Every request:
 *
 *  1. runs inside [RestEgressGate.dispatch], so it is refused in
 *     Private/Ghost and is registered as a revocable lease;
 *  2. registers `HttpURLConnection.disconnect()` with
 *     [EgressCallRegistry], so a privacy-mode change aborts the
 *     blocking socket read rather than merely cancelling a coroutine.
 */
class GatedAppHttp(
    private val egressGate: RestEgressGate,
    private val callRegistry: EgressCallRegistry,
) {

    sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        /** Refused by the privacy posture. No bytes left the device. */
        data object BlockedByPrivacyMode : Outcome<Nothing>()
        data class Failed(val reason: String) : Outcome<Nothing>()

        /**
         * The destination was refused by [PreviewUrlPolicy] — a loopback,
         * private, link-local or otherwise non-global address, on the
         * initial URL or on a redirect hop. No request was made to it.
         */
        data class RefusedDestination(val reason: String) : Outcome<Nothing>()
    }

    /** POST a JSON body. Returns the HTTP status code on completion. */
    suspend fun postJson(
        url: String,
        jsonBody: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
    ): Outcome<Int> = run("app_post_json") {
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            val token = callRegistry.registerOrRefuse("app_post_json") {
                runCatching { conn.disconnect() }
            }
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.doOutput = true
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                withContext(NonCancellable) {
                    callRegistry.unregister(token)
                    runCatching { conn.disconnect() }
                }
            }
        }
    }

    /**
     * GET a bounded amount of text from a destination the message
     * SENDER chose.
     *
     * Two limits apply because the target is attacker-controlled:
     *
     *  - [maxChars] caps how much of the body is read, so a hostile host
     *    cannot stream an unbounded response into memory;
     *  - [PreviewUrlPolicy] refuses any destination that is not a
     *    globally routable unicast address — and it is applied to the
     *    initial URL AND to every redirect hop. Platform redirect
     *    following is switched OFF for exactly that reason: a check
     *    applied only to the first URL is no check at all, because a
     *    public URL can 302 straight to `127.0.0.1`.
     */
    suspend fun getText(
        url: String,
        maxChars: Int,
        userAgent: String,
        connectTimeoutMs: Int = 3_000,
        readTimeoutMs: Int = 3_000,
        urlPolicy: (String) -> PreviewUrlPolicy.Verdict = { PreviewUrlPolicy.check(it) },
    ): Outcome<String> = run("app_get_text") {
        withContext(Dispatchers.IO) {
            var current = url
            var hops = 0
            var result: String? = null
            while (result == null) {
                when (val verdict = urlPolicy(current)) {
                    is PreviewUrlPolicy.Verdict.Refused ->
                        throw RefusedDestinationException(verdict.reason)
                    PreviewUrlPolicy.Verdict.Allowed -> Unit
                }
                val conn = URL(current).openConnection() as HttpURLConnection
                val token = callRegistry.registerOrRefuse("app_get_text") {
                    runCatching { conn.disconnect() }
                }
                var redirectTo: String? = null
                try {
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.setRequestProperty("User-Agent", userAgent)
                    // Never let the platform follow a redirect for us:
                    // it would skip the policy check on the new host.
                    conn.instanceFollowRedirects = false
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                            ?: throw IllegalStateException("redirect without Location")
                        if (++hops > PreviewUrlPolicy.MAX_REDIRECTS) {
                            throw IllegalStateException("too many redirects")
                        }
                        // Resolve relative Location against the current URL,
                        // then re-check it on the next iteration.
                        redirectTo = java.net.URI(current).resolve(location).toString()
                    } else if (code !in 200..299) {
                        throw IllegalStateException("non-2xx $code")
                    } else {
                        result = conn.inputStream.bufferedReader().use { reader ->
                            val sb = StringBuilder()
                            val buf = CharArray(1024)
                            var read = 0
                            while (
                                sb.length < maxChars &&
                                reader.read(buf).also { read = it } != -1
                            ) {
                                sb.appendRange(buf, 0, minOf(read, maxChars - sb.length))
                            }
                            sb.toString()
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        callRegistry.unregister(token)
                        runCatching { conn.disconnect() }
                    }
                }
                if (redirectTo != null) current = redirectTo
            }
            result
        }
    }

    /** A destination the address policy refused. Carries only the reason. */
    class RefusedDestinationException(val reason: String) :
        Exception("preview destination refused: reason=$reason")

    private suspend fun <T> run(operation: String, block: suspend () -> T): Outcome<T> = try {
        Outcome.Ok(egressGate.dispatch(operation, block))
    } catch (_: RestEgressBlockedException) {
        Outcome.BlockedByPrivacyMode
    } catch (ce: kotlinx.coroutines.CancellationException) {
        // Structured concurrency: a revoked or cancelled request must
        // propagate, never be reported to the caller as an ordinary
        // network failure.
        throw ce
    } catch (refused: RefusedDestinationException) {
        Outcome.RefusedDestination(refused.reason)
    } catch (ex: Throwable) {
        Outcome.Failed(ex::class.simpleName ?: "error")
    }
}
