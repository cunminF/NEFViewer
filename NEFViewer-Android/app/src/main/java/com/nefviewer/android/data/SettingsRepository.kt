package com.nefviewer.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val autoBuildPreviews: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_BUILD] ?: false }
    val preferredEditor: Flow<String?> = context.dataStore.data.map { it[KEY_EDITOR] }
    /** 图库根目录（SAF tree URI）；null = 应用私有目录 */
    val libraryTreeUri: Flow<String?> = context.dataStore.data.map { it[KEY_LIBRARY_URI] }
    val libraryTreeDisplay: Flow<String?> = context.dataStore.data.map { it[KEY_LIBRARY_DISPLAY] }

    suspend fun setAutoBuildPreviews(value: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_BUILD] = value }
    }

    suspend fun setPreferredEditor(flattenedComponent: String?) {
        context.dataStore.edit {
            if (flattenedComponent == null) it.remove(KEY_EDITOR) else it[KEY_EDITOR] = flattenedComponent
        }
    }

    suspend fun setLibraryTree(uri: String?, display: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(KEY_LIBRARY_URI) else it[KEY_LIBRARY_URI] = uri
            if (display == null) it.remove(KEY_LIBRARY_DISPLAY) else it[KEY_LIBRARY_DISPLAY] = display
        }
    }

    companion object {
        private val KEY_AUTO_BUILD = booleanPreferencesKey("autoBuildPreviews")
        private val KEY_EDITOR = stringPreferencesKey("preferredEditor")
        private val KEY_LIBRARY_URI = stringPreferencesKey("libraryTreeUri")
        private val KEY_LIBRARY_DISPLAY = stringPreferencesKey("libraryTreeDisplay")

        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
