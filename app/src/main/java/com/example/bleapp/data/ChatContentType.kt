package com.example.bleapp.data

enum class ChatContentType {
    TEXT,
    LOCATION,
    LINK,
    PHOTO,
    VIDEO,
    ;

    companion object {
        fun fromStored(value: String): ChatContentType =
            entries.find { it.name == value } ?: TEXT
    }
}
