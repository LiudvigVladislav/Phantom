// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.diagnostic

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WSS-3 audit ROUND-22 canonical-shape test.
 *
 * Per architect ROUND-22 P0-2:
 *
 *   > DiagnosticNetworkProfileReporterCanonicalShapeTest MUST read
 *   > canonical_network_profile.phone.json and
 *   > canonical_network_profile.emu.json as test resources.
 *   >
 *   > Forbidden:
 *   >   - keeping CANONICAL_*_KEYS as a second hard-coded authority;
 *   >   - comparing Kotlin source literals through Python text searches;
 *   >   - committing a second independent copy of a fixture;
 *   >   - using a filesystem symlink that is not portable to Windows.
 *   >
 *   > Prefer registering the existing fixtures/ directory as a
 *   > Gradle test-resource source. A generated copy is allowed only in
 *   > build/ with declared task inputs and outputs.
 *   >
 *   > The test must verify:
 *   >   - the phone key set and all types;
 *   >   - the emu key set and all types;
 *   >   - the emu.egress_fingerprint key set and all types;
 *   >   - pinned constants against values read from the fixture.
 *
 * This test:
 *   1. Loads the canonical fixtures via classpath resource
 *      (Gradle task `copyCanonicalWss3Fixtures` in
 *      `apps/android/build.gradle.kts` copies
 *      `docs/tracks/direct-wss/operator-package/fixtures/<name>.json`
 *      into `build/generated/canonicalFixtures/wss3/` and adds
 *      that directory as a test-resource source).
 *   2. Runs the REAL DiagnosticNetworkProfileReporter through
 *      Robolectric with a canned HTTP server (same pattern as
 *      DiagnosticNetworkProfileReporterHmacDeterminismTest).
 *   3. Asserts the reporter's KEY SET on both the state-only
 *      (phone) and full (emu) paths matches the fixture EXACTLY.
 *   4. Asserts every field's JSON type matches the fixture's type.
 *   5. Pins the three constants — reading their pinned VALUES from
 *      the fixture (no independent hard-coded copy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticNetworkProfileReporterCanonicalShapeTest {

    private val ctx: android.content.Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "203.0.113.42".toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
    }

    /**
     * Load a canonical fixture from the JVM classpath. The Gradle
     * `copyCanonicalWss3Fixtures` task copies
     * `docs/tracks/direct-wss/operator-package/fixtures/<name>` into
     * the test-resource root so `getResourceAsStream("/<name>")`
     * resolves. If the resource is missing, the test fails with a
     * pointer to the Gradle task — no silent hardcoded fallback.
     */
    private fun loadCanonical(name: String): JSONObject {
        val stream = this::class.java.classLoader
            ?.getResourceAsStream(name)
            ?: error(
                "canonical fixture $name not found on the JVM classpath. " +
                    "Verify Gradle task `copyCanonicalWss3Fixtures` is " +
                    "wired at apps/android/build.gradle.kts and that " +
                    "docs/tracks/direct-wss/operator-package/fixtures/$name " +
                    "exists in the repository.",
            )
        return stream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }

    private fun keySet(obj: JSONObject): Set<String> {
        val out = mutableSetOf<String>()
        val it = obj.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    private fun runReporter(egress: Boolean): JSONObject {
        val dir = File(ctx.filesDir, DiagnosticNetworkProfileReporter.REPORT_SUBDIR)
        if (dir.exists()) dir.deleteRecursively()
        val probe = object : DiagnosticNetworkProfileReporter.NetworkStateProbe {
            override fun activeNetworkKind(): String = "CELLULAR"
            override fun hasTransportVpn(): Boolean = false
        }
        DiagnosticNetworkProfileReporter.runInternal(
            context = ctx,
            checkpointKeyHex = "a".repeat(64),
            endpoint = "http://127.0.0.1:${server.address.port}/",
            timeoutMs = DiagnosticNetworkProfileReporter.EGRESS_TIMEOUT_MS,
            probeOverride = probe,
            skipEgress = !egress,
        )
        val file = File(dir, DiagnosticNetworkProfileReporter.REPORT_FILENAME)
        assertTrue(file.isFile, "reporter did not produce ${file.path}")
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    /**
     * Assert `actual` is a JSON value of the same kind as `expected`
     * (bool vs int vs str vs list vs dict). We use the type of the
     * fixture's canonical value as the ground truth.
     */
    /**
     * Tri-state producer fields: the Kotlin reporter models
     * `wifi_enabled` and `mobile_data_enabled` as `Boolean?` and
     * emits `null` when the value cannot be read (e.g. under
     * Robolectric where the ConnectivityManager / WifiManager
     * shadow lacks the underlying state). The canonical fixture
     * pins the happy-path VALUE (typically `true`/`false`), but
     * the TYPE is legitimately `bool` OR `null`.
     */
    private val TRI_STATE_BOOL_KEYS: Set<String> = setOf(
        "wifi_enabled", "mobile_data_enabled",
    )

    private fun assertSameJsonType(
        keyPath: String,
        expected: Any?,
        actual: Any?,
    ) {
        val isTriState = TRI_STATE_BOOL_KEYS.any { keyPath.endsWith(".$it") }
        // A tri-state field may legitimately emit null when the
        // producer cannot read the underlying state.
        val actualIsNull = (actual == null || actual == JSONObject.NULL)
        when (expected) {
            is Boolean -> assertTrue(
                actual is Boolean || (isTriState && actualIsNull),
                "$keyPath: fixture is bool but reporter emitted ${actual?.javaClass}",
            )
            is Int, is Long -> assertTrue(
                actual is Int || actual is Long,
                "$keyPath: fixture is int but reporter emitted ${actual?.javaClass}",
            )
            is String -> assertTrue(
                actual is String,
                "$keyPath: fixture is str but reporter emitted ${actual?.javaClass}",
            )
            is JSONObject -> assertTrue(
                actual is JSONObject,
                "$keyPath: fixture is object but reporter emitted ${actual?.javaClass}",
            )
            null -> assertTrue(
                actualIsNull,
                "$keyPath: fixture is null but reporter emitted $actual",
            )
        }
    }

    @Test
    fun phone_report_key_set_matches_fixture() {
        val fx = loadCanonical("canonical_network_profile.phone.json")
        val json = runReporter(egress = false)
        assertEquals(
            keySet(fx), keySet(json),
            "phone key set drifted from canonical fixture " +
                "(fixtures/canonical_network_profile.phone.json).",
        )
    }

    @Test
    fun emu_report_key_set_matches_fixture() {
        val fx = loadCanonical("canonical_network_profile.emu.json")
        val json = runReporter(egress = true)
        assertEquals(
            keySet(fx), keySet(json),
            "emu key set drifted from canonical fixture " +
                "(fixtures/canonical_network_profile.emu.json).",
        )
    }

    @Test
    fun egress_fingerprint_key_set_matches_fixture() {
        val fx = loadCanonical("canonical_network_profile.emu.json")
            .getJSONObject("egress_fingerprint")
        val json = runReporter(egress = true)
        val egf = json.optJSONObject("egress_fingerprint")
        assertNotNull(egf, "egress_fingerprint missing on emu path")
        assertEquals(
            keySet(fx), keySet(egf),
            "egress_fingerprint key set drifted from canonical fixture " +
                "(fixtures/canonical_network_profile.emu.json).",
        )
    }

    @Test
    fun phone_field_types_match_fixture() {
        val fx = loadCanonical("canonical_network_profile.phone.json")
        val json = runReporter(egress = false)
        for (k in keySet(fx)) {
            assertSameJsonType("phone.$k", fx.opt(k), json.opt(k))
        }
    }

    @Test
    fun emu_field_types_match_fixture() {
        val fx = loadCanonical("canonical_network_profile.emu.json")
        val json = runReporter(egress = true)
        for (k in keySet(fx)) {
            if (k == "egress_fingerprint") continue
            assertSameJsonType("emu.$k", fx.opt(k), json.opt(k))
        }
    }

    @Test
    fun emu_egress_field_types_match_fixture() {
        val fx = loadCanonical("canonical_network_profile.emu.json")
            .getJSONObject("egress_fingerprint")
        val json = runReporter(egress = true).getJSONObject("egress_fingerprint")
        for (k in keySet(fx)) {
            assertSameJsonType("emu.egress_fingerprint.$k", fx.opt(k), json.opt(k))
        }
    }

    @Test
    fun pinned_constants_are_read_from_fixture() {
        // Pins come from the fixture, NOT hardcoded here. The
        // fixture IS the canonical shape; if the reporter drifts,
        // this test fails against the shipped fixture.
        val fxEmu = loadCanonical("canonical_network_profile.emu.json")
        val json = runReporter(egress = true)
        assertEquals(
            fxEmu.getString("schema_version"),
            json.getString("schema_version"),
            "schema_version drift vs fixture " +
                "(DiagnosticNetworkProfileReporter.kt:62).",
        )
        val fxEgress = fxEmu.getJSONObject("egress_fingerprint")
        val jsonEgress = json.getJSONObject("egress_fingerprint")
        assertEquals(
            fxEgress.getString("endpoint"),
            jsonEgress.getString("endpoint"),
            "egress.endpoint drift vs fixture " +
                "(DiagnosticNetworkProfileReporter.kt:559).",
        )
        assertEquals(
            fxEgress.getInt("timeout_ms_used"),
            jsonEgress.getInt("timeout_ms_used"),
            "egress.timeout_ms_used drift vs fixture " +
                "(EGRESS_TIMEOUT_MS, DiagnosticNetworkProfileReporter.kt:59).",
        )
    }

    @Test
    fun emu_egress_pinned_values_match_fixture() {
        // R22 P0-2 explicitly requires emu egress type + value
        // assertions (not just types + phone). The address_family
        // and http_status carry the same pinned values across the
        // producer's healthy path and the fixture.
        val fxEgress = loadCanonical("canonical_network_profile.emu.json")
            .getJSONObject("egress_fingerprint")
        val jsonEgress = runReporter(egress = true).getJSONObject("egress_fingerprint")
        assertEquals(
            fxEgress.getString("address_family"),
            jsonEgress.getString("address_family"),
            "egress.address_family drift vs fixture.",
        )
        assertEquals(
            fxEgress.getInt("http_status"),
            jsonEgress.getInt("http_status"),
            "egress.http_status drift vs fixture (canary 200-OK).",
        )
    }
}
