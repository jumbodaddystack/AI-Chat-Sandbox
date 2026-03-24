package com.aichat.sandbox.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.model.ApiProvider
import com.aichat.sandbox.data.model.ChatSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String = "",
    val apiBaseUrl: String = ChatSettings.Defaults.API_BASE_URL,
    val apiBaseUrlError: String? = null,
    val defaultModel: String = ChatSettings.Defaults.MODEL,
    val defaultTemperature: Float = ChatSettings.Defaults.TEMPERATURE,
    val defaultTopP: Float = ChatSettings.Defaults.TOP_P,
    val defaultMaxTokens: Int = ChatSettings.Defaults.MAX_TOKENS,
    val defaultPresencePenalty: Float = ChatSettings.Defaults.PRESENCE_PENALTY,
    val defaultFrequencyPenalty: Float = ChatSettings.Defaults.FREQUENCY_PENALTY,
    val darkMode: Boolean = ChatSettings.Defaults.DARK_MODE,
    val customModels: Map<String, List<String>> = emptyMap()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                preferencesManager.apiKey,
                preferencesManager.apiBaseUrl,
                preferencesManager.defaultModel,
                preferencesManager.defaultTemperature,
                preferencesManager.defaultTopP
            ) { apiKey, baseUrl, model, temp, topP ->
                _uiState.update {
                    it.copy(
                        apiKey = apiKey,
                        apiBaseUrl = baseUrl,
                        defaultModel = model,
                        defaultTemperature = temp,
                        defaultTopP = topP
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            preferencesManager.darkMode.collect { darkMode ->
                _uiState.update { it.copy(darkMode = darkMode) }
            }
        }
        viewModelScope.launch {
            combine(
                preferencesManager.defaultMaxTokens,
                preferencesManager.defaultPresencePenalty,
                preferencesManager.defaultFrequencyPenalty
            ) { maxTokens, presPenalty, freqPenalty ->
                _uiState.update {
                    it.copy(
                        defaultMaxTokens = maxTokens,
                        defaultPresencePenalty = presPenalty,
                        defaultFrequencyPenalty = freqPenalty
                    )
                }
            }.collect()
        }
        viewModelScope.launch {
            preferencesManager.customModels.collect { customModels ->
                _uiState.update { it.copy(customModels = customModels) }
            }
        }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { preferencesManager.setApiKey(key) }
    }

    fun setApiBaseUrl(url: String) {
        _uiState.update { it.copy(apiBaseUrl = url) }
        if (PreferencesManager.isValidApiBaseUrl(url)) {
            _uiState.update { it.copy(apiBaseUrlError = null) }
            viewModelScope.launch { preferencesManager.setApiBaseUrl(url) }
        } else if (url.isNotEmpty()) {
            _uiState.update { it.copy(apiBaseUrlError = "URL must be HTTPS, valid, and end with /") }
        }
    }

    fun setDefaultModel(model: String) {
        viewModelScope.launch { preferencesManager.setDefaultModel(model) }
    }

    fun setDefaultTemperature(temp: Float) {
        viewModelScope.launch { preferencesManager.setDefaultTemperature(temp) }
    }

    fun setDefaultTopP(topP: Float) {
        viewModelScope.launch { preferencesManager.setDefaultTopP(topP) }
    }

    fun setDefaultMaxTokens(tokens: Int) {
        viewModelScope.launch { preferencesManager.setDefaultMaxTokens(tokens) }
    }

    fun setDefaultPresencePenalty(penalty: Float) {
        viewModelScope.launch { preferencesManager.setDefaultPresencePenalty(penalty) }
    }

    fun setDefaultFrequencyPenalty(penalty: Float) {
        viewModelScope.launch { preferencesManager.setDefaultFrequencyPenalty(penalty) }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setDarkMode(enabled) }
    }

    fun addCustomModel(model: String) {
        // Determine provider from the current base URL
        val provider = detectProvider(uiState.value.apiBaseUrl)
        viewModelScope.launch { preferencesManager.addCustomModel(provider, model) }
    }

    fun removeCustomModel(model: String) {
        val provider = detectProvider(uiState.value.apiBaseUrl)
        viewModelScope.launch { preferencesManager.removeCustomModel(provider, model) }
    }

    private fun detectProvider(baseUrl: String): String {
        return when {
            baseUrl.contains("openai.com") -> "OpenAI"
            baseUrl.contains("anthropic.com") -> "Anthropic"
            baseUrl.contains("googleapis.com") || baseUrl.contains("google") -> "Google"
            else -> "Custom"
        }
    }

    fun getAllModels(): List<String> {
        val builtIn = ApiProvider.defaults.flatMap { it.models }
        val custom = uiState.value.customModels.values.flatten()
        return builtIn + custom.filter { it !in builtIn }
    }

    fun getCustomModelsFlat(): List<String> {
        return uiState.value.customModels.values.flatten()
    }
}
