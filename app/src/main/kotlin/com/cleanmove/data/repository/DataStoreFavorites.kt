package com.cleanmove.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cleanmove.domain.FavoritesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreFavorites(private val dataStore: DataStore<Preferences>) : FavoritesStore {
    private val key = stringSetPreferencesKey("favorite_lines")

    override val favoriteLines: Flow<Set<String>> = dataStore.data.map { it[key] ?: emptySet() }

    override suspend fun toggleLine(code: String) {
        dataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (code in current) current - code else current + code
        }
    }
}
