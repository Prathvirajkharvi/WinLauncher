package com.winlauncher.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.winlauncher.app.data.db.dao.ControllerProfileDao
import com.winlauncher.app.data.db.dao.GameProfileDao
import com.winlauncher.app.data.db.dao.RuntimeProfileDao
import com.winlauncher.app.data.db.entity.ControllerProfile
import com.winlauncher.app.data.db.entity.GameProfile
import com.winlauncher.app.data.db.entity.RuntimeProfile

@Database(
    entities = [GameProfile::class, RuntimeProfile::class, ControllerProfile::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun gameProfileDao(): GameProfileDao
    abstract fun runtimeProfileDao(): RuntimeProfileDao
    abstract fun controllerProfileDao(): ControllerProfileDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "winlauncher.db",
                ).build().also { instance = it }
            }
        }
    }
}
