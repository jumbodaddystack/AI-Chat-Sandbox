package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.local.UserTemplateDao
import com.aichat.sandbox.data.model.UserTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 14.3 — repository for user-saved note templates. Pure DB
 * (no files, unlike [StampRepository] — templates carry no thumbnail).
 */
@Singleton
class UserTemplateRepository @Inject constructor(
    private val dao: UserTemplateDao,
) {

    fun observeAll(): Flow<List<UserTemplate>> = dao.observeAll()

    suspend fun get(templateId: String): UserTemplate? = withContext(Dispatchers.IO) {
        dao.get(templateId)
    }

    suspend fun save(name: String, payloadJson: String): UserTemplate =
        withContext(Dispatchers.IO) {
            val template = UserTemplate(
                name = name.ifBlank { "Template" },
                payloadJson = payloadJson,
            )
            dao.upsert(template)
            template
        }

    suspend fun delete(templateId: String) = withContext(Dispatchers.IO) {
        dao.delete(templateId)
    }

    suspend fun touchLastUsed(templateId: String) = withContext(Dispatchers.IO) {
        dao.touchLastUsed(templateId, System.currentTimeMillis())
    }
}
