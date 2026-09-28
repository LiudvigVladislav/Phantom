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
import phantom.android.R
import phantom.android.screens.chat.ComposerDrafts

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
        assertEquals("Язык", localized.getString(R.string.settings_language))
        assertEquals(AppLanguage.RUSSIAN, AppLanguageStore.effectiveLanguage(context))
    }

    @Test
    fun switchingBackToEnglishRefreshesLongLivedRussianContexts() {
        AppLanguageStore.set(context, AppLanguage.RUSSIAN)
        val oldContext = AppLanguageStore.stringsContext(context)
        val revision = AppLanguageStore.configurationChanges.value
        AppLanguageStore.set(context, AppLanguage.ENGLISH)
        assertTrue(AppLanguageStore.configurationChanges.value > revision)
        assertEquals("Reply", AppLanguageStore.stringsContext(oldContext).getString(R.string.notification_reply_action))
        assertEquals("Ответить", oldContext.getString(R.string.notification_reply_action))
    }

    @Test
    @Config(qualifiers = "ru-rRU")
    fun russianSystemIsAutomaticAndExplicitEnglishOverridesIt() {
        assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context))
        assertEquals(AppLanguage.RUSSIAN, AppLanguageStore.effectiveLanguage(context))
        assertEquals("Язык", AppLanguageStore.stringsContext(context).getString(R.string.settings_language))
        AppLanguageStore.set(context, AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, AppLanguageStore.effectiveLanguage(context))
        assertEquals("Language", AppLanguageStore.stringsContext(context).getString(R.string.settings_language))
        AppLanguageStore.set(context, AppLanguage.SYSTEM)
        assertEquals("Язык", AppLanguageStore.stringsContext(context).getString(R.string.settings_language))
    }

    @Test
    @Config(sdk = [32, 35], qualifiers = "en-rUS")
    fun defaultFollowsSystemWithoutWritingAnOverrideButManualChoiceWins() {
        assertEquals(AppLanguage.ENGLISH, AppLanguageStore.effectiveLanguage(context))
        assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context))
        assertTrue(!context.getSharedPreferences("phantom_prefs", Context.MODE_PRIVATE).contains("app_language"))
        RuntimeEnvironment.setQualifiers("ru-rRU")
        assertEquals(AppLanguage.RUSSIAN, AppLanguageStore.effectiveLanguage(context))
        AppLanguageStore.set(context, AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, AppLanguageStore.effectiveLanguage(context))
        RuntimeEnvironment.setQualifiers("en-rUS")
        AppLanguageStore.set(context, AppLanguage.RUSSIAN)
        assertEquals(AppLanguage.RUSSIAN, AppLanguageStore.effectiveLanguage(context))
        assertEquals("Язык", AppLanguageStore.stringsContext(context).getString(R.string.settings_language))
    }

    @Test
    @Config(sdk = [32, 35], qualifiers = "fr-rFR")
    fun unsupportedSystemLanguageDefaultsToEnglishWithoutPersistingIt() {
        assertEquals(AppLanguage.ENGLISH, AppLanguageStore.effectiveLanguage(context))
        assertEquals(AppLanguage.SYSTEM, AppLanguageStore.selected(context))
    }

    @Test
    fun russianPluralFormsAreResolvedByAndroid() {
        AppLanguageStore.set(context, AppLanguage.RUSSIAN)
        val resources = AppLanguageStore.stringsContext(context).resources
        assertEquals("1 участник", resources.getQuantityString(R.plurals.group_members, 1, 1))
        assertEquals("2 участника", resources.getQuantityString(R.plurals.group_members, 2, 2))
        assertEquals("5 участников", resources.getQuantityString(R.plurals.group_members, 5, 5))
        assertEquals("21 участник", resources.getQuantityString(R.plurals.group_members, 21, 21))
    }

    @Test
    fun composerDraftsSurviveNavigationAndNeverCrossIdentityOrConversation() {
        val owner = androidx.lifecycle.ViewModelStore()
        val provider = androidx.lifecycle.ViewModelProvider(
            owner, androidx.lifecycle.ViewModelProvider.NewInstanceFactory(),
        )
        val first = provider[ComposerDrafts::class.java].get("identity-a", "chat:a")
        first.text.value = "Unsent / Не отправлено"
        first.editingId.value = "edit-id"
        val recreatedProvider = androidx.lifecycle.ViewModelProvider(
            owner, androidx.lifecycle.ViewModelProvider.NewInstanceFactory(),
        )
        val resumed = recreatedProvider[ComposerDrafts::class.java].get("identity-a", "chat:a")
        assertSame(first, resumed)
        assertEquals("Unsent / Не отправлено", resumed.text.value)
        assertEquals("edit-id", resumed.editingId.value)
        assertEquals("", provider[ComposerDrafts::class.java].get("identity-a", "group:a").text.value)
        assertEquals("", provider[ComposerDrafts::class.java].get("identity-b", "chat:a").text.value)
        assertEquals("", provider[ComposerDrafts::class.java].get("identity-a", "chat:a").text.value)
        owner.clear()
    }

    @Test
    fun deletingAConversationAndClearingTheOwnerDiscardDraftReferences() {
        val owner = androidx.lifecycle.ViewModelStore()
        val holder = androidx.lifecycle.ViewModelProvider(
            owner, androidx.lifecycle.ViewModelProvider.NewInstanceFactory(),
        )[ComposerDrafts::class.java]
        val draft = holder.get("identity", "chat:a")
        draft.text.value = "private draft"
        draft.editingId.value = "edit-id"
        holder.clear("chat:a")
        assertEquals("", draft.text.value)
        assertEquals(null, draft.editingId.value)
        val second = holder.get("identity", "chat:b")
        second.text.value = "another draft"
        owner.clear()
        assertEquals("", second.text.value)
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
