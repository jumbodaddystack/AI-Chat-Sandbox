package com.aichat.sandbox.di

import android.content.Context
import androidx.room.Room
import com.aichat.sandbox.data.local.AppDatabase
import com.aichat.sandbox.data.local.ChatDao
import com.aichat.sandbox.data.local.MIGRATION_1_2
import com.aichat.sandbox.data.local.MIGRATION_2_3
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ai_chat_sandbox.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }
}
