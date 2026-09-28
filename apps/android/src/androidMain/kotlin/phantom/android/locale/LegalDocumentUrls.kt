// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.locale

internal enum class LegalDocument(val path: String) {
    Terms("terms"),
    Privacy("privacy"),
}

internal fun legalDocumentUrl(document: LegalDocument, language: String): String {
    val suffix = if (language.equals("ru", ignoreCase = true)) "/ru" else ""
    return "https://phntm.pro/${document.path}$suffix"
}
