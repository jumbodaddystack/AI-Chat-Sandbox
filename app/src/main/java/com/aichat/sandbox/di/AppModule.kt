package com.aichat.sandbox.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aichat.sandbox.data.local.AppDatabase
import com.aichat.sandbox.data.local.ChatDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ai_chat_sandbox.db"
        ).addMigrations(MIGRATION_1_2)
         .fallbackToDestructiveMigration()
         .build()
    }

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }
}
