package com.aichat.sandbox.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String = "",
    val apiBaseUrl: String = "https://api.openai.com/v1/",
    val defaultModel: String = "gpt-4o",
    val defaultTemperature: Float = 0.1f,
    val defaultTopP: Float = 1.0f,
    val defaultMaxTokens: Int = 131072,
    val defaultPresencePenalty: Float = 0.0f,
    val defaultFrequencyPenalty: Float = 0.0f
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
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { preferencesManager.setApiKey(key) }
    }

    fun setApiBaseUrl(url: String) {
        viewModelScope.launch { preferencesManager.setApiBaseUrl(url) }
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
}
