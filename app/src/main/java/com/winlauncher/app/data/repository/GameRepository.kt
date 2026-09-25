package com.winlauncher.app.data.repository

import com.winlauncher.app.data.db.dao.GameProfileDao
import com.winlauncher.app.data.db.entity.GameProfile
import kotlinx.coroutines.flow.Flow

class GameRepository(private val dao: GameProfileDao) {
    fun observeAll(): Flow<List<GameProfile>> = dao.observeAll()
    fun search(query: String): Flow<List<GameProfile>> = dao.search(query)
    suspend fun getById(id: Long): GameProfile? = dao.getById(id)
    suspend fun save(game: GameProfile): Long = dao.upsert(game)
    suspend fun delete(game: GameProfile) = dao.delete(game)
    suspend fun markPlayed(id: Long) = dao.markPlayed(id, System.currentTimeMillis())
}
