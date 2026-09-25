package com.winlauncher.app.domain.controller

/** Immutable snapshot of controller state, XInput-shaped regardless of source. */
data class XInputState(
    val leftStickX: Float = 0f,
    val leftStickY: Float = 0f,
    val rightStickX: Float = 0f,
    val rightStickY: Float = 0f,
    val leftTrigger: Float = 0f,   // 0f..1f
    val rightTrigger: Float = 0f,  // 0f..1f

    val buttonA: Boolean = false,
    val buttonB: Boolean = false,
    val buttonX: Boolean = false,
    val buttonY: Boolean = false,
    val bumperLeft: Boolean = false,
    val bumperRight: Boolean = false,
    val stickClickLeft: Boolean = false,
    val stickClickRight: Boolean = false,
    val buttonStart: Boolean = false,
    val buttonMenu: Boolean = false,
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
)

enum class XInputButton {
    A, B, X, Y, BUMPER_LEFT, BUMPER_RIGHT, STICK_CLICK_LEFT, STICK_CLICK_RIGHT,
    START, MENU, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
}
