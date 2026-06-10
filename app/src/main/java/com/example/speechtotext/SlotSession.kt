package com.example.speechtotext

data class SlotSession(
    val intentName: String,
    val requiredSlots: List<SlotConfig>,
    val filledSlots: HashMap<String, String> = HashMap(),
    var currentSlotIndex: Int = 0
)
data class SlotConfig(
    val name: String,
    val entity: String,
    val required: Boolean,
    val prompt: String
)
