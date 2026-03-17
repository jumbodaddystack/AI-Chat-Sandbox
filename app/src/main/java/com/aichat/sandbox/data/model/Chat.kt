package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "New chat",
    val model: String = "gpt-4o",
    val systemMessage: String = "",
    val temperature: Float = 0.1f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 131072,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    val totalTokens: Int = 0,
    val totalCost: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
