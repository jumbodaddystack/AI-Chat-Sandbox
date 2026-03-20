package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Chat::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class Message(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val chatId: String,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val tokenCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val contentType: String = "text", // "text" or "multimodal"
    val metadata: String? = null // JSON blob for image URIs, tool calls, etc.
)

enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system")
}
