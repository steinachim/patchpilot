package de.thewolfwalkexperience.software.patchpilot.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")
private val THEME_KEY = stringPreferencesKey("app_theme")

/**
 * Persists which [AppTheme] the user picked in Settings, across launches.
 *
 * Stored by [AppTheme.name] rather than an ordinal - an ordinal silently points at the wrong
 * theme the day this enum's order changes, where an unrecognised name just falls back to
 * [AppTheme.Default] (see [theme] below), which is always a safe value to land on.
 */
class ThemePreferences(private val context: Context) {
    val theme: Flow<AppTheme> = context.themeDataStore.data.map { prefs ->
        prefs[THEME_KEY]?.let { stored ->
            AppTheme.entries.firstOrNull { it.name == stored }
        } ?: AppTheme.Default
    }

    suspend fun setTheme(theme: AppTheme) {
        context.themeDataStore.edit { prefs -> prefs[THEME_KEY] = theme.name }
    }
}
