package com.winlauncher.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CpuBackend { BOX64, BOX86, NATIVE_ARM }

@Entity(tableName = "runtime_profiles")
data class RuntimeProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,

    // Path under the app's private storage (Context.filesDir), e.g. "prefixes/default".
    // Never a raw external path -- Wine prefixes live in app-private space.
    val winePrefixRelativePath: String,

    val cpuBackend: String = CpuBackend.BOX64.name,
    val launchArguments: String = "",
    val environmentVariables: String = "",
)
