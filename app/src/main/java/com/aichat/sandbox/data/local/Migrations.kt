package com.aichat.sandbox.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 1 to 2:
 * - Establishes safe migration infrastructure (replaces fallbackToDestructiveMigration)
 * - Creates FTS4 virtual table for full-text search on messages (1.5)
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create FTS4 virtual table for full-text search on messages
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts`
            USING FTS4(`content`, content=`messages`)
        """.trimIndent())
        // Populate FTS table with existing messages
        db.execSQL("""
            INSERT INTO messages_fts(messages_fts) VALUES('rebuild')
        """.trimIndent())
    }
}
