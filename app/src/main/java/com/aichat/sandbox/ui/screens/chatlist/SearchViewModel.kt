package com.aichat.sandbox.ui.screens.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.local.ChatDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchResult(
    val chat: Chat,
    val matchingMessages: List<Message>
)

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val chatDao: ChatDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String) {
        _uiState.update { it.copy(query = query) }

        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            try {
                // FTS4 query: escape special characters and append wildcard
                val ftsQuery = query.trim().replace("\"", "\"\"") + "*"
                val matchingMessages = chatDao.searchMessages(ftsQuery)
                val matchingChats = chatDao.searchChats(ftsQuery)

                // Group messages by chat
                val messagesByChatId = matchingMessages.groupBy { it.chatId }

                // Build results: matched chats with their matching messages
                val results = matchingChats.map { chat ->
                    SearchResult(
                        chat = chat,
                        matchingMessages = messagesByChatId[chat.id] ?: emptyList()
                    )
                }

                _uiState.update { it.copy(results = results, isSearching = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { SearchUiState() }
    }
}
