package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = Message::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val content: String
)
