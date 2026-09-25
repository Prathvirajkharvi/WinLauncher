package com.winlauncher.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winlauncher.app.data.db.entity.ControllerProfile
import com.winlauncher.app.data.repository.ControllerRepository
import com.winlauncher.app.domain.controller.ConnectedGamepad
import com.winlauncher.app.domain.controller.GamepadManager
import com.winlauncher.app.domain.controller.InputMapper
import com.winlauncher.app.domain.controller.XInputState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ControllerViewModel(
    private val controllerRepository: ControllerRepository,
    private val gamepadManager: GamepadManager,
    val inputMapper: InputMapper,
) : ViewModel() {

    val profiles: StateFlow<List<ControllerProfile>> = controllerRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedGamepads: StateFlow<List<ConnectedGamepad>> = gamepadManager.gamepads

    val liveState: StateFlow<XInputState> = inputMapper.state

    init {
        gamepadManager.start()
    }

    override fun onCleared() {
        super.onCleared()
        gamepadManager.stop()
    }

    fun save(profile: ControllerProfile) {
        viewModelScope.launch { controllerRepository.save(profile) }
    }

    fun delete(profile: ControllerProfile) {
        viewModelScope.launch { controllerRepository.delete(profile) }
    }
}
