// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.service

/**
 * Single-pass Kotlin scanner that strips comments and literals, leaving
 * code.
 *
 * This is the same algorithm `AppContainerCommitWiringTest` uses, kept in
 * a separate file rather than shared with it. The F-1b test and its
 * mutations are part of accepted evidence; refactoring it to export this
 * would change bytes that were signed off, for no benefit to the round
 * that is actually in progress. The duplication is deliberate and
 * temporary — the two should be merged in a later round that is allowed
 * to touch both.
 *
 * The algorithm matters and is not a formality. Three separate review
 * findings in the F-1b track came from getting it wrong:
 *
 *  - stripping comments and strings in separate passes is unsound in
 *    either order (`"https://host"` loses its tail; a comment holding a
 *    lone quote swallows the file);
 *  - Kotlin block comments NEST, so ending at the first closing delimiter lets
 *    commented-out wiring read as live code;
 *  - raw strings and character literals need their own branches, and
 *    each needs its own discriminating mutation to be known to work.
 */
internal object RetryWiringSourceScanner {

    fun codeOnly(text: String): String {
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

    /**
     * The body of the first `if` block whose header matches [headerNeedle],
     * brace-balanced over already-stripped code. Returns null when the
     * header is absent, so a caller can tell "the branch moved" from "the
     * branch is empty".
     */
    fun blockAfter(code: String, headerNeedle: String): String? {
        val at = code.indexOf(headerNeedle)
        if (at < 0) return null
        val open = code.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        return null
    }
}
