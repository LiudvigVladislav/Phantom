// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Willen LLC

package phantom.android.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    RUSSIAN("ru");

    companion object {
        fun fromTag(tag: String?): AppLanguage = when (tag?.lowercase(Locale.ROOT)) {
            "en" -> ENGLISH
            "ru" -> RUSSIAN
            else -> SYSTEM
        }
    }
}

/** OS-owned on Android 13+, local preference on earlier Android versions. */
internal object AppLanguageStore {
    private const val PREFS = "phantom_prefs"
    private const val KEY = "app_language"
    private val changes = MutableStateFlow(0L)
    val configurationChanges = changes.asStateFlow()

    fun configurationChanged() { changes.update { it + 1 } }

    fun selected(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.fromTag(locales.get(0).language)
        }
        return AppLanguage.fromTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }

    /** System-following is an implicit default, never a third language in the picker. */
    fun effectiveLanguage(context: Context): AppLanguage {
        val selection = selected(context)
        if (selection != AppLanguage.SYSTEM) return selection
        val locales = stringsContext(context).resources.configuration.locales
        return if (locales.getFirstMatch(arrayOf("en", "ru"))?.language == "ru")
            AppLanguage.RUSSIAN else AppLanguage.ENGLISH
    }

    fun set(context: Context, language: AppLanguage): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // A stale pre-33 override must never reappear after the user chooses System.
            if (!prefs.edit().remove(KEY).commit()) return false
            val locales = if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(language.tag)
            context.getSystemService(LocaleManager::class.java).applicationLocales = locales
            configurationChanged()
            return true
        }
        return prefs.edit().putString(KEY, language.tag).commit().also {
            if (it) configurationChanged()
        }
    }

    fun migratePre33Override(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = AppLanguage.fromTag(prefs.getString(KEY, null))
        if (legacy == AppLanguage.SYSTEM) return
        val manager = context.getSystemService(LocaleManager::class.java)
        if (!manager.applicationLocales.isEmpty) {
            prefs.edit().remove(KEY).apply()
            return
        }
        manager.applicationLocales = LocaleList.forLanguageTags(legacy.tag)
        prefs.edit().remove(KEY).apply()
    }

    fun localizedBaseContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        return stringsContext(base)
    }

    /** Resolve fresh resources even when a long-lived service holds an old override. */
    fun stringsContext(base: Context): Context {
        val language = selected(base)
        val locales = if (language == AppLanguage.SYSTEM) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                base.getSystemService(LocaleManager::class.java).systemLocales
            else Resources.getSystem().configuration.locales
        }
            else LocaleList.forLanguageTags(language.tag)
        if (base.resources.configuration.locales == locales) return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(locales)
        return base.createConfigurationContext(configuration)
    }
}
