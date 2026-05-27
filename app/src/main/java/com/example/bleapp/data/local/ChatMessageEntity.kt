package com.example.bleapp.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.bleapp.data.ChatContentType
import com.example.bleapp.data.ChatMessage

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["peerAddress", "timestampMillis"])],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val peerAddress: String,
    val peerName: String,
    val text: String,
    val contentType: String,
    val mediaUri: String?,
    val latitude: Double?,
    val longitude: Double?,
    val linkUrl: String?,
    val isMine: Boolean,
    val timestampMillis: Long,
)

fun ChatMessageEntity.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    text = text,
    contentType = ChatContentType.fromStored(contentType),
    mediaUri = mediaUri,
    latitude = latitude,
    longitude = longitude,
    linkUrl = linkUrl,
    isMine = isMine,
    timestampMillis = timestampMillis,
)

fun ChatMessage.toEntity(peerAddress: String, peerName: String): ChatMessageEntity = ChatMessageEntity(
    id = id,
    peerAddress = peerAddress,
    peerName = peerName,
    text = text,
    contentType = contentType.name,
    mediaUri = mediaUri,
    latitude = latitude,
    longitude = longitude,
    linkUrl = linkUrl,
    isMine = isMine,
    timestampMillis = timestampMillis,
)
