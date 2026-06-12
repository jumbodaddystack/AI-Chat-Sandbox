package com.aichat.sandbox.ui.screens.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.UserTemplate
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.data.repository.UserTemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val userTemplateRepository: UserTemplateRepository,
) : ViewModel() {

    val notes: StateFlow<List<Note>> = repository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 14.3 — user-saved templates for the "New" menu's gallery section. */
    val userTemplates: StateFlow<List<UserTemplate>> = userTemplateRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // One-shot pass for notes saved before thumbnails existed (or whose
        // cached PNG was wiped out from under us). Cheap when nothing's
        // missing; the DAO query short-circuits to an empty list.
        viewModelScope.launch { repository.renderMissingThumbnails() }
    }

    fun delete(note: Note) {
        viewModelScope.launch { repository.deleteNote(note.id) }
    }

    fun deleteUserTemplate(templateId: String) {
        viewModelScope.launch { userTemplateRepository.delete(templateId) }
    }
}
