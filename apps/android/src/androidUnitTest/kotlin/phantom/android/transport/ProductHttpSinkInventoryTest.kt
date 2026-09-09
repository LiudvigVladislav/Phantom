// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * N1-F2 R-N1.3 P1-1 — the coverage inventory, as a test rather than a
 * claim.
 *
 * R-N1.2 wired the fail-closed authority into the transport
 * abstractions it knew about and asserted, in prose, that Direct REST
 * was fully covered. It was not: an identity-bearing auth challenge, an
 * abuse report carrying both parties' public keys, and an automatic
 * link-preview fetch to a URL chosen by the message sender all opened
 * their own HTTP and never saw the gate. Prose could not catch that,
 * and no behavioural test could either, because nothing named the paths
 * that were missing.
 *
 * Two complementary checks run here.
 *
 *  1. **Construction inventory** (whole repo, production source sets):
 *     every file that CONSTRUCTS an HTTP client or connection must be
 *     on an explicit allowlist with a written reason.
 *  2. **Import boundary** (`apps/android` production code): no product
 *     file outside the approved gateway may even import an HTTP client
 *     type. This catches the case check 1 cannot — a file that
 *     originates HTTP through an INJECTED client it did not construct.
 *
 * Both scans strip comments and string literals first, so writing the
 * word `HttpURLConnection` in a kdoc block does not trip them and,
 * more importantly, cannot be used to hide a real sink.
 *
 * ## What this test does NOT prove
 *
 * It is syntactic. It proves no NEW sink appears unnoticed; it does not
 * prove the allowlisted sinks are correctly gated — that is what the
 * behavioural fail-closed and revocation tests are for. `src/debug/`
 * and `diagnostic` packages are operator tooling rather than ordinary
 * product egress; they are reported separately rather than silently
 * ignored.
 */
class ProductHttpSinkInventoryTest {

    private val repoRoot: File = run {
        var candidate = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(candidate, "shared/core/transport/src/commonMain").exists() &&
                File(candidate, "apps/android/src/androidMain").exists()
            ) {
                return@run candidate
            }
            candidate = candidate.parentFile ?: candidate
        }
        fail(
            "could not locate the repository root from ${System.getProperty("user.dir")}; " +
                "this test must not pass vacuously",
        )
    }

    // ── Source normalisation ──────────────────────────────────────────

    /**
     * Remove line comments, block comments and string literals, so the
     * scans see code only. Without this, a kdoc that MENTIONS a sink
     * trips the check, and — the direction that actually matters — a
     * real sink could be excused as "just a comment".
     */
    private fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = minOf(i + 2, src.length)
                }
                src.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) i++
                    i = minOf(i + 3, src.length)
                }
                c == '"' -> {
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Constructions that originate an HTTP request. The negative
     * lookbehind keeps `createRestHttpClient()` from matching
     * `HttpClient(` — a factory CALL is not a construction site.
     */
    private val sinkPatterns = listOf(
        Regex("""(?<![A-Za-z0-9_])HttpURLConnection"""),
        Regex("""\.openConnection\s*\("""),
        Regex("""(?<![A-Za-z0-9_])OkHttpClient\s*(\.Builder)?\s*\("""),
        Regex("""(?<![A-Za-z0-9_])HttpClient\s*\("""),
        Regex("""\.newCall\s*\("""),
    )

    /** Importing an HTTP client type is enough to originate a request. */
    private val httpImportPatterns = listOf(
        Regex("""^import\s+okhttp3\.""", RegexOption.MULTILINE),
        Regex("""^import\s+io\.ktor\.client\.""", RegexOption.MULTILINE),
        Regex("""^import\s+java\.net\.HttpURLConnection""", RegexOption.MULTILINE),
        Regex("""^import\s+java\.net\.URLConnection""", RegexOption.MULTILINE),
    )

    private val allowedProductionSinks = mapOf(
        "shared/core/transport/src/androidMain/kotlin/phantom/core/transport/RelayTransportFactory.kt"
            to "Android transport factory; its pre-key publish registers its Call for revocation",
        "shared/core/transport/src/jvmMain/kotlin/phantom/core/transport/RelayTransportFactory.kt"
            to "JVM stub factory (throws)",
        "shared/core/transport/src/iosMain/kotlin/phantom/core/transport/RelayTransportFactory.kt"
            to "iOS stub factory (throws)",
        "shared/core/transport/src/androidMain/kotlin/phantom/core/transport/AndroidNativeOkHttpRestFallbackTransport.kt"
            to "gated REST transport; registers its Call for revocation",
        "shared/core/transport/src/androidMain/kotlin/phantom/core/transport/AndroidNativeOkHttpMediaUploadTransport.kt"
            to "gated media transport; registers its Calls for revocation",
        "shared/core/transport/src/androidMain/kotlin/phantom/core/transport/AndroidNativeOkHttpDirectProbe.kt"
            to "connectivity probe, not user traffic",
        "shared/core/transport/src/androidMain/kotlin/phantom/core/transport/KtorTransportProbe.kt"
            to "connectivity probe, not user traffic",
        "apps/android/src/androidMain/kotlin/phantom/android/net/GatedAppHttp.kt"
            to "the single approved product HTTP gateway; every request runs inside RestEgressGate.dispatch",
    )

    /**
     * Files in `apps/android` production code permitted to import an
     * HTTP client type.
     */
    private val allowedAppHttpImports = mapOf(
        "apps/android/src/androidMain/kotlin/phantom/android/net/GatedAppHttp.kt"
            to "the single approved product HTTP gateway; every request runs inside " +
            "RestEgressGate.dispatch and registers a cancellation handle",
        "apps/android/src/androidMain/kotlin/phantom/android/net/RelayChallengeClient.kt"
            to "auth-challenge request; constructs no client, uses the injected Ktor REST " +
            "client, and its only call site is wrapped in " +
            "egressGate.dispatch(\"auth_challenge\") by RestFallbackOrchestrator",
    )

    private fun relative(f: File): String =
        f.absolutePath.removePrefix(repoRoot.absolutePath).replace('\\', '/').trimStart('/')

    private fun productionKotlinFiles(vararg roots: String): List<File> =
        roots.map { File(repoRoot, it) }
            .filter { it.exists() }
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { "${File.separatorChar}build${File.separatorChar}" !in it.path }
            .filterNot { f ->
                val rel = relative(f)
                rel.contains("/commonTest/") || rel.contains("/jvmTest/") ||
                    rel.contains("/androidUnitTest/") || rel.contains("/src/debug/")
            }

    private fun allProduction(): List<File> =
        productionKotlinFiles("shared", "apps/android/src/androidMain")

    private fun sinkFiles(): List<String> = allProduction()
        .filter { f ->
            val code = codeOnly(f.readText())
            sinkPatterns.any { it.containsMatchIn(code) }
        }
        .map { relative(it) }
        .sorted()

    // ── Non-vacuity ───────────────────────────────────────────────────

    @Test
    fun the_scan_is_not_vacuous() {
        val all = allProduction()
        assertTrue(
            all.size > 100,
            "expected a substantial production source tree, found ${all.size} files under " +
                "${repoRoot.absolutePath} — a scan that finds nothing must not pass",
        )
        assertTrue(
            sinkFiles().size >= 5,
            "the sink patterns matched ${sinkFiles().size} files; the known gated transports " +
                "alone should match more than that, so the patterns are broken",
        )
    }

    @Test
    fun comment_and_string_stripping_works() {
        val disguised = """
            // val c = java.net.HttpURLConnection.something
            /* OkHttpClient() */
            val s = "openConnection("
            val real = OkHttpClient()
        """.trimIndent()
        val code = codeOnly(disguised)
        assertTrue("HttpURLConnection" !in code, "line comments must be stripped")
        assertTrue(code.count { it == 'O' } > 0, "code must survive stripping")
        assertTrue(
            sinkPatterns.any { it.containsMatchIn(code) },
            "a real construction outside comments must still be detected",
        )
        assertTrue(
            !sinkPatterns.any { it.containsMatchIn(codeOnly("// OkHttpClient()")) },
            "a construction that exists only inside a comment must not be detected",
        )
        assertTrue(
            !sinkPatterns.any { it.containsMatchIn(codeOnly("val x = createRestHttpClient()")) },
            "a factory CALL is not a construction site",
        )
    }

    // ── The inventory ─────────────────────────────────────────────────

    @Test
    fun every_production_http_sink_is_on_the_allowlist() {
        val unexpected = mutableListOf<String>()
        for (rel in sinkFiles()) {
            if (rel in allowedProductionSinks) continue
            if ("/diagnostic" in rel || "/diagnostics" in rel) continue
            unexpected += rel
        }
        assertTrue(
            unexpected.isEmpty(),
            "New product HTTP sink(s) outside the approved adapters:\n" +
                unexpected.joinToString("\n") { "  $it" } +
                "\n\nEvery product HTTP request must go through RestEgressGate — route it via " +
                "phantom.android.net.GatedAppHttp or an existing gated transport, or add the " +
                "file to allowedProductionSinks with a written reason.",
        )
    }

    @Test
    fun the_allowlist_has_no_stale_entries() {
        val actual = sinkFiles().toSet()
        val stale = allowedProductionSinks.keys.filterNot { it in actual }
        assertTrue(
            stale.isEmpty(),
            "allowlist entries that no longer contain an HTTP sink (remove them so the list " +
                "keeps meaning something):\n" + stale.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun app_module_imports_no_http_client_outside_the_gateway() {
        val offenders = productionKotlinFiles("apps/android/src/androidMain")
            .filter { f ->
                val code = codeOnly(f.readText())
                httpImportPatterns.any { it.containsMatchIn(code) }
            }
            .map { relative(it) }
            .filterNot { it in allowedAppHttpImports.keys }
            .filterNot { "/diagnostic" in it || "/diagnostics" in it }
            .sorted()
        assertTrue(
            offenders.isEmpty(),
            "Product code in apps/android must not import an HTTP client type — this is how a " +
                "file originates HTTP through an injected client without constructing one:\n" +
                offenders.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun the_app_http_import_allowlist_has_no_stale_entries() {
        val actual = productionKotlinFiles("apps/android/src/androidMain")
            .filter { f ->
                val code = codeOnly(f.readText())
                httpImportPatterns.any { it.containsMatchIn(code) }
            }
            .map { relative(it) }
            .toSet()
        val stale = allowedAppHttpImports.keys.filterNot { it in actual }
        assertTrue(
            stale.isEmpty(),
            "import-allowlist entries that no longer import an HTTP client type:" +
                stale.joinToString(prefix = " ", separator = " "),
        )
    }

    @Test
    fun compose_screens_originate_no_http() {
        val offenders = allProduction()
            .filter { "/screens/" in relative(it) }
            .filter { f ->
                val code = codeOnly(f.readText())
                sinkPatterns.any { it.containsMatchIn(code) } ||
                    httpImportPatterns.any { it.containsMatchIn(code) }
            }
            .map { relative(it) }
        assertTrue(
            offenders.isEmpty(),
            "UI screens must never originate HTTP — this is exactly how the abuse-report and " +
                "link-preview bypasses reached production:\n" +
                offenders.joinToString("\n") { "  $it" },
        )
    }

    /**
     * N1-F2 R-N1.4 P1-2 — supplementary source tripwire for the
     * privacy-switch WIRING, not for its behaviour.
     *
     * The transaction itself is now covered behaviourally by
     * `PrivacyModeTeardownTransactionTest`, which cancels the caller
     * mid-transition and asserts disconnect and release still run. What
     * a unit test still cannot reach is `AppContainer` itself:
     * constructing one is not practical. So this checks the two things
     * that live only in the caller —
     *
     *  1. the teardown goes through `runPrivacyModeTeardown` rather than
     *     inline steps that could drift apart again;
     *  2. `ensureActive()` appears AFTER that call, never before it.
     *     R-N1.3's defect was precisely an `ensureActive()` sitting
     *     between the REST revocation and the socket teardown, so a
     *     cancelled caller left the old Direct WSS connection alive
     *     while Settings already showed Private/Ghost.
     *
     * This watches source text. It is mutation-tested, but it is
     * recorded in SECURITY-INVARIANTS.md as a limitation, not as
     * coverage.
     */
    @Test
    fun set_privacy_mode_tears_down_before_re_asserting_cancellation() {
        val container = File(
            repoRoot,
            "apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt",
        )
        assertTrue(container.exists(), "AppContainer.kt not found at ${container.path}")
        val code = codeOnly(container.readText())
        assertTrue(
            "suspend fun setPrivacyMode" in code,
            "setPrivacyMode not found — the anchor moved",
        )
        val body = code.substringAfter("suspend fun setPrivacyMode").substringBefore("suspend fun ")

        val teardownAt = body.indexOf("runPrivacyModeTeardown")
        assertTrue(
            teardownAt >= 0,
            "setPrivacyMode must tear down through runPrivacyModeTeardown, so the revocation, " +
                "the disconnect and the release stay one non-cancellable transaction",
        )
        val ensureAt = body.indexOf("ensureActive")
        assertTrue(
            ensureAt >= 0,
            "the caller's own cancellation must be re-asserted after the teardown, not absorbed",
        )
        assertTrue(
            ensureAt > teardownAt,
            "ensureActive() appears BEFORE the teardown. Cancelling the caller there leaves the " +
                "old Direct connection up while the persisted mode already says Private/Ghost — " +
                "the R-N1.3 defect.",
        )
        assertTrue(
            "lastPrivacyModeTeardown" in body,
            "the teardown outcome must be retained, not discarded",
        )
        for (step in listOf("transport.disconnectAndJoin(", "transportManager.release()")) {
            val at = body.indexOf(step)
            assertTrue(at >= 0, "$step must still happen during a privacy switch")
            assertTrue(
                at in teardownAt..ensureAt,
                "$step must sit inside the runPrivacyModeTeardown call, not outside the " +
                    "non-cancellable transaction",
            )
        }
        // R-N1.5: disconnect() flushes pendingOutbox/pendingAcks over the
        // STILL-LIVE Direct socket for up to 3s before closing it. During
        // a privacy switch that is user payload leaving over the posture
        // the user just abandoned. Only the non-flushing variant is
        // acceptable here.
        assertFalse(
            "transport.disconnect()" in body,
            "setPrivacyMode must not call disconnect(): it flushes the pending outbox over " +
                "the old Direct transport before closing. Use disconnectAndJoin, which does " +
                "not flush and reports whether the teardown actually joined.",
        )
    }

    @Test
    fun link_preview_is_never_fetched_automatically() {
        val chat = File(
            repoRoot,
            "apps/android/src/androidMain/kotlin/phantom/android/screens/chat/ChatScreen.kt",
        )
        assertTrue(chat.exists(), "ChatScreen.kt not found at ${chat.path}")
        val code = codeOnly(chat.readText())
        // Bound the region by two anchors that survive comment stripping.
        assertTrue("val urlInMsg" in code, "link-preview region start anchor moved")
        assertTrue(
            "LinkPreviewState.Loaded)?.preview" in code,
            "link-preview region end anchor moved",
        )
        val region = code
            .substringAfter("val urlInMsg")
            .substringBefore("LinkPreviewState.Loaded)?.preview")
        assertTrue(region.isNotEmpty(), "link-preview region not found — the anchor moved")
        assertTrue(
            "clickable" in region,
            "the link-preview load must be behind an explicit user tap",
        )
        assertTrue(
            "LaunchedEffect" !in region,
            "link preview must not be fetched from a LaunchedEffect: rendering a message would " +
                "again disclose the reader's IP to a host the message sender chose",
        )
        assertTrue(
            "fetchLinkPreviewHtml" in code,
            "the link-preview load must go through AppContainer.fetchLinkPreviewHtml, which is gated",
        )
    }
}
