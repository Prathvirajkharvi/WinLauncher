package com.winlauncher.app.data.db.dao

import androidx.room.*
import com.winlauncher.app.data.db.entity.RuntimeProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface RuntimeProfileDao {
    @Query("SELECT * FROM runtime_profiles ORDER BY name")
    fun observeAll(): Flow<List<RuntimeProfile>>

    @Query("SELECT * FROM runtime_profiles WHERE id = :id")
    suspend fun getById(id: Long): RuntimeProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: RuntimeProfile): Long

    @Delete
    suspend fun delete(profile: RuntimeProfile)
}
