package com.example.speechtotext

class ConversationManager {

    private val state = ConversationState()

    fun process(
        text: String,
        detectedIntent: String
    ): ConversationResponse {

        if (state.currentIntent == null) {

            when (detectedIntent.lowercase()) {

                "reminder" -> {
                    state.currentIntent = "reminder"
                    state.currentSlot = "title"

                    return ConversationResponse(
                        reply = "Aap kis cheez ka reminder set karna chahte hain?",
                        completed = false
                    )
                }

                "memory" -> {
                    state.currentIntent = "memory"
                    state.currentSlot = "memory_text"

                    return ConversationResponse(
                        reply = "Kaunsi memory save karni hai?",
                        completed = false
                    )
                }

                else -> {
                    return ConversationResponse(
                        reply = "Intent detected: $detectedIntent",
                        completed = true,
                        finalIntent = detectedIntent
                    )
                }
            }
        }

        return handleSlotInput(text)
    }

    private fun handleSlotInput(
        text: String
    ): ConversationResponse {

        when (state.currentIntent) {

            "reminder" -> {

                when (state.currentSlot) {

                    "title" -> {

                        state.slots["title"] = text
                        state.currentSlot = "time"

                        return ConversationResponse(
                            reply = "Reminder kab lagana hai?",
                            completed = false
                        )
                    }

                    "time" -> {

                        state.slots["time"] = text

                        val result = ConversationResponse(
                            reply = "Reminder create ho gaya.",
                            completed = true,
                            finalIntent = "reminder",
                            data = state.slots.toMap()
                        )

                        reset()
                        return result
                    }
                }
            }

            "memory" -> {

                state.slots["memory_text"] = text

                val result = ConversationResponse(
                    reply = "Memory save ho gayi.",
                    completed = true,
                    finalIntent = "memory",
                    data = state.slots.toMap()
                )

                reset()
                return result
            }
        }

        reset()

        return ConversationResponse(
            reply = "Unknown request",
            completed = true
        )
    }

    private fun reset() {
        state.currentIntent = null
        state.currentSlot = null
        state.slots.clear()
    }
}