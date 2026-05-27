package com.example.bleapp.data

data class ChatMessage(
    val id: Long = 0L,
    val text: String,
    val contentType: ChatContentType = ChatContentType.TEXT,
    val mediaUri: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val linkUrl: String? = null,
    val isMine: Boolean,
    val timestampMillis: Long = System.currentTimeMillis(),
)
