package dev.mimir.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.themeDataStore by preferencesDataStore(name = "mimir_theme")

class ThemeStore(private val context: Context) {
    private val key = stringPreferencesKey("theme_config_json")
    private val json = Json { ignoreUnknownKeys = true }

    val config: Flow<ThemeConfig> = context.themeDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<ThemeConfig>(it) }.getOrNull() } ?: ThemeConfig()
    }

    suspend fun update(transform: (ThemeConfig) -> ThemeConfig) {
        context.themeDataStore.edit { prefs ->
            val current = prefs[key]?.let { runCatching { json.decodeFromString<ThemeConfig>(it) }.getOrNull() } ?: ThemeConfig()
            prefs[key] = json.encodeToString(ThemeConfig.serializer(), transform(current))
        }
    }
}
