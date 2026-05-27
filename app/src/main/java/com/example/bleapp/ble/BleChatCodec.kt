package com.example.bleapp.ble

import com.example.bleapp.data.ChatContentType
import com.example.bleapp.data.ChatMessage
import com.example.bleapp.data.ChatPacket
import java.nio.charset.Charset

object BleChatCodec {

    private val utf8: Charset = Charsets.UTF_8
    private const val TYPE_MESSAGE: Byte = 1
    private const val TYPE_TYPING: Byte = 2
    private const val TYPE_RICH: Byte = 3
    private const val RICH_LOCATION: Byte = 1
    private const val RICH_LINK: Byte = 2
    private const val RICH_PHOTO: Byte = 3
    private const val RICH_VIDEO: Byte = 4
    private const val MAX_TYPING_PREVIEW_CHARS = 48

    fun encodeMessage(message: ChatMessage): ByteArray = when (message.contentType) {
        ChatContentType.TEXT -> encodePlainText(message.text)
        ChatContentType.LOCATION -> encodeRich(
            RICH_LOCATION,
            "${message.latitude ?: 0.0},${message.longitude ?: 0.0}|${message.text}",
        )
        ChatContentType.LINK -> encodeRich(RICH_LINK, "${message.linkUrl.orEmpty()}|${message.text}")
        ChatContentType.PHOTO -> encodeRich(RICH_PHOTO, message.text) // legacy caption-only
        ChatContentType.VIDEO -> encodeRich(RICH_VIDEO, message.text)
    }

    fun encodePlainText(text: String): ByteArray {
        val body = text.toByteArray(utf8)
        val maxBytes = BleConstants.maxChatBytes()
        require(1 + body.size <= maxBytes) {
            "Message too long (max ${maxBytes - 1} bytes approx)"
        }
        return byteArrayOf(TYPE_MESSAGE) + body
    }

    fun encodeTyping(isTyping: Boolean, preview: String = ""): ByteArray {
        val safePreview = preview.take(MAX_TYPING_PREVIEW_CHARS).toByteArray(utf8)
        require(2 + safePreview.size <= BleConstants.maxChatBytes()) {
            "Typing preview too long"
        }
        return byteArrayOf(TYPE_TYPING, if (isTyping) 1 else 0) + safePreview
    }

    private fun encodeRich(subtype: Byte, payload: String): ByteArray {
        val body = payload.toByteArray(utf8)
        require(2 + body.size <= BleConstants.maxChatBytes()) {
            "Shared content too large for BLE"
        }
        return byteArrayOf(TYPE_RICH, subtype) + body
    }

    fun decode(bytes: ByteArray?): ChatPacket? {
        if (bytes == null || bytes.isEmpty()) return null
        BleImageTransfer.decodeChunk(bytes)?.let { return it }
        return when (bytes[0]) {
            TYPE_MESSAGE -> {
                val text = bytes.copyOfRange(1, bytes.size).toString(utf8)
                if (text.isEmpty()) null else ChatPacket.Content(text = text)
            }
            TYPE_TYPING -> {
                val isTyping = bytes.getOrElse(1) { 0 } == 1.toByte()
                val preview = if (bytes.size > 2) {
                    bytes.copyOfRange(2, bytes.size).toString(utf8)
                } else {
                    ""
                }
                ChatPacket.Typing(isTyping = isTyping, preview = preview)
            }
            TYPE_RICH -> decodeRich(bytes)
            else -> ChatPacket.Content(text = bytes.toString(utf8))
        }
    }

    private fun decodeRich(bytes: ByteArray): ChatPacket? {
        if (bytes.size < 2) return null
        val payload = bytes.copyOfRange(2, bytes.size).toString(utf8)
        return when (bytes[1]) {
            RICH_LOCATION -> {
                val parts = payload.split("|", limit = 2)
                val coords = parts[0].split(",")
                val lat = coords.getOrNull(0)?.toDoubleOrNull() ?: return null
                val lng = coords.getOrNull(1)?.toDoubleOrNull() ?: return null
                val label = parts.getOrNull(1).orEmpty().ifBlank { "Shared location" }
                ChatPacket.Content(
                    text = label,
                    contentType = ChatContentType.LOCATION,
                    latitude = lat,
                    longitude = lng,
                )
            }
            RICH_LINK -> {
                val parts = payload.split("|", limit = 2)
                val url = parts.getOrNull(0).orEmpty()
                if (url.isBlank()) return null
                ChatPacket.Content(
                    text = parts.getOrNull(1).orEmpty().ifBlank { url },
                    contentType = ChatContentType.LINK,
                    linkUrl = url,
                )
            }
            RICH_PHOTO -> ChatPacket.Content(
                text = payload.ifBlank { "Photo" },
                contentType = ChatContentType.PHOTO,
            )
            RICH_VIDEO -> ChatPacket.Content(
                text = payload.ifBlank { "Video" },
                contentType = ChatContentType.VIDEO,
            )
            else -> null
        }
    }
}
