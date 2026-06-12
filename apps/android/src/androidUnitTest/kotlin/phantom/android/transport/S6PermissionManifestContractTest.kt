// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Trek 2 Stage 2B-B (C6 review-fix round 5 P1.security/tester) —
 * contract test that pins the `phantom.android.dev.permission.TRIGGER_S6`
 * permission declaration in `AndroidManifest.xml`.
 *
 * The S6 controllable trigger receiver registration in `AppContainer`
 * passes this permission name as the `broadcastPermission` argument
 * to `registerReceiver`. Manifest-side, the permission MUST be:
 *
 *   * Declared with the literal name
 *     `phantom.android.dev.permission.TRIGGER_S6`.
 *   * Protection level `signature` (NOT `normal`, NOT `dangerous`,
 *     NOT `signatureOrSystem` — only `signature` correctly prevents
 *     a co-installed third-party app from holding it without
 *     compromising the app's own signing certificate).
 *   * Paired with a corresponding `<uses-permission>` so the
 *     app's own components can satisfy the gate when needed (the
 *     receiver registration is the load-bearing consumer; the
 *     `<uses-permission>` keeps Android's permission framework
 *     consistent even though the receiver itself does NOT need to
 *     hold the permission to register).
 *
 * A future refactor that flips the level to `normal` silently
 * undoes the round-5 P1 security fix: any installed app could then
 * hold the permission with a manifest declaration and broadcast
 * the trigger. This test fences that regression.
 */
class S6PermissionManifestContractTest {

    private val manifest: File by lazy {
        val candidates = listOf(
            File("src/androidMain/AndroidManifest.xml"),
            File("apps/android/src/androidMain/AndroidManifest.xml"),
            File("../apps/android/src/androidMain/AndroidManifest.xml"),
        )
        candidates.firstOrNull { it.exists() && it.isFile }
            ?: fail(
                "Could not locate AndroidManifest.xml. Tried: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    @Test
    fun permission_declared_with_signature_protection_level() {
        val text = manifest.readText(Charsets.UTF_8)
        // The `<permission>` element must include BOTH the literal
        // permission name AND `android:protectionLevel="signature"`.
        // We match a single <permission ... /> element containing
        // both attributes in any order via two independent regex
        // checks against the element's text.
        val elementRegex = Regex(
            """<permission\b[^>]*?android:name="phantom\.android\.dev\.permission\.TRIGGER_S6"[^>]*?/>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = elementRegex.find(text)
        if (match == null) {
            // Try the alternate attribute order — the permission
            // name might appear AFTER protectionLevel in the source.
            val swapRegex = Regex(
                """<permission\b[^>]*?android:name="phantom\.android\.dev\.permission\.TRIGGER_S6"[^>]*?>""",
                RegexOption.DOT_MATCHES_ALL,
            )
            if (swapRegex.find(text) == null) {
                fail(
                    "AndroidManifest.xml MUST declare a `<permission>` element with " +
                        "`android:name=\"phantom.android.dev.permission.TRIGGER_S6\"`. The " +
                        "S6 controllable trigger receiver registration relies on this " +
                        "permission to gate broadcast senders.",
                )
            }
        }
        val element = match?.value ?: fail("permission element not located")
        assertTrue(
            element.contains("""android:protectionLevel="signature""""),
            "The TRIGGER_S6 permission MUST use `android:protectionLevel=\"signature\"`. " +
                "Any other level (normal, dangerous, signatureOrSystem) would let a " +
                "co-installed third-party app hold the permission and broadcast the " +
                "trigger. Element body: $element",
        )
    }

    @Test
    fun uses_permission_paired_with_declared_permission() {
        val text = manifest.readText(Charsets.UTF_8)
        val usesRegex = Regex(
            """<uses-permission\b[^>]*?android:name="phantom\.android\.dev\.permission\.TRIGGER_S6"[^>]*?/>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            usesRegex.containsMatchIn(text),
            "AndroidManifest.xml MUST contain a `<uses-permission " +
                "android:name=\"phantom.android.dev.permission.TRIGGER_S6\" />` element " +
                "paired with the `<permission>` declaration. Without the " +
                "`<uses-permission>`, Android's permission framework treats the app as " +
                "not having declared intent to consume the permission, which is " +
                "internally inconsistent even when the receiver registration does not " +
                "need the app itself to HOLD the permission.",
        )
    }
}
