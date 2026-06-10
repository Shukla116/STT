package com.example.speechtotext

data class ConversationResponse(
    val reply: String,
    val completed: Boolean,
    val finalIntent: String? = null,
    val data: Map<String, String> = emptyMap()
)
