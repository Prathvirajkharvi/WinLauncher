package com.winlauncher.app.data.db.dao

import androidx.room.*
import com.winlauncher.app.data.db.entity.GameProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface GameProfileDao {
    @Query("SELECT * FROM game_profiles ORDER BY lastPlayed DESC, createdAt DESC")
    fun observeAll(): Flow<List<GameProfile>>

    @Query("SELECT * FROM game_profiles WHERE id = :id")
    suspend fun getById(id: Long): GameProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(game: GameProfile): Long

    @Delete
    suspend fun delete(game: GameProfile)

    @Query("UPDATE game_profiles SET lastPlayed = :timestamp WHERE id = :id")
    suspend fun markPlayed(id: Long, timestamp: Long)

    @Query("SELECT * FROM game_profiles WHERE name LIKE '%' || :query || '%' ORDER BY name")
    fun search(query: String): Flow<List<GameProfile>>
}
