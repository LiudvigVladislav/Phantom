// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.transport

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Path-2 step 2 (2026-06-30) — ProGuard narrowing structural pin.
 *
 * Pins `apps/android/proguard-rules.pro` against silent regressions on the
 * narrowed `KtorRelayTransport` keeps. The companion `verifyR8StripsTestSeams`
 * Gradle task verifies the runtime side (no forbidden member patterns
 * survive R8 in the release `mapping.txt`); this test guards the design
 * intent in source — the rules file must NOT carry the over-broad
 * wildcard, and the documented targeted keeps must be present.
 *
 * Three checks:
 *
 *   1. The over-broad `-keep class phantom.core.transport.KtorRelayTransport { *; }`
 *      wildcard MUST NOT appear anywhere in the file. The wildcard was the
 *      original 2026-06 keep that preserved every member, including the
 *      production-test seams (`*ForTest`) the path-2 narrowing is meant to
 *      strip. Re-introducing it would silently bypass the entire narrowing.
 *
 *   2. The `-keepclassmembers class phantom.core.transport.KtorRelayTransport {`
 *      block MUST be present. Without it, R8 would strip the five
 *      concrete-type-accessed members the wildcard previously preserved
 *      (`wsSessionLifecycle`, `outboundAckDeadlineExpired`, `inboundStalled`,
 *      `snapshotPendingOutbound`, `markPendingOutboundAcceptedByFallback`),
 *      breaking `HybridRelayTransport`'s WS-side wiring.
 *
 *   3. Each of the five named members must appear by name inside the keep
 *      block. If any future PR drops a member from the block without first
 *      proving the corresponding call site is gone, this test fails loudly.
 *      The check is structural (substring match), NOT semantic — a member
 *      that survives R8 but is no longer accessed by name from external
 *      code is still preserved by this rule.
 *
 * Companion runtime check: `verifyR8StripsTestSeams` Gradle task
 * (`build.gradle.kts`, wired as `finalizedBy assembleRelease`) parses the
 * release `mapping.txt` and fails the build if any `phantom.*` class shows
 * a surviving `*ForTest*` / `debugForce*` / `*Synthetic*` member. Together
 * the two checks pin both the design intent (this test) and the runtime
 * outcome (the Gradle task).
 */
class KtorRelayTransportProguardNarrowingPinTest {

    /**
     * The forbidden wildcard line — exact match against the pre-narrowing
     * line that path-2 step 2 removed. Whitespace-sensitive because the
     * negative-control case (someone re-adding the wildcard) is the very
     * scenario we want to catch in CR, not in the field after R8 ships a
     * broken release.
     */
    private val forbiddenWildcardLine: String =
        "-keep class phantom.core.transport.KtorRelayTransport { *; }"

    /**
     * The narrowed keep-block header line. The block body lists the five
     * targeted members; structural presence of the header proves the
     * narrowing path was taken (vs. someone removing the keep block
     * entirely and silently relying on R8's auto-determination).
     */
    private val narrowedKeepBlockHeader: String =
        "-keepclassmembers class phantom.core.transport.KtorRelayTransport {"

    /**
     * The five concrete-type-accessed members the narrowed block must
     * preserve. Each is justified by a call site in `HybridRelayTransport`
     * (line numbers in the comments inside `proguard-rules.pro`). Adding
     * a member to this list is a deliberate ceremony — it requires
     * justification in the commit message and PR body per the path-2
     * step 2 contract.
     */
    private val requiredMemberNames: List<String> = listOf(
        "wsSessionLifecycle",
        "outboundAckDeadlineExpired",
        "inboundStalled",
        "snapshotPendingOutbound",
        "markPendingOutboundAcceptedByFallback",
    )

    @Test
    fun proguard_rules_does_not_carry_the_over_broad_ktorrelay_wildcard() {
        val source = loadProguardRules()
        // The forbidden wildcard text is allowed to appear inside justification
        // comments — that is how the path-2 narrowing's rationale documents
        // what it removed. What is forbidden is the wildcard appearing as a
        // live ProGuard DIRECTIVE: a non-comment line containing the wildcard
        // shape. Lines starting with `#` are ProGuard comments and ignored by
        // R8; lines starting with `-keep ... { *; }` are the directive shape
        // we must reject.
        val directiveLines = source.lineSequence()
            .map { it.trimEnd() }
            .filterNot { it.trimStart().startsWith("#") }
            .toList()
        val offending = directiveLines.firstOrNull { it.trim() == forbiddenWildcardLine }
        assertTrue(
            offending == null,
            "`apps/android/proguard-rules.pro` MUST NOT carry the wildcard\n" +
                "  $forbiddenWildcardLine\n" +
                "as a live directive (commented mentions in justification text are " +
                "permitted). Found offending line: `$offending`. The wildcard was " +
                "removed by the path-2 step 2 narrowing so R8 can strip production-test " +
                "seams (`*ForTest`), future debug surfaces (`debugForce*`), and " +
                "`*Synthetic*` members from the release APK. Re-introducing the " +
                "wildcard silently bypasses the entire narrowing — the " +
                "`verifyR8StripsTestSeams` Gradle task is the runtime catch, but the " +
                "structural pin here is the source-of-truth catch in code review.",
        )
    }

    @Test
    fun proguard_rules_carries_the_narrowed_keepclassmembers_block() {
        val source = loadProguardRules()
        assertTrue(
            source.contains(narrowedKeepBlockHeader),
            "`apps/android/proguard-rules.pro` MUST carry the narrowed keep block\n" +
                "  $narrowedKeepBlockHeader\n" +
                "Without it, R8 strips `wsSessionLifecycle`, `outboundAckDeadlineExpired`, " +
                "`inboundStalled`, `snapshotPendingOutbound`, and " +
                "`markPendingOutboundAcceptedByFallback` — all of which are accessed " +
                "by name from `HybridRelayTransport` via the concrete `KtorRelayTransport` " +
                "type (not via the `RelayTransport` interface). The result would be a " +
                "release APK whose WS-side wiring crashes on first WebSocket connect.",
        )
    }

    @Test
    fun narrowed_block_lists_every_required_member_by_name() {
        val source = loadProguardRules()
        val block = extractNarrowedKeepBlock(source)
            ?: fail(
                "Could not locate the narrowed `KtorRelayTransport` keep block " +
                    "inside `apps/android/proguard-rules.pro`. Header expected:\n" +
                    "  $narrowedKeepBlockHeader",
            )
        val missing = requiredMemberNames.filterNot { block.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "Narrowed `KtorRelayTransport` keep block is missing the following " +
                "required member name(s): $missing. Each missing name corresponds to " +
                "a concrete-type access from `HybridRelayTransport`; dropping the keep " +
                "would cause a release-binary regression. If a member is genuinely no " +
                "longer needed (e.g., the call site was deleted), document the removal " +
                "in the commit message + PR body and update this test in the same PR.",
        )
    }

    /**
     * Load `apps/android/proguard-rules.pro` relative to the JVM unit-test
     * working directory. Gradle's `:apps:android:testDebugUnitTest` task
     * runs with the module directory as the cwd. Mirrors the resolution
     * pattern in [Mode2FastPathReleaseBuildConfigPinTest.loadBuildGradle].
     */
    private fun loadProguardRules(): String {
        val candidates = listOf(
            File("proguard-rules.pro"),
            File("apps/android/proguard-rules.pro"),
            File("../proguard-rules.pro"),
        )
        val resolved = candidates.firstOrNull { it.exists() && it.isFile }
        assertNotNull(
            resolved,
            "Could not locate `proguard-rules.pro` relative to the test cwd. " +
                "Candidates tried: ${candidates.map { it.absolutePath }}.",
        )
        return resolved.readText()
    }

    /**
     * Returns the substring between the narrowed block header and the
     * matching closing brace, or `null` if the header is not present.
     * Used by the member-name presence check so we only look inside the
     * keep block itself (not at unrelated mentions elsewhere in the file).
     */
    private fun extractNarrowedKeepBlock(source: String): String? {
        val headerIndex = source.indexOf(narrowedKeepBlockHeader)
        if (headerIndex < 0) return null
        val afterHeader = headerIndex + narrowedKeepBlockHeader.length
        val closingBrace = source.indexOf('}', afterHeader)
        if (closingBrace < 0) return null
        return source.substring(afterHeader, closingBrace)
    }
}
