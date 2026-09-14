// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.proguard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 2 physical acceptance (2026-09-14) — gomobile keep-rule pin.
 *
 * The release build aborted three times on a TECNO BF7 the moment the
 * phone lost network and the transport lifecycle reached the vendored Xray
 * runtime:
 *
 * ```text
 * Fatal signal 6 (SIGABRT) in DefaultDispatch
 * Abort message: 'failed to find method Seq.getRef'
 * libgojni.so (Java_go_Seq_init+504)
 * ```
 *
 * `Java_go_Seq_init` resolves the gomobile bridge by NAME through JNI. In
 * the shipped release DEX `go.Seq` had been reduced to a static
 * initialiser plus the private native `init()`, `go.Seq$Ref` was gone
 * entirely, and so was the whole `libXray` binding.
 *
 * **The subtlety this test exists to pin.** The rules file already had a
 * catch-all, `-keepclasseswithmembernames class * { native <methods>; }`,
 * and it looked like it covered this. It does not.
 * `-keepclasseswithmembernames` preserves NAMES; it does not prevent
 * SHRINKING. Nothing in Java references the gomobile binding — Go reaches
 * it over JNI — so R8 correctly saw it as unused and removed it. Only a
 * full `-keep` suppresses both shrinking and obfuscation, which is why the
 * fix cannot be expressed as `-keepnames` or by extending the catch-all.
 *
 * Four checks, the first three matching the shape of
 * [phantom.android.transport.KtorRelayTransportProguardNarrowingPinTest]:
 *
 *   1. Both vendored gomobile packages are kept with their members.
 *   2. The rules are `-keep`, not a name-only variant that would leave the
 *      classes shrinkable and reproduce the crash.
 *   3. The fix did NOT reach for a broad application-wide keep.
 *   4. The release-time DEX check decides on DEFINITIONS, not on the DEX id
 *      tables.
 *
 * The runtime half lives in the `verifyR8KeepsGomobileJniSurface` Gradle
 * task, which reads `class_defs` and each target's `class_data_item` in the
 * assembled APK and requires every JNI-resolved member by name, descriptor
 * and static/instance shape. This test guards the design intent in source;
 * that task proves the intent took effect. Neither is a substitute for the
 * other: a rule can be present and ineffective, and a DEX can be correct
 * today for a reason nobody wrote down.
 *
 * Case 4 exists because the first version of that task was unsound. It
 * asked whether each descriptor appeared in `type_ids` / `method_ids` /
 * `field_ids`, which are the DEX's symbol REFERENCE pool: a class that some
 * other class merely calls into is listed there even after its own
 * definition has been shrunk away. An independent review measured the
 * consequence — a one-class probe that referenced all thirteen contract
 * entries and defined none of them was accepted. Pinning the distinction
 * here keeps a future edit from quietly reintroducing it.
 */
class GomobileJniKeepRulePinTest {

    private val goKeep = "-keep class go.** { *; }"
    private val libXrayKeep = "-keep class libXray.** { *; }"

    @Test
    fun both_vendored_gomobile_packages_are_kept_with_their_members() {
        val rules = loadProguardRules()
        assertTrue(
            goKeep in rules,
            "`apps/android/proguard-rules.pro` MUST carry `$goKeep`.\n" +
                "Without it R8 strips `go.Seq`'s pure-Java members and removes " +
                "`go.Seq\$Ref` outright, and `Java_go_Seq_init` aborts the process " +
                "with `failed to find method Seq.getRef` the first time the Xray " +
                "runtime is reached.",
        )
        assertTrue(
            libXrayKeep in rules,
            "`apps/android/proguard-rules.pro` MUST carry `$libXrayKeep`.\n" +
                "The arm64 `libgojni.so` resolves `libXray/CountGeoDataRequest`, " +
                "`libXray/DialerController`, `libXray/LibXray\$proxyDialerController`, " +
                "`libXray/RunXrayFromJSONRequest` and `libXray/RunXrayRequest` with " +
                "FindClass, so those classes must survive R8 under their original names.",
        )
    }

    @Test
    fun the_gomobile_rules_are_keep_and_not_a_name_only_variant() {
        val rules = loadProguardRules()
        for (packageGlob in listOf("go.**", "libXray.**")) {
            val nameOnly = Regex(
                """^\s*-keep(names|classeswithmembernames|classmembernames)\b[^\n]*""" +
                    Regex.escape(packageGlob),
                RegexOption.MULTILINE,
            ).find(rules)
            assertTrue(
                nameOnly == null,
                "`$packageGlob` must be held by a full `-keep`, not `${nameOnly?.value?.trim()}`.\n" +
                    "Name-only directives stop renaming but still allow R8 to shrink the " +
                    "classes away, which is exactly the failure this rule exists to prevent: " +
                    "the build that crashed already had " +
                    "`-keepclasseswithmembernames class * { native <methods>; }`.",
            )
        }
    }

    @Test
    fun the_fix_did_not_reach_for_an_application_wide_keep() {
        val rules = loadProguardRules()
        val liveDirectives = rules.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("-keep") }
            .toList()
        val broad = liveDirectives.filter { line ->
            Regex("""^-keep\s+class\s+(\*\*|\*)\s*(\{|$)""").containsMatchIn(line) ||
                line.startsWith("-keep class phantom.** ") ||
                line.startsWith("-keep class phantom.**{")
        }
        assertTrue(
            broad.isEmpty(),
            "A JNI keep must stay scoped to the vendored binding. Found a broad keep: $broad.\n" +
                "`go.**` and `libXray.**` are narrow by construction — together they are " +
                "exactly the 18 classes in " +
                "`shared/core/xray/src/androidMain/libs/libXray.jar` — whereas an " +
                "application-wide keep would disable shrinking for PHANTOM's own code and " +
                "silently re-admit the very test seams `verifyR8StripsTestSeams` exists to strip.",
        )
        assertFalse(
            "-dontobfuscate" in rules,
            "`-dontobfuscate` would hide a missing keep by making every name survive, " +
                "turning both R8 verifications into no-ops.",
        )
        assertFalse(
            "-dontshrink" in rules,
            "`-dontshrink` would hide a missing keep by making every class survive, " +
                "turning both R8 verifications into no-ops.",
        )
    }

    @Test
    fun the_release_dex_check_decides_on_definitions_not_id_tables() {
        val buildScript = loadModuleFile("build.gradle.kts")

        for (marker in listOf("class_defs", "class_data_item", "classDefsOff", "classDataOff")) {
            assertTrue(
                marker in buildScript,
                "`verifyR8KeepsGomobileJniSurface` must read `$marker`. A JNI keep is proven only " +
                    "by a class DEFINITION surviving R8, and definitions live in `class_defs` and " +
                    "`class_data_item` — not in the id tables.",
            )
        }

        // Acceptance must be expressed against the `defined*` collections.
        for (accepted in listOf("in surface.definedTypes", "surface.definedFields", "surface.definedMethods")) {
            assertTrue(
                accepted in buildScript,
                "The contract verdict must be taken from `$accepted`.",
            )
        }

        // The id tables may still be parsed, but only to explain a failure.
        assertFalse(
            "entry.owner in surface.referencedTypes -> null" in buildScript,
            "A contract entry must never be accepted because it appears in `type_ids`. " +
                "That is exactly the false positive an independent review measured: a probe with " +
                "one class definition and references to all thirteen entries was accepted.",
        )

        // And the task must keep proving that it can still fail.
        for (selfTest in listOf("stripClassDefs", "SELF-TEST FAILED", "REFERENCED-ONLY")) {
            assertTrue(
                selfTest in buildScript,
                "The release check must keep its `$selfTest` self-test, which rebuilds the artifact " +
                    "with every contract owner's `class_def` removed and refuses to certify the " +
                    "build unless its own verdict flips to a rejection.",
            )
        }

        // The JNI shape is part of the contract, not a detail.
        assertTrue(
            "requiredStatic" in buildScript && "ACC_STATIC" in buildScript,
            "The five `go.Seq` entry points are invoked with `CallStatic*Method` and " +
                "`go.Seq\$Ref.obj` is read with `GetObjectField`, so the static/instance shape " +
                "must be checked alongside the descriptor.",
        )
    }

    /**
     * Load `apps/android/proguard-rules.pro` relative to the JVM unit-test
     * working directory, the same way
     * [phantom.android.transport.KtorRelayTransportProguardNarrowingPinTest]
     * does.
     */
    private fun loadProguardRules(): String = loadModuleFile("proguard-rules.pro")

    /** Resolve a file of the `apps/android` module from the unit-test cwd. */
    private fun loadModuleFile(name: String): String {
        val candidates = listOf(
            File(name),
            File("apps/android/$name"),
            File("../$name"),
        )
        val resolved = candidates.firstOrNull { it.exists() && it.isFile }
        assertNotNull(
            resolved,
            "Could not locate `$name` relative to the test cwd. " +
                "Candidates tried: ${candidates.map { it.absolutePath }}.",
        )
        return resolved.readText()
    }
}
