package com.example.bleapp.ui.chat

import com.example.bleapp.data.ChatMessage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed interface ChatListItem {
    val key: String

    data class DaySeparator(
        val label: String,
        val dayKey: String,
    ) : ChatListItem {
        override val key: String = "day_$dayKey"
    }

    data class MessageItem(
        val message: ChatMessage,
    ) : ChatListItem {
        override val key: String = "msg_${message.id}_${message.timestampMillis}"
    }
}

fun groupChatMessagesByDay(messages: List<ChatMessage>): List<ChatListItem> {
    if (messages.isEmpty()) return emptyList()
    val today = startOfDay(System.currentTimeMillis())
    val yesterday = today - DAY_MS
    val dayLabelFormat = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())
    val result = mutableListOf<ChatListItem>()
    var lastDayKey: String? = null
    for (message in messages) {
        val dayStart = startOfDay(message.timestampMillis)
        val dayKey = dayStart.toString()
        if (dayKey != lastDayKey) {
            val label = when (dayStart) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> dayLabelFormat.format(Date(dayStart))
            }
            result += ChatListItem.DaySeparator(label = label, dayKey = dayKey)
            lastDayKey = dayKey
        }
        result += ChatListItem.MessageItem(message = message)
    }
    return result
}

fun formatMessageTime(timestampMillis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMillis))

private const val DAY_MS = 24L * 60L * 60L * 1000L

private fun startOfDay(timestampMillis: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = timestampMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}
