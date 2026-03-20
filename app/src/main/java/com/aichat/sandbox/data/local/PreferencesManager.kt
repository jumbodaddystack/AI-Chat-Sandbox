package com.aichat.sandbox.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.aichat.sandbox.data.model.ChatSettings
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        private const val TAG = "PreferencesManager"

        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_TEMPERATURE = floatPreferencesKey("default_temperature")
        val DEFAULT_TOP_P = floatPreferencesKey("default_top_p")
        val DEFAULT_MAX_TOKENS = intPreferencesKey("default_max_tokens")
        val DEFAULT_PRESENCE_PENALTY = floatPreferencesKey("default_presence_penalty")
        val DEFAULT_FREQUENCY_PENALTY = floatPreferencesKey("default_frequency_penalty")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val AUTO_GENERATE_TITLES = booleanPreferencesKey("auto_generate_titles")

        fun isValidApiBaseUrl(url: String): Boolean {
            return try {
                val uri = URI(url)
                val scheme = uri.scheme?.lowercase()
                val isLocalhost = uri.host?.let {
                    it == "localhost" || it == "127.0.0.1" || it == "10.0.2.2"
                } ?: false
                (scheme == "https" || (scheme == "http" && isLocalhost)) &&
                    uri.host != null &&
                    url.endsWith("/")
            } catch (e: Exception) {
                false
            }
        }
    }

    val apiKey: Flow<String> = dataStore.data.map { it[API_KEY] ?: "" }
    val apiBaseUrl: Flow<String> = dataStore.data.map { it[API_BASE_URL] ?: ChatSettings.Defaults.API_BASE_URL }
    val defaultModel: Flow<String> = dataStore.data.map { it[DEFAULT_MODEL] ?: ChatSettings.Defaults.MODEL }
    val defaultTemperature: Flow<Float> = dataStore.data.map { it[DEFAULT_TEMPERATURE] ?: ChatSettings.Defaults.TEMPERATURE }
    val defaultTopP: Flow<Float> = dataStore.data.map { it[DEFAULT_TOP_P] ?: ChatSettings.Defaults.TOP_P }
    val defaultMaxTokens: Flow<Int> = dataStore.data.map { it[DEFAULT_MAX_TOKENS] ?: ChatSettings.Defaults.MAX_TOKENS }
    val defaultPresencePenalty: Flow<Float> = dataStore.data.map { it[DEFAULT_PRESENCE_PENALTY] ?: ChatSettings.Defaults.PRESENCE_PENALTY }
    val defaultFrequencyPenalty: Flow<Float> = dataStore.data.map { it[DEFAULT_FREQUENCY_PENALTY] ?: ChatSettings.Defaults.FREQUENCY_PENALTY }
    val darkMode: Flow<Boolean> = dataStore.data.map { it[DARK_MODE] ?: ChatSettings.Defaults.DARK_MODE }
    val autoGenerateTitles: Flow<Boolean> = dataStore.data.map { it[AUTO_GENERATE_TITLES] ?: true }

    suspend fun setApiKey(key: String) {
        try {
            dataStore.edit { it[API_KEY] = key }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API key", e)
        }
    }

    suspend fun setApiBaseUrl(url: String): Boolean {
        if (!isValidApiBaseUrl(url)) return false
        return try {
            dataStore.edit { it[API_BASE_URL] = url }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save API base URL", e)
            false
        }
    }

    suspend fun setDefaultModel(model: String) {
        try {
            dataStore.edit { it[DEFAULT_MODEL] = model }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default model", e)
        }
    }

    suspend fun setDefaultTemperature(temp: Float) {
        try {
            dataStore.edit { it[DEFAULT_TEMPERATURE] = temp }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default temperature", e)
        }
    }

    suspend fun setDefaultTopP(topP: Float) {
        try {
            dataStore.edit { it[DEFAULT_TOP_P] = topP }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default top_p", e)
        }
    }

    suspend fun setDefaultMaxTokens(tokens: Int) {
        try {
            dataStore.edit { it[DEFAULT_MAX_TOKENS] = tokens }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default max tokens", e)
        }
    }

    suspend fun setDefaultPresencePenalty(penalty: Float) {
        try {
            dataStore.edit { it[DEFAULT_PRESENCE_PENALTY] = penalty }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default presence penalty", e)
        }
    }

    suspend fun setDefaultFrequencyPenalty(penalty: Float) {
        try {
            dataStore.edit { it[DEFAULT_FREQUENCY_PENALTY] = penalty }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default frequency penalty", e)
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        try {
            dataStore.edit { it[DARK_MODE] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save dark mode preference", e)
        }
    }

    suspend fun setAutoGenerateTitles(enabled: Boolean) {
        try {
            dataStore.edit { it[AUTO_GENERATE_TITLES] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save auto-generate titles preference", e)
        }
    }
}
