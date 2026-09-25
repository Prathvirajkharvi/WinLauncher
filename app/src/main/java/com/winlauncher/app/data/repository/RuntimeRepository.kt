package com.winlauncher.app.data.repository

import com.winlauncher.app.data.db.dao.RuntimeProfileDao
import com.winlauncher.app.data.db.entity.RuntimeProfile
import kotlinx.coroutines.flow.Flow

class RuntimeRepository(private val dao: RuntimeProfileDao) {
    fun observeAll(): Flow<List<RuntimeProfile>> = dao.observeAll()
    suspend fun getById(id: Long): RuntimeProfile? = dao.getById(id)
    suspend fun save(profile: RuntimeProfile): Long = dao.upsert(profile)
    suspend fun delete(profile: RuntimeProfile) = dao.delete(profile)

    suspend fun clone(profile: RuntimeProfile, newName: String): Long {
        return dao.upsert(profile.copy(id = 0, name = newName))
    }
}
