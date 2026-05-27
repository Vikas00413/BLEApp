package com.example.bleapp.data

import android.content.Context
import com.example.bleapp.data.local.BleAppDatabase
import com.example.bleapp.data.local.toChatMessage
import com.example.bleapp.data.local.toEntity
import com.example.bleapp.data.normalizeBleAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(context: Context) {

    private val dao = BleAppDatabase.get(context).chatMessageDao()

    fun observeMessages(peerAddress: String): Flow<List<ChatMessage>> =
        dao.observeByPeer(peerAddress.normalizeBleAddress()).map { entities ->
            entities.map { it.toChatMessage() }
        }

    suspend fun loadMessages(peerAddress: String): List<ChatMessage> =
        dao.getByPeer(peerAddress).map { it.toChatMessage() }

    suspend fun saveMessage(
        message: ChatMessage,
        peerAddress: String,
        peerName: String,
    ): ChatMessage {
        val id = dao.insert(message.toEntity(peerAddress.normalizeBleAddress(), peerName))
        return message.copy(id = id)
    }
}
