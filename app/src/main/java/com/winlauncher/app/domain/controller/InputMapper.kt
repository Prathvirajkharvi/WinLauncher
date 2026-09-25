package com.winlauncher.app.domain.controller

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single funnel for controller input. GamepadManager (physical) and the
 * Compose TouchOverlay (virtual) both call into this same instance so
 * downstream consumers (InputBridge) never know or care where an input came
 * from.
 */
class InputMapper(private val deadzone: Float = 0.15f) {

    private val _state = MutableStateFlow(XInputState())
    val state: StateFlow<XInputState> = _state

    fun currentState(): XInputState = _state.value

    // --- Physical gamepad path -------------------------------------------------

    fun onMotionEvent(event: MotionEvent): Boolean {
        val source = event.source
        if (source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false

        val lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
        val ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
        val rx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z))
        val ry = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceIn(0f, 1f)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceIn(0f, 1f)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        _state.value = _state.value.copy(
            leftStickX = lx, leftStickY = ly,
            rightStickX = rx, rightStickY = ry,
            leftTrigger = lt, rightTrigger = rt,
            dpadLeft = hatX < -0.5f, dpadRight = hatX > 0.5f,
            dpadUp = hatY < -0.5f, dpadDown = hatY > 0.5f,
        )
        return true
    }

    fun onKeyEvent(keyCode: Int, pressed: Boolean): Boolean {
        val button = mapKeyCodeToButton(keyCode) ?: return false
        setButton(button, pressed)
        return true
    }

    private fun mapKeyCodeToButton(keyCode: Int): XInputButton? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> XInputButton.A
        KeyEvent.KEYCODE_BUTTON_B -> XInputButton.B
        KeyEvent.KEYCODE_BUTTON_X -> XInputButton.X
        KeyEvent.KEYCODE_BUTTON_Y -> XInputButton.Y
        KeyEvent.KEYCODE_BUTTON_L1 -> XInputButton.BUMPER_LEFT
        KeyEvent.KEYCODE_BUTTON_R1 -> XInputButton.BUMPER_RIGHT
        KeyEvent.KEYCODE_BUTTON_THUMBL -> XInputButton.STICK_CLICK_LEFT
        KeyEvent.KEYCODE_BUTTON_THUMBR -> XInputButton.STICK_CLICK_RIGHT
        KeyEvent.KEYCODE_BUTTON_START -> XInputButton.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> XInputButton.MENU
        KeyEvent.KEYCODE_DPAD_UP -> XInputButton.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> XInputButton.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> XInputButton.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> XInputButton.DPAD_RIGHT
        else -> null
    }

    // --- Virtual touch controller path ------------------------------------------

    fun setLeftStick(x: Float, y: Float) {
        _state.value = _state.value.copy(leftStickX = applyDeadzone(x), leftStickY = applyDeadzone(y))
    }

    fun setRightStick(x: Float, y: Float) {
        _state.value = _state.value.copy(rightStickX = applyDeadzone(x), rightStickY = applyDeadzone(y))
    }

    fun setTrigger(left: Boolean, value: Float) {
        _state.value = if (left) {
            _state.value.copy(leftTrigger = value.coerceIn(0f, 1f))
        } else {
            _state.value.copy(rightTrigger = value.coerceIn(0f, 1f))
        }
    }

    fun setButton(button: XInputButton, pressed: Boolean) {
        val s = _state.value
        _state.value = when (button) {
            XInputButton.A -> s.copy(buttonA = pressed)
            XInputButton.B -> s.copy(buttonB = pressed)
            XInputButton.X -> s.copy(buttonX = pressed)
            XInputButton.Y -> s.copy(buttonY = pressed)
            XInputButton.BUMPER_LEFT -> s.copy(bumperLeft = pressed)
            XInputButton.BUMPER_RIGHT -> s.copy(bumperRight = pressed)
            XInputButton.STICK_CLICK_LEFT -> s.copy(stickClickLeft = pressed)
            XInputButton.STICK_CLICK_RIGHT -> s.copy(stickClickRight = pressed)
            XInputButton.START -> s.copy(buttonStart = pressed)
            XInputButton.MENU -> s.copy(buttonMenu = pressed)
            XInputButton.DPAD_UP -> s.copy(dpadUp = pressed)
            XInputButton.DPAD_DOWN -> s.copy(dpadDown = pressed)
            XInputButton.DPAD_LEFT -> s.copy(dpadLeft = pressed)
            XInputButton.DPAD_RIGHT -> s.copy(dpadRight = pressed)
        }
    }

    private fun applyDeadzone(value: Float): Float = if (abs(value) < deadzone) 0f else value

    companion object {
        fun isGamepadDevice(device: InputDevice?): Boolean {
            if (device == null) return false
            val sources = device.sources
            return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }
    }
}
