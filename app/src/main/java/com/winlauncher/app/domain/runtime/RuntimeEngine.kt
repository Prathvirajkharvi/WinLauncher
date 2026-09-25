package com.winlauncher.app.domain.runtime

import com.winlauncher.app.data.db.entity.GameProfile
import com.winlauncher.app.data.db.entity.RuntimeProfile
import com.winlauncher.app.domain.error.AppError

sealed class RuntimeStatus {
    object Idle : RuntimeStatus()
    object Initializing : RuntimeStatus()
    object Running : RuntimeStatus()
    data class Stopped(val exitCode: Int) : RuntimeStatus()
    data class Failed(val error: AppError) : RuntimeStatus()
}

data class ValidationResult(val ok: Boolean, val reason: String)

/**
 * Everything above this interface (UI, GameManager, RuntimeManager) is real.
 * The concrete Wine + Box64 + DXVK/VKD3D implementation is NOT part of this MVP --
 * DummyRuntimeEngine below stands in for it so the full launch pipeline
 * (validate -> initialize -> launch -> monitor -> stop) is provably wired end to
 * end. Swapping in the real engine means implementing this interface again; no
 * other layer should need to change.
 */
interface RuntimeEngine {
    suspend fun initialize(runtimeProfile: RuntimeProfile): ValidationResult
    suspend fun validate(game: GameProfile, runtimeProfile: RuntimeProfile): ValidationResult
    suspend fun launch(game: GameProfile, runtimeProfile: RuntimeProfile): RuntimeStatus
    suspend fun stop()
    fun getStatus(): RuntimeStatus
}
