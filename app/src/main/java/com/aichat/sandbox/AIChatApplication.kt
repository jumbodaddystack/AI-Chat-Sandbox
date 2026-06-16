package com.aichat.sandbox

import android.app.Application
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.notes.AiDebugLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AIChatApplication : Application() {

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var aiDebugLog: AiDebugLog

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Mirror the persisted "Capture AI debug log" setting into the runtime
        // gate the AI services check, so the choice survives restarts and flips
        // live when toggled.
        appScope.launch {
            preferencesManager.aiDebugLogEnabled
                .distinctUntilChanged()
                .collect { aiDebugLog.enabled = it }
        }
    }
}
