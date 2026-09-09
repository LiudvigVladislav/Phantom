// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.di

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N1-F1 / N1-F1b — the settlement repositories must stay wired.
 *
 * Both `inboundCommitRepository` and `controlEventCommitRepository` are
 * nullable with a `null` default, so `DefaultMessagingService` keeps
 * compiling if either is deleted from `AppContainer`. The service then
 * silently drops to its fallback: the ordering stays correct, but the
 * ACTION and the LEDGER stop being one transaction, and nothing else in
 * the suite notices.
 *
 * Constructing an `AppContainer` in a unit test is not practical — the
 * same reason the privacy-switch tripwire exists — so this watches the
 * source. It is a tripwire, recorded as such: it proves the wiring is
 * written, not that it behaves.
 *
 * What it pins:
 *
 *  1. both repositories are constructed against the SAME database
 *     handle the other repositories use, because a second database would
 *     put the action and the ledger in different transactions again;
 *  2. both are passed to the `DefaultMessagingService` constructor.
 */
class AppContainerCommitWiringTest {

    private val repoRoot: File = run {
        var candidate = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(candidate, "apps/android/src/androidMain").exists() &&
                File(candidate, "shared/core/storage/src/commonMain").exists()
            ) {
                return@run candidate
            }
            candidate = candidate.parentFile ?: candidate
        }
        candidate
    }

    private val appContainer: File
        get() = File(
            repoRoot,
            "apps/android/src/androidMain/kotlin/phantom/android/di/AppContainer.kt",
        )

    /**
     * Source with comments AND string literals removed.
     *
     * R-N1.14 (review P3-3): this used to claim it removed string
     * literals while removing only comments. The assertions below search
     * for things like `dbHolder.database` and
     * `inboundCommitRepository = inboundCommitRepo`; a future string
     * constant or log line containing that text would have satisfied
     * them without any wiring existing. Stripping strings closes that,
     * and makes the claim true rather than merely corrected.
     *
     * This is a SINGLE-PASS scanner, not a sequence of regex passes.
     * R-N1.14 review finding 2: stripping comments first and strings
     * afterwards is unsound in either order. Strip comments first and
     * `"https://host/path"` loses everything from `//` onward; strip
     * strings first and a `//` comment containing a lone quote swallows
     * the rest of the file. Only one pass that knows which state it is
     * in gets both right.
     */
    private fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    // R-N1.15 review P2: Kotlin block comments NEST.
                    // Stopping at the first `*/` would end the comment
                    // early and treat the remainder of an outer comment
                    // as live code -- so wiring deleted by commenting it
                    // out, with a nested comment inside, would still
                    // satisfy this scan. Depth-aware, therefore.
                    i += 2
                    var depth = 1
                    while (i < n && depth > 0) {
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                            depth++
                            i += 2
                        } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            depth--
                            i += 2
                        } else {
                            i++
                        }
                    }
                    out.append(' ')
                }
                c == '"' && next == '"' && i + 2 < n && text[i + 2] == '"' -> {
                    i += 3
                    while (i < n && !(text[i] == '"' && i + 1 < n && text[i + 1] == '"' &&
                            i + 2 < n && text[i + 2] == '"')
                    ) i++
                    i = minOf(n, i + 3)
                    out.append(" \"\" ")
                }
                c == '"' -> {
                    i++
                    while (i < n && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                    out.append(" \"\" ")
                }
                c == '\'' -> {
                    i++
                    while (i < n && text[i] != '\'') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                    out.append(" '' ")
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun both_commit_repositories_are_built_on_the_shared_database() {
        assertTrue(appContainer.exists(), "AppContainer.kt not found at ${appContainer.path}")
        val code = codeOnly(appContainer.readText())

        for (repo in listOf(
            "SqlDelightInboundCommitRepository",
            "SqlDelightControlEventCommitRepository",
        )) {
            val at = code.indexOf(repo)
            assertTrue(
                at >= 0,
                "$repo is not constructed in AppContainer. Without it the messaging service " +
                    "falls back to a non-atomic settlement and no other test notices.",
            )
            val tail = code.substring(at, minOf(code.length, at + 200))
            assertTrue(
                "dbHolder.database" in tail,
                "$repo must be built on dbHolder.database -- the same handle every other " +
                    "repository uses. A different database would put the action and the " +
                    "ledger in separate transactions, which is the defect being fixed.",
            )
        }
    }

    @Test
    fun both_commit_repositories_are_passed_to_the_messaging_service() {
        val code = codeOnly(appContainer.readText())
        for (param in listOf(
            "inboundCommitRepository = inboundCommitRepo",
            "controlEventCommitRepository = controlEventCommitRepo",
        )) {
            assertTrue(
                param in code,
                "`$param` is missing from the DefaultMessagingService construction. The " +
                    "parameter is nullable with a null default, so removing it still " +
                    "compiles and silently disables atomic settlement.",
            )
        }
    }

    @Test
    fun the_scanner_does_not_mistake_comment_markers_inside_strings() {
        // R-N1.14 review finding 2. A `//` or a block-comment marker
        // inside a string literal is not a comment, and a quote inside a
        // comment does not open a string. A multi-pass stripper gets one
        // of these wrong whichever order it uses.
        val url = "\"https://host/path\""
        val fakeBlock = "\"/* not a comment */\""
        val sample = listOf(
            "val u = $url",
            "val b = $fakeBlock",
            "val keep = dbHolder.database",
            "// a real comment with an unbalanced \" quote",
            "val after = SqlDelightInboundCommitRepository(dbHolder.database)",
        ).joinToString("\n")

        val stripped = codeOnly(sample)

        assertTrue("val u =" in stripped, "the `//` inside a URL string must not eat the line")
        assertTrue("val b =" in stripped, "a block-comment marker inside a string is not a comment")
        assertTrue(
            "https" !in stripped && "not a comment" !in stripped,
            "but the string CONTENTS must still be gone",
        )
        assertTrue(
            "a real comment" !in stripped,
            "a real line comment must go, and its unbalanced quote must not open a string " +
                "that swallows the rest of the file",
        )
        assertEquals(
            2,
            Regex(Regex.escape("dbHolder.database")).findAll(stripped).count(),
            "both real code occurrences survive -- the one after the unbalanced quote too",
        )
    }

    @Test
    fun the_scanner_strips_string_literals_and_comments() {
        // R-N1.14 (review P3-3). The two wiring assertions are only
        // sound if a matching STRING cannot satisfy them. This pins the
        // stripper against a sample carrying the exact text they search
        // for, in every place it could hide.
        val sample = """
            val a = "dbHolder.database"
            val b = "with an escaped quote \" and inboundCommitRepository = inboundCommitRepo"
            // comment: controlEventCommitRepository = controlEventCommitRepo
            /* block: dbHolder.database */
            val real = SqlDelightInboundCommitRepository(dbHolder.database)
        """.trimIndent()
        val stripped = codeOnly(sample)

        assertTrue(
            "SqlDelightInboundCommitRepository(dbHolder.database)" in stripped,
            "real code must survive the stripper, or the wiring assertions can never pass",
        )
        assertEquals(
            1,
            Regex(Regex.escape("dbHolder.database")).findAll(stripped).count(),
            "only the real occurrence may remain: the string literal and the block " +
                "comment carrying the same text must both be gone",
        )
        assertTrue(
            "inboundCommitRepository = inboundCommitRepo" !in stripped,
            "a string literal containing the wiring text must not satisfy the scan -- " +
                "the escaped quote inside it must not end the literal early",
        )
        assertTrue(
            "controlEventCommitRepository = controlEventCommitRepo" !in stripped,
            "nor may a line comment",
        )
    }

    @Test
    fun a_nested_block_comment_cannot_smuggle_wiring_back_in() {
        // R-N1.15 review P2. This is the attack the scanner exists to
        // stop: delete the wiring by commenting it out, and put a nested
        // comment above it. A scanner that ends the block at the first
        // `*/` sees the wiring line as live code and stays GREEN while
        // the wiring is gone.
        val sample = listOf(
            "/*",
            "  /* nested */",
            "  SqlDelightControlEventCommitRepository(dbHolder.database)",
            "*/",
            "val real = SqlDelightInboundCommitRepository(dbHolder.database)",
        ).joinToString("\n")

        val stripped = codeOnly(sample)

        assertEquals(
            1,
            Regex(Regex.escape("dbHolder.database")).findAll(stripped).count(),
            "the commented-out construction must NOT survive the nested comment: " +
                "only the one real occurrence may remain",
        )
        assertTrue(
            "SqlDelightControlEventCommitRepository" !in stripped,
            "wiring commented out with a nested comment inside must not read as live code",
        )
        assertTrue("val real =" in stripped, "and the real code after the comment must survive")
    }

    @Test
    fun raw_strings_are_stripped() {
        // R-N1.15 review P2. The documentation claimed raw strings were
        // handled; no test supplied one.
        //
        // Split from the character-literal case deliberately: a single
        // fixture asserting both would fail on whichever assertion came
        // first, leaving the other branch undiscriminated no matter how
        // many mutations were run against it.
        // The lone double quote on the second line is load bearing.
        // Without it, deleting the raw-string branch happens to strip the
        // same span anyway: `"""` reads as an empty string plus one quote
        // that opens an ordinary string running to the first quote of the
        // closing delimiter. The fixture would pass against either
        // implementation and prove nothing -- measured, not assumed:
        // MUT-RAWSTRING came back NOT OBSERVED against the first version
        // of this sample. A lone quote inside the raw string closes that
        // accidental ordinary string early, so the text after it re-enters
        // the scan as code and the count assertion below moves.
        val sample = listOf(
            "val r = \"\"\"",
            "    a lone \" quote, then",
            "    SqlDelightControlEventCommitRepository(dbHolder.database)",
            "    // not a comment, it is inside a raw string",
            "\"\"\"",
            "val keep = SqlDelightInboundCommitRepository(dbHolder.database)",
        ).joinToString("\n")

        val stripped = codeOnly(sample)

        assertEquals(
            1,
            Regex(Regex.escape("dbHolder.database")).findAll(stripped).count(),
            "a raw string carrying the wiring text must not satisfy the scan",
        )
        assertTrue(
            "not a comment" !in stripped,
            "raw string contents go whole, comment markers inside them included",
        )
        assertTrue("val keep =" in stripped, "and the real code after it survives")
    }

    @Test
    fun a_char_literal_holding_a_quote_does_not_swallow_the_code_after_it() {
        // R-N1.15 review P2, second half. `'"'` is a character literal
        // containing a double quote. Without a character-literal branch
        // the scanner treats that quote as the start of a string and
        // consumes everything after it, so the wiring below would vanish
        // from the scan and the tripwire would report it missing --
        // or, in the mirror case, hide a deletion.
        val sample = listOf(
            "val q = '\"'",
            "val keep = SqlDelightInboundCommitRepository(dbHolder.database)",
        ).joinToString("\n")

        val stripped = codeOnly(sample)

        assertTrue(
            "val keep =" in stripped,
            "the code after a char literal holding a double quote must survive",
        )
        assertEquals(
            1,
            Regex(Regex.escape("dbHolder.database")).findAll(stripped).count(),
            "and it must survive exactly once, as real code",
        )
    }

    @Test
    fun the_scan_is_not_vacuous() {
        // If the file could not be read, or the comment stripper ate the
        // whole thing, every assertion above would pass on an empty
        // string.
        val code = codeOnly(appContainer.readText())
        assertTrue(code.length > 10_000, "AppContainer source looks empty: ${code.length} chars")
        assertTrue(
            "DefaultMessagingService(" in code,
            "the messaging service construction is the anchor these tests rely on",
        )
    }
}
