package com.aichat.sandbox.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 1 to 2:
 * No schema changes in this migration — this establishes the safe migration
 * infrastructure. Future schema changes should be added as new Migration objects.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Version 2 has no schema changes from version 1.
        // This migration exists to move away from fallbackToDestructiveMigration()
        // and establish the safe migration pattern for all future changes.
    }
}
