// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.connectionDataStore by preferencesDataStore(name = "connection_preferences")
private val AUTO_CONNECT_KEY = booleanPreferencesKey("auto_connect_to_first_found")

/**
 * Persists whether [InstrumentViewModel.performConnect] should connect straight to the first
 * instrument a scan finds, or always stop at [ConnectionState.DeviceSelection] so the user picks.
 *
 * Defaults to true - most people have exactly one instrument attached, and this is what every
 * scan already did before the picker gained a way to be shown for a single device too.
 */
class ConnectionPreferences(private val context: Context) {
    val autoConnectToFirstFound: Flow<Boolean> = context.connectionDataStore.data.map { prefs ->
        prefs[AUTO_CONNECT_KEY] ?: true
    }

    suspend fun setAutoConnectToFirstFound(enabled: Boolean) {
        context.connectionDataStore.edit { prefs -> prefs[AUTO_CONNECT_KEY] = enabled }
    }
}
