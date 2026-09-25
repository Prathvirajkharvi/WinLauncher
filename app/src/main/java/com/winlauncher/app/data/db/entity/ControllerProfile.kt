package com.winlauncher.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "controller_profiles")
data class ControllerProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val physicalEnabled: Boolean = true,
    val touchEnabled: Boolean = true,
    val deadzone: Float = 0.15f,
    val sensitivity: Float = 1.0f,
    val touchOpacity: Float = 0.6f,
    // Reserved for future per-button remap customization; MVP ships Android's
    // standard XInput-style layout unmapped.
    val buttonRemapJson: String = "{}",
)
