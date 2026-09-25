package com.winlauncher.app.viewmodel

import android.hardware.input.InputManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.winlauncher.app.LauncherApplication
import com.winlauncher.app.domain.controller.GamepadManager
import com.winlauncher.app.domain.controller.InputMapper

/**
 * One shared InputMapper for the whole app -- MainActivity forwards physical
 * gamepad key/motion events into it regardless of which screen is visible, and
 * the virtual touch overlay writes into the same instance (see architecture
 * doc section 9: physical and touch must feed one pipeline).
 */
object SharedInput {
    val inputMapper = InputMapper()
}

class AppViewModelFactory(private val app: LauncherApplication) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return when (modelClass) {
            LibraryViewModel::class.java -> LibraryViewModel(app.gameRepository) as T
            AddGameViewModel::class.java -> AddGameViewModel(app.gameRepository) as T
            GameDetailsViewModel::class.java -> GameDetailsViewModel(
                app.gameRepository, app.runtimeRepository, app.runtimeEngine,
            ) as T
            RuntimeViewModel::class.java -> RuntimeViewModel(app.runtimeRepository) as T
            ControllerViewModel::class.java -> {
                val inputManager = app.getSystemService(InputManager::class.java)
                ControllerViewModel(
                    app.controllerRepository,
                    GamepadManager(inputManager),
                    SharedInput.inputMapper,
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
