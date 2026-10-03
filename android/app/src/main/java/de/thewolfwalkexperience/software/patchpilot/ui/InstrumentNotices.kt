// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.noticeDataStore by preferencesDataStore(name = "notice_preferences")
private val HIDDEN_NOTICES_KEY = stringSetPreferencesKey("hidden_notice_ids")

/**
 * Which [InstrumentNotice]s the user asked never to see again, by id. Settings clears the set, so
 * a notice hidden by mistake can be read again.
 */
class NoticePreferences(private val context: Context) {
    val hidden: Flow<Set<String>> = context.noticeDataStore.data.map { prefs ->
        prefs[HIDDEN_NOTICES_KEY] ?: emptySet()
    }

    suspend fun hide(id: String) {
        context.noticeDataStore.edit { prefs ->
            prefs[HIDDEN_NOTICES_KEY] = (prefs[HIDDEN_NOTICES_KEY] ?: emptySet()) + id
        }
    }

    suspend fun showAll() {
        context.noticeDataStore.edit { prefs -> prefs.remove(HIDDEN_NOTICES_KEY) }
    }
}

/**
 * The notice to show next, or null: the first of [notices] that is neither hidden for good nor
 * already shown since the app started. Once per app run rather than once per session, since a
 * USB family rebuilds its session on every return to the foreground.
 */
internal fun pendingNotice(
    notices: List<InstrumentNotice>,
    hidden: Set<String>,
    shownThisRun: Set<String>,
): InstrumentNotice? = notices.firstOrNull { it.id !in hidden && it.id !in shownThisRun }
