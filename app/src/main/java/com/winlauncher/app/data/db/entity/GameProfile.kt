package com.winlauncher.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_profiles")
data class GameProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,

    // SAF content:// URIs, never raw filesystem paths -- persisted permission
    // is taken when the user picks these in AddGameScreen.
    val executableUri: String,
    val workingDirectoryUri: String?,
    val iconUri: String?,

    val runtimeProfileId: Long?,
    val controllerProfileId: Long?,

    val graphicsBackendPreference: String, // GraphicsBackendPreference.name
    val directXTarget: String,             // DirectXTarget.name
    val resolutionWidth: Int = 1280,
    val resolutionHeight: Int = 720,
    val fpsCap: Int = 30,
    val fullscreen: Boolean = true,

    val launchArguments: String = "",
    val environmentVariables: String = "", // "KEY=VALUE" pairs, one per line

    val createdAt: Long = System.currentTimeMillis(),
    val lastPlayed: Long? = null,
)
