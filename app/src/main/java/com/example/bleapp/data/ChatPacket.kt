package com.example.bleapp.data

sealed interface ChatPacket {
    data class Content(
        val text: String,
        val contentType: ChatContentType = ChatContentType.TEXT,
        val linkUrl: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    ) : ChatPacket

    data class Typing(
        val isTyping: Boolean,
        val preview: String = "",
    ) : ChatPacket

    /** JPEG fragment for reassembly on the peer device. */
    data class ImageChunk(
        val transferId: Int,
        val index: Int,
        val totalChunks: Int,
        val data: ByteArray,
    ) : ChatPacket
}
