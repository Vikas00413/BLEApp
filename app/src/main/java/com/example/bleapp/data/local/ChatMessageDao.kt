package com.example.bleapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE peerAddress = :peerAddress
        ORDER BY timestampMillis ASC
        """,
    )
    fun observeByPeer(peerAddress: String): Flow<List<ChatMessageEntity>>

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE peerAddress = :peerAddress
        ORDER BY timestampMillis ASC
        """,
    )
    suspend fun getByPeer(peerAddress: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: ChatMessageEntity): Long
}
