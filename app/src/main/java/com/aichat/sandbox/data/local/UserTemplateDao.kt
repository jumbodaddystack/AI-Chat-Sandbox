package com.aichat.sandbox.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aichat.sandbox.data.model.UserTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface UserTemplateDao {

    /** All user templates, most recently used (or created) first. */
    @Query("""
        SELECT * FROM user_templates
        ORDER BY COALESCE(lastUsedAt, createdAt) DESC
    """)
    fun observeAll(): Flow<List<UserTemplate>>

    @Query("SELECT * FROM user_templates WHERE id = :templateId")
    suspend fun get(templateId: String): UserTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: UserTemplate)

    @Query("UPDATE user_templates SET lastUsedAt = :timestamp WHERE id = :templateId")
    suspend fun touchLastUsed(templateId: String, timestamp: Long)

    @Query("DELETE FROM user_templates WHERE id = :templateId")
    suspend fun delete(templateId: String)
}
