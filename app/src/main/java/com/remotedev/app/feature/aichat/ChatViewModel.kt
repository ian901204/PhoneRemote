package com.remotedev.app.feature.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: AiRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return

        val conversation = _messages.value + ChatMessage(role = "user", content = trimmed)
        _messages.value = conversation + ChatMessage(role = "assistant", content = "")
        _isGenerating.value = true

        viewModelScope.launch {
            repo.chatStream(conversation)
                .catch { e -> appendToLastAssistant("錯誤：${e.message ?: e.toString()}") }
                .onCompletion { _isGenerating.value = false }
                .collect { token -> appendToLastAssistant(token) }
        }
    }

    private fun appendToLastAssistant(token: String) {
        val list = _messages.value
        if (list.isEmpty()) return
        val last = list.last()
        _messages.value = list.dropLast(1) + last.copy(content = last.content + token)
    }

    fun clear() {
        if (_isGenerating.value) return
        _messages.value = emptyList()
    }
}
