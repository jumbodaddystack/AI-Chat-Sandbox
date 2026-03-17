package com.aichat.sandbox.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val DEFAULT_MODEL = stringPreferencesKey("default_model")
        val DEFAULT_TEMPERATURE = floatPreferencesKey("default_temperature")
        val DEFAULT_TOP_P = floatPreferencesKey("default_top_p")
        val DEFAULT_MAX_TOKENS = intPreferencesKey("default_max_tokens")
        val DEFAULT_PRESENCE_PENALTY = floatPreferencesKey("default_presence_penalty")
        val DEFAULT_FREQUENCY_PENALTY = floatPreferencesKey("default_frequency_penalty")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
    }

    val apiKey: Flow<String> = dataStore.data.map { it[API_KEY] ?: "" }
    val apiBaseUrl: Flow<String> = dataStore.data.map { it[API_BASE_URL] ?: "https://api.openai.com/v1/" }
    val defaultModel: Flow<String> = dataStore.data.map { it[DEFAULT_MODEL] ?: "gpt-4o" }
    val defaultTemperature: Flow<Float> = dataStore.data.map { it[DEFAULT_TEMPERATURE] ?: 0.1f }
    val defaultTopP: Flow<Float> = dataStore.data.map { it[DEFAULT_TOP_P] ?: 1.0f }
    val defaultMaxTokens: Flow<Int> = dataStore.data.map { it[DEFAULT_MAX_TOKENS] ?: 131072 }
    val defaultPresencePenalty: Flow<Float> = dataStore.data.map { it[DEFAULT_PRESENCE_PENALTY] ?: 0.0f }
    val defaultFrequencyPenalty: Flow<Float> = dataStore.data.map { it[DEFAULT_FREQUENCY_PENALTY] ?: 0.0f }
    val darkMode: Flow<Boolean> = dataStore.data.map { it[DARK_MODE] ?: true }

    suspend fun setApiKey(key: String) {
        dataStore.edit { it[API_KEY] = key }
    }

    suspend fun setApiBaseUrl(url: String) {
        dataStore.edit { it[API_BASE_URL] = url }
    }

    suspend fun setDefaultModel(model: String) {
        dataStore.edit { it[DEFAULT_MODEL] = model }
    }

    suspend fun setDefaultTemperature(temp: Float) {
        dataStore.edit { it[DEFAULT_TEMPERATURE] = temp }
    }

    suspend fun setDefaultTopP(topP: Float) {
        dataStore.edit { it[DEFAULT_TOP_P] = topP }
    }

    suspend fun setDefaultMaxTokens(tokens: Int) {
        dataStore.edit { it[DEFAULT_MAX_TOKENS] = tokens }
    }

    suspend fun setDefaultPresencePenalty(penalty: Float) {
        dataStore.edit { it[DEFAULT_PRESENCE_PENALTY] = penalty }
    }

    suspend fun setDefaultFrequencyPenalty(penalty: Float) {
        dataStore.edit { it[DEFAULT_FREQUENCY_PENALTY] = penalty }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { it[DARK_MODE] = enabled }
    }
}
