package com.example.photowallpaper.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_INTERVAL = intPreferencesKey("interval_minutes")
        private val KEY_ENABLED = booleanPreferencesKey("is_enabled")
        private val KEY_LAST_CHANGED = longPreferencesKey("last_changed")
        private val KEY_FOLDER_NAME = stringPreferencesKey("folder_name")
        private val KEY_FOLDER_ID = stringPreferencesKey("folder_id")
        private val KEY_BLUR_HOME = intPreferencesKey("blur_home_percent")
        private val KEY_BLUR_LOCK = intPreferencesKey("blur_lock_percent")

        // Multi-label filter: user's selected filter labels (comma-separated)
        private val KEY_SELECTED_FILTER_LABELS = stringPreferencesKey("selected_filter_labels")

        // Classification cache: file IDs that have been processed
        private val KEY_CLASSIFIED_FILE_IDS = stringPreferencesKey("classified_file_ids")

        // Per-photo labels cache: "fileId=Label1|Label2\nfileId2=Label3|Label4"
        private val KEY_PHOTO_LABELS_MAP = stringPreferencesKey("photo_labels_map")

        val INTERVAL_OPTIONS = listOf(
            30 to "30 minutes",
            60 to "1 hour",
            180 to "3 hours",
            360 to "6 hours",
            720 to "12 hours",
            1440 to "24 hours"
        )
        const val DEFAULT_INTERVAL = 360
        const val DEFAULT_FOLDER_NAME = "Wallpapers"
    }

    val intervalMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_INTERVAL] ?: DEFAULT_INTERVAL
    }

    val isEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ENABLED] ?: false
    }

    val lastChanged: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_CHANGED] ?: 0L
    }

    val folderName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FOLDER_NAME] ?: DEFAULT_FOLDER_NAME
    }

    val folderId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_FOLDER_ID]
    }

    val blurHomePercent: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_BLUR_HOME] ?: 0
    }

    val blurLockPercent: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_BLUR_LOCK] ?: 0
    }

    /** The user's selected filter labels (e.g. {"Mountains", "Cats", "Ocean"}) */
    val selectedFilterLabels: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_FILTER_LABELS]
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    /** Set of file IDs that have already been classified */
    val classifiedFileIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLASSIFIED_FILE_IDS]
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Map of fileId -> Set<String> (category labels detected for that photo).
     * Encoded as "fileId=Label1|Label2\nfileId2=Label3" in DataStore.
     */
    val photoLabelsMap: Flow<Map<String, Set<String>>> = context.dataStore.data.map { prefs ->
        parsePhotoLabelsMap(prefs[KEY_PHOTO_LABELS_MAP])
    }

    // ── Setters ──────────────────────────────────────────────────────

    suspend fun setInterval(minutes: Int) {
        context.dataStore.edit { it[KEY_INTERVAL] = minutes }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setLastChanged(timestamp: Long) {
        context.dataStore.edit { it[KEY_LAST_CHANGED] = timestamp }
    }

    suspend fun setFolderName(name: String) {
        context.dataStore.edit { it[KEY_FOLDER_NAME] = name }
    }

    suspend fun setFolderId(id: String?) {
        context.dataStore.edit {
            if (id != null) {
                it[KEY_FOLDER_ID] = id
            } else {
                it.remove(KEY_FOLDER_ID)
            }
        }
    }

    suspend fun setFolder(id: String, name: String) {
        context.dataStore.edit {
            it[KEY_FOLDER_ID] = id
            it[KEY_FOLDER_NAME] = name
        }
    }

    suspend fun setBlurHomePercent(percent: Int) {
        context.dataStore.edit { it[KEY_BLUR_HOME] = percent.coerceIn(0, 100) }
    }

    suspend fun setBlurLockPercent(percent: Int) {
        context.dataStore.edit { it[KEY_BLUR_LOCK] = percent.coerceIn(0, 100) }
    }

    suspend fun setSelectedFilterLabels(labels: Set<String>) {
        context.dataStore.edit {
            it[KEY_SELECTED_FILTER_LABELS] = labels.joinToString(",")
        }
    }

    suspend fun setClassifiedFileIds(ids: Set<String>) {
        context.dataStore.edit {
            it[KEY_CLASSIFIED_FILE_IDS] = ids.joinToString(",")
        }
    }

    suspend fun setPhotoLabelsMap(map: Map<String, Set<String>>) {
        context.dataStore.edit {
            it[KEY_PHOTO_LABELS_MAP] = encodePhotoLabelsMap(map)
        }
    }

    suspend fun clearClassificationCache() {
        context.dataStore.edit {
            it.remove(KEY_CLASSIFIED_FILE_IDS)
            it.remove(KEY_PHOTO_LABELS_MAP)
        }
    }

    // ── Encoding helpers ─────────────────────────────────────────────

    private fun parsePhotoLabelsMap(raw: String?): Map<String, Set<String>> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = mutableMapOf<String, Set<String>>()
        for (line in raw.split("\n")) {
            val eqIndex = line.indexOf('=')
            if (eqIndex <= 0) continue
            val fileId = line.substring(0, eqIndex)
            val labels = line.substring(eqIndex + 1)
                .split("|")
                .filter { it.isNotEmpty() }
                .toSet()
            if (labels.isNotEmpty()) {
                result[fileId] = labels
            }
        }
        return result
    }

    private fun encodePhotoLabelsMap(map: Map<String, Set<String>>): String {
        return map.entries.joinToString("\n") { (fileId, labels) ->
            "$fileId=${labels.joinToString("|")}"
        }
    }
}
