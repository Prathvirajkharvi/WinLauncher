package com.winlauncher.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winlauncher.app.data.db.entity.GameProfile
import com.winlauncher.app.data.db.entity.RuntimeProfile
import com.winlauncher.app.data.repository.GameRepository
import com.winlauncher.app.data.repository.RuntimeRepository
import com.winlauncher.app.domain.error.AppError
import com.winlauncher.app.domain.graphics.DirectXTarget
import com.winlauncher.app.domain.graphics.GpuDetector
import com.winlauncher.app.domain.graphics.GraphicsBackendPreference
import com.winlauncher.app.domain.graphics.GraphicsManager
import com.winlauncher.app.domain.graphics.GraphicsResolution
import com.winlauncher.app.domain.runtime.DummyRuntimeEngine
import com.winlauncher.app.domain.runtime.RuntimeEngine
import com.winlauncher.app.domain.runtime.RuntimeStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GameDetailsViewModel(
    private val gameRepository: GameRepository,
    private val runtimeRepository: RuntimeRepository,
    private val runtimeEngine: RuntimeEngine,
) : ViewModel() {

    private val _game = MutableStateFlow<GameProfile?>(null)
    val game: StateFlow<GameProfile?> = _game

    private val _graphicsResolution = MutableStateFlow<GraphicsResolution?>(null)
    val graphicsResolution: StateFlow<GraphicsResolution?> = _graphicsResolution

    private val _runtimeStatus = MutableStateFlow<RuntimeStatus>(RuntimeStatus.Idle)
    val runtimeStatus: StateFlow<RuntimeStatus> = _runtimeStatus

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error

    fun load(gameId: Long) {
        viewModelScope.launch {
            val loaded = gameRepository.getById(gameId)
            _game.value = loaded
            if (loaded != null) {
                val caps = GpuDetector.detect()
                _graphicsResolution.value = GraphicsManager.resolve(
                    caps = caps,
                    directXTarget = DirectXTarget.valueOf(loaded.directXTarget),
                    preference = GraphicsBackendPreference.valueOf(loaded.graphicsBackendPreference),
                )
            }
        }
    }

    fun play() {
        val currentGame = _game.value ?: return
        val resolution = _graphicsResolution.value
        if (resolution is GraphicsResolution.Unsupported) {
            _error.value = AppError.UnsupportedGraphicsConfig(resolution.reason)
            return
        }

        viewModelScope.launch {
            val runtimeProfileId = currentGame.runtimeProfileId
            if (runtimeProfileId == null) {
                _error.value = AppError.MissingRuntime
                return@launch
            }
            val runtimeProfile = runtimeRepository.getById(runtimeProfileId)
            if (runtimeProfile == null) {
                _error.value = AppError.MissingRuntime
                return@launch
            }

            val init = runtimeEngine.initialize(runtimeProfile)
            if (!init.ok) {
                _error.value = AppError.ProcessLaunchFailed(init.reason)
                return@launch
            }
            val validation = runtimeEngine.validate(currentGame, runtimeProfile)
            if (!validation.ok) {
                _error.value = AppError.ProcessLaunchFailed(validation.reason)
                return@launch
            }

            _runtimeStatus.value = runtimeEngine.launch(currentGame, runtimeProfile)
            gameRepository.markPlayed(currentGame.id)
            pollStatus()
        }
    }

    fun stop() {
        viewModelScope.launch { runtimeEngine.stop() }
    }

    private fun pollStatus() {
        viewModelScope.launch {
            while (true) {
                _runtimeStatus.value = runtimeEngine.getStatus()
                if (runtimeEngine is DummyRuntimeEngine) {
                    _logs.value = runtimeEngine.currentLogs()
                }
                if (_runtimeStatus.value is RuntimeStatus.Stopped || _runtimeStatus.value is RuntimeStatus.Failed) {
                    break
                }
                delay(500)
            }
        }
    }
}
