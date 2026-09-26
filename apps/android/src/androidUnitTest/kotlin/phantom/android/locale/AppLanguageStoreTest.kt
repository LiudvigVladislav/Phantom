// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.locale

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class AppLanguageStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreference() {
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .edit().remove("app_language").commit()
    }

    @Test
    fun systemIsDistinctFromExplicitEnglish() {
        assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context))
        assertSame(context, AppLanguageStore.localizedBaseContext(context))

        assertTrue(AppLanguageStore.set(context, AppLanguage.ENGLISH))
        assertEquals(AppLanguage.ENGLISH, AppLanguageStore.selected(context))

        assertTrue(AppLanguageStore.set(context, AppLanguage.SYSTEM))
        assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context))
        assertSame(context, AppLanguageStore.localizedBaseContext(context))
    }

    @Test
    fun russianOverrideChangesResourcesWithoutChangingSystemDefault() {
        val original = context.resources.configuration.locales.get(0).language
        assertTrue(AppLanguageStore.set(context, AppLanguage.RUSSIAN))

        val localized = AppLanguageStore.localizedBaseContext(context)
        assertEquals("ru", localized.resources.configuration.locales.get(0).language)
        assertEquals(original, context.resources.configuration.locales.get(0).language)
    }

    @Test
    fun returningToSystemDoesNotRetainTheApplicationOverride() {
        val systemLanguage = context.resources.configuration.locales.get(0).language
        assertTrue(AppLanguageStore.set(context, AppLanguage.RUSSIAN))
        val russianBase = AppLanguageStore.localizedBaseContext(context)
        assertEquals("ru", russianBase.resources.configuration.locales.get(0).language)

        assertTrue(AppLanguageStore.set(russianBase, AppLanguage.SYSTEM))
        val systemBase = AppLanguageStore.localizedBaseContext(russianBase)
        assertEquals(systemLanguage, systemBase.resources.configuration.locales.get(0).language)
    }

    @Test
    fun unsupportedPersistedTagDoesNotSelectAnUnexpectedLanguage() {
        context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_language", "fr").commit()
        assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context))
    }

    @Test
    @Config(sdk = [35])
    fun android13SelectionUsesTheSystemPerAppLocale() {
        val manager = context.getSystemService(LocaleManager::class.java)
        try {
            assertTrue(AppLanguageStore.set(context, AppLanguage.RUSSIAN))
            assertEquals("ru", manager.applicationLocales.get(0).language)
            assertEquals(AppLanguage.RUSSIAN, AppLanguageStore.selected(context))

            assertTrue(AppLanguageStore.set(context, AppLanguage.SYSTEM))
            assertTrue(manager.applicationLocales.isEmpty)
            assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context))
        } finally {
            AppLanguageStore.set(context, AppLanguage.SYSTEM)
        }
    }

    @Test
    @Config(sdk = [35])
    fun androidUpgradeMigratesLegacyOverrideOnlyWhenSystemHasNone() {
        val manager = context.getSystemService(LocaleManager::class.java)
        try {
            assertTrue(AppLanguageStore.set(context, AppLanguage.SYSTEM))
            context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
                .edit().putString("app_language", "ru").commit()

            AppLanguageStore.migratePre33Override(context)
            assertEquals(AppLanguage.RUSSIAN, AppLanguageStore.selected(context))
            assertEquals(null, context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
                .getString("app_language", null))

            context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE)
                .edit().putString("app_language", "en").commit()
            AppLanguageStore.migratePre33Override(context)
            assertEquals("ru", manager.applicationLocales.get(0).language)
        } finally {
            AppLanguageStore.set(context, AppLanguage.SYSTEM)
        }
    }
}
