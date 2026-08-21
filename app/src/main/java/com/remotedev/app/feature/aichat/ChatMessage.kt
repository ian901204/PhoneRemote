package com.remotedev.app.feature.aichat

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)
