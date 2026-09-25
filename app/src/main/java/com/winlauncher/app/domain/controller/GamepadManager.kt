package com.winlauncher.app.domain.controller

import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ConnectedGamepad(
    val deviceId: Int,
    val name: String,
    val isBluetooth: Boolean,
    val isUsb: Boolean,
)

/**
 * Wraps InputManager's device-added/removed callbacks so the Controller
 * Manager screen can show a live list without polling.
 */
class GamepadManager(private val inputManager: InputManager) : InputManager.InputDeviceListener {

    private val _gamepads = MutableStateFlow<List<ConnectedGamepad>>(emptyList())
    val gamepads: StateFlow<List<ConnectedGamepad>> = _gamepads

    fun start() {
        inputManager.registerInputDeviceListener(this, null)
        refresh()
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(this)
    }

    private fun refresh() {
        val devices = inputManager.inputDeviceIds
            .toList()
            .mapNotNull { deviceId -> inputManager.getInputDevice(deviceId) }
            .filter { device -> InputMapper.isGamepadDevice(device) }
            .map { device -> device.toConnectedGamepad() }
        _gamepads.value = devices
    }

    private fun InputDevice.toConnectedGamepad(): ConnectedGamepad {
        // Android doesn't expose a direct "is Bluetooth" API on InputDevice;
        // descriptor/name heuristics are the best available signal without
        // reading raw HID reports. Good enough for a status label in the UI.
        val descriptorLower = (descriptor ?: "").lowercase()
        val nameLower = name.lowercase()
        val looksBluetooth = descriptorLower.contains("bluetooth") || nameLower.contains("wireless")
        return ConnectedGamepad(
            deviceId = id,
            name = name,
            isBluetooth = looksBluetooth,
            isUsb = !looksBluetooth,
        )
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()
}
