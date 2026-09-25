package com.winlauncher.app.data.repository

import com.winlauncher.app.data.db.dao.ControllerProfileDao
import com.winlauncher.app.data.db.entity.ControllerProfile
import kotlinx.coroutines.flow.Flow

class ControllerRepository(private val dao: ControllerProfileDao) {
    fun observeAll(): Flow<List<ControllerProfile>> = dao.observeAll()
    suspend fun getById(id: Long): ControllerProfile? = dao.getById(id)
    suspend fun save(profile: ControllerProfile): Long = dao.upsert(profile)
    suspend fun delete(profile: ControllerProfile) = dao.delete(profile)
}
