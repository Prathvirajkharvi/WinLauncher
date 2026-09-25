package com.winlauncher.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winlauncher.app.data.db.entity.GameProfile
import com.winlauncher.app.data.repository.GameRepository
import com.winlauncher.app.domain.error.AppError
import com.winlauncher.app.domain.graphics.DirectXTarget
import com.winlauncher.app.domain.graphics.GraphicsBackendPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AddGameViewModel(private val gameRepository: GameRepository) : ViewModel() {

    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    fun save(
        name: String,
        executableUri: String,
        workingDirectoryUri: String?,
        iconUri: String?,
        directXTarget: DirectXTarget,
    ) {
        if (name.isBlank()) {
            _error.value = AppError.InvalidExecutable("Game name is empty")
            return
        }
        if (executableUri.isBlank()) {
            _error.value = AppError.InvalidExecutable("No executable selected")
            return
        }
        if (!executableUri.endsWith(".exe", ignoreCase = true)) {
            _error.value = AppError.InvalidExecutable("Selected file is not a .exe")
            return
        }

        viewModelScope.launch {
            gameRepository.save(
                GameProfile(
                    name = name,
                    executableUri = executableUri,
                    workingDirectoryUri = workingDirectoryUri,
                    iconUri = iconUri,
                    runtimeProfileId = null,
                    controllerProfileId = null,
                    graphicsBackendPreference = GraphicsBackendPreference.AUTO.name,
                    directXTarget = directXTarget.name,
                )
            )
            _saved.value = true
        }
    }
}
