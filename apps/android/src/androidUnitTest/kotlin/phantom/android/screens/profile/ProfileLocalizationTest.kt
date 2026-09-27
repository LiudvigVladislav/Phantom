// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.screens.profile

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProfileLocalizationTest {
    @Test
    fun editingUsesStablePreferenceKeysInsteadOfTranslatedLabels() {
        assertEquals(
            mapOf(
                ProfileField.FIRST_NAME to "profile_first_name",
                ProfileField.LAST_NAME to "profile_last_name",
                ProfileField.DATE_OF_BIRTH to "profile_dob",
                ProfileField.CITY to "profile_city",
                ProfileField.COUNTRY to "profile_country",
                ProfileField.ABOUT to "profile_bio",
            ),
            ProfileField.entries.associateWith { it.prefKey },
        )
    }

    @Test
    fun memberSinceUsesTheSelectedLocale() {
        val date = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.FEBRUARY, 15, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val english = formatMemberSince(date, Locale.ENGLISH)
        val russian = formatMemberSince(date, Locale.forLanguageTag("ru"))
        assertEquals("February 2026", english)
        assertTrue(russian.contains("2026"))
        assertNotEquals(english, russian)
        assertEquals("—", formatMemberSince(0L, Locale.ENGLISH))
    }
}
