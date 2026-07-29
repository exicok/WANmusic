package com.example.music

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.edit
import java.util.Locale

enum class AppLanguage(val preferenceValue: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en")
}

object AppLanguageSettings {
    private const val PREFS_NAME = "music_prefs"
    private const val KEY_APP_LANGUAGE = "app_language"

    fun load(context: Context): AppLanguage {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.preferenceValue)
        return AppLanguage.entries.firstOrNull { it.preferenceValue == saved } ?: AppLanguage.SYSTEM
    }

    fun save(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_APP_LANGUAGE, language.preferenceValue)
        }
    }

    fun wrapContext(context: Context): Context {
        val locale = when (load(context)) {
            AppLanguage.SYSTEM -> return context
            AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.ENGLISH -> Locale.ENGLISH
        }
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }
}

@Composable
fun appText(chinese: String, english: String): String {
    return if (LocalConfiguration.current.locales[0].language == Locale.CHINESE.language) {
        chinese
    } else {
        english
    }
}
