package com.winlauncher.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winlauncher.app.data.db.entity.ControllerProfile
import com.winlauncher.app.data.repository.ControllerRepository
import com.winlauncher.app.domain.controller.ConnectedGamepad
import com.winlauncher.app.domain.controller.GamepadManager
import com.winlauncher.app.domain.controller.InputMapper
import com.winlauncher.app.domain.controller.XInputState
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _activeProfileId = MutableStateFlow<Long?>(null)
    val activeProfileId: StateFlow<Long?> = _activeProfileId

    init {
        gamepadManager.start()
    }

    override fun onCleared() {
        super.onCleared()
        gamepadManager.stop()
    }

    fun save(profile: ControllerProfile) {
        viewModelScope.launch {
            val savedId = controllerRepository.save(profile)
            // A brand new profile (id was 0) becomes active immediately once it has
            // a real id; an edited existing profile keeps its id unchanged.
            val resolvedId = if (profile.id != 0L) profile.id else savedId
            if (_activeProfileId.value == null) setActive(resolvedId, profile)
        }
    }

    fun delete(profile: ControllerProfile) {
        viewModelScope.launch {
            controllerRepository.delete(profile)
            if (_activeProfileId.value == profile.id) _activeProfileId.value = null
        }
    }

    /** Pushes this profile's deadzone/sensitivity into the shared InputMapper live. */
    fun setActive(id: Long, profile: ControllerProfile) {
        _activeProfileId.value = id
        inputMapper.applyProfile(deadzone = profile.deadzone, sensitivity = profile.sensitivity)
    }
}

