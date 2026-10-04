// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice
import de.thewolfwalkexperience.software.patchpilot.ui.theme.AppTheme
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "AppPreferences"

// The file names and keys installed copies already hold; renaming one would reset that setting.
private val Context.themeStore by preferencesDataStore(name = "theme_preferences")
private val Context.connectionStore by preferencesDataStore(name = "connection_preferences")
private val Context.noticeStore by preferencesDataStore(name = "notice_preferences")

private val THEME_KEY = stringPreferencesKey("app_theme")
private val AUTO_CONNECT_KEY = booleanPreferencesKey("auto_connect_to_first_found")
private val HIDDEN_NOTICES_KEY = stringSetPreferencesKey("hidden_notice_ids")

/**
 * Every setting the app stores, with one rule for failures. A read never fails: a store that
 * cannot be read reads as its defaults. A write runs on [scope], its owner's, so leaving the
 * screen that asked does not cancel it; a failed write is logged and dropped, and the returned
 * [Job] lets a caller wait for it.
 */
class AppPreferences(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext

    /**
     * Which [AppTheme] the user picked, stored by [AppTheme.name] rather than an ordinal, which
     * would point at the wrong theme the day the enum's order changed; an unrecognised name reads
     * as [AppTheme.Default]. Always emits, since `MainActivity` holds the first frame until it has
     * a value.
     */
    val theme: Flow<AppTheme> = this.context.themeStore.read { prefs ->
        prefs[THEME_KEY]?.let { stored -> AppTheme.entries.firstOrNull { it.name == stored } }
            ?: AppTheme.Default
    }

    /**
     * Whether a scan connects straight to the first instrument it finds rather than stopping at
     * [ConnectionState.DeviceSelection]. Defaults to true: most people have exactly one instrument
     * attached, and the picker would only ask them to choose it.
     */
    val autoConnectToFirstFound: Flow<Boolean> =
        this.context.connectionStore.read { it[AUTO_CONNECT_KEY] ?: true }

    /** The ids of the [InstrumentNotice]s the user asked never to see again. */
    val hiddenNotices: Flow<Set<String>> =
        this.context.noticeStore.read { it[HIDDEN_NOTICES_KEY] ?: emptySet() }

    fun setTheme(theme: AppTheme): Job = context.themeStore.write { it[THEME_KEY] = theme.name }

    fun setAutoConnectToFirstFound(enabled: Boolean): Job =
        context.connectionStore.write { it[AUTO_CONNECT_KEY] = enabled }

    fun hideNotice(id: String): Job =
        context.noticeStore.write { it[HIDDEN_NOTICES_KEY] = (it[HIDDEN_NOTICES_KEY] ?: emptySet()) + id }

    /** Brings every hidden notice back, so one hidden by mistake can be read again. */
    fun showAllNotices(): Job = context.noticeStore.write { it.remove(HIDDEN_NOTICES_KEY) }

    private fun <T> DataStore<Preferences>.read(transform: (Preferences) -> T): Flow<T> =
        data
            .catch { e ->
                if (e !is IOException) throw e
                Log.w(TAG, "Couldn't read a settings store; using its defaults", e)
                emit(emptyPreferences())
            }
            .map(transform)

    private fun DataStore<Preferences>.write(change: (MutablePreferences) -> Unit): Job =
        scope.launch {
            try {
                edit { change(it) }
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't store a setting", e)
            }
        }
}
