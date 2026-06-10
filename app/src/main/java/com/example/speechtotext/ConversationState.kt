package com.example.speechtotext

data class ConversationState(
    var currentIntent: String? = null,
    var currentSlot: String? = null,
    val slots: MutableMap<String, String> = mutableMapOf()
)
