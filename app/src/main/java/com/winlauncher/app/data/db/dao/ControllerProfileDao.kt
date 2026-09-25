package com.winlauncher.app.data.db.dao

import androidx.room.*
import com.winlauncher.app.data.db.entity.ControllerProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ControllerProfileDao {
    @Query("SELECT * FROM controller_profiles ORDER BY name")
    fun observeAll(): Flow<List<ControllerProfile>>

    @Query("SELECT * FROM controller_profiles WHERE id = :id")
    suspend fun getById(id: Long): ControllerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ControllerProfile): Long

    @Delete
    suspend fun delete(profile: ControllerProfile)
}
