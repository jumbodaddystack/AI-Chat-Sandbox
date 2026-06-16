package com.aichat.sandbox.ui.screens.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.notes.AiDebugLog
import com.aichat.sandbox.data.notes.AiDebugTrace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridges the process-wide [AiDebugLog] ring buffer and the persisted
 * "Capture AI debug log" setting to Compose. Shared by the Settings toggle, the
 * full log screen, and the in-editor inline raw-reply view so they all observe
 * one source of truth.
 */
@HiltViewModel
class AiDebugLogViewModel @Inject constructor(
    private val aiDebugLog: AiDebugLog,
    private val preferencesManager: PreferencesManager,
) : ViewModel() {

    /** Captured exchanges, newest first. Empty until capture is enabled. */
    val traces: StateFlow<List<AiDebugTrace>> = aiDebugLog.traces

    /** Whether capture is currently on (mirrors the persisted setting). */
    val enabled: StateFlow<Boolean> = preferencesManager.aiDebugLogEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setEnabled(value: Boolean) {
        // Flip the runtime gate immediately so the very next exchange is caught
        // even before the persisted flow round-trips; startup re-syncs from disk.
        aiDebugLog.enabled = value
        viewModelScope.launch { preferencesManager.setAiDebugLogEnabled(value) }
    }

    fun clear() = aiDebugLog.clear()
}
