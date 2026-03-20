package com.aichat.sandbox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageFts

@Database(
    entities = [Chat::class, Message::class, MessageFts::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
