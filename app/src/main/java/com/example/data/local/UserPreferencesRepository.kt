package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.SourceSortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val KEY_LAST_REFRESH_AT = longPreferencesKey("last_refresh_at")
        val KEY_SOURCE_SORT_MODE = stringPreferencesKey("source_sort_mode")
    }

    val lastRefreshAt: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.KEY_LAST_REFRESH_AT] ?: 0L
        }

    val sourceSortMode: Flow<SourceSortMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val raw = preferences[PreferencesKeys.KEY_SOURCE_SORT_MODE]
            SourceSortMode.entries.firstOrNull { it.name == raw } ?: SourceSortMode.MANUAL
        }

    suspend fun setLastRefreshAt(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_LAST_REFRESH_AT] = timestamp
        }
    }

    suspend fun setSourceSortMode(mode: SourceSortMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_SOURCE_SORT_MODE] = mode.name
        }
    }
}
